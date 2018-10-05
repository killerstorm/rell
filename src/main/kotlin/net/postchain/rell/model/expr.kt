package net.postchain.rell.model

import net.postchain.rell.runtime.*

abstract class RExpr(val type: RType) {
    abstract fun evaluate(env: RtEnv): RtValue
}

class RVarExpr(val attr: RAttrib, val offset: Int): RExpr(attr.type) {
    override fun evaluate(env: RtEnv): RtValue {
        val value = env.get(offset)
        return value
    }
}

sealed class RBinaryOp(val code: String) {
    abstract fun evaluate(left: RtValue, right: RtValue): RtValue
}

sealed class RBinaryOp_Eq: RBinaryOp("==")

object RBinaryOp_Eq_Text: RBinaryOp_Eq() {
    override fun evaluate(left: RtValue, right: RtValue): RtValue = RtBooleanValue(left.asString() == right.asString())
}

class RBinaryExpr(type: RType, val op: RBinaryOp, val left: RExpr, val right: RExpr): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue {
        val leftValue = left.evaluate(env)
        val rightValue = right.evaluate(env)
        val resValue = op.evaluate(leftValue, rightValue)
        return resValue
    }
}

class RStringLiteralExpr(type: RType, literal: String): RExpr(type) {
    val value = RtTextValue(literal)
    override fun evaluate(env: RtEnv): RtValue = value
}

class RByteArrayLiteralExpr(type: RType, val literal: ByteArray): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RIntegerLiteralExpr(type: RType, literal: Long): RExpr(type) {
    val value = RtIntValue(literal)
    override fun evaluate(env: RtEnv): RtValue = value
}

class RFunCallExpr(type: RType, val fname: String, val args: List<RExpr>): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RAttrExprPair(val attr: RAttrib, val expr: RExpr) {
}

class RLambdaExpr(type: RType, val args: List<RAttrib>, val expr: RExpr): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

// TODO: RFuncall is probably unnecessary
class RFuncall(type: RType, val lambdaExpr: RLambdaExpr, val args: List<RExpr>): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}
