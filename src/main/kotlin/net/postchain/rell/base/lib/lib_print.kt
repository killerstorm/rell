/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib

import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.expr.C_ExprUtils
import net.postchain.rell.base.compiler.base.fn.C_BasicGlobalFuncCaseMatch
import net.postchain.rell.base.compiler.base.fn.C_GlobalFuncCaseCtx
import net.postchain.rell.base.compiler.base.fn.C_GlobalFuncCaseMatch
import net.postchain.rell.base.compiler.base.fn.C_GlobalSpecialFuncCase
import net.postchain.rell.base.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.base.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.base.compiler.base.utils.C_LibUtils
import net.postchain.rell.base.compiler.vexpr.V_Expr
import net.postchain.rell.base.model.R_FilePos
import net.postchain.rell.base.model.R_StackPos
import net.postchain.rell.base.model.R_SysFunction
import net.postchain.rell.base.model.R_UnitType
import net.postchain.rell.base.model.expr.R_Expr
import net.postchain.rell.base.runtime.Rt_CallContext
import net.postchain.rell.base.runtime.Rt_UnitValue
import net.postchain.rell.base.runtime.Rt_Value

object C_Lib_Print {
    fun bind(nsBuilder: C_SysNsProtoBuilder) {
        val fb = C_GlobalFuncBuilder()
        fb.add("print", C_SysFn_Print(false))
        fb.add("log", C_SysFn_Print(true))
        C_LibUtils.bindFunctions(nsBuilder, fb.build())
    }
}

private class C_SysFn_Print(private val log: Boolean): C_GlobalSpecialFuncCase() {
    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_GlobalFuncCaseMatch {
        // Print supports any number of arguments and any types, so not checking.
        return CaseMatch(args)
    }

    private inner class CaseMatch(args: List<V_Expr>): C_BasicGlobalFuncCaseMatch(R_UnitType, args) {
        override fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr {
            val filePos = caseCtx.filePos()
            val rFn = R_SysFn_Print(log, filePos)
            return C_ExprUtils.createSysCallRExpr(R_UnitType, rFn, args, caseCtx)
        }
    }
}

private class R_SysFn_Print(private val log: Boolean, private val filePos: R_FilePos): R_SysFunction {
    override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        val buf = StringBuilder()
        for (arg in args) {
            if (!buf.isEmpty()) {
                buf.append(" ")
            }
            buf.append(arg.str())
        }

        val str = buf.toString()

        val printer = if (log) ctx.globalCtx.logPrinter else ctx.globalCtx.outPrinter
        val fullStr = if (log) logStr(ctx, str) else str
        printer.print(fullStr)

        return Rt_UnitValue
    }

    private fun logStr(ctx: Rt_CallContext, str: String): String {
        val pos = R_StackPos(ctx.defCtx.defId, filePos)
        val posStr = "[$pos]"
        return if (str.isEmpty()) posStr else "$posStr $str"
    }
}
