package net.postchain.rell.model

import net.postchain.rell.runtime.RtBooleanValue
import net.postchain.rell.runtime.RtCallFrame
import net.postchain.rell.runtime.RtIntValue
import net.postchain.rell.runtime.RtValue

sealed class RUnaryOp {
    abstract fun evaluate(operand: RtValue): RtValue
}

object RUnaryOp_Minus: RUnaryOp() {
    override fun evaluate(operand: RtValue): RtValue {
        val v = operand.asInteger()
        return RtIntValue(-v)
    }
}

object RUnaryOp_Not: RUnaryOp() {
    override fun evaluate(operand: RtValue): RtValue {
        val v = operand.asBoolean()
        return RtBooleanValue(!v)
    }
}

class RUnaryExpr(type: RType, val op: RUnaryOp, val expr: RExpr): RExpr(type) {
    override fun evaluate(frame: RtCallFrame): RtValue {
        val operand = expr.evaluate(frame)
        val resValue = op.evaluate(operand)
        return resValue
    }
}
