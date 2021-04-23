/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.*
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_RExpr
import net.postchain.rell.compiler.vexpr.V_SysGlobalCaseCallExpr
import net.postchain.rell.compiler.vexpr.V_SysMemberCaseCallExpr
import net.postchain.rell.lib.R_TestOpType
import net.postchain.rell.model.*
import net.postchain.rell.utils.RecursionAwareCalculator
import net.postchain.rell.utils.RecursionAwareResult
import net.postchain.rell.utils.toImmList

class C_FunctionArgs(val valid: Boolean, positional: List<V_Expr>, named: List<Pair<S_Name, V_Expr>>) {
    val positional = positional.toImmList()
    val named = named.toImmList()
}

sealed class C_GlobalFunction {
    open fun getFunctionDefinition(): R_FunctionDefinition? = null
    open fun getAbstractDescriptor(): C_AbstractDescriptor? = null

    abstract fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr
}

sealed class C_RegularGlobalFunction: C_GlobalFunction() {
    abstract fun getHintParams(): C_FormalParameters
    protected abstract fun compileCallRegular(ctx: C_ExprContext, name: S_Name, args: C_FunctionArgs): C_Expr

    final override fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr {
        val hintParams = getHintParams()
        val cArgs = C_FunctionUtils.compileRegularArgs(ctx, args, hintParams)
        return compileCallRegular(ctx, name, cArgs)
    }
}

class C_StructGlobalFunction(private val struct: R_Struct): C_GlobalFunction() {
    override fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr {
        return compileCall(ctx, struct, name.pos, args)
    }

    companion object {
        fun compileCall(ctx: C_ExprContext, struct: R_Struct, fnPos: S_Pos, args: List<S_NameExprPair>): C_Expr {
            val createCtx = C_CreateContext(ctx, struct.initFrameGetter, fnPos.toFilePos())
            val cArgs = C_Argument.compile(ctx, struct.attributes, args)
            val attrs = C_AttributeResolver.resolveCreate(createCtx, struct.attributes, cArgs, fnPos)
            val rExpr = R_StructExpr(struct, attrs.rAttrs)
            return V_RExpr.makeExpr(ctx, fnPos, rExpr, attrs.exprFacts)
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
        val rFunction: R_FunctionDefinition,
        private val abstract: C_AbstractDescriptor?
): C_RegularGlobalFunction() {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, C_UserFunctionHeader.ERROR)

    fun setHeader(header: C_UserFunctionHeader) {
        headerLate.set(header)
    }

    override fun getFunctionDefinition() = rFunction
    override fun getAbstractDescriptor() = abstract
    override fun getHintParams() = headerLate.get().params

    override fun compileCallRegular(ctx: C_ExprContext, name: S_Name, args: C_FunctionArgs): C_Expr {
        val header = headerLate.get()
        return C_FunctionUtils.compileRegularCall(ctx, name, args, rFunction, header)
    }
}

class C_OperationGlobalFunction(val rOp: R_OperationDefinition): C_GlobalFunction() {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, C_OperationFunctionHeader.ERROR)

    fun setHeader(header: C_OperationFunctionHeader) {
        headerLate.set(header)
    }

    override fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr {
        val header = headerLate.get()

        val cArgs = C_FunctionUtils.compileRegularArgs(ctx, args, header.params)
        val cEffArgs = C_FunctionUtils.checkArgs(ctx, name, header.params, cArgs)

        if (cEffArgs == null) {
            return C_Utils.errorExpr(ctx, name.pos, R_TestOpType)
        }

        if (!ctx.defCtx.modCtx.isTestLib()) {
            ctx.msgCtx.error(name.pos, "expr:operation_call:no_test:$name",
                    "Operation calls are allowed only in tests or REPL")
        }

        val rArgs = cEffArgs.map { it.toRExpr() }
        val vExpr = V_RExpr(ctx, name.pos, R_OperationExpr(rOp, rArgs))
        return C_VExpr(vExpr)
    }
}

class C_QueryGlobalFunction(val rQuery: R_QueryDefinition): C_GlobalFunction() {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, C_QueryFunctionHeader.ERROR)

    fun setHeader(header: C_QueryFunctionHeader) {
        headerLate.set(header)
    }

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
        val positional = mutableListOf<V_Expr>()
        val namedNames = mutableSetOf<String>()
        val named = mutableListOf<Pair<S_Name, V_Expr>>()
        var valid = true
        var errPositionalAfterNamed = false

        for ((i, arg) in args.withIndex()) {
            val sExpr = arg.expr
            val typeHint = paramsHints.getTypeHint(i, arg.name?.str)
            val vExpr = sExpr.compile(ctx, typeHint).value()

            val type = vExpr.type()
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
                    positional.add(vExpr)
                }
            } else {
                val name = arg.name
                if (!namedNames.add(name.str)) {
                    ctx.msgCtx.error(name.pos, "expr:call:named_arg_dup:$name", "Named argument '$name' specified more than once")
                    valid = false
                } else {
                    named.add(Pair(name, vExpr))
                }
            }
        }

        return C_FunctionArgs(valid, positional, named)
    }

    fun compileRegularCall(
            ctx: C_ExprContext,
            name: S_Name,
            args: C_FunctionArgs,
            rFunction: R_RoutineDefinition,
            header: C_FunctionHeader
    ): C_Expr {
        val effArgs = checkArgs(ctx, name, header.params, args)
        val retType = compileReturnType(ctx, name, header)

        val vExpr = if (effArgs != null && retType != null) {
            compileRegularCall0(ctx, name, effArgs, retType, rFunction)
        } else {
            C_Utils.errorVExpr(ctx, name.pos, retType ?: R_CtErrorType, "Compilation error")
        }

        return C_VExpr(vExpr)
    }

    fun checkArgs(ctx: C_ExprContext, name: S_Name, params: C_FormalParameters, args: C_FunctionArgs): List<V_Expr>? {
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

        val match = C_ArgsTypesMatch(matchListNz)
        return match.effectiveArgs(ctx, posArgsNz)
    }

    private fun matchArgs(
            ctx: C_ExprContext,
            fnName: S_Name,
            params: C_FormalParameters,
            args: C_FunctionArgs
    ): Pair<Boolean, List<V_Expr?>> {
        val res = mutableListOf<V_Expr?>()
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
                res[i] = param.createDefaultValueExpr(ctx, fnName.pos)
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

    private fun matchArgTypes(ctx: C_ExprContext, fnName: S_Name, params: C_FormalParameters, args: List<V_Expr?>): List<C_ArgTypeMatch?> {
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
                if (m == null && argType.isNotError()) {
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

    private fun compileRegularCall0(
            ctx: C_ExprContext,
            name: S_Name,
            effArgs: List<V_Expr>,
            retType: R_Type,
            rFunction: R_RoutineDefinition
    ): V_Expr {
        val filePos = name.pos.toFilePos()
        val rArgs = effArgs.map { it.toRExpr() }
        val rExpr = R_UserCallExpr(retType, rFunction, rArgs, filePos)
        val exprFacts = C_ExprVarFacts.forSubExpressions(effArgs)
        return V_RExpr(ctx, name.pos, rExpr, exprFacts)
    }
}

abstract class C_FormalParamsFuncBody<CtxT: C_FuncCaseCtx>(val resType: R_Type) {
    abstract fun varFacts(caseCtx: CtxT, args: List<V_Expr>): C_ExprVarFacts

    abstract fun compileCall(ctx: C_ExprContext, caseCtx: CtxT, args: List<R_Expr>): R_Expr

    open fun compileCallDb(ctx: C_ExprContext, caseCtx: CtxT, args: List<Db_Expr>): Db_Expr {
        throw C_Errors.errFunctionNoSql(caseCtx.linkPos, caseCtx.qualifiedNameMsg())
    }
}

typealias C_GlobalFormalParamsFuncBody = C_FormalParamsFuncBody<C_GlobalFuncCaseCtx>
typealias C_MemberFormalParamsFuncBody = C_FormalParamsFuncBody<C_MemberFuncCaseCtx>

class C_SysGlobalFormalParamsFuncBody(
        resType: R_Type,
        private val rFn: R_SysFunction,
        private val dbFn: Db_SysFunction? = null
): C_GlobalFormalParamsFuncBody(resType) {
    override fun varFacts(caseCtx: C_GlobalFuncCaseCtx, args: List<V_Expr>) = C_ExprVarFacts.forSubExpressions(args)

    override fun compileCall(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx, args: List<R_Expr>): R_Expr {
        return C_Utils.createSysCallExpr(resType, rFn, args, caseCtx)
    }

    override fun compileCallDb(ctx: C_ExprContext, caseCtx: C_GlobalFuncCaseCtx, args: List<Db_Expr>): Db_Expr {
        if (dbFn == null) {
            throw C_Errors.errFunctionNoSql(caseCtx.linkPos, caseCtx.qualifiedNameMsg())
        }
        return Db_CallExpr(resType, dbFn, args)
    }
}

class C_SysMemberFormalParamsFuncBody(
        resType: R_Type,
        private val rFn: R_SysFunction,
        private val dbFn: Db_SysFunction? = null
): C_MemberFormalParamsFuncBody(resType) {
    override fun varFacts(caseCtx: C_MemberFuncCaseCtx, args: List<V_Expr>): C_ExprVarFacts {
        return C_ExprVarFacts.forSubExpressions(listOf(caseCtx.member.base) + args)
    }

    override fun compileCall(ctx: C_ExprContext, caseCtx: C_MemberFuncCaseCtx, args: List<R_Expr>): R_Expr {
        val member = caseCtx.member
        val rBase = member.base.toRExpr()
        val fnName = caseCtx.qualifiedNameMsg()
        val calculator = R_MemberCalculator_SysFn(resType, rFn, args, fnName)
        return R_MemberExpr(rBase, member.safe, calculator)
    }

    override fun compileCallDb(ctx: C_ExprContext, caseCtx: C_MemberFuncCaseCtx, args: List<Db_Expr>): Db_Expr {
        if (dbFn == null) {
            return super.compileCallDb(ctx, caseCtx, args)
        }
        val member = caseCtx.member
        val dbBase = member.base.toDbExpr()
        val dbFullArgs = listOf(dbBase) + args
        return Db_CallExpr(resType, dbFn, dbFullArgs)
    }
}

class C_RegularSysGlobalFunction(private val cases: List<C_GlobalFuncCase>): C_RegularGlobalFunction() {
    override fun getHintParams() = C_FormalParameters.EMPTY

    override fun compileCallRegular(ctx: C_ExprContext, name: S_Name, args: C_FunctionArgs): C_Expr {
        if (!args.named.isEmpty()) {
            val arg = args.named[0]
            C_Errors.errSysFunctionNamedArg(ctx.msgCtx, name.str, arg.first)
            return C_Utils.errorExpr(ctx, name.pos)
        }

        if (!args.valid) {
            return C_Utils.errorExpr(ctx, name.pos)
        }

        val posArgs = args.positional

        val match = matchCase(ctx, name, posArgs)
        match ?: return C_Utils.errorExpr(ctx, name.pos)

        val caseCtx = C_GlobalFuncCaseCtx(name)
        val vExpr = V_SysGlobalCaseCallExpr(ctx, caseCtx, match, posArgs)
        return C_VExpr(vExpr)
    }

    private fun matchCase(ctx: C_ExprContext, name: S_Name, args: List<V_Expr>): C_GlobalFuncCaseMatch? {
        for (case in cases) {
            val res = case.match(ctx, args)
            if (res != null) {
                return res
            }
        }

        val argTypes = args.map { it.type() }
        C_FuncMatchUtils.errNoMatch(ctx, name.pos, name.str, argTypes)
        return null
    }
}

abstract class C_SpecialSysGlobalFunction: C_GlobalFunction() {
    protected open fun paramCount(): Int? = null
    protected abstract fun compileCall0(ctx: C_ExprContext, name: S_Name, args: List<S_Expr>): C_Expr

    final override fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr {
        val argExprs = args.map { it.expr }

        val argNames = args.mapNotNull { it.name }.firstOrNull()
        if (argNames != null) {
            C_Errors.errSysFunctionNamedArg(ctx.msgCtx, name.str, argNames)
            argExprs.forEach { it.compileSafe(ctx) }
            return C_Utils.errorExpr(ctx, name.pos)
        }

        val paramCount = paramCount()
        val argCount = argExprs.size
        if (argCount != paramCount) {
            ctx.msgCtx.error(name.pos, "fn:sys:wrong_arg_count:$paramCount:$argCount",
                    "Wrong number of arguments for function '${name.str}': $argCount instead of $paramCount")
            argExprs.forEach { it.compileSafe(ctx) }
            return C_Utils.errorExpr(ctx, name.pos, R_BooleanType)
        }

        return compileCall0(ctx, name, argExprs)
    }
}

sealed class C_SysMemberFunction {
    abstract fun getParamsHints(): C_FunctionParametersHints
    abstract fun compileCall(ctx: C_ExprContext, callCtx: C_MemberFuncCaseCtx, args: List<V_Expr>): C_Expr
}

class C_CasesSysMemberFunction(private val cases: List<C_MemberFuncCase>): C_SysMemberFunction() {
    override fun getParamsHints(): C_FunctionParametersHints = C_SysMemberFnParamHints()

    override fun compileCall(ctx: C_ExprContext, callCtx: C_MemberFuncCaseCtx, args: List<V_Expr>): C_Expr {
        val match = matchCase(ctx, callCtx, args)
        match ?: return C_Utils.errorExpr(ctx, callCtx.member.base.pos)
        val vExpr = V_SysMemberCaseCallExpr(ctx, callCtx, match, args)
        return C_VExpr(vExpr)
    }

    private fun matchCase(
            ctx: C_ExprContext,
            callCtx: C_MemberFuncCaseCtx,
            args: List<V_Expr>
    ): C_MemberFuncCaseMatch? {
        for (case in cases) {
            val res = case.match(ctx, args)
            if (res != null) {
                return res
            }
        }

        val qName = callCtx.qualifiedNameMsg()
        val argTypes = args.map { it.type() }
        C_FuncMatchUtils.errNoMatch(ctx, callCtx.member.linkPos, qName, argTypes)
        return null
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

abstract class C_SpecialSysMemberFunction: C_SysMemberFunction() {
    override fun getParamsHints(): C_FunctionParametersHints = C_FormalParameters(listOf())
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
