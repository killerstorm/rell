package net.postchain.rell.parser

import net.postchain.rell.model.*
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap

abstract class C_SysMemberFunction {
    abstract fun compileCall(ctx: C_ExprContext, name: S_Name, base: C_Value, safe: Boolean, args: List<C_Value>): C_Expr
}

sealed class C_ArgTypeMatcher {
    abstract fun match(type: R_Type): Boolean
}

object C_ArgTypeMatcher_Any: C_ArgTypeMatcher() {
    override fun match(type: R_Type) = true
}

object C_ArgTypeMatcher_Nullable: C_ArgTypeMatcher() {
    override fun match(type: R_Type) = type is R_NullableType
}

class C_ArgTypeMatcher_Simple(val targetType: R_Type): C_ArgTypeMatcher() {
    override fun match(type: R_Type) = targetType.isAssignableFrom(type)
}

class C_ArgTypeMatcher_CollectionSub(val elementType: R_Type): C_ArgTypeMatcher() {
    override fun match(type: R_Type) = type is R_CollectionType && elementType.isAssignableFrom(type.elementType)
}

class C_ArgTypeMatcher_MapSub(val keyType: R_Type, val valueType: R_Type): C_ArgTypeMatcher() {
    override fun match(type: R_Type) =
            type is R_MapType
                    && keyType.isAssignableFrom(type.keyType)
                    && valueType.isAssignableFrom(type.valueType)
}

abstract class C_GlobalFuncCase {
    abstract fun match(args: List<C_Value>): C_GlobalFuncCaseMatch?
}

abstract class C_GlobalFuncCaseMatch {
    abstract fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<C_Value>): C_Value

    open fun compileCallDb(ctx: C_ExprContext, name: S_Name, args: List<C_Value>): C_Value {
        throw C_Errors.errFunctionNoSql(name.pos, name.str)
    }
}

abstract class C_SimpleGlobalFuncCase: C_GlobalFuncCase() {
    abstract fun matchTypes(args: List<R_Type>): C_GlobalFuncCaseMatch?

    override fun match(args: List<C_Value>): C_GlobalFuncCaseMatch? {
        val argTypes = args.map { it.type() }
        return matchTypes(argTypes)
    }
}

class C_DeprecatedGlobalFuncCase(
        private val case: C_GlobalFuncCase,
        private val deprecated: C_Deprecated
): C_GlobalFuncCase() {
    override fun match(args: List<C_Value>): C_GlobalFuncCaseMatch? {
        val match = case.match(args)
        return if (match == null) match else C_DeprecatedGlobalFuncCaseMatch(match, deprecated)
    }

    private class C_DeprecatedGlobalFuncCaseMatch(
            private val match: C_GlobalFuncCaseMatch,
            private val deprecated: C_Deprecated
    ): C_GlobalFuncCaseMatch() {
        override fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<C_Value>): C_Value {
            C_Def.deprecatedMessage(ctx.blkCtx.entCtx.nsCtx, C_DefType.FUNCTION, name.pos, name.str, deprecated)
            return match.compileCall(ctx, name, args)
        }

        override fun compileCallDb(ctx: C_ExprContext, name: S_Name, args: List<C_Value>): C_Value {
            C_Def.deprecatedMessage(ctx.blkCtx.entCtx.nsCtx, C_DefType.FUNCTION, name.pos, name.str, deprecated)
            return match.compileCallDb(ctx, name, args)
        }
    }
}

abstract class C_SimpleGlobalFuncCaseMatch: C_GlobalFuncCaseMatch() {
    abstract fun compileCallExpr(name: S_Name, args: List<R_Expr>): R_Expr

    open fun compileCallDbExpr(name: S_Name, args: List<Db_Expr>): Db_Expr {
        throw C_Errors.errFunctionNoSql(name.pos, name.str)
    }

    override fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<C_Value>): C_Value {
        val rArgs = args.map { it.toRExpr() }
        val rExpr = compileCallExpr(name, rArgs)
        val facts = C_ExprVarFacts.forSubExpressions(args)
        return C_RValue(name.pos, rExpr, facts)
    }

    override fun compileCallDb(ctx: C_ExprContext, name: S_Name, args: List<C_Value>): C_Value {
        val dbArgs = args.map { it.toDbExpr() }
        val dbExpr = compileCallDbExpr(name, dbArgs)
        val facts = C_ExprVarFacts.forSubExpressions(args)
        return C_DbValue(name.pos, dbExpr, facts)
    }
}

class C_StdGlobalFuncCase(val params: List<C_ArgTypeMatcher>, val match: C_GlobalFuncCaseMatch): C_SimpleGlobalFuncCase() {
    override fun matchTypes(args: List<R_Type>): C_GlobalFuncCaseMatch? {
        if (!C_FuncUtils.matchArgs(params, args)) return null
        return match
    }
}

class C_StdGlobalFuncCaseMatch(val type: R_Type, val rFn: R_SysFunction, val dbFn: Db_SysFunction? = null
): C_SimpleGlobalFuncCaseMatch() {
    override fun compileCallExpr(name: S_Name, args: List<R_Expr>): R_Expr {
        return R_SysCallExpr(type, rFn, args)
    }

    override fun compileCallDbExpr(name: S_Name, args: List<Db_Expr>): Db_Expr {
        if (dbFn == null) throw C_Errors.errFunctionNoSql(name.pos, name.str)
        return Db_CallExpr(type, dbFn, args)
    }
}

abstract class C_MemberFuncCase {
    abstract fun match(args: List<R_Type>): C_MemberFuncCaseMatch?
}

abstract class C_MemberFuncCaseMatch {
    abstract fun compileCall(ctx: C_ExprContext, pos: S_Pos, name: String, args: List<R_Expr>): R_MemberCalculator

    open fun compileCallDb(ctx: C_ExprContext, pos: S_Pos, name: String, base: Db_Expr, args: List<Db_Expr>): Db_Expr {
        throw C_Errors.errFunctionNoSql(pos, name)
    }
}

class C_DeprecatedMemberFuncCase(
        private val case: C_MemberFuncCase,
        private val deprecated: C_Deprecated
): C_MemberFuncCase() {
    override fun match(args: List<R_Type>): C_MemberFuncCaseMatch? {
        val match = case.match(args)
        return if (match == null) match else C_DeprecatedMemberFuncCaseMatch(match, deprecated)
    }

    private class C_DeprecatedMemberFuncCaseMatch(
            private val match: C_MemberFuncCaseMatch,
            private val deprecated: C_Deprecated
    ): C_MemberFuncCaseMatch() {
        override fun compileCall(ctx: C_ExprContext, pos: S_Pos, name: String, args: List<R_Expr>): R_MemberCalculator {
            C_Def.deprecatedMessage(ctx.blkCtx.entCtx.nsCtx, C_DefType.FUNCTION, pos, name, deprecated)
            return match.compileCall(ctx, pos, name, args)
        }

        override fun compileCallDb(ctx: C_ExprContext, pos: S_Pos, name: String, base: Db_Expr, args: List<Db_Expr>): Db_Expr {
            C_Def.deprecatedMessage(ctx.blkCtx.entCtx.nsCtx, C_DefType.FUNCTION, pos, name, deprecated)
            return match.compileCallDb(ctx, pos, name, base, args)
        }
    }
}

class C_StdMemberFuncCase(val params: List<C_ArgTypeMatcher>, val match: C_MemberFuncCaseMatch): C_MemberFuncCase() {
    override fun match(args: List<R_Type>): C_MemberFuncCaseMatch? {
        if (!C_FuncUtils.matchArgs(params, args)) return null
        return match
    }
}

class C_StdMemberFuncCaseMatch(val type: R_Type, val rFn: R_SysFunction, val dbFn: Db_SysFunction? = null): C_MemberFuncCaseMatch() {
    override fun compileCall(ctx: C_ExprContext, pos: S_Pos, name: String, args: List<R_Expr>): R_MemberCalculator {
        return R_MemberCalculator_SysFn(type, rFn, args)
    }

    override fun compileCallDb(ctx: C_ExprContext, pos: S_Pos, name: String, base: Db_Expr, args: List<Db_Expr>): Db_Expr {
        if (dbFn == null) throw C_Errors.errFunctionNoSql(pos, name)
        val fullArgs = listOf(base) + args
        return Db_CallExpr(type, dbFn, fullArgs)
    }
}

object C_FuncUtils {
    fun matchArgs(params: List<C_ArgTypeMatcher>, args: List<R_Type>): Boolean {
        if (args.size != params.size) {
            return false
        }

        for ((i, arg) in args.withIndex()) {
            if (!params[i].match(arg)) {
                return false
            }
        }

        return true
    }

    fun checkArgs(name: S_Name, params: List<R_Type>, args: List<R_Expr>) {
        val argTypes = args.map { it.type }
        checkArgs0(name, params, argTypes)
    }

    private fun checkArgs0(name: S_Name, params: List<R_Type>, args: List<R_Type>) {
        val nameStr = name.str

        if (args.size != params.size) {
            throw C_Error(name.pos, "expr_call_argcnt:$nameStr:${params.size}:${args.size}",
                    "Wrong number of arguments for '$nameStr': ${args.size} instead of ${params.size}")
        }

        for ((i, param) in params.withIndex()) {
            val arg = args[i]
            if (!param.isAssignableFrom(arg)) {
                throw C_Error(name.pos, "expr_call_argtype:$nameStr:$i:${param.toStrictString()}:${arg.toStrictString()}",
                        "Wrong argument type for '$nameStr' #${i + 1}: ${arg.toStrictString()} instead of ${param.toStrictString()}")
            }
        }
    }

    fun errNoMatch(pos: S_Pos, name: String, args: List<R_Type>): C_Error {
        val argsStrShort = args.joinToString(",") { it.toStrictString() }
        val argsStr = args.joinToString { it.toStrictString() }
        return C_Error(pos, "expr_call_argtypes:$name:$argsStrShort", "Function $name undefined for arguments ($argsStr)")
    }
}

class C_SysGlobalFunction(private val cases: List<C_GlobalFuncCase>): C_GlobalFunction() {
    override fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<C_Value>): C_Expr {
        val match = matchCase(name, args)
        val db = args.any { it.isDb() }
        val value = if (db) {
            match.compileCallDb(ctx, name, args)
        } else {
            match.compileCall(ctx, name, args)
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

class C_StdSysMemberFunction(val cases: List<C_MemberFuncCase>): C_SysMemberFunction() {
    override fun compileCall(ctx: C_ExprContext, name: S_Name, base: C_Value, safe: Boolean, args: List<C_Value>): C_Expr {
        val fullName = "${base.type().toStrictString()}.${name.str}"
        val match = matchCase(name.pos, fullName, args)

        val db = base.isDb() || args.any { it.isDb() }
        if (db) {
            val dbBase = base.toDbExpr()
            val dbArgs = args.map { it.toDbExpr() }
            val dbExpr = match.compileCallDb(ctx, name.pos, fullName, dbBase, dbArgs)
            return C_DbValue.makeExpr(name.pos, dbExpr)
        }

        val rBase = base.toRExpr()
        val rArgs = args.map { it.toRExpr() }
        val calculator = match.compileCall(ctx, name.pos, fullName, rArgs)
        val rExpr = R_MemberExpr(rBase, safe, calculator)

        val subValues = listOf(base) + args
        val exprFacts = C_ExprVarFacts.forSubExpressions(subValues)

        return C_RValue.makeExpr(name.pos, rExpr, exprFacts)
    }

    private fun matchCase(pos: S_Pos, fullName: String, args: List<C_Value>): C_MemberFuncCaseMatch {
        val argTypes = args.map { it.type() }

        for (case in cases) {
            val res = case.match(argTypes)
            if (res != null) {
                return res
            }
        }

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

sealed class C_FuncBuilder<BuilderT, CaseT, MatchT, FuncT> {
    private val map = ArrayListValuedHashMap<String, CaseT>()

    protected abstract fun makeMatch(result: R_Type, rFn: R_SysFunction, dbFn: Db_SysFunction?): MatchT
    protected abstract fun makeCase(params: List<C_ArgTypeMatcher>, match: MatchT): CaseT
    protected abstract fun makeDeprecatedCase(case: CaseT, deprecated: C_Deprecated): CaseT
    protected abstract fun makeFunc(cases: List<CaseT>): FuncT

    protected fun addCase(name: String, case: CaseT, deprecated: C_Deprecated?) {
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
        val match = makeMatch(result, rFn, dbFn)
        val case = makeCase(params, match)
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

    fun add(name: String, params: List<R_Type>, match: MatchT, deprecated: C_Deprecated? = null): BuilderT {
        val matchers = params.map { C_ArgTypeMatcher_Simple(it) }
        val case = makeCase(matchers, match)
        addCase(name, case, deprecated)
        return this as BuilderT
    }

    fun add(name: String, case: CaseT, deprecated: C_Deprecated? = null): BuilderT {
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

class C_GlobalFuncBuilder: C_FuncBuilder<C_GlobalFuncBuilder, C_GlobalFuncCase, C_GlobalFuncCaseMatch, C_GlobalFunction>() {
    override fun makeMatch(result: R_Type, rFn: R_SysFunction, dbFn: Db_SysFunction?): C_GlobalFuncCaseMatch {
        return C_StdGlobalFuncCaseMatch(result, rFn, dbFn)
    }

    override fun makeCase(params: List<C_ArgTypeMatcher>, match: C_GlobalFuncCaseMatch): C_GlobalFuncCase {
        return C_StdGlobalFuncCase(params, match)
    }

    override fun makeDeprecatedCase(case: C_GlobalFuncCase, deprecated: C_Deprecated): C_GlobalFuncCase {
        return C_DeprecatedGlobalFuncCase(case, deprecated)
    }

    override fun makeFunc(cases: List<C_GlobalFuncCase>): C_GlobalFunction {
        return C_SysGlobalFunction(cases)
    }

    fun build(): C_GlobalFuncTable {
        val fnMap = buildMap()
        return C_GlobalFuncTable(fnMap)
    }
}

class C_MemberFuncBuilder: C_FuncBuilder<C_MemberFuncBuilder, C_MemberFuncCase, C_MemberFuncCaseMatch, C_SysMemberFunction>() {
    override fun makeMatch(result: R_Type, rFn: R_SysFunction, dbFn: Db_SysFunction?): C_MemberFuncCaseMatch {
        return C_StdMemberFuncCaseMatch(result, rFn, dbFn)
    }

    override fun makeCase(params: List<C_ArgTypeMatcher>, match: C_MemberFuncCaseMatch): C_MemberFuncCase {
        return C_StdMemberFuncCase(params, match)
    }

    override fun makeDeprecatedCase(case: C_MemberFuncCase, deprecated: C_Deprecated): C_MemberFuncCase {
        return C_DeprecatedMemberFuncCase(case, deprecated)
    }

    override fun makeFunc(cases: List<C_MemberFuncCase>): C_SysMemberFunction {
        return C_StdSysMemberFunction(cases)
    }

    fun build(): C_MemberFuncTable {
        val fnMap = buildMap()
        return C_MemberFuncTable(fnMap)
    }
}
