package net.postchain.rell.model

import com.google.common.base.Preconditions
import net.postchain.rell.runtime.RtEnv
import net.postchain.rell.runtime.RtValue
import net.postchain.rell.sql.SqlExecutor

class RAttrib(val name: String, val type: RType)

class ROperation(val name: String, val params: Array<RAttrib>, val statements: Array<RStatement>)

class RQuery(val name: String, val params: Array<Int>, val statements: Array<RStatement>) {
    fun execute(sqlExec: SqlExecutor, args: Array<RtValue>): RtValue? {
        Preconditions.checkArgument(args.size == params.size, "Wrong number of arguments: %s instead of %s",
                args.size, params.size)

        val env = RtEnv(sqlExec)
        for (i in 0 .. params.size - 1) {
            val offset = params[i]
            val value = args[i]
            env.set(offset, value)
        }

        for (stmt in statements) {
            val res = stmt.execute(env)
            if (res != null) {
                return res
            }
        }

        return null
    }
}

class RModule(
        val classes: Array<RClass>,
        val operations: Array<ROperation>,
        val queries: Array<RQuery>
)
