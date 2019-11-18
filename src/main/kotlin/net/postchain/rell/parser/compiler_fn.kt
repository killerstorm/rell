package net.postchain.rell.parser

import net.postchain.rell.model.*


abstract class C_GlobalFunction {
    abstract fun getAbstractInfo(): Pair<R_Definition?, C_AbstractDescriptor?>
    abstract fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr
}

abstract class C_RegularGlobalFunction: C_GlobalFunction() {
    abstract fun compileCallRegular(ctx: C_ExprContext, name: S_Name, args: List<C_Value>): C_Expr

    override final fun compileCall(ctx: C_ExprContext, name: S_Name, args: List<S_NameExprPair>): C_Expr {
        val cArgs = compileArgs(ctx, args)
        return compileCallRegular(ctx, name, cArgs)
    }

    companion object {
        fun compileArgs(ctx: C_ExprContext, args: List<S_NameExprPair>): List<C_Value> {
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

class C_UserFunctionHeader(val retType: R_Type, val cParams: List<C_ExternalParam>) {
    companion object {
        val EMPTY = C_UserFunctionHeader(R_UnitType, listOf())
    }
}

class C_UserGlobalFunction(val rFunction: R_Function, private val abstract: C_AbstractDescriptor?): C_RegularGlobalFunction() {
    private val headerLate = C_LateInit(C_CompilerPass.MEMBERS, C_UserFunctionHeader.EMPTY)

    fun setHeader(retType: R_Type, cParams: List<C_ExternalParam>) {
        val header = C_UserFunctionHeader(retType, cParams)
        headerLate.set(header)
        abstract?.setHeader(header)
    }

    override fun getAbstractInfo() = Pair(rFunction, abstract)

    override fun compileCallRegular(ctx: C_ExprContext, name: S_Name, args: List<C_Value>): C_Expr {
        val header = headerLate.get()
        val effArgs = checkArgs(ctx, name, header.cParams, args)

        val rExpr = if (effArgs != null) {
            val rArgs = effArgs.map { it.toRExpr() }
            R_UserCallExpr(header.retType, rFunction, rArgs)
        } else {
            C_Utils.crashExpr(header.retType, "Compilation error")
        }

        val exprFacts = C_ExprVarFacts.forSubExpressions(args)
        return C_RValue.makeExpr(name.pos, rExpr, exprFacts)
    }

    private fun checkArgs(ctx: C_ExprContext, name: S_Name, params: List<C_ExternalParam>, args: List<C_Value>): List<C_Value>? {
        val nameStr = name.str
        var err = false

        if (args.size != params.size) {
            ctx.globalCtx.error(name.pos, "expr_call_argcnt:$nameStr:${params.size}:${args.size}",
                    "Wrong number of arguments for '$nameStr': ${args.size} instead of ${params.size}")
            err = true
        }

        val matchList = mutableListOf<C_ArgTypeMatch>()
        val n = Math.min(args.size, params.size)

        for ((i, param) in params.subList(0, n).withIndex()) {
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
                    ctx.globalCtx.error(name.pos, code, msg)
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

        return C_DbValue(member.name.pos, dbExpr, exprFacts)
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
