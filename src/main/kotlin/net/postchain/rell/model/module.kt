package net.postchain.rell.model

import net.postchain.rell.runtime.*
import java.util.*

class R_ExternalChain(val name: String, val index: Int)

data class R_FrameBlockId(val id: Long)
data class R_VarPtr(val blockId: R_FrameBlockId, val offset: Int)
class R_FrameBlock(val parentId: R_FrameBlockId?, val id: R_FrameBlockId, val offset: Int, val size: Int)
class R_CallFrame(val size: Int, val rootBlock: R_FrameBlock)

class R_Attrib(
        val index: Int,
        val name: String,
        val type: R_Type,
        val mutable: Boolean,
        val hasExpr: Boolean,
        val canSetInCreate: Boolean = true,
        val sqlMapping: String = name
){
    private lateinit var expr0: Optional<R_Expr>
    val expr: R_Expr? get() = expr0.orElse(null)

    fun setExpr(expr: R_Expr?) {
        expr0 = Optional.ofNullable(expr)
    }
}

class R_ExternalParam(val name: String, val type: R_Type, val ptr: R_VarPtr)

sealed class R_Routine(val name: String, val params: List<R_ExternalParam>, val body: R_Statement, val frame: R_CallFrame) {
    abstract fun callTop(modCtx: Rt_ModuleContext, args: List<Rt_Value>)
}

class R_Operation(
        name: String,
        params: List<R_ExternalParam>,
        body: R_Statement,
        frame: R_CallFrame
): R_Routine(name, params, body, frame)
{
    override fun callTop(modCtx: Rt_ModuleContext, args: List<Rt_Value>) {
        val entCtx = Rt_EntityContext(modCtx, true)
        val rtFrame = Rt_CallFrame(entCtx, frame)

        checkCallArgs(name, params, args)
        processArgs(params, args, rtFrame)

        modCtx.globalCtx.sqlExec.transaction {
            execute(rtFrame)
        }
    }

    fun callTopNoTx(modCtx: Rt_ModuleContext, args: List<Rt_Value>) {
        val entCtx = Rt_EntityContext(modCtx, true)
        val rtFrame = Rt_CallFrame(entCtx, frame)
        checkCallArgs(name, params, args)
        processArgs(params, args, rtFrame)
        execute(rtFrame)
    }

    private fun execute(rtFrame: Rt_CallFrame) {
        val res = body.execute(rtFrame)
        if (res != null) {
            check(res is R_StatementResult_Return && res.value == null)
        }
    }
}

class R_Query(
        name: String,
        val type: R_Type,
        params: List<R_ExternalParam>,
        body: R_Statement,
        frame: R_CallFrame
): R_Routine(name, params, body, frame)
{
    override fun callTop(modCtx: Rt_ModuleContext, args: List<Rt_Value>) {
        callTopQuery(modCtx, args)
    }

    fun callTopQuery(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value {
        val entCtx = Rt_EntityContext(modCtx, false)
        val rtFrame = Rt_CallFrame(entCtx, frame)
        checkCallArgs(name, params, args)
        processArgs(params, args, rtFrame)

        val res = body.execute(rtFrame)
        if (res == null) {
            throw Rt_Error("query_novalue:$name", "Query '$name' did not return a value")
        }

        if (res !is R_StatementResult_Return) {
            throw IllegalStateException("" + res)
        }
        check(res.value != null)

        return res.value!!
    }
}

class R_Function(
        name: String,
        params: List<R_ExternalParam>,
        body: R_Statement,
        frame: R_CallFrame,
        val type: R_Type,
        val fnKey: Int
): R_Routine(name, params, body, frame)
{
    override fun callTop(modCtx: Rt_ModuleContext, args: List<Rt_Value>) {
        callTopFunction(modCtx, args)
    }

    fun callTopFunction(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value {
        val entCtx = Rt_EntityContext(modCtx, false)
        val rtFrame = Rt_CallFrame(entCtx, frame)
        val res = call(rtFrame, args)
        return res
    }

    fun call(rtFrame: Rt_CallFrame, args: List<Rt_Value>): Rt_Value {
        val subEntCtx = Rt_EntityContext(rtFrame.entCtx.modCtx, rtFrame.entCtx.dbUpdateAllowed)
        val rtSubFrame = Rt_CallFrame(subEntCtx, frame)

        processArgs(params, args, rtSubFrame)
        val res = body.execute(rtSubFrame)

        val retVal = if (res is R_StatementResult_Return) res.value else null
        return if (retVal == null) Rt_UnitValue else retVal
    }
}

private fun checkCallArgs(name: String, params: List<R_ExternalParam>, args: List<Rt_Value>) {
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

class R_Module(
        val classes: Map<String, R_Class>,
        val objects: Map<String, R_Object>,
        val records: Map<String, R_RecordType>,
        val operations: Map<String, R_Operation>,
        val queries: Map<String, R_Query>,
        val functionsTable: List<R_Function>,
        val moduleArgsRecord: R_RecordType?,
        val topologicalClasses: List<R_Class>,
        val externalChains: List<R_ExternalChain>
){
    val functions = functionsTable.associate { Pair(it.name, it) }

    init {
        for ((i, f) in functionsTable.withIndex()) {
            check(f.fnKey == i)
        }
        for ((i, c) in externalChains.withIndex()) {
            check(c.index == i)
        }
    }
}
