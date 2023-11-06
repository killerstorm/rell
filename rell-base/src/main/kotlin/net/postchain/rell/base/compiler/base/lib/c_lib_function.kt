/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.lib

import net.postchain.rell.base.compiler.ast.S_CallArgument
import net.postchain.rell.base.compiler.ast.S_CallArgumentValue_Expr
import net.postchain.rell.base.compiler.ast.S_CallArgumentValue_Wildcard
import net.postchain.rell.base.compiler.ast.S_Expr
import net.postchain.rell.base.compiler.base.core.C_IdeSymbolInfo
import net.postchain.rell.base.compiler.base.core.C_TypeHint
import net.postchain.rell.base.compiler.base.def.C_GlobalFunction
import net.postchain.rell.base.compiler.base.expr.*
import net.postchain.rell.base.compiler.base.fn.*
import net.postchain.rell.base.compiler.base.utils.C_Errors
import net.postchain.rell.base.compiler.vexpr.*
import net.postchain.rell.base.model.*
import net.postchain.rell.base.model.expr.R_MemberCalculator
import net.postchain.rell.base.utils.LazyPosString
import net.postchain.rell.base.utils.mapOrSame
import net.postchain.rell.base.utils.toImmList

object C_LibFunctionUtils {
    fun makeGlobalFunction(
        naming: C_GlobalFunctionNaming,
        cases: List<C_LibFuncCase<V_GlobalFunctionCall>>,
    ): C_LibGlobalFunction {
        return C_RegularLibGlobalFunction(naming, cases)
    }

    fun makeMemberFunction(
        cases: List<C_LibFuncCase<V_MemberFunctionCall>>,
    ): C_LibMemberFunction {
        return C_RegularLibMemberFunction(cases)
    }
}

abstract class C_LibGlobalFunction: C_GlobalFunction() {
    abstract fun replaceTypeParams(rep: C_TypeMemberReplacement): C_LibGlobalFunction
}

sealed class C_LibMemberFunction {
    abstract fun getCallTypeHints(selfType: R_Type): C_CallTypeHints

    abstract fun replaceTypeParams(rep: C_TypeMemberReplacement): C_LibMemberFunction

    abstract fun compileCallFull(
        ctx: C_ExprContext,
        callCtx: C_LibFuncCaseCtx,
        selfType: R_Type,
        args: List<V_Expr>,
        resTypeHint: C_TypeHint,
    ): V_MemberFunctionCall

    abstract fun compileCallPartial(
        ctx: C_ExprContext,
        caseCtx: C_LibFuncCaseCtx,
        selfType: R_Type,
        args: C_PartialCallArguments,
        resTypeHint: R_FunctionType?,
    ): V_MemberFunctionCall?
}

abstract class C_SpecialLibGlobalFunctionBody {
    open fun paramCount(): IntRange? = null

    abstract fun compileCall(ctx: C_ExprContext, name: LazyPosString, args: List<S_Expr>): V_Expr
}

class C_SpecialLibGlobalFunction(
    private val body: C_SpecialLibGlobalFunctionBody,
    private val ideInfo: C_IdeSymbolInfo,
): C_LibGlobalFunction() {
    override fun replaceTypeParams(rep: C_TypeMemberReplacement): C_LibGlobalFunction {
        return this
    }

    override fun compileCall(
        ctx: C_ExprContext,
        name: LazyPosString,
        args: List<S_CallArgument>,
        resTypeHint: C_TypeHint,
    ): V_GlobalFunctionCall {
        val errPartial = ctx.msgCtx.firstErrorReporter()

        val argExprsZ = args.map {
            when (val value = it.value) {
                is S_CallArgumentValue_Expr -> value.expr
                is S_CallArgumentValue_Wildcard -> {
                    errPartial.error(value.pos, C_Errors.msgPartialCallNotAllowed(name.str))
                    null
                }
            }
        }

        val argExprs = argExprsZ.filterNotNull()
        if (argExprs.size != argExprsZ.size) return C_ExprUtils.errorVGlobalCall(ctx, name.pos)

        val argName = args.firstNotNullOfOrNull { it.name }
        if (argName != null) {
            C_Errors.errLibFunctionNamedArg(ctx.msgCtx, name.str, argName)
            argExprs.forEach { it.compileSafe(ctx) }
            return C_ExprUtils.errorVGlobalCall(ctx, name.pos)
        }

        val paramCountRange = body.paramCount()
        val argCount = argExprs.size
        if (paramCountRange != null && argCount !in paramCountRange) {
            val paramsMin = paramCountRange.first
            val paramsMax = paramCountRange.last
            val paramCountMsg = if (paramsMin == paramsMax) "$paramsMin" else "$paramsMin .. $paramsMax"
            ctx.msgCtx.error(name.pos, "fn:sys:wrong_arg_count:$paramsMin:$paramsMax:$argCount",
                    "Wrong number of arguments for function '$name': $argCount instead of $paramCountMsg")
            argExprs.forEach { it.compileSafe(ctx) }
            return C_ExprUtils.errorVGlobalCall(ctx, name.pos, R_BooleanType)
        }

        val vExpr = body.compileCall(ctx, name, argExprs)
        return V_GlobalFunctionCall(vExpr, ideInfo)
    }
}

abstract class C_SpecialLibMemberFunctionBody {
    abstract fun compileCall(
        ctx: C_ExprContext,
        callCtx: C_LibFuncCaseCtx,
        selfType: R_Type,
        args: List<V_Expr>,
    ): V_SpecialMemberFunctionCall?
}

abstract class V_SpecialMemberFunctionCall(protected val exprCtx: C_ExprContext, val returnType: R_Type) {
    abstract fun calculator(): R_MemberCalculator

    open fun globalConstantRestriction(): V_GlobalConstantRestriction? = null
    open fun canBeDbExpr(): Boolean = false
    open fun dbExprWhat(base: V_Expr, safe: Boolean): C_DbAtWhatValue? = null
}

class C_SpecialLibMemberFunction(
    private val body: C_SpecialLibMemberFunctionBody,
    private val ideInfo: C_IdeSymbolInfo,
): C_LibMemberFunction() {
    override fun replaceTypeParams(rep: C_TypeMemberReplacement): C_LibMemberFunction = this

    override fun getCallTypeHints(selfType: R_Type): C_CallTypeHints = C_CallTypeHints_None

    override fun compileCallFull(
        ctx: C_ExprContext,
        callCtx: C_LibFuncCaseCtx,
        selfType: R_Type,
        args: List<V_Expr>,
        resTypeHint: C_TypeHint,
    ): V_MemberFunctionCall {
        val vCall = body.compileCall(ctx, callCtx, selfType, args)
        vCall ?: return V_MemberFunctionCall_Error(ctx, ideInfo)
        return V_MemberFunctionCall_SpecialLibFunction(ctx, ideInfo, args, vCall)
    }

    override fun compileCallPartial(
        ctx: C_ExprContext,
        caseCtx: C_LibFuncCaseCtx,
        selfType: R_Type,
        args: C_PartialCallArguments,
        resTypeHint: R_FunctionType?,
    ): V_MemberFunctionCall? {
        args.errPartialNotSupportedFn(caseCtx.qualifiedNameMsg())
        return null
    }

    private class V_MemberFunctionCall_SpecialLibFunction(
        exprCtx: C_ExprContext,
        ideInfo: C_IdeSymbolInfo,
        private val vExprs: List<V_Expr>,
        private val specialCall: V_SpecialMemberFunctionCall,
    ): V_MemberFunctionCall(exprCtx, ideInfo) {
        override fun vExprs() = vExprs
        override fun globalConstantRestriction() = specialCall.globalConstantRestriction()
        override fun returnType() = specialCall.returnType
        override fun canBeDbExpr() = specialCall.canBeDbExpr()

        override fun calculator(): R_MemberCalculator {
            return specialCall.calculator()
        }

        override fun dbExprWhat(base: V_Expr, safe: Boolean): C_DbAtWhatValue? {
            return specialCall.dbExprWhat(base, safe)
        }
    }
}

private class C_RegularLibGlobalFunction(
    private val naming: C_GlobalFunctionNaming,
    private val cases: List<C_LibFuncCase<V_GlobalFunctionCall>>,
): C_LibGlobalFunction() {
    override fun replaceTypeParams(rep: C_TypeMemberReplacement): C_LibGlobalFunction {
        val naming2 = naming.replaceSelfType(rep.selfType)
        val cases2 = cases.mapOrSame { it.replaceTypeParams(rep) }
        return if (naming2 === naming && cases2 === cases) this else C_RegularLibGlobalFunction(naming2, cases2)
    }

    override fun compileCall(
        ctx: C_ExprContext,
        name: LazyPosString,
        args: List<S_CallArgument>,
        resTypeHint: C_TypeHint,
    ): V_GlobalFunctionCall {
        val target = C_FunctionCallTarget_LibGlobalFunction(ctx, name)
        val vCall = C_FunctionCallArgsUtils.compileCall(ctx, args, resTypeHint, target)
        return vCall ?: C_ExprUtils.errorVGlobalCall(ctx, name.pos, ideInfo = cases.first().ideInfo)
    }

    private fun matchCase(
        ctx: C_ExprContext,
        caseCtx: C_LibFuncCaseCtx,
        name: LazyPosString,
        args: List<V_Expr>,
        resTypeHint: C_TypeHint,
    ): C_LibFuncCaseMatch<V_GlobalFunctionCall>? {
        for (case in cases) {
            val res = case.match(caseCtx, R_UnitType, args, resTypeHint)
            if (res != null) {
                return res
            }
        }

        val argTypes = args.map { it.type }
        C_LibFuncCaseUtils.errNoMatch(ctx, name.pos, name.str, argTypes)
        return null
    }

    private inner class C_FunctionCallTarget_LibGlobalFunction(
        val ctx: C_ExprContext,
        val name: LazyPosString,
    ): C_FunctionCallTarget() {
        override fun retType() = null
        override fun typeHints() = C_LibFunctionParamHints(R_UnitType, cases)
        override fun getParameter(name: R_Name) = null

        override fun compileFull(args: C_FullCallArguments, resTypeHint: C_TypeHint): V_GlobalFunctionCall? {
            val vArgs = args.compileSimpleArgs(name.lazyStr)
            val caseCtx = C_LibFuncCaseCtx(name.pos, naming.fullNameLazy)
            val match = matchCase(ctx, caseCtx, name, vArgs, resTypeHint)
            match ?: return null
            return match.compileCall(ctx, caseCtx)
        }

        override fun compilePartial(args: C_PartialCallArguments, resTypeHint: R_FunctionType?): V_GlobalFunctionCall? {
            val caseCtx = C_LibFuncCaseCtx(name.pos, naming.fullNameLazy)
            return compileCallPartialCommon(ctx, caseCtx, R_UnitType, cases, args, resTypeHint)
        }
    }

    companion object {
        fun <CallT: V_FunctionCall> compileCallPartialCommon(
            ctx: C_ExprContext,
            caseCtx: C_LibFuncCaseCtx,
            selfType: R_Type,
            cases: List<C_LibFuncCase<CallT>>,
            args: C_PartialCallArguments,
            resTypeHint: R_FunctionType?
        ): CallT? {
            val caseTargets = cases.mapNotNull { it.getPartialCallTarget(caseCtx, selfType) }
            if (caseTargets.isEmpty()) {
                val name = getFunctionNameForMessage(caseCtx, cases.map { it.getSpecificName(selfType) })
                args.errPartialNotSupportedFn(name)
                return null
            }

            val partMatch = if (resTypeHint == null) {
                if (caseTargets.size > 1) {
                    val name = getFunctionNameForMessage(caseCtx, caseTargets.map { it.fullName.value })
                    ctx.msgCtx.error(args.wildcardPos, C_Errors.msgPartialCallAmbiguous(name))
                    return null
                }
                PartMatch(caseTargets[0], caseTargets[0].match())
            } else if (caseTargets.size > 1) {
                var matches = caseTargets.mapNotNull { caseTarget ->
                    caseTarget.match(resTypeHint)?.let { PartMatch(caseTarget, it) }
                }
                if (matches.size > 1) {
                    val exactMatches = matches.filter { it.match.exact }
                    if (exactMatches.size == 1) {
                        matches = exactMatches
                    }
                }
                if (matches.size != 1) {
                    val targets = if (matches.isEmpty()) caseTargets else matches.map { it.target }
                    val name = getFunctionNameForMessage(caseCtx, targets.map { it.fullName.value })
                    ctx.msgCtx.error(args.wildcardPos, C_Errors.msgPartialCallAmbiguous(name))
                    return null
                }
                matches[0]
            } else {
                val caseTarget = caseTargets[0]
                val match = caseTarget.match(resTypeHint) ?: caseTarget.match()
                PartMatch(caseTarget, match)
            }

            return compileMatch(ctx, caseCtx, args, partMatch)
        }

        private fun getFunctionNameForMessage(caseCtx: C_LibFuncCaseCtx, caseNames: List<String>): String {
            val caseName = caseNames.toSet().singleOrNull()
            return caseName ?: caseCtx.qualifiedNameMsg()
        }

        private fun <CallT: V_FunctionCall> compileMatch(
            ctx: C_ExprContext,
            caseCtx: C_LibFuncCaseCtx,
            args: C_PartialCallArguments,
            match: PartMatch<CallT>,
        ): CallT? {
            val rParamTypes = match.match.paramTypes()
            if (rParamTypes == null) {
                args.errPartialNotSupportedCase(match.target.codeMsg())
                return null
            }

            val callParams = C_FunctionCallParameters.fromTypes(rParamTypes)
            val callInfo = C_FunctionCallInfo(caseCtx.linkPos, match.target.fullName, callParams)
            val effArgs = args.compileEffectiveArgs(callInfo)
            effArgs ?: return null

            return match.match.compileCall(ctx, effArgs)
        }

        private class PartMatch<CallT: V_FunctionCall>(
            val target: C_PartialCallTarget<CallT>,
            val match: C_PartialCallTargetMatch<CallT>,
        )
    }
}

private class C_RegularLibMemberFunction(
    private val cases: List<C_LibFuncCase<V_MemberFunctionCall>>,
): C_LibMemberFunction() {
    override fun replaceTypeParams(rep: C_TypeMemberReplacement): C_LibMemberFunction {
        val cases2 = cases.mapOrSame { it.replaceTypeParams(rep) }
        return if (cases2 === cases) this else C_RegularLibMemberFunction(cases2)
    }

    override fun getCallTypeHints(selfType: R_Type): C_CallTypeHints = C_LibFunctionParamHints(selfType, cases)

    override fun compileCallFull(
        ctx: C_ExprContext,
        callCtx: C_LibFuncCaseCtx,
        selfType: R_Type,
        args: List<V_Expr>,
        resTypeHint: C_TypeHint,
    ): V_MemberFunctionCall {
        val match = matchCase(ctx, callCtx, selfType, args, resTypeHint)
        match ?: return C_ExprUtils.errorVMemberCall(ctx, ideInfo = cases.first().ideInfo)
        return match.compileCall(ctx, callCtx)
    }

    private fun matchCase(
        ctx: C_ExprContext,
        caseCtx: C_LibFuncCaseCtx,
        selfType: R_Type,
        args: List<V_Expr>,
        resTypeHint: C_TypeHint,
    ): C_LibFuncCaseMatch<V_MemberFunctionCall>? {
        for (case in cases) {
            val res = case.match(caseCtx, selfType, args, resTypeHint)
            if (res != null) {
                return res
            }
        }

        val qName = caseCtx.qualifiedNameMsg()
        val argTypes = args.map { it.type }
        C_LibFuncCaseUtils.errNoMatch(ctx, caseCtx.linkPos, qName, argTypes)
        return null
    }

    override fun compileCallPartial(
        ctx: C_ExprContext,
        caseCtx: C_LibFuncCaseCtx,
        selfType: R_Type,
        args: C_PartialCallArguments,
        resTypeHint: R_FunctionType?,
    ): V_MemberFunctionCall? {
        return C_RegularLibGlobalFunction.compileCallPartialCommon(ctx, caseCtx, selfType, cases, args, resTypeHint)
    }
}

private class C_LibFunctionParamHints(
    private val selfType: R_Type,
    private val cases: List<C_LibFuncCase<*>>,
): C_CallTypeHints {
    private val caseHints: List<C_CallTypeHints> by lazy {
        cases.map { it.getCallTypeHints(selfType) }.toImmList()
    }

    override fun getTypeHint(index: Int?, name: R_Name?): C_TypeHint {
        val hints = caseHints.map { it.getTypeHint(index, name) }
        return C_TypeHint.combined(hints)
    }
}
