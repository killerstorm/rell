package net.postchain.rell.parser

import net.postchain.rell.model.*

sealed class S_UnaryOp(val code: String) {
    abstract fun compile(pos: S_Pos, expr: RExpr): RExpr
    abstract fun compileDb(pos: S_Pos, expr: DbExpr): DbExpr

    fun errTypeMissmatch(pos: S_Pos, type: RType): C_Error {
        return C_Error(pos, "unop_operand_type:$code:$type", "Wrong operand type for '$code': $type")
    }
}

object S_UnaryOp_Plus: S_UnaryOp("+") {
    override fun compile(pos: S_Pos, expr: RExpr): RExpr {
        if (expr.type != RIntegerType) {
            throw errTypeMissmatch(pos, expr.type)
        }
        return expr
    }

    override fun compileDb(pos: S_Pos, expr: DbExpr): DbExpr {
        if (expr.type != RIntegerType) {
            throw errTypeMissmatch(pos, expr.type)
        }
        return expr
    }
}

object S_UnaryOp_Minus: S_UnaryOp("-") {
    override fun compile(pos: S_Pos, expr: RExpr): RExpr {
        if (expr.type != RIntegerType) {
            throw errTypeMissmatch(pos, expr.type)
        }
        return RUnaryExpr(RIntegerType, RUnaryOp_Minus, expr)
    }

    override fun compileDb(pos: S_Pos, expr: DbExpr): DbExpr {
        if (expr.type != RIntegerType) {
            throw errTypeMissmatch(pos, expr.type)
        }
        return UnaryDbExpr(RIntegerType, DbUnaryOp_Minus, expr)
    }
}

object S_UnaryOp_Not: S_UnaryOp("not") {
    override fun compile(pos: S_Pos, expr: RExpr): RExpr {
        if (expr.type != RBooleanType) {
            throw errTypeMissmatch(pos, expr.type)
        }
        return RUnaryExpr(RBooleanType, RUnaryOp_Not, expr)
    }

    override fun compileDb(pos: S_Pos, expr: DbExpr): DbExpr {
        if (expr.type != RBooleanType) {
            throw errTypeMissmatch(pos, expr.type)
        }
        return UnaryDbExpr(RBooleanType, DbUnaryOp_Not, expr)
    }
}

object S_UnaryOp_NotNull: S_UnaryOp("!!") {
    override fun compile(pos: S_Pos, expr: RExpr): RExpr {
        val type = expr.type
        if (type !is RNullableType) {
            throw errTypeMissmatch(pos, type)
        }
        return RNotNullExpr(type.valueType, expr)
    }

    override fun compileDb(pos: S_Pos, expr: DbExpr): DbExpr {
        throw errTypeMissmatch(pos, expr.type)
    }
}

class S_UnaryExpr(startPos: S_Pos, val op: S_Node<S_UnaryOp>, val expr: S_Expr): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext): RExpr {
        val rExpr = expr.compile(ctx)
        checkUnitType(rExpr.type)
        return op.value.compile(op.pos, rExpr)
    }

    override fun compileDb(ctx: C_DbExprContext): DbExpr {
        val dbExpr = expr.compileDb(ctx)
        checkUnitType(dbExpr.type)

        //TODO don't use "is"
        if (dbExpr is InterpretedDbExpr) {
            val rExpr = op.value.compile(op.pos, dbExpr.expr)
            return InterpretedDbExpr(rExpr)
        } else {
            return op.value.compileDb(op.pos, dbExpr)
        }
    }

    private fun checkUnitType(type: RType) = C_Utils.checkUnitType(op.pos, type, "expr_operand_unit",
            "Operand of '${op.value.code}' returns nothing")
}
