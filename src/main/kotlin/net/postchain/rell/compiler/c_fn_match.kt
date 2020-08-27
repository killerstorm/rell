/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_String
import net.postchain.rell.model.*
import net.postchain.rell.utils.toImmList
import java.util.*

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

sealed class C_ArgTypeMatch {
    abstract fun effectiveArg(arg: C_Value): C_Value
}

object C_ArgTypeMatch_Direct: C_ArgTypeMatch() {
    override fun effectiveArg(arg: C_Value) = arg
}

object C_ArgTypeMatch_IntegerToDecimal: C_ArgTypeMatch() {
    override fun effectiveArg(arg: C_Value) = C_Utils.integerToDecimalPromotion(arg)
}

class C_ArgTypesMatch(private val match: List<C_ArgTypeMatch>) {
    val size = match.size

    fun effectiveArgs(args: List<C_Value>): List<C_Value> {
        check(args.size == match.size) { "${args.size} != ${match.size}" }
        return args.mapIndexed { i, arg -> match[i].effectiveArg(arg) }
    }

    companion object {
        fun match(params: List<C_ArgTypeMatcher>, args: List<R_Type>): C_ArgTypesMatch? {
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

            return C_ArgTypesMatch(res)
        }
    }
}

sealed class C_FuncCaseCtx {
    abstract val fullName: S_String
}

class C_GlobalFuncCaseCtx(name: S_Name): C_FuncCaseCtx() {
    override val fullName = S_String(name)

    fun filePos() = fullName.pos.toFilePos()
}

class C_MemberFuncCaseCtx(val member: C_MemberRef): C_FuncCaseCtx() {
    override val fullName = S_String(member.name.pos, member.qualifiedName())
}

abstract class C_FuncCase<CtxT: C_FuncCaseCtx> {
    abstract fun getParamTypeHint(index: Int): C_TypeHint
    abstract fun match(args: List<C_Value>): C_FuncCaseMatch<CtxT>?
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

abstract class C_FuncCaseMatch<CtxT: C_FuncCaseCtx> {
    abstract fun compileCall(ctx: C_ExprContext, caseCtx: CtxT): C_Value

    open fun compileCallDb(ctx: C_ExprContext, caseCtx: CtxT): C_Value {
        val name = caseCtx.fullName
        throw C_Errors.errFunctionNoSql(name.pos, name.str)
    }
}

class C_DeprecatedFuncCase<CtxT: C_FuncCaseCtx>(
        private val case: C_FuncCase<CtxT>,
        private val deprecated: C_Deprecated
): C_FuncCase<CtxT>() {
    override fun getParamTypeHint(index: Int) = case.getParamTypeHint(index)

    override fun match(args: List<C_Value>): C_FuncCaseMatch<CtxT>? {
        val match = case.match(args)
        return if (match == null) match else C_DeprecatedFuncCaseMatch(match, deprecated)
    }

    private class C_DeprecatedFuncCaseMatch<CtxT: C_FuncCaseCtx>(
            private val match: C_FuncCaseMatch<CtxT>,
            private val deprecated: C_Deprecated
    ): C_FuncCaseMatch<CtxT>() {
        override fun compileCall(ctx: C_ExprContext, caseCtx: CtxT): C_Value {
            deprecatedMessage(ctx, caseCtx)
            return match.compileCall(ctx, caseCtx)
        }

        override fun compileCallDb(ctx: C_ExprContext, caseCtx: CtxT): C_Value {
            deprecatedMessage(ctx, caseCtx)
            return match.compileCallDb(ctx, caseCtx)
        }

        private fun deprecatedMessage(ctx: C_ExprContext, caseCtx: CtxT) {
            val name = caseCtx.fullName
            C_DefProxy.deprecatedMessage(
                    ctx.msgCtx,
                    name.pos,
                    name.str,
                    C_DefProxyDeprecation(C_DeclarationType.FUNCTION, deprecated)
            )
        }
    }
}

abstract class C_BasicGlobalFuncCaseMatch(private val args: List<C_Value>): C_GlobalFuncCaseMatch() {
    abstract fun compileCallExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr

    open fun compileCallDbExpr(caseCtx: C_GlobalFuncCaseCtx, args: List<Db_Expr>): Db_Expr {
        throw C_Errors.errFunctionNoSql(caseCtx.fullName.pos, caseCtx.fullName.str)
    }

    final override fun compileCall(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): C_Value {
        return compileCall(caseCtx, args, this::compileCallExpr)
    }

    final override fun compileCallDb(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): C_Value {
        return compileCallDb(caseCtx, args, this::compileCallDbExpr)
    }

    companion object {
        fun compileCall(
                caseCtx: C_GlobalFuncCaseCtx,
                args: List<C_Value>,
                rFactory: (C_GlobalFuncCaseCtx, List<R_Expr>) -> R_Expr
        ): C_Value {
            val rArgs = args.map { it.toRExpr() }
            val rExpr = rFactory(caseCtx, rArgs)
            val facts = C_ExprVarFacts.forSubExpressions(args)
            return C_RValue(caseCtx.fullName.pos, rExpr, facts)
        }

        fun compileCallDb(
                caseCtx: C_GlobalFuncCaseCtx,
                args: List<C_Value>,
                dbFactory: (C_GlobalFuncCaseCtx, List<Db_Expr>) -> Db_Expr
        ): C_Value {
            val dbArgs = args.map { it.toDbExpr() }
            val dbExpr = dbFactory(caseCtx, dbArgs)
            val facts = C_ExprVarFacts.forSubExpressions(args)
            return C_DbValue.create(caseCtx.fullName.pos, dbExpr, facts)
        }
    }
}

class C_FormalParamsFuncCase<CtxT: C_FuncCaseCtx>(
        private val params: List<C_ArgTypeMatcher>,
        private val body: C_FormalParamsFuncBody<CtxT>
): C_FuncCase<CtxT>() {
    override fun getParamTypeHint(index: Int) = if (index >= params.size) C_TypeHint.NONE else params[index].getTypeHint()

    override fun match(args: List<C_Value>): C_FuncCaseMatch<CtxT>? {
        val argTypes = args.map { it.type() }
        val paramsMatch = C_ArgTypesMatch.match(params, argTypes)
        if (paramsMatch == null) {
            return null
        }
        return C_FormalParamsFuncCaseMatch(body, args, paramsMatch)
    }
}

class C_FormalParamsFuncCaseMatch<CtxT: C_FuncCaseCtx>(
        private val body: C_FormalParamsFuncBody<CtxT>,
        private val args: List<C_Value>,
        private val paramsMatch: C_ArgTypesMatch = C_ArgTypesMatch(args.map { C_ArgTypeMatch_Direct })
): C_FuncCaseMatch<CtxT>() {
    init {
        check(paramsMatch.size == args.size)
    }

    override fun compileCall(ctx: C_ExprContext, caseCtx: CtxT): C_Value {
        val effArgs = paramsMatch.effectiveArgs(args)
        return body.compileCall(ctx, caseCtx, effArgs)
    }

    override fun compileCallDb(ctx: C_ExprContext, caseCtx: CtxT): C_Value {
        val effArgs = paramsMatch.effectiveArgs(args)
        return body.compileCallDb(ctx, caseCtx, effArgs)
    }
}

object C_FuncMatchUtils {
    fun errNoMatch(pos: S_Pos, name: String, args: List<R_Type>): C_Error {
        val argsStrShort = args.joinToString(",") { it.toStrictString() }
        val argsStr = args.joinToString { it.toStrictString() }
        return C_Error(pos, "expr_call_argtypes:$name:$argsStrShort", "Function $name undefined for arguments ($argsStr)")
    }
}
