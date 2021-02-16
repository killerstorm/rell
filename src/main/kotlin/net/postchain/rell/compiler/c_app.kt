/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.model.*
import net.postchain.rell.utils.putAllAbsent
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap
import net.postchain.rell.utils.toImmSet
import org.apache.commons.collections4.SetUtils
import java.util.*
import java.util.concurrent.atomic.AtomicLong

private object C_InternalAppUtils {
    private val AT_EXPR_ID_SEQ = AtomicLong(0)
    private val AT_ENTITY_ID_SEQ = AtomicLong(0)

    fun nextAtExprId(): R_AtExprId {
        val id = AT_EXPR_ID_SEQ.getAndIncrement()
        return R_AtExprId(id)
    }

    fun nextAtEntityId(exprId: R_AtExprId): R_AtEntityId {
        val id = AT_ENTITY_ID_SEQ.getAndIncrement()
        return R_AtEntityId(exprId, id)
    }
}

class C_AppContext(
        val msgCtx: C_MessageContext,
        val executor: C_CompilerExecutor,
        val repl: Boolean,
        private val oldReplState: C_ReplAppState
) {
    val globalCtx = msgCtx.globalCtx

    val appUid = C_GlobalContext.nextAppUid()
    private val containerUidGen = C_UidGen { id, name -> R_ContainerUid(id, name, appUid) }

    private val defsBuilder = C_AppDefsBuilder(executor)
    val defsAdder: C_AppDefsAdder = defsBuilder

    val sysDefs = oldReplState.sysDefs ?: C_SystemDefs.create(this, appUid)

    val functionReturnTypeCalculator = C_FunctionBody.createReturnTypeCalculator()

    private val appDefsLate = C_LateInit(C_CompilerPass.APPDEFS, C_AppDefs.EMPTY)

    private val nsAssembler = C_NsAsm_AppAssembler.create(msgCtx, appUid, oldReplState.modules)
    private val modulesBuilder = C_ListBuilder<C_AppModule>()
    private val extraMountTables = C_ListBuilder<C_MountTables>()

    private val externalChainsRoot = R_ExternalChainsRoot()
    private val externalChains = mutableMapOf<String, C_ExternalChain>()

    private val appLate = C_LateInit(C_CompilerPass.APPLICATION, Optional.empty<R_App>())
    private val nsAsmAppLate = C_LateInit(C_CompilerPass.NAMESPACES, C_NsAsm_App.EMPTY)
    private val newReplStateLate = C_LateInit(C_CompilerPass.APPLICATION, C_ReplAppState.EMPTY)

    init {
        extraMountTables.add(oldReplState.mntTables)

        executor.onPass(C_CompilerPass.NAMESPACES) {
            val asmApp = nsAssembler.assemble()
            nsAsmAppLate.set(asmApp)
        }

        executor.onPass(C_CompilerPass.ABSTRACT) {
            val mods = modulesBuilder.commit().map { it.module }
            C_AbstractCompiler.compile(msgCtx, mods)
        }

        executor.onPass(C_CompilerPass.APPDEFS) {
            val appDefs = defsBuilder.build()
            appDefsLate.set(appDefs)
            processStructs(appDefs.structs)
        }

        executor.onPass(C_CompilerPass.APPLICATION) {
            val app = createApp()
            createNewReplState(app)
        }
    }

    fun nextContainerUid(name: String) = containerUidGen.next(name)

    fun nextAtExprId() = C_InternalAppUtils.nextAtExprId()
    fun nextAtEntityId(exprId: R_AtExprId) = C_InternalAppUtils.nextAtEntityId(exprId)

    fun getApp(): R_App? {
        executor.checkPass(C_CompilerPass.FINISH)
        val opt = appLate.get()
        return opt.orElse(null)
    }

    fun getNewReplState() = newReplStateLate.get()

    fun createModuleNsAssembler(moduleKey: C_ModuleKey, sysDefs: C_SystemDefs, exportSysEntities: Boolean): C_NsAsm_ModuleAssembler {
        return nsAssembler.addModule(moduleKey, sysDefs.nsProto, exportSysEntities)
    }

    fun createReplNsAssembler(linkedModule: C_ModuleKey?): C_NsAsm_ReplAssembler {
        return nsAssembler.addRepl(sysDefs.nsProto, linkedModule, oldReplState.nsAsmState)
    }

    fun addExternalChain(name: String): C_ExternalChain {
        executor.checkPass(C_CompilerPass.DEFINITIONS)
        return externalChains.computeIfAbsent(name) { createExternalChain(name) }
    }

    private fun createExternalChain(name: String): C_ExternalChain {
        val ref = R_ExternalChainRef(externalChainsRoot, name, externalChains.size)

        val blockEntity = C_Utils.createBlockEntity(this, ref)
        val transactionEntity = C_Utils.createTransactionEntity(this, ref, blockEntity)

        val extSysDefs = C_SystemDefs.create(appUid, blockEntity, transactionEntity, listOf())
        return C_ExternalChain(name, ref, extSysDefs)
    }

    fun addModule(module: C_ModuleDescriptor, compiled: C_CompiledModule) {
        executor.checkPass(C_CompilerPass.MODULES)
        modulesBuilder.add(C_AppModule(module, compiled.rModule, compiled.contents.mntTables))
    }

    fun addExtraMountTables(mntTables: C_MountTables) {
        executor.checkPass(C_CompilerPass.MODULES)
        extraMountTables.add(mntTables)
    }

    private fun createApp(): R_App {
        val appDefs = appDefsLate.get()
        val topologicalEntities = calcTopologicalEntities(appDefs.entities)

        val appOperationsMap = routinesToMap(appDefs.operations)
        val appQueriesMap = routinesToMap(sysDefs.queries + appDefs.queries)

        val valid = !msgCtx.messages().any { !it.type.ignorable }
        val modules = modulesBuilder.commit()

        val oldSqlDefs = oldReplState.sqlDefs
        val sqlDefs = R_AppSqlDefs(
                entities = oldSqlDefs.entities + appDefs.entities.map { it.entity },
                objects = oldSqlDefs.objects + appDefs.objects,
                topologicalEntities = oldSqlDefs.topologicalEntities + topologicalEntities
        )

        val rApp = R_App(
                valid = valid,
                uid = appUid,
                modules = modules.map { it.rModule },
                operations = appOperationsMap,
                queries = appQueriesMap,
                externalChainsRoot = externalChainsRoot,
                externalChains = externalChains.values.map { it.ref },
                sqlDefs = sqlDefs
        )
        appLate.set(Optional.of(rApp))

        return rApp
    }

    private fun createNewReplState(app: R_App) {
        val mntTables = createAppMounts()

        val asmApp = nsAsmAppLate.get()

        val modules = modulesBuilder.commit()

        val newPrecompiledModules = modules.map { module ->
                    val ns = asmApp.modules[module.module.key]
                    if (ns == null) null else {
                        val preModule = C_PrecompiledModule(module.module, ns)
                        Pair(module.module.key, preModule)
                    }
                }.filterNotNull()
                .toMap()
                .toImmMap()

        val stateModules = oldReplState.modules.toMutableMap()
        stateModules.putAllAbsent(newPrecompiledModules)

        val newReplState = C_ReplAppState(asmApp.newReplState, stateModules, sysDefs, app.sqlDefs, mntTables)
        newReplStateLate.set(newReplState)
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

    private fun createAppMounts(): C_MountTables {
        val builder = C_MountTablesBuilder(appUid)
        builder.add(sysDefs.mntTables)
        for (extChain in externalChains.values) {
            builder.add(extChain.sysDefs.mntTables)
        }

        for (module in modulesBuilder.commit()) {
            builder.add(module.mntTables)
        }

        for (mntTables in extraMountTables.commit()) {
            builder.add(mntTables)
        }

        val tables = builder.build()
        return C_MntEntry.processMountConflicts(msgCtx, appUid, tables)
    }

    private fun calcTopologicalEntities(entities: List<C_Entity>): List<R_EntityDefinition> {
        val declaredEntities = entities.map { it.entity }.toImmSet()
        val graph = mutableMapOf<R_EntityDefinition, Collection<R_EntityDefinition>>()

        for (entity in entities) {
            val deps = mutableSetOf<R_EntityDefinition>()
            for (attr in entity.entity.attributes.values) {
                if (attr.type is R_EntityType && attr.type.rEntity in declaredEntities) {
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
            throw C_Error.stop(pos, "entity_cycle:$shortStr", "Entity cycle, not allowed: $str")
        }

        val res = C_GraphUtils.topologicalSort(graph)
        return res
    }

    private fun <T: R_MountedRoutineDefinition> routinesToMap(list: List<T>): Map<R_MountName, T> {
        val res = mutableMapOf<R_MountName, T>()
        for (r in list) {
            val name = r.mountName
            if (name !in res) res[name] = r
        }
        return res.toImmMap()
    }

    private class C_AppModule(val module: C_ModuleDescriptor, val rModule: R_Module, val mntTables: C_MountTables)
}

class C_AppDefs(
        entities: List<C_Entity>,
        objects: List<R_ObjectDefinition>,
        structs: List<R_Struct>,
        operations: List<R_OperationDefinition>,
        queries: List<R_QueryDefinition>
) {
    val entities = entities.toImmList()
    val objects = objects.toImmList()
    val structs = structs.toImmList()
    val operations = operations.toImmList()
    val queries = queries.toImmList()

    companion object { val EMPTY = C_AppDefs(listOf(), listOf(), listOf(), listOf(), listOf()) }
}

interface C_AppDefsAdder {
    fun addEntity(entity: C_Entity)
    fun addObject(obj: R_ObjectDefinition)
    fun addStruct(struct: R_Struct)
    fun addOperation(op: R_OperationDefinition)
    fun addQuery(q: R_QueryDefinition)
}

private class C_AppDefsBuilder(executor: C_CompilerExecutor): C_AppDefsAdder {
    private val entities = C_AppDefsTableBuilder<C_Entity, R_EntityDefinition>(executor) { it.entity }
    private val objects = C_AppDefsTableBuilder<R_ObjectDefinition, R_ObjectDefinition>(executor) { it }
    private val structs = C_AppDefsTableBuilder<R_Struct, R_Struct>(executor) { it }
    private val operations = C_AppDefsTableBuilder<R_OperationDefinition, R_OperationDefinition>(executor) { it }
    private val queries = C_AppDefsTableBuilder<R_QueryDefinition, R_QueryDefinition>(executor) { it }
    private var build = false

    override fun addEntity(entity: C_Entity) = add(entities, entity)
    override fun addObject(obj: R_ObjectDefinition) = add(objects, obj)
    override fun addStruct(struct: R_Struct) = add(structs, struct)
    override fun addOperation(op: R_OperationDefinition) = add(operations, op)
    override fun addQuery(q: R_QueryDefinition) = add(queries, q)

    private fun <T, K> add(table: C_AppDefsTableBuilder<T, K>, value: T) {
        check(!build)
        table.add(value)
    }

    fun build(): C_AppDefs {
        check(!build)
        build = true
        return C_AppDefs(
                entities.build(),
                objects.build(),
                structs.build(),
                operations.build(),
                queries.build()
        )
    }
}

private class C_AppDefsTableBuilder<T, K>(private val executor: C_CompilerExecutor, private val keyGetter: (T) -> K) {
    private val keys: MutableSet<K> = SetUtils.newIdentityHashSet()
    private val defs = mutableListOf<T>()
    private var build = false

    fun add(def: T) {
        executor.checkPass(C_CompilerPass.DEFINITIONS)
        val k = keyGetter(def)
        check(keys.add(k)) { "Duplicate def: $def" }
        defs.add(def)
    }

    fun build(): List<T> {
        check(!build)
        build = true
        return defs.toImmList()
    }
}
