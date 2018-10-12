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

class RBooleanLiteralExpr(literal: Boolean): RExpr(RBooleanType) {
    val value = RtBooleanValue(literal)
    override fun evaluate(env: RtEnv): RtValue = value
}

class RTupleFieldExpr(type: RType, val baseExpr: RExpr, val fieldIndex: Int): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue {
        val baseValue = baseExpr.evaluate(env)
        val tupleValue = baseValue as RtTupleValue
        return tupleValue.elements[fieldIndex]
    }
}

class RFunCallExpr(type: RType, val fname: String, val args: List<RExpr>): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RLambdaExpr(type: RType, val args: List<RAttrib>, val expr: RExpr): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

// TODO: RFuncall is probably unnecessary
class RFuncall(type: RType, val lambdaExpr: RLambdaExpr, val args: List<RExpr>): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}
