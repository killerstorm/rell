package net.postchain.rell.model

import net.postchain.gtv.Gtv
import net.postchain.rell.compiler.C_CompilerPass
import net.postchain.rell.compiler.C_LateInit
import net.postchain.rell.compiler.C_Utils
import net.postchain.rell.runtime.*
import net.postchain.rell.toImmList
import net.postchain.rell.toImmMap
import java.util.*

class R_DefinitionNames(val module: String, val namespace: String?, val simpleName: String) {
    val qualifiedName = if (namespace == null) simpleName else "$namespace.$simpleName"
    val appLevelName = if (module.isEmpty()) qualifiedName else R_DefinitionPos.appLevelName(module, qualifiedName)

    fun pos() = R_DefinitionPos(module, qualifiedName)

    override fun toString() = appLevelName
}

abstract class R_Definition(names: R_DefinitionNames) {
    val simpleName = names.simpleName
    val moduleLevelName = names.qualifiedName
    val appLevelName = names.appLevelName
    val pos = names.pos()

    abstract fun toMetaGtv(): Gtv

    final override fun toString() = "${javaClass.simpleName}[$appLevelName]"
}

class R_ExternalChainsRoot
class R_ExternalChainRef(val root: R_ExternalChainsRoot, val name: String, val index: Int)

data class R_FrameBlockId(val id: Long, val location: String)
data class R_VarPtr(val name: String, val blockId: R_FrameBlockId, val offset: Int)
class R_FrameBlock(val parentId: R_FrameBlockId?, val id: R_FrameBlockId, val offset: Int, val size: Int)

class R_CallFrame(val size: Int, val rootBlock: R_FrameBlock) {
    companion object {
        private val ERROR_BLOCK = R_FrameBlock(null, R_FrameBlockId(-1, "error"), -1, -1)
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
    abstract fun callTop(appCtx: Rt_AppContext, args: List<Rt_Value>): Rt_Value?
}

sealed class R_MountedRoutine(names: R_DefinitionNames, val mountName: R_MountName): R_Routine(names)

class R_Operation(names: R_DefinitionNames, mountName: R_MountName): R_MountedRoutine(names, mountName) {
    private val internals = C_LateInit(C_CompilerPass.EXPRESSIONS, ERROR_INTERNALS)

    fun setInternals(varParams: List<R_VarParam>, body: R_Statement, frame: R_CallFrame) {
        val params = varParams.map { it.toParam() }.toImmList()
        internals.set(Internals(params, varParams, body, frame))
    }

    override fun params() = internals.get().params

    override fun callTop(appCtx: Rt_AppContext, args: List<Rt_Value>): Rt_Value? {
        val rtFrame = processCallArgs(appCtx, args)

        appCtx.globalCtx.sqlExec.transaction {
            execute(rtFrame)
        }

        return null
    }

    fun callTopNoTx(appCtx: Rt_AppContext, args: List<Rt_Value>) {
        val rtFrame = processCallArgs(appCtx, args)
        execute(rtFrame)
    }

    private fun processCallArgs(appCtx: Rt_AppContext, args: List<Rt_Value>): Rt_CallFrame {
        val ints = internals.get()

        val defCtx = Rt_DefinitionContext(appCtx, true, pos)
        val rtFrame = Rt_CallFrame(null, null, defCtx, ints.frame)

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

sealed class R_QueryBody {
    abstract fun params(): List<R_Param>
    abstract fun execute(defCtx: Rt_DefinitionContext, args: List<Rt_Value>, pos: R_DefinitionPos): Rt_Value
}

class R_UserQueryBody(
        private val varParams: List<R_VarParam>,
        private val body: R_Statement,
        private val frame: R_CallFrame
): R_QueryBody() {
    private val params = varParams.map { it.toParam() }.toImmList()

    override fun params() = params

    override fun execute(defCtx: Rt_DefinitionContext, args: List<Rt_Value>, pos: R_DefinitionPos): Rt_Value {
        val rtFrame = Rt_CallFrame(null, null, defCtx, frame)

        processArgs(varParams, args, rtFrame)

        val res = body.execute(rtFrame)
        check(res is R_StatementResult_Return) { "${res?.javaClass?.name}" }

        check(res.value != null)
        return res.value
    }

    companion object {
        val ERROR: R_QueryBody = R_UserQueryBody(listOf(), C_Utils.ERROR_STATEMENT, R_CallFrame.ERROR)
    }
}

class R_SysQueryBody(params: List<R_Param>, private val fn: R_SysFunction): R_QueryBody() {
    private val params = params.toImmList()

    override fun params() = params

    override fun execute(defCtx: Rt_DefinitionContext, args: List<Rt_Value>, pos: R_DefinitionPos): Rt_Value {
        val ctx = Rt_CallContext(defCtx)
        return fn.call(ctx, args)
    }
}

class R_Query(names: R_DefinitionNames, mountName: R_MountName): R_MountedRoutine(names, mountName) {
    private val internals = C_LateInit(C_CompilerPass.EXPRESSIONS, ERROR_INTERNALS)

    fun setInternals(type: R_Type, body: R_QueryBody) {
        val params = body.params()
        internals.set(Internals(type, params, body))
    }

    override fun params() = internals.get().params
    fun type(): R_Type = internals.get().type

    override fun callTop(appCtx: Rt_AppContext, args: List<Rt_Value>): Rt_Value? {
        return callTopQuery(appCtx, args)
    }

    fun callTopQuery(appCtx: Rt_AppContext, args: List<Rt_Value>): Rt_Value {
        val ints = internals.get()
        checkCallArgs(this, ints.params, args)
        val defCtx = Rt_DefinitionContext(appCtx, false, pos)
        val res = ints.body.execute(defCtx, args, pos)
        return res
    }

    override fun toMetaGtv(): Gtv {
        return mapOf(
                "type" to type().toMetaGtv(),
                "parameters" to params().map { it.toMetaGtv() }.toGtv()
        ).toGtv()
    }

    private class Internals(val type: R_Type, val params: List<R_Param>, val body: R_QueryBody)

    companion object {
        private val ERROR_INTERNALS = Internals(type = R_UnitType, params = listOf(), body = R_UserQueryBody.ERROR)
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
        val EMPTY = R_FunctionBody(
                R_DefinitionPos("<error>", "<error>"),
                R_UnitType,
                listOf(),
                C_Utils.ERROR_STATEMENT,
                R_CallFrame.ERROR
        )
    }
}

class R_Function(names: R_DefinitionNames): R_Routine(names) {
    private val bodyLate = C_LateInit(C_CompilerPass.EXPRESSIONS, R_FunctionBody.EMPTY)

    fun setBody(body: R_FunctionBody) {
        bodyLate.set(body)
    }

    override fun params() = bodyLate.get().params

    override fun callTop(appCtx: Rt_AppContext, args: List<Rt_Value>): Rt_Value? {
        val res = callTopFunction(appCtx, args)
        val type = bodyLate.get().type
        return if (type != R_UnitType) res else null
    }

    fun callTopFunction(appCtx: Rt_AppContext, args: List<Rt_Value>, dbUpdateAllowed: Boolean = false): Rt_Value {
        val body = bodyLate.get()
        val rtFrame = createRtFrame(body, appCtx, dbUpdateAllowed, null, null)
        val res = call(rtFrame, args, null)
        return res
    }

    fun call(rtFrame: Rt_CallFrame, args: List<Rt_Value>, callerFilePos: R_FilePos?): Rt_Value {
        val body = bodyLate.get()
        val rtSubFrame = createRtFrame(body, rtFrame.defCtx.appCtx, rtFrame.defCtx.dbUpdateAllowed, rtFrame, callerFilePos)

        processArgs(body.varParams, args, rtSubFrame)

        val res = body.body.execute(rtSubFrame)

        val retVal = if (res is R_StatementResult_Return) res.value else null
        return retVal ?: Rt_UnitValue
    }

    private fun createRtFrame(
            body: R_FunctionBody,
            appCtx: Rt_AppContext,
            dbUpdateAllowed: Boolean,
            callerFrame: Rt_CallFrame?,
            callerFilePos: R_FilePos?
    ): Rt_CallFrame {
        val defCtx = Rt_DefinitionContext(appCtx, dbUpdateAllowed, body.defPos)

        val callerPos = if (callerFrame?.defCtx?.pos == null || callerFilePos == null) null else {
            R_StackPos(callerFrame.defCtx.pos, callerFilePos)
        }

        return Rt_CallFrame(callerFrame, callerPos, defCtx, body.frame)
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
