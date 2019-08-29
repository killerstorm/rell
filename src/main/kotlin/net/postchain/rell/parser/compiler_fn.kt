package net.postchain.rell.parser

import net.postchain.rell.model.*
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap

sealed class C_ArgTypeMatcher {
    abstract fun match(type: R_Type): C_ArgTypeMatch?
}

object C_ArgTypeMatcher_Any: C_ArgTypeMatcher() {
    override fun match(type: R_Type) = C_ArgTypeMatch_Direct
}

class C_ArgTypeMatcher_Simple(val targetType: R_Type): C_ArgTypeMatcher() {
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

class C_ArgTypeMatcher_CollectionSub(val elementType: R_Type): C_ArgTypeMatcher() {
    override fun match(type: R_Type): C_ArgTypeMatch? {
        val direct = type is R_CollectionType && elementType.isAssignableFrom(type.elementType)
        return if (direct) C_ArgTypeMatch_Direct else null
    }
}

class C_ArgTypeMatcher_MapSub(val keyType: R_Type, val valueType: R_Type): C_ArgTypeMatcher() {
    override fun match(type: R_Type): C_ArgTypeMatch? {
        val direct = type is R_MapType
                && keyType.isAssignableFrom(type.keyType)
                && valueType.isAssignableFrom(type.valueType)
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
    abstract val fullName: S_Name
}

class C_GlobalFuncCaseCtx(name: S_Name): C_FuncCaseCtx() {
    override val fullName = name
}

class C_MemberFuncCaseCtx(val member: C_MemberRef): C_FuncCaseCtx() {
    override val fullName = S_Name(member.name.pos, member.qualifiedName())
}

abstract class C_FuncCase<CtxT: C_FuncCaseCtx> {
    abstract fun match(args: List<C_Value>): C_FuncCaseMatch<CtxT>?
}

typealias C_GlobalFuncCase = C_FuncCase<C_GlobalFuncCaseCtx>
typealias C_GlobalFuncCaseMatch = C_FuncCaseMatch<C_GlobalFuncCaseCtx>

abstract class C_SysFuncCase<CtxT: C_FuncCaseCtx>: C_FuncCase<CtxT>()
typealias C_GlobalSysFuncCase = C_SysFuncCase<C_GlobalFuncCaseCtx>
typealias C_MemberSysFuncCase = C_SysFuncCase<C_MemberFuncCaseCtx>

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
            C_Def.deprecatedMessage(ctx.blkCtx.entCtx.nsCtx, C_DefType.FUNCTION, name.pos, name.str, deprecated)
        }
    }
}

abstract class C_BasicGlobalFuncCaseMatch(private val args: List<C_Value>): C_GlobalFuncCaseMatch() {
    abstract fun compileCallExpr(name: S_Name, args: List<R_Expr>): R_Expr

    open fun compileCallDbExpr(name: S_Name, args: List<Db_Expr>): Db_Expr {
        throw C_Errors.errFunctionNoSql(name.pos, name.str)
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
                rFactory: (S_Name, List<R_Expr>) -> R_Expr
        ): C_Value {
            val name = caseCtx.fullName
            val rArgs = args.map { it.toRExpr() }
            val rExpr = rFactory(name, rArgs)
            val facts = C_ExprVarFacts.forSubExpressions(args)
            return C_RValue(name.pos, rExpr, facts)
        }

        fun compileCallDb(
                caseCtx: C_GlobalFuncCaseCtx,
                args: List<C_Value>,
                dbFactory: (S_Name, List<Db_Expr>) -> Db_Expr
        ): C_Value {
            val name = caseCtx.fullName
            val dbArgs = args.map { it.toDbExpr() }
            val dbExpr = dbFactory(name, dbArgs)
            val facts = C_ExprVarFacts.forSubExpressions(args)
            return C_DbValue(name.pos, dbExpr, facts)
        }
    }
}

class C_FormalParamsFuncCase<CtxT: C_FuncCaseCtx>(
        private val params: List<C_ArgTypeMatcher>,
        private val body: C_FormalParamsFuncBody<CtxT>
): C_FuncCase<CtxT>() {
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

abstract class C_FormalParamsFuncBody<CtxT: C_FuncCaseCtx> {
    abstract fun compileCall(ctx: C_ExprContext, caseCtx: CtxT, args: List<C_Value>): C_Value

    open fun compileCallDb(ctx: C_ExprContext, caseCtx: CtxT, args: List<C_Value>): C_Value {
        val name = caseCtx.fullName
        throw C_Errors.errFunctionNoSql(name.pos, name.str)
    }
}

typealias C_GlobalFormalParamsFuncBody = C_FormalParamsFuncBody<C_GlobalFuncCaseCtx>
typealias C_MemberFormalParamsFuncBody = C_FormalParamsFuncBody<C_MemberFuncCaseCtx>

class C_SysGlobalFormalParamsFuncBody(
        private val type: R_Type,
        private val rFn: R_SysFunction,
        private val dbFn: Db_SysFunction? = null
): C_GlobalFormalParamsFuncBody() {
    override fun compileCall(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx, args: List<C_Value>): C_Value {
        return C_BasicGlobalFuncCaseMatch.compileCall(caseCtx, args) { _, rArgs ->
            R_SysCallExpr(type, rFn, rArgs)
        }
    }

    override fun compileCallDb(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx, args: List<C_Value>): C_Value {
        val name = caseCtx.fullName
        if (dbFn == null) {
            throw C_Errors.errFunctionNoSql(name.pos, name.str)
        }

        return C_BasicGlobalFuncCaseMatch.compileCallDb(caseCtx, args) { name, dbArgs ->
            Db_CallExpr(type, dbFn, dbArgs)
        }
    }
}

typealias C_MemberFuncCase = C_FuncCase<C_MemberFuncCaseCtx>
typealias C_MemberFuncCaseMatch = C_FuncCaseMatch<C_MemberFuncCaseCtx>

class C_SysMemberFormalParamsFuncBody(
        private val type: R_Type,
        private val rFn: R_SysFunction,
        private val dbFn: Db_SysFunction? = null
): C_MemberFormalParamsFuncBody() {
    override fun compileCall(ctx: C_ExprContext, caseCtx: C_MemberFuncCaseCtx, args: List<C_Value>): C_Value {
        val member = caseCtx.member
        val rBase = member.base.toRExpr()
        val rArgs = args.map { it.toRExpr() }
        val calculator = R_MemberCalculator_SysFn(type, rFn, rArgs)
        val rExpr = R_MemberExpr(rBase, member.safe, calculator)

        val subValues = listOf(member.base) + args
        val exprFacts = C_ExprVarFacts.forSubExpressions(subValues)

        return C_RValue(member.name.pos, rExpr, exprFacts)
    }

    override fun compileCallDb(ctx: C_ExprContext, caseCtx: C_MemberFuncCaseCtx, args: List<C_Value>): C_Value {
        if (dbFn == null) {
            return super.compileCallDb(ctx, caseCtx, args)
        }

        val member = caseCtx.member
        val dbBase = member.base.toDbExpr()
        val dbArgs = args.map { it.toDbExpr() }
        val dbFullArgs = listOf(dbBase) + dbArgs
        val dbExpr = Db_CallExpr(type, dbFn, dbFullArgs)

        val subValues = listOf(member.base) + args
        val exprFacts = C_ExprVarFacts.forSubExpressions(subValues)

        return C_DbValue(member.name.pos, dbExpr, exprFacts)
    }
}

object C_FuncUtils {
    fun checkArgs(name: S_Name, params: List<R_ExternalParam>, args: List<C_Value>): List<C_Value> {
        val nameStr = name.str

        if (args.size != params.size) {
            throw C_Error(name.pos, "expr_call_argcnt:$nameStr:${params.size}:${args.size}",
                    "Wrong number of arguments for '$nameStr': ${args.size} instead of ${params.size}")
        }

        val matchList = mutableListOf<C_ArgTypeMatch>()

        for ((i, param) in params.withIndex()) {
            val arg = args[i]
            val paramType = param.type
            val argType = arg.type()
            val m = C_ArgTypeMatcher_Simple(paramType).match(argType)
            if (m == null) {
                throw C_Error(name.pos, "expr_call_argtype:$nameStr:$i:${paramType.toStrictString()}:${argType.toStrictString()}",
                        "Wrong argument type for '$nameStr' #${i + 1}: ${argType.toStrictString()} instead of ${paramType.toStrictString()}")
            }
            matchList.add(m)
        }

        val match = C_ArgTypesMatch(matchList)
        return match.effectiveArgs(args)
    }

    fun errNoMatch(pos: S_Pos, name: String, args: List<R_Type>): C_Error {
        val argsStrShort = args.joinToString(",") { it.toStrictString() }
        val argsStr = args.joinToString { it.toStrictString() }
        return C_Error(pos, "expr_call_argtypes:$name:$argsStrShort", "Function $name undefined for arguments ($argsStr)")
    }
}

class C_SysGlobalFunction(private val cases: List<C_GlobalFuncCase>): C_RegularGlobalFunction() {
    override fun compileCallRegular(ctx: C_ExprContext, name: S_Name, args: List<C_Value>): C_Expr {
        val match = matchCase(name, args)
        val caseCtx = C_GlobalFuncCaseCtx(name)

        val db = args.any { it.isDb() }
        val value = if (db) {
            match.compileCallDb(ctx, caseCtx)
        } else {
            match.compileCall(ctx, caseCtx)
        }

        return C_ValueExpr(value)
    }

    private fun matchCase(name: S_Name, args: List<C_Value>): C_GlobalFuncCaseMatch {
        for (case in cases) {
            val res = case.match(args)
            if (res != null) {
                return res
            }
        }

        val argTypes = args.map { it.type() }
        throw C_FuncUtils.errNoMatch(name.pos, name.str, argTypes)
    }
}

class C_SysMemberFunction(val cases: List<C_MemberFuncCase>) {
    fun compileCall(ctx: C_ExprContext, member: C_MemberRef, args: List<C_Value>): C_Expr {
        val qName = member.qualifiedName()
        val match = matchCase(member.name.pos, qName, args)
        val caseCtx = C_MemberFuncCaseCtx(member)

        val db = member.base.isDb() || args.any { it.isDb() }
        val value = if (db) {
            match.compileCallDb(ctx, caseCtx)
        } else {
            match.compileCall(ctx, caseCtx)
        }

        return C_ValueExpr(value)
    }

    private fun matchCase(pos: S_Pos, fullName: String, args: List<C_Value>): C_MemberFuncCaseMatch {
        for (case in cases) {
            val res = case.match(args)
            if (res != null) {
                return res
            }
        }

        val argTypes = args.map { it.type() }
        throw C_FuncUtils.errNoMatch(pos, fullName, argTypes)
    }
}

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
    private val map = ArrayListValuedHashMap<String, C_FuncCase<CaseCtxT>>()

    protected abstract fun makeBody(result: R_Type, rFn: R_SysFunction, dbFn: Db_SysFunction?): C_FormalParamsFuncBody<CaseCtxT>
    protected abstract fun makeFunc(cases: List<C_FuncCase<CaseCtxT>>): FuncT

    protected fun addCase(name: String, case: C_FuncCase<CaseCtxT>, deprecated: C_Deprecated?) {
        val case2 = if (deprecated == null) case else makeDeprecatedCase(case, deprecated)
        map.put(name, case2)
    }

    protected fun buildMap(): Map<String, FuncT> {
        val fnMap = mutableMapOf<String, FuncT>()
        for (name in map.keySet().sorted()) {
            val cases = map[name]
            fnMap[name] = makeFunc(cases)
        }
        return fnMap.toMap()
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
        return C_SysMemberFunction(cases)
    }

    fun build(): C_MemberFuncTable {
        val fnMap = buildMap()
        return C_MemberFuncTable(fnMap)
    }
}
