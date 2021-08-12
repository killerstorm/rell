package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.R_CtErrorType
import net.postchain.rell.model.R_FunctionType
import net.postchain.rell.model.R_Type
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap

abstract class C_FunctionCallTarget {
    abstract fun retType(): R_Type?
    abstract fun typeHints(): C_CallTypeHints
    abstract fun compileFull(args: C_FullCallArguments): V_Expr?
    abstract fun compilePartial(args: C_PartialCallArguments, resTypeHint: R_FunctionType?): V_Expr?
}

abstract class C_FunctionCallTarget_Regular(
        private val ctx: C_ExprContext,
        private val callInfo: C_FunctionCallInfo
): C_FunctionCallTarget() {
    protected abstract fun createVTarget(): V_FunctionCallTarget

    override fun typeHints() = callInfo.params.typeHints

    override fun compileFull(args: C_FullCallArguments): V_Expr? {
        val retType = retType()
        retType ?: return null
        val vTarget = createVTarget()
        val vCallArgs = args.compileComplexArgs(callInfo)
        vCallArgs ?: return null
        val exprFacts = C_ExprVarFacts.forSubExpressions(vCallArgs.exprs)
        return V_FullFunctionCallExpr(ctx, callInfo.callPos, callInfo.callPos, retType, vTarget, vCallArgs, exprFacts)
    }

    override fun compilePartial(args: C_PartialCallArguments, resTypeHint: R_FunctionType?): V_Expr? {
        val retType = retType() ?: R_CtErrorType
        val effArgs = args.compileEffectiveArgs(callInfo)
        effArgs ?: return null
        val fnType = R_FunctionType(effArgs.wildArgs, retType)
        val target = createVTarget()
        val mapping = effArgs.toRMapping()
        val exprFacts = C_ExprVarFacts.forSubExpressions(effArgs.exprArgs)
        return V_PartialFunctionCallExpr(ctx, callInfo.callPos, fnType, target, effArgs.exprArgs, mapping, exprFacts)
    }
}

class C_FunctionCallTarget_FunctionType(
        ctx: C_ExprContext,
        callInfo: C_FunctionCallInfo,
        private val fnExpr: V_Expr,
        fnType: R_FunctionType,
        private val safe: Boolean
): C_FunctionCallTarget_Regular(ctx, callInfo) {
    private val resType = C_Utils.effectiveMemberType(fnType.result, safe)

    override fun retType() = resType
    override fun createVTarget(): V_FunctionCallTarget = V_FunctionCallTarget_FunctionValue(fnExpr, safe)
}

abstract class C_PartialCallTarget(val callPos: S_Pos, val fullName: String, val params: C_FunctionCallParameters) {
    abstract fun matchesType(fnType: R_FunctionType): Boolean
    abstract fun compileCall(ctx: C_ExprContext, args: C_EffectivePartialArguments): V_Expr
}

class C_FunctionCallInfo(
        val callPos: S_Pos,
        val functionName: String?,
        val params: C_FunctionCallParameters
) {
    fun functionNameCode() = functionName ?: "?"

    companion object {
        fun forDirectFunction(name: S_Name, params: C_FormalParameters): C_FunctionCallInfo {
            return C_FunctionCallInfo(name.pos, name.str, params.callParameters)
        }

        fun forFunctionType(callPos: S_Pos, fnType: R_FunctionType): C_FunctionCallInfo {
            return C_FunctionCallInfo(callPos, null, fnType.callParameters)
        }
    }
}

class C_FunctionCallParameters(list: List<C_FunctionCallParameter>) {
    val list = list.toImmList()
    val typeHints: C_CallTypeHints = C_FunctionCallParametersTypeHints(this.list)

    companion object {
        fun fromTypes(types: List<R_Type>): C_FunctionCallParameters {
            val params = types.mapIndexed { index, rType -> C_FunctionCallParameter(null, rType, index, null) }
            return C_FunctionCallParameters(params)
        }
    }
}

class C_FunctionCallParameter(
        val name: String?,
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

    override fun getTypeHint(index: Int?, name: String?): C_TypeHint {
        val byName = if (name != null) map[name] else null
        val byIndex = if (index != null && index >= 0 && index < list.size) list[index] else null
        val param = byName ?: byIndex
        return C_TypeHint.ofType(param?.type)
    }
}
