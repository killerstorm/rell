package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.multiMapOf
import net.postchain.rell.sql.SqlConstants
import net.postchain.rell.toImmList
import net.postchain.rell.toImmMap
import org.apache.commons.collections4.SetUtils

class C_AppContext(val globalCtx: C_GlobalContext, controller: C_CompilerController) {
    private val controller = controller

    val executor = controller.executor
    val defsBuilder = C_AppDefsBuilder(executor)
    val sysDefs = C_SystemDefs.create(executor, defsBuilder)

    private val nsAssembler = C_NsAsm_AppAssembler.create(globalCtx)
    private val modules = C_ListBuilder<C_AppModule>()

    private val externalChainsRoot = R_ExternalChainsRoot()
    private val externalChains = mutableMapOf<String, C_ExternalChain>()

    fun createModuleNsAssembler(moduleKey: C_ModuleKey, sysDefs: C_SystemDefs, exportSysEntities: Boolean): C_NsAsm_ModuleAssembler {
        return nsAssembler.addModule(moduleKey, sysDefs.nsProto, exportSysEntities)
    }

    fun addExternalChain(name: String): C_ExternalChain {
        executor.checkPass(C_CompilerPass.DEFINITIONS)
        return externalChains.computeIfAbsent(name) { createExternalChain(name) }
    }

    private fun createExternalChain(name: String): C_ExternalChain {
        val ref = R_ExternalChainRef(externalChainsRoot, name, externalChains.size)

        val blockEntity = C_Utils.createBlockEntity(executor, ref)
        val transactionEntity = C_Utils.createTransactionEntity(executor, ref, blockEntity)

        val sysDefs = C_SystemDefs.create(defsBuilder, blockEntity, transactionEntity, listOf())
        return C_ExternalChain(name, ref, sysDefs)
    }

    fun addModule(module: C_Module, compiled: C_CompiledModule) {
        executor.checkPass(C_CompilerPass.MODULES)
        modules.add(C_AppModule(module, compiled.rModule, compiled.contents))
    }

    private var createAppCalled = false

    fun createApp(): R_App {
        check(!createAppCalled)
        createAppCalled = true

        executor.onPass(C_CompilerPass.NAMESPACES) {
            nsAssembler.assemble()
        }

        executor.onPass(C_CompilerPass.ABSTRACT) {
            val mods = modules.commit().map { it.module }
            C_AbstractCompiler.compile(globalCtx, mods)
        }

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
                modules = modules.commit().map { it.rModule },
                entities = appEntities.map { it.entity },
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
            builder.add(extChain.sysDefs.mntTables)
        }

        for (module in modules.commit()) {
            builder.add(module.contents.mntTables)
        }

        val tables = builder.build()
        C_MntEntry.processMountConflicts(globalCtx, tables)
    }

    private fun calcTopologicalEntities(entities: List<C_Entity>): List<R_Entity> {
        val graph = mutableMapOf<R_Entity, Collection<R_Entity>>()
        for (entity in entities) {
            val deps = mutableSetOf<R_Entity>()
            for (attr in entity.entity.attributes.values) {
                if (attr.type is R_EntityType) {
                    deps.add(attr.type.rEntity)
                }
            }
            graph[entity.entity] = deps
        }

        val entityToPos = entities.filter { it.defPos != null }.map { Pair(it.entity, it.defPos!!) }.toMap()

        val cycles = C_GraphUtils.findCycles(graph)
        if (!cycles.isEmpty()) {
            val cycle = cycles[0]
            val shortStr = cycle.joinToString(",") { it.appLevelName }
            val str = cycle.joinToString { it.appLevelName }
            val entity = cycle[0]
            val pos = entityToPos[entity]
            check(pos != null) { entity.appLevelName }
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

    private class C_AppModule(val module: C_Module, val rModule: R_Module, val contents: C_ModuleContents)
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
    val entities = C_AppDefsTableBuilder<C_Entity, R_Entity>(executor) { it.entity }
    val objects = C_AppDefsTableBuilder<R_Object, R_Object>(executor) { it }
    val structs = C_AppDefsTableBuilder<R_Struct, R_Struct>(executor) { it }
    val operations = C_AppDefsTableBuilder<R_Operation, R_Operation>(executor) { it }
    val queries = C_AppDefsTableBuilder<R_Query, R_Query>(executor) { it }
}

private class C_MountConflictsProcessor(private val chain: String?) {
    fun processConflicts(
            globalCtx: C_GlobalContext,
            allEntries: List<C_MntEntry>,
            errorsEntries: MutableSet<C_MntEntry>
    ): List<C_MntEntry> {
        val map = multiMapOf<R_MountName, C_MntEntry>()
        for (entry in allEntries) {
            map.put(entry.mountName, entry)
        }

        val res = mutableListOf<C_MntEntry>()

        for (name in map.keySet()) {
            val entries = map.get(name)
            val (sysEntries, userEntries) = entries.partition { it.pos == null }

            if (entries.size > 1) {
                for (entry in userEntries) {
                    if (errorsEntries.add(entry)) {
                        val otherEntry = entries.first { it !== entry }
                        handleConflict(globalCtx, entry, otherEntry)
                    }
                }
            }

            res.addAll(sysEntries)
            if (sysEntries.isEmpty() && !userEntries.isEmpty()) {
                res.add(userEntries[0])
            }
        }

        return res.toImmList()
    }

    private fun handleConflict(globalCtx: C_GlobalContext, entry: C_MntEntry, otherEntry: C_MntEntry) {
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
            val processor = C_MountConflictsProcessor(chain)
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

    fun addQuery(q: R_Query) {
        val b = chainBuilder("")
        b.queries.add(C_DeclarationType.QUERY, q, null, q.mountName)
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
