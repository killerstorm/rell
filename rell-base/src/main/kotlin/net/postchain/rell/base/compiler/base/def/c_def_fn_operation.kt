/*
 * Copyright (C) 2024 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.def

import net.postchain.rell.base.compiler.ast.S_CallArgument
import net.postchain.rell.base.compiler.base.core.C_CompilerPass
import net.postchain.rell.base.compiler.base.core.C_TypeHint
import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.fn.C_FormalParameters
import net.postchain.rell.base.compiler.base.fn.C_FunctionCallTargetBase
import net.postchain.rell.base.compiler.base.fn.C_FunctionCallTarget_Regular
import net.postchain.rell.base.compiler.base.fn.C_FunctionUtils
import net.postchain.rell.base.compiler.base.utils.C_LateInit
import net.postchain.rell.base.compiler.vexpr.V_FunctionCallTarget_Operation
import net.postchain.rell.base.compiler.vexpr.V_GlobalFunctionCall
import net.postchain.rell.base.lib.test.R_TestOpType
import net.postchain.rell.base.model.R_DefinitionMeta
import net.postchain.rell.base.model.R_OperationDefinition
import net.postchain.rell.base.utils.LazyPosString

class C_OperationFunctionHeader(val params: C_FormalParameters) {
    companion object {
        val ERROR = C_OperationFunctionHeader(C_FormalParameters.EMPTY)
    }
}

class C_OperationGlobalFunction(val rOp: R_OperationDefinition): C_GlobalFunction() {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, C_OperationFunctionHeader.ERROR)

    override fun getDefMeta(): R_DefinitionMeta {
        return R_DefinitionMeta(rOp.defName, mountName = rOp.mountName)
    }

    fun setHeader(header: C_OperationFunctionHeader) {
        headerLate.set(header)
    }

    override fun compileCall(
        ctx: C_ExprContext,
        name: LazyPosString,
        args: List<S_CallArgument>,
        resTypeHint: C_TypeHint,
    ): V_GlobalFunctionCall {
        val header = headerLate.get()
        val callTargetBase = C_FunctionCallTargetBase.forDirectFunction(ctx, name, header.params)
        val callTarget = C_FunctionCallTarget_Operation(callTargetBase, rOp)
        val vCall = C_FunctionUtils.compileRegularCall(callTargetBase, callTarget, args, resTypeHint)

        if (!ctx.defCtx.modCtx.isTestLib()) {
            ctx.msgCtx.error(name.pos, "expr:operation_call:no_test:$name",
                    "Operation calls are allowed only in tests or REPL")
        }

        return vCall
    }
}

private class C_FunctionCallTarget_Operation(
    base: C_FunctionCallTargetBase,
    private val rOp: R_OperationDefinition
): C_FunctionCallTarget_Regular(base, R_TestOpType) {
    override fun createVTarget() = V_FunctionCallTarget_Operation(rOp)
}
