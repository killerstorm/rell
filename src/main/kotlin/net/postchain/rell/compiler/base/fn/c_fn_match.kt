/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.fn

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_TypeAdapter
import net.postchain.rell.compiler.base.core.C_TypeAdapter_Direct
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.namespace.C_DeclarationType
import net.postchain.rell.compiler.base.namespace.C_Deprecated
import net.postchain.rell.compiler.base.namespace.C_NamespaceElement
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.Db_Expr
import net.postchain.rell.model.expr.R_Expr
import net.postchain.rell.utils.LazyString
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList

abstract class C_ArgsTypesMatcher {
    abstract fun getTypeHint(index: Int): C_TypeHint
    abstract fun match(types: List<R_Type>): C_ArgsTypesMatch?
    abstract fun getFixedParameters(): C_FunctionCallParameters?
}

class C_ArgsTypesMatcher_Fixed(matchers: List<C_ArgTypeMatcher>): C_ArgsTypesMatcher() {
    private val matchers = matchers.toImmList()

    private val fixedParameters0 by lazy {
        val types = matchers.mapNotNull { it.getFixedType() }
        if (types.size != matchers.size) null else C_FunctionCallParameters.fromTypes(types)
    }

    override fun getTypeHint(index: Int) = if (index >= matchers.size) C_TypeHint.NONE else matchers[index].getTypeHint()

    override fun getFixedParameters() = fixedParameters0

    override fun match(types: List<R_Type>) = C_ArgsTypesMatch.match(matchers, types)
}

class C_ArgsTypesMatcher_VarArg(
        fixedMatchers: List<C_ArgTypeMatcher>,
        private val varargMatcher: C_ArgTypeMatcher,
        private val allowZero: Boolean
): C_ArgsTypesMatcher() {
    private val fixedMatchers = fixedMatchers.toImmList()

    override fun getTypeHint(index: Int): C_TypeHint {
        val matcher = if (index >= fixedMatchers.size) varargMatcher else fixedMatchers[index]
        return matcher.getTypeHint()
    }

    override fun getFixedParameters() = null

    override fun match(types: List<R_Type>): C_ArgsTypesMatch? {
        if (types.size < fixedMatchers.size || (!allowZero && types.size <= fixedMatchers.size)) {
            return null
        }

        val res = mutableListOf<C_TypeAdapter>()

        for ((i, matcher) in fixedMatchers.withIndex()) {
            val match = matcher.match(types[i])
            match ?: return null
            res.add(match)
        }

        for (i in fixedMatchers.size until types.size) {
            val match = varargMatcher.match(types[i])
            match ?: return null
            res.add(match)
        }

        return C_ArgsTypesMatch(res)
    }

    companion object {
        fun make(type: R_Type, allowZero: Boolean = true): C_ArgsTypesMatcher {
            return C_ArgsTypesMatcher_VarArg(immListOf(), C_ArgTypeMatcher_Simple(type), allowZero)
        }
    }
}

sealed class C_ArgTypeMatcher {
    abstract fun getTypeHint(): C_TypeHint
    abstract fun match(type: R_Type): C_TypeAdapter?
    open fun getFixedType(): R_Type? = null
}

object C_ArgTypeMatcher_Any: C_ArgTypeMatcher() {
    override fun getTypeHint() = C_TypeHint.NONE
    override fun match(type: R_Type) = C_TypeAdapter_Direct
}

class C_ArgTypeMatcher_Simple(val targetType: R_Type): C_ArgTypeMatcher() {
    override fun getTypeHint() = C_TypeHint.ofType(targetType)
    override fun getFixedType() = targetType

    override fun match(type: R_Type): C_TypeAdapter? {
        return targetType.getTypeAdapter(type)
    }
}

object C_ArgTypeMatcher_Nullable: C_ArgTypeMatcher() {
    override fun getTypeHint() = C_TypeHint.NONE

    override fun match(type: R_Type): C_TypeAdapter? {
        val direct = type is R_NullableType || type == R_NullType
        return if (direct) C_TypeAdapter_Direct else null
    }
}

class C_ArgTypeMatcher_CollectionSub(private val elementType: R_Type): C_ArgTypeMatcher() {
    override fun getTypeHint() = C_TypeHint.collection(elementType)

    override fun match(type: R_Type): C_TypeAdapter? {
        val direct = type is R_CollectionType && elementType.isAssignableFrom(type.elementType)
        return if (direct) C_TypeAdapter_Direct else null
    }
}

class C_ArgTypeMatcher_MapSub(private val keyValueTypes: R_MapKeyValueTypes): C_ArgTypeMatcher() {
    override fun getTypeHint() = C_TypeHint.map(keyValueTypes)

    override fun match(type: R_Type): C_TypeAdapter? {
        val direct = type is R_MapType
                && keyValueTypes.key.isAssignableFrom(type.keyType)
                && keyValueTypes.value.isAssignableFrom(type.valueType)
        return if (direct) C_TypeAdapter_Direct else null
    }
}

class C_ArgTypeMatcher_List(val elementMatcher: C_ArgTypeMatcher): C_ArgTypeMatcher() {
    override fun getTypeHint() = C_TypeHint.NONE

    override fun match(type: R_Type): C_TypeAdapter? {
        if (type !is R_ListType) return null
        val match = elementMatcher.match(type.elementType)
        return if (match == C_TypeAdapter_Direct) match else null
    }
}

object C_ArgTypeMatcher_MirrorStructOperation: C_ArgTypeMatcher() {
    override fun getTypeHint() = C_TypeHint.NONE

    override fun match(type: R_Type): C_TypeAdapter? {
        val direct = type is R_StructType && type.struct.mirrorStructs?.operation != null
        return if (direct) C_TypeAdapter_Direct else null
    }
}

class C_ArgsTypesMatch(private val match: List<C_TypeAdapter>) {
    val size = match.size

    fun effectiveArgs(ctx: C_ExprContext, args: List<V_Expr>): List<V_Expr> {
        checkEquals(args.size, match.size)
        return args.mapIndexed { i, arg -> match[i].adaptExpr(ctx, arg) }
    }

    companion object {
        fun match(params: List<C_ArgTypeMatcher>, args: List<R_Type>): C_ArgsTypesMatch? {
            if (args.size != params.size) {
                return null
            }

            val res = mutableListOf<C_TypeAdapter>()

            for ((i, arg) in args.withIndex()) {
                val param = params[i]
                val match = param.match(arg)
                if (match == null) {
                    return null
                }
                res.add(match)
            }

            return C_ArgsTypesMatch(res)
        }
    }
}

sealed class C_FuncCaseCtx(val linkPos: S_Pos) {
    abstract fun simpleNameMsg(): String
    abstract fun qualifiedNameMsg(): String
    abstract fun qualifiedNameMsgLazy(): LazyString
}

class C_GlobalFuncCaseCtx(
        linkPos: S_Pos,
        private val simpleName: R_Name,
        private val fullNameLazy: LazyString
): C_FuncCaseCtx(linkPos) {
    override fun simpleNameMsg() = simpleName.str
    override fun qualifiedNameMsg() = fullNameLazy.value
    override fun qualifiedNameMsgLazy() = fullNameLazy
    fun filePos() = linkPos.toFilePos()
}

class C_MemberFuncCaseCtx(
    linkPos: S_Pos,
    private val memberName: R_Name,
    private val fullName: LazyString,
): C_FuncCaseCtx(linkPos) {
    override fun simpleNameMsg() = memberName.str
    override fun qualifiedNameMsg() = fullName.value
    override fun qualifiedNameMsgLazy() = fullName
}

abstract class C_FuncCase<CtxT: C_FuncCaseCtx, ExprT> {
    abstract fun getParamTypeHint(index: Int): C_TypeHint
    abstract fun match(ctx: C_ExprContext, args: List<V_Expr>): C_FuncCaseMatch<CtxT, ExprT>?

    open fun getPartialCallTarget(caseCtx: CtxT): C_PartialCallTarget<ExprT>? = null
}

typealias C_MemberFuncCase = C_FuncCase<C_MemberFuncCaseCtx, V_MemberFunctionCall>
typealias C_MemberFuncCaseMatch = C_FuncCaseMatch<C_MemberFuncCaseCtx, V_MemberFunctionCall>

typealias C_GlobalFuncCase = C_FuncCase<C_GlobalFuncCaseCtx, V_GlobalFunctionCall>
typealias C_GlobalFuncCaseMatch = C_FuncCaseMatch<C_GlobalFuncCaseCtx, V_GlobalFunctionCall>

abstract class C_SpecialFuncCase<CtxT: C_FuncCaseCtx, ExprT>: C_FuncCase<CtxT, ExprT>() {
    override fun getParamTypeHint(index: Int) = C_TypeHint.NONE
}

typealias C_GlobalSpecialFuncCase = C_SpecialFuncCase<C_GlobalFuncCaseCtx, V_GlobalFunctionCall>
typealias C_MemberSpecialFuncCase = C_SpecialFuncCase<C_MemberFuncCaseCtx, V_MemberFunctionCall>

abstract class C_FuncCaseMatch<CtxT: C_FuncCaseCtx, ExprT>(val resType: R_Type) {
    abstract fun compileCall(ctx: C_ExprContext, caseCtx: CtxT): ExprT
}

class C_DeprecatedFuncCase<CtxT: C_FuncCaseCtx, ExprT>(
        private val case: C_FuncCase<CtxT, ExprT>,
        private val deprecated: C_Deprecated
): C_FuncCase<CtxT, ExprT>() {
    override fun getParamTypeHint(index: Int) = case.getParamTypeHint(index)

    override fun getPartialCallTarget(caseCtx: CtxT): C_PartialCallTarget<ExprT>? {
        val target = case.getPartialCallTarget(caseCtx)
        return if (target == null) null else C_PartialCallTarget_Deprecated(target)
    }

    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_FuncCaseMatch<CtxT, ExprT>? {
        val match = case.match(ctx, args)
        return if (match == null) null else C_DeprecatedFuncCaseMatch(match, deprecated)
    }

    private class C_DeprecatedFuncCaseMatch<CtxT: C_FuncCaseCtx, ExprT>(
            private val match: C_FuncCaseMatch<CtxT, ExprT>,
            private val deprecated: C_Deprecated
    ): C_FuncCaseMatch<CtxT, ExprT>(match.resType) {
        override fun compileCall(ctx: C_ExprContext, caseCtx: CtxT): ExprT {
            deprecatedMessage(ctx, caseCtx)
            return match.compileCall(ctx, caseCtx)
        }

        private fun deprecatedMessage(ctx: C_ExprContext, caseCtx: CtxT) {
            C_NamespaceElement.deprecatedMessage(
                    ctx.msgCtx,
                    caseCtx.linkPos,
                    caseCtx.qualifiedNameMsg(),
                    C_DeclarationType.FUNCTION,
                    deprecated
            )
        }
    }

    private inner class C_PartialCallTarget_Deprecated<ExprT>(
            private val target: C_PartialCallTarget<ExprT>
    ): C_PartialCallTarget<ExprT>(target.callPos, target.fullName, target.params) {
        override fun matchesType(fnType: R_FunctionType) = target.matchesType(fnType)

        override fun compileCall(ctx: C_ExprContext, args: C_EffectivePartialArguments): ExprT {
            C_NamespaceElement.deprecatedMessage(
                    ctx.msgCtx,
                    callPos,
                    fullName.value,
                    C_DeclarationType.FUNCTION,
                    deprecated
            )
            return target.compileCall(ctx, args)
        }
    }
}

abstract class C_BasicGlobalFuncCaseMatch(resType: R_Type, args: List<V_Expr>): C_GlobalFuncCaseMatch(resType) {
    val args = args.toImmList()

    open val canBeDb = false

    open fun globalConstantRestriction(caseCtx: C_GlobalFuncCaseCtx): V_GlobalConstantRestriction? =
            V_GlobalConstantRestriction("fn:${caseCtx.qualifiedNameMsg()}", "function '${caseCtx.qualifiedNameMsg()}'")

    abstract fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr

    open fun compileCallDbExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<Db_Expr>): Db_Expr {
        throw C_Errors.errFunctionNoSql(caseCtx.linkPos, caseCtx.qualifiedNameMsg())
    }

    final override fun compileCall(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): V_GlobalFunctionCall {
        val vExpr = V_SysBasicGlobalCaseCallExpr(ctx, caseCtx, this)
        return V_GlobalFunctionCall(vExpr)
    }

    fun compileCallR(caseCtx: C_GlobalFuncCaseCtx): R_Expr {
        val rArgs = args.map { it.toRExpr() }
        return compileCallExpr(caseCtx, rArgs)
    }

    fun compileCallDb(caseCtx: C_GlobalFuncCaseCtx): Db_Expr {
        val dbArgs = args.map { it.toDbExpr() }
        return compileCallDbExpr(caseCtx, dbArgs)
    }
}

class C_FormalParamsFuncCase<CtxT: C_FuncCaseCtx, ExprT>(
        private val matcher: C_ArgsTypesMatcher,
        private val body: C_FormalParamsFuncBody<CtxT, ExprT>
): C_FuncCase<CtxT, ExprT>() {
    override fun getParamTypeHint(index: Int) = matcher.getTypeHint(index)

    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_FuncCaseMatch<CtxT, ExprT>? {
        val argTypes = args.map { it.type }
        val paramsMatch = matcher.match(argTypes)
        paramsMatch ?: return null
        return C_FormalParamsFuncCaseMatch(body, args, paramsMatch)
    }

    override fun getPartialCallTarget(caseCtx: CtxT): C_PartialCallTarget<ExprT>? {
        val params = matcher.getFixedParameters()
        return if (params == null) null else {
            C_PartialCallTarget_SysFunction(caseCtx, params, body)
        }
    }
}

class C_FormalParamsFuncCaseMatch<CtxT: C_FuncCaseCtx, ExprT>(
        private val body: C_FormalParamsFuncBody<CtxT, ExprT>,
        private val args: List<V_Expr>,
        private val paramsMatch: C_ArgsTypesMatch = C_ArgsTypesMatch(args.map { C_TypeAdapter_Direct })
): C_FuncCaseMatch<CtxT, ExprT>(body.resType) {
    init {
        checkEquals(paramsMatch.size, args.size)
    }

    override fun compileCall(ctx: C_ExprContext, caseCtx: CtxT): ExprT {
        val effArgs = paramsMatch.effectiveArgs(ctx, args)
        return body.compileCallFull(ctx, caseCtx, effArgs)
    }
}

object C_FuncMatchUtils {
    fun errNoMatch(ctx: C_ExprContext, pos: S_Pos, name: String, args: List<R_Type>) {
        if (args.any { it.isError() }) return
        val argsStrShort = args.joinToString(",") { it.strCode() }
        val argsStr = args.joinToString { it.strCode() }
        ctx.msgCtx.error(pos, "expr_call_argtypes:[$name]:$argsStrShort", "Function '$name' undefined for arguments ($argsStr)")
    }
}
