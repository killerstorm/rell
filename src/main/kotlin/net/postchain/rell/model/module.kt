package net.postchain.rell.model

import net.postchain.rell.runtime.RtEnv
import net.postchain.rell.runtime.RtErrWrongArgumentType
import net.postchain.rell.runtime.RtErrWrongNumberOfArguments
import net.postchain.rell.runtime.RtValue
import net.postchain.rell.sql.SqlExecutor
import java.lang.IllegalStateException

class RAttrib(val name: String, val type: RType)

class RExternalParam(val name: String, val type: RType, val offset: Int)

class ROperation(val name: String, val params: Array<RAttrib>, val statements: Array<RStatement>)

class RQuery(val name: String, val params: Array<RExternalParam>, val statements: Array<RStatement>) {
    fun execute(sqlExec: SqlExecutor, args: Array<RtValue>): RtValue {
        if (args.size != params.size) {
            throw RtErrWrongNumberOfArguments(
                    "Wrong number of arguments for query $name: ${args.size} instead of ${params.size}")
        }

        val env = RtEnv(sqlExec)
        for (i in 0 .. params.size - 1) {
            val param = params[i]
            val arg = args[i]
            val argType = arg.type()
            if (argType != param.type) {
                throw RtErrWrongArgumentType("Wrong type of argument ${param.name} for query $name: " +
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
        val classes: Array<RClass>,
        val operations: Array<ROperation>,
        val queries: Array<RQuery>
)
