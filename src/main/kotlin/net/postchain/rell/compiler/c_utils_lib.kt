/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.model.Db_SysFunction
import net.postchain.rell.model.R_SysFunction
import net.postchain.rell.model.R_Type
import org.apache.commons.collections4.MultiValuedMap
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap

class C_GlobalFuncTable(private val map: Map<String, C_GlobalFunction>) {
    companion object {
        val EMPTY = C_GlobalFuncTable(mapOf())
    }

    fun get(name: String): C_GlobalFunction? {
        return map[name]
    }

    fun toMap(): Map<String, C_GlobalFunction> {
        return map.toMap()
    }
}

class C_MemberFuncTable(private val map: Map<String, C_SysMemberFunction>) {
    fun get(name: String): C_SysMemberFunction? {
        return map[name]
    }
}

sealed class C_FuncBuilder<BuilderT, CaseCtxT: C_FuncCaseCtx, FuncT> {
    private val caseMap: MultiValuedMap<String, C_FuncCase<CaseCtxT>> = ArrayListValuedHashMap()
    private val fnMap = mutableMapOf<String, FuncT>()

    protected abstract fun makeBody(result: R_Type, rFn: R_SysFunction, dbFn: Db_SysFunction?): C_FormalParamsFuncBody<CaseCtxT>
    protected abstract fun makeFunc(cases: List<C_FuncCase<CaseCtxT>>): FuncT

    protected fun addCase(name: String, case: C_FuncCase<CaseCtxT>, deprecated: C_Deprecated?) {
        val case2 = if (deprecated == null) case else makeDeprecatedCase(case, deprecated)
        caseMap.put(name, case2)
    }

    protected fun buildMap(): Map<String, FuncT> {
        val res = mutableMapOf<String, FuncT>()
        for (name in caseMap.keySet().sorted()) {
            val cases = caseMap[name]
            res[name] = makeFunc(cases.toList())
        }
        for (name in fnMap.keys) {
            check(name !in res) { name }
            res[name] = fnMap.getValue(name)
        }
        return res.toMap()
    }

    private fun makeCase(params: List<C_ArgTypeMatcher>, body: C_FormalParamsFuncBody<CaseCtxT>): C_FuncCase<CaseCtxT> {
        return C_FormalParamsFuncCase(params, body)
    }

    private fun makeDeprecatedCase(case: C_FuncCase<CaseCtxT>, deprecated: C_Deprecated): C_FuncCase<CaseCtxT> {
        return C_DeprecatedFuncCase(case, deprecated)
    }

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
    ): BuilderT {
        val body = makeBody(result, rFn, dbFn)
        val case = makeCase(params, body)
        addCase(name, case, deprecated)
        return this as BuilderT
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
        return this as BuilderT
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
        return this as BuilderT
    }

    fun add(name: String, case: C_FuncCase<CaseCtxT>, deprecated: C_Deprecated? = null): BuilderT {
        addCase(name, case, deprecated)
        return this as BuilderT
    }

    fun add(name: String, fn: FuncT): BuilderT {
        check(name !in fnMap) { "Duplicate function: '$name'" }
        fnMap[name] = fn
        return this as BuilderT
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
        return this as BuilderT
    }
}

class C_GlobalFuncBuilder: C_FuncBuilder<C_GlobalFuncBuilder, C_GlobalFuncCaseCtx, C_GlobalFunction>() {
    override fun makeBody(
            result: R_Type,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction?
    ): C_FormalParamsFuncBody<C_GlobalFuncCaseCtx> {
        return C_SysGlobalFormalParamsFuncBody(result, rFn, dbFn)
    }

    override fun makeFunc(cases: List<C_FuncCase<C_GlobalFuncCaseCtx>>): C_GlobalFunction {
        return C_SysGlobalFunction(cases)
    }

    fun build(): C_GlobalFuncTable {
        val fnMap = buildMap()
        return C_GlobalFuncTable(fnMap)
    }
}

class C_MemberFuncBuilder: C_FuncBuilder<C_MemberFuncBuilder, C_MemberFuncCaseCtx, C_SysMemberFunction>() {
    override fun makeBody(
            result: R_Type,
            rFn: R_SysFunction,
            dbFn: Db_SysFunction?
    ): C_FormalParamsFuncBody<C_MemberFuncCaseCtx> {
        return C_SysMemberFormalParamsFuncBody(result, rFn, dbFn)
    }

    override fun makeFunc(cases: List<C_FuncCase<C_MemberFuncCaseCtx>>): C_SysMemberFunction {
        return C_CasesSysMemberFunction(cases)
    }

    fun build(): C_MemberFuncTable {
        val fnMap = buildMap()
        return C_MemberFuncTable(fnMap)
    }
}
