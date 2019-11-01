package net.postchain.rell.model

import net.postchain.rell.parser.C_CompilerPass
import net.postchain.rell.parser.C_LateInit
import net.postchain.rell.runtime.*
import net.postchain.rell.toImmList
import net.postchain.rell.toImmMap
import java.util.*

class R_DefinitionNames(
        val simpleName: String,
        val moduleLevelName: String,
        val appLevelName: String
) {
    override fun toString() = appLevelName
}

abstract class R_Definition(names: R_DefinitionNames) {
    val simpleName = names.simpleName
    val moduleLevelName = names.moduleLevelName
    val appLevelName = names.appLevelName

    final override fun toString() = "${javaClass.simpleName}[$appLevelName]"
}

class R_ExternalChainsRoot
class R_ExternalChainRef(val root: R_ExternalChainsRoot, val name: String, val index: Int)

data class R_FrameBlockId(val id: Long)
data class R_VarPtr(val blockId: R_FrameBlockId, val offset: Int)
class R_FrameBlock(val parentId: R_FrameBlockId?, val id: R_FrameBlockId, val offset: Int, val size: Int)

class R_CallFrame(val size: Int, val rootBlock: R_FrameBlock) {
    companion object {
        private val ERROR_BLOCK = R_FrameBlock(null, R_FrameBlockId(-1), -1, -1)
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
}

class R_ExternalParam(val name: String, val type: R_Type, val ptr: R_VarPtr)

sealed class R_Routine(names: R_DefinitionNames): R_Definition(names) {
    abstract fun params(): List<R_ExternalParam>
    abstract fun callTop(appCtx: Rt_AppContext, args: List<Rt_Value>): Rt_Value?
}

sealed class R_MountedRoutine(names: R_DefinitionNames, val mountName: R_MountName): R_Routine(names)

class R_Operation(names: R_DefinitionNames, mountName: R_MountName): R_MountedRoutine(names, mountName) {
    private val internals = C_LateInit(C_CompilerPass.EXPRESSIONS, ERROR_INTERNALS)

    fun setInternals(params: List<R_ExternalParam>, body: R_Statement, frame: R_CallFrame) {
        internals.set(Internals(params, body, frame))
    }

    override fun params() = internals.get().params

    override fun callTop(appCtx: Rt_AppContext, args: List<Rt_Value>): Rt_Value? {
        val ints = internals.get()

        val defCtx = Rt_DefinitionContext(appCtx, true)
        val rtFrame = Rt_CallFrame(defCtx, ints.frame)

        checkCallArgs(this, ints.params, args)
        processArgs(ints.params, args, rtFrame)

        appCtx.globalCtx.sqlExec.transaction {
            execute(rtFrame)
        }

        return null
    }

    fun callTopNoTx(appCtx: Rt_AppContext, args: List<Rt_Value>) {
        val ints = internals.get()

        val defCtx = Rt_DefinitionContext(appCtx, true)
        val rtFrame = Rt_CallFrame(defCtx, ints.frame)

        checkCallArgs(this, ints.params, args)
        processArgs(ints.params, args, rtFrame)

        execute(rtFrame)
    }

    private fun execute(rtFrame: Rt_CallFrame) {
        val ints = internals.get()
        val res = ints.body.execute(rtFrame)
        if (res != null) {
            check(res is R_StatementResult_Return && res.value == null)
        }
    }

    private class Internals(val params: List<R_ExternalParam>, val body: R_Statement, val frame: R_CallFrame)

    companion object {
        private val ERROR_INTERNALS = Internals(params = listOf(), body = R_EmptyStatement, frame = R_CallFrame.ERROR)
    }
}

class R_Query(names: R_DefinitionNames, mountName: R_MountName): R_MountedRoutine(names, mountName) {
    private val internals = C_LateInit(C_CompilerPass.EXPRESSIONS, ERROR_INTERNALS)

    fun setInternals(type: R_Type, params: List<R_ExternalParam>, body: R_Statement, frame: R_CallFrame) {
        internals.set(Internals(type, params, body, frame))
    }

    override fun params() = internals.get().params
    fun type(): R_Type = internals.get().type

    override fun callTop(appCtx: Rt_AppContext, args: List<Rt_Value>): Rt_Value? {
        return callTopQuery(appCtx, args)
    }

    fun callTopQuery(appCtx: Rt_AppContext, args: List<Rt_Value>): Rt_Value {
        val ints = internals.get()

        val defCtx = Rt_DefinitionContext(appCtx, false)
        val rtFrame = Rt_CallFrame(defCtx, ints.frame)

        checkCallArgs(this, ints.params, args)
        processArgs(ints.params, args, rtFrame)

        val res = ints.body.execute(rtFrame)
        if (res == null) {
            val name = appLevelName
            throw Rt_Error("query_novalue:$name", "Query '$name' did not return a value")
        }

        if (res !is R_StatementResult_Return) {
            throw IllegalStateException("" + res)
        }
        check(res.value != null)

        return res.value
    }

    private class Internals(
            val type: R_Type,
            val params: List<R_ExternalParam>,
            val body: R_Statement,
            val frame: R_CallFrame
    )

    companion object {
        private val ERROR_INTERNALS = Internals(
                type = R_UnitType,
                params = listOf(),
                body = R_EmptyStatement,
                frame = R_CallFrame.ERROR
        )
    }
}

private class R_FunctionHeader(val type: R_Type, val params: List<R_ExternalParam>)
private class R_FunctionBody(val body: R_Statement, val frame: R_CallFrame)

class R_Function(names: R_DefinitionNames): R_Routine(names) {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, R_FunctionHeader(R_UnitType, listOf()))
    private val bodyLate = C_LateInit(C_CompilerPass.EXPRESSIONS, R_FunctionBody(R_EmptyStatement, R_CallFrame.ERROR))

    fun setHeader(type: R_Type, params: List<R_ExternalParam>) {
        headerLate.set(R_FunctionHeader(type, params))
    }

    fun setBody(body: R_Statement, frame: R_CallFrame) {
        bodyLate.set(R_FunctionBody(body, frame))
    }

    fun type() = headerLate.get().type

    override fun params() = headerLate.get().params

    override fun callTop(appCtx: Rt_AppContext, args: List<Rt_Value>): Rt_Value? {
        val res = callTopFunction(appCtx, args)
        val type = headerLate.get().type
        return if (type != R_UnitType) res else null
    }

    fun callTopFunction(appCtx: Rt_AppContext, args: List<Rt_Value>, dbUpdateAllowed: Boolean = false): Rt_Value {
        val rtFrame = createRtFrame(appCtx, dbUpdateAllowed)
        val res = call(rtFrame, args)
        return res
    }

    fun call(rtFrame: Rt_CallFrame, args: List<Rt_Value>): Rt_Value {
        val rtSubFrame = createRtFrame(rtFrame.defCtx.appCtx, rtFrame.defCtx.dbUpdateAllowed)

        val params = params()
        processArgs(params, args, rtSubFrame)

        val body = bodyLate.get().body
        val res = body.execute(rtSubFrame)

        val retVal = if (res is R_StatementResult_Return) res.value else null
        return retVal ?: Rt_UnitValue
    }

    private fun createRtFrame(appCtx: Rt_AppContext, dbUpdateAllowed: Boolean): Rt_CallFrame {
        val frame = bodyLate.get().frame
        val defCtx = Rt_DefinitionContext(appCtx, dbUpdateAllowed)
        return Rt_CallFrame(defCtx, frame)
    }
}

private fun checkCallArgs(routine: R_Routine, params: List<R_ExternalParam>, args: List<Rt_Value>) {
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

private fun processArgs(params: List<R_ExternalParam>, args: List<Rt_Value>, frame: Rt_CallFrame) {
    check(args.size == params.size)
    for (i in params.indices) {
        val param = params[i]
        val arg = args[i]
        frame.set(param.ptr, param.type, arg, false)
    }
}

class R_App(
        val valid: Boolean,
        modules: List<R_Module>,
        entities: List<R_Entity>,
        objects: List<R_Object>,
        operations: Map<R_MountName, R_Operation>,
        queries: Map<R_MountName, R_Query>,
        topologicalEntities: List<R_Entity>,
        val externalChainsRoot: R_ExternalChainsRoot,
        externalChains: List<R_ExternalChainRef>
) {
    val modules = modules.toImmList()
    val entities = entities.toImmList()
    val objects = objects.toImmList()
    val operations = operations.toImmMap()
    val queries = queries.toImmMap()
    val topologicalEntities = topologicalEntities.toImmList()
    val externalChains = externalChains.toImmList()

    init {
        check(topologicalEntities.size == entities.size) { "${topologicalEntities.size} != ${entities.size}" }

        for ((i, c) in externalChains.withIndex()) {
            check(c.root === externalChainsRoot)
            check(c.index == i)
        }
    }
}

class R_Module(
        val name: R_ModuleName,
        val entities: Map<String, R_Entity>,
        val objects: Map<String, R_Object>,
        val structs: Map<String, R_Struct>,
        val operations: Map<String, R_Operation>,
        val queries: Map<String, R_Query>,
        val functions: Map<String, R_Function>,
        val moduleArgs: R_Struct?
){
    override fun toString() = name.toString()
}
