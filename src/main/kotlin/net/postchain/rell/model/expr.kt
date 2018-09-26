package net.postchain.rell.model

import net.postchain.rell.runtime.RtEnv
import net.postchain.rell.runtime.RtIntValue
import net.postchain.rell.runtime.RtTextValue
import net.postchain.rell.runtime.RtValue

sealed class RExpr(val type: RType) {
    abstract fun evaluate(env: RtEnv): RtValue
}

class RVarRef(type: RType, val offset: Int, val attr: RAttrib): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue {
        val value = env.get(offset)
        return value
    }
}

class RAtExpr(type: RType, val cls: RClass, val attrConditions: List<Pair<RAttrib, RExpr>>): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RBinOpExpr(type: RType, val op: String, val left: RExpr, val right: RExpr): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RStringLiteral(type: RType, literal: String): RExpr(type) {
    val value = RtTextValue(literal)
    override fun evaluate(env: RtEnv): RtValue = value
}

class RByteALiteral(type: RType, val literal: ByteArray): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RIntegerLiteral(type: RType, literal: Long): RExpr(type) {
    val value = RtIntValue(literal)
    override fun evaluate(env: RtEnv): RtValue = value
}

class RFunCallExpr(type: RType, val fname: String, val args: List<RExpr>): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RAttrExpr(val attr: RAttrib, val expr: RExpr) {
}

class RLambda(type: RType, val args: List<RAttrib>, val expr: RExpr): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

// TODO: RFuncall is probably unnecessary
class RFuncall(type: RType, val lambdaExpr: RLambda, val args: List<RExpr>): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}
