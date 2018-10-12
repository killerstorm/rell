package net.postchain.rell.model

import net.postchain.rell.runtime.RtEnv
import net.postchain.rell.runtime.RtValue
import net.postchain.rell.sql.ROWID_COLUMN

abstract class RStatement {
    abstract fun execute(env: RtEnv): RtValue?
}

class RValStatement(val offset: Int, val attr: RAttrib, val expr: RExpr): RStatement() {
    override fun execute(env: RtEnv): RtValue? {
        val value = expr.evaluate(env)
        env.set(offset, value)
        return null
    }
}

class RCallStatement(val expr: RFunCallExpr): RStatement() {
    override fun execute(env: RtEnv): RtValue? = TODO("TODO")
}

class RReturnStatement(val expr: RExpr): RStatement() {
    override fun execute(env: RtEnv): RtValue? {
        val value = expr.evaluate(env)
        return value
    }
}

class RBlockStatement(val stmts: List<RStatement>): RStatement() {
    override fun execute(env: RtEnv): RtValue? {
        for (stmt in stmts) {
            val res = stmt.execute(env)
            if (res != null) {
                return res
            }
        }
        return null
    }
}
