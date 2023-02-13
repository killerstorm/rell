/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.utils

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_DefinitionName
import net.postchain.rell.compiler.base.core.C_DefinitionPath
import net.postchain.rell.compiler.base.def.C_GlobalFunction
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.expr.C_ExprVarFacts
import net.postchain.rell.compiler.base.expr.C_TypeValueMember
import net.postchain.rell.compiler.base.expr.C_TypeValueMember_Function
import net.postchain.rell.compiler.base.fn.*
import net.postchain.rell.compiler.base.namespace.*
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.lib.type.C_Lib_Type_Any
import net.postchain.rell.lib.type.C_Lib_Type_Virtual
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.Db_SysFunction
import net.postchain.rell.model.expr.R_Expr
import net.postchain.rell.runtime.Rt_CallContext
import net.postchain.rell.runtime.Rt_DecimalValue
import net.postchain.rell.runtime.Rt_IntValue
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.*
import org.apache.commons.collections4.MultiValuedMap
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap
import java.math.BigDecimal

object C_LibUtils {
    val DEFAULT_MODULE = R_ModuleName.EMPTY
    val DEFAULT_MODULE_STR = DEFAULT_MODULE.str()

    fun defName(name: String) = C_DefinitionName(DEFAULT_MODULE_STR, name)

    fun makeNs(
        basePath: C_DefinitionPath,
        functions: C_GlobalFuncTable,
        vararg properties: Pair<String, C_NamespaceProperty>
    ): C_Namespace {
        val b = C_SysNsProtoBuilder(basePath)
        for ((name, f) in functions.toMap()) {
            b.addFunction(name, f)
        }
        for ((name, v) in properties) {
            b.addProperty(name, v)
        }
        return b.build().toNamespace()
    }

    fun makeNsValues(basePath: C_DefinitionPath, constants: Map<String, Rt_Value>): C_Namespace {
        val pairs = constants.mapValues { C_NamespaceProperty_RtValue(IdeSymbolInfo.DEF_CONSTANT, it.value) }.toList().toTypedArray()
        return makeNs(basePath, C_GlobalFuncTable.EMPTY, *pairs)
    }

    fun typeGlobalFuncBuilder(type: R_Type): C_GlobalFuncBuilder {
        val b = C_GlobalFuncBuilder(type.defName.toPath())
        addGlobalFnFromGtv(b, "from_gtv", type, R_GtvType, C_Lib_Type_Any.FromGtv(type, false, "from_gtv"))

        if (type !is R_VirtualType) {
            val name = "from_gtv_pretty"
            addGlobalFnFromGtv(b, name, type, R_GtvType, C_Lib_Type_Any.FromGtv(type, true, name))
        }

        return b
    }

    fun addGlobalFnFromGtv(
        b: C_GlobalFuncBuilder,
        name: String,
        resType: R_Type,
        argType: R_Type,
        cFn: C_SysFunction,
        deprecated: C_Deprecated? = null
    ) {
        val flags = resType.completeFlags()
        val resFn = if (flags.gtv.fromGtv) cFn else {
            C_SysFunction.validating(cFn) { ctx ->
                val typeStr = resType.name
                ctx.exprCtx.msgCtx.error(ctx.callPos, "fn:invalid:$typeStr:$name",
                    "Function '$name' not available for type '$typeStr'")
            }
        }
        b.add(name, resType, listOf(argType), resFn, deprecated)
    }

    fun typeMemFuncBuilder(type: R_Type, default: Boolean = true): C_MemberFuncBuilder {
        val b = C_MemberFuncBuilder(type.defName)

        if (default) {
            if (type is R_VirtualType) {
                b.add("hash", R_ByteArrayType, listOf(), C_Lib_Type_Virtual.Hash)
            } else {
                addMemFnToGtv(b, "hash", type, R_ByteArrayType, C_Lib_Type_Any.Hash(type))
            }

            addMemFnToGtv(b, "to_gtv", type, R_GtvType, C_Lib_Type_Any.ToGtv(type, false, "to_gtv"))
            addMemFnToGtv(b, "to_gtv_pretty", type, R_GtvType, C_Lib_Type_Any.ToGtv(type, true, "to_gtv_pretty"))
        }

        return b
    }

    fun addMemFnToGtv(
        b: C_MemberFuncBuilder,
        name: String,
        type: R_Type,
        resType: R_Type,
        cFn: C_SysFunction,
        deprecated: C_Deprecated? = null
    ) {
        val flags = type.completeFlags()
        val resFn = if (flags.gtv.toGtv) cFn else {
            C_SysFunction.validating(cFn) { ctx ->
                val typeStr = type.name
                ctx.exprCtx.msgCtx.error(ctx.callPos, "fn:invalid:$typeStr:$name",
                    "Function '$name' not available for type '$typeStr'")
            }
        }
        b.add(name, resType, listOf(), resFn, deprecated = deprecated)
    }

    fun constValue(name: String, value: Long) = constValue(name, Rt_IntValue(value))
    fun constValue(name: String, value: BigDecimal) = constValue(name, Rt_DecimalValue.of(value))

    private fun constValue(name: String, value: Rt_Value): Pair<String, C_NamespaceProperty> {
        val ideInfo = IdeSymbolInfo(IdeSymbolKind.DEF_CONSTANT)
        return Pair(name, C_NamespaceProperty_RtValue(ideInfo, value))
    }

    fun depWarn(newName: String) = C_Deprecated(useInstead = newName, error = false)
    fun depError(newName: String) = C_Deprecated(useInstead = newName, error = true)

    fun bindFunctions(nsBuilder: C_SysNsProtoBuilder, fns: C_GlobalFuncTable) {
        for (f in fns.toMap()) {
            nsBuilder.addFunction(f.key, f.value)
        }
    }

    fun makeValueMembers(
        baseType: R_Type,
        fns: C_MemberFuncTable,
        otherMembers: List<C_TypeValueMember> = immListOf(),
    ): List<C_TypeValueMember> {
        val fnMembers = fns.toMap().entries.map { C_TypeValueMember_Function(it.key, baseType, it.value) }
        return (otherMembers + fnMembers).toImmList()
    }
}

class C_SysFunctionBody(val rFn: R_SysFunction, val dbFn: Db_SysFunction?)

class C_SysFunctionCtx(val exprCtx: C_ExprContext, val callPos: S_Pos)

abstract class C_SysFunction(val pure: Boolean) {
    abstract fun compileCall(ctx: C_SysFunctionCtx): C_SysFunctionBody

    companion object {
        fun simple(
            dbFn: Db_SysFunction? = null,
            pure: Boolean = false,
            rCode: (List<Rt_Value>) -> Rt_Value
        ): C_SysFunction {
            val rFn = R_SysFunction { _, args ->
                rCode(args)
            }
            return direct(rFn, dbFn, pure = pure)
        }

        fun simple0(pure: Boolean = false, rCode: () -> Rt_Value): C_SysFunction {
            val rFn = R_SysFunction { _, args ->
                Rt_Utils.checkEquals(args.size, 0)
                rCode()
            }
            return direct(rFn, pure = pure)
        }

        fun simple1(dbFn: Db_SysFunction? = null, pure: Boolean = false, rCode: (Rt_Value) -> Rt_Value): C_SysFunction {
            val rFn = rSimple1(rCode)
            return direct(rFn, dbFn, pure = pure)
        }

        fun simple2(
            dbFn: Db_SysFunction? = null,
            pure: Boolean = false,
            rCode: (Rt_Value, Rt_Value) -> Rt_Value
        ): C_SysFunction {
            val rFn = R_SysFunction { ctx, args ->
                Rt_Utils.checkEquals(args.size, 2)
                val res = rCode(args[0], args[1])
                res
            }
            return direct(rFn, dbFn, pure = pure)
        }

        fun simple3(
            dbFn: Db_SysFunction? = null,
            pure: Boolean = false,
            rCode: (Rt_Value, Rt_Value, Rt_Value) -> Rt_Value
        ): C_SysFunction {
            val rFn = R_SysFunction { ctx, args ->
                Rt_Utils.checkEquals(args.size, 3)
                val res = rCode(args[0], args[1], args[2])
                res
            }
            return direct(rFn, dbFn, pure = pure)
        }

        fun simple4(
            dbFn: Db_SysFunction? = null,
            pure: Boolean = false,
            rCode: (Rt_Value, Rt_Value, Rt_Value, Rt_Value) -> Rt_Value
        ): C_SysFunction {
            val rFn = R_SysFunction { ctx, args ->
                Rt_Utils.checkEquals(args.size, 4)
                val res = rCode(args[0], args[1], args[2], args[3])
                res
            }
            return direct(rFn, dbFn, pure = pure)
        }

        fun context1(
            dbFn: Db_SysFunction? = null,
            pure: Boolean = false,
            rCode: (Rt_CallContext, Rt_Value) -> Rt_Value
        ): C_SysFunction {
            val rFn = rContext1(rCode)
            return direct(rFn, dbFn, pure = pure)
        }

        fun rSimple1(rCode: (Rt_Value) -> Rt_Value): R_SysFunction = rContext1 { _, a ->
            rCode(a)
        }

        fun rContext1(rCode: (Rt_CallContext, Rt_Value) -> Rt_Value): R_SysFunction = R_SysFunction { ctx, args ->
            Rt_Utils.checkEquals(args.size, 1)
            val res = rCode(ctx, args[0])
            res
        }

        fun direct(rFn: R_SysFunction, dbFn: Db_SysFunction? = null, pure: Boolean = false): C_SysFunction {
            val body = C_SysFunctionBody(rFn, dbFn)
            return C_SysFunction_Direct(body, pure)
        }

        fun validating(
            rFn: R_SysFunction,
            dbFn: Db_SysFunction? = null,
            validator: (C_SysFunctionCtx) -> Unit
        ): C_SysFunction {
            val cFn = direct(rFn, dbFn)
            return validating(cFn, validator)
        }

        fun validating(
            cFn: C_SysFunction,
            validator: (C_SysFunctionCtx) -> Unit
        ): C_SysFunction {
            return C_SysFunction_Validating(cFn, validator)
        }
    }
}

private class C_SysFunction_Direct(private val body: C_SysFunctionBody, pure: Boolean): C_SysFunction(pure) {
    override fun compileCall(ctx: C_SysFunctionCtx) = body
}

private class C_SysFunction_Validating(
        private val cFn: C_SysFunction,
        private val validator: (C_SysFunctionCtx) -> Unit
): C_SysFunction(pure = cFn.pure) {
    override fun compileCall(ctx: C_SysFunctionCtx): C_SysFunctionBody {
        validator(ctx)
        return cFn.compileCall(ctx)
    }
}

abstract class C_SpecialGlobalFuncCaseMatch(resType: R_Type): C_GlobalFuncCaseMatch(resType) {
    abstract fun varFacts(): C_ExprVarFacts
    abstract fun subExprs(): List<V_Expr>

    abstract fun compileCallR(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): R_Expr

    final override fun compileCall(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx): V_GlobalFunctionCall {
        val vExpr = V_SysSpecialGlobalCaseCallExpr(ctx, caseCtx, this)
        return V_GlobalFunctionCall(vExpr)
    }

    open fun globalConstantRestriction(caseCtx: C_GlobalFuncCaseCtx): V_GlobalConstantRestriction? =
            V_GlobalConstantRestriction("fn:${caseCtx.qualifiedNameMsg()}", "function '${caseCtx.qualifiedNameMsg()}'")
}

class C_GlobalFuncTable(map: Map<R_Name, C_GlobalFunction>) {
    private val map = map.toImmMap()

    fun get(name: R_Name): C_GlobalFunction? {
        return map[name]
    }

    fun toMap() = map

    companion object {
        val EMPTY = C_GlobalFuncTable(mapOf())
    }
}

class C_MemberFuncTable(map: Map<R_Name, C_SysMemberFunction>) {
    private val map = map.toImmMap()

    fun toMap() = map

    fun get(name: R_Name): C_SysMemberFunction? {
        return map[name]
    }

    companion object {
        val EMPTY = C_MemberFuncTable(immMapOf())
    }
}

sealed class C_FuncBuilder<BuilderT, CaseCtxT: C_FuncCaseCtx, FuncT, ExprT>(
        private val defPath: C_DefinitionPath
) {
    private val caseMap: MultiValuedMap<R_Name, C_FuncCase<CaseCtxT, ExprT>> = ArrayListValuedHashMap()
    private val fnMap = mutableMapOf<R_Name, FuncT>()

    protected abstract fun makeBody(result: R_Type, cFn: C_SysFunction): C_FormalParamsFuncBody<CaseCtxT, ExprT>
    protected abstract fun makeFunc(simpleName: R_Name, fullName: String, cases: List<C_FuncCase<CaseCtxT, ExprT>>): FuncT

    private fun addCase(name: String, case: C_FuncCase<CaseCtxT, ExprT>, deprecated: C_Deprecated?) {
        val rName = R_Name.of(name)
        val case2 = if (deprecated == null) case else makeDeprecatedCase(case, deprecated)
        caseMap.put(rName, case2)
    }

    protected fun buildMap(): Map<R_Name, FuncT> {
        val res = mutableMapOf<R_Name, FuncT>()

        for (name in caseMap.keySet().sorted()) {
            val cases = caseMap[name]
            val fullName = defPath.subName(name).qualifiedName.str()
            res[name] = makeFunc(name, fullName, cases.toList())
        }

        for (name in fnMap.keys) {
            check(name !in res) { name }
            res[name] = fnMap.getValue(name)
        }

        return res.toMap()
    }

    private fun makeCase(params: List<C_ArgTypeMatcher>, body: C_FormalParamsFuncBody<CaseCtxT, ExprT>): C_FuncCase<CaseCtxT, ExprT> {
        val matcher = C_ArgsTypesMatcher_Fixed(params)
        return makeCase(matcher, body)
    }

    private fun makeCase(matcher: C_ArgsTypesMatcher, body: C_FormalParamsFuncBody<CaseCtxT, ExprT>): C_FuncCase<CaseCtxT, ExprT> {
        return C_FormalParamsFuncCase(matcher, body)
    }

    private fun makeDeprecatedCase(case: C_FuncCase<CaseCtxT, ExprT>, deprecated: C_Deprecated): C_FuncCase<CaseCtxT, ExprT> {
        return C_DeprecatedFuncCase(case, deprecated)
    }

    @Suppress("UNCHECKED_CAST")
    private fun self(): BuilderT = this as BuilderT

    fun addEx(
            name: String,
            result: R_Type,
            params: List<C_ArgTypeMatcher>,
            rFn: R_SysFunction,
            deprecated: C_Deprecated? = null
    ): BuilderT {
        val cFn = C_SysFunction.direct(rFn)
        addEx(name, result, params, cFn, deprecated = deprecated)
        return self()
    }

    fun addEx(
            name: String,
            result: R_Type,
            params: List<C_ArgTypeMatcher>,
            cFn: C_SysFunction,
            deprecated: C_Deprecated? = null
    ): BuilderT = addEx(name, result, C_ArgsTypesMatcher_Fixed(params), cFn, deprecated = deprecated)

    private fun addEx(
            name: String,
            result: R_Type,
            matcher: C_ArgsTypesMatcher,
            cFn: C_SysFunction,
            deprecated: C_Deprecated? = null
    ): BuilderT {
        val body = makeBody(result, cFn)
        val case = makeCase(matcher, body)
        addCase(name, case, deprecated)
        return self()
    }

    fun add(
            name: String,
            result: R_Type,
            params: List<R_Type>,
            rFn: R_SysFunction,
            deprecated: C_Deprecated? = null
    ): BuilderT {
        val matchers = params.map { C_ArgTypeMatcher_Simple(it) }
        addEx(name, result, matchers, rFn, deprecated = deprecated)
        return self()
    }

    fun add(
            name: String,
            result: R_Type,
            params: List<R_Type>,
            cFn: C_SysFunction,
            deprecated: C_Deprecated? = null
    ): BuilderT {
        val matchers = params.map { C_ArgTypeMatcher_Simple(it) }
        val matcher = C_ArgsTypesMatcher_Fixed(matchers)
        addEx(name, result, matcher, cFn, deprecated = deprecated)
        return self()
    }

    fun add(
            name: String,
            case: C_FuncCase<CaseCtxT, ExprT>,
            deprecated: C_Deprecated? = null
    ): BuilderT {
        addCase(name, case, deprecated = deprecated)
        return self()
    }

    fun add(name: String, fn: FuncT): BuilderT {
        val rName = R_Name.of(name)
        check(rName !in caseMap.keySet()) { "Duplicate function: '$rName'" }
        check(rName !in fnMap) { "Duplicate function: '$rName'" }
        fnMap[rName] = fn
        return self()
    }

    fun addZeroMany(
            name: String,
            result: R_Type,
            fixedParams: List<R_Type>,
            varargParam: R_Type,
            rFn: R_SysFunction,
            deprecated: C_Deprecated? = null
    ): BuilderT {
        addVarArg(name, result, fixedParams, varargParam, true, rFn, deprecated = deprecated)
        return self()
    }

    fun addOneMany(
            name: String,
            result: R_Type,
            fixedParams: List<R_Type>,
            varargParam: R_Type,
            rFn: R_SysFunction,
            deprecated: C_Deprecated? = null
    ): BuilderT {
        addVarArg(name, result, fixedParams, varargParam, false, rFn, deprecated = deprecated)
        return self()
    }

    fun addOneMany(
            name: String,
            result: R_Type,
            fixedParams: List<C_ArgTypeMatcher>,
            varargParam: C_ArgTypeMatcher,
            rFn: R_SysFunction,
            deprecated: C_Deprecated? = null
    ): BuilderT {
        addVarArg(name, result, fixedParams, varargParam, false, rFn, deprecated = deprecated)
        return self()
    }

    private fun addVarArg(
            name: String,
            result: R_Type,
            fixedParams: List<R_Type>,
            varargParam: R_Type,
            allowZero: Boolean,
            rFn: R_SysFunction,
            deprecated: C_Deprecated? = null
    ): BuilderT {
        val fixedMatchers = fixedParams.map { C_ArgTypeMatcher_Simple(it) }
        val varargMatcher = C_ArgTypeMatcher_Simple(varargParam)
        addVarArg(name, result, fixedMatchers, varargMatcher, allowZero, rFn, deprecated = deprecated)
        return self()
    }

    private fun addVarArg(
            name: String,
            result: R_Type,
            fixedParams: List<C_ArgTypeMatcher>,
            varargParam: C_ArgTypeMatcher,
            allowZero: Boolean,
            rFn: R_SysFunction,
            deprecated: C_Deprecated? = null
    ): BuilderT {
        val argsMatcher = C_ArgsTypesMatcher_VarArg(fixedParams, varargParam, allowZero)
        val cFn = C_SysFunction.direct(rFn)
        addEx(name, result, argsMatcher, cFn, deprecated)
        return self()
    }
}

class C_GlobalFuncBuilder(
    defPath: C_DefinitionPath = C_DefinitionPath.ROOT,
    typeNames: Set<R_Name> = immSetOf()
): C_FuncBuilder<C_GlobalFuncBuilder, C_GlobalFuncCaseCtx, C_GlobalFunction, V_GlobalFunctionCall>(
        defPath
) {
    private val typeNames = typeNames.toImmSet()

    override fun makeBody(result: R_Type, cFn: C_SysFunction): C_FormalParamsFuncBody<C_GlobalFuncCaseCtx, V_GlobalFunctionCall> {
        return C_SysGlobalFormalParamsFuncBody(result, cFn)
    }

    override fun makeFunc(simpleName: R_Name, fullName: String, cases: List<C_FuncCase<C_GlobalFuncCaseCtx, V_GlobalFunctionCall>>): C_GlobalFunction {
        val ideInfo = if (simpleName in typeNames) IdeSymbolInfo.DEF_TYPE else IdeSymbolInfo.DEF_FUNCTION_SYSTEM
        return C_RegularSysGlobalFunction(simpleName, fullName, cases, ideInfo)
    }

    fun build(): C_GlobalFuncTable {
        val fnMap = buildMap()
        return C_GlobalFuncTable(fnMap)
    }
}

class C_MemberFuncBuilder(
        defName: C_DefinitionName,
): C_FuncBuilder<C_MemberFuncBuilder, C_MemberFuncCaseCtx, C_SysMemberFunction, V_MemberFunctionCall>(
        defName.toPath(),
) {
    override fun makeBody(result: R_Type, cFn: C_SysFunction): C_FormalParamsFuncBody<C_MemberFuncCaseCtx, V_MemberFunctionCall> {
        return C_SysMemberFormalParamsFuncBody(result, cFn)
    }

    override fun makeFunc(
        simpleName: R_Name,
        fullName: String,
        cases: List<C_FuncCase<C_MemberFuncCaseCtx, V_MemberFunctionCall>>,
    ): C_SysMemberFunction {
        return C_CasesSysMemberFunction(cases)
    }

    fun build(): C_MemberFuncTable {
        val fnMap = buildMap()
        return C_MemberFuncTable(fnMap)
    }
}
