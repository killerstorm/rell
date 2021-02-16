/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model

import net.postchain.gtv.Gtv
import net.postchain.rell.compiler.C_CompilerPass
import net.postchain.rell.compiler.C_LateGetter
import net.postchain.rell.compiler.C_LateInit
import net.postchain.rell.compiler.C_Utils
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap

class R_DefinitionNames(val module: String, val namespace: String?, val simpleName: String) {
    val qualifiedName = if (namespace == null) simpleName else "$namespace.$simpleName"
    val appLevelName = if (module.isEmpty()) qualifiedName else R_DefinitionId.appLevelName(module, qualifiedName)
    val defId = R_DefinitionId(module, qualifiedName)

    override fun toString() = appLevelName
}

abstract class R_Definition(val names: R_DefinitionNames, val initFrameGetter: C_LateGetter<R_CallFrame>) {
    val simpleName = names.simpleName
    val moduleLevelName = names.qualifiedName
    val appLevelName = names.appLevelName
    val defId = names.defId

    abstract fun toMetaGtv(): Gtv

    final override fun toString() = "${javaClass.simpleName}[$appLevelName]"
}

class R_ExternalChainsRoot
class R_ExternalChainRef(val root: R_ExternalChainsRoot, val name: String, val index: Int)

data class R_AppUid(val id: Long) {
    override fun toString() = "App[$id]"
}

data class R_ContainerUid(val id: Long, val name: String, val app: R_AppUid) {
    override fun toString(): String {
        val params = listOf(id.toString(), name).filter { it.isNotEmpty() }.joinToString(",")
        return "$app/Container[$params]"
    }
}

data class R_FnUid(val id: Long, val name: String, val container: R_ContainerUid) {
    override fun toString() = "$container/Fn[$id,$name]"
}

data class R_FrameBlockUid(val id: Long, val name: String, val fn: R_FnUid) {
    override fun toString() = "$fn/Block[$id,$name]"
}

data class R_AtExprId(val id: Long) {
    fun toRawString() = "$id"
    override fun toString() = "AtExpr[$id]"
}

data class R_AtEntityId(val exprId: R_AtExprId, val id: Long) {
    override fun toString() = "AtEntity[${exprId.toRawString()}:$id]"
}

class R_Attribute(
        val index: Int,
        val name: String,
        val type: R_Type,
        val mutable: Boolean,
        val canSetInCreate: Boolean = true,
        val sqlMapping: String = name,
        private val exprGetter: C_LateGetter<R_Expr>?
) {
    val expr: R_Expr? get() = exprGetter?.get()

    val hasExpr: Boolean get() = exprGetter != null

    fun toMetaGtv(): Gtv {
        return mapOf(
                "type" to type.toMetaGtv(),
                "mutable" to mutable.toGtv()
        ).toGtv()
    }

    fun copy(mutable: Boolean): R_Attribute {
        return R_Attribute(
                index = index,
                name = name,
                type = type,
                mutable = mutable,
                canSetInCreate = canSetInCreate,
                sqlMapping = sqlMapping,
                exprGetter = exprGetter
        )
    }

    override fun toString() = name
}

class R_Param(val name: String, val type: R_Type) {
    fun toMetaGtv(): Gtv = mapOf(
            "name" to name.toGtv(),
            "type" to type.toMetaGtv()
    ).toGtv()
}

class R_VarParam(val name: String, val type: R_Type, val ptr: R_VarPtr) {
    fun toParam() = R_Param(name, type)
}

sealed class R_RoutineDefinition(
        names: R_DefinitionNames,
        initFrameGetter: C_LateGetter<R_CallFrame>
): R_Definition(names, initFrameGetter) {
    abstract fun params(): List<R_Param>
    abstract fun call(rtFrame: Rt_CallFrame, args: List<Rt_Value>, callerFilePos: R_FilePos): Rt_Value
}

sealed class R_MountedRoutineDefinition(
        names: R_DefinitionNames,
        initFrameGetter: C_LateGetter<R_CallFrame>,
        val mountName: R_MountName
): R_RoutineDefinition(names, initFrameGetter)

class R_OperationDefinition(
        names: R_DefinitionNames,
        initFrameGetter: C_LateGetter<R_CallFrame>,
        mountName: R_MountName,
        val mirrorStructs: R_MirrorStructs
): R_MountedRoutineDefinition(names, initFrameGetter, mountName) {
    private val internals = C_LateInit(C_CompilerPass.EXPRESSIONS, ERROR_INTERNALS)

    fun setInternals(varParams: List<R_VarParam>, body: R_Statement, frame: R_CallFrame) {
        val params = varParams.map { it.toParam() }.toImmList()
        internals.set(Internals(params, varParams, body, frame))
    }

    override fun params() = internals.get().params

    fun call(exeCtx: Rt_ExecutionContext, args: List<Rt_Value>): Rt_Value? {
        val rtFrame = processCallArgs(exeCtx, args)
        execute(rtFrame)
        return null
    }

    override fun call(rtFrame: Rt_CallFrame, args: List<Rt_Value>, callerFilePos: R_FilePos): Rt_Value {
        throw Rt_Error("call:operation", "Calling operation is not allowed")
    }

    private fun processCallArgs(exeCtx: Rt_ExecutionContext, args: List<Rt_Value>): Rt_CallFrame {
        val ints = internals.get()

        val defCtx = Rt_DefinitionContext(exeCtx, true, defId)
        val rtFrame = ints.frame.createRtFrame(defCtx, null, null)

        checkCallArgs(this, ints.params, args)
        processArgs(ints.varParams, args, rtFrame)

        return rtFrame
    }

    private fun execute(rtFrame: Rt_CallFrame) {
        val ints = internals.get()
        val res = ints.body.execute(rtFrame)
        if (res != null) {
            check(res is R_StatementResult_Return && res.value == null)
        }
    }

    override fun toMetaGtv(): Gtv {
        return mapOf(
                "parameters" to params().map { it.toMetaGtv() }.toGtv()
        ).toGtv()
    }

    private class Internals(
            val params: List<R_Param>,
            val varParams: List<R_VarParam>,
            val body: R_Statement,
            val frame: R_CallFrame
    )

    companion object {
        private val ERROR_INTERNALS = Internals(
                params = listOf(),
                varParams = listOf(),
                body = C_Utils.ERROR_STATEMENT,
                frame = R_CallFrame.ERROR
        )
    }
}

sealed class R_QueryBody(
        val retType: R_Type,
        params: List<R_Param>
) {
    val params = params.toImmList()

    abstract fun call(defCtx: Rt_DefinitionContext, args: List<Rt_Value>, caller: Rt_FrameCaller?): Rt_Value
}

class R_UserQueryBody(
        retType: R_Type,
        varParams: List<R_VarParam>,
        private val body: R_Statement,
        private val frame: R_CallFrame
): R_QueryBody(retType, varParams.map { it.toParam() }) {
    private val varParams = varParams.toImmList()

    override fun call(defCtx: Rt_DefinitionContext, args: List<Rt_Value>, caller: Rt_FrameCaller?): Rt_Value {
        val rtFrame = frame.createRtFrame(defCtx, caller, null)

        processArgs(varParams, args, rtFrame)

        val res = body.execute(rtFrame)
        check(res is R_StatementResult_Return) { "${res?.javaClass?.name}" }

        check(res.value != null)
        return res.value
    }

    companion object {
        val ERROR: R_QueryBody = R_UserQueryBody(R_CtErrorType, listOf(), C_Utils.ERROR_STATEMENT, R_CallFrame.ERROR)
    }
}

class R_SysQueryBody(retType: R_Type, params: List<R_Param>, private val fn: R_SysFunction): R_QueryBody(retType, params) {
    override fun call(defCtx: Rt_DefinitionContext, args: List<Rt_Value>, caller: Rt_FrameCaller?): Rt_Value {
        val ctx = Rt_CallContext(defCtx)
        return fn.call(ctx, args)
    }
}

class R_QueryDefinition(
        names: R_DefinitionNames,
        initFrameGetter: C_LateGetter<R_CallFrame>,
        mountName: R_MountName
): R_MountedRoutineDefinition(names, initFrameGetter, mountName) {
    private val bodyLate = C_LateInit(C_CompilerPass.EXPRESSIONS, R_UserQueryBody.ERROR)

    fun setBody(body: R_QueryBody) {
        bodyLate.set(body)
    }

    fun type(): R_Type = bodyLate.get().retType
    override fun params() = bodyLate.get().params

    fun call(exeCtx: Rt_ExecutionContext, args: List<Rt_Value>): Rt_Value {
        val body = bodyLate.get()
        checkCallArgs(this, body.params, args)
        val defCtx = Rt_DefinitionContext(exeCtx, false, defId)
        val res = body.call(defCtx, args, null)
        return res
    }

    override fun call(rtFrame: Rt_CallFrame, args: List<Rt_Value>, callerFilePos: R_FilePos): Rt_Value {
        val body = bodyLate.get()
        checkCallArgs(this, body.params, args)

        val callerStackPos = R_StackPos(rtFrame.defCtx.defId, callerFilePos)
        val caller = Rt_FrameCaller(rtFrame, callerStackPos)

        val defCtx = rtFrame.defCtx
        val subDefCtx = Rt_DefinitionContext(defCtx.exeCtx, false, names.defId)
        val res = body.call(subDefCtx, args, caller)
        return res
    }

    override fun toMetaGtv(): Gtv {
        return mapOf(
                "type" to type().toMetaGtv(),
                "parameters" to params().map { it.toMetaGtv() }.toGtv()
        ).toGtv()
    }
}

class R_FunctionBody(
        val type: R_Type,
        val varParams: List<R_VarParam>,
        val body: R_Statement,
        val frame: R_CallFrame
) {
    val params = varParams.map { it.toParam() }.toImmList()

    companion object {
        val ERROR = R_FunctionBody(
                R_CtErrorType,
                listOf(),
                C_Utils.ERROR_STATEMENT,
                R_CallFrame.ERROR
        )
    }
}

class R_FunctionDefinition(
        names: R_DefinitionNames,
        initFrameGetter: C_LateGetter<R_CallFrame>
): R_RoutineDefinition(names, initFrameGetter) {
    private val bodyLate = C_LateInit(C_CompilerPass.EXPRESSIONS, R_FunctionBody.ERROR)

    fun setBody(body: R_FunctionBody) {
        bodyLate.set(body)
    }

    override fun params() = bodyLate.get().params

    fun callTop(exeCtx: Rt_ExecutionContext, args: List<Rt_Value>, dbUpdateAllowed: Boolean = false): Rt_Value {
        val body = bodyLate.get()
        val defCtx = Rt_DefinitionContext(exeCtx, dbUpdateAllowed, body.frame.defId)
        val res = call0(defCtx, args, null)
        return res
    }

    override fun call(frame: Rt_CallFrame, args: List<Rt_Value>, callerFilePos: R_FilePos): Rt_Value {
        val caller = createFrameCaller(frame, callerFilePos)
        return call0(frame.defCtx, args, caller)
    }

    private fun call0(parentDefCtx: Rt_DefinitionContext, args: List<Rt_Value>, caller: Rt_FrameCaller?): Rt_Value {
        val body = bodyLate.get()
        val rtSubFrame = createCallFrame(parentDefCtx, caller, body.frame)
        processArgs(body.varParams, args, rtSubFrame)

        val res = body.body.execute(rtSubFrame)

        val retVal = if (res is R_StatementResult_Return) res.value else null
        return retVal ?: Rt_UnitValue
    }

    override fun toMetaGtv(): Gtv {
        val body = bodyLate.get()
        return mapOf(
                "type" to body.type.toMetaGtv(),
                "parameters" to params().map { it.toMetaGtv() }.toGtv()
        ).toGtv()
    }

    companion object {
        fun createFrameCaller(frame: Rt_CallFrame, callerFilePos: R_FilePos): Rt_FrameCaller {
            val callerStackPos = R_StackPos(frame.defCtx.defId, callerFilePos)
            return Rt_FrameCaller(frame, callerStackPos)
        }

        fun createCallFrame(
                callerDefCtx: Rt_DefinitionContext,
                caller: Rt_FrameCaller?,
                targetFrame: R_CallFrame
        ): Rt_CallFrame {
            val callerDbUpdateAllowed = caller?.frame?.dbUpdateAllowed() ?: true
            val dbUpdateAllowed = callerDbUpdateAllowed && callerDefCtx.dbUpdateAllowed
            val subDefCtx = Rt_DefinitionContext(callerDefCtx.exeCtx, dbUpdateAllowed, targetFrame.defId)
            return targetFrame.createRtFrame(subDefCtx, caller, null)
        }
    }
}

private fun checkCallArgs(routine: R_RoutineDefinition, params: List<R_Param>, args: List<Rt_Value>) {
    val name = routine.appLevelName

    if (args.size != params.size) {
        throw Rt_Error("fn_wrong_arg_count:$name:${params.size}:${args.size}",
                "Wrong number of arguments for '$name': ${args.size} instead of ${params.size}")
    }

    for (i in params.indices) {
        val param = params[i]
        val argType = args[i].type()
        if (!param.type.isAssignableFrom(argType)) {
            throw Rt_Error("fn_wrong_arg_type:$name:${param.type.toStrictString()}:${argType.toStrictString()}",
                    "Wrong type of argument ${param.name} for '$name': " +
                            "${argType.toStrictString()} instead of ${param.type.toStrictString()}")
        }
    }
}

private fun processArgs(params: List<R_VarParam>, args: List<Rt_Value>, frame: Rt_CallFrame) {
    check(args.size == params.size)
    for (i in params.indices) {
        val param = params[i]
        val arg = args[i]
        frame.set(param.ptr, param.type, arg, false)
    }
}

class R_Module(
        val name: R_ModuleName,
        val abstract: Boolean,
        val external: Boolean,
        val externalChain: String?,
        val test: Boolean,
        val entities: Map<String, R_EntityDefinition>,
        val objects: Map<String, R_ObjectDefinition>,
        val structs: Map<String, R_StructDefinition>,
        val enums: Map<String, R_EnumDefinition>,
        val operations: Map<String, R_OperationDefinition>,
        val queries: Map<String, R_QueryDefinition>,
        val functions: Map<String, R_FunctionDefinition>,
        val moduleArgs: R_StructDefinition?
){
    override fun toString() = name.toString()

    fun toMetaGtv(): Gtv {
        val map = mutableMapOf(
                "name" to name.str().toGtv()
        )

        if (abstract) map["abstract"] = abstract.toGtv()
        if (external) map["external"] = external.toGtv()
        if (externalChain != null) map["externalChain"] = externalChain.toGtv()

        addGtvDefs(map, "entities", entities)
        addGtvDefs(map, "objects", objects)
        addGtvDefs(map, "structs", structs)
        addGtvDefs(map, "enums", enums)
        addGtvDefs(map, "operations", operations)
        addGtvDefs(map, "queries", queries)
        addGtvDefs(map, "functions", functions)

        return map.toGtv()
    }

    private fun addGtvDefs(map: MutableMap<String, Gtv>, key: String, defs: Map<String, R_Definition>) {
        if (defs.isNotEmpty()) {
            map[key] = defs.keys.sorted().map { it to defs.getValue(it).toMetaGtv() }.toMap().toGtv()
        }
    }
}

class R_AppSqlDefs(
        entities: List<R_EntityDefinition>,
        objects: List<R_ObjectDefinition>,
        topologicalEntities: List<R_EntityDefinition>
) {
    val entities = entities.toImmList()
    val objects = objects.toImmList()
    val topologicalEntities = topologicalEntities.toImmList()

    init {
        check(this.topologicalEntities.size == this.entities.size) { "${this.topologicalEntities.size} != ${this.entities.size}" }
    }

    fun same(other: R_AppSqlDefs): Boolean {
        return entities == other.entities
                && objects == other.objects
                && topologicalEntities == other.topologicalEntities
    }

    companion object {
        val EMPTY = R_AppSqlDefs(listOf(), listOf(), listOf())
    }
}

class R_App(
        val valid: Boolean,
        val uid: R_AppUid,
        modules: List<R_Module>,
        operations: Map<R_MountName, R_OperationDefinition>,
        queries: Map<R_MountName, R_QueryDefinition>,
        val externalChainsRoot: R_ExternalChainsRoot,
        externalChains: List<R_ExternalChainRef>,
        val sqlDefs: R_AppSqlDefs
) {
    val modules = modules.toImmList()
    val operations = operations.toImmMap()
    val queries = queries.toImmMap()
    val externalChains = externalChains.toImmList()

    val moduleMap = this.modules.map { it.name to it }.toMap().toImmMap()

    init {
        for ((i, c) in externalChains.withIndex()) {
            check(c.root === externalChainsRoot)
            check(c.index == i)
        }
    }

    fun toMetaGtv(): Gtv {
        return mapOf(
                "modules" to modules.map {
                    val name = it.name.str()
                    val fullName = if (it.externalChain == null) name else "$name[${it.externalChain}]"
                    fullName to it.toMetaGtv()
                }.toMap().toGtv()
        ).toGtv()
    }
}
