package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.*
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.R_CtErrorType
import net.postchain.rell.model.R_DefinitionNames
import net.postchain.rell.model.R_Type
import net.postchain.rell.model.R_UnitType

object C_FunctionUtils {
    fun compileFunctionHeader(
            defCtx: C_DefinitionContext,
            simpleName: S_Name,
            defNames: R_DefinitionNames,
            params: List<S_FormalParameter>,
            retType: S_Type?,
            body: S_FunctionBody?
    ): C_UserFunctionHeader {
        val explicitRetType = if (retType == null) null else (retType.compileOpt(defCtx.nsCtx) ?: R_CtErrorType)
        val bodyRetType = if (body == null) R_UnitType else null
        val rRetType = explicitRetType ?: bodyRetType

        val cParams = C_FormalParameters.compile(defCtx, params, false)

        val cBody = if (body == null) null else {
            val bodyCtx = C_FunctionBodyContext(defCtx, simpleName.pos, defNames, rRetType, cParams)
            C_UserFunctionBody(bodyCtx, body)
        }

        return C_UserFunctionHeader(rRetType, cParams, cBody)
    }

    fun compileQueryHeader(
            defCtx: C_DefinitionContext,
            simpleName: S_Name,
            defNames: R_DefinitionNames,
            params: List<S_FormalParameter>,
            retType: S_Type?,
            body: S_FunctionBody
    ): C_QueryFunctionHeader {
        val rRetType = if (retType == null) null else (retType.compileOpt(defCtx.nsCtx) ?: R_CtErrorType)
        val cParams = C_FormalParameters.compile(defCtx, params, defCtx.globalCtx.compilerOptions.gtv)
        val bodyCtx = C_FunctionBodyContext(defCtx, simpleName.pos, defNames, rRetType, cParams)
        val cBody = C_QueryFunctionBody(bodyCtx, body)
        return C_QueryFunctionHeader(rRetType, cParams, cBody)
    }

    fun compileRegularCall(
            ctx: C_ExprContext,
            callInfo: C_FunctionCallInfo,
            callTarget: C_FunctionCallTarget,
            args: List<S_CallArgument>,
            resTypeHint: C_TypeHint
    ): V_Expr {
        val res = C_FunctionCallArgsUtils.compileCall(ctx, args, resTypeHint, callTarget)
        return res ?: return C_Utils.errorVExpr(ctx, callInfo.callPos, callTarget.retType() ?: R_CtErrorType)
    }

    fun compileReturnType(ctx: C_ExprContext, name: S_Name, header: C_FunctionHeader): R_Type? {
        if (header.explicitType != null) {
            return header.explicitType
        } else if (header.body == null) {
            return null
        }

        val decType = header.declarationType
        val retTypeRes = header.body.returnType()

        if (retTypeRes.recursion) {
            val nameStr = name.str
            ctx.msgCtx.error(name.pos, "fn_type_recursion:$decType:$nameStr",
                    "${decType.capitalizedMsg} '$nameStr' is recursive, cannot infer the return type; specify return type explicitly")
        } else if (retTypeRes.stackOverflow) {
            val nameStr = name.str
            ctx.msgCtx.error(name.pos, "fn_type_stackoverflow:$decType:$nameStr",
                    "Cannot infer return type for ${decType.msg} '$nameStr': call chain is too long; specify return type explicitly")
        }

        return retTypeRes.value
    }
}
