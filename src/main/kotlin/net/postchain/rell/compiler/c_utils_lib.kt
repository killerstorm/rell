/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_FunctionCallTarget
import net.postchain.rell.compiler.vexpr.V_SysSpecialGlobalCaseCallExpr
import net.postchain.rell.model.*
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

    private fun <T> addPartValues(dst: MutableMap<String, T>, src: Map<String, T>) {
        for ((key, value) in src) {
            check(key !in dst)
            dst[key] = value
        }
    }

    fun makeNsValues(values: Map<String, Rt_Value>): C_Namespace {
        return makeNsEx(
                values = values.mapValues { C_NamespaceValue_Value(it.value) }
        )
    }

    fun typeMemFuncBuilder(type: R_Type): C_MemberFuncBuilder {
        val b = C_MemberFuncBuilder(type.name)

        if (type is R_VirtualType) {
            b.add("hash", R_ByteArrayType, listOf(), R_SysFn_Virtual.Hash)
        } else {
            b.add("hash", listOf(), memFnToGtv(type, R_ByteArrayType, R_SysFn_Any.Hash(type)))
        }

        b.add("to_gtv", listOf(), memFnToGtv(type, R_GtvType, R_SysFn_Any.ToGtv(type, false, "to_gtv")))
        b.add("to_gtv_pretty", listOf(), memFnToGtv(type, R_GtvType, R_SysFn_Any.ToGtv(type, true, "to_gtv_pretty")))
        return b
    }

    fun memFnToGtv(type: R_Type, resType: R_Type, fn: R_SysFunction): C_MemberFormalParamsFuncBody {
        val flags = type.completeFlags()
        return if (!flags.gtv.toGtv) C_SysMemberFunction_Invalid(type) else C_SysMemberFormalParamsFuncBody(resType, fn)
    }
}

private class C_SysMemberFunction_Invalid(private val ownerType: R_Type): C_MemberFormalParamsFuncBody(R_CtErrorType) {
    override fun effectiveResType(caseCtx: C_MemberFuncCaseCtx, type: R_Type): R_Type {
        return C_Utils.effectiveMemberType(type, caseCtx.member.safe)
    }

    override fun makeCallTarget(caseCtx: C_MemberFuncCaseCtx): V_FunctionCallTarget {
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

    override fun compileCall(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): V_Expr {
        return V_SysSpecialGlobalCaseCallExpr(ctx, caseCtx, this)
    }
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

sealed class C_FuncBuilder<BuilderT, CaseCtxT: C_FuncCaseCtx, FuncT>(private val namespace: String?) {
    private val caseMap: MultiValuedMap<String, C_FuncCase<CaseCtxT>> = ArrayListValuedHashMap()
    private val fnMap = mutableMapOf<String, FuncT>()

    protected abstract fun makeBody(result: R_Type, rFn: R_SysFunction, dbFn: Db_SysFunction?): C_FormalParamsFuncBody<CaseCtxT>
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
            deprecated: C_Deprecated
    ): BuilderT = addEx(name, result, params, rFn, null, deprecated)

    fun addEx(
            name: String,
            result: R_Type,
            params: List<C_ArgTypeMatcher>,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction? = null,
            deprecated: C_Deprecated? = null
    ): BuilderT = addEx(name, result, C_ArgsTypesMatcher_Fixed(params), rFn, dbFn, deprecated)

    fun addEx(
            name: String,
            result: R_Type,
            matcher: C_ArgsTypesMatcher,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction? = null,
            deprecated: C_Deprecated? = null
    ): BuilderT {
        val body = makeBody(result, rFn, dbFn)
        val case = makeCase(matcher, body)
        addCase(name, case, deprecated)
        return self()
    }

    fun add(
            name: String,
            result: R_Type,
            params: List<R_Type>,
            rFn: R_SysFunction,
            deprecated: C_Deprecated
    ): BuilderT = add(name, result, params, rFn, null, deprecated)

    fun add(
            name: String,
            result: R_Type,
            params: List<R_Type>,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction? = null,
            deprecated: C_Deprecated? = null
    ): BuilderT {
        val matchers = params.map { C_ArgTypeMatcher_Simple(it) }
        addEx(name, result, matchers, rFn, dbFn, deprecated)
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
        addCase(name, case, deprecated)
        return self()
    }

    fun add(name: String, case: C_FuncCase<CaseCtxT>, deprecated: C_Deprecated? = null): BuilderT {
        addCase(name, case, deprecated)
        return self()
    }

    fun add(name: String, fn: FuncT): BuilderT {
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
            dbFn: Db_SysFunction? = null
    ): BuilderT {
        if (c) {
            add(name, result, params, rFn, dbFn)
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
            deprecated: C_Deprecated? = null
    ): BuilderT {
        addVarArg(name, result, fixedParams, varargParam, true, rFn, dbFn, deprecated)
        return self()
    }

    fun addOneMany(
            name: String,
            result: R_Type,
            fixedParams: List<R_Type>,
            varargParam: R_Type,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction? = null,
            deprecated: C_Deprecated? = null
    ): BuilderT {
        addVarArg(name, result, fixedParams, varargParam, false, rFn, dbFn, deprecated)
        return self()
    }

    fun addOneMany(
            name: String,
            result: R_Type,
            fixedParams: List<C_ArgTypeMatcher>,
            varargParam: C_ArgTypeMatcher,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction? = null,
            deprecated: C_Deprecated? = null
    ): BuilderT {
        addVarArg(name, result, fixedParams, varargParam, false, rFn, dbFn, deprecated)
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
            deprecated: C_Deprecated? = null
    ): BuilderT {
        val fixedMatchers = fixedParams.map { C_ArgTypeMatcher_Simple(it) }
        val varargMatcher = C_ArgTypeMatcher_Simple(varargParam)
        addVarArg(name, result, fixedMatchers, varargMatcher, allowZero, rFn, dbFn, deprecated)
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
            deprecated: C_Deprecated? = null
    ): BuilderT {
        val argsMatcher = C_ArgsTypesMatcher_VarArg(fixedParams, varargParam, allowZero)
        addEx(name, result, argsMatcher, rFn, dbFn, deprecated)
        return self()
    }
}

class C_GlobalFuncBuilder(
        namespace: String?
): C_FuncBuilder<C_GlobalFuncBuilder, C_GlobalFuncCaseCtx, C_GlobalFunction>(namespace) {
    override fun makeBody(
            result: R_Type,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction?
    ): C_FormalParamsFuncBody<C_GlobalFuncCaseCtx> {
        return C_SysGlobalFormalParamsFuncBody(result, rFn, dbFn)
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
        namespace: String
): C_FuncBuilder<C_MemberFuncBuilder, C_MemberFuncCaseCtx, C_SysMemberFunction>(namespace) {
    override fun makeBody(
            result: R_Type,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction?
    ): C_FormalParamsFuncBody<C_MemberFuncCaseCtx> {
        return C_SysMemberFormalParamsFuncBody(result, rFn, dbFn)
    }

    override fun makeFunc(simpleName: String, fullName: String, cases: List<C_FuncCase<C_MemberFuncCaseCtx>>): C_SysMemberFunction {
        return C_CasesSysMemberFunction(cases)
    }

    fun build(): C_MemberFuncTable {
        val fnMap = buildMap()
        return C_MemberFuncTable(fnMap)
    }
}
