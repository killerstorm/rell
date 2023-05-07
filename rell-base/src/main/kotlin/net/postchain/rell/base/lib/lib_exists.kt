/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib

import net.postchain.rell.base.compiler.ast.S_Expr
import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.expr.C_ExprUtils
import net.postchain.rell.base.compiler.base.expr.C_ExprVarFacts
import net.postchain.rell.base.compiler.base.fn.C_FuncMatchUtils
import net.postchain.rell.base.compiler.base.fn.C_SpecialSysGlobalFunction
import net.postchain.rell.base.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.base.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.base.compiler.base.utils.C_LibUtils
import net.postchain.rell.base.compiler.vexpr.V_Expr
import net.postchain.rell.base.compiler.vexpr.V_ExprInfo
import net.postchain.rell.base.compiler.vexpr.V_GlobalFunctionCall
import net.postchain.rell.base.model.R_BooleanType
import net.postchain.rell.base.model.R_NullableType
import net.postchain.rell.base.model.R_SysFunction_1
import net.postchain.rell.base.model.expr.Db_ExistsExpr
import net.postchain.rell.base.model.expr.Db_Expr
import net.postchain.rell.base.model.expr.R_Expr
import net.postchain.rell.base.runtime.Rt_BooleanValue
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.utils.LazyPosString
import net.postchain.rell.base.utils.checkEquals

object C_Lib_Exists {
    fun bind(nsBuilder: C_SysNsProtoBuilder) {
        val fb = C_GlobalFuncBuilder()
        fb.add("exists", C_SysFn_Exists(false))
        fb.add("empty", C_SysFn_Exists(true))
        C_LibUtils.bindFunctions(nsBuilder, fb.build())
    }
}

private class C_SysFn_Exists(private val not: Boolean): C_SpecialSysGlobalFunction() {
    override fun paramCount() = 1 .. 1

    override fun compileCall0(ctx: C_ExprContext, name: LazyPosString, args: List<S_Expr>): V_GlobalFunctionCall {
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
            return C_ExprUtils.errorVGlobalCall(ctx, name.pos, R_BooleanType)
        }

        val preFacts = C_ExprVarFacts.forSubExpressions(listOf(vArg))
        val varFacts = preFacts.and(C_ExprVarFacts.forNullCheck(vArg, not))

        val vExpr = V_ExistsExpr(ctx, name, vArg, condition, not, varFacts)
        return V_GlobalFunctionCall(vExpr)
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
        private val name: LazyPosString,
        private val subExpr: V_Expr,
        private val condition: R_RequireCondition,
        private val not: Boolean,
        private val resVarFacts: C_ExprVarFacts
): V_Expr(exprCtx, name.pos) {
    override fun exprInfo0() = V_ExprInfo.simple(R_BooleanType, subExpr)
    override fun varFacts0() = resVarFacts

    override fun toRExpr0(): R_Expr {
        val fn = R_SysFn_Exists(condition, not)
        val rArgs = listOf(subExpr.toRExpr())
        return C_ExprUtils.createSysCallRExpr(R_BooleanType, fn, rArgs, name)
    }

    override fun toDbExpr0(): Db_Expr {
        val dbSubExpr = subExpr.toDbExpr()
        return Db_ExistsExpr(dbSubExpr, not)
    }
}

private class R_SysFn_Exists(private val condition: R_RequireCondition, private val not: Boolean): R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val value = condition.calculate(arg)
        val exists = value != null
        val res = if (not) !exists else exists
        return Rt_BooleanValue(res)
    }
}
