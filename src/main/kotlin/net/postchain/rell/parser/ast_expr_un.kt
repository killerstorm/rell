package net.postchain.rell.parser

import net.postchain.rell.model.*

sealed class S_UnaryOp(val code: String) {
    abstract fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr

    fun errTypeMissmatch(pos: S_Pos, type: R_Type): C_Error {
        return C_Error(pos, "unop_operand_type:$code:$type", "Wrong operand type for '$code': $type")
    }
}

object S_UnaryOp_Plus: S_UnaryOp("+") {
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
        val type = expr.value().type()
        if (type != R_IntegerType) {
            throw errTypeMissmatch(opPos, type)
        }

        // Cannot simply return "expr", because then expressions like "(+x)++" or "(+x) = 123" will be allowed.
        val value = expr.value()
        if (value.isDb()) {
            val dbExpr = value.toDbExpr()
            return C_DbExpr(expr.startPos(), dbExpr)
        } else {
            val rExpr = value.toRExpr()
            return C_RExpr(expr.startPos(), rExpr)
        }
    }
}

object S_UnaryOp_Minus: S_UnaryOp("-") {
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
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
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
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

class S_UnaryOp_IncDec(val inc: Boolean, val post: Boolean): S_UnaryOp(if (inc) "++" else "--") {
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
        var dst = expr.value().destination(ctx)

        val type = dst.type()

        val op = if (inc) {
            C_AssignOp(opPos, "++", R_BinaryOp_Add, Db_BinaryOp_Add)
        } else {
            C_AssignOp(opPos, "--", R_BinaryOp_Sub, Db_BinaryOp_Sub)
        }

        if (!R_IntegerType.isAssignableFrom(type)) {
            val opCode = op.code
            throw C_Error(opPos, "expr_incdec_type:$opCode:$type", "Bad operand type for '$opCode': $type")
        }

        val srcExpr = R_ConstantExpr.makeInt(1)
        return dst.compileAssignExpr(startPos, srcExpr, op, post)
    }
}

object S_UnaryOp_NotNull: S_UnaryOp("!!") {
    override fun compile(ctx: C_ExprContext, startPos: S_Pos, opPos: S_Pos, expr: C_Expr): C_Expr {
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
        return op.value.compile(ctx, startPos, op.pos, cExpr)
    }

    private fun checkUnitType(type: R_Type) = C_Utils.checkUnitType(op.pos, type, "expr_operand_unit",
            "Operand of '${op.value.code}' returns nothing")
}
