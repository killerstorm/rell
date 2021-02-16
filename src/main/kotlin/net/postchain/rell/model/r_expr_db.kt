/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model

import net.postchain.rell.compiler.C_Constants
import net.postchain.rell.compiler.C_EntityAttrRef
import net.postchain.rell.runtime.Rt_BooleanValue
import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_NullValue
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.CommonUtils
import net.postchain.rell.utils.immSetOf
import net.postchain.rell.utils.toImmSet

sealed class Db_BinaryOp(val code: String, val sql: String) {
    open fun evaluate(left: Rt_Value, right: Rt_Value): Rt_Value? = null

    open fun toRedExpr(frame: Rt_CallFrame, type: R_Type, redLeft: RedDb_Expr, right: Db_Expr): RedDb_Expr {
        val redRight = right.toRedExpr(frame)

        val leftValue = redLeft.constantValue()
        val rightValue = if (leftValue == null) null else redRight.constantValue()
        if (leftValue != null && rightValue != null) {
            val resValue = evaluate(leftValue, rightValue)
            if (resValue != null) {
                return RedDb_ConstantExpr(resValue)
            }
        }

        val redExpr = RedDb_BinaryExpr(this, redLeft, redRight)
        return RedDb_Utils.wrapDecimalExpr(type, redExpr)
    }
}

object Db_BinaryOp_Eq: Db_BinaryOp("==", "=") {
    override fun evaluate(left: Rt_Value, right: Rt_Value) = Rt_BooleanValue(left == right)
}

object Db_BinaryOp_Ne: Db_BinaryOp("!=", "<>") {
    override fun evaluate(left: Rt_Value, right: Rt_Value) = Rt_BooleanValue(left != right)
}

object Db_BinaryOp_Lt: Db_BinaryOp("<", "<")
object Db_BinaryOp_Gt: Db_BinaryOp(">", ">")
object Db_BinaryOp_Le: Db_BinaryOp("<=", "<=")
object Db_BinaryOp_Ge: Db_BinaryOp(">=", ">=")
object Db_BinaryOp_Add_Integer: Db_BinaryOp("+", "+")
object Db_BinaryOp_Add_Decimal: Db_BinaryOp("+", "+")
object Db_BinaryOp_Sub_Integer: Db_BinaryOp("-", "-")
object Db_BinaryOp_Sub_Decimal: Db_BinaryOp("-", "-")
object Db_BinaryOp_Mul_Integer: Db_BinaryOp("*", "*")
object Db_BinaryOp_Mul_Decimal: Db_BinaryOp("*", "*")
object Db_BinaryOp_Div_Integer: Db_BinaryOp("/", "/")
object Db_BinaryOp_Div_Decimal: Db_BinaryOp("/", "/")
object Db_BinaryOp_Mod_Integer: Db_BinaryOp("%", "%")
object Db_BinaryOp_Mod_Decimal: Db_BinaryOp("%", "%")
object Db_BinaryOp_Concat: Db_BinaryOp("+", "||")
object Db_BinaryOp_In: Db_BinaryOp("in", "IN")

sealed class Db_BinaryOp_AndOr(code: String, sql: String, private val shortCircuitValue: Boolean): Db_BinaryOp(code, sql) {
    final override fun toRedExpr(frame: Rt_CallFrame, type: R_Type, redLeft: RedDb_Expr, right: Db_Expr): RedDb_Expr {
        val leftValue = redLeft.constantValue()
        if (leftValue != null) {
            val v = leftValue.asBoolean()
            return if (v == shortCircuitValue) RedDb_ConstantExpr(leftValue) else right.toRedExpr(frame)
        }

        val redRight = right.toRedExpr(frame)
        val rightValue = redRight.constantValue()
        if (rightValue != null) {
            val v = rightValue.asBoolean()
            return if (v == shortCircuitValue) RedDb_ConstantExpr(rightValue) else redLeft
        }

        val redExpr = RedDb_BinaryExpr(this, redLeft, redRight)
        return RedDb_Utils.wrapDecimalExpr(type, redExpr)
    }
}

object Db_BinaryOp_And: Db_BinaryOp_AndOr("and", "AND", false)
object Db_BinaryOp_Or: Db_BinaryOp_AndOr("or", "OR", true)

sealed class Db_UnaryOp(val code: String, val sql: String)
object Db_UnaryOp_Minus_Integer: Db_UnaryOp("-", "-")
object Db_UnaryOp_Minus_Decimal: Db_UnaryOp("-", "-")
object Db_UnaryOp_Not: Db_UnaryOp("not", "NOT")

abstract class Db_Expr(val type: R_Type, directSubExprs: List<Db_Expr>, atExprId: R_AtExprId? = null) {
    private val refAtExprIds: Set<R_AtExprId>

    init {
        val subSets = (directSubExprs.map { it.refAtExprIds } + listOf(listOfNotNull(atExprId).toSet()))
                .filter { it.isNotEmpty() }
        refAtExprIds = when {
            subSets.isEmpty() -> immSetOf()
            subSets.size == 1 -> subSets.first()
            else -> subSets.flatMap { it }.toImmSet()
        }
    }

    open fun constantValue(): Rt_Value? = null
    abstract fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr

    // TODO Actually needed only during compilation; better design would be to split Db_Expr into CT-only and post-CT parts
    fun referencedAtExprIds(): Set<R_AtExprId> = refAtExprIds
}

abstract class RedDb_Expr {
    open fun constantValue(): Rt_Value? = null
    abstract fun toSql(ctx: SqlGenContext, bld: SqlBuilder)
}

class Db_InterpretedExpr(val expr: R_Expr): Db_Expr(expr.type, listOf()) {
    override fun constantValue() = expr.constantValue()

    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val value = expr.evaluate(frame)
        return RedDb_ConstantExpr(value)
    }
}

private class RedDb_ConstantExpr(val value: Rt_Value): RedDb_Expr() {
    override fun constantValue() = value

    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
        bld.append(value)
    }
}

class Db_BinaryExpr(type: R_Type, val op: Db_BinaryOp, val left: Db_Expr, val right: Db_Expr)
    : Db_Expr(type, listOf(left, right))
{
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val redLeft = left.toRedExpr(frame)
        return op.toRedExpr(frame, type, redLeft, right)
    }
}

private class RedDb_BinaryExpr(val op: Db_BinaryOp, val left: RedDb_Expr, val right: RedDb_Expr): RedDb_Expr() {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
        bld.append("(")
        left.toSql(ctx, bld)
        bld.append(" ")
        bld.append(op.sql)
        bld.append(" ")
        right.toSql(ctx, bld)
        bld.append(")")
    }
}

class Db_UnaryExpr(type: R_Type, val op: Db_UnaryOp, val expr: Db_Expr): Db_Expr(type, listOf(expr)) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val redExpr = expr.toRedExpr(frame)
        return RedDb_UnaryExpr(op, redExpr)
    }

    private class RedDb_UnaryExpr(val op: Db_UnaryOp, val expr: RedDb_Expr): RedDb_Expr() {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
            bld.append("(")
            bld.append(op.sql)
            bld.append(" ")
            expr.toSql(ctx, bld)
            bld.append(")")
        }
    }
}

class Db_IsNullExpr(val expr: Db_Expr, val isNull: Boolean): Db_Expr(R_BooleanType, listOf(expr)) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val redExpr = expr.toRedExpr(frame)
        return RedDb_IsNullExpr(redExpr, isNull)
    }

    private class RedDb_IsNullExpr(val expr: RedDb_Expr, val isNull: Boolean): RedDb_Expr() {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
            bld.append("(")
            expr.toSql(ctx, bld)
            bld.append(if (isNull) " IS NULL" else " IS NOT NULL")
            bld.append(")")
        }
    }
}

sealed class Db_TableExpr(val rEntity: R_EntityDefinition, directSubExprs: List<Db_Expr>, atExprId: R_AtExprId?)
    : Db_Expr(rEntity.type, directSubExprs, atExprId)
{
    abstract fun alias(ctx: SqlGenContext): SqlTableAlias

    final override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        return RedDb_TableExpr(this)
    }

    private class RedDb_TableExpr(val tableExpr: Db_TableExpr): RedDb_Expr() {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
            val alias = tableExpr.alias(ctx)
            val rowidCol = tableExpr.rEntity.sqlMapping.rowidColumn()
            bld.appendColumn(alias, rowidCol)
        }
    }
}

class Db_EntityExpr(val entity: R_DbAtEntity): Db_TableExpr(entity.rEntity, listOf(), entity.id.exprId) {
    override fun alias(ctx: SqlGenContext) = ctx.getEntityAlias(entity)
}

class Db_RelExpr(val base: Db_TableExpr, val attr: R_Attribute, targetEntity: R_EntityDefinition)
    : Db_TableExpr(targetEntity, listOf(base), null)
{
    override fun alias(ctx: SqlGenContext): SqlTableAlias {
        val baseAlias = base.alias(ctx)
        return ctx.getRelAlias(baseAlias, attr, rEntity)
    }
}

class Db_AttrExpr(val base: Db_TableExpr, val attr: R_Attribute): Db_Expr(attr.type, listOf(base)) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val redExpr = RedDb_AttrExpr(base, attr)
        return RedDb_Utils.wrapDecimalExpr(type, redExpr)
    }

    private class RedDb_AttrExpr(val base: Db_TableExpr, val attr: R_Attribute): RedDb_Expr() {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
            val alias = base.alias(ctx)
            bld.appendColumn(alias, attr.sqlMapping)
        }
    }
}

class Db_RowidExpr(val base: Db_TableExpr): Db_Expr(C_EntityAttrRef.ROWID_TYPE, listOf(base)) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        return RedDb_RowidExpr(base)
    }

    private class RedDb_RowidExpr(val base: Db_TableExpr): RedDb_Expr() {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
            val alias = base.alias(ctx)
            val col = alias.entity.sqlMapping.rowidColumn()
            bld.appendColumn(alias, col)
        }
    }
}

class Db_CollectionInterpretedExpr(val expr: R_Expr): Db_Expr(expr.type, listOf()) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val value = expr.evaluate(frame)
        val collection = value.asCollection()
        return RedDb_CollectionConstantExpr(collection)
    }

    private class RedDb_CollectionConstantExpr(val value: Collection<Rt_Value>): RedDb_Expr() {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
            bld.append("(")
            bld.append(value, ",") {
                bld.append(it)
            }
            bld.append(")")
        }
    }
}

class Db_InExpr(val keyExpr: Db_Expr, val exprs: List<Db_Expr>): Db_Expr(R_BooleanType, listOf(keyExpr) + exprs) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val redKeyExpr = keyExpr.toRedExpr(frame)
        val redExprs = toRedExprs(frame, redKeyExpr, exprs)
        return if (redExprs != null) {
            RedDb_Utils.makeRedDbInExpr(redKeyExpr, redExprs)
        } else {
            RedDb_ConstantExpr(Rt_BooleanValue(true))
        }
    }

    companion object {
        fun toRedExprs(frame: Rt_CallFrame, redKeyExpr: RedDb_Expr, exprs: List<Db_Expr>): List<RedDb_Expr>? {
            val keyValue = redKeyExpr.constantValue()

            val redExprs = mutableListOf<RedDb_Expr>()
            for (expr in exprs) {
                val redExpr = expr.toRedExpr(frame)
                val exprValue = if (keyValue == null) null else redExpr.constantValue()
                if (keyValue != null && exprValue != null) {
                    if (exprValue == keyValue) {
                        return null
                    }
                } else {
                    redExprs.add(redExpr)
                }
            }

            return redExprs
        }
    }
}

private class RedDb_InExpr(val keyExpr: RedDb_Expr, val exprs: List<RedDb_Expr>): RedDb_Expr() {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
        bld.append("(")
        keyExpr.toSql(ctx, bld)
        bld.append(" IN (")
        bld.append(exprs, ", ") { expr ->
            expr.toSql(ctx, bld)
        }
        bld.append(")")
        bld.append(")")
    }
}

class Db_WhenCase(val conds: List<Db_Expr>, val expr: Db_Expr)

class Db_WhenExpr(type: R_Type, val keyExpr: Db_Expr?, val cases: List<Db_WhenCase>, val elseExpr: Db_Expr)
    : Db_Expr(type, listOfNotNull(keyExpr) + cases.flatMap { it.conds + listOf(it.expr) } + listOf(elseExpr))
{
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val redKeyExpr = keyExpr?.toRedExpr(frame)

        val internalCases = mutableListOf<Pair<RedDb_Expr, Db_Expr>>()
        val matchedCase = if (redKeyExpr != null) {
            makeRedCasesKeyed(frame, redKeyExpr, internalCases)
        } else {
            makeRedCasesGeneral(frame, internalCases)
        }

        if (matchedCase != null) {
            val redExpr = matchedCase.expr.toRedExpr(frame)
            return redExpr
        }

        val redCases = internalCases.map { (redCond, expr) ->
            val redExpr = expr.toRedExpr(frame)
            RedDb_WhenCase(redCond, redExpr)
        }

        val redElse = elseExpr.toRedExpr(frame)
        if (redCases.isEmpty()) {
            return redElse
        }

        return RedDb_WhenExpr(redCases, redElse)
    }

    private fun makeRedCasesKeyed(
            frame: Rt_CallFrame,
            redKeyExpr: RedDb_Expr,
            resCases: MutableList<Pair<RedDb_Expr, Db_Expr>>
    ): Db_WhenCase? {
        for (case in cases) {
            val matched = makeRedCaseKeyed(frame, redKeyExpr, case, resCases)
            if (matched) {
                return case
            }
        }
        return null
    }

    private fun makeRedCaseKeyed(
            frame: Rt_CallFrame,
            redKeyExpr: RedDb_Expr,
            case: Db_WhenCase,
            resCases: MutableList<Pair<RedDb_Expr, Db_Expr>>
    ): Boolean {
        val redConds = Db_InExpr.toRedExprs(frame, redKeyExpr, case.conds)

        if (redConds == null) {
            return true
        }

        if (!redConds.isEmpty()) {
            val redCond = RedDb_Utils.makeRedDbInExpr(redKeyExpr, redConds)
            resCases.add(Pair(redCond, case.expr))
        }

        return false
    }

    private fun makeRedCasesGeneral(frame: Rt_CallFrame, resCases: MutableList<Pair<RedDb_Expr, Db_Expr>>): Db_WhenCase? {
        for (case in cases) {
            val redConds = mutableListOf<RedDb_Expr>()
            for (cond in case.conds) {
                val redCond = cond.toRedExpr(frame)
                val condValue = redCond.constantValue()
                if (condValue != null) {
                    if (condValue.asBoolean()) {
                        return case
                    }
                } else {
                    redConds.add(redCond)
                }
            }

            if (!redConds.isEmpty()) {
                val redCond = RedDb_Utils.makeRedDbBinaryExprChain(Db_BinaryOp_Or, redConds)
                resCases.add(Pair(redCond, case.expr))
            }
        }

        return null
    }
}

private class RedDb_WhenCase(val cond: RedDb_Expr, val expr: RedDb_Expr)

private class RedDb_WhenExpr(val cases: List<RedDb_WhenCase>, val elseExpr: RedDb_Expr): RedDb_Expr() {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
        bld.append("CASE")

        for (case in cases) {
            bld.append(" WHEN ")
            case.cond.toSql(ctx, bld)
            bld.append(" THEN ")
            case.expr.toSql(ctx, bld)
        }

        bld.append(" ELSE ")
        elseExpr.toSql(ctx, bld)

        bld.append(" END")
    }
}

class Db_ElvisExpr(type: R_Type, val left: R_Expr, val right: Db_Expr): Db_Expr(type, listOf(right)) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val leftValue = left.evaluate(frame)
        if (leftValue != Rt_NullValue) {
            return RedDb_ConstantExpr(leftValue)
        }

        val redRight = right.toRedExpr(frame)
        return redRight
    }
}

class Db_CallExpr(type: R_Type, val fn: Db_SysFunction, val args: List<Db_Expr>): Db_Expr(type, args) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val redArgs = args.map { it.toRedExpr(frame) }
        val redExpr = RedDb_CallExpr(fn, redArgs)
        return RedDb_Utils.wrapDecimalExpr(type, redExpr)
    }

    private class RedDb_CallExpr(val fn: Db_SysFunction, val args: List<RedDb_Expr>): RedDb_Expr() {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) = fn.toSql(ctx, bld, args)
    }
}

class Db_ExistsExpr(val subExpr: Db_Expr, val not: Boolean): Db_Expr(R_BooleanType, listOf(subExpr)) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val redSubExpr = subExpr.toRedExpr(frame)
        return RedDb_ExistsExpr(not, redSubExpr)
    }

    private class RedDb_ExistsExpr(val not: Boolean, val subExpr: RedDb_Expr): RedDb_Expr() {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
            if (not) bld.append("NOT ")
            bld.append("EXISTS(")
            subExpr.toSql(ctx, bld)
            bld.append(")")
        }
    }
}

object RedDb_Utils {
    fun makeRedDbBinaryExprChain(op: Db_BinaryOp, exprs: List<RedDb_Expr>): RedDb_Expr {
        return CommonUtils.foldSimple(exprs) { left, right -> RedDb_BinaryExpr(op, left, right) }
    }

    fun makeRedDbInExpr(left: RedDb_Expr, right: List<RedDb_Expr>): RedDb_Expr {
        return if (right.isEmpty()) {
            RedDb_ConstantExpr(Rt_BooleanValue(false))
        } else if (right.size == 1) {
            RedDb_BinaryExpr(Db_BinaryOp_Eq, left, right[0])
        } else {
            RedDb_InExpr(left, right)
        }
    }

    fun wrapDecimalExpr(type: R_Type, redExpr: RedDb_Expr): RedDb_Expr {
        return if (type != R_DecimalType || redExpr is RedDb_DecimalRoundExpr) {
            redExpr
        } else {
            RedDb_DecimalRoundExpr(redExpr)
        }
    }

    private class RedDb_DecimalRoundExpr(private val expr: RedDb_Expr): RedDb_Expr() {
        override fun constantValue(): Rt_Value? = expr.constantValue()

        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
            bld.append("ROUND(")
            expr.toSql(ctx, bld)
            bld.append(", ${C_Constants.DECIMAL_FRAC_DIGITS})")
        }
    }
}
