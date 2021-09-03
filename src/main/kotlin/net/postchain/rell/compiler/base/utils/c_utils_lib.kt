/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.utils

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.expr.C_ExprVarFacts
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.fn.*
import net.postchain.rell.compiler.base.namespace.*
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_FunctionCallTarget
import net.postchain.rell.compiler.vexpr.V_GlobalConstantRestriction
import net.postchain.rell.compiler.vexpr.V_SysSpecialGlobalCaseCallExpr
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.Db_SysFunction
import net.postchain.rell.model.expr.R_Expr
import net.postchain.rell.model.lib.R_SysFn_Any
import net.postchain.rell.model.lib.R_SysFn_Virtual
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.toImmMap
import org.apache.commons.collections4.MultiValuedMap
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap

object C_LibUtils {
    fun makeNs(functions: C_GlobalFuncTable, vararg values: Pair<String, C_NamespaceValue>): C_Namespace {
        return makeNsEx(functions = functions, values = mapOf(*values))
    }

    fun makeNsEx(
            types: Map<String, R_Type> = mapOf(),
            namespaces: Map<String, C_Namespace> = mapOf(),
            values: Map<String, C_NamespaceValue> = mapOf(),
            functions: C_GlobalFuncTable = C_GlobalFuncTable.EMPTY,
            parts: List<C_Namespace> = listOf()
    ): C_Namespace {
        val b = C_NamespaceBuilder()

        namespaces.forEach { b.addNamespace(it.key, C_DefProxy.create(it.value)) }
        types.forEach { b.addType(it.key, C_DefProxy.create(it.value)) }
        values.forEach { b.addValue(it.key, C_DefProxy.create(it.value)) }
        functions.toMap().forEach { b.addFunction(it.key, C_DefProxy.create(it.value)) }

        namespaces.forEach { b.addValue(it.key, C_DefProxy.create(C_NamespaceValue_Namespace(C_DefProxy.create(it.value)))) }

        for (part in parts) {
            part.addTo(b)
        }

        return b.build()
    }

    fun makeNsValues(values: Map<String, Rt_Value>): C_Namespace {
        return makeNsEx(
                values = values.mapValues { C_NamespaceValue_RtValue(it.value) }
        )
    }

    fun typeMemFuncBuilder(type: R_Type, pure: Boolean = false): C_MemberFuncBuilder {
        val b = C_MemberFuncBuilder(type.name, pure = pure)

        if (type is R_VirtualType) {
            b.add("hash", R_ByteArrayType, listOf(), R_SysFn_Virtual.Hash, pure = true)
        } else {
            b.add("hash", listOf(), memFnToGtv(type, R_ByteArrayType, R_SysFn_Any.Hash(type)))
        }

        b.add("to_gtv", listOf(), memFnToGtv(type, R_GtvType, R_SysFn_Any.ToGtv(type, false, "to_gtv")))
        b.add("to_gtv_pretty", listOf(), memFnToGtv(type, R_GtvType, R_SysFn_Any.ToGtv(type, true, "to_gtv_pretty")))

        return b
    }

    fun memFnToGtv(type: R_Type, resType: R_Type, fn: R_SysFunction): C_MemberFormalParamsFuncBody {
        val flags = type.completeFlags()
        return if (!flags.gtv.toGtv) {
            C_SysMemberFunction_Invalid(type)
        } else {
            val cFn = C_SysFunction.direct(fn)
            C_SysMemberFormalParamsFuncBody(resType, cFn, pure = true)
        }
    }
}

abstract class C_SysFunction {
    abstract fun compileCall(ctx: C_ExprContext, callPos: S_Pos): Pair<R_SysFunction, Db_SysFunction?>

    companion object {
        fun direct(rFn: R_SysFunction, dbFn: Db_SysFunction? = null): C_SysFunction = C_SysFunction_Direct(rFn, dbFn)

        fun validating(cFn: C_SysFunction, validator: (C_ExprContext, S_Pos) -> Unit): C_SysFunction {
            return C_SysFunction_Validating(cFn, validator)
        }
    }
}

private class C_SysFunction_Direct(
        private val rFn: R_SysFunction,
        private val dbFn: Db_SysFunction?
): C_SysFunction() {
    override fun compileCall(ctx: C_ExprContext, callPos: S_Pos) = Pair(rFn, dbFn)
}

private class C_SysFunction_Validating(
        private val cFn: C_SysFunction,
        private val validator: (C_ExprContext, S_Pos) -> Unit
): C_SysFunction() {
    override fun compileCall(ctx: C_ExprContext, callPos: S_Pos): Pair<R_SysFunction, Db_SysFunction?> {
        validator(ctx, callPos)
        return cFn.compileCall(ctx, callPos)
    }
}

private class C_SysMemberFunction_Invalid(private val ownerType: R_Type): C_MemberFormalParamsFuncBody(R_CtErrorType) {
    override fun effectiveResType(caseCtx: C_MemberFuncCaseCtx, type: R_Type): R_Type {
        return C_Utils.effectiveMemberType(type, caseCtx.member.safe)
    }

    override fun makeCallTarget(ctx: C_ExprContext, caseCtx: C_MemberFuncCaseCtx): V_FunctionCallTarget {
        val typeStr = ownerType.name
        val member = caseCtx.member
        val name = caseCtx.simpleNameMsg()
        throw C_Error.stop(member.linkPos, "fn:invalid:$typeStr:$name", "Function '$name' not available for type '$typeStr'")
    }
}

abstract class C_SpecialGlobalFuncCaseMatch(resType: R_Type): C_GlobalFuncCaseMatch(resType) {
    abstract fun varFacts(): C_ExprVarFacts
    abstract fun subExprs(): List<V_Expr>

    abstract fun compileCallR(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): R_Expr

    final override fun compileCall(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): V_Expr {
        return V_SysSpecialGlobalCaseCallExpr(ctx, caseCtx, this)
    }

    open fun globalConstantRestriction(caseCtx: C_GlobalFuncCaseCtx): V_GlobalConstantRestriction? =
            V_GlobalConstantRestriction("fn:${caseCtx.qualifiedNameMsg()}", "function '${caseCtx.qualifiedNameMsg()}'")
}

class C_GlobalFuncTable(map: Map<String, C_GlobalFunction>) {
    private val map = map.toImmMap()

    fun get(name: String): C_GlobalFunction? {
        return map[name]
    }

    fun toMap(): Map<String, C_GlobalFunction> {
        return map.toMap()
    }

    fun addTo(b: C_GlobalFuncBuilder) {
        map.forEach { b.add(it.key, it.value) }
    }

    companion object {
        val EMPTY = C_GlobalFuncTable(mapOf())
    }
}

class C_MemberFuncTable(private val map: Map<String, C_SysMemberFunction>) {
    fun get(name: String): C_SysMemberFunction? {
        return map[name]
    }
}

sealed class C_FuncBuilder<BuilderT, CaseCtxT: C_FuncCaseCtx, FuncT>(
        private val namespace: String?,
        private val defaultPure: Boolean
) {
    private val caseMap: MultiValuedMap<String, C_FuncCase<CaseCtxT>> = ArrayListValuedHashMap()
    private val fnMap = mutableMapOf<String, FuncT>()

    protected abstract fun makeBody(result: R_Type, cFn: C_SysFunction, pure: Boolean): C_FormalParamsFuncBody<CaseCtxT>
    protected abstract fun makeFunc(simpleName: String, fullName: String, cases: List<C_FuncCase<CaseCtxT>>): FuncT

    protected fun addCase(name: String, case: C_FuncCase<CaseCtxT>, deprecated: C_Deprecated?) {
        val case2 = if (deprecated == null) case else makeDeprecatedCase(case, deprecated)
        caseMap.put(name, case2)
    }

    protected fun buildMap(): Map<String, FuncT> {
        val res = mutableMapOf<String, FuncT>()

        for (name in caseMap.keySet().sorted()) {
            val cases = caseMap[name]
            val fullName = if (namespace == null) name else "$namespace.$name"
            res[name] = makeFunc(name, fullName, cases.toList())
        }

        for (name in fnMap.keys) {
            check(name !in res) { name }
            res[name] = fnMap.getValue(name)
        }

        return res.toMap()
    }

    private fun makeCase(params: List<C_ArgTypeMatcher>, body: C_FormalParamsFuncBody<CaseCtxT>): C_FuncCase<CaseCtxT> {
        val matcher = C_ArgsTypesMatcher_Fixed(params)
        return makeCase(matcher, body)
    }

    private fun makeCase(matcher: C_ArgsTypesMatcher, body: C_FormalParamsFuncBody<CaseCtxT>): C_FuncCase<CaseCtxT> {
        return C_FormalParamsFuncCase(matcher, body)
    }

    private fun makeDeprecatedCase(case: C_FuncCase<CaseCtxT>, deprecated: C_Deprecated): C_FuncCase<CaseCtxT> {
        return C_DeprecatedFuncCase(case, deprecated)
    }

    @Suppress("UNCHECKED_CAST")
    private fun self(): BuilderT = this as BuilderT

    fun addEx(
            name: String,
            result: R_Type,
            params: List<C_ArgTypeMatcher>,
            rFn: R_SysFunction,
            deprecated: C_Deprecated,
            pure: Boolean = defaultPure
    ): BuilderT = addEx(name, result, params, rFn, null, deprecated = deprecated, pure = pure)

    fun addEx(
            name: String,
            result: R_Type,
            params: List<C_ArgTypeMatcher>,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction? = null,
            deprecated: C_Deprecated? = null,
            pure: Boolean = defaultPure
    ): BuilderT = addEx(name, result, C_ArgsTypesMatcher_Fixed(params), rFn, dbFn, deprecated = deprecated, pure = pure)

    fun addEx(
            name: String,
            result: R_Type,
            matcher: C_ArgsTypesMatcher,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction? = null,
            deprecated: C_Deprecated? = null,
            pure: Boolean = defaultPure
    ): BuilderT {
        val cFn = C_SysFunction.direct(rFn, dbFn)
        addEx(name, result, matcher, cFn, deprecated, pure = pure)
        return self()
    }

    fun addEx(
            name: String,
            result: R_Type,
            matcher: C_ArgsTypesMatcher,
            cFn: C_SysFunction,
            deprecated: C_Deprecated? = null,
            pure: Boolean = defaultPure
    ): BuilderT {
        val body = makeBody(result, cFn, pure)
        val case = makeCase(matcher, body)
        addCase(name, case, deprecated)
        return self()
    }

    fun add(
            name: String,
            result: R_Type,
            params: List<R_Type>,
            rFn: R_SysFunction,
            deprecated: C_Deprecated,
            pure: Boolean = defaultPure
    ): BuilderT = add(name, result, params, rFn, null, deprecated = deprecated, pure = pure)

    fun add(
            name: String,
            result: R_Type,
            params: List<R_Type>,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction? = null,
            deprecated: C_Deprecated? = null,
            pure: Boolean = defaultPure
    ): BuilderT {
        val matchers = params.map { C_ArgTypeMatcher_Simple(it) }
        addEx(name, result, matchers, rFn, dbFn, deprecated = deprecated, pure = pure)
        return self()
    }

    fun add(
            name: String,
            result: R_Type,
            params: List<R_Type>,
            cFn: C_SysFunction,
            deprecated: C_Deprecated? = null,
            pure: Boolean = defaultPure
    ): BuilderT {
        val matchers = params.map { C_ArgTypeMatcher_Simple(it) }
        val matcher = C_ArgsTypesMatcher_Fixed(matchers)
        addEx(name, result, matcher, cFn, deprecated = deprecated, pure = pure)
        return self()
    }

    fun add(
            name: String,
            params: List<R_Type>,
            body: C_FormalParamsFuncBody<CaseCtxT>,
            deprecated: C_Deprecated? = null
    ): BuilderT {
        val matchers = params.map { C_ArgTypeMatcher_Simple(it) }
        val case = makeCase(matchers, body)
        addCase(name, case, deprecated = deprecated)
        return self()
    }

    fun add(
            name: String,
            case: C_FuncCase<CaseCtxT>,
            deprecated: C_Deprecated? = null
    ): BuilderT {
        addCase(name, case, deprecated = deprecated)
        return self()
    }

    fun add(name: String, fn: FuncT): BuilderT {
        check(name !in caseMap.keySet()) { "Duplicate function: '$name'" }
        check(name !in fnMap) { "Duplicate function: '$name'" }
        fnMap[name] = fn
        return self()
    }

    fun addIf(
            c: Boolean,
            name: String,
            result: R_Type,
            params: List<R_Type>,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction? = null,
            pure: Boolean = defaultPure
    ): BuilderT {
        if (c) {
            add(name, result, params, rFn, dbFn, pure = pure)
        }
        return self()
    }

    fun addZeroMany(
            name: String,
            result: R_Type,
            fixedParams: List<R_Type>,
            varargParam: R_Type,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction? = null,
            deprecated: C_Deprecated? = null,
            pure: Boolean = defaultPure
    ): BuilderT {
        addVarArg(name, result, fixedParams, varargParam, true, rFn, dbFn, deprecated = deprecated, pure = pure)
        return self()
    }

    fun addOneMany(
            name: String,
            result: R_Type,
            fixedParams: List<R_Type>,
            varargParam: R_Type,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction? = null,
            deprecated: C_Deprecated? = null,
            pure: Boolean = defaultPure
    ): BuilderT {
        addVarArg(name, result, fixedParams, varargParam, false, rFn, dbFn, deprecated = deprecated, pure = pure)
        return self()
    }

    fun addOneMany(
            name: String,
            result: R_Type,
            fixedParams: List<C_ArgTypeMatcher>,
            varargParam: C_ArgTypeMatcher,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction? = null,
            deprecated: C_Deprecated? = null,
            pure: Boolean = defaultPure
    ): BuilderT {
        addVarArg(name, result, fixedParams, varargParam, false, rFn, dbFn, deprecated = deprecated, pure = pure)
        return self()
    }

    private fun addVarArg(
            name: String,
            result: R_Type,
            fixedParams: List<R_Type>,
            varargParam: R_Type,
            allowZero: Boolean,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction? = null,
            deprecated: C_Deprecated? = null,
            pure: Boolean = defaultPure
    ): BuilderT {
        val fixedMatchers = fixedParams.map { C_ArgTypeMatcher_Simple(it) }
        val varargMatcher = C_ArgTypeMatcher_Simple(varargParam)
        addVarArg(name, result, fixedMatchers, varargMatcher, allowZero, rFn, dbFn, deprecated = deprecated, pure = pure)
        return self()
    }

    private fun addVarArg(
            name: String,
            result: R_Type,
            fixedParams: List<C_ArgTypeMatcher>,
            varargParam: C_ArgTypeMatcher,
            allowZero: Boolean,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction? = null,
            deprecated: C_Deprecated? = null,
            pure: Boolean = defaultPure
    ): BuilderT {
        val argsMatcher = C_ArgsTypesMatcher_VarArg(fixedParams, varargParam, allowZero)
        addEx(name, result, argsMatcher, rFn, dbFn, deprecated = deprecated, pure = pure)
        return self()
    }
}

class C_GlobalFuncBuilder(
        namespace: String?,
        pure: Boolean = false
): C_FuncBuilder<C_GlobalFuncBuilder, C_GlobalFuncCaseCtx, C_GlobalFunction>(
        namespace,
        pure
) {
    override fun makeBody(result: R_Type, cFn: C_SysFunction, pure: Boolean): C_FormalParamsFuncBody<C_GlobalFuncCaseCtx> {
        return C_SysGlobalFormalParamsFuncBody(result, cFn, pure = pure)
    }

    override fun makeFunc(simpleName: String, fullName: String, cases: List<C_FuncCase<C_GlobalFuncCaseCtx>>): C_GlobalFunction {
        return C_RegularSysGlobalFunction(simpleName, fullName, cases)
    }

    fun build(): C_GlobalFuncTable {
        val fnMap = buildMap()
        return C_GlobalFuncTable(fnMap)
    }
}

class C_MemberFuncBuilder(
        namespace: String,
        pure: Boolean = false
): C_FuncBuilder<C_MemberFuncBuilder, C_MemberFuncCaseCtx, C_SysMemberFunction>(
        namespace,
        pure
) {
    override fun makeBody(result: R_Type, cFn: C_SysFunction, pure: Boolean): C_FormalParamsFuncBody<C_MemberFuncCaseCtx> {
        return C_SysMemberFormalParamsFuncBody(result, cFn, pure = pure)
    }

    override fun makeFunc(simpleName: String, fullName: String, cases: List<C_FuncCase<C_MemberFuncCaseCtx>>): C_SysMemberFunction {
        return C_CasesSysMemberFunction(cases)
    }

    fun build(): C_MemberFuncTable {
        val fnMap = buildMap()
        return C_MemberFuncTable(fnMap)
    }
}
