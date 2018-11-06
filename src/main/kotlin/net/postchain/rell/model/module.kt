package net.postchain.rell.model

import net.postchain.rell.runtime.*
import java.lang.IllegalStateException

data class RFrameBlockId(val id: Long)
data class RVarPtr(val blockId: RFrameBlockId, val offset: Int)
class RFrameBlock(val parentId: RFrameBlockId?, val id: RFrameBlockId, val offset: Int, val size: Int)
class RCallFrame(val size: Int, val rootBlock: RFrameBlock)

class RAttrib(val name: String, val type: RType, val mutable: Boolean, val expr: RExpr?)

class RExternalParam(val name: String, val type: RType, val ptr: RVarPtr)

sealed class RRoutine(val name: String, val params: List<RExternalParam>, val body: RStatement, val frame: RCallFrame) {
    abstract fun callTop(modCtx: RtModuleContext, args: List<RtValue>)
}

class ROperation(name: String, params: List<RExternalParam>, body: RStatement, frame: RCallFrame)
    : RRoutine(name, params, body, frame)
{
    override fun callTop(modCtx: RtModuleContext, args: List<RtValue>) {
        val entCtx = RtEntityContext(modCtx, true)
        val rtFrame = RtCallFrame(entCtx, frame)
        processArgs(name, params, args, rtFrame)
        execute(rtFrame)
    }

    fun callInTransaction(modCtx: RtModuleContext, args: List<RtValue>) {
        val entCtx = RtEntityContext(modCtx, true)
        val rtFrame = RtCallFrame(entCtx, frame)
        processArgs(name, params, args, rtFrame)

        modCtx.globalCtx.sqlExec.transaction {
            execute(rtFrame)
        }
    }

    private fun execute(rtFrame: RtCallFrame) {
        val res = body.execute(rtFrame)
        if (res != null) {
            check(res is RStatementResult_Return && res.value == null)
        }
    }
}

class RQuery(name: String, params: List<RExternalParam>, body: RStatement, frame: RCallFrame)
    : RRoutine(name, params, body, frame)
{
    override fun callTop(modCtx: RtModuleContext, args: List<RtValue>) {
        callTopQuery(modCtx, args)
    }

    fun callTopQuery(modCtx: RtModuleContext, args: List<RtValue>): RtValue {
        val entCtx = RtEntityContext(modCtx, false)
        val rtFrame = RtCallFrame(entCtx, frame)
        processArgs(name, params, args, rtFrame)

        val res = body.execute(rtFrame)
        if (res == null) {
            throw RtError("query_novalue:$name", "Query '$name' did not return a value")
        }

        if (!(res is RStatementResult_Return)) {
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
        val entCtx = RtEntityContext(modCtx, false)
        val rtFrame = RtCallFrame(entCtx, frame)
        call(rtFrame, args)
    }

    fun call(rtFrame: RtCallFrame, args: List<RtValue>): RtValue {
        val subEntCtx = RtEntityContext(rtFrame.entCtx.modCtx, rtFrame.entCtx.dbUpdateAllowed)
        val rtSubFrame = RtCallFrame(subEntCtx, frame)

        processArgs(name, params, args, rtSubFrame)

        val res = body.execute(rtSubFrame)

        val retVal = if (res is RStatementResult_Return) res.value else null
        return if (retVal == null) RtUnitValue else retVal
    }
}

private fun processArgs(name: String, params: List<RExternalParam>, args: List<RtValue>, frame: RtCallFrame) {
    if (args.size != params.size) {
        throw RtError("fn_wrong_arg_count:$name:${params.size}:${args.size}",
                "Wrong number of arguments for '$name': ${args.size} instead of ${params.size}")
    }

    for (i in params.indices) {
        val param = params[i]
        val arg = args[i]
        val argType = arg.type()
        if (argType != param.type) {
            throw RtError("fn_wrong_arg_type:$name:${param.type.toStrictString()}:${argType.toStrictString()}",
                    "Wrong type of argument ${param.name} for '$name': " +
                            "${argType.toStrictString()} instead of ${param.type.toStrictString()}")
        }
        frame.set(param.ptr, arg, false)
    }
}

class RModule(
        val classes: Map<String, RClass>,
        val operations: Map<String, ROperation>,
        val queries: Map<String, RQuery>,
        val functions: List<RFunction>
){
    init {
        for ((i, f) in functions.withIndex()) {
            check(f.fnKey == i)
        }
    }
}
