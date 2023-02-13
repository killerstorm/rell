/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.fn

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.expr.C_CallTypeHints
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.utils.C_CodeMsg
import net.postchain.rell.compiler.base.utils.C_ParameterDefaultValue
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.R_CtErrorType
import net.postchain.rell.model.R_FunctionType
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_Type
import net.postchain.rell.utils.LazyPosString
import net.postchain.rell.utils.LazyString
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap

abstract class C_FunctionCallTargetInfo {
    abstract fun retType(): R_Type?
    abstract fun typeHints(): C_CallTypeHints
    abstract fun hasParameter(name: R_Name): Boolean
}

abstract class C_FunctionCallTarget: C_FunctionCallTargetInfo() {
    abstract fun compileFull(args: C_FullCallArguments): V_GlobalFunctionCall?
    abstract fun compilePartial(args: C_PartialCallArguments, resTypeHint: R_FunctionType?): V_GlobalFunctionCall?
}

abstract class C_FunctionCallTarget_Regular(
        private val ctx: C_ExprContext,
        private val callInfo: C_FunctionCallInfo,
        private val retType: R_Type?,
): C_FunctionCallTarget() {
    protected open fun vBase(): V_Expr? = null
    protected open fun safe() = false
    protected abstract fun createVTarget(): V_FunctionCallTarget

    final override fun retType() = retType
    final override fun typeHints() = callInfo.params.typeHints
    final override fun hasParameter(name: R_Name) = name in callInfo.params.set

    final override fun compileFull(args: C_FullCallArguments): V_GlobalFunctionCall? {
        retType ?: return null
        val vBase = vBase()
        val vTarget = createVTarget()
        val vCallArgs = args.compileComplexArgs(callInfo)
        vCallArgs ?: return null
        val safe = safe()
        val vCall = V_CommonFunctionCall_Full(callInfo.callPos, callInfo.callPos, retType, vTarget, vCallArgs)
        val vExpr = V_FunctionCallExpr(ctx, callInfo.callPos, vBase, vCall, safe)
        return V_GlobalFunctionCall(vExpr)
    }

    final override fun compilePartial(args: C_PartialCallArguments, resTypeHint: R_FunctionType?): V_GlobalFunctionCall? {
        val vBase = vBase()
        val effArgs = args.compileEffectiveArgs(callInfo)
        effArgs ?: return null
        val fnType = R_FunctionType(effArgs.wildArgs, retType ?: R_CtErrorType)
        val vTarget = createVTarget()
        val mapping = effArgs.toRMapping()
        val safe = safe()
        val vCall = V_CommonFunctionCall_Partial(callInfo.callPos, fnType, vTarget, effArgs.exprArgs, mapping)
        val vExpr = V_FunctionCallExpr(ctx, callInfo.callPos, vBase, vCall, safe)
        return V_GlobalFunctionCall(vExpr)
    }
}

class C_FunctionCallTarget_FunctionType(
        ctx: C_ExprContext,
        callInfo: C_FunctionCallInfo,
        private val fnExpr: V_Expr,
        fnType: R_FunctionType,
        private val safe: Boolean
): C_FunctionCallTarget_Regular(ctx, callInfo, C_Utils.effectiveMemberType(fnType.result, safe)) {
    override fun vBase() = fnExpr
    override fun safe() = safe
    override fun createVTarget(): V_FunctionCallTarget = V_FunctionCallTarget_FunctionValue
}

abstract class C_PartialCallTarget<ExprT>(val callPos: S_Pos, val fullName: LazyString, val params: C_FunctionCallParameters) {
    abstract fun matchesType(fnType: R_FunctionType): Boolean
    abstract fun compileCall(ctx: C_ExprContext, args: C_EffectivePartialArguments): ExprT
}

class C_FunctionCallInfo(
        val callPos: S_Pos,
        val functionName: LazyString?,
        val params: C_FunctionCallParameters
) {
    fun functionNameCode() = functionName?.value ?: "?"

    companion object {
        fun forDirectFunction(name: LazyPosString, params: C_FormalParameters): C_FunctionCallInfo {
            return C_FunctionCallInfo(name.pos, name.lazyStr, params.callParameters)
        }

        fun forFunctionType(callPos: S_Pos, fnType: R_FunctionType): C_FunctionCallInfo {
            return C_FunctionCallInfo(callPos, null, fnType.callParameters)
        }
    }
}

class C_FunctionCallParameters(list: List<C_FunctionCallParameter>) {
    val list = list.toImmList()
    val set = list.mapNotNull { it.name }.toImmList()
    val typeHints: C_CallTypeHints = C_FunctionCallParametersTypeHints(this.list)

    companion object {
        fun fromTypes(types: List<R_Type>): C_FunctionCallParameters {
            val params = types.mapIndexed { index, rType -> C_FunctionCallParameter(null, rType, index, null) }
            return C_FunctionCallParameters(params)
        }
    }
}

class C_FunctionCallParameter(
        val name: R_Name?,
        val type: R_Type,
        val index: Int,
        private val defaultValue: C_ParameterDefaultValue?
) {
    fun nameCodeMsg(): C_CodeMsg {
        val code = if (name != null) "$index:$name" else "$index"
        val msg = if (name != null) "'$name'" else "${index+1}"
        return C_CodeMsg(code, msg)
    }

    fun createDefaultValueExpr(ctx: C_ExprContext, callPos: S_Pos): V_Expr? {
        return defaultValue?.createArgumentExpr(ctx, callPos, type)
    }
}

private class C_FunctionCallParametersTypeHints(params: List<C_FunctionCallParameter>): C_CallTypeHints {
    private val list = params.toImmList()
    private val map = params.filter { it.name != null }.map { it.name!! to it }.toMap().toImmMap()

    override fun getTypeHint(index: Int?, name: R_Name?): C_TypeHint {
        val byName = if (name != null) map[name] else null
        val byIndex = if (index != null && index >= 0 && index < list.size) list[index] else null
        val param = byName ?: byIndex
        return C_TypeHint.ofType(param?.type)
    }
}
