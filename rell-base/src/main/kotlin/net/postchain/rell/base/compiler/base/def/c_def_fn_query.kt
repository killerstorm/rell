/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.def

import net.postchain.rell.base.compiler.ast.S_CallArgument
import net.postchain.rell.base.compiler.ast.S_FunctionBody
import net.postchain.rell.base.compiler.base.core.C_CompilerPass
import net.postchain.rell.base.compiler.base.core.C_FunctionBodyContext
import net.postchain.rell.base.compiler.base.core.C_TypeHint
import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.fn.C_FormalParameters
import net.postchain.rell.base.compiler.base.fn.C_FunctionCallInfo
import net.postchain.rell.base.compiler.base.fn.C_FunctionUtils
import net.postchain.rell.base.compiler.base.namespace.C_DeclarationType
import net.postchain.rell.base.compiler.base.utils.C_LateInit
import net.postchain.rell.base.compiler.vexpr.V_GlobalFunctionCall
import net.postchain.rell.base.model.R_QueryBody
import net.postchain.rell.base.model.R_QueryDefinition
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.model.R_UserQueryBody
import net.postchain.rell.base.utils.LazyPosString

class C_QueryFunctionHeader(
        explicitType: R_Type?,
        val params: C_FormalParameters,
        val queryBody: C_QueryFunctionBody?
): C_FunctionHeader(explicitType, queryBody) {
    override val declarationType = C_DeclarationType.QUERY

    companion object {
        val ERROR = C_QueryFunctionHeader(null, C_FormalParameters.EMPTY, null)
    }
}

class C_QueryGlobalFunction(val rQuery: R_QueryDefinition): C_GlobalFunction() {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, C_QueryFunctionHeader.ERROR)

    fun setHeader(header: C_QueryFunctionHeader) {
        headerLate.set(header)
    }

    override fun compileCall(
        ctx: C_ExprContext,
        name: LazyPosString,
        args: List<S_CallArgument>,
        resTypeHint: C_TypeHint,
    ): V_GlobalFunctionCall {
        val header = headerLate.get()
        val retType = C_FunctionUtils.compileReturnType(ctx, name, header)
        val callInfo = C_FunctionCallInfo.forDirectFunction(name, header.params)
        val callTarget = C_FunctionCallTarget_RegularUserFunction(ctx, callInfo, retType, rQuery)
        return C_FunctionUtils.compileRegularCall(ctx, callInfo, callTarget, args, resTypeHint)
    }
}

class C_QueryFunctionBody(
        bodyCtx: C_FunctionBodyContext,
        private val sBody: S_FunctionBody
): C_CommonFunctionBody<R_QueryBody>(bodyCtx) {
    override fun returnsValue() = sBody.returnsValue()
    override fun getErrorBody() = R_UserQueryBody.ERROR
    override fun getReturnType(body: R_QueryBody) = body.retType
    override fun compileBody() = sBody.compileQuery(bodyCtx)
}
