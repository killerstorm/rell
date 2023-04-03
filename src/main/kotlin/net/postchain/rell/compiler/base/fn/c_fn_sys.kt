/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.fn

import net.postchain.rell.compiler.ast.*
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.def.C_GlobalFunction
import net.postchain.rell.compiler.base.expr.C_CallTypeHints
import net.postchain.rell.compiler.base.expr.C_CallTypeHints_None
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.expr.C_ExprUtils
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.compiler.base.utils.C_SysFunctionBody
import net.postchain.rell.compiler.base.utils.C_SysFunctionCtx
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.*
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.utils.LazyPosString
import net.postchain.rell.utils.LazyString
import net.postchain.rell.utils.checkEquals

abstract class C_FormalParamsFuncBody<CtxT: C_FuncCaseCtx, ExprT>(val resType: R_Type) {
    abstract fun compileCallFull(ctx: C_ExprContext, caseCtx: CtxT, args: List<V_Expr>): ExprT
    abstract fun compileCallPartial(ctx: C_ExprContext, caseCtx: CtxT, args: C_EffectivePartialArguments, callPos: S_Pos): ExprT
}

typealias C_GlobalFormalParamsFuncBody = C_FormalParamsFuncBody<C_GlobalFuncCaseCtx, V_GlobalFunctionCall>
typealias C_MemberFormalParamsFuncBody = C_FormalParamsFuncBody<C_MemberFuncCaseCtx, V_MemberFunctionCall>

class C_SysGlobalFormalParamsFuncBody(
        resType: R_Type,
        private val cFn: C_SysFunction
): C_GlobalFormalParamsFuncBody(resType) {
    override fun compileCallFull(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx, args: List<V_Expr>): V_GlobalFunctionCall {
        val pos = caseCtx.linkPos
        val callTarget = makeCallTarget(ctx, caseCtx)
        val callArgs = V_FunctionCallArgs.positional(args)
        val vCall: V_CommonFunctionCall = V_CommonFunctionCall_Full(pos, pos, resType, callTarget, callArgs)
        val vExpr: V_Expr = V_FunctionCallExpr(ctx, pos, null, vCall, false)
        return V_GlobalFunctionCall(vExpr)
    }

    override fun compileCallPartial(
        ctx: C_ExprContext,
        caseCtx: C_GlobalFuncCaseCtx,
        args: C_EffectivePartialArguments,
        callPos: S_Pos,
    ): V_GlobalFunctionCall {
        val callTarget = makeCallTarget(ctx, caseCtx)
        val fnType = R_FunctionType(args.wildArgs, resType)
        val mapping = args.toRMapping()
        val vCall: V_CommonFunctionCall = V_CommonFunctionCall_Partial(callPos, fnType, callTarget, args.exprArgs, mapping)
        val vExpr: V_Expr = V_FunctionCallExpr(ctx, callPos, null, vCall, false)
        return V_GlobalFunctionCall(vExpr)
    }

    private fun makeCallTarget(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): V_FunctionCallTarget {
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
    override fun compileCallFull(ctx: C_ExprContext, caseCtx: C_MemberFuncCaseCtx, args: List<V_Expr>): V_MemberFunctionCall {
        val pos = caseCtx.linkPos
        val callTarget = makeCallTarget(ctx, caseCtx)
        val callArgs = V_FunctionCallArgs.positional(args)
        val vCall = V_CommonFunctionCall_Full(pos, pos, resType, callTarget, callArgs)
        return V_MemberFunctionCall_CommonCall(ctx, vCall, resType)
    }

    override fun compileCallPartial(
        ctx: C_ExprContext,
        caseCtx: C_MemberFuncCaseCtx,
        args: C_EffectivePartialArguments,
        callPos: S_Pos,
    ): V_MemberFunctionCall {
        val callTarget = makeCallTarget(ctx, caseCtx)
        val fnType = R_FunctionType(args.wildArgs, resType)
        val mapping = args.toRMapping()
        val vCall = V_CommonFunctionCall_Partial(callPos, fnType, callTarget, args.exprArgs, mapping)
        return V_MemberFunctionCall_CommonCall(ctx, vCall, fnType)
    }

    private fun makeCallTarget(ctx: C_ExprContext, caseCtx: C_MemberFuncCaseCtx): V_FunctionCallTarget {
        val fullName = caseCtx.qualifiedNameMsgLazy()
        val body = cFn.compileCall(C_SysFunctionCtx(ctx, caseCtx.linkPos))
        val desc = V_SysFunctionTargetDescriptor(resType, body.rFn, body.dbFn, fullName, cFn.pure)
        return V_FunctionCallTarget_SysMemberFunction(desc)
    }

    private class V_FunctionCallTarget_SysMemberFunction(
        desc: V_SysFunctionTargetDescriptor,
    ): V_FunctionCallTarget_SysFunction(desc) {
        override fun toRTarget(): R_FunctionCallTarget {
            return R_FunctionCallTarget_SysMemberFunction(desc.rFn, desc.fullName)
        }

        override fun toDbExpr(pos: S_Pos, dbBase: Db_Expr?, dbArgs: List<Db_Expr>): Db_Expr {
            checkNotNull(dbBase)

            if (desc.dbFn == null) {
                throw C_Errors.errFunctionNoSql(pos, desc.fullName.value)
            }

            val dbFullArgs = listOf(dbBase) + dbArgs
            return Db_CallExpr(desc.resType, desc.dbFn, dbFullArgs)
        }
    }
}

class C_PartialCallTarget_SysFunction<CtxT: C_FuncCaseCtx, ExprT>(
        private val caseCtx: CtxT,
        params: C_FunctionCallParameters,
        private val body: C_FormalParamsFuncBody<CtxT, ExprT>,
): C_PartialCallTarget<ExprT>(caseCtx.linkPos, caseCtx.qualifiedNameMsgLazy(), params) {
    override fun matchesType(fnType: R_FunctionType): Boolean {
        return fnType.matchesParameters(params.list) && fnType.result == body.resType
    }

    override fun compileCall(ctx: C_ExprContext, args: C_EffectivePartialArguments): ExprT {
        return body.compileCallPartial(ctx, caseCtx, args, callPos)
    }
}

private class C_SysFunctionParamHints(private val cases: List<C_FuncCase<*, *>>): C_CallTypeHints {
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
): C_GlobalFunction() {
    private val fullNameLazy = LazyString.of(fullName)

    override fun compileCall(
        ctx: C_ExprContext,
        name: LazyPosString,
        args: List<S_CallArgument>,
        resTypeHint: C_TypeHint,
    ): V_GlobalFunctionCall {
        val target = C_FunctionCallTarget_SysGlobalFunction(ctx, name)
        val vCall = C_FunctionCallArgsUtils.compileCall(ctx, args, resTypeHint, target)
        return vCall ?: C_ExprUtils.errorVGlobalCall(ctx, name.pos)
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
        override fun getParameter(name: R_Name) = null

        override fun compileFull(args: C_FullCallArguments): V_GlobalFunctionCall? {
            val vArgs = args.compileSimpleArgs(name.lazyStr)
            val match = matchCase(ctx, name, vArgs)
            match ?: return null
            val caseCtx = C_GlobalFuncCaseCtx(name.pos, simpleName, fullNameLazy)
            return match.compileCall(ctx, caseCtx)
        }

        override fun compilePartial(args: C_PartialCallArguments, resTypeHint: R_FunctionType?): V_GlobalFunctionCall? {
            val caseCtx = C_GlobalFuncCaseCtx(name.pos, simpleName, fullNameLazy)
            return compileCallPartialCommon(ctx, caseCtx, cases, args, resTypeHint)
        }
    }

    companion object {
        fun <CtxT: C_FuncCaseCtx, ExprT> compileCallPartialCommon(
                ctx: C_ExprContext,
                caseCtx: CtxT,
                cases: List<C_FuncCase<CtxT, ExprT>>,
                args: C_PartialCallArguments,
                resTypeHint: R_FunctionType?
        ): ExprT? {
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

abstract class C_SpecialSysGlobalFunction: C_GlobalFunction() {
    protected open fun paramCount(): IntRange? = null
    protected abstract fun compileCall0(ctx: C_ExprContext, name: LazyPosString, args: List<S_Expr>): V_GlobalFunctionCall

    final override fun compileCall(
            ctx: C_ExprContext,
            name: LazyPosString,
            args: List<S_CallArgument>,
            resTypeHint: C_TypeHint
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
            C_Errors.errSysFunctionNamedArg(ctx.msgCtx, name.str, argName)
            argExprs.forEach { it.compileSafe(ctx) }
            return C_ExprUtils.errorVGlobalCall(ctx, name.pos)
        }

        val paramCountRange = paramCount()
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

        return compileCall0(ctx, name, argExprs)
    }
}

sealed class C_SysMemberFunction(val ideInfo: IdeSymbolInfo = IdeSymbolInfo.DEF_FUNCTION_SYSTEM) {
    abstract fun getParamsHints(): C_CallTypeHints

    abstract fun compileCallFull(ctx: C_ExprContext, callCtx: C_MemberFuncCaseCtx, args: List<V_Expr>): V_MemberFunctionCall

    abstract fun compileCallPartial(
            ctx: C_ExprContext,
            caseCtx: C_MemberFuncCaseCtx,
            args: C_PartialCallArguments,
            resTypeHint: R_FunctionType?
    ): V_MemberFunctionCall?
}

class C_CasesSysMemberFunction(
        private val cases: List<C_MemberFuncCase>
): C_SysMemberFunction() {
    override fun getParamsHints(): C_CallTypeHints = C_SysFunctionParamHints(cases)

    override fun compileCallFull(ctx: C_ExprContext, callCtx: C_MemberFuncCaseCtx, args: List<V_Expr>): V_MemberFunctionCall {
        val match = matchCase(ctx, callCtx, args)
        match ?: return C_ExprUtils.errorVMemberCall(ctx)
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
        C_FuncMatchUtils.errNoMatch(ctx, callCtx.linkPos, qName, argTypes)
        return null
    }

    override fun compileCallPartial(
            ctx: C_ExprContext,
            caseCtx: C_MemberFuncCaseCtx,
            args: C_PartialCallArguments,
            resTypeHint: R_FunctionType?
    ): V_MemberFunctionCall? {
        return C_RegularSysGlobalFunction.compileCallPartialCommon(ctx, caseCtx, cases, args, resTypeHint)
    }
}

abstract class C_SpecialSysMemberFunction: C_SysMemberFunction() {
    override fun getParamsHints(): C_CallTypeHints = C_CallTypeHints_None
}

class C_SysMemberProperty(
    val type: R_Type,
    val pure: Boolean,
    val fn: C_SysFunctionBody,
) {
    companion object {
        fun simple(type: R_Type, dbFn: Db_SysFunction? = null, pure: Boolean = false, rCode: (Rt_Value) -> Rt_Value): C_SysMemberProperty {
            val rFn = R_SysFunction { ctx, args ->
                checkEquals(args.size, 1)
                rCode(args[0])
            }
            val fn = C_SysFunctionBody(rFn, dbFn)
            return C_SysMemberProperty(type, pure, fn)
        }
    }
}
