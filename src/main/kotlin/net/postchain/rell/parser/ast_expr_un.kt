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
        val type = expr.type()
        if (type != R_IntegerType) {
            throw errTypeMissmatch(opPos, type)
        }
        return expr
    }
}

object S_UnaryOp_Minus: S_UnaryOp("-") {
    override fun compile(startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
        val type = expr.type()
        if (type != R_IntegerType) {
            throw errTypeMissmatch(opPos, type)
        }

        if (expr.isDb()) {
            val dbExpr = Db_UnaryExpr(R_IntegerType, Db_UnaryOp_Minus, expr.toDbExpr())
            return C_DbExpr(startPos, dbExpr)
        } else {
            val rExpr = R_UnaryExpr(R_IntegerType, R_UnaryOp_Minus, expr.toRExpr())
            return C_RExpr(startPos, rExpr)
        }
    }
}

object S_UnaryOp_Not: S_UnaryOp("not") {
    override fun compile(startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
        val type = expr.type()
        if (type != R_BooleanType) {
            throw errTypeMissmatch(opPos, type)
        }

        if (expr.isDb()) {
            val dbExpr = Db_UnaryExpr(R_BooleanType, Db_UnaryOp_Not, expr.toDbExpr())
            return C_DbExpr(startPos, dbExpr)
        } else {
            val rExpr = R_UnaryExpr(R_BooleanType, R_UnaryOp_Not, expr.toRExpr())
            return C_RExpr(startPos, rExpr)
        }
    }
}

object S_UnaryOp_NotNull: S_UnaryOp("!!") {
    override fun compile(startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
        val type = expr.type()
        if (type !is R_NullableType) {
            throw errTypeMissmatch(opPos, type)
        }

        val rExpr = R_NotNullExpr(type.valueType, expr.toRExpr())
        return C_RExpr(startPos, rExpr)
    }
}

class S_UnaryExpr(startPos: S_Pos, val op: S_Node<S_UnaryOp>, val expr: S_Expr): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        val cExpr = expr.compile(ctx)
        checkUnitType(cExpr.type())
        return op.value.compile(startPos, op.pos, cExpr)
    }

    private fun checkUnitType(type: R_Type) = C_Utils.checkUnitType(op.pos, type, "expr_operand_unit",
            "Operand of '${op.value.code}' returns nothing")
}
