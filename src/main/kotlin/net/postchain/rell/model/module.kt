package net.postchain.rell.model

import net.postchain.rell.runtime.RtEnv
import net.postchain.rell.runtime.RtError
import net.postchain.rell.runtime.RtValue
import net.postchain.rell.sql.SqlExecutor
import java.lang.IllegalStateException

class RAttrib(val name: String, val type: RType)

class RExternalParam(val name: String, val type: RType, val offset: Int)

class ROperation(val name: String, val params: Array<RAttrib>, val statements: Array<RStatement>)

class RQuery(val name: String, val params: Array<RExternalParam>, val statements: Array<RStatement>) {
    fun execute(sqlExec: SqlExecutor, args: Array<RtValue>): RtValue {
        if (args.size != params.size) {
            throw RtError("query_wrong_arg_count:$name:${params.size}:${args.size}",
                    "Wrong number of arguments for query $name: ${args.size} instead of ${params.size}")
        }

        val env = RtEnv(sqlExec)
        for (i in 0 .. params.size - 1) {
            val param = params[i]
            val arg = args[i]
            val argType = arg.type()
            if (argType != param.type) {
                throw RtError("query_wrong_arg_type:$name:${param.type.toStrictString()}:${argType.toStrictString()}",
                        "Wrong type of argument ${param.name} for query $name: " +
                        "${argType.toStrictString()} instead of ${param.type.toStrictString()}")
            }
            env.set(param.offset, arg)
        }

        for (stmt in statements) {
            val res = stmt.execute(env)
            if (res != null) {
                return res
            }
        }

        throw IllegalStateException("No return value")
    }
}

class RModule(
        val classes: List<RClass>,
        val operations: List<ROperation>,
        val queries: List<RQuery>
)
