/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.def

import net.postchain.rell.compiler.ast.S_CallArgument
import net.postchain.rell.compiler.ast.S_FunctionBody
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.base.core.C_CompilerPass
import net.postchain.rell.compiler.base.core.C_FunctionBodyContext
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.fn.C_FormalParameters
import net.postchain.rell.compiler.base.fn.C_FunctionCallInfo
import net.postchain.rell.compiler.base.fn.C_FunctionUtils
import net.postchain.rell.compiler.base.namespace.C_DeclarationType
import net.postchain.rell.compiler.base.utils.C_LateInit
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.R_QueryBody
import net.postchain.rell.model.R_QueryDefinition
import net.postchain.rell.model.R_Type
import net.postchain.rell.model.R_UserQueryBody

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

    override fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_CallArgument>, resTypeHint: C_TypeHint): V_Expr {
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
