package net.postchain.rell.model

import net.postchain.rell.runtime.*
import java.lang.IllegalStateException

class RAttrib(val name: String, val type: RType, val mutable: Boolean, val expr: RExpr?)

class RExternalParam(val name: String, val type: RType, val offset: Int)

sealed class RRoutine(val name: String, val params: List<RExternalParam>, val body: RStatement) {
    abstract fun callTop(modCtx: RtModuleContext, args: List<RtValue>)
}

class ROperation(name: String, params: List<RExternalParam>, body: RStatement): RRoutine(name, params, body) {
    override fun callTop(modCtx: RtModuleContext, args: List<RtValue>) {
        val env = RtEnv(modCtx, true)
        processArgs(name, params, args, env)

        modCtx.globalCtx.sqlExec.transaction {
            val res = body.execute(env)
            if (res != null) {
                check(res is RStatementResult_Return && res.value == null)
            }
        }
    }
}

class RQuery(name: String, params: List<RExternalParam>, body: RStatement): RRoutine(name, params, body) {
    override fun callTop(modCtx: RtModuleContext, args: List<RtValue>) {
        callTopQuery(modCtx, args)
    }

    fun callTopQuery(modCtx: RtModuleContext, args: List<RtValue>): RtValue {
        val env = RtEnv(modCtx, false)
        processArgs(name, params, args, env)

        val res = body.execute(env)
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
        val type: RType,
        val fnKey: Int
): RRoutine(name, params, body)
{
    override fun callTop(modCtx: RtModuleContext, args: List<RtValue>) {
        val env = RtEnv(modCtx, false)
        call(env, args)
    }

    fun call(env: RtEnv, args: List<RtValue>): RtValue {
        val fnEnv = RtEnv(env.modCtx, env.dbUpdateAllowed)
        processArgs(name, params, args, fnEnv)

        val res = body.execute(fnEnv)

        val retVal = if (res is RStatementResult_Return) res.value else null
        return if (retVal == null) RtUnitValue else retVal
    }
}

private fun processArgs(name: String, params: List<RExternalParam>, args: List<RtValue>, env: RtEnv) {
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
        env.set(param.offset, arg)
    }
}

class RModule(
        val classes: List<RClass>,
        val operations: List<ROperation>,
        val queries: List<RQuery>,
        val functions: List<RFunction>
){
    init {
        for ((i, f) in functions.withIndex()) {
            check(f.fnKey == i)
        }
    }
}
