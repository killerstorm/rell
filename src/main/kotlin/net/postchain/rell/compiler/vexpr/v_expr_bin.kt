package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.C_ExprContext
import net.postchain.rell.compiler.C_ExprVarFacts
import net.postchain.rell.compiler.C_Utils
import net.postchain.rell.compiler.ast.C_BinOp
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*

class V_BinaryOp(val code: String, val resType: R_Type, val rOp: R_BinaryOp, val dbOp: Db_BinaryOp?) {
    companion object {
        fun of(resType: R_Type, rOp: R_BinaryOp, dbOp: Db_BinaryOp?) = V_BinaryOp(rOp.code, resType, rOp, dbOp)
    }
}

class V_BinaryExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val op: V_BinaryOp,
        private val left: V_Expr,
        private val right: V_Expr,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    override val exprInfo = V_ExprInfo.make(listOf(left, right), canBeDbExpr = op.dbOp != null)

    override fun type() = op.resType
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rLeft = left.toRExpr()
        val rRight = right.toRExpr()
        return R_BinaryExpr(op.resType, op.rOp, rLeft, rRight)
    }

    override fun toDbExpr0(): Db_Expr {
        val dbLeft = left.toDbExpr()
        val dbRight = right.toDbExpr()
        return if (op.dbOp == null) {
            C_BinOp.errTypeMismatch(msgCtx, pos, op.code, left.type(), right.type())
            C_Utils.errorDbExpr(op.resType)
        } else {
            Db_BinaryExpr(op.resType, op.dbOp, dbLeft, dbRight)
        }
    }
}

class V_ElvisExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val resType: R_Type,
        private val left: V_Expr,
        private val right: V_Expr,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    override val exprInfo = V_ExprInfo.make(listOf(left, right))

    override fun type() = resType
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rLeft = left.toRExpr()
        val rRight = right.toRExpr()
        return R_ElvisExpr(resType, rLeft, rRight)
    }

    override fun toDbExpr0(): Db_Expr {
        val rLeft = left.toRExpr() // DB-expressions cannot be nullable...
        val dbRight = right.toDbExpr()
        return Db_ElvisExpr(resType, rLeft, dbRight)
    }
}
