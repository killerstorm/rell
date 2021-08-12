package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_NullValue
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList

class V_SysBasicGlobalCaseCallExpr(
        exprCtx: C_ExprContext,
        private val caseCtx: C_GlobalFuncCaseCtx,
        private val match: C_BasicGlobalFuncCaseMatch,
        args: List<V_Expr>
): V_Expr(exprCtx, caseCtx.linkPos) {
    override val exprInfo = V_ExprInfo.make(args, canBeDbExpr = match.canBeDb)

    private val varFacts = C_ExprVarFacts.forSubExpressions(args)

    override fun type() = match.resType
    override fun varFacts() = varFacts
    override fun toRExpr0() = match.compileCallR(caseCtx)
    override fun toDbExpr0() = match.compileCallDb(caseCtx)
}

class V_SysSpecialGlobalCaseCallExpr(
        exprCtx: C_ExprContext,
        private val caseCtx: C_GlobalFuncCaseCtx,
        private val match: C_SpecialGlobalFuncCaseMatch
): V_Expr(exprCtx, caseCtx.linkPos) {
    override val exprInfo = V_ExprInfo.make(match.subExprs(), canBeDbExpr = false)

    private val varFacts = match.varFacts()

    override fun type() = match.resType
    override fun varFacts() = varFacts
    override fun toRExpr0() = match.compileCallR(exprCtx, caseCtx)
}

class V_SysMemberPropertyExpr(
        exprCtx: C_ExprContext,
        private val expr: V_Expr,
        private val propName: String
): V_Expr(exprCtx, expr.pos) {
    override val exprInfo = V_ExprInfo.make(expr)

    override fun type() = expr.type()
    override fun varFacts() = expr.varFacts()
    override fun toRExpr0() = expr.toRExpr()
    override fun toDbExpr0() = expr.toDbExpr()
    override fun toDbExprWhat0() = expr.toDbExprWhat()

    override fun implicitAtWhatAttrName() = propName // That's the only purpose of this class.
}

class V_FunctionCallArgs(
        exprs: List<V_Expr>,
        mapping: List<Int>
) {
    val exprs = exprs.toImmList()
    val mapping = mapping.toImmList()

    init {
        checkEquals(this.mapping.sorted().toList(), this.exprs.indices.toList())
    }
}

sealed class V_FunctionCallExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        protected val resType: R_Type,
        protected val target: V_FunctionCallTarget,
        args: List<V_Expr>,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    private val args = args.toImmList()
    private val allExprs = (target.vExprs() + args).toImmList()

    final override val exprInfo = V_ExprInfo.make(allExprs, canBeDbExpr = target.canBeDb())

    final override fun type() = resType
    final override fun varFacts() = varFacts

    protected abstract fun toRExpr0(
            rTarget: R_FunctionCallTarget,
            rTargetExprs: List<R_Expr>,
            rArgExprs: List<R_Expr>
    ): R_Expr

    protected abstract fun callTarget(
            frame: Rt_CallFrame,
            rtTarget: Rt_FunctionCallTarget,
            argValues: List<Rt_Value>
    ): Rt_Value

    final override fun toRExpr0(): R_Expr {
        val targetExprs = target.vExprs()
        val rTargetExprs = targetExprs.map { it.toRExpr() }
        val rTarget = target.toRTarget()
        val rArgExprs = args.map { it.toRExpr() }
        return toRExpr0(rTarget, rTargetExprs, rArgExprs)
    }

    final override fun toDbExprWhat0(): C_DbAtWhatValue {
        val targetExprs = target.vExprs()
        val rTarget = target.toRTarget()

        val evaluator = object: Db_ComplexAtWhatEvaluator() {
            override fun evaluate(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
                val targetValues = values.subList(0, targetExprs.size)
                val rtTarget = rTarget.evaluateTarget(frame, targetValues)
                rtTarget ?: return Rt_NullValue
                val argValues = values.subList(targetExprs.size, values.size)
                return callTarget(frame, rtTarget, argValues)
            }
        }

        return V_AtUtils.createAtWhatValueComplex(allExprs, evaluator)
    }
}

class V_FullFunctionCallExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        callPos: S_Pos,
        resType: R_Type,
        target: V_FunctionCallTarget,
        private val callArgs: V_FunctionCallArgs,
        varFacts: C_ExprVarFacts
): V_FunctionCallExpr(exprCtx, pos, resType, target, callArgs.exprs, varFacts) {
    private val callFilePos = callPos.toFilePos()

    override fun toRExpr0(rTarget: R_FunctionCallTarget, rTargetExprs: List<R_Expr>, rArgExprs: List<R_Expr>): R_Expr {
        return R_RegularFunctionCallExpr(resType, rTarget, callFilePos, rTargetExprs, rArgExprs, callArgs.mapping)
    }

    override fun callTarget(frame: Rt_CallFrame, rtTarget: Rt_FunctionCallTarget, argValues: List<Rt_Value>): Rt_Value {
        val values2 = callArgs.mapping.map { argValues[it] }
        return rtTarget.call(frame, values2, callFilePos)
    }

    override fun toDbExpr0(): Db_Expr {
        val dbArgs = callArgs.exprs.map { it.toDbExpr() }
        return target.toDbExpr(pos, dbArgs)
    }
}

class V_PartialFunctionCallExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        resType: R_Type,
        target: V_FunctionCallTarget,
        args: List<V_Expr>,
        private val mapping: R_PartialCallMapping,
        varFacts: C_ExprVarFacts
): V_FunctionCallExpr(exprCtx, pos, resType, target, args, varFacts) {
    override fun toRExpr0(rTarget: R_FunctionCallTarget, rTargetExprs: List<R_Expr>, rArgExprs: List<R_Expr>): R_Expr {
        return R_PartialCallExpr(resType, rTarget, mapping, rTargetExprs, rArgExprs)
    }

    override fun callTarget(frame: Rt_CallFrame, rtTarget: Rt_FunctionCallTarget, argValues: List<Rt_Value>): Rt_Value {
        return rtTarget.createFunctionValue(resType, mapping, argValues)
    }
}

sealed class V_FunctionCallTarget {
    abstract fun vExprs(): List<V_Expr>
    abstract fun toRTarget(): R_FunctionCallTarget

    open fun canBeDb() = false
    open fun toDbExpr(pos: S_Pos, dbArgs: List<Db_Expr>): Db_Expr = throw C_Errors.errExprDbNotAllowed(pos)
}

class V_FunctionCallTarget_UserFunction(
        private val fn: R_RoutineDefinition
): V_FunctionCallTarget() {
    override fun vExprs() = immListOf<V_Expr>()
    override fun toRTarget(): R_FunctionCallTarget = R_FunctionCallTarget_UserFunction(fn)
}

class V_FunctionCallTarget_Operation(
        private val op: R_OperationDefinition
): V_FunctionCallTarget() {
    override fun vExprs() = immListOf<V_Expr>()
    override fun toRTarget(): R_FunctionCallTarget = R_FunctionCallTarget_Operation(op)
}

class V_FunctionCallTarget_FunctionValue(
        private val fnExpr: V_Expr,
        private val safe: Boolean
): V_FunctionCallTarget() {
    override fun vExprs() = immListOf(fnExpr)

    override fun toRTarget(): R_FunctionCallTarget {
        return R_FunctionCallTarget_FunctionValue(safe)
    }
}

class V_FunctionCallTarget_SysGlobalFunction(
        private val resType: R_Type,
        private val fn: R_SysFunction,
        private val dbFn: Db_SysFunction?,
        private val fullName: String
): V_FunctionCallTarget() {
    override fun vExprs() = immListOf<V_Expr>()
    override fun toRTarget(): R_FunctionCallTarget = R_FunctionCallTarget_SysGlobalFunction(fn, fullName)

    override fun canBeDb() = dbFn != null

    override fun toDbExpr(pos: S_Pos, dbArgs: List<Db_Expr>): Db_Expr {
        if (dbFn == null) {
            throw C_Errors.errFunctionNoSql(pos, fullName)
        }
        return Db_CallExpr(resType, dbFn, dbArgs)
    }
}

class V_FunctionCallTarget_SysMemberFunction(
        private val resType: R_Type,
        private val member: C_MemberLink,
        private val fn: R_SysFunction,
        private val dbFn: Db_SysFunction?,
        private val fullName: String
): V_FunctionCallTarget() {
    override fun vExprs() = immListOf(member.base)

    override fun toRTarget(): R_FunctionCallTarget {
        return R_FunctionCallTarget_SysMemberFunction(member.safe, fn, fullName)
    }

    override fun canBeDb() = dbFn != null

    override fun toDbExpr(pos: S_Pos, dbArgs: List<Db_Expr>): Db_Expr {
        if (dbFn == null) {
            throw C_Errors.errFunctionNoSql(pos, fullName)
        }

        val dbBase = member.base.toDbExpr()
        val dbFullArgs = listOf(dbBase) + dbArgs
        return Db_CallExpr(resType, dbFn, dbFullArgs)
    }
}
