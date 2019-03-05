package net.postchain.rell.parser

import net.postchain.rell.model.*

sealed class S_UnaryOp(val code: String) {
    abstract fun compile(startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr

    fun errTypeMissmatch(pos: S_Pos, type: R_Type): C_Error {
        return C_Error(pos, "unop_operand_type:$code:$type", "Wrong operand type for '$code': $type")
    }
}

object S_UnaryOp_Plus: S_UnaryOp("+") {
    override fun compile(startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
        val type = expr.value().type()
        if (type != R_IntegerType) {
            throw errTypeMissmatch(opPos, type)
        }
        return expr
    }
}

object S_UnaryOp_Minus: S_UnaryOp("-") {
    override fun compile(startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
        val value = expr.value()
        val type = value.type()
        if (type != R_IntegerType) {
            throw errTypeMissmatch(opPos, type)
        }

        if (value.isDb()) {
            val dbExpr = Db_UnaryExpr(R_IntegerType, Db_UnaryOp_Minus, value.toDbExpr())
            return C_DbExpr(startPos, dbExpr)
        } else {
            val rExpr = R_UnaryExpr(R_IntegerType, R_UnaryOp_Minus, value.toRExpr())
            return C_RExpr(startPos, rExpr)
        }
    }
}

object S_UnaryOp_Not: S_UnaryOp("not") {
    override fun compile(startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
        val value = expr.value()
        val type = value.type()
        if (type != R_BooleanType) {
            throw errTypeMissmatch(opPos, type)
        }

        if (value.isDb()) {
            val dbExpr = Db_UnaryExpr(R_BooleanType, Db_UnaryOp_Not, value.toDbExpr())
            return C_DbExpr(startPos, dbExpr)
        } else {
            val rExpr = R_UnaryExpr(R_BooleanType, R_UnaryOp_Not, value.toRExpr())
            return C_RExpr(startPos, rExpr)
        }
    }
}

object S_UnaryOp_NotNull: S_UnaryOp("!!") {
    override fun compile(startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
        val value = expr.value()
        val type = value.type()
        if (type !is R_NullableType) {
            throw errTypeMissmatch(opPos, type)
        }

        val rExpr = R_NotNullExpr(type.valueType, value.toRExpr())
        return C_RExpr(startPos, rExpr)
    }
}

class S_UnaryExpr(startPos: S_Pos, val op: S_Node<S_UnaryOp>, val expr: S_Expr): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        val cExpr = expr.compile(ctx)
        checkUnitType(cExpr.value().type())
        return op.value.compile(startPos, op.pos, cExpr)
    }

    private fun checkUnitType(type: R_Type) = C_Utils.checkUnitType(op.pos, type, "expr_operand_unit",
            "Operand of '${op.value.code}' returns nothing")
}
