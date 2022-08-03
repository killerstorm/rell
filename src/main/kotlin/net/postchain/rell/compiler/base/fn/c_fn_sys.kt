/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.fn

import net.postchain.rell.compiler.ast.S_CallArgument
import net.postchain.rell.compiler.ast.S_CallArgumentValue_Expr
import net.postchain.rell.compiler.ast.S_CallArgumentValue_Wildcard
import net.postchain.rell.compiler.ast.S_Expr
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.def.C_GlobalFunction
import net.postchain.rell.compiler.base.expr.C_CallTypeHints
import net.postchain.rell.compiler.base.expr.C_CallTypeHints_None
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.expr.C_ExprUtils
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.compiler.base.utils.C_SysFunctionCtx
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.R_BooleanType
import net.postchain.rell.model.R_FunctionType
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_Type
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.utils.LazyPosString
import net.postchain.rell.utils.LazyString

abstract class C_FormalParamsFuncBody<CtxT: C_FuncCaseCtx>(val resType: R_Type) {
    abstract fun effectiveResType(caseCtx: CtxT, type: R_Type): R_Type

    abstract fun makeCallTarget(ctx: C_ExprContext, caseCtx: CtxT): V_FunctionCallTarget

    fun compileCall(ctx: C_ExprContext, caseCtx: CtxT, args: List<V_Expr>): V_Expr {
        val pos = caseCtx.linkPos
        val effResType = effectiveResType(caseCtx, resType)

        val callTarget = makeCallTarget(ctx, caseCtx)
        val callArgs = V_FunctionCallArgs.positional(args)

        return V_FullFunctionCallExpr(ctx, pos, pos, effResType, callTarget, callArgs)
    }
}

typealias C_GlobalFormalParamsFuncBody = C_FormalParamsFuncBody<C_GlobalFuncCaseCtx>
typealias C_MemberFormalParamsFuncBody = C_FormalParamsFuncBody<C_MemberFuncCaseCtx>

class C_SysGlobalFormalParamsFuncBody(
        resType: R_Type,
        private val cFn: C_SysFunction
): C_GlobalFormalParamsFuncBody(resType) {
    override fun effectiveResType(caseCtx: C_GlobalFuncCaseCtx, type: R_Type) = type

    override fun makeCallTarget(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): V_FunctionCallTarget {
        val fullName = caseCtx.qualifiedNameMsgLazy()
        val body = cFn.compileCall(C_SysFunctionCtx(ctx, caseCtx.linkPos))
        val desc = V_SysFunctionTargetDescriptor(resType, body.rFn, body.dbFn, fullName, cFn.pure)
        return V_FunctionCallTarget_SysGlobalFunction(desc)
    }
}

class C_SysMemberFormalParamsFuncBody(
        resType: R_Type,
        private val cFn: C_SysFunction,
): C_MemberFormalParamsFuncBody(resType) {
    override fun effectiveResType(caseCtx: C_MemberFuncCaseCtx, type: R_Type): R_Type {
        return C_Utils.effectiveMemberType(type, caseCtx.member.safe)
    }

    override fun makeCallTarget(ctx: C_ExprContext, caseCtx: C_MemberFuncCaseCtx): V_FunctionCallTarget {
        val fullName = caseCtx.qualifiedNameMsgLazy()
        val body = cFn.compileCall(C_SysFunctionCtx(ctx, caseCtx.linkPos))
        val desc = V_SysFunctionTargetDescriptor(resType, body.rFn, body.dbFn, fullName, cFn.pure)
        return V_FunctionCallTarget_SysMemberFunction(desc, caseCtx.member)
    }
}

class C_PartialCallTarget_SysFunction<CtxT: C_FuncCaseCtx>(
        private val caseCtx: CtxT,
        params: C_FunctionCallParameters,
        private val body: C_FormalParamsFuncBody<CtxT>
): C_PartialCallTarget(caseCtx.linkPos, caseCtx.qualifiedNameMsgLazy(), params) {
    override fun matchesType(fnType: R_FunctionType): Boolean {
        return fnType.matchesParameters(params.list) && fnType.result == body.resType
    }

    override fun compileCall(ctx: C_ExprContext, args: C_EffectivePartialArguments): V_Expr {
        val callTarget = body.makeCallTarget(ctx, caseCtx)
        val fnType = R_FunctionType(args.wildArgs, body.resType)
        val effType = body.effectiveResType(caseCtx, fnType)
        val mapping = args.toRMapping()
        return V_PartialFunctionCallExpr(ctx, callPos, effType, callTarget, args.exprArgs, mapping)
    }
}

private class C_SysFunctionParamHints(private val cases: List<C_FuncCase<*>>): C_CallTypeHints {
    override fun getTypeHint(index: Int?, name: R_Name?): C_TypeHint {
        return if (index == null) C_TypeHint.NONE else C_TypeHint_SysFunction(index)
    }

    private inner class C_TypeHint_SysFunction(private val index: Int): C_TypeHint() {
        override fun getListElementType() = calcHint { it.getListElementType() }
        override fun getSetElementType() = calcHint { it.getSetElementType() }
        override fun getMapKeyValueTypes() = calcHint { it.getMapKeyValueTypes() }
        override fun getFunctionType() = calcHint { it.getFunctionType() }

        private fun <T> calcHint(getter: (C_TypeHint) -> T?): T? {
            val set = mutableSetOf<T>()
            for (case in cases) {
                val hint = case.getParamTypeHint(index)
                val value = getter(hint)
                if (value != null) set.add(value)
            }
            return if (set.size != 1) null else set.iterator().next()
        }
    }
}

class C_RegularSysGlobalFunction(
        private val simpleName: R_Name,
        fullName: String,
        private val cases: List<C_GlobalFuncCase>,
        ideInfo: IdeSymbolInfo
): C_GlobalFunction(ideInfo) {
    private val fullNameLazy = LazyString.of(fullName)

    override fun compileCall(ctx: C_ExprContext, name: LazyPosString, args: List<S_CallArgument>, resTypeHint: C_TypeHint): V_Expr {
        val target = C_FunctionCallTarget_SysGlobalFunction(ctx, name)
        val vExpr = C_FunctionCallArgsUtils.compileCall(ctx, args, resTypeHint, target)
        return vExpr ?: C_ExprUtils.errorVExpr(ctx, name.pos)
    }

    private fun matchCase(ctx: C_ExprContext, name: LazyPosString, args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        for (case in cases) {
            val res = case.match(ctx, args)
            if (res != null) {
                return res
            }
        }

        val argTypes = args.map { it.type }
        C_FuncMatchUtils.errNoMatch(ctx, name.pos, name.str, argTypes)
        return null
    }

    private inner class C_FunctionCallTarget_SysGlobalFunction(
            val ctx: C_ExprContext,
            val name: LazyPosString
    ): C_FunctionCallTarget() {
        override fun retType() = null
        override fun typeHints() = C_SysFunctionParamHints(cases)
        override fun hasParameter(name: R_Name) = false

        override fun compileFull(args: C_FullCallArguments): V_Expr? {
            val vArgs = args.compileSimpleArgs(name.lazyStr)
            val match = matchCase(ctx, name, vArgs)
            match ?: return null
            val caseCtx = C_GlobalFuncCaseCtx(name.pos, simpleName, fullNameLazy)
            return match.compileCall(ctx, caseCtx)
        }

        override fun compilePartial(args: C_PartialCallArguments, resTypeHint: R_FunctionType?): V_Expr? {
            val caseCtx = C_GlobalFuncCaseCtx(name.pos, simpleName, fullNameLazy)
            return compileCallPartialCommon(ctx, caseCtx, cases, args, resTypeHint)
        }
    }

    companion object {
        fun <CtxT: C_FuncCaseCtx> compileCallPartialCommon(
                ctx: C_ExprContext,
                caseCtx: CtxT,
                cases: List<C_FuncCase<CtxT>>,
                args: C_PartialCallArguments,
                resTypeHint: R_FunctionType?
        ): V_Expr? {
            val fullName = caseCtx.qualifiedNameMsgLazy()

            var caseTargets = cases.mapNotNull { it.getPartialCallTarget(caseCtx) }
            if (caseTargets.isEmpty()) {
                args.errPartialNotSupported(fullName.value)
                return null
            }

            if (caseTargets.size > 1 && resTypeHint != null) {
                caseTargets = caseTargets.filter { it.matchesType(resTypeHint) }
            }

            if (caseTargets.size != 1) {
                ctx.msgCtx.error(args.wildcardPos, C_Errors.msgPartialCallAmbiguous(fullName.value))
                return null
            }

            val caseTarget = caseTargets[0]
            val callInfo = C_FunctionCallInfo(caseCtx.linkPos, fullName, caseTarget.params)
            val effArgs = args.compileEffectiveArgs(callInfo)
            effArgs ?: return null

            return caseTarget.compileCall(ctx, effArgs)
        }
    }
}

abstract class C_SpecialSysGlobalFunction(ideInfo: IdeSymbolInfo): C_GlobalFunction(ideInfo) {
    protected open fun paramCount(): Int? = null
    protected abstract fun compileCall0(ctx: C_ExprContext, name: LazyPosString, args: List<S_Expr>): V_Expr

    final override fun compileCall(
            ctx: C_ExprContext,
            name: LazyPosString,
            args: List<S_CallArgument>,
            resTypeHint: C_TypeHint
    ): V_Expr {
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
        if (argExprs.size != argExprsZ.size) return C_ExprUtils.errorVExpr(ctx, name.pos)

        val argName = args.mapNotNull { it.name }.firstOrNull()
        if (argName != null) {
            C_Errors.errSysFunctionNamedArg(ctx.msgCtx, name.str, argName)
            argExprs.forEach { it.compileSafe(ctx) }
            return C_ExprUtils.errorVExpr(ctx, name.pos)
        }

        val paramCount = paramCount()
        val argCount = argExprs.size
        if (argCount != paramCount) {
            ctx.msgCtx.error(name.pos, "fn:sys:wrong_arg_count:$paramCount:$argCount",
                    "Wrong number of arguments for function '$name': $argCount instead of $paramCount")
            argExprs.forEach { it.compileSafe(ctx) }
            return C_ExprUtils.errorVExpr(ctx, name.pos, R_BooleanType)
        }

        return compileCall0(ctx, name, argExprs)
    }
}

sealed class C_SysMemberFunction(val ideInfo: IdeSymbolInfo = IdeSymbolInfo.DEF_FUNCTION_SYSTEM) {
    abstract fun getParamsHints(): C_CallTypeHints

    abstract fun compileCallFull(ctx: C_ExprContext, callCtx: C_MemberFuncCaseCtx, args: List<V_Expr>): V_Expr

    abstract fun compileCallPartial(
            ctx: C_ExprContext,
            caseCtx: C_MemberFuncCaseCtx,
            args: C_PartialCallArguments,
            resTypeHint: R_FunctionType?
    ): V_Expr?
}

class C_CasesSysMemberFunction(
        private val cases: List<C_MemberFuncCase>
): C_SysMemberFunction() {
    override fun getParamsHints(): C_CallTypeHints = C_SysFunctionParamHints(cases)

    override fun compileCallFull(ctx: C_ExprContext, callCtx: C_MemberFuncCaseCtx, args: List<V_Expr>): V_Expr {
        val match = matchCase(ctx, callCtx, args)
        match ?: return C_ExprUtils.errorVExpr(ctx, callCtx.member.base.pos)
        return match.compileCall(ctx, callCtx)
    }

    private fun matchCase(
            ctx: C_ExprContext,
            callCtx: C_MemberFuncCaseCtx,
            args: List<V_Expr>
    ): C_MemberFuncCaseMatch? {
        for (case in cases) {
            val res = case.match(ctx, args)
            if (res != null) {
                return res
            }
        }

        val qName = callCtx.qualifiedNameMsg()
        val argTypes = args.map { it.type }
        C_FuncMatchUtils.errNoMatch(ctx, callCtx.member.linkPos, qName, argTypes)
        return null
    }

    override fun compileCallPartial(
            ctx: C_ExprContext,
            caseCtx: C_MemberFuncCaseCtx,
            args: C_PartialCallArguments,
            resTypeHint: R_FunctionType?
    ): V_Expr? {
        return C_RegularSysGlobalFunction.compileCallPartialCommon(ctx, caseCtx, cases, args, resTypeHint)
    }
}

abstract class C_SpecialSysMemberFunction: C_SysMemberFunction() {
    override fun getParamsHints(): C_CallTypeHints = C_CallTypeHints_None
}

class C_SysMemberProperty(
        val type: R_Type,
        val fn: C_SysFunction,
        val pure: Boolean
)
