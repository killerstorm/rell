package net.postchain.rell.model

import net.postchain.rell.runtime.Rt_BooleanValue
import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_IntValue
import net.postchain.rell.runtime.Rt_Value

sealed class R_UnaryOp {
    abstract fun evaluate(operand: Rt_Value): Rt_Value
}

object R_UnaryOp_Minus: R_UnaryOp() {
    override fun evaluate(operand: Rt_Value): Rt_Value {
        val v = operand.asInteger()
        return Rt_IntValue(-v)
    }
}

object R_UnaryOp_Not: R_UnaryOp() {
    override fun evaluate(operand: Rt_Value): Rt_Value {
        val v = operand.asBoolean()
        return Rt_BooleanValue(!v)
    }
}

class R_UnaryExpr(type: R_Type, val op: R_UnaryOp, val expr: R_Expr): R_Expr(type) {
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
        val operand = expr.evaluate(frame)
        val resValue = op.evaluate(operand)
        return resValue
    }

    override fun constantValue(): Rt_Value? {
        val v = expr.constantValue()
        if (v == null) return null

        val res = op.evaluate(v)
        return res
    }
}
