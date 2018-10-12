package net.postchain.rell.model

import net.postchain.rell.runtime.RtEnv
import net.postchain.rell.runtime.RtError
import net.postchain.rell.runtime.RtValue
import net.postchain.rell.sql.SqlExecutor
import java.lang.IllegalStateException

class RAttrib(val name: String, val type: RType)

class RExternalParam(val name: String, val type: RType, val offset: Int)

class ROperation(val name: String, val params: List<RExternalParam>, val body: RStatement) {
    fun execute(sqlExec: SqlExecutor, args: Array<RtValue>) {
        val env = RtEnv(sqlExec)
        processArgs(name, params, args, env)

        val res = body.execute(env)
        check(res == null)
    }
}

class RQuery(val name: String, val params: List<RExternalParam>, val body: RStatement) {
    fun execute(sqlExec: SqlExecutor, args: Array<RtValue>): RtValue {
        val env = RtEnv(sqlExec)
        processArgs(name, params, args, env)

        val res = body.execute(env)
        checkNotNull(res)

        return res!!
    }
}

private fun processArgs(name: String, params: List<RExternalParam>, args: Array<RtValue>, env: RtEnv) {
    if (args.size != params.size) {
        throw RtError("fn_wrong_arg_count:$name:${params.size}:${args.size}",
                "Wrong number of arguments for '$name': ${args.size} instead of ${params.size}")
    }

    for (i in 0 .. params.size - 1) {
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
