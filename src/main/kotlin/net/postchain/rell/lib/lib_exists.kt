/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.compiler.ast.S_Expr
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.expr.C_ExprVarFacts
import net.postchain.rell.compiler.base.fn.C_FuncMatchUtils
import net.postchain.rell.compiler.base.fn.C_SpecialSysGlobalFunction
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_ExprInfo
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.*
import net.postchain.rell.model.lib.R_SysFn_General
import net.postchain.rell.utils.checkEquals

class C_SysFn_Exists(private val not: Boolean): C_SpecialSysGlobalFunction() {
    override fun paramCount() = 1

    override fun compileCall0(ctx: C_ExprContext, name: S_Name, args: List<S_Expr>): V_Expr {
        checkEquals(1, args.size)

        val arg = args[0]

        val atCtx = ctx.atCtx
        val cArg = if (atCtx != null) {
            arg.compileNestedAt(ctx, atCtx)
        } else {
            arg.compile(ctx)
        }

        val vArg = cArg.value()
        val condition = compileCondition(vArg)
        if (condition == null) {
            C_FuncMatchUtils.errNoMatch(ctx, name.pos, name.str, listOf(vArg.type))
            return C_Utils.errorVExpr(ctx, name.pos, R_BooleanType)
        }

        val preFacts = C_ExprVarFacts.forSubExpressions(listOf(vArg))
        val varFacts = preFacts.and(C_ExprVarFacts.forNullCheck(vArg, not))

        return V_ExistsExpr(ctx, name, vArg, condition, not, varFacts)
    }

    private fun compileCondition(arg: V_Expr): R_RequireCondition? {
        val argType = arg.type

        val (_, condition) = C_SysFn_Require_Collection.getCondition(argType)
        if (condition != null) {
            return condition
        }

        val argN = arg.asNullable()
        val argTypeN = argN.type
        if (argTypeN is R_NullableType) {
            return R_RequireCondition_Nullable
        }

        return null
    }
}

private class V_ExistsExpr(
        exprCtx: C_ExprContext,
        private val name: S_Name,
        private val subExpr: V_Expr,
        private val condition: R_RequireCondition,
        private val not: Boolean,
        private val resVarFacts: C_ExprVarFacts
): V_Expr(exprCtx, name.pos) {
    override fun exprInfo0() = V_ExprInfo.simple(R_BooleanType, subExpr)
    override fun varFacts0() = resVarFacts

    override fun toRExpr0(): R_Expr {
        val fn = R_SysFn_General.Exists(condition, not)
        val rArgs = listOf(subExpr.toRExpr())
        return C_Utils.createSysCallRExpr(R_BooleanType, fn, rArgs, listOf(name))
    }

    override fun toDbExpr0(): Db_Expr {
        val dbSubExpr = subExpr.toDbExpr()
        return Db_ExistsExpr(dbSubExpr, not)
    }
}
