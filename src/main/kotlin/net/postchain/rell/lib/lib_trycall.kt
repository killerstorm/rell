/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.compiler.base.core.C_Types
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.fn.C_BasicGlobalFuncCaseMatch
import net.postchain.rell.compiler.base.fn.C_GlobalFuncCaseCtx
import net.postchain.rell.compiler.base.fn.C_GlobalFuncCaseMatch
import net.postchain.rell.compiler.base.fn.C_GlobalSpecialFuncCase
import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.R_Expr
import net.postchain.rell.runtime.Rt_BooleanValue
import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_NullValue
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.runtime.utils.Rt_Utils.checkEquals
import net.postchain.rell.utils.immListOf

object C_Lib_TryCall {
    fun bind(nsBuilder: C_SysNsProtoBuilder) {
        val fb = C_GlobalFuncBuilder()
        fb.add("try_call", C_SysFn_TryCall_1)
        fb.add("try_call", C_SysFn_TryCall_2)
        C_LibUtils.bindFunctions(nsBuilder, fb.build())
    }
}

private object C_SysFn_TryCall_1: C_GlobalSpecialFuncCase() {
    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null

        val fnType = args[0].type as? R_FunctionType
        if (fnType == null || fnType.params.isNotEmpty()) {
            return null
        }

        val callPos = args[0].pos.toFilePos()

        return when (fnType.result) {
            R_UnitType -> CaseMatch(R_BooleanType, args, callPos, Rt_BooleanValue.TRUE, Rt_BooleanValue.FALSE)
            else -> {
                val resType = C_Types.toNullable(fnType.result)
                CaseMatch(resType, args, callPos, null, Rt_NullValue)
            }
        }
    }

    private class CaseMatch(
        resType: R_Type,
        args: List<V_Expr>,
        val callPos: R_FilePos,
        val okValue: Rt_Value?,
        val errValue: Rt_Value,
    ): C_BasicGlobalFuncCaseMatch(resType, args) {
        override fun globalConstantRestriction(caseCtx: C_GlobalFuncCaseCtx) = null

        override fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr {
            checkEquals(args.size, 1)
            return R_TryCall1Expr(resType, callPos, args[0], okValue, errValue)
        }
    }

    private class R_TryCall1Expr(
        type: R_Type,
        val callPos: R_FilePos,
        val fnExpr: R_Expr,
        val okValue: Rt_Value?,
        val errValue: Rt_Value,
    ): R_Expr(type) {
        override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
            val fnValue = fnExpr.evaluate(frame).asFunction()
            val callCtx = frame.callCtx(callPos)
            return try {
                val v = fnValue.call(callCtx, immListOf())
                okValue ?: v
            } catch (e: Throwable) {
                errValue
            }
        }
    }
}

private object C_SysFn_TryCall_2: C_GlobalSpecialFuncCase() {
    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size != 2) return null

        val fnType = args[0].type as? R_FunctionType
        if (fnType == null || fnType.result == R_UnitType || fnType.params.isNotEmpty()) {
            return null
        }

        val defType = args[1].type
        val resType = C_Types.commonTypeOpt(defType, fnType.result)
        resType ?: return null

        val callPos = args[0].pos.toFilePos()
        return CaseMatch(resType, args, callPos)
    }

    private class CaseMatch(
        resType: R_Type,
        args: List<V_Expr>,
        val callPos: R_FilePos,
    ): C_BasicGlobalFuncCaseMatch(resType, args) {
        override fun globalConstantRestriction(caseCtx: C_GlobalFuncCaseCtx) = null

        override fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr {
            checkEquals(args.size, 2)
            return R_TryCall2Expr(resType, callPos, args[0], args[1])
        }
    }

    private class R_TryCall2Expr(
        type: R_Type,
        val callPos: R_FilePos,
        val fnExpr: R_Expr,
        val valueExpr: R_Expr,
    ): R_Expr(type) {
        override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
            val fnValue = fnExpr.evaluate(frame).asFunction()
            val callCtx = frame.callCtx(callPos)
            return try {
                fnValue.call(callCtx, immListOf())
            } catch (e: Throwable) {
                valueExpr.evaluate(frame)
            }
        }
    }
}
