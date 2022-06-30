/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.expr.C_ExprVarFacts
import net.postchain.rell.compiler.base.fn.C_BasicGlobalFuncCaseMatch
import net.postchain.rell.compiler.base.fn.C_GlobalFuncCaseCtx
import net.postchain.rell.compiler.base.fn.C_GlobalFuncCaseMatch
import net.postchain.rell.compiler.base.fn.C_GlobalSpecialFuncCase
import net.postchain.rell.compiler.base.namespace.C_Deprecated
import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_SpecialGlobalFuncCaseMatch
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_GlobalConstantRestriction
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.R_Expr
import net.postchain.rell.runtime.*

object C_Lib_Require {
    fun bind(nsBuilder: C_SysNsProtoBuilder) {
        val fb = C_GlobalFuncBuilder(null)
        fb.add("require", C_SysFn_Require_Boolean)
        fb.add("require", C_SysFn_Require_Nullable)
        fb.add("requireNotEmpty", C_SysFn_Require_Collection, depError("require_not_empty"))
        fb.add("requireNotEmpty", C_SysFn_Require_Nullable, depError("require_not_empty"))
        fb.add("require_not_empty", C_SysFn_Require_Collection)
        fb.add("require_not_empty", C_SysFn_Require_Nullable)
        C_LibUtils.bindFunctions(nsBuilder, fb.build())
    }
}

private object C_SysFn_Require_Boolean: C_GlobalSpecialFuncCase() {
    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size < 1 || args.size > 2) return null

        val exprType = args[0].type
        if (!R_BooleanType.isAssignableFrom(exprType)) return null

        val msgType = if (args.size < 2) null else args[1].type
        if (msgType != null && !R_TextType.isAssignableFrom(msgType)) return null

        return CaseMatch(args)
    }

    private class CaseMatch(args: List<V_Expr>): C_BasicGlobalFuncCaseMatch(R_UnitType, args) {
        override fun globalConstantRestriction(caseCtx: C_GlobalFuncCaseCtx) = null

        override fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr {
            val rExpr = args[0]
            val rMsgExpr = if (args.size < 2) null else args[1]
            return R_RequireExpr(R_UnitType, rExpr, rMsgExpr, R_RequireCondition_Boolean)
        }
    }
}

private object C_SysFn_Require_Nullable: C_GlobalSpecialFuncCase() {
    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size < 1 || args.size > 2) return null

        val expr = args[0].asNullable()
        val exprType = expr.type
        if (exprType !is R_NullableType) return null

        val msg = if (args.size < 2) null else args[1]
        if (msg != null && !R_TextType.isAssignableFrom(msg.type)) return null

        val preFacts = expr.varFacts.postFacts
        val varFacts = C_ExprVarFacts.forNullCast(preFacts, expr)
        return CaseMatch(args, exprType.valueType, varFacts)
    }

    private class CaseMatch(
        private val args: List<V_Expr>,
        private val valueType: R_Type,
        private val varFacts: C_ExprVarFacts
    ): C_SpecialGlobalFuncCaseMatch(valueType) {
        override fun varFacts() = varFacts
        override fun subExprs() = args
        override fun globalConstantRestriction(caseCtx: C_GlobalFuncCaseCtx): V_GlobalConstantRestriction? = null

        override fun compileCallR(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): R_Expr {
            val exprValue = args[0]
            val rExpr = exprValue.toRExpr()
            val rMsgExpr = if (args.size < 2) null else args[1].toRExpr()
            return R_RequireExpr(valueType, rExpr, rMsgExpr, R_RequireCondition_Nullable)
        }
    }
}

object C_SysFn_Require_Collection: C_GlobalSpecialFuncCase() {
    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size < 1 || args.size > 2) return null

        val msgType = if (args.size < 2) null else args[1].type
        if (msgType != null && !R_TextType.isAssignableFrom(msgType)) return null

        val exprType = args[0].type
        val (valueType, condition) = getCondition(exprType)
        return if (condition == null) null else CaseMatch(valueType, args, condition)
    }

    fun getCondition(exprType: R_Type): Pair<R_Type, R_RequireCondition?> {
        val resType = if (exprType is R_NullableType) exprType.valueType else exprType

        val condition = if (resType is R_CollectionType) {
            R_RequireCondition_Collection
        } else if (resType is R_MapType) {
            R_RequireCondition_Map
        } else {
            null
        }

        return Pair(resType, condition)
    }

    private class CaseMatch(
        resType: R_Type,
        args: List<V_Expr>,
        private val condition: R_RequireCondition
    ): C_BasicGlobalFuncCaseMatch(resType, args) {
        override fun globalConstantRestriction(caseCtx: C_GlobalFuncCaseCtx) = null

        override fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr {
            val rExpr = args[0]
            val rMsgExpr = if (args.size < 2) null else args[1]
            return R_RequireExpr(resType, rExpr, rMsgExpr, condition)
        }
    }
}

private class R_RequireExpr(
    type: R_Type,
    private val expr: R_Expr,
    private val msgExpr: R_Expr?,
    private val condition: R_RequireCondition
): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val value = expr.evaluate(frame)
        val res = condition.calculate(value)
        if (res != null) {
            return res
        }

        val msg = if (msgExpr == null) null else {
            val msgValue = msgExpr.evaluate(frame)
            msgValue.asString()
        }

        throw Rt_RequireError(msg)
    }
}

sealed class R_RequireCondition {
    abstract fun calculate(v: Rt_Value): Rt_Value?
}

private object R_RequireCondition_Boolean: R_RequireCondition() {
    override fun calculate(v: Rt_Value) = if (v.asBoolean()) Rt_UnitValue else null
}

object R_RequireCondition_Nullable: R_RequireCondition() {
    override fun calculate(v: Rt_Value) = if (v != Rt_NullValue) v else null
}

private object R_RequireCondition_Collection: R_RequireCondition() {
    override fun calculate(v: Rt_Value) = if (v != Rt_NullValue && !v.asCollection().isEmpty()) v else null
}

private object R_RequireCondition_Map: R_RequireCondition() {
    override fun calculate(v: Rt_Value) = if (v != Rt_NullValue && !v.asMap().isEmpty()) v else null
}

private fun depError(newName: String) = C_Deprecated(useInstead = newName, error = true)
