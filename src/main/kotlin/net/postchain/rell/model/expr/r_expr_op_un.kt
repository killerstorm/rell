/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model.expr

import com.google.common.math.LongMath
import net.postchain.rell.model.R_Type
import net.postchain.rell.runtime.*

sealed class R_UnaryOp {
    abstract fun evaluate(operand: Rt_Value): Rt_Value
}

object R_UnaryOp_Minus_Integer: R_UnaryOp() {
    override fun evaluate(operand: Rt_Value): Rt_Value {
        val v = operand.asInteger()

        val res = try {
            LongMath.checkedSubtract(0, v)
        } catch (e: ArithmeticException) {
            throw Rt_Exception.common("expr:-:overflow:$v", "Integer overflow: -($v)")
        }

        return Rt_IntValue(res)
    }
}

object R_UnaryOp_Minus_BigInteger: R_UnaryOp() {
    override fun evaluate(operand: Rt_Value): Rt_Value {
        val v = operand.asBigInteger()
        return Rt_BigIntegerValue.of(v.negate())
    }
}

object R_UnaryOp_Minus_Decimal: R_UnaryOp() {
    override fun evaluate(operand: Rt_Value): Rt_Value {
        val v = operand.asDecimal()
        return Rt_DecimalValue.of(v.negate())
    }
}

object R_UnaryOp_Not: R_UnaryOp() {
    override fun evaluate(operand: Rt_Value): Rt_Value {
        val v = operand.asBoolean()
        return Rt_BooleanValue(!v)
    }
}

class R_UnaryExpr(type: R_Type, val op: R_UnaryOp, val expr: R_Expr): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val operand = expr.evaluate(frame)
        val resValue = op.evaluate(operand)
        return resValue
    }
}
