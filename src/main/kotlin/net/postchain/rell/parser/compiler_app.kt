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

        val blockEntity = C_Utils.createBlockEntity(executor, ref)
        val transactionEntity = C_Utils.createTransactionEntity(executor, ref, blockEntity)

        val sysEntities = listOf(blockEntity, transactionEntity)

        val mntBuilder = C_MountTablesBuilder()
        for (cls in sysEntities) {
            defsBuilder.entities.add(C_Entity(null, cls))
            mntBuilder.addEntity(null, cls)
        }

        val mntTables = mntBuilder.build()

        return C_ExternalChain(name, ref, blockEntity, transactionEntity, mntTables)
    }

    fun addModule(module: C_CompiledModule) {
        executor.checkPass(C_CompilerPass.NAMESPACES)
        modules.add(module)
    }

    private var createAppCalled = false

    fun createApp(): R_App {
        check(!createAppCalled)
        createAppCalled = true

        executor.onPass(C_CompilerPass.STRUCTS) {
            val appStructs = defsBuilder.structs.build()
            processStructs(appStructs)
        }

        controller.run()

        processAppMountsConflicts()

        val appEntities = defsBuilder.entities.build()
        val appObjects = defsBuilder.objects.build()
        val appOperations = defsBuilder.operations.build()
        val appQueries = defsBuilder.queries.build()

        val topologicalEntities = calcTopologicalEntities(appEntities)

        val appOperationsMap = routinesToMap(appOperations)
        val appQueriesMap = routinesToMap(appQueries)

        val valid = !globalCtx.messages().any { !it.type.ignorable }

        return R_App(
                valid = valid,
                modules = modules.map { it.rModule },
                entities = appEntities.map { it.cls },
                objects = appObjects,
                operations = appOperationsMap,
                queries = appQueriesMap,
                topologicalEntities = topologicalEntities,
                externalChainsRoot = externalChainsRoot,
                externalChains = externalChains.values.map { it.ref }
        )
    }

    private fun processStructs(structs: List<R_Struct>) {
        val info = C_StructUtils.buildStructsInfo(structs)
        val graph = info.graph
        val transGraph = C_GraphUtils.transpose(graph)

        val cyclicStructs = C_GraphUtils.findCyclicVertices(graph).toSet()
        val infiniteStructs = C_GraphUtils.closure(transGraph, cyclicStructs).toSet()
        val mutableStructs = C_GraphUtils.closure(transGraph, info.mutable).toSet()
        val nonVirtualableStructs = C_GraphUtils.closure(transGraph, info.nonVirtualable).toSet()
        val nonGtvFromStructs = C_GraphUtils.closure(transGraph, info.nonGtvFrom).toSet()
        val nonGtvToStructs = C_GraphUtils.closure(transGraph, info.nonGtvTo).toSet()

        for (struct in structs) {
            val gtv = R_GtvCompatibility(struct !in nonGtvFromStructs, struct !in nonGtvToStructs)
            val typeFlags = R_TypeFlags(struct in mutableStructs, gtv, struct !in nonVirtualableStructs)
            val flags = R_StructFlags(typeFlags, struct in cyclicStructs, struct in infiniteStructs)
            struct.setFlags(flags)
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

    private fun calcTopologicalEntities(entities: List<C_Entity>): List<R_Entity> {
        val graph = mutableMapOf<R_Entity, Collection<R_Entity>>()
        for (cls in entities) {
            val deps = mutableSetOf<R_Entity>()
            for (attr in cls.cls.attributes.values) {
                if (attr.type is R_EntityType) {
                    deps.add(attr.type.rEntity)
                }
            }
            graph[cls.cls] = deps
        }

        val entityToPos = entities.filter { it.defPos != null }.map { Pair(it.cls, it.defPos!!) }.toMap()

        val cycles = C_GraphUtils.findCycles(graph)
        if (!cycles.isEmpty()) {
            val cycle = cycles[0]
            val shortStr = cycle.joinToString(",") { it.appLevelName }
            val str = cycle.joinToString { it.appLevelName }
            val cls = cycle[0]
            val pos = entityToPos[cls]
            check(pos != null) { cls.appLevelName }
            throw C_Error(pos, "entity_cycle:$shortStr", "Entity cycle, not allowed: $str")
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
    val entities = C_AppDefsTableBuilder<C_Entity, R_Entity>(executor) { it.cls }
    val objects = C_AppDefsTableBuilder<R_Object, R_Object>(executor) { it }
    val structs = C_AppDefsTableBuilder<R_Struct, R_Struct>(executor) { it }
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

class C_MntEntry(val type: C_DeclarationType, val def: R_Definition, val pos: S_Pos?, val mountName: R_MountName) {
    companion object {
        private val SYSTEM_MOUNT_NAMES = SqlConstants.SYSTEM_OBJECTS.map { R_MountName.of(it) }
        private val SYSTEM_MOUNT_PREFIX = R_MountName.of("sys")

        fun processMountConflicts(globalCtx: C_GlobalContext, mntTables: C_MountTables): C_MountTables {
            val resChains = mntTables.chains.mapValues { (chain, t) ->
                val nullableChain = if (chain == "") null else chain

                val entities = processSystemMountConflicts(globalCtx, t.entities)

                C_ChainMountTables(
                        processMountConflicts0(globalCtx, nullableChain, entities),
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
            b.entities.add(t.entities)
            b.operations.add(t.operations)
            b.queries.add(t.queries)
        }
    }

    fun addEntity(namePos: S_Pos?, rEntity: R_Entity) {
        addEntity0(C_DeclarationType.ENTITY, namePos, rEntity, rEntity)
    }

    fun addObject(name: S_Name, rObject: R_Object) {
        addEntity0(C_DeclarationType.OBJECT, name.pos, rObject, rObject.rEntity)
    }

    private fun addEntity0(type: C_DeclarationType, namePos: S_Pos?, def: R_Definition, rEntity: R_Entity) {
        val chain = rEntity.external?.chain?.name ?: ""
        val b = chainBuilder(chain)
        b.entities.add(type, def, namePos, rEntity.mountName)
    }

    fun addOperation(name: S_Name, o: R_Operation) {
        val b = chainBuilder("")
        b.operations.add(C_DeclarationType.OPERATION, o, name.pos, o.mountName)
    }

    fun addQuery(name: S_Name, q: R_Query) {
        val b = chainBuilder("")
        b.queries.add(C_DeclarationType.QUERY, q, name.pos, q.mountName)
    }

    private fun chainBuilder(chain: String) = chains.computeIfAbsent(chain) { C_ChainMountTablesBuilder() }

    fun build(): C_MountTables {
        val resChains = chains.mapValues { (_, v) ->
            C_ChainMountTables(v.entities.build(), v.operations.build(), v.queries.build())
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

class C_ChainMountTables(val entities: C_MntTable, val operations: C_MntTable, val queries: C_MntTable)

class C_ChainMountTablesBuilder {
    val entities = C_MntTableBuilder()
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

    fun add(type: C_DeclarationType, def: R_Definition, namePos: S_Pos?, mountName: R_MountName) {
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
