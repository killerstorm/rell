/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model.expr

import net.postchain.rell.model.R_Type
import net.postchain.rell.runtime.Rt_CallFrame

class Db_WhenCase(val conds: List<Db_Expr>, val expr: Db_Expr)

class Db_WhenExpr(type: R_Type, val keyExpr: Db_Expr?, val cases: List<Db_WhenCase>, val elseExpr: Db_Expr): Db_Expr(type) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val redKeyExpr = keyExpr?.toRedExpr(frame)

        val internalCases = mutableListOf<Pair<RedDb_Expr, Db_Expr>>()
        val matchedCase = if (redKeyExpr != null) {
            makeRedCasesKeyed(frame, redKeyExpr, internalCases)
        } else {
            makeRedCasesGeneral(frame, internalCases)
        }

        if (matchedCase != null) {
            val redExpr = matchedCase.expr.toRedExpr(frame)
            return redExpr
        }

        val redCases = internalCases.map { (redCond, expr) ->
            val redExpr = expr.toRedExpr(frame)
            RedDb_WhenCase(redCond, redExpr)
        }

        val redElse = elseExpr.toRedExpr(frame)
        if (redCases.isEmpty()) {
            return redElse
        }

        return RedDb_WhenExpr(redCases, redElse)
    }

    private fun makeRedCasesKeyed(
            frame: Rt_CallFrame,
            redKeyExpr: RedDb_Expr,
            resCases: MutableList<Pair<RedDb_Expr, Db_Expr>>
    ): Db_WhenCase? {
        for (case in cases) {
            val matched = makeRedCaseKeyed(frame, redKeyExpr, case, resCases)
            if (matched) {
                return case
            }
        }
        return null
    }

    private fun makeRedCaseKeyed(
            frame: Rt_CallFrame,
            redKeyExpr: RedDb_Expr,
            case: Db_WhenCase,
            resCases: MutableList<Pair<RedDb_Expr, Db_Expr>>
    ): Boolean {
        val redConds = Db_InExpr.toRedExprs(frame, redKeyExpr, case.conds)

        if (redConds == null) {
            return true
        }

        if (!redConds.isEmpty()) {
            val redCond = RedDb_Utils.makeRedDbInExpr(redKeyExpr, redConds, false)
            resCases.add(Pair(redCond, case.expr))
        }

        return false
    }

    private fun makeRedCasesGeneral(frame: Rt_CallFrame, resCases: MutableList<Pair<RedDb_Expr, Db_Expr>>): Db_WhenCase? {
        for (case in cases) {
            val redConds = mutableListOf<RedDb_Expr>()
            for (cond in case.conds) {
                val redCond = cond.toRedExpr(frame)
                val condValue = redCond.constantValue()
                if (condValue != null) {
                    if (condValue.asBoolean()) {
                        return case
                    }
                } else {
                    redConds.add(redCond)
                }
            }

            if (!redConds.isEmpty()) {
                val redCond = RedDb_Utils.makeRedDbBinaryExprChain(Db_BinaryOp_Or, redConds)
                resCases.add(Pair(redCond, case.expr))
            }
        }

        return null
    }
}

private class RedDb_WhenCase(val cond: RedDb_Expr, val expr: RedDb_Expr)

private class RedDb_WhenExpr(val cases: List<RedDb_WhenCase>, val elseExpr: RedDb_Expr): RedDb_Expr() {
    override fun needsEnclosing() = false

    override fun toSql0(ctx: SqlGenContext, bld: SqlBuilder) {
        bld.append("CASE")

        for (case in cases) {
            bld.append(" WHEN ")
            case.cond.toSql(ctx, bld, false)
            bld.append(" THEN ")
            case.expr.toSql(ctx, bld, false)
        }

        bld.append(" ELSE ")
        elseExpr.toSql(ctx, bld, false)

        bld.append(" END")
    }
}
