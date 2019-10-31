package net.postchain.rell.parser

import net.postchain.rell.model.*
import net.postchain.rell.sql.SqlConstants
import net.postchain.rell.toImmList
import net.postchain.rell.toImmMap
import org.apache.commons.collections4.SetUtils

class C_AppContext(val globalCtx: C_GlobalContext, controller: C_CompilerController) {
    private val controller = controller

    val executor = controller.executor
    val defsBuilder = C_AppDefsBuilder(executor)
    val sysDefs = C_SystemDefs.create(executor, defsBuilder)

    private val modules = mutableListOf<C_CompiledModule>()

    private val externalChainsRoot = R_ExternalChainsRoot()
    private val externalChains = mutableMapOf<String, C_ExternalChain>()

    fun addExternalChain(name: S_String): C_ExternalChain {
        executor.checkPass(C_CompilerPass.DEFINITIONS)
        return externalChains.computeIfAbsent(name.str) { createExternalChain(name) }
    }

    private fun createExternalChain(name: S_String): C_ExternalChain {
        val ref = R_ExternalChainRef(externalChainsRoot, name.str, externalChains.size)

        val blockClass = C_Utils.createBlockClass(executor, ref)
        val transactionClass = C_Utils.createTransactionClass(executor, ref, blockClass)

        val sysClasses = listOf(blockClass, transactionClass)

        val mntBuilder = C_MountTablesBuilder()
        for (cls in sysClasses) {
            defsBuilder.classes.add(C_Class(null, cls))
            mntBuilder.addClass(null, cls)
        }

        val mntTables = mntBuilder.build()

        return C_ExternalChain(name, ref, blockClass, transactionClass, mntTables)
    }

    fun addModule(module: C_CompiledModule) {
        executor.checkPass(C_CompilerPass.NAMESPACES)
        modules.add(module)
    }

    private var createAppCalled = false

    fun createApp(): R_App {
        check(!createAppCalled)
        createAppCalled = true

        executor.onPass(C_CompilerPass.RECORDS) {
            val appRecords = defsBuilder.records.build()
            processRecords(appRecords)
        }

        controller.run()

        processAppMountsConflicts()

        val appClasses = defsBuilder.classes.build()
        val appObjects = defsBuilder.objects.build()
        val appOperations = defsBuilder.operations.build()
        val appQueries = defsBuilder.queries.build()

        val topologicalClasses = calcTopologicalClasses(appClasses)

        val appOperationsMap = routinesToMap(appOperations)
        val appQueriesMap = routinesToMap(appQueries)

        val valid = !globalCtx.messages().any { !it.type.ignorable }

        return R_App(
                valid = valid,
                modules = modules.map { it.rModule },
                classes = appClasses.map { it.cls },
                objects = appObjects,
                operations = appOperationsMap,
                queries = appQueriesMap,
                topologicalClasses = topologicalClasses,
                externalChainsRoot = externalChainsRoot,
                externalChains = externalChains.values.map { it.ref }
        )
    }

    private fun processRecords(records: List<R_Record>) {
        val structure = C_RecordUtils.buildRecordsStructure(records)
        val graph = structure.graph
        val transGraph = C_GraphUtils.transpose(graph)

        val cyclicRecs = C_GraphUtils.findCyclicVertices(graph).toSet()
        val infiniteRecs = C_GraphUtils.closure(transGraph, cyclicRecs).toSet()
        val mutableRecs = C_GraphUtils.closure(transGraph, structure.mutable).toSet()
        val nonVirtualableRecs = C_GraphUtils.closure(transGraph, structure.nonVirtualable).toSet()
        val nonGtvFromRecs = C_GraphUtils.closure(transGraph, structure.nonGtvFrom).toSet()
        val nonGtvToRecs = C_GraphUtils.closure(transGraph, structure.nonGtvTo).toSet()

        for (record in records) {
            val gtv = R_GtvCompatibility(record !in nonGtvFromRecs, record !in nonGtvToRecs)
            val typeFlags = R_TypeFlags(record in mutableRecs, gtv, record !in nonVirtualableRecs)
            val flags = R_RecordFlags(typeFlags, record in cyclicRecs, record in infiniteRecs)
            record.setFlags(flags)
        }
    }

    private fun processAppMountsConflicts() {
        val builder = C_MountTablesBuilder()
        builder.add(sysDefs.mntTables)
        for (extChain in externalChains.values) {
            builder.add(extChain.mntTables)
        }

        for (module in modules) {
            builder.add(module.content.mntTables)
        }

        val tables = builder.build()
        C_MntEntry.processMountConflicts(globalCtx, tables)
    }

    private fun calcTopologicalClasses(classes: List<C_Class>): List<R_Class> {
        val graph = mutableMapOf<R_Class, Collection<R_Class>>()
        for (cls in classes) {
            val deps = mutableSetOf<R_Class>()
            for (attr in cls.cls.attributes.values) {
                if (attr.type is R_ClassType) {
                    deps.add(attr.type.rClass)
                }
            }
            graph[cls.cls] = deps
        }

        val classToPos = classes.filter { it.defPos != null }.map { Pair(it.cls, it.defPos!!) }.toMap()

        val cycles = C_GraphUtils.findCycles(graph)
        if (!cycles.isEmpty()) {
            val cycle = cycles[0]
            val shortStr = cycle.joinToString(",") { it.appLevelName }
            val str = cycle.joinToString { it.appLevelName }
            val cls = cycle[0]
            val pos = classToPos[cls]
            check(pos != null) { cls.appLevelName }
            throw C_Error(pos, "class_cycle:$shortStr", "Class cycle, not allowed: $str")
        }

        val res = C_GraphUtils.topologicalSort(graph)
        return res
    }

    private fun <T: R_MountedRoutine> routinesToMap(list: List<T>): Map<R_MountName, T> {
        val res = mutableMapOf<R_MountName, T>()
        for (r in list) {
            val name = r.mountName
            if (name !in res) res[name] = r
        }
        return res.toImmMap()
    }
}

class C_AppDefsTableBuilder<T, K>(private val executor: C_CompilerExecutor, private val keyGetter: (T) -> K) {
    private val keys: MutableSet<K> = SetUtils.newIdentityHashSet()
    private val defs = mutableListOf<T>()
    private var completed = false

    fun add(def: T) {
        executor.checkPass(C_CompilerPass.DEFINITIONS)
        val k = keyGetter(def)
        check(keys.add(k)) { "Duplicate def: $def" }
        defs.add(def)
    }

    fun build(): List<T> {
        check(!completed)
        completed = true
        return defs.toImmList()
    }
}

class C_AppDefsBuilder(executor: C_CompilerExecutor) {
    val classes = C_AppDefsTableBuilder<C_Class, R_Class>(executor) { it.cls }
    val objects = C_AppDefsTableBuilder<R_Object, R_Object>(executor) { it }
    val records = C_AppDefsTableBuilder<R_Record, R_Record>(executor) { it }
    val operations = C_AppDefsTableBuilder<R_Operation, R_Operation>(executor) { it }
    val queries = C_AppDefsTableBuilder<R_Query, R_Query>(executor) { it }
}

private class C_NameConflictsProcessor_MntEntry(private val chain: String?): C_NameConflictsProcessor<R_MountName, C_MntEntry>() {
    override fun isSystemEntry(entry: C_MntEntry) = entry.pos == null
    override fun getConflictableKey(entry: C_MntEntry) = entry.mountName

    override fun handleConflict(globalCtx: C_GlobalContext, entry: C_MntEntry, otherEntry: C_MntEntry) {
        if (entry.def.appLevelName == otherEntry.def.appLevelName) {
            // Same module and same qualified name -> must be also a name conflict error.
            return
        }

        val error = C_Errors.errMountConflict(chain, entry.mountName, entry.def, entry.pos!!, otherEntry)
        globalCtx.error(error)
    }
}

class C_MntEntry(val type: C_DefType, val def: R_Definition, val pos: S_Pos?, val mountName: R_MountName) {
    companion object {
        private val SYSTEM_MOUNT_NAMES = SqlConstants.SYSTEM_OBJECTS.map { R_MountName.of(it) }
        private val SYSTEM_MOUNT_PREFIX = R_MountName.of("sys")

        fun processMountConflicts(globalCtx: C_GlobalContext, mntTables: C_MountTables): C_MountTables {
            val resChains = mntTables.chains.mapValues { (chain, t) ->
                val nullableChain = if (chain == "") null else chain

                val classes = processSystemMountConflicts(globalCtx, t.classes)

                C_ChainMountTables(
                        processMountConflicts0(globalCtx, nullableChain, classes),
                        processMountConflicts0(globalCtx, nullableChain, t.operations),
                        processMountConflicts0(globalCtx, nullableChain, t.queries)
                )
            }
            return C_MountTables(resChains)
        }

        private fun processMountConflicts0(globalCtx: C_GlobalContext, chain: String?, mntTable: C_MntTable): C_MntTable {
            val processor = C_NameConflictsProcessor_MntEntry(chain)
            val resEntries = processor.processConflicts(globalCtx, mntTable.entries, mutableSetOf())
            return C_MntTable(resEntries)
        }

        private fun processSystemMountConflicts(globalCtx: C_GlobalContext, mntTable: C_MntTable): C_MntTable {
            val b = C_MntTableBuilder()

            for (entry in mntTable.entries) {
                if (entry.pos != null && (entry.mountName in SYSTEM_MOUNT_NAMES || entry.mountName.startsWith(SYSTEM_MOUNT_PREFIX))) {
                    globalCtx.error(C_Errors.errMountConflictSystem(entry.mountName, entry.def, entry.pos))
                } else {
                    b.add(entry)
                }
            }

            return b.build()
        }
    }
}

class C_MountTablesBuilder {
    private val chains = mutableMapOf<String, C_ChainMountTablesBuilder>()

    fun add(tables: C_MountTables) {
        for ((chain, t) in tables.chains) {
            val b = chainBuilder(chain)
            b.classes.add(t.classes)
            b.operations.add(t.operations)
            b.queries.add(t.queries)
        }
    }

    fun addClass(namePos: S_Pos?, rClass: R_Class) {
        addClass0(C_DefType.CLASS, namePos, rClass, rClass)
    }

    fun addObject(name: S_Name, rObject: R_Object) {
        addClass0(C_DefType.OBJECT, name.pos, rObject, rObject.rClass)
    }

    private fun addClass0(type: C_DefType, namePos: S_Pos?, def: R_Definition, rClass: R_Class) {
        val chain = rClass.external?.chain?.name ?: ""
        val b = chainBuilder(chain)
        b.classes.add(type, def, namePos, rClass.mountName)
    }

    fun addOperation(name: S_Name, o: R_Operation) {
        val b = chainBuilder("")
        b.operations.add(C_DefType.OPERATION, o, name.pos, o.mountName)
    }

    fun addQuery(name: S_Name, q: R_Query) {
        val b = chainBuilder("")
        b.queries.add(C_DefType.QUERY, q, name.pos, q.mountName)
    }

    private fun chainBuilder(chain: String) = chains.computeIfAbsent(chain) { C_ChainMountTablesBuilder() }

    fun build(): C_MountTables {
        val resChains = chains.mapValues { (_, v) ->
            C_ChainMountTables(v.classes.build(), v.operations.build(), v.queries.build())
        }
        return C_MountTables(resChains)
    }
}

class C_MountTables(chains: Map<String, C_ChainMountTables>) {
    val chains = chains.toImmMap()

    companion object {
        val EMPTY = C_MountTables(mapOf())
    }
}

class C_ChainMountTables(val classes: C_MntTable, val operations: C_MntTable, val queries: C_MntTable)

class C_ChainMountTablesBuilder {
    val classes = C_MntTableBuilder()
    val operations = C_MntTableBuilder()
    val queries = C_MntTableBuilder()
}

class C_MntTableBuilder {
    private val entries = mutableListOf<C_MntEntry>()
    private var finished = false

    fun add(table: C_MntTable) {
        check(!finished)
        entries.addAll(table.entries)
    }

    fun add(entry: C_MntEntry) {
        check(!finished)
        entries.add(entry)
    }

    fun add(type: C_DefType, def: R_Definition, namePos: S_Pos?, mountName: R_MountName) {
        check(!finished)
        entries.add(C_MntEntry(type, def, namePos, mountName))
    }

    fun build(): C_MntTable {
        check(!finished)
        finished = true
        return C_MntTable(entries)
    }
}

class C_MntTable(entries: List<C_MntEntry>) {
    val entries = entries.toImmList()

    companion object {
        val EMPTY = C_MntTable(listOf())
    }
}
