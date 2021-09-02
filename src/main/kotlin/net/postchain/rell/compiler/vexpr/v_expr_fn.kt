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
        private val match: C_BasicGlobalFuncCaseMatch
): V_Expr(exprCtx, caseCtx.linkPos) {
    override fun exprInfo0() = V_ExprInfo.simple(
            match.resType,
            match.args,
            canBeDbExpr = match.canBeDb
    )

    override fun globalConstantRestriction() = match.globalConstantRestriction(caseCtx)

    override fun toRExpr0() = match.compileCallR(caseCtx)
    override fun toDbExpr0() = match.compileCallDb(caseCtx)
}

class V_SysSpecialGlobalCaseCallExpr(
        exprCtx: C_ExprContext,
        private val caseCtx: C_GlobalFuncCaseCtx,
        private val match: C_SpecialGlobalFuncCaseMatch
): V_Expr(exprCtx, caseCtx.linkPos) {
    override fun exprInfo0() = V_ExprInfo.simple(
            match.resType,
            match.subExprs(),
            canBeDbExpr = false
    )

    override fun varFacts0() = match.varFacts()

    override fun globalConstantRestriction() = match.globalConstantRestriction(caseCtx)

    override fun toRExpr0() = match.compileCallR(exprCtx, caseCtx)
}

// Just a wrapper which adds implicit at-expr what attribute name.
class V_SysMemberPropertyExpr(
        exprCtx: C_ExprContext,
        private val expr: V_Expr,
        private val propName: String
): V_Expr(exprCtx, expr.pos) {
    override fun exprInfo0() = V_ExprInfo.simple(expr.type, expr)

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

    companion object {
        val EMPTY = V_FunctionCallArgs(immListOf(), immListOf())

        fun positional(args: List<V_Expr>): V_FunctionCallArgs {
            return V_FunctionCallArgs(args, args.indices.toImmList())
        }
    }
}

sealed class V_FunctionCallExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        protected val resType: R_Type,
        protected val target: V_FunctionCallTarget,
        args: List<V_Expr>
): V_Expr(exprCtx, pos) {
    private val args = args.toImmList()
    private val allExprs = (target.vExprs() + args).toImmList()

    final override fun exprInfo0() = V_ExprInfo.simple(
            resType,
            allExprs,
            canBeDbExpr = target.canBeDb()
    )

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
            override fun evaluate(frame: Rt_CallFrame, values: List<Rt_AtWhatItem>): Rt_Value {
                val targetValues = values.subList(0, targetExprs.size).map { it.value() }
                val rtTarget = rTarget.evaluateTarget(frame, targetValues)
                rtTarget ?: return Rt_NullValue
                val argValues = values.subList(targetExprs.size, values.size).map { it.value() }
                return callTarget(frame, rtTarget, argValues)
            }
        }

        return C_DbAtWhatValue_Complex(allExprs, evaluator)
    }
}

class V_FullFunctionCallExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        callPos: S_Pos,
        resType: R_Type,
        target: V_FunctionCallTarget,
        private val callArgs: V_FunctionCallArgs
): V_FunctionCallExpr(exprCtx, pos, resType, target, callArgs.exprs) {
    private val callFilePos = callPos.toFilePos()

    override fun globalConstantRestriction() = target.globalConstantRestriction()

    override fun callTarget(frame: Rt_CallFrame, rtTarget: Rt_FunctionCallTarget, argValues: List<Rt_Value>): Rt_Value {
        val values2 = callArgs.mapping.map { argValues[it] }
        return rtTarget.call(frame, values2, callFilePos)
    }

    override fun toRExpr0(rTarget: R_FunctionCallTarget, rTargetExprs: List<R_Expr>, rArgExprs: List<R_Expr>): R_Expr {
        return R_FullFunctionCallExpr(resType, rTarget, callFilePos, rTargetExprs, rArgExprs, callArgs.mapping)
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
        private val mapping: R_PartialCallMapping
): V_FunctionCallExpr(exprCtx, pos, resType, target, args) {
    override fun globalConstantRestriction() = V_GlobalConstantRestriction("partial_call", null)

    override fun toRExpr0(rTarget: R_FunctionCallTarget, rTargetExprs: List<R_Expr>, rArgExprs: List<R_Expr>): R_Expr {
        return R_PartialFunctionCallExpr(resType, rTarget, mapping, rTargetExprs, rArgExprs)
    }

    override fun callTarget(frame: Rt_CallFrame, rtTarget: Rt_FunctionCallTarget, argValues: List<Rt_Value>): Rt_Value {
        return rtTarget.createFunctionValue(resType, mapping, argValues)
    }
}

sealed class V_FunctionCallTarget {
    abstract fun vExprs(): List<V_Expr>
    abstract fun toRTarget(): R_FunctionCallTarget

    open fun toDbExpr(pos: S_Pos, dbArgs: List<Db_Expr>): Db_Expr = throw C_Errors.errExprDbNotAllowed(pos)

    open fun canBeDb() = false
    open fun globalConstantRestriction(): V_GlobalConstantRestriction? = null
}

class V_FunctionCallTarget_UserFunction(
        private val fn: R_RoutineDefinition
): V_FunctionCallTarget() {
    override fun vExprs() = immListOf<V_Expr>()
    override fun toRTarget(): R_FunctionCallTarget = R_FunctionCallTarget_UserFunction(fn)
    override fun globalConstantRestriction() = V_GlobalConstantRestriction("fn:${fn.appLevelName}", "user function call")
}

class V_FunctionCallTarget_Operation(
        private val op: R_OperationDefinition
): V_FunctionCallTarget() {
    override fun vExprs() = immListOf<V_Expr>()
    override fun toRTarget(): R_FunctionCallTarget = R_FunctionCallTarget_Operation(op)
    override fun globalConstantRestriction() = V_GlobalConstantRestriction("op:${op.appLevelName}", "operation call")
}

class V_FunctionCallTarget_FunctionValue(
        private val fnExpr: V_Expr,
        private val safe: Boolean
): V_FunctionCallTarget() {
    override fun vExprs() = immListOf(fnExpr)

    override fun toRTarget(): R_FunctionCallTarget {
        return R_FunctionCallTarget_FunctionValue(safe)
    }

    override fun globalConstantRestriction() = V_GlobalConstantRestriction("fn_value_call", "user function call")
}

class V_SysFunctionTargetDescriptor(
        val resType: R_Type,
        val rFn: R_SysFunction,
        val dbFn: Db_SysFunction?,
        val fullName: String,
        val pure: Boolean,
        val synth: Boolean = false
)

abstract class V_FunctionCallTarget_SysFunction(
        protected val desc: V_SysFunctionTargetDescriptor
): V_FunctionCallTarget() {
    final override fun canBeDb() = desc.dbFn != null

    final override fun globalConstantRestriction(): V_GlobalConstantRestriction? {
        return if (desc.pure) null else {
            val name = desc.fullName
            val code = if (desc.synth) "fn:prop:$name" else "fn:sys:$name"
            val msg = if (desc.synth) null else "function '$name'"
            V_GlobalConstantRestriction(code, msg)
        }
    }
}

class V_FunctionCallTarget_SysGlobalFunction(
        desc: V_SysFunctionTargetDescriptor
): V_FunctionCallTarget_SysFunction(desc) {
    override fun vExprs() = immListOf<V_Expr>()
    override fun toRTarget(): R_FunctionCallTarget = R_FunctionCallTarget_SysGlobalFunction(desc.rFn, desc.fullName)

    override fun toDbExpr(pos: S_Pos, dbArgs: List<Db_Expr>): Db_Expr {
        if (desc.dbFn == null) {
            throw C_Errors.errFunctionNoSql(pos, desc.fullName)
        }
        return Db_CallExpr(desc.resType, desc.dbFn, dbArgs)
    }
}

class V_FunctionCallTarget_SysMemberFunction(
        desc: V_SysFunctionTargetDescriptor,
        private val member: C_MemberLink
): V_FunctionCallTarget_SysFunction(desc) {
    override fun vExprs() = immListOf(member.base)

    override fun toRTarget(): R_FunctionCallTarget {
        return R_FunctionCallTarget_SysMemberFunction(member.safe, desc.rFn, desc.fullName)
    }

    override fun toDbExpr(pos: S_Pos, dbArgs: List<Db_Expr>): Db_Expr {
        if (desc.dbFn == null) {
            throw C_Errors.errFunctionNoSql(pos, desc.fullName)
        }

        val dbBase = member.base.toDbExpr()
        val dbFullArgs = listOf(dbBase) + dbArgs
        return Db_CallExpr(desc.resType, desc.dbFn, dbFullArgs)
    }
}
