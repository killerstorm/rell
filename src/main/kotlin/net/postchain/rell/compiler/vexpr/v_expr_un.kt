package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Value

sealed class V_UnaryOp(val resType: R_Type) {
    abstract fun compileR(pos: S_Pos, expr: R_Expr): R_Expr
    abstract fun compileDb(pos: S_Pos, expr: Db_Expr): Db_Expr
    open fun evaluate(value: Rt_Value): Rt_Value? = null
}

class V_UnaryOp_Plus(resType: R_Type): V_UnaryOp(resType) {
    override fun compileR(pos: S_Pos, expr: R_Expr) = expr
    override fun compileDb(pos: S_Pos, expr: Db_Expr) = expr
    override fun evaluate(value: Rt_Value) = value
}

class V_UnaryOp_Minus(resType: R_Type, val rOp: R_UnaryOp, val dbOp: Db_UnaryOp): V_UnaryOp(resType) {
    override fun compileR(pos: S_Pos, expr: R_Expr) = R_UnaryExpr(resType, rOp, expr)
    override fun compileDb(pos: S_Pos, expr: Db_Expr) = Db_UnaryExpr(resType, dbOp, expr)
    override fun evaluate(value: Rt_Value) = rOp.evaluate(value)
}

class V_UnaryOp_Not: V_UnaryOp(R_BooleanType) {
    override fun compileR(pos: S_Pos, expr: R_Expr) = R_UnaryExpr(R_BooleanType, R_UnaryOp_Not, expr)
    override fun compileDb(pos: S_Pos, expr: Db_Expr) = Db_UnaryExpr(R_BooleanType, Db_UnaryOp_Not, expr)
    override fun evaluate(value: Rt_Value) = R_UnaryOp_Not.evaluate(value)
}

class V_UnaryOp_NotNull(resType: R_Type): V_UnaryOp(resType) {
    override fun compileR(pos: S_Pos, expr: R_Expr) = R_NotNullExpr(resType, expr)
    override fun compileDb(pos: S_Pos, expr: Db_Expr) = throw C_Error.stop(pos, "expr:is_null:nodb", "Not supported for SQL")
}

class V_UnaryOp_IsNull: V_UnaryOp(R_BooleanType) {
    override fun compileR(pos: S_Pos, expr: R_Expr) = R_BinaryExpr(R_BooleanType, R_BinaryOp_Ne, expr, R_ConstantExpr.makeNull())
    override fun compileDb(pos: S_Pos, expr: Db_Expr) = throw C_Error.stop(pos, "expr:is_null:nodb", "Not supported for SQL")
}

class V_UnaryExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val op: V_UnaryOp,
        private val expr: V_Expr,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    private val isDb = isDb(expr)
    private val atDependencies = expr.atDependencies()

    override fun type() = op.resType
    override fun isDb() = isDb
    override fun atDependencies() = atDependencies
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rExpr = expr.toRExpr()
        return op.compileR(pos, rExpr)
    }

    override fun toDbExpr0(): Db_Expr {
        val dbExpr = expr.toDbExpr()
        return op.compileDb(pos, dbExpr)
    }

    override fun constantValue(): Rt_Value? {
        val v = expr.constantValue()
        if (v == null) return null

        val res = op.evaluate(v)
        return res
    }
}

class V_IncDecExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val destination: C_Destination,
        private val resType: R_Type,
        private val srcExpr: R_Expr,
        private val op: C_AssignOp,
        private val post: Boolean,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    override fun type() = resType
    override fun isDb() = false
    override fun varFacts() = varFacts
    override fun toRExpr0() = destination.compileAssignExpr(exprCtx, pos, resType, srcExpr, op, post)
}
