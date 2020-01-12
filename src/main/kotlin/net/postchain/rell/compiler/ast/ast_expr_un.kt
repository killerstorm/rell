package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_DecimalValue

sealed class S_UnaryOp(val code: String) {
    abstract fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr

    fun errTypeMismatch(pos: S_Pos, type: R_Type): C_Error {
        return C_Error(pos, "unop_operand_type:$code:[$type]", "Wrong operand type for '$code': $type")
    }
}

object S_UnaryOp_Plus: S_UnaryOp("+") {
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
        val type = expr.value().type()
        if (type != R_IntegerType && type != R_DecimalType) {
            throw errTypeMismatch(opPos, type)
        }

        // Cannot simply return "expr", because then expressions like "(+x)++" or "(+x) = 123" will be allowed.

        val value = expr.value()

        if (value.isDb()) {
            val dbExpr = value.toDbExpr()
            return C_DbValue.makeExpr(expr.startPos(), dbExpr)
        } else {
            val rExpr = value.toRExpr()
            val varFacts = C_ExprVarFacts.of(postFacts = value.varFacts().postFacts)
            return C_RValue.makeExpr(expr.startPos(), rExpr, varFacts)
        }
    }
}

object S_UnaryOp_Minus: S_UnaryOp("-") {
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
        val value = expr.value()
        val type = value.type()

        val (rOp, dbOp) = when (type) {
            R_IntegerType -> Pair(R_UnaryOp_Minus_Integer, Db_UnaryOp_Minus_Integer)
            R_DecimalType -> Pair(R_UnaryOp_Minus_Decimal, Db_UnaryOp_Minus_Decimal)
            else -> throw errTypeMismatch(opPos, type)
        }

        if (value.isDb()) {
            val dbExpr = Db_UnaryExpr(type, dbOp, value.toDbExpr())
            return C_DbValue.makeExpr(startPos, dbExpr)
        } else {
            val rExpr = R_UnaryExpr(type, rOp, value.toRExpr())
            val varFacts = C_ExprVarFacts.of(postFacts = value.varFacts().postFacts)
            return C_RValue.makeExpr(startPos, rExpr, varFacts)
        }
    }
}

object S_UnaryOp_Not: S_UnaryOp("not") {
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
        val value = expr.value()
        val type = value.type()
        if (type != R_BooleanType) {
            throw errTypeMismatch(opPos, type)
        }

        val varFacts = value.varFacts()
        val resVarFacts = C_ExprVarFacts.of(
                trueFacts = varFacts.falseFacts,
                falseFacts = varFacts.trueFacts,
                postFacts = varFacts.postFacts
        )

        val resValue = if (value.isDb()) {
            val dbExpr = Db_UnaryExpr(R_BooleanType, Db_UnaryOp_Not, value.toDbExpr())
            C_DbValue(startPos, dbExpr, resVarFacts)
        } else {
            val rExpr = R_UnaryExpr(R_BooleanType, R_UnaryOp_Not, value.toRExpr())
            C_RValue(startPos, rExpr, resVarFacts)
        }

        return C_ValueExpr(resValue)
    }
}

class S_UnaryOp_IncDec(val inc: Boolean, val post: Boolean): S_UnaryOp(if (inc) "++" else "--") {
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
        val value = expr.value()
        var dst = value.destination(ctx)
        val dstType = dst.effectiveType()

        val idOps = if (R_IntegerType.isAssignableFrom(dstType)) {
            Pair(INTEGER_INCREMENT, INTEGER_DECREMENT)
        } else if (R_DecimalType.isAssignableFrom(dstType)) {
            Pair(DECIMAL_INCREMENT, DECIMAL_DECREMENT)
        } else {
            val opCode = if (inc) "++" else "--"
            throw C_Error(opPos, "expr_incdec_type:$opCode:$dstType", "Bad operand type for '$opCode': $dstType")
        }

        val idOp = if (inc) idOps.first else idOps.second
        val op = C_AssignOp(opPos, idOp.op, idOp.rOp, idOp.dbOp)

        val resType = value.type()
        val rExpr = dst.compileAssignExpr(startPos, resType, idOp.srcExpr, op, post)

        val varFacts = C_ExprVarFacts.of(postFacts = value.varFacts().postFacts)
        return C_RValue.makeExpr(startPos, rExpr, varFacts)
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
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
        val value = expr.value().asNullable()
        val type = value.type()
        if (type !is R_NullableType) {
            throw errTypeMismatch(opPos, type)
        }

        val rExpr = R_NotNullExpr(type.valueType, value.toRExpr())

        val preFacts = value.varFacts().postFacts
        val varFacts = C_ExprVarFacts.forNullCast(preFacts, value)

        return C_RValue.makeExpr(startPos, rExpr, varFacts)
    }
}

object S_UnaryOp_IsNull: S_UnaryOp("??") {
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
        val value = expr.value().asNullable()
        val type = value.type()
        if (type !is R_NullableType) {
            throw errTypeMismatch(opPos, type)
        }

        val rSubExpr = value.toRExpr()
        val rExpr = R_BinaryExpr(R_BooleanType, R_BinaryOp_Ne, rSubExpr, R_ConstantExpr.makeNull())

        val preFacts = value.varFacts()
        val varFacts = C_ExprVarFacts.forNullCheck(value, false).update(postFacts = preFacts.postFacts)

        return C_RValue.makeExpr(startPos, rExpr, varFacts)
    }
}

class S_UnaryExpr(startPos: S_Pos, val op: S_PosValue<S_UnaryOp>, val expr: S_Expr): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        val cExpr = expr.compile(ctx)
        checkUnitType(cExpr.value().type())
        return op.value.compile(ctx, startPos, op.pos, cExpr)
    }

    private fun checkUnitType(type: R_Type) = C_Utils.checkUnitType(op.pos, type, "expr_operand_unit",
            "Operand of '${op.value.code}' returns nothing")
}
