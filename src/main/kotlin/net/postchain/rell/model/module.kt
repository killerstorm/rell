package net.postchain.rell.model

import net.postchain.rell.runtime.RtEnv
import net.postchain.rell.runtime.RtError
import net.postchain.rell.runtime.RtValue
import net.postchain.rell.sql.SqlExecutor
import java.lang.IllegalStateException

class RAttrib(val name: String, val type: RType, val mutable: Boolean, val expr: RExpr?)

class RExternalParam(val name: String, val type: RType, val offset: Int)

class ROperation(val name: String, val params: List<RExternalParam>, val body: RStatement) {
    fun execute(sqlExec: SqlExecutor, args: List<RtValue>) {
        val env = RtEnv(sqlExec, true)
        processArgs(name, params, args, env)

        val res = body.execute(env)
        check(res == null)
    }
}

class RQuery(val name: String, val params: List<RExternalParam>, val body: RStatement) {
    fun execute(sqlExec: SqlExecutor, args: List<RtValue>): RtValue {
        val env = RtEnv(sqlExec, false)
        processArgs(name, params, args, env)

        val res = body.execute(env)
        if (res == null || !(res is RStatementResult_Return)) {
            throw IllegalStateException("No return value")
        }

        return res.value
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
        val queries: List<RQuery>
)
