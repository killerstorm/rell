/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.*
import net.postchain.rell.model.*
import net.postchain.rell.utils.RecursionAwareCalculator
import net.postchain.rell.utils.RecursionAwareResult

sealed class C_GlobalFunction {
    abstract fun getAbstractInfo(): Pair<R_Definition?, C_AbstractDescriptor?>
    abstract fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr
}

sealed class C_RegularGlobalFunction: C_GlobalFunction() {
    abstract fun compileCallRegular(ctx: C_ExprContext, name: S_Name, args: List<C_Value>): C_Expr

    override final fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr {
        val cArgs = C_FunctionUtils.compileRegularArgs(ctx, args)
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

    override fun getAbstractInfo() = Pair(rFunction, abstract)

    override fun compileCallRegular(ctx: C_ExprContext, name: S_Name, args: List<C_Value>): C_Expr {
        val header = headerLate.get()
        return C_FunctionUtils.compileRegularCall(ctx, name, args, rFunction, header)
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
        val cArgs = C_FunctionUtils.compileRegularArgs(ctx, args)
        return C_FunctionUtils.compileRegularCall(ctx, name, cArgs, rQuery, header)
    }
}

object C_FunctionUtils {
    fun compileFunctionHeader(
            ctx: C_MountContext,
            simpleName: S_Name,
            defNames: R_DefinitionNames,
            params: List<S_AttrHeader>,
            retType: S_Type?,
            body: S_FunctionBody?
    ): C_UserFunctionHeader {
        val explicitRetType = if (retType == null) null else (retType.compileOpt(ctx.nsCtx) ?: R_CtErrorType)
        val bodyRetType = if (body == null) R_UnitType else null
        val retType = explicitRetType ?: bodyRetType

        val cParams = C_FormalParameters.create(ctx.nsCtx, params, false)

        val cBody = if (body == null) null else {
            val bodyCtx = C_FunctionBodyContext(ctx, simpleName.pos, defNames, retType, cParams)
            C_UserFunctionBody(bodyCtx, body)
        }

        return C_UserFunctionHeader(retType, cParams, cBody)
    }

    fun compileQueryHeader(
            ctx: C_MountContext,
            simpleName: S_Name,
            defNames: R_DefinitionNames,
            params: List<S_AttrHeader>,
            retType: S_Type?,
            body: S_FunctionBody
    ): C_QueryFunctionHeader {
        val retType = if (retType == null) null else (retType.compileOpt(ctx.nsCtx) ?: R_CtErrorType)
        val cParams = C_FormalParameters.create(ctx.nsCtx, params, ctx.globalCtx.compilerOptions.gtv)
        val bodyCtx = C_FunctionBodyContext(ctx, simpleName.pos, defNames, retType, cParams)
        val cBody = C_QueryFunctionBody(bodyCtx, body)
        return C_QueryFunctionHeader(retType, cParams, cBody)
    }

    fun compileRegularArgs(ctx: C_ExprContext, args: List<S_NameExprPair>): List<C_Value> {
        val namedArg = args.map { it.name }.filterNotNull().firstOrNull()
        if (namedArg != null) {
            val argName = namedArg.str
            throw C_Error(namedArg.pos, "expr_call_namedarg:$argName", "Named function arguments not supported")
        }

        val cArgs = args.map {
            val sArg = it.expr
            val cArg = sArg.compile(ctx).value()
            val type = cArg.type()
            C_Utils.checkUnitType(sArg.startPos, type, "expr_arg_unit", "Argument expression returns nothing")
            cArg
        }

        return cArgs
    }

    fun compileRegularCall(
            ctx: C_ExprContext,
            name: S_Name,
            args: List<C_Value>,
            rFunction: R_Routine,
            header: C_FunctionHeader
    ): C_Expr {
        val effArgs = checkArgs(ctx, name, header.params, args)
        val retType = compileReturnType(ctx, name, header)

        val rExpr = if (effArgs != null && retType != null) {
            compileRegularCall0(name, effArgs, retType, rFunction)
        } else {
            C_Utils.crashExpr(retType ?: R_CtErrorType, "Compilation error")
        }

        val exprFacts = C_ExprVarFacts.forSubExpressions(args)
        return C_RValue.makeExpr(name.pos, rExpr, exprFacts)
    }

    private fun checkArgs(ctx: C_ExprContext, name: S_Name, params: C_FormalParameters, args: List<C_Value>): List<C_Value>? {
        val nameStr = name.str
        var err = false

        val paramsSize = params.list.size
        if (args.size != paramsSize) {
            ctx.msgCtx.error(name.pos, "expr_call_argcnt:$nameStr:$paramsSize:${args.size}",
                    "Wrong number of arguments for '$nameStr': ${args.size} instead of $paramsSize")
            err = true
        }

        val matchList = mutableListOf<C_ArgTypeMatch>()
        val n = Math.min(args.size, paramsSize)

        for ((i, param) in params.list.subList(0, n).withIndex()) {
            val arg = args[i]
            val paramType = param.type
            val argType = arg.type()

            var m: C_ArgTypeMatch? = null

            if (paramType != null) {
                val matcher = C_ArgTypeMatcher_Simple(paramType)
                m = matcher.match(argType)
                if (m == null) {
                    val paramCode = param.nameCode(i)
                    val paramMsg = param.nameMsg(i)
                    val code = "expr_call_argtype:$nameStr:$paramCode:${paramType.toStrictString()}:${argType.toStrictString()}"
                    val msg = "Wrong argument type for parameter $paramMsg: ${argType} instead of ${paramType}"
                    ctx.msgCtx.error(name.pos, code, msg)
                    err = true
                }
            }

            matchList.add(m ?: C_ArgTypeMatch_Direct)
        }

        if (err) {
            return null
        }

        val match = C_ArgTypesMatch(matchList)
        return match.effectiveArgs(args)
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
    override fun getAbstractInfo() = Pair(null, null)

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
        throw C_FuncMatchUtils.errNoMatch(name.pos, name.str, argTypes)
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
        throw C_FuncMatchUtils.errNoMatch(pos, fullName, argTypes)
    }
}

sealed class C_FunctionBody(bodyCtx: C_FunctionBodyContext) {
    private val retTypeCalculator = bodyCtx.mntCtx.appCtx.functionReturnTypeCalculator

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
        s.bodyCtx.mntCtx.executor.checkPass(C_CompilerPass.EXPRESSIONS)

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
        s.bodyCtx.mntCtx.executor.checkPass(C_CompilerPass.EXPRESSIONS)

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
