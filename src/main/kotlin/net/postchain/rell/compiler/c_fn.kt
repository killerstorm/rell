/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.*
import net.postchain.rell.model.*
import net.postchain.rell.utils.RecursionAwareCalculator
import net.postchain.rell.utils.RecursionAwareResult
import net.postchain.rell.utils.toImmList

class C_FunctionArgs(val valid: Boolean, positional: List<C_Value>, named: List<Pair<S_Name, C_Value>>) {
    val positional = positional.toImmList()
    val named = named.toImmList()
}

sealed class C_GlobalFunction {
    abstract fun getAbstractInfo(): Pair<R_Definition?, C_AbstractDescriptor?>
    abstract fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr
}

sealed class C_RegularGlobalFunction: C_GlobalFunction() {
    abstract fun getHintParams(): C_FormalParameters
    abstract fun compileCallRegular(ctx: C_ExprContext, name: S_Name, args: C_FunctionArgs): C_Expr

    override final fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr {
        val hintParams = getHintParams()
        val cArgs = C_FunctionUtils.compileRegularArgs(ctx, args, hintParams)
        return compileCallRegular(ctx, name, cArgs)
    }
}

class C_StructGlobalFunction(private val struct: R_Struct): C_GlobalFunction() {
    override fun getAbstractInfo() = Pair(struct, null)

    override fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr {
        return compileCall(struct, ctx, name, args)
    }

    companion object {
        fun compileCall(struct: R_Struct, ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr {
            val attrs = C_AttributeResolver.resolveCreate(ctx, struct.attributes, args, name.pos)
            val rExpr = R_StructExpr(struct, attrs.rAttrs)
            return C_RValue.makeExpr(name.pos, rExpr, attrs.exprFacts)
        }
    }
}

sealed class C_FunctionHeader(val explicitType: R_Type?, val params: C_FormalParameters, val body: C_FunctionBody?) {
    abstract val declarationType: C_DeclarationType

    fun returnType(): R_Type {
        return explicitType ?: (body?.returnType()?.value ?: R_CtErrorType)
    }
}

class C_UserFunctionHeader(
        explicitType: R_Type?,
        params: C_FormalParameters,
        val fnBody: C_UserFunctionBody?
): C_FunctionHeader(explicitType, params, fnBody) {
    override val declarationType = C_DeclarationType.FUNCTION

    companion object {
        val ERROR = C_UserFunctionHeader(null, C_FormalParameters.EMPTY, null)
    }
}

class C_OperationFunctionHeader(val params: C_FormalParameters) {
    companion object {
        val ERROR = C_OperationFunctionHeader(C_FormalParameters.EMPTY)
    }
}

class C_QueryFunctionHeader(
        explicitType: R_Type?,
        params: C_FormalParameters,
        val queryBody: C_QueryFunctionBody?
): C_FunctionHeader(explicitType, params, queryBody) {
    override val declarationType = C_DeclarationType.QUERY

    companion object {
        val ERROR = C_QueryFunctionHeader(null, C_FormalParameters.EMPTY, null)
    }
}

class C_UserGlobalFunction(
        val rFunction: R_Function,
        private val abstract: C_AbstractDescriptor?
): C_RegularGlobalFunction() {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, C_UserFunctionHeader.ERROR)

    fun setHeader(header: C_UserFunctionHeader) {
        headerLate.set(header)
    }

    override fun getHintParams() = headerLate.get().params
    override fun getAbstractInfo() = Pair(rFunction, abstract)

    override fun compileCallRegular(ctx: C_ExprContext, name: S_Name, args: C_FunctionArgs): C_Expr {
        val header = headerLate.get()
        return C_FunctionUtils.compileRegularCall(ctx, name, args, rFunction, header)
    }
}

class C_OperationGlobalFunction(val rOp: R_Operation): C_GlobalFunction() {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, C_OperationFunctionHeader.ERROR)

    fun setHeader(header: C_OperationFunctionHeader) {
        headerLate.set(header)
    }

    override fun getAbstractInfo() = Pair(null, null)

    override fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr {
        val header = headerLate.get()

        val cArgs = C_FunctionUtils.compileRegularArgs(ctx, args, header.params)
        val cEffArgs = C_FunctionUtils.checkArgs(ctx, name, header.params, cArgs)

        val rExpr = if (cEffArgs == null) {
            C_Utils.errorRExpr(R_OperationType)
        } else {
            val rArgs = cEffArgs.map { it.toRExpr() }
            R_OperationExpr(rOp, rArgs)
        }

        return C_RValue.makeExpr(name.pos, rExpr)
    }
}

class C_QueryGlobalFunction(val rQuery: R_Query): C_GlobalFunction() {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, C_QueryFunctionHeader.ERROR)

    fun setHeader(header: C_QueryFunctionHeader) {
        headerLate.set(header)
    }

    override fun getAbstractInfo() = Pair(null, null)

    override fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr {
        val header = headerLate.get()
        val cArgs = C_FunctionUtils.compileRegularArgs(ctx, args, header.params)
        return C_FunctionUtils.compileRegularCall(ctx, name, cArgs, rQuery, header)
    }
}

interface C_FunctionParametersHints {
    fun getTypeHint(index: Int, name: String?): C_TypeHint
}

object C_FunctionUtils {
    fun compileFunctionHeader(
            defCtx: C_DefinitionContext,
            simpleName: S_Name,
            defNames: R_DefinitionNames,
            params: List<S_FormalParameter>,
            retType: S_Type?,
            body: S_FunctionBody?
    ): C_UserFunctionHeader {
        val explicitRetType = if (retType == null) null else (retType.compileOpt(defCtx.nsCtx) ?: R_CtErrorType)
        val bodyRetType = if (body == null) R_UnitType else null
        val retType = explicitRetType ?: bodyRetType

        val cParams = C_FormalParameters.compile(defCtx, params, false)

        val cBody = if (body == null) null else {
            val bodyCtx = C_FunctionBodyContext(defCtx, simpleName.pos, defNames, retType, cParams)
            C_UserFunctionBody(bodyCtx, body)
        }

        return C_UserFunctionHeader(retType, cParams, cBody)
    }

    fun compileQueryHeader(
            defCtx: C_DefinitionContext,
            simpleName: S_Name,
            defNames: R_DefinitionNames,
            params: List<S_FormalParameter>,
            retType: S_Type?,
            body: S_FunctionBody
    ): C_QueryFunctionHeader {
        val retType = if (retType == null) null else (retType.compileOpt(defCtx.nsCtx) ?: R_CtErrorType)
        val cParams = C_FormalParameters.compile(defCtx, params, defCtx.globalCtx.compilerOptions.gtv)
        val bodyCtx = C_FunctionBodyContext(defCtx, simpleName.pos, defNames, retType, cParams)
        val cBody = C_QueryFunctionBody(bodyCtx, body)
        return C_QueryFunctionHeader(retType, cParams, cBody)
    }

    fun compileRegularArgs(ctx: C_ExprContext, args: List<S_NameExprPair>, paramsHints: C_FunctionParametersHints): C_FunctionArgs {
        val positional = mutableListOf<C_Value>()
        val namedNames = mutableSetOf<String>()
        val named = mutableListOf<Pair<S_Name, C_Value>>()
        var valid = true
        var errPositionalAfterNamed = false

        for ((i, arg) in args.withIndex()) {
            val sExpr = arg.expr
            val typeHint = paramsHints.getTypeHint(i, arg.name?.str)
            val cValue = sExpr.compile(ctx, typeHint).value()

            val type = cValue.type()
            if (!C_Utils.checkUnitType(ctx.msgCtx, sExpr.startPos, type, "expr_arg_unit", "Argument expression returns nothing")) {
                valid = false
            }

            if (arg.name == null) {
                if (!named.isEmpty()) {
                    if (!errPositionalAfterNamed) {
                        ctx.msgCtx.error(sExpr.startPos, "expr:call:positional_after_named", "Unnamed argument after a named argument")
                        errPositionalAfterNamed = true
                    }
                    valid = false
                } else {
                    positional.add(cValue)
                }
            } else {
                val name = arg.name
                if (!namedNames.add(name.str)) {
                    ctx.msgCtx.error(name.pos, "expr:call:named_arg_dup:$name", "Named argument '$name' specified more than once")
                    valid = false
                } else {
                    named.add(Pair(name, cValue))
                }
            }
        }

        return C_FunctionArgs(valid, positional, named)
    }

    fun compileRegularCall(
            ctx: C_ExprContext,
            name: S_Name,
            args: C_FunctionArgs,
            rFunction: R_Routine,
            header: C_FunctionHeader
    ): C_Expr {
        val effArgs = checkArgs(ctx, name, header.params, args)
        val retType = compileReturnType(ctx, name, header)

        val rExpr = if (effArgs != null && retType != null) {
            compileRegularCall0(name, effArgs, retType, rFunction)
        } else {
            C_Utils.errorRExpr(retType ?: R_CtErrorType, "Compilation error")
        }

        val argsValues = args.positional + args.named.map { it.second }
        val exprFacts = C_ExprVarFacts.forSubExpressions(argsValues)

        return C_RValue.makeExpr(name.pos, rExpr, exprFacts)
    }

    fun checkArgs(ctx: C_ExprContext, name: S_Name, params: C_FormalParameters, args: C_FunctionArgs): List<C_Value>? {
        if (!args.valid) {
            return null
        }

        val (err, posArgs) = matchArgs(ctx, name, params, args)

        val matchList = matchArgTypes(ctx, name, params, posArgs)
        val posArgsNz = posArgs.filterNotNull()
        val matchListNz = matchList.filterNotNull()
        if (err || matchListNz.size != params.list.size || posArgsNz.size != params.list.size) {
            return null
        }

        val match = C_ArgTypesMatch(matchListNz)
        return match.effectiveArgs(posArgsNz)
    }

    private fun matchArgs(
            ctx: C_ExprContext,
            fnName: S_Name,
            params: C_FormalParameters,
            args: C_FunctionArgs
    ): Pair<Boolean, List<C_Value?>> {
        val res = mutableListOf<C_Value?>()
        res.addAll(args.positional)
        while (res.size < params.list.size) res.add(null)

        var err = false

        if (args.positional.size > params.list.size) {
            val expCount = params.list.size
            val actCount = args.positional.size + args.named.size
            ctx.msgCtx.error(fnName.pos, "expr:call:arg_count:$fnName:$expCount:$actCount",
                    "Wrong number of arguments for function '$fnName': $actCount instead of $expCount")
            err = true
        }

        for ((name, value) in args.named) {
            val i = params.list.indexOfFirst { it.name.str == name.str }
            if (i < 0) {
                ctx.msgCtx.error(name.pos, "expr:call:unknown_named_arg:$fnName:$name", "Function '$fnName' has no parameter '$name'")
                err = true
            } else if (res[i] != null) {
                ctx.msgCtx.error(name.pos, "expr:call:named_arg_already_specified:$fnName:$name",
                        "Parameter '$name' specified more than once (as named and unnamed)")
                err = true
            } else {
                res[i] = value
            }
        }

        for ((i, param) in params.list.withIndex()) {
            if (res[i] == null && param.hasExpr) {
                res[i] = param.createDefaultValueExpr()
            }
        }

        val missing = params.list.withIndex().filter { (i, param) -> res[i] == null }.map { it.value.name }
        if (!missing.isEmpty()) {
            val codeStr = missing.joinToString(",")
            val msgStr = missing.joinToString(", ")
            ctx.msgCtx.error(fnName.pos, "expr:call:missing_args:$fnName:$codeStr", "Missing argument(s): $msgStr")
            err = true
        }

        return Pair(err, res)
    }

    private fun matchArgTypes(ctx: C_ExprContext, fnName: S_Name, params: C_FormalParameters, args: List<C_Value?>): List<C_ArgTypeMatch?> {
        val matchList = mutableListOf<C_ArgTypeMatch?>()
        val n = Math.min(args.size, params.list.size)

        for ((i, param) in params.list.subList(0, n).withIndex()) {
            val arg = args[i]
            val paramType = param.type

            var m: C_ArgTypeMatch?

            if (arg == null) {
                m = null
            } else if (paramType == null) {
                m = C_ArgTypeMatch_Direct
            } else {
                val argType = arg.type()
                val matcher = C_ArgTypeMatcher_Simple(paramType)
                m = matcher.match(argType)
                if (m == null) {
                    val paramCode = param.nameCode(i)
                    val paramMsg = param.nameMsg(i)
                    val code = "expr_call_argtype:$fnName:$paramCode:${paramType.toStrictString()}:${argType.toStrictString()}"
                    val msg = "Wrong argument type for parameter $paramMsg: ${argType} instead of ${paramType}"
                    ctx.msgCtx.error(fnName.pos, code, msg)
                }
            }

            matchList.add(m)
        }

        return matchList
    }

    private fun compileReturnType(ctx: C_ExprContext, name: S_Name, header: C_FunctionHeader): R_Type? {
        if (header.explicitType != null) {
            return header.explicitType
        } else if (header.body == null) {
            return null
        }

        val decType = header.declarationType
        val retTypeRes = header.body.returnType()

        if (retTypeRes.recursion) {
            val nameStr = name.str
            ctx.msgCtx.error(name.pos, "fn_type_recursion:$decType:$nameStr",
                    "${decType.capitalizedMsg} '$nameStr' is recursive, cannot infer the return type; specify return type explicitly")
        } else if (retTypeRes.stackOverflow) {
            val nameStr = name.str
            ctx.msgCtx.error(name.pos, "fn_type_stackoverflow:$decType:$nameStr",
                    "Cannot infer return type for ${decType.msg} '$nameStr': call chain is too long; specify return type explicitly")
        }

        return retTypeRes.value
    }

    private fun compileRegularCall0(name: S_Name, effArgs: List<C_Value>, retType: R_Type, rFunction: R_Routine): R_Expr {
        val file = name.pos.path().str()
        val line = name.pos.line()
        val filePos = R_FilePos(file, line)
        val rArgs = effArgs.map { it.toRExpr() }
        return R_UserCallExpr(retType, rFunction, rArgs, filePos)
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
            C_Utils.createSysCallExpr(type, rFn, rArgs, caseCtx.fullName)
        }
    }

    override fun compileCallDb(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx, args: List<C_Value>): C_Value {
        val name = caseCtx.fullName
        if (dbFn == null) {
            throw C_Errors.errFunctionNoSql(name.pos, name.str)
        }

        return C_BasicGlobalFuncCaseMatch.compileCallDb(caseCtx, args) { _, dbArgs ->
            Db_CallExpr(type, dbFn, dbArgs)
        }
    }
}

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

        return C_DbValue.create(member.name.pos, dbExpr, exprFacts)
    }
}

class C_SysGlobalFunction(private val cases: List<C_GlobalFuncCase>): C_RegularGlobalFunction() {
    override fun getHintParams() = C_FormalParameters.EMPTY
    override fun getAbstractInfo() = Pair(null, null)

    override fun compileCallRegular(ctx: C_ExprContext, name: S_Name, args: C_FunctionArgs): C_Expr {
        if (!args.named.isEmpty()) {
            val arg = args.named[0]
            ctx.msgCtx.error(arg.first.pos, "expr:call:sys_global_named_arg:${arg.first}",
                    "Named arguments not supported for function '$name'")
            return C_Utils.errorExpr(name.pos)
        }

        if (!args.valid) {
            return C_Utils.errorExpr(name.pos)
        }

        val posArgs = args.positional
        val match = matchCase(name, posArgs)
        val caseCtx = C_GlobalFuncCaseCtx(name)

        val db = posArgs.any { it.isDb() }
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
        throw C_FuncMatchUtils.errNoMatch(name.pos, name.str, argTypes)
    }
}

class C_SysMemberFunction(val cases: List<C_MemberFuncCase>) {
    val paramsHints: C_FunctionParametersHints = C_SysMemberFnParamHints()

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
        throw C_FuncMatchUtils.errNoMatch(pos, fullName, argTypes)
    }

    private inner class C_SysMemberFnParamHints: C_FunctionParametersHints {
        override fun getTypeHint(index: Int, name: String?) = C_TypeHint_SysFunc(index)
    }

    private inner class C_TypeHint_SysFunc(private val index: Int): C_TypeHint() {
        override fun getListElementType() = calcHint { it.getListElementType() }
        override fun getSetElementType() = calcHint { it.getSetElementType() }
        override fun getMapKeyValueTypes() = calcHint { it.getMapKeyValueTypes() }

        private fun <T> calcHint(getter: (C_TypeHint) -> T?): T? {
            val set = mutableSetOf<T>()
            for (case in cases) {
                val hint = case.getParamTypeHint(index)
                val value = getter(hint)
                if (value != null) set.add(value)
            }
            return if (set.size != 1) null else set.iterator().next()
        }
    }
}

sealed class C_FunctionBody(bodyCtx: C_FunctionBodyContext) {
    private val retTypeCalculator = bodyCtx.appCtx.functionReturnTypeCalculator

    fun returnType(): RecursionAwareResult<R_Type> {
        val res = retTypeCalculator.calculate(this)
        return res
    }

    abstract fun calculateReturnType(): R_Type

    companion object {
        fun createReturnTypeCalculator(): RecursionAwareCalculator<C_FunctionBody, R_Type> {
            // Experimental threshold with default JRE settings is 500 (depth > 500 ==> StackOverflowError).
            return RecursionAwareCalculator(200, R_CtErrorType) {
                it.calculateReturnType()
            }
        }
    }
}

sealed class C_CommonFunctionBody<T>(bodyCtx: C_FunctionBodyContext, sBody: S_FunctionBody): C_FunctionBody(bodyCtx) {
    private var state: BodyState<T> = BodyState.StartState(bodyCtx, sBody)

    protected abstract fun getErrorBody(): T
    protected abstract fun getReturnType(body: T): R_Type
    protected abstract fun compileBody(ctx: C_FunctionBodyContext, sBody: S_FunctionBody): T

    final override fun calculateReturnType(): R_Type {
        val s = state
        return when (s) {
            is BodyState.StartState -> calculateReturnType0(s)
            is BodyState.EndState -> getReturnType(s.rBody)
        }
    }

    private fun calculateReturnType0(s: BodyState.StartState<T>): R_Type {
        s.bodyCtx.executor.checkPass(C_CompilerPass.EXPRESSIONS)

        if (!s.sBody.returnsValue()) {
            return R_UnitType
        }

        val rBody = doCompile(s)
        val rType = getReturnType(rBody)
        return rType
    }

    fun compile(): T {
        // Needed to put the type calculation result to the cache. If this is not done, in case of a recursion,
        // subsequently getting the return type will calculate it and emit an extra compilation error (recursion).
        returnType()

        val s = state
        val res = when (s) {
            is BodyState.StartState -> doCompile(s)
            is BodyState.EndState -> s.rBody
        }

        return res
    }

    private fun doCompile(s: BodyState.StartState<T>): T {
        check(state === s)
        s.bodyCtx.executor.checkPass(C_CompilerPass.EXPRESSIONS)

        var res = getErrorBody()

        try {
            res = compileBody(s.bodyCtx, s.sBody)
        } finally {
            state = BodyState.EndState(res)
        }

        return res
    }

    private sealed class BodyState<T> {
        class StartState<T>(val bodyCtx: C_FunctionBodyContext, val sBody: S_FunctionBody): BodyState<T>()
        class EndState<T>(val rBody: T): BodyState<T>()
    }
}

class C_UserFunctionBody(bodyCtx: C_FunctionBodyContext, sBody: S_FunctionBody): C_CommonFunctionBody<R_FunctionBody>(bodyCtx, sBody) {
    override fun getErrorBody() = R_FunctionBody.ERROR
    override fun getReturnType(body: R_FunctionBody) = body.type
    override fun compileBody(ctx: C_FunctionBodyContext, sBody: S_FunctionBody) = sBody.compileFunction(ctx)
}

class C_QueryFunctionBody(bodyCtx: C_FunctionBodyContext, sBody: S_FunctionBody): C_CommonFunctionBody<R_QueryBody>(bodyCtx, sBody) {
    override fun getErrorBody() = R_UserQueryBody.ERROR
    override fun getReturnType(body: R_QueryBody) = body.retType
    override fun compileBody(ctx: C_FunctionBodyContext, sBody: S_FunctionBody) = sBody.compileQuery(ctx)
}
