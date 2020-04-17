/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model

import net.postchain.rell.utils.CommonUtils
import net.postchain.rell.compiler.C_Constants
import net.postchain.rell.compiler.C_EntityAttrRef
import net.postchain.rell.runtime.Rt_BooleanValue
import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_NullValue
import net.postchain.rell.runtime.Rt_Value

sealed class Db_BinaryOp(val code: String, val sql: String) {
    open fun evaluate(left: Rt_Value, right: Rt_Value): Rt_Value? = null

    open fun toRedExpr(frame: Rt_CallFrame, type: R_Type, redLeft: RedDb_Expr, right: Db_Expr): RedDb_Expr {
        val redRight = right.toRedExpr(frame)

        val leftValue = redLeft.constantValue()
        val rightValue = if (leftValue == null) null else redRight.constantValue()
        if (leftValue != null && rightValue != null) {
            val resValue = evaluate(leftValue, rightValue)
            if (resValue != null) {
                return RedDb_ConstantExpr(type, resValue)
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
        if (leftValue == null) {
            return super.toRedExpr(frame, type, redLeft, right)
        }

        if (leftValue.asBoolean() == shortCircuitValue) {
            return RedDb_ConstantExpr(type, leftValue)
        }

        val redRight = right.toRedExpr(frame)
        return redRight
    }
}

object Db_BinaryOp_And: Db_BinaryOp_AndOr("and", "AND", false)
object Db_BinaryOp_Or: Db_BinaryOp_AndOr("or", "OR", true)

sealed class Db_UnaryOp(val code: String, val sql: String)
object Db_UnaryOp_Minus_Integer: Db_UnaryOp("-", "-")
object Db_UnaryOp_Minus_Decimal: Db_UnaryOp("-", "-")
object Db_UnaryOp_Not: Db_UnaryOp("not", "NOT")

sealed class Db_Expr(val type: R_Type) {
    open fun implicitName(): String? = null
    open fun constantValue(): Rt_Value? = null
    abstract fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr
}

abstract class RedDb_Expr {
    open fun constantValue(): Rt_Value? = null
    abstract fun toSql(ctx: SqlGenContext, bld: SqlBuilder)
}

class Db_InterpretedExpr(val expr: R_Expr): Db_Expr(expr.type) {
    override fun constantValue() = expr.constantValue()

    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val value = expr.evaluate(frame)
        return RedDb_ConstantExpr(type, value)
    }
}

private class RedDb_ConstantExpr(val type: R_Type, val value: Rt_Value): RedDb_Expr() {
    override fun constantValue() = value

    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
        bld.append(type, value)
    }
}

class Db_BinaryExpr(type: R_Type, val op: Db_BinaryOp, val left: Db_Expr, val right: Db_Expr): Db_Expr(type) {
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

class Db_UnaryExpr(type: R_Type, val op: Db_UnaryOp, val expr: Db_Expr): Db_Expr(type) {
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

class Db_IsNullExpr(val expr: Db_Expr, val isNull: Boolean): Db_Expr(R_BooleanType) {
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

sealed class Db_TableExpr(val rEntity: R_Entity): Db_Expr(rEntity.type) {
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

class Db_EntityExpr(val entity: R_AtEntity): Db_TableExpr(entity.rEntity) {
    override fun alias(ctx: SqlGenContext) = ctx.getEntityAlias(entity)
}

class Db_RelExpr(val base: Db_TableExpr, val attr: R_Attrib, targetEntity: R_Entity): Db_TableExpr(targetEntity) {
    override fun implicitName(): String? {
        return if (base is Db_EntityExpr) attr.name else null
    }

    override fun alias(ctx: SqlGenContext): SqlTableAlias {
        val baseAlias = base.alias(ctx)
        return ctx.getRelAlias(baseAlias, attr, rEntity)
    }
}

class Db_AttrExpr(val base: Db_TableExpr, val attr: R_Attrib): Db_Expr(attr.type) {
    override fun implicitName(): String? {
        return if (base is Db_EntityExpr) attr.name else null
    }

    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val redExpr = RedDb_AttrExpr(base, attr)
        return RedDb_Utils.wrapDecimalExpr(type, redExpr)
    }

    private class RedDb_AttrExpr(val base: Db_TableExpr, val attr: R_Attrib): RedDb_Expr() {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
            val alias = base.alias(ctx)
            bld.appendColumn(alias, attr.sqlMapping)
        }
    }
}

class Db_RowidExpr(val base: Db_TableExpr): Db_Expr(C_EntityAttrRef.ROWID_TYPE) {
    override fun implicitName(): String? {
        return if (base is Db_EntityExpr) C_EntityAttrRef.ROWID_NAME else null
    }

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

class Db_ParameterExpr(type: R_Type, val index: Int): Db_Expr(type) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        return RedDb_ParameterExpr(type, index)
    }

    private class RedDb_ParameterExpr(val type: R_Type, val index: Int): RedDb_Expr() {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
            val value = ctx.getParameter(index)
            bld.append(type, value)
        }
    }
}

class Db_ArrayParameterExpr(type: R_Type, val elementType: R_Type, val index: Int): Db_Expr(type) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        return RedDb_ArrayParameterExpr(elementType, index)
    }

    private class RedDb_ArrayParameterExpr(val elementType: R_Type, val index: Int): RedDb_Expr() {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
            val value = ctx.getParameter(index)
            bld.append("(")
            bld.append(value.asCollection(), ",") {
                bld.append(elementType, it)
            }
            bld.append(")")
        }
    }
}

class Db_InExpr(val keyExpr: Db_Expr, val exprs: List<Db_Expr>): Db_Expr(R_BooleanType) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val redKeyExpr = keyExpr.toRedExpr(frame)
        val redExprs = toRedExprs(frame, redKeyExpr, exprs)
        return if (redExprs != null) {
            RedDb_Utils.makeRedDbInExpr(redKeyExpr, redExprs)
        } else {
            RedDb_ConstantExpr(R_BooleanType, Rt_BooleanValue(true))
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

class Db_WhenExpr(type: R_Type, val keyExpr: Db_Expr?, val cases: List<Db_WhenCase>, val elseExpr: Db_Expr): Db_Expr(type) {
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

class Db_ElvisExpr(type: R_Type, val left: R_Expr, val right: Db_Expr): Db_Expr(type) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val leftValue = left.evaluate(frame)
        if (leftValue != Rt_NullValue) {
            return RedDb_ConstantExpr(type, leftValue)
        }

        val redRight = right.toRedExpr(frame)
        return redRight
    }
}

abstract class Db_SysFunction(val name: String) {
    abstract fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<RedDb_Expr>)
}

abstract class Db_SysFn_Simple(name: String, val sql: String): Db_SysFunction(name) {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<RedDb_Expr>) {
        bld.append(sql)
        bld.append("(")
        bld.append(args, ", ") {
            it.toSql(ctx, bld)
        }
        bld.append(")")
    }
}

abstract class Db_SysFn_Cast(name: String, val type: String): Db_SysFunction(name) {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<RedDb_Expr>) {
        check(args.size == 1)
        bld.append("((")
        args[0].toSql(ctx, bld)
        bld.append(")::$type)")
    }
}

object Db_SysFn_Int_ToText: Db_SysFn_Cast("int.to_text", "TEXT")
object Db_SysFn_Abs_Integer: Db_SysFn_Simple("abs", "ABS")
object Db_SysFn_Abs_Decimal: Db_SysFn_Simple("abs", "ABS")
object Db_SysFn_Min_Integer: Db_SysFn_Simple("min", "LEAST")
object Db_SysFn_Min_Decimal: Db_SysFn_Simple("min", "LEAST")
object Db_SysFn_Max_Integer: Db_SysFn_Simple("max", "GREATEST")
object Db_SysFn_Max_Decimal: Db_SysFn_Simple("max", "GREATEST")
object Db_SysFn_Sign: Db_SysFn_Simple("sign", "SIGN")
object Db_SysFn_Text_Size: Db_SysFn_Simple("text.len", "LENGTH")
object Db_SysFn_Text_UpperCase: Db_SysFn_Simple("text.len", "UPPER")
object Db_SysFn_Text_LowerCase: Db_SysFn_Simple("text.len", "LOWER")
object Db_SysFn_ByteArray_Size: Db_SysFn_Simple("byte_array.len", "LENGTH")
object Db_SysFn_Json: Db_SysFn_Cast("json", "JSONB")
object Db_SysFn_Json_ToText: Db_SysFn_Cast("json.to_text", "TEXT")
object Db_SysFn_ToText: Db_SysFn_Cast("to_text", "TEXT")

object Db_SysFn_Decimal {
    object FromInteger: Db_SysFn_Cast("decimal(integer)", C_Constants.DECIMAL_SQL_TYPE_STR)
    object FromText: Db_SysFn_Cast("decimal(text)", C_Constants.DECIMAL_SQL_TYPE_STR)

    object Ceil: Db_SysFn_Simple("decimal.ceil", "CEIL")
    object Floor: Db_SysFn_Simple("decimal.floor", "FLOOR")

    object Round: Db_SysFunction("decimal.round") {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<RedDb_Expr>) {
            check(args.size == 1 || args.size == 2)
            bld.append("ROUND(")
            args[0].toSql(ctx, bld)
            if (args.size == 2) {
                // Argument #2 has to be casted to INT, PostgreSQL doesn't allow BIGINT.
                bld.append(", (")
                args[1].toSql(ctx, bld)
                bld.append(")::INT")
            }
            bld.append(")")
        }
    }

    object Pow: Db_SysFn_Simple("decimal.pow", "POW")
    object Sign: Db_SysFn_Simple("decimal.sign", "SIGN")
    object Sqrt: Db_SysFn_Simple("decimal.sqrt", "SQRT")

    object ToInteger: Db_SysFunction("decimal.to_integer") {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<RedDb_Expr>) {
            check(args.size == 1)
            bld.append("TRUNC(")
            args[0].toSql(ctx, bld)
            bld.append(")::BIGINT")
        }
    }

    object ToText: Db_SysFunction("decimal.to_text") {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<RedDb_Expr>) {
            // Using regexp to remove trailing zeros.
            check(args.size == 1)
            bld.append("REGEXP_REPLACE(")
            args[0].toSql(ctx, bld)
            // Clever regexp: can handle special cases like "0.0", "0.000000", etc.
            bld.append(" :: TEXT, '(([.][0-9]*[1-9])(0+)\$)|([.]0+\$)', '\\2')")
        }
    }
}

object Db_SysFn_Nop: Db_SysFunction("NOP") {
    override fun toSql(ctx: SqlGenContext, bld: SqlBuilder, args: List<RedDb_Expr>) {
        check(args.size == 1)
        args[0].toSql(ctx, bld)
    }
}

class Db_CallExpr(type: R_Type, val fn: Db_SysFunction, val args: List<Db_Expr>): Db_Expr(type) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val redArgs = args.map { it.toRedExpr(frame) }
        val redExpr = RedDb_CallExpr(fn, redArgs)
        return RedDb_Utils.wrapDecimalExpr(type, redExpr)
    }

    private class RedDb_CallExpr(val fn: Db_SysFunction, val args: List<RedDb_Expr>): RedDb_Expr() {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) = fn.toSql(ctx, bld, args)
    }
}

object RedDb_Utils {
    fun makeRedDbBinaryExprChain(op: Db_BinaryOp, exprs: List<RedDb_Expr>): RedDb_Expr {
        return CommonUtils.foldSimple(exprs) { left, right -> RedDb_BinaryExpr(op, left, right) }
    }

    fun makeRedDbInExpr(left: RedDb_Expr, right: List<RedDb_Expr>): RedDb_Expr {
        return if (right.isEmpty()) {
            RedDb_ConstantExpr(R_BooleanType, Rt_BooleanValue(false))
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
