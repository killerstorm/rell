/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib

import net.postchain.rell.base.compiler.ast.S_Expr
import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.expr.C_ExprUtils
import net.postchain.rell.base.compiler.base.fn.*
import net.postchain.rell.base.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.base.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.base.compiler.base.utils.C_LibUtils
import net.postchain.rell.base.compiler.base.utils.C_SysFunction
import net.postchain.rell.base.compiler.vexpr.V_ConstantValueExpr
import net.postchain.rell.base.compiler.vexpr.V_Expr
import net.postchain.rell.base.compiler.vexpr.V_GlobalFunctionCall
import net.postchain.rell.base.model.*
import net.postchain.rell.base.model.expr.R_Expr
import net.postchain.rell.base.runtime.Rt_RowidValue
import net.postchain.rell.base.runtime.Rt_TextValue
import net.postchain.rell.base.runtime.utils.RellInterpreterCrashException
import net.postchain.rell.base.utils.LazyPosString
import net.postchain.rell.base.utils.checkEquals

object C_Lib_Hidden {
    fun bind(nsBuilder: C_SysNsProtoBuilder) {
        val fb = C_GlobalFuncBuilder()

        fb.add("_crash", R_UnitType, listOf(R_TextType), HiddenFns.Crash)
        fb.add("_type_of", C_SysFn_TypeOf)
        fb.add("_nullable", C_SysFn_Nullable(null))
        fb.add("_nullable_int", C_SysFn_Nullable(R_IntegerType))
        fb.add("_nullable_text", C_SysFn_Nullable(R_TextType))
        fb.add("_nop", C_SysFn_Nop(false))
        fb.add("_nop_print", C_SysFn_Nop(true))
        fb.addEx("_strict_str", R_TextType, listOf(C_ArgTypeMatcher_Any), HiddenFns.StrictStr)
        fb.add("_int_to_rowid", R_RowidType, listOf(R_IntegerType), HiddenFns.IntToRowid)

        C_LibUtils.bindFunctions(nsBuilder, fb.build())
    }
}

private class C_SysFn_Nop(private val print: Boolean): C_GlobalSpecialFuncCase() {
    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null
        val resType = args[0].type
        return CaseMatch(resType, args)
    }

    private inner class CaseMatch(resType: R_Type, args: List<V_Expr>): C_BasicGlobalFuncCaseMatch(resType, args) {
        // Internal function, used in compiler unit tests, must be allowed for constants.
        override fun globalConstantRestriction(caseCtx: C_GlobalFuncCaseCtx) = null

        override fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr {
            checkEquals(args.size, 1)
            return C_ExprUtils.createSysCallRExpr(resType, HiddenFns.Nop(print), args, caseCtx)
        }
    }
}

private object C_SysFn_TypeOf: C_SpecialSysGlobalFunction() {
    override fun paramCount() = 1 .. 1

    override fun compileCall0(ctx: C_ExprContext, name: LazyPosString, args: List<S_Expr>): V_GlobalFunctionCall {
        checkEquals(1, args.size)

        val arg = args[0]
        val cArg = arg.compile(ctx)
        val vArg = cArg.value()

        val type = vArg.type
        val str = type.strCode()
        val value = Rt_TextValue(str)

        val vExpr = V_ConstantValueExpr(ctx, name.pos, value)
        return V_GlobalFunctionCall(vExpr)
    }
}

private class C_SysFn_Nullable(private val baseType: R_Type?): C_GlobalSpecialFuncCase() {
    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        if (args.size != 1) return null

        val type = args[0].type
        if (baseType == null && type == R_NullType) return null
        if (baseType != null && !R_NullableType(baseType).isAssignableFrom(type)) return null

        val resType = if (type is R_NullableType) type else R_NullableType(baseType ?: type)
        return CaseMatch(resType, args)
    }

    private inner class CaseMatch(resType: R_Type, args: List<V_Expr>): C_BasicGlobalFuncCaseMatch(resType, args) {
        override fun globalConstantRestriction(caseCtx: C_GlobalFuncCaseCtx) = null

        override fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr {
            checkEquals(args.size, 1)
            return C_ExprUtils.createSysCallRExpr(resType, HiddenFns.Nop(false), args, caseCtx)
        }
    }
}

private object HiddenFns {
    val StrictStr = C_SysFunction.simple1 { a ->
        val s = a.strCode()
        Rt_TextValue(s)
    }

    fun Nop(print: Boolean) = C_SysFunction.rContext1 { ctx, a ->
        if (print) {
            ctx.globalCtx.outPrinter.print(a.str())
        }
        a
    }

    val Crash = C_SysFunction.rSimple1 { a ->
        val s = a.asString()
        throw RellInterpreterCrashException(s)
    }

    val IntToRowid = C_SysFunction.simple1(pure = true) { a ->
        val i = a.asInteger()
        Rt_RowidValue(i)
    }
}
