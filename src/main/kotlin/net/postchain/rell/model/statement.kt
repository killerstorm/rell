package net.postchain.rell.model

import net.postchain.rell.runtime.RtEnv
import net.postchain.rell.runtime.RtValue

sealed class RStatement {
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

class RCreateStatement(val rclass: RClass, val attrs: Array<RAttrExpr>): RStatement() {
    override fun execute(env: RtEnv): RtValue? = TODO("TODO")
}

class RUpdateStatement(val atExpr: RAtExpr, val setAttrs: Array<RAttrExpr>): RStatement() {
    override fun execute(env: RtEnv): RtValue? = TODO("TODO")
}

class RDeleteStatement(val atExpr: RAtExpr): RStatement() {
    override fun execute(env: RtEnv): RtValue? = TODO("TODO")
}
