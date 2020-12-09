/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_DecimalValue

sealed class S_UnaryOp(val code: String) {
    abstract fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: V_Expr): V_Expr

    fun errTypeMismatch(ctx: C_ExprContext, pos: S_Pos, type: R_Type) {
        if (type != R_CtErrorType) {
            ctx.msgCtx.error(pos, "unop_operand_type:$code:[$type]", "Wrong operand type for '$code': $type")
        }
    }
}

object S_UnaryOp_Plus: S_UnaryOp("+") {
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: V_Expr): V_Expr {
        val type = expr.type()
        if (type != R_IntegerType && type != R_DecimalType) {
            errTypeMismatch(ctx, opPos, type)
        }

        // Cannot simply return "expr", because then expressions like "(+x)++" or "(+x) = 123" will be allowed.
        val vOp = V_UnaryOp_Plus(type)
        val varFacts = C_ExprVarFacts.of(postFacts = expr.varFacts().postFacts)
        return V_UnaryExpr(startPos, vOp, expr, varFacts)
    }
}

object S_UnaryOp_Minus: S_UnaryOp("-") {
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: V_Expr): V_Expr {
        val type = expr.type()

        val vOp = when (type) {
            R_IntegerType -> V_UnaryOp_Minus(type, R_UnaryOp_Minus_Integer, Db_UnaryOp_Minus_Integer)
            R_DecimalType -> V_UnaryOp_Minus(type, R_UnaryOp_Minus_Decimal, Db_UnaryOp_Minus_Decimal)
            else -> {
                errTypeMismatch(ctx, opPos, type)
                V_UnaryOp_Minus(type, R_UnaryOp_Minus_Integer, Db_UnaryOp_Minus_Integer) // Fake op for error recovery.
            }
        }

        val varFacts = C_ExprVarFacts.of(postFacts = expr.varFacts().postFacts)
        return V_UnaryExpr(startPos, vOp, expr, varFacts)
    }
}

object S_UnaryOp_Not: S_UnaryOp("not") {
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: V_Expr): V_Expr {
        val type = expr.type()
        if (type != R_BooleanType) {
            errTypeMismatch(ctx, opPos, type)
        }

        val varFacts = expr.varFacts()
        val resVarFacts = C_ExprVarFacts.of(
                trueFacts = varFacts.falseFacts,
                falseFacts = varFacts.trueFacts,
                postFacts = varFacts.postFacts
        )

        return V_UnaryExpr(startPos, V_UnaryOp_Not(), expr, resVarFacts)
    }
}

class S_UnaryOp_IncDec(val inc: Boolean, val post: Boolean): S_UnaryOp(if (inc) "++" else "--") {
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: V_Expr): V_Expr {
        val dst = expr.destination(ctx)
        val dstType = dst.effectiveType()

        val ops = if (R_IntegerType.isAssignableFrom(dstType)) {
            Pair(INTEGER_INCREMENT, INTEGER_DECREMENT)
        } else if (R_DecimalType.isAssignableFrom(dstType)) {
            Pair(DECIMAL_INCREMENT, DECIMAL_DECREMENT)
        } else {
            val opCode = if (inc) "++" else "--"
            ctx.msgCtx.error(opPos, "expr_incdec_type:$opCode:$dstType", "Bad operand type for '$opCode': $dstType")
            Pair(INTEGER_INCREMENT, INTEGER_DECREMENT) // Fake ops for recovery.
        }

        val idOp = if (inc) ops.first else ops.second
        val op = C_AssignOp(opPos, idOp.op, idOp.rOp, idOp.dbOp)

        val resType = dst.resultType(expr.type())
        val varFacts = C_ExprVarFacts.of(postFacts = expr.varFacts().postFacts)
        return V_IncDecExpr(startPos, dst, resType, idOp.srcExpr, op, post, varFacts)
    }

    companion object {
        private val INTEGER_ONE = R_ConstantExpr.makeInt(1)
        private val DECIMAL_ONE = R_ConstantExpr(Rt_DecimalValue.of(1))

        private val INTEGER_INCREMENT = C_IncDecOp("++", R_BinaryOp_Add_Integer, Db_BinaryOp_Add_Integer, INTEGER_ONE)
        private val INTEGER_DECREMENT = C_IncDecOp("--", R_BinaryOp_Sub_Integer, Db_BinaryOp_Sub_Integer, INTEGER_ONE)
        private val DECIMAL_INCREMENT = C_IncDecOp("++", R_BinaryOp_Add_Decimal, Db_BinaryOp_Add_Decimal, DECIMAL_ONE)
        private val DECIMAL_DECREMENT = C_IncDecOp("--", R_BinaryOp_Sub_Decimal, Db_BinaryOp_Sub_Decimal, DECIMAL_ONE)
    }

    private class C_IncDecOp(val op: String, val rOp: R_BinaryOp, val dbOp: Db_BinaryOp, val srcExpr: R_Expr)
}

object S_UnaryOp_NotNull: S_UnaryOp("!!") {
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: V_Expr): V_Expr {
        val value = expr.asNullable()
        val type = value.type()
        val valueType = if (type is R_NullableType) type.valueType else {
            errTypeMismatch(ctx, opPos, type)
            type
        }

        val preFacts = value.varFacts().postFacts
        val varFacts = C_ExprVarFacts.forNullCast(preFacts, value)
        return V_UnaryExpr(startPos, V_UnaryOp_NotNull(valueType), expr, varFacts)
    }
}

object S_UnaryOp_IsNull: S_UnaryOp("??") {
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: V_Expr): V_Expr {
        val value = expr.asNullable()
        val type = value.type()
        if (type !is R_NullableType) {
            errTypeMismatch(ctx, opPos, type)
        }

        val preFacts = value.varFacts()
        val varFacts = C_ExprVarFacts.forNullCheck(value, false).update(postFacts = preFacts.postFacts)
        return V_UnaryExpr(startPos, V_UnaryOp_IsNull(), expr, varFacts)
    }
}

class S_UnaryExpr(startPos: S_Pos, val op: S_PosValue<S_UnaryOp>, val expr: S_Expr): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val cExpr = expr.compile(ctx)
        val vExpr = cExpr.value()
        checkUnitType(vExpr.type())
        val vResExpr = op.value.compile(ctx, startPos, op.pos, vExpr)
        return C_VExpr(vResExpr)
    }

    private fun checkUnitType(type: R_Type) = C_Utils.checkUnitType(op.pos, type, "expr_operand_unit",
            "Operand of '${op.value.code}' returns nothing")
}
