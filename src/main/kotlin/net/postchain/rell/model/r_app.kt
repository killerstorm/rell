/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model

import net.postchain.gtv.Gtv
import net.postchain.rell.compiler.C_CompilerPass
import net.postchain.rell.compiler.C_LateInit
import net.postchain.rell.compiler.C_Utils
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap
import java.util.*

class R_DefinitionNames(val module: String, val namespace: String?, val simpleName: String) {
    val qualifiedName = if (namespace == null) simpleName else "$namespace.$simpleName"
    val appLevelName = if (module.isEmpty()) qualifiedName else R_DefinitionPos.appLevelName(module, qualifiedName)
    val pos = R_DefinitionPos(module, qualifiedName)

    override fun toString() = appLevelName
}

abstract class R_Definition(val names: R_DefinitionNames) {
    val simpleName = names.simpleName
    val moduleLevelName = names.qualifiedName
    val appLevelName = names.appLevelName
    val pos = names.pos

    abstract fun toMetaGtv(): Gtv

    final override fun toString() = "${javaClass.simpleName}[$appLevelName]"
}

class R_ExternalChainsRoot
class R_ExternalChainRef(val root: R_ExternalChainsRoot, val name: String, val index: Int)

data class R_AppUid(val id: Long)
data class R_ContainerUid(val id: Long, val name: String, val app: R_AppUid)
data class R_FnUid(val id: Long, val name: String, val container: R_ContainerUid)
data class R_FrameBlockUid(val id: Long, val name: String, val fn: R_FnUid)
data class R_VarPtr(val name: String, val blockUid: R_FrameBlockUid, val offset: Int)
class R_FrameBlock(val parentUid: R_FrameBlockUid?, val uid: R_FrameBlockUid, val offset: Int, val size: Int)

class R_CallFrame(val size: Int, val rootBlock: R_FrameBlock) {
    fun createRtFrame(defCtx: Rt_DefinitionContext, caller: Rt_ParentFrame?, state: Rt_CallFrameState?): Rt_CallFrame {
        return Rt_CallFrame(defCtx, this, caller, state)
    }

    companion object {
        private val ERROR_BLOCK = R_FrameBlock(null, R_Utils.ERROR_BLOCK_UID, -1, -1)
        val ERROR = R_CallFrame(0, ERROR_BLOCK)
    }
}

class R_Attrib(
        val index: Int,
        val name: String,
        val type: R_Type,
        val mutable: Boolean,
        val hasExpr: Boolean,
        val canSetInCreate: Boolean = true,
        val sqlMapping: String = name
){
    private val expr0 = C_LateInit<Optional<R_Expr>>(C_CompilerPass.EXPRESSIONS, Optional.empty())
    val expr: R_Expr? get() = expr0.get().orElse(null)

    fun setExpr(expr: R_Expr?) {
        expr0.set(Optional.ofNullable(expr))
    }

    fun toMetaGtv(): Gtv {
        return mapOf(
                "type" to type.toMetaGtv(),
                "mutable" to mutable.toGtv()
        ).toGtv()
    }
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

sealed class R_Routine(names: R_DefinitionNames): R_Definition(names) {
    abstract fun params(): List<R_Param>
}

sealed class R_MountedRoutine(names: R_DefinitionNames, val mountName: R_MountName): R_Routine(names)

class R_Operation(names: R_DefinitionNames, mountName: R_MountName): R_MountedRoutine(names, mountName) {
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

    private fun processCallArgs(exeCtx: Rt_ExecutionContext, args: List<Rt_Value>): Rt_CallFrame {
        val ints = internals.get()

        val defCtx = Rt_DefinitionContext(exeCtx, true, pos)
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

    abstract fun execute(defCtx: Rt_DefinitionContext, args: List<Rt_Value>, pos: R_DefinitionPos): Rt_Value
}

class R_UserQueryBody(
        retType: R_Type,
        varParams: List<R_VarParam>,
        private val body: R_Statement,
        private val frame: R_CallFrame
): R_QueryBody(retType, varParams.map { it.toParam() }) {
    private val varParams = varParams.toImmList()

    override fun execute(defCtx: Rt_DefinitionContext, args: List<Rt_Value>, pos: R_DefinitionPos): Rt_Value {
        val rtFrame = frame.createRtFrame(defCtx, null, null)

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
    override fun execute(defCtx: Rt_DefinitionContext, args: List<Rt_Value>, pos: R_DefinitionPos): Rt_Value {
        val ctx = Rt_CallContext(defCtx)
        return fn.call(ctx, args)
    }
}

class R_Query(names: R_DefinitionNames, mountName: R_MountName): R_MountedRoutine(names, mountName) {
    private val bodyLate = C_LateInit(C_CompilerPass.EXPRESSIONS, R_UserQueryBody.ERROR)

    fun setBody(body: R_QueryBody) {
        bodyLate.set(body)
    }

    fun type(): R_Type = bodyLate.get().retType
    override fun params() = bodyLate.get().params

    fun call(exeCtx: Rt_ExecutionContext, args: List<Rt_Value>): Rt_Value {
        val body = bodyLate.get()
        checkCallArgs(this, body.params, args)
        val defCtx = Rt_DefinitionContext(exeCtx, false, pos)
        val res = body.execute(defCtx, args, pos)
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
        val defPos: R_DefinitionPos,
        val type: R_Type,
        val varParams: List<R_VarParam>,
        val body: R_Statement,
        val frame: R_CallFrame
) {
    val params = varParams.map { it.toParam() }.toImmList()

    companion object {
        val ERROR = R_FunctionBody(
                R_DefinitionPos("<error>", "<error>"),
                R_CtErrorType,
                listOf(),
                C_Utils.ERROR_STATEMENT,
                R_CallFrame.ERROR
        )
    }
}

class R_Function(names: R_DefinitionNames): R_Routine(names) {
    private val bodyLate = C_LateInit(C_CompilerPass.EXPRESSIONS, R_FunctionBody.ERROR)

    fun setBody(body: R_FunctionBody) {
        bodyLate.set(body)
    }

    override fun params() = bodyLate.get().params

    fun callTop(exeCtx: Rt_ExecutionContext, args: List<Rt_Value>, dbUpdateAllowed: Boolean = false): Rt_Value {
        val body = bodyLate.get()
        val defCtx = Rt_DefinitionContext(exeCtx, dbUpdateAllowed, body.defPos)
        val res = call0(defCtx, args, null)
        return res
    }

    fun call(rtFrame: Rt_CallFrame, args: List<Rt_Value>, callerFilePos: R_FilePos): Rt_Value {
        val callerStackPos = R_StackPos(rtFrame.defCtx.pos, callerFilePos)
        val caller = Rt_ParentFrame(rtFrame, callerStackPos)
        return call0(rtFrame.defCtx, args, caller)
    }

    private fun call0(defCtx: Rt_DefinitionContext, args: List<Rt_Value>, caller: Rt_ParentFrame?): Rt_Value {
        val body = bodyLate.get()
        val rtSubFrame = createRtFrame(body, defCtx.exeCtx, defCtx.dbUpdateAllowed, caller)

        processArgs(body.varParams, args, rtSubFrame)

        val res = body.body.execute(rtSubFrame)

        val retVal = if (res is R_StatementResult_Return) res.value else null
        return retVal ?: Rt_UnitValue
    }

    private fun createRtFrame(
            body: R_FunctionBody,
            exeCtx: Rt_ExecutionContext,
            dbUpdateAllowed: Boolean,
            caller: Rt_ParentFrame?
    ): Rt_CallFrame {
        val defCtx = Rt_DefinitionContext(exeCtx, dbUpdateAllowed, body.defPos)
        return body.frame.createRtFrame(defCtx, caller, null)
    }

    override fun toMetaGtv(): Gtv {
        val body = bodyLate.get()
        return mapOf(
                "type" to body.type.toMetaGtv(),
                "parameters" to params().map { it.toMetaGtv() }.toGtv()
        ).toGtv()
    }
}

private fun checkCallArgs(routine: R_Routine, params: List<R_Param>, args: List<Rt_Value>) {
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
        val entities: Map<String, R_Entity>,
        val objects: Map<String, R_Object>,
        val structs: Map<String, R_Struct>,
        val enums: Map<String, R_Enum>,
        val operations: Map<String, R_Operation>,
        val queries: Map<String, R_Query>,
        val functions: Map<String, R_Function>,
        val moduleArgs: R_Struct?
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
        entities: List<R_Entity>,
        objects: List<R_Object>,
        topologicalEntities: List<R_Entity>
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
        operations: Map<R_MountName, R_Operation>,
        queries: Map<R_MountName, R_Query>,
        val externalChainsRoot: R_ExternalChainsRoot,
        externalChains: List<R_ExternalChainRef>,
        val sqlDefs: R_AppSqlDefs
) {
    val modules = modules.toImmList()
    val operations = operations.toImmMap()
    val queries = queries.toImmMap()
    val externalChains = externalChains.toImmList()

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
