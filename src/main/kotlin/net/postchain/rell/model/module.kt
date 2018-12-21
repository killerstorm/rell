package net.postchain.rell.model

import net.postchain.rell.module.GtxRtConversion
import net.postchain.rell.runtime.*
import java.lang.IllegalStateException
import java.util.*

data class RFrameBlockId(val id: Long)
data class RVarPtr(val blockId: RFrameBlockId, val offset: Int)
class RFrameBlock(val parentId: RFrameBlockId?, val id: RFrameBlockId, val offset: Int, val size: Int)
class RCallFrame(val size: Int, val rootBlock: RFrameBlock)

class RAttrib(val index: Int, val name: String, val type: RType, val mutable: Boolean, val hasExpr: Boolean) {
    private lateinit var expr0: Optional<RExpr>
    val expr: RExpr? get() = expr0.orElse(null)

    fun setExpr(expr: RExpr?) {
        expr0 = Optional.ofNullable(expr)
    }
}

class RExternalParam(val name: String, val type: RType, val ptr: RVarPtr)

sealed class RRoutine(val name: String, val params: List<RExternalParam>, val body: RStatement, val frame: RCallFrame) {
    abstract fun callTop(modCtx: RtModuleContext, args: List<RtValue>)
}

class ROperation(
        name: String,
        params: List<RExternalParam>,
        body: RStatement,
        frame: RCallFrame
): RRoutine(name, params, body, frame)
{
    override fun callTop(modCtx: RtModuleContext, args: List<RtValue>) {
        val entCtx = RtEntityContext(modCtx, true)
        val rtFrame = RtCallFrame(entCtx, frame)

        checkCallArgs(name, params, args)
        processArgs(params, args, rtFrame)

        modCtx.globalCtx.sqlExec.transaction {
            execute(rtFrame)
        }
    }

    fun callTopNoTx(modCtx: RtModuleContext, args: List<RtValue>) {
        val entCtx = RtEntityContext(modCtx, true)
        val rtFrame = RtCallFrame(entCtx, frame)
        checkCallArgs(name, params, args)
        processArgs(params, args, rtFrame)
        execute(rtFrame)
    }

    private fun execute(rtFrame: RtCallFrame) {
        val res = body.execute(rtFrame)
        if (res != null) {
            check(res is RStatementResult_Return && res.value == null)
        }
    }
}

class RQuery(
        name: String,
        val type: RType,
        params: List<RExternalParam>,
        body: RStatement,
        frame: RCallFrame
): RRoutine(name, params, body, frame)
{
    override fun callTop(modCtx: RtModuleContext, args: List<RtValue>) {
        callTopQuery(modCtx, args)
    }

    fun callTopQuery(modCtx: RtModuleContext, args: List<RtValue>): RtValue {
        val entCtx = RtEntityContext(modCtx, false)
        val rtFrame = RtCallFrame(entCtx, frame)
        checkCallArgs(name, params, args)
        processArgs(params, args, rtFrame)

        val res = body.execute(rtFrame)
        if (res == null) {
            throw RtError("query_novalue:$name", "Query '$name' did not return a value")
        }

        if (res !is RStatementResult_Return) {
            throw IllegalStateException("" + res)
        }
        check(res.value != null)

        return res.value!!
    }
}

class RFunction(
        name: String,
        params: List<RExternalParam>,
        body: RStatement,
        frame: RCallFrame,
        val type: RType,
        val fnKey: Int
): RRoutine(name, params, body, frame)
{
    override fun callTop(modCtx: RtModuleContext, args: List<RtValue>) {
        callTopFunction(modCtx, args)
    }

    fun callTopFunction(modCtx: RtModuleContext, args: List<RtValue>): RtValue {
        val entCtx = RtEntityContext(modCtx, false)
        val rtFrame = RtCallFrame(entCtx, frame)
        val res = call(rtFrame, args)
        return res
    }

    fun call(rtFrame: RtCallFrame, args: List<RtValue>): RtValue {
        val subEntCtx = RtEntityContext(rtFrame.entCtx.modCtx, rtFrame.entCtx.dbUpdateAllowed)
        val rtSubFrame = RtCallFrame(subEntCtx, frame)

        processArgs(params, args, rtSubFrame)
        val res = body.execute(rtSubFrame)

        val retVal = if (res is RStatementResult_Return) res.value else null
        return if (retVal == null) RtUnitValue else retVal
    }
}

private fun checkCallArgs(name: String, params: List<RExternalParam>, args: List<RtValue>) {
    if (args.size != params.size) {
        throw RtError("fn_wrong_arg_count:$name:${params.size}:${args.size}",
                "Wrong number of arguments for '$name': ${args.size} instead of ${params.size}")
    }

    for (i in params.indices) {
        val param = params[i]
        val argType = args[i].type()
        if (!param.type.isAssignableFrom(argType)) {
            throw RtError("fn_wrong_arg_type:$name:${param.type.toStrictString()}:${argType.toStrictString()}",
                    "Wrong type of argument ${param.name} for '$name': " +
                            "${argType.toStrictString()} instead of ${param.type.toStrictString()}")
        }
    }
}

private fun processArgs(params: List<RExternalParam>, args: List<RtValue>, frame: RtCallFrame) {
    check(args.size == params.size)
    for (i in params.indices) {
        val param = params[i]
        val arg = args[i]
        frame.set(param.ptr, arg, false)
    }
}

class RModule(
        val classes: Map<String, RClass>,
        val records: Map<String, RRecordType>,
        val operations: Map<String, ROperation>,
        val queries: Map<String, RQuery>,
        val functionsTable: List<RFunction>
){
    val functions = functionsTable.associate { Pair(it.name, it) }

    init {
        for ((i, f) in functionsTable.withIndex()) {
            check(f.fnKey == i)
        }
    }
}
