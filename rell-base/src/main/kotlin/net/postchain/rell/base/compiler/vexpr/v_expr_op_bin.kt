/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.vexpr

import net.postchain.rell.base.compiler.ast.C_BinOp
import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.expr.C_ExprUtils
import net.postchain.rell.base.compiler.base.expr.C_ExprVarFacts
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.model.expr.*
import net.postchain.rell.base.runtime.Rt_Value

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
        private val resVarFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    override fun exprInfo0() = V_ExprInfo.simple(op.resType, left, right, canBeDbExpr = op.dbOp != null)
    override fun varFacts0() = resVarFacts

    override fun constantValue(ctx: V_ConstantValueEvalContext): Rt_Value? {
        val leftValue = left.constantValue(ctx)
        leftValue ?: return null
        val rightValue = right.constantValue(ctx)
        rightValue ?: return null
        return op.rOp.evaluate(leftValue, rightValue)
    }

    override fun toRExpr0(): R_Expr {
        val rLeft = left.toRExpr()
        val rRight = right.toRExpr()
        return R_BinaryExpr(op.resType, op.rOp, rLeft, rRight)
    }

    override fun toDbExpr0(): Db_Expr {
        val dbLeft = left.toDbExpr()
        val dbRight = right.toDbExpr()
        return if (op.dbOp == null) {
            C_BinOp.errTypeMismatchDb(msgCtx, pos, op.code, dbLeft.type, dbRight.type)
            C_ExprUtils.errorDbExpr(op.resType)
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
        private val right: V_Expr
): V_Expr(exprCtx, pos) {
    override fun exprInfo0() = V_ExprInfo.simple(resType, left, right)
    override fun varFacts0() = C_ExprVarFacts.forSubExpressions(left) // left is always evaluated, right is not

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
