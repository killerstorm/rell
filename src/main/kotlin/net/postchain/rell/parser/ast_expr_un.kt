package net.postchain.rell.parser

import net.postchain.rell.model.*

class S_UnOpType(val resType: RType, val rOp: RUnaryOp?, val dbOp: DbUnaryOp?)

sealed class S_UnaryOp(val code: String) {
    abstract fun compile(expr: RExpr): RExpr
    abstract fun compileDb(expr: DbExpr): DbExpr

    fun errTypeMissmatch(type: RType): CtError {
        return CtError("unop_operand_type:$code:$type", "Wrong operand type for operator '$code': $type")
    }
}

object S_UnaryOp_Plus: S_UnaryOp("+") {
    override fun compile(expr: RExpr): RExpr {
        if (expr.type != RIntegerType) {
            throw errTypeMissmatch(expr.type)
        }
        return expr
    }

    override fun compileDb(expr: DbExpr): DbExpr {
        if (expr.type != RIntegerType) {
            throw errTypeMissmatch(expr.type)
        }
        return expr
    }
}

object S_UnaryOp_Minus: S_UnaryOp("-") {
    override fun compile(expr: RExpr): RExpr {
        if (expr.type != RIntegerType) {
            throw errTypeMissmatch(expr.type)
        }
        return RUnaryExpr(RIntegerType, RUnaryOp_Minus, expr)
    }

    override fun compileDb(expr: DbExpr): DbExpr {
        if (expr.type != RIntegerType) {
            throw errTypeMissmatch(expr.type)
        }
        return UnaryDbExpr(RIntegerType, DbUnaryOp_Minus, expr)
    }
}

object S_UnaryOp_Not: S_UnaryOp("not") {
    override fun compile(expr: RExpr): RExpr {
        if (expr.type != RBooleanType) {
            throw errTypeMissmatch(expr.type)
        }
        return RUnaryExpr(RBooleanType, RUnaryOp_Not, expr)
    }

    override fun compileDb(expr: DbExpr): DbExpr {
        if (expr.type != RBooleanType) {
            throw errTypeMissmatch(expr.type)
        }
        return UnaryDbExpr(RBooleanType, DbUnaryOp_Not, expr)
    }
}

class S_UnaryExpr(val op: S_UnaryOp, val expr: S_Expression): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr {
        val rExpr = expr.compile(ctx)
        return op.compile(rExpr)
    }

    override fun compileDb(ctx: DbCompilationContext): DbExpr {
        val dbExpr = expr.compileDb(ctx)

        //TODO don't use "is"
        if (dbExpr is InterpretedDbExpr) {
            val rExpr = op.compile(dbExpr.expr)
            return InterpretedDbExpr(rExpr)
        } else {
            return op.compileDb(dbExpr)
        }
    }
}
