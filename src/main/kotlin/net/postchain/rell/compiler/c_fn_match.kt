/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.*
import net.postchain.rell.utils.toImmList

abstract class C_ArgsTypesMatcher {
    abstract fun getTypeHint(index: Int): C_TypeHint
    abstract fun match(types: List<R_Type>): C_ArgsTypesMatch?
}

class C_ArgsTypesMatcher_Fixed(matchers: List<C_ArgTypeMatcher>): C_ArgsTypesMatcher() {
    private val matchers = matchers.toImmList()
    override fun getTypeHint(index: Int) = if (index >= matchers.size) C_TypeHint.NONE else matchers[index].getTypeHint()
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

    override fun match(types: List<R_Type>): C_ArgsTypesMatch? {
        if (types.size < fixedMatchers.size || (!allowZero && types.size <= fixedMatchers.size)) {
            return null
        }

        val res = mutableListOf<C_ArgTypeMatch>()

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
}

sealed class C_ArgTypeMatcher {
    abstract fun getTypeHint(): C_TypeHint
    abstract fun match(type: R_Type): C_ArgTypeMatch?
}

object C_ArgTypeMatcher_Any: C_ArgTypeMatcher() {
    override fun getTypeHint() = C_TypeHint.NONE
    override fun match(type: R_Type) = C_ArgTypeMatch_Direct
}

class C_ArgTypeMatcher_Simple(val targetType: R_Type): C_ArgTypeMatcher() {
    override fun getTypeHint() = C_TypeHint.ofType(targetType)

    override fun match(type: R_Type): C_ArgTypeMatch? {
        return if (targetType.isAssignableFrom(type)) {
            C_ArgTypeMatch_Direct
        } else if (targetType == R_DecimalType && type == R_IntegerType) {
            C_ArgTypeMatch_IntegerToDecimal
        } else {
            null
        }
    }
}

object C_ArgTypeMatcher_Nullable: C_ArgTypeMatcher() {
    override fun getTypeHint() = C_TypeHint.NONE

    override fun match(type: R_Type): C_ArgTypeMatch? {
        val direct = type is R_NullableType || type == R_NullType
        return if (direct) C_ArgTypeMatch_Direct else null
    }
}

class C_ArgTypeMatcher_CollectionSub(private val elementType: R_Type): C_ArgTypeMatcher() {
    override fun getTypeHint() = C_TypeHint.collection(elementType)

    override fun match(type: R_Type): C_ArgTypeMatch? {
        val direct = type is R_CollectionType && elementType.isAssignableFrom(type.elementType)
        return if (direct) C_ArgTypeMatch_Direct else null
    }
}

class C_ArgTypeMatcher_MapSub(private val keyValueTypes: R_MapKeyValueTypes): C_ArgTypeMatcher() {
    override fun getTypeHint() = C_TypeHint.map(keyValueTypes)

    override fun match(type: R_Type): C_ArgTypeMatch? {
        val direct = type is R_MapType
                && keyValueTypes.key.isAssignableFrom(type.keyType)
                && keyValueTypes.value.isAssignableFrom(type.valueType)
        return if (direct) C_ArgTypeMatch_Direct else null
    }
}

class C_ArgTypeMatcher_List(val elementMatcher: C_ArgTypeMatcher): C_ArgTypeMatcher() {
    override fun getTypeHint() = C_TypeHint.NONE

    override fun match(type: R_Type): C_ArgTypeMatch? {
        if (type !is R_ListType) return null
        val match = elementMatcher.match(type.elementType)
        return if (match == C_ArgTypeMatch_Direct) match else null
    }
}

object C_ArgTypeMatcher_MirrorStructOperation: C_ArgTypeMatcher() {
    override fun getTypeHint() = C_TypeHint.NONE

    override fun match(type: R_Type): C_ArgTypeMatch? {
        val direct = type is R_StructType && type.struct.mirrorStructs?.operation != null
        return if (direct) C_ArgTypeMatch_Direct else null
    }
}

object C_ArgTypeMatcher_ListOfMirrorStructOperations: C_ArgTypeMatcher() {
    override fun getTypeHint() = C_TypeHint.NONE

    override fun match(type: R_Type): C_ArgTypeMatch? {
        if (type !is R_ListType) return null
        val elemType = type.elementType
        val direct = elemType is R_StructType && elemType.struct.mirrorStructs?.operation != null
        return if (direct) C_ArgTypeMatch_Direct else null
    }
}

sealed class C_ArgTypeMatch {
    abstract fun effectiveArg(ctx: C_ExprContext, arg: V_Expr): V_Expr
}

object C_ArgTypeMatch_Direct: C_ArgTypeMatch() {
    override fun effectiveArg(ctx: C_ExprContext, arg: V_Expr) = arg
}

object C_ArgTypeMatch_IntegerToDecimal: C_ArgTypeMatch() {
    override fun effectiveArg(ctx: C_ExprContext, arg: V_Expr) = C_Utils.integerToDecimalPromotion(ctx, arg)
}

class C_ArgsTypesMatch(private val match: List<C_ArgTypeMatch>) {
    val size = match.size

    fun effectiveArgs(ctx: C_ExprContext, args: List<V_Expr>): List<V_Expr> {
        check(args.size == match.size) { "${args.size} != ${match.size}" }
        return args.mapIndexed { i, arg -> match[i].effectiveArg(ctx, arg) }
    }

    companion object {
        fun match(params: List<C_ArgTypeMatcher>, args: List<R_Type>): C_ArgsTypesMatch? {
            if (args.size != params.size) {
                return null
            }

            val res = mutableListOf<C_ArgTypeMatch>()

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
    abstract fun qualifiedNameMsg(): String
}

class C_GlobalFuncCaseCtx(private val name: S_Name): C_FuncCaseCtx(name.pos) {
    override fun qualifiedNameMsg() = name.str
    fun filePos() = linkPos.toFilePos()
}

class C_MemberFuncCaseCtx(val member: C_MemberLink, val memberName: String): C_FuncCaseCtx(member.linkPos) {
    override fun qualifiedNameMsg() = "${member.base.type().toStrictString()}.$memberName"
}

abstract class C_FuncCase<CtxT: C_FuncCaseCtx> {
    abstract fun getParamTypeHint(index: Int): C_TypeHint
    abstract fun match(ctx: C_ExprContext, args: List<V_Expr>): C_FuncCaseMatch<CtxT>?
}

typealias C_MemberFuncCase = C_FuncCase<C_MemberFuncCaseCtx>
typealias C_MemberFuncCaseMatch = C_FuncCaseMatch<C_MemberFuncCaseCtx>

typealias C_GlobalFuncCase = C_FuncCase<C_GlobalFuncCaseCtx>
typealias C_GlobalFuncCaseMatch = C_FuncCaseMatch<C_GlobalFuncCaseCtx>

abstract class C_SpecialFuncCase<CtxT: C_FuncCaseCtx>: C_FuncCase<CtxT>() {
    override fun getParamTypeHint(index: Int) = C_TypeHint.NONE
}

typealias C_GlobalSpecialFuncCase = C_SpecialFuncCase<C_GlobalFuncCaseCtx>
typealias C_MemberSpecialFuncCase = C_SpecialFuncCase<C_MemberFuncCaseCtx>

abstract class C_FuncCaseMatch<CtxT: C_FuncCaseCtx>(val resType: R_Type) {
    open val canBeDb = true

    abstract fun varFacts(): C_ExprVarFacts

    abstract fun compileCall(ctx: C_ExprContext, caseCtx: CtxT): R_Expr

    open fun compileCallDb(ctx: C_ExprContext, caseCtx: CtxT): Db_Expr {
        throw C_Errors.errFunctionNoSql(caseCtx.linkPos, caseCtx.qualifiedNameMsg())
    }
}

class C_DeprecatedFuncCase<CtxT: C_FuncCaseCtx>(
        private val case: C_FuncCase<CtxT>,
        private val deprecated: C_Deprecated
): C_FuncCase<CtxT>() {
    override fun getParamTypeHint(index: Int) = case.getParamTypeHint(index)

    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_FuncCaseMatch<CtxT>? {
        val match = case.match(ctx, args)
        return if (match == null) match else C_DeprecatedFuncCaseMatch(match, deprecated)
    }

    private class C_DeprecatedFuncCaseMatch<CtxT: C_FuncCaseCtx>(
            private val match: C_FuncCaseMatch<CtxT>,
            private val deprecated: C_Deprecated
    ): C_FuncCaseMatch<CtxT>(match.resType) {
        override fun varFacts() = match.varFacts()

        override fun compileCall(ctx: C_ExprContext, caseCtx: CtxT): R_Expr {
            deprecatedMessage(ctx, caseCtx)
            return match.compileCall(ctx, caseCtx)
        }

        override fun compileCallDb(ctx: C_ExprContext, caseCtx: CtxT): Db_Expr {
            deprecatedMessage(ctx, caseCtx)
            return match.compileCallDb(ctx, caseCtx)
        }

        private fun deprecatedMessage(ctx: C_ExprContext, caseCtx: CtxT) {
            C_DefProxy.deprecatedMessage(
                    ctx.msgCtx,
                    caseCtx.linkPos,
                    caseCtx.qualifiedNameMsg(),
                    C_DefProxyDeprecation(C_DeclarationType.FUNCTION, deprecated)
            )
        }
    }
}

abstract class C_BasicGlobalFuncCaseMatch(resType: R_Type, private val args: List<V_Expr>): C_GlobalFuncCaseMatch(resType) {
    abstract fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr

    open fun compileCallDbExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<Db_Expr>): Db_Expr {
        throw C_Errors.errFunctionNoSql(caseCtx.linkPos, caseCtx.qualifiedNameMsg())
    }

    final override fun varFacts() = C_ExprVarFacts.forSubExpressions(args)

    final override fun compileCall(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): R_Expr {
        return compileCall(caseCtx, args, this::compileCallExpr)
    }

    final override fun compileCallDb(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): Db_Expr {
        return compileCallDb(caseCtx, args, this::compileCallDbExpr)
    }

    companion object {
        fun compileCall(
                caseCtx: C_GlobalFuncCaseCtx,
                args: List<V_Expr>,
                rFactory: (C_GlobalFuncCaseCtx, List<R_Expr>) -> R_Expr
        ): R_Expr {
            val rArgs = args.map { it.toRExpr() }
            val rExpr = rFactory(caseCtx, rArgs)
            return rExpr
        }

        fun compileCallDb(
                caseCtx: C_GlobalFuncCaseCtx,
                args: List<V_Expr>,
                dbFactory: (C_GlobalFuncCaseCtx, List<Db_Expr>) -> Db_Expr
        ): Db_Expr {
            val dbArgs = args.map { it.toDbExpr() }
            val dbExpr = dbFactory(caseCtx, dbArgs)
            return dbExpr
        }
    }
}

class C_FormalParamsFuncCase<CtxT: C_FuncCaseCtx>(
        private val matcher: C_ArgsTypesMatcher,
        private val body: C_FormalParamsFuncBody<CtxT>
): C_FuncCase<CtxT>() {
    override fun getParamTypeHint(index: Int) = matcher.getTypeHint(index)

    override fun match(ctx: C_ExprContext, args: List<V_Expr>): C_FuncCaseMatch<CtxT>? {
        val argTypes = args.map { it.type() }
        val paramsMatch = matcher.match(argTypes)
        paramsMatch ?: return null
        return C_FormalParamsFuncCaseMatch(body, args, paramsMatch)
    }
}

class C_FormalParamsFuncCaseMatch<CtxT: C_FuncCaseCtx>(
        private val body: C_FormalParamsFuncBody<CtxT>,
        private val args: List<V_Expr>,
        private val paramsMatch: C_ArgsTypesMatch = C_ArgsTypesMatch(args.map { C_ArgTypeMatch_Direct })
): C_FuncCaseMatch<CtxT>(body.resType) {
    init {
        check(paramsMatch.size == args.size)
    }

    override fun varFacts(): C_ExprVarFacts {
        return C_ExprVarFacts.forSubExpressions(args)
    }

    override fun compileCall(ctx: C_ExprContext, caseCtx: CtxT): R_Expr {
        val effArgs = paramsMatch.effectiveArgs(ctx, args)
        val rArgs = effArgs.map { it.toRExpr() }
        return body.compileCall(ctx, caseCtx, rArgs)
    }

    override fun compileCallDb(ctx: C_ExprContext, caseCtx: CtxT): Db_Expr {
        val effArgs = paramsMatch.effectiveArgs(ctx, args)
        val dbArgs = effArgs.map { it.toDbExpr() }
        return body.compileCallDb(ctx, caseCtx, dbArgs)
    }
}

object C_FuncMatchUtils {
    fun errNoMatch(ctx: C_ExprContext, pos: S_Pos, name: String, args: List<R_Type>) {
        if (args.any { it.isError() }) return
        val argsStrShort = args.joinToString(",") { it.toStrictString() }
        val argsStr = args.joinToString { it.toStrictString() }
        ctx.msgCtx.error(pos, "expr_call_argtypes:$name:$argsStrShort", "Function '$name' undefined for arguments ($argsStr)")
    }
}
