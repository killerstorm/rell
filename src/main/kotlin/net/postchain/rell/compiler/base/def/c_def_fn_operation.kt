/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.def

import net.postchain.rell.compiler.ast.S_CallArgument
import net.postchain.rell.compiler.base.core.C_CompilerPass
import net.postchain.rell.compiler.base.core.C_Name
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.fn.C_FormalParameters
import net.postchain.rell.compiler.base.fn.C_FunctionCallInfo
import net.postchain.rell.compiler.base.fn.C_FunctionCallTarget_Regular
import net.postchain.rell.compiler.base.fn.C_FunctionUtils
import net.postchain.rell.compiler.base.utils.C_LateInit
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_FunctionCallTarget_Operation
import net.postchain.rell.lib.test.R_TestOpType
import net.postchain.rell.model.R_OperationDefinition
import net.postchain.rell.tools.api.IdeSymbolInfo

class C_OperationFunctionHeader(val params: C_FormalParameters) {
    companion object {
        val ERROR = C_OperationFunctionHeader(C_FormalParameters.EMPTY)
    }
}

class C_OperationGlobalFunction(val rOp: R_OperationDefinition, ideInfo: IdeSymbolInfo): C_GlobalFunction(ideInfo) {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, C_OperationFunctionHeader.ERROR)

    fun setHeader(header: C_OperationFunctionHeader) {
        headerLate.set(header)
    }

    override fun compileCall(ctx: C_ExprContext, name: C_Name, args: List<S_CallArgument>, resTypeHint: C_TypeHint): V_Expr {
        val header = headerLate.get()
        val callInfo = C_FunctionCallInfo.forDirectFunction(name, header.params)
        val callTarget = C_FunctionCallTarget_Operation(ctx, callInfo, rOp)
        val vExpr = C_FunctionUtils.compileRegularCall(ctx, callInfo, callTarget, args, resTypeHint)

        if (!ctx.defCtx.modCtx.isTestLib()) {
            ctx.msgCtx.error(name.pos, "expr:operation_call:no_test:$name",
                    "Operation calls are allowed only in tests or REPL")
        }

        return vExpr
    }
}

private class C_FunctionCallTarget_Operation(
        ctx: C_ExprContext,
        callInfo: C_FunctionCallInfo,
        private val rOp: R_OperationDefinition
): C_FunctionCallTarget_Regular(ctx, callInfo, R_TestOpType) {
    override fun createVTarget() = V_FunctionCallTarget_Operation(rOp)
}
