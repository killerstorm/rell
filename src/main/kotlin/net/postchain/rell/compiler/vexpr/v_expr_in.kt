package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.C_Errors
import net.postchain.rell.compiler.C_ExprContext
import net.postchain.rell.compiler.C_ExprVarFacts
import net.postchain.rell.compiler.C_Utils
import net.postchain.rell.compiler.ast.C_BinOp_EqNe
import net.postchain.rell.model.*

class V_InCollectionExpr(
        exprCtx: C_ExprContext,
        private val elemType: R_Type,
        private val left: V_Expr,
        private val right: V_Expr,
        private val not: Boolean,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, left.pos) {
    override val exprInfo = V_ExprInfo.make(listOf(left, right))

    override fun type() = R_BooleanType
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rLeft = left.toRExpr()
        val rRight = right.toRExpr()
        var rExpr: R_Expr = R_BinaryExpr(R_BooleanType, R_BinaryOp_In_Collection, rLeft, rRight)
        if (not) {
            rExpr = R_UnaryExpr(R_BooleanType, R_UnaryOp_Not, rExpr)
        }
        return rExpr
    }

    override fun toDbExpr0(): Db_Expr {
        val dbLeft = left.toDbExpr()

        if (!C_BinOp_EqNe.checkTypesDb(elemType, elemType) || !elemType.sqlAdapter.isSqlCompatible()) {
            C_Errors.errExprNoDb(msgCtx, pos, elemType)
            return C_Utils.errorDbExpr(R_BooleanType)
        }

        return if (isDb(right)) {
            if (right is V_ListLiteralExpr) {
                val dbRights = right.elems.map { it.toDbExpr() }
                Db_InExpr(dbLeft, dbRights, not)
            } else {
                val dbRight = right.toDbExpr()
                val op = if (not) Db_BinaryOp_NotIn else Db_BinaryOp_In
                Db_BinaryExpr(R_BooleanType, op, dbLeft, dbRight)
            }
        } else {
            val rRight = right.toRExpr()
            Db_InCollectionExpr(dbLeft, rRight, not)
        }
    }
}
