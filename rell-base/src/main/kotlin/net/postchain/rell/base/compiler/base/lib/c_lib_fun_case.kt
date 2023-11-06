/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.lib

import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.base.core.C_IdeSymbolInfo
import net.postchain.rell.base.compiler.base.core.C_TypeAdapter
import net.postchain.rell.base.compiler.base.core.C_TypeHint
import net.postchain.rell.base.compiler.base.expr.*
import net.postchain.rell.base.compiler.base.fn.C_EffectivePartialArguments
import net.postchain.rell.base.compiler.base.fn.C_PartialCallTarget
import net.postchain.rell.base.compiler.base.fn.C_PartialCallTargetMatch
import net.postchain.rell.base.compiler.base.namespace.C_Deprecated
import net.postchain.rell.base.compiler.base.utils.C_CodeMsg
import net.postchain.rell.base.compiler.base.utils.C_Errors
import net.postchain.rell.base.compiler.base.utils.toCodeMsg
import net.postchain.rell.base.compiler.vexpr.*
import net.postchain.rell.base.lmodel.*
import net.postchain.rell.base.model.*
import net.postchain.rell.base.model.expr.Db_CallExpr
import net.postchain.rell.base.model.expr.Db_Expr
import net.postchain.rell.base.model.expr.R_FunctionCallTarget
import net.postchain.rell.base.model.expr.R_FunctionCallTarget_SysMemberFunction
import net.postchain.rell.base.mtype.*
import net.postchain.rell.base.utils.*

object C_LibFuncCaseUtils {
    fun makeGlobalCase(
        naming: C_GlobalFunctionNaming,
        lFunction: L_Function,
        outerTypeArgs: Map<R_Name, M_Type>,
        deprecated: C_Deprecated?,
        ideInfo: C_IdeSymbolInfo,
    ): C_LibFuncCase<V_GlobalFunctionCall> {
        var res: C_LibFuncCase<V_GlobalFunctionCall> = C_GlobalLibFuncCase(lFunction, ideInfo, naming, outerTypeArgs)
        if (deprecated != null) {
            res = C_DeprecatedLibFuncCase(res, deprecated)
        }
        return res
    }

    fun makeMemberCase(
        simpleName: R_Name,
        lFunction: L_Function,
        deprecated: C_Deprecated?,
        ideInfo: C_IdeSymbolInfo,
    ): C_LibFuncCase<V_MemberFunctionCall> {
        var res: C_LibFuncCase<V_MemberFunctionCall> = C_MemberLibFuncCase(lFunction, ideInfo, simpleName)
        if (deprecated != null) {
            res = C_DeprecatedLibFuncCase(res, deprecated)
        }
        return res
    }

    fun errNoMatch(ctx: C_ExprContext, pos: S_Pos, name: String, args: List<R_Type>) {
        if (args.any { it.isError() }) return
        val argsStrShort = args.joinToString(",") { it.strCode() }
        val argsStr = args.joinToString { it.strCode() }
        ctx.msgCtx.error(pos, "expr_call_argtypes:[$name]:$argsStrShort", "Function '$name' undefined for arguments ($argsStr)")
    }
}

class C_LibFuncCaseCtx(val linkPos: S_Pos, private val fullNameLazy: LazyString) {
    /** Beware that the returned name is a link name, not actual function definition name
     * (may differ sometimes, e.g. aliases, namespace member links, etc.). */
    fun qualifiedNameMsg() = fullNameLazy.value
}

abstract class C_LibFuncCase<CallT: V_FunctionCall>(val ideInfo: C_IdeSymbolInfo) {
    abstract fun getSpecificName(selfType: R_Type): String
    abstract fun getCallTypeHints(selfType: R_Type): C_CallTypeHints

    abstract fun replaceTypeParams(rep: C_TypeMemberReplacement): C_LibFuncCase<CallT>

    abstract fun match(
        caseCtx: C_LibFuncCaseCtx,
        selfType: R_Type,
        args: List<V_Expr>,
        resTypeHint: C_TypeHint,
    ): C_LibFuncCaseMatch<CallT>?

    open fun getPartialCallTarget(caseCtx: C_LibFuncCaseCtx, selfType: R_Type): C_PartialCallTarget<CallT>? = null
}

abstract class C_LibFuncCaseMatch<CallT: V_FunctionCall> {
    abstract fun compileCall(ctx: C_ExprContext, caseCtx: C_LibFuncCaseCtx): CallT
}

private class C_GenericFuncCaseCtx(
    val outerTypeArgs: Map<R_Name, M_Type>,
    val header: L_FunctionHeader,
)

private class C_CaseMatchBase(
    val resType: R_Type,
    val fullNameLazy: LazyString,
    val linkPos: S_Pos,
) {
    fun makeCallTargetGlobal(ctx: C_ExprContext, linkPos: S_Pos, cFn: C_SysFunction): V_FunctionCallTarget {
        val desc = makeCallTargetDescriptor(ctx, linkPos, cFn)
        return V_FunctionCallTarget_SysGlobalFunction(desc)
    }

    fun makeCallTargetMember(ctx: C_ExprContext, linkPos: S_Pos, cFn: C_SysFunction): V_FunctionCallTarget {
        val desc = makeCallTargetDescriptor(ctx, linkPos, cFn)
        return V_FunctionCallTarget_SysMemberFunction(desc)
    }

    private fun makeCallTargetDescriptor(
        ctx: C_ExprContext,
        linkPos: S_Pos,
        cFn: C_SysFunction,
    ): V_SysFunctionTargetDescriptor {
        val body = cFn.compileCall(C_SysFunctionCtx(ctx, linkPos))
        return V_SysFunctionTargetDescriptor(resType, body.rFn, body.dbFn, fullNameLazy, body.pure)
    }
}

private class C_LibMatchParams(
    private val args: List<V_ExprWrapper>,
    private val adapters: List<C_TypeAdapter>,
) {
    init {
        checkEquals(adapters.size, args.size)
    }

    fun effectiveArgs(ctx: C_ExprContext): List<V_Expr> {
        return args.mapIndexed { i, arg ->
            val expr = arg.unwrap()
            adapters[i].adaptExpr(ctx, expr)
        }
    }
}

private abstract class C_CommonLibFuncCase<CallT: V_FunctionCall>(
    protected val lFunction: L_Function,
    ideInfo: C_IdeSymbolInfo,
): C_LibFuncCase<CallT>(ideInfo) {
    protected abstract fun getFullName(selfType: R_Type): LazyString
    protected abstract fun getCaseContext(selfType: R_Type): C_GenericFuncCaseCtx?

    protected abstract fun makeMatch(
        matchBase: C_CaseMatchBase,
        matchParams: C_LibMatchParams,
        params: List<L_FunctionParam>,
        cFn: C_SysFunction,
    ): C_LibFuncCaseMatch<CallT>

    protected abstract fun makeErrorMatch(
        matchBase: C_CaseMatchBase,
        params: C_LibMatchParams,
        codeMsg: C_CodeMsg,
    ): C_LibFuncCaseMatch<CallT>

    protected abstract fun makePartTarget(
        matchBase: C_CaseMatchBase,
        paramTypes: List<M_Type>,
        minParams: Int,
        cFn: C_SysFunction,
    ): C_PartialCallTarget_LibFunction<CallT>

    final override fun getSpecificName(selfType: R_Type): String {
        val fullNameLazy = getFullName(selfType)
        return fullNameLazy.value
    }

    final override fun getCallTypeHints(selfType: R_Type): C_CallTypeHints {
        val libCtx = getCaseContext(selfType)
        libCtx ?: return C_CallTypeHints_None
        return C_LibFunctionCallTypeHints(libCtx.header)
    }

    final override fun match(
        caseCtx: C_LibFuncCaseCtx,
        selfType: R_Type,
        args: List<V_Expr>,
        resTypeHint: C_TypeHint,
    ): C_LibFuncCaseMatch<CallT>? {
        val genCaseCtx = getCaseContext(selfType)
        genCaseCtx ?: return null

        val header = genCaseCtx.header
        val paramsMatch = header.matchParams(args.size)
        paramsMatch ?: return null

        val actualArgWrappers = args.mapIndexed { i, arg ->
            val param = paramsMatch.actualParams[i]
            if (param.nullable) arg.asNullable() else arg.asWrapper()
        }

        val expectedResultType = L_TypeUtils.getExpectedType(header.typeParams, header.resultType, resTypeHint)
        val argTypes = actualArgWrappers.map { it.type.mType }

        val match = paramsMatch.matchArgs(argTypes, expectedResultType)
        match ?: return null

        val rResultType = L_TypeUtils.getRType(match.actualHeader.resultType)
        val unresolved = match.actualHeader.typeParams

        val fullName = getFullName(selfType)

        val matchBase = C_CaseMatchBase(rResultType ?: R_CtErrorType, fullName, caseCtx.linkPos)
        val matchParams = C_LibMatchParams(actualArgWrappers, match.adapters)

        if (rResultType == null || unresolved.isNotEmpty()) {
            return makeUnresolvedMatch(matchBase, matchParams, unresolved)
        }

        val allTypeArgs = genCaseCtx.outerTypeArgs.unionNoConflicts(match.typeArgs)
        val cFn = getSysFunction(caseCtx, selfType, rResultType, allTypeArgs)
        cFn ?: return null

        try {
            header.validate()
        } catch (e: M_TypeException) {
            val codeMsg = e.code toCodeMsg e.msg
            return makeErrorMatch(matchBase, matchParams, codeMsg)
        }

        return makeMatch(matchBase, matchParams, match.actualHeader.params, cFn)
    }

    private fun makeUnresolvedMatch(
        matchBase: C_CaseMatchBase,
        matchParams: C_LibMatchParams,
        unresolved: List<M_TypeParam>,
    ): C_LibFuncCaseMatch<CallT> {
        val codeMsg = if (unresolved.isNotEmpty()) {
            val unresolvedNames = unresolved.map { it.name }.sorted()
            val paramsCode = unresolvedNames.joinToString(",")
            val paramsMsg = unresolvedNames.toString()
            "fn:sys:unresolved_type_params:%s:$paramsCode" toCodeMsg
                    "Failed to infer type arguments for function '%s': $paramsMsg"
        } else {
            "fn:sys:no_res_type:%s" toCodeMsg "Return type is unknown for function '%s'"
        }

        return makeErrorMatch(matchBase, matchParams, codeMsg)
    }

    final override fun getPartialCallTarget(caseCtx: C_LibFuncCaseCtx, selfType: R_Type): C_PartialCallTarget<CallT>? {
        val genCaseCtx = getCaseContext(selfType)
        genCaseCtx ?: return null

        val header = genCaseCtx.header
        if (header.typeParams.isNotEmpty() || header.params.any { it.lazy || it.arity.many }) {
            return null
        }

        val rResultType = L_TypeUtils.getRType(header.resultType)
        rResultType ?: return null

        val cFn = getSysFunction(caseCtx, selfType, rResultType, genCaseCtx.outerTypeArgs)
        cFn ?: return null

        try {
            header.validate()
        } catch (e: M_TypeException) {
            return null
        }

        val paramTypes = header.params.map { it.type }
        val minParams = header.params.takeWhile { it.arity == M_ParamArity.ONE }.size

        val fullName = getFullName(selfType)
        val matchBase = C_CaseMatchBase(rResultType, fullName, caseCtx.linkPos)
        return makePartTarget(matchBase, paramTypes, minParams, cFn)
    }

    private fun getSysFunction(
        caseCtx: C_LibFuncCaseCtx,
        rSelfType: R_Type,
        rResultType: R_Type,
        typeArgs: Map<R_Name, M_Type>,
    ): C_SysFunction? {
        val rTypeArgs = typeArgs.entries
            .mapNotNullAllOrNull {
                val rType = L_TypeUtils.getRType(it.value)
                if (rType == null) null else (it.key.str to rType)
            }
            ?.toImmMap()
        rTypeArgs ?: return null

        val meta = L_FunctionBodyMeta(
            callPos = caseCtx.linkPos,
            rSelfType = rSelfType,
            rResultType = rResultType,
            rTypeArgs = rTypeArgs,
        )

        return lFunction.body.getSysFunction(meta)
    }
}

sealed class C_GlobalFunctionNaming {
    abstract val fullNameLazy: LazyString

    abstract fun replaceSelfType(selfType: M_Type?): C_GlobalFunctionNaming

    final override fun toString() = fullNameLazy.value

    companion object {
        fun makeQualifiedName(qualifiedName: R_QualifiedName): C_GlobalFunctionNaming {
            return C_GlobalFunctionNaming_QualifiedName(qualifiedName)
        }

        fun makeTypeMember(mType: M_Type, simpleName: R_Name): C_GlobalFunctionNaming {
            return C_GlobalFunctionNaming_TypeMember(mType, simpleName)
        }

        fun makeConstructor(mType: M_Type): C_GlobalFunctionNaming {
            return C_GlobalFunctionNaming_Constructor(mType)
        }
    }
}

private class C_GlobalFunctionNaming_QualifiedName(
    private val qualifiedName: R_QualifiedName,
): C_GlobalFunctionNaming() {
    override val fullNameLazy = LazyString.of {
        qualifiedName.str()
    }

    override fun replaceSelfType(selfType: M_Type?) = this
}

private class C_GlobalFunctionNaming_TypeMember(
    private val mType: M_Type,
    private val simpleName: R_Name,
): C_GlobalFunctionNaming() {
    override val fullNameLazy = LazyString.of {
        "${mType.strCode()}.$simpleName"
    }

    override fun replaceSelfType(selfType: M_Type?): C_GlobalFunctionNaming {
        return if (selfType == null) this else C_GlobalFunctionNaming_TypeMember(selfType, simpleName)
    }
}

private class C_GlobalFunctionNaming_Constructor(private val mType: M_Type): C_GlobalFunctionNaming() {
    override val fullNameLazy = LazyString.of {
        mType.strCode()
    }

    override fun replaceSelfType(selfType: M_Type?) = this
}

private class C_GlobalLibFuncCase(
    lFunction: L_Function,
    ideInfo: C_IdeSymbolInfo,
    private val naming: C_GlobalFunctionNaming,
    private val outerTypeArgs: Map<R_Name, M_Type>,
): C_CommonLibFuncCase<V_GlobalFunctionCall>(lFunction, ideInfo) {
    override fun replaceTypeParams(rep: C_TypeMemberReplacement): C_LibFuncCase<V_GlobalFunctionCall> {
        val naming2 = naming.replaceSelfType(rep.selfType)
        val lFunction2 = lFunction.replaceTypeParams(rep.map)
        return if (naming2 === naming && lFunction2 === lFunction) this else {
            C_GlobalLibFuncCase(lFunction2, ideInfo, naming2, outerTypeArgs)
        }
    }

    override fun getFullName(selfType: R_Type): LazyString {
        return naming.fullNameLazy
    }

    override fun getCaseContext(selfType: R_Type): C_GenericFuncCaseCtx {
        checkEquals(selfType, R_UnitType)
        return C_GenericFuncCaseCtx(outerTypeArgs, lFunction.header)
    }

    override fun makeMatch(
        matchBase: C_CaseMatchBase,
        matchParams: C_LibMatchParams,
        params: List<L_FunctionParam>,
        cFn: C_SysFunction,
    ): C_LibFuncCaseMatch<V_GlobalFunctionCall> {
        return C_GlobalLibFuncCaseMatch(matchBase, matchParams, params, cFn, ideInfo)
    }

    override fun makeErrorMatch(
        matchBase: C_CaseMatchBase,
        params: C_LibMatchParams,
        codeMsg: C_CodeMsg,
    ): C_LibFuncCaseMatch<V_GlobalFunctionCall> {
        return C_GlobalErrorLibFuncCaseMatch(matchBase, params, codeMsg, ideInfo)
    }

    override fun makePartTarget(
        matchBase: C_CaseMatchBase,
        paramTypes: List<M_Type>,
        minParams: Int,
        cFn: C_SysFunction,
    ): C_PartialCallTarget_LibFunction<V_GlobalFunctionCall> {
        return C_PartialCallTarget_GlobalLibFunction(paramTypes, minParams, matchBase, cFn, ideInfo)
    }
}

private class C_MemberLibFuncCase(
    lFunction: L_Function,
    ideInfo: C_IdeSymbolInfo,
    private val simpleName: R_Name,
): C_CommonLibFuncCase<V_MemberFunctionCall>(lFunction, ideInfo) {
    override fun replaceTypeParams(rep: C_TypeMemberReplacement): C_LibFuncCase<V_MemberFunctionCall> {
        val lFunction2 = lFunction.replaceTypeParams(rep.map)
        return if (lFunction2 === lFunction) this else C_MemberLibFuncCase(lFunction2, ideInfo, simpleName)
    }

    override fun getFullName(selfType: R_Type): LazyString {
        return LazyString.of { "${selfType.name}.$simpleName" }
    }

    override fun getCaseContext(selfType: R_Type): C_GenericFuncCaseCtx? {
        val outerTypeArgs = M_TypeUtils.getTypeArgs(selfType.mType)
        val specificHeader = lFunction.header.replaceTypeParams(outerTypeArgs)

        if (specificHeader !== lFunction.header) {
            try {
                specificHeader.validate()
            } catch (e: M_TypeException) {
                return null
            }
        }

        val outerTypeArgTypes = outerTypeArgs
            .mapKeys { R_Name.of(it.key.name) }
            .mapValues { it.value.captureType() }
            .toImmMap()

        return C_GenericFuncCaseCtx(outerTypeArgTypes, specificHeader)
    }

    override fun makeMatch(
        matchBase: C_CaseMatchBase,
        matchParams: C_LibMatchParams,
        params: List<L_FunctionParam>,
        cFn: C_SysFunction,
    ): C_LibFuncCaseMatch<V_MemberFunctionCall> {
        return C_MemberLibFuncCaseMatch(matchBase, matchParams, params, cFn, ideInfo)
    }

    override fun makeErrorMatch(
        matchBase: C_CaseMatchBase,
        params: C_LibMatchParams,
        codeMsg: C_CodeMsg,
    ): C_LibFuncCaseMatch<V_MemberFunctionCall> {
        return C_MemberErrorLibFuncCaseMatch(matchBase, params, codeMsg, ideInfo)
    }

    override fun makePartTarget(
        matchBase: C_CaseMatchBase,
        paramTypes: List<M_Type>,
        minParams: Int,
        cFn: C_SysFunction,
    ): C_PartialCallTarget_LibFunction<V_MemberFunctionCall> {
        return C_MemberPartialCallTarget_LibFunction(paramTypes, minParams, matchBase, cFn, ideInfo)
    }
}

private class C_LibFunctionCallTypeHints(private val header: L_FunctionHeader): C_CallTypeHints {
    override fun getTypeHint(index: Int?, name: R_Name?): C_TypeHint {
        val param = when {
            index == null -> null
            index < 0 -> null
            index < header.params.size -> header.params[index]
            header.params.isNotEmpty() && header.params.last().arity.many -> header.params.last()
            else -> null
        }

        val mType = param?.type
        return if (mType == null) C_TypeHint.NONE else C_TypeHint.ofType(mType)
    }
}

private abstract class C_BaseLibFuncCaseMatch<CallT: V_FunctionCall>(
    protected val matchBase: C_CaseMatchBase,
    private val matchParams: C_LibMatchParams,
): C_LibFuncCaseMatch<CallT>() {
    protected val resType = matchBase.resType

    protected abstract fun compileCall(ctx: C_ExprContext, linkPos: S_Pos, args: List<V_Expr>): CallT

    final override fun compileCall(ctx: C_ExprContext, caseCtx: C_LibFuncCaseCtx): CallT {
        val effArgs = matchParams.effectiveArgs(ctx)
        return compileCall(ctx, caseCtx.linkPos, effArgs)
    }
}

private abstract class C_NormalLibFuncCaseMatch<CallT: V_FunctionCall>(
    matchBase: C_CaseMatchBase,
    matchParams: C_LibMatchParams,
    private val lParams: List<L_FunctionParam>,
): C_BaseLibFuncCaseMatch<CallT>(matchBase, matchParams) {
    protected fun makeCallArgs(ctx: C_ExprContext, args: List<V_Expr>): V_FunctionCallArgs {
        val finalArgs = args.mapIndexed { i, arg -> if (lParams[i].lazy) V_LazyExpr(ctx, arg) else arg }
        return V_FunctionCallArgs.positional(finalArgs)
    }

    protected fun makeVarFacts(args: List<V_Expr>): C_VarFacts {
        checkEquals(args.size, lParams.size)

        var varFacts = C_VarFacts.EMPTY

        for ((i, param) in lParams.withIndex()) {
            if (!param.lazy) {
                val arg = args[i]
                varFacts = varFacts.and(arg.varFacts.postFacts)

                val varId = arg.varId()
                if (param.implies != null && varId != null) {
                    val impFacts = param.implies.toVarFacts(varId)
                    varFacts = varFacts.and(impFacts)
                }
            }
        }

        return varFacts
    }
}

private class C_GlobalLibFuncCaseMatch(
    matchBase: C_CaseMatchBase,
    matchParams: C_LibMatchParams,
    lParams: List<L_FunctionParam>,
    private val cFn: C_SysFunction,
    private val ideInfo: C_IdeSymbolInfo,
): C_NormalLibFuncCaseMatch<V_GlobalFunctionCall>(matchBase, matchParams, lParams) {
    override fun compileCall(ctx: C_ExprContext, linkPos: S_Pos, args: List<V_Expr>): V_GlobalFunctionCall {
        val callTarget = matchBase.makeCallTargetGlobal(ctx, linkPos, cFn)
        val callArgs = makeCallArgs(ctx, args)
        val varFacts = makeVarFacts(args)
        val vCall = V_CommonFunctionCall_Full(linkPos, linkPos, resType, callTarget, callArgs, varFacts)
        val vExpr: V_Expr = V_FunctionCallExpr(ctx, linkPos, null, vCall, false)
        return V_GlobalFunctionCall(vExpr, ideInfo)
    }
}

private class C_MemberLibFuncCaseMatch(
    matchBase: C_CaseMatchBase,
    matchParams: C_LibMatchParams,
    lParams: List<L_FunctionParam>,
    private val cFn: C_SysFunction,
    private val ideInfo: C_IdeSymbolInfo,
): C_NormalLibFuncCaseMatch<V_MemberFunctionCall>(matchBase, matchParams, lParams) {
    override fun compileCall(ctx: C_ExprContext, linkPos: S_Pos, args: List<V_Expr>): V_MemberFunctionCall {
        val callTarget = matchBase.makeCallTargetMember(ctx, linkPos, cFn)
        val callArgs = makeCallArgs(ctx, args)
        val varFacts = makeVarFacts(args)
        val vCall = V_CommonFunctionCall_Full(linkPos, linkPos, resType, callTarget, callArgs, varFacts)
        return V_MemberFunctionCall_CommonCall(ctx, ideInfo, vCall, resType)
    }
}

private abstract class C_ErrorLibFuncCaseMatch<CallT: V_FunctionCall>(
    matchBase: C_CaseMatchBase,
    matchParams: C_LibMatchParams,
    protected val errCodeMsg: C_CodeMsg,
): C_BaseLibFuncCaseMatch<CallT>(matchBase, matchParams) {
    protected abstract fun compileCall0(ctx: C_ExprContext, linkPos: S_Pos): CallT

    final override fun compileCall(ctx: C_ExprContext, linkPos: S_Pos, args: List<V_Expr>): CallT {
        val fnName = matchBase.fullNameLazy.value
        ctx.msgCtx.error(linkPos, errCodeMsg.code.formatSafe(fnName), errCodeMsg.msg.formatSafe(fnName))
        return compileCall0(ctx, linkPos)
    }
}

private class C_GlobalErrorLibFuncCaseMatch(
    matchBase: C_CaseMatchBase,
    matchParams: C_LibMatchParams,
    errCodeMsg: C_CodeMsg,
    private val ideInfo: C_IdeSymbolInfo,
): C_ErrorLibFuncCaseMatch<V_GlobalFunctionCall>(matchBase, matchParams, errCodeMsg) {
    override fun compileCall0(ctx: C_ExprContext, linkPos: S_Pos): V_GlobalFunctionCall {
        val expr = C_ExprUtils.errorVExpr(ctx, linkPos, resType)
        return V_GlobalFunctionCall(expr, ideInfo)
    }
}

private class C_MemberErrorLibFuncCaseMatch(
    matchBase: C_CaseMatchBase,
    matchParams: C_LibMatchParams,
    errCodeMsg: C_CodeMsg,
    private val ideInfo: C_IdeSymbolInfo,
): C_ErrorLibFuncCaseMatch<V_MemberFunctionCall>(matchBase, matchParams, errCodeMsg) {
    override fun compileCall0(ctx: C_ExprContext, linkPos: S_Pos): V_MemberFunctionCall {
        return V_MemberFunctionCall_Error(ctx, ideInfo, resType, errCodeMsg.msg)
    }
}

private abstract class C_PartialCallTarget_LibFunction<CallT: V_FunctionCall>(
    private val params: List<M_Type>,
    private val minParams: Int,
    protected val matchBase: C_CaseMatchBase,
): C_PartialCallTarget<CallT>(matchBase.linkPos, matchBase.fullNameLazy) {
    init {
        check(minParams >= 0)
        check(minParams <= params.size)
    }

    abstract fun compileCall0(ctx: C_ExprContext, linkPos: S_Pos, args: C_EffectivePartialArguments): CallT

    final override fun codeMsg(): C_CodeMsg {
        val name = fullName.value
        val code = "$name(${params.joinToString(",") { it.strCode() }}):${matchBase.resType.strCode()}"
        val msg = "$name(${params.joinToString(", "){ it.strMsg() }}): ${matchBase.resType.str()}"
        return code toCodeMsg msg
    }

    final override fun match(): C_PartialCallTargetMatch<CallT> {
        return C_PartialCallTargetMatch_LibFunction(true, params)
    }

    final override fun match(fnType: R_FunctionType): C_PartialCallTargetMatch<CallT>? {
        val paramCount = fnType.params.size
        val resParams = when {
            paramCount == params.size -> params
            paramCount >= minParams && paramCount < params.size -> params.take(paramCount)
            else -> null
        }
        resParams ?: return null

        val mFnParams = fnType.params.map { it.mType }
        val mFnType = M_Types.function(fnType.result.mType, mFnParams)

        val mSelfType = M_Types.function(matchBase.resType.mType, resParams)
        if (!mFnType.isSuperTypeOf(mSelfType)) {
            return null
        }

        val exact = mFnType == mSelfType
        return C_PartialCallTargetMatch_LibFunction(exact, mFnParams)
    }

    private inner class C_PartialCallTargetMatch_LibFunction(
        exact: Boolean,
        private val params: List<M_Type>,
    ): C_PartialCallTargetMatch<CallT>(exact) {
        override fun paramTypes(): List<R_Type>? {
            return params.mapNotNullAllOrNull { L_TypeUtils.getRType(it) }
        }

        override fun compileCall(ctx: C_ExprContext, args: C_EffectivePartialArguments): CallT {
            return compileCall0(ctx, matchBase.linkPos, args)
        }
    }
}

private class C_PartialCallTarget_GlobalLibFunction(
    params: List<M_Type>,
    minParams: Int,
    matchBase: C_CaseMatchBase,
    private val cFn: C_SysFunction,
    private val ideInfo: C_IdeSymbolInfo,
): C_PartialCallTarget_LibFunction<V_GlobalFunctionCall>(params, minParams, matchBase) {
    override fun compileCall0(
        ctx: C_ExprContext,
        linkPos: S_Pos,
        args: C_EffectivePartialArguments,
    ): V_GlobalFunctionCall {
        val callTarget = matchBase.makeCallTargetGlobal(ctx, linkPos, cFn)
        val fnType = R_FunctionType(args.wildArgs, matchBase.resType)
        val mapping = args.toRMapping()
        val vCall: V_CommonFunctionCall = V_CommonFunctionCall_Partial(linkPos, fnType, callTarget, args.exprArgs, mapping)
        val vExpr: V_Expr = V_FunctionCallExpr(ctx, linkPos, null, vCall, false)
        return V_GlobalFunctionCall(vExpr, ideInfo)
    }
}

private class C_MemberPartialCallTarget_LibFunction(
    params: List<M_Type>,
    minParams: Int,
    matchBase: C_CaseMatchBase,
    private val cFn: C_SysFunction,
    private val ideInfo: C_IdeSymbolInfo,
): C_PartialCallTarget_LibFunction<V_MemberFunctionCall>(params, minParams, matchBase) {
    override fun compileCall0(
        ctx: C_ExprContext,
        linkPos: S_Pos,
        args: C_EffectivePartialArguments,
    ): V_MemberFunctionCall {
        val callTarget = matchBase.makeCallTargetMember(ctx, linkPos, cFn)
        val fnType = R_FunctionType(args.wildArgs, matchBase.resType)
        val mapping = args.toRMapping()
        val vCall = V_CommonFunctionCall_Partial(linkPos, fnType, callTarget, args.exprArgs, mapping)
        return V_MemberFunctionCall_CommonCall(ctx, ideInfo, vCall, fnType)
    }
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
