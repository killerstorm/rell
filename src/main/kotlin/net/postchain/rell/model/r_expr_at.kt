/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model

import net.postchain.rell.compiler.C_Utils
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.toImmList

enum class R_AtCardinality(val zero: Boolean, val many: Boolean) {
    ZERO_ONE(true, false),
    ONE(false, false),
    ZERO_MANY(true, true),
    ONE_MANY(false, true),
    ;

    fun matches(count: Int): Boolean {
        if (count < 0 || count == 0 && !zero || count > 1 && !many) {
            return false
        }
        return true
    }
}

class R_DbAtEntity(val rEntity: R_EntityDefinition, val index: Int) {
    override fun equals(other: Any?) = other is R_DbAtEntity && rEntity == other.rEntity && index == other.index
    override fun hashCode() = rEntity.hashCode() * 31 + index
}

sealed class R_AtExprRowDecoder {
    abstract fun decode(row: List<Rt_Value>): Rt_Value
}

object R_AtExprRowDecoder_Simple: R_AtExprRowDecoder() {
    override fun decode(row: List<Rt_Value>): Rt_Value {
        check(row.size == 1) { "row.size == ${row.size}" }
        return row[0]
    }
}

class R_AtExprRowDecoder_Tuple(val type: R_TupleType): R_AtExprRowDecoder() {
    override fun decode(row: List<Rt_Value>): Rt_Value {
        check(row.size == type.fields.size) { "row.size == ${row.size}, not ${type.fields.size}" }
        return Rt_TupleValue(type, row.toList())
    }
}

enum class R_AtWhatSort(val asc: Boolean) {
    ASC(true),
    DESC(false),
}

class R_AtWhatFieldFlags(val omit: Boolean, val sort: R_AtWhatSort?, val group: Boolean, val aggregate: Boolean) {
    companion object {
        val DEFAULT = R_AtWhatFieldFlags(omit = false, sort = null, group = false, aggregate = false)
    }
}

abstract class R_DbAtWhatCombiner {
    abstract fun combine(values: List<Rt_Value>): Rt_Value
}

sealed class R_DbAtWhatValue {
    abstract fun rawCount(): Int
    abstract fun rawTypes(): List<R_Type>
    abstract fun toRedExprs(frame: Rt_CallFrame): List<RedDb_Expr>
    abstract fun rawToResult(rawValues: List<Rt_Value>): Rt_Value
}

class R_DbAtWhatValue_Simple(private val expr: Db_Expr, private val resultType: R_Type): R_DbAtWhatValue() {
    override fun rawCount() = 1
    override fun rawTypes() = listOf(resultType)

    override fun toRedExprs(frame: Rt_CallFrame): List<RedDb_Expr> {
        val redExpr = expr.toRedExpr(frame)
        return listOf(redExpr)
    }

    override fun rawToResult(rawValues: List<Rt_Value>): Rt_Value {
        check(rawValues.size == 1) { "rawValues.size = ${rawValues.size}" }
        return rawValues[0]
    }
}

class R_DbAtWhatValue_Complex(exprs: List<Db_Expr>, private val combiner: R_DbAtWhatCombiner): R_DbAtWhatValue() {
    private val exprs = exprs.toImmList()

    override fun rawCount() = exprs.size
    override fun rawTypes() = exprs.map { it.type }

    override fun toRedExprs(frame: Rt_CallFrame): List<RedDb_Expr> {
        return exprs.map { it.toRedExpr(frame) }
    }

    override fun rawToResult(rawValues: List<Rt_Value>): Rt_Value {
        check(rawValues.size == exprs.size) { "rawValues.size = ${rawValues.size} (expected {${exprs.size}})" }
        return combiner.combine(rawValues)
    }
}

class R_DbAtWhatField(val flags: R_AtWhatFieldFlags, val value: R_DbAtWhatValue)

class R_DbAtExprBase(
        private val from: List<R_DbAtEntity>,
        private val what: List<R_DbAtWhatField>,
        private val where: Db_Expr?
) {
    private val selWhat = what.filter { !it.flags.omit }.toImmList()
    private val resultTypes = selWhat.flatMap { it.value.rawTypes() }.toImmList()

    init {
        from.withIndex().forEach { check(it.index == it.value.index) }
    }

    fun execute(frame: Rt_CallFrame, params: List<Rt_Value>, limit: Long?, offset: Long?): List<List<Rt_Value>> {
        val rtSql = buildSql(frame, params, limit, offset)
        val select = SqlSelect(rtSql, resultTypes)
        val records = select.execute(frame) { combineRow(it) }
        return records
    }

    private fun combineRow(row: List<Rt_Value>): List<Rt_Value> {
        val res: MutableList<Rt_Value> = ArrayList(selWhat.size)

        var pos = 0
        for (field in selWhat) {
            val v = field.value
            val count = v.rawCount()
            val subRow = row.subList(pos, pos + count)
            val value = v.rawToResult(subRow)
            res.add(value)
            pos += count
        }

        return res.toImmList()
    }

    private fun buildSql(frame: Rt_CallFrame, params: List<Rt_Value>, limit: Long?, offset: Long?): ParameterizedSql {
        val redWhere = makeFullWhere(frame)

        val redWhat = what.flatMap { whatField ->
            val redExprs = whatField.value.toRedExprs(frame)
            redExprs.map { RedWhatField(it, whatField.flags) }
        }

        val ctx = SqlGenContext.create(frame, from, params)
        val fromInfo = buildFromInfo(ctx, redWhere, redWhat)

        val b = SqlBuilder()

        b.append("SELECT ")

        val selRedWhat = redWhat.filter { !it.flags.omit }
        b.append(selRedWhat, ", ") {
            it.expr.toSql(ctx, b)
        }

        appendFrom(b, ctx.sqlCtx, fromInfo)
        appendWhere(b, ctx, redWhere, fromInfo)
        appendGroupBy(b, ctx, redWhat)
        appendOrderBy(b, ctx, redWhat)

        if (limit != null) {
            b.append(" LIMIT ")
            b.append(limit)
        }

        if (offset != null) {
            b.append(" OFFSET ")
            b.append(offset)
        }

        return b.build()
    }

    private fun buildFromInfo(
            ctx: SqlGenContext,
            redWhere: RedDb_Expr?,
            redWhat: List<RedWhatField>
    ): SqlFromInfo {
        val redExprs = mutableListOf<RedDb_Expr>()
        redExprs.addAll(redWhat.map { it.expr })
        if (redWhere != null) redExprs.add(redWhere)

        val b = SqlBuilder()
        for (expr in redExprs) {
            expr.toSql(ctx, b)
        }
        for (entity in from) {
            ctx.getEntityAlias(entity)
        }
        return ctx.getFromInfo()
    }

    private fun appendFrom(b: SqlBuilder, sqlCtx: Rt_SqlContext, fromInfo: SqlFromInfo) {
        b.append(" FROM ")
        b.append(fromInfo.entities, ", ") { entity ->
            val table = entity.alias.entity.sqlMapping.table(sqlCtx)
            b.appendName(table)
            b.append(" ")
            b.append(entity.alias.str)

            for (join in entity.joins) {
                b.append(" INNER JOIN ")
                val joinMapping = join.alias.entity.sqlMapping
                b.appendName(joinMapping.table(sqlCtx))
                b.append(" ")
                b.append(join.alias.str)
                b.append(" ON ")
                b.appendColumn(join.baseAlias, join.attr)
                b.append(" = ")
                b.appendColumn(join.alias, joinMapping.rowidColumn())
            }
        }
    }

    private fun appendWhere(b: SqlBuilder, ctx: SqlGenContext, redWhere: RedDb_Expr?, fromInfo: SqlFromInfo) {
        val whereB = SqlBuilder()
        redWhere?.toSql(ctx, whereB)

        if (!whereB.isEmpty()) {
            b.append(" WHERE ")
            b.append(whereB)
        }
    }

    private fun appendGroupBy(b: SqlBuilder, ctx: SqlGenContext, redWhat: List<RedWhatField>) {
        val redGroup = redWhat.filter { it.flags.group }
        if (redGroup.isEmpty()) {
            return
        }

        b.append(" GROUP BY ")

        val listB = b.listBuilder()
        for (field in redGroup) {
            listB.nextItem()
            field.expr.toSql(ctx, b)
        }
    }

    private fun appendOrderBy(b: SqlBuilder, ctx: SqlGenContext, redWhat: List<RedWhatField>) {
        val elements = getOrderByElements(redWhat)
        if (elements.isEmpty()) {
            return
        }

        b.append(" ORDER BY ")

        val orderByList = b.listBuilder()
        for (element in elements) {
            orderByList.nextItem()
            element.toSql(ctx, b)
        }
    }

    private fun getOrderByElements(redWhat: List<RedWhatField>): List<OrderByElement> {
        val elements = mutableListOf<OrderByElement>()

        for (field in redWhat) {
            if (field.flags.sort != null) {
                elements.add(OrderByElement_Expr(field.expr, field.flags.sort))
            }
        }

        val redGroup = redWhat.filter { it.flags.group }
        if (redGroup.isNotEmpty() || redWhat.any { it.flags.aggregate }) {
            for (field in redGroup) {
                if (field.flags.sort == null) {
                    elements.add(OrderByElement_Expr(field.expr, R_AtWhatSort.ASC))
                }
            }
        } else {
            for (entity in from) {
                elements.add(OrderByElement_Entity(entity))
            }
        }

        return elements.toImmList()
    }

    private fun makeFullWhere(frame: Rt_CallFrame): RedDb_Expr? {
        val exprs = mutableListOf<Db_Expr?>()
        exprs.add(where)

        for (atEntity in from) {
            val expr = atEntity.rEntity.sqlMapping.extraWhereExpr(atEntity)
            exprs.add(expr)
        }

        val validExprs = exprs.filterNotNull()
        val expr = if (validExprs.isEmpty()) {
            null
        } else {
            C_Utils.makeDbBinaryExprChain(R_BooleanType, R_BinaryOp_And, Db_BinaryOp_And, validExprs)
        }

        return expr?.toRedExpr(frame)
    }

    private class RedWhatField(val expr: RedDb_Expr, val flags: R_AtWhatFieldFlags)

    private abstract class OrderByElement {
        abstract fun toSql(ctx: SqlGenContext, b: SqlBuilder)
    }

    private class OrderByElement_Expr(val redExpr: RedDb_Expr, val sort: R_AtWhatSort): OrderByElement() {
        override fun toSql(ctx: SqlGenContext, b: SqlBuilder) {
            redExpr.toSql(ctx, b)
            if (!sort.asc) {
                b.append(" DESC")
            }
        }
    }

    private class OrderByElement_Entity(val entity: R_DbAtEntity): OrderByElement() {
        override fun toSql(ctx: SqlGenContext, b: SqlBuilder) {
            val alias = ctx.getEntityAlias(entity)
            b.appendColumn(alias, entity.rEntity.sqlMapping.rowidColumn())
        }
    }
}

abstract class R_AtExpr(
        type: R_Type,
        val cardinality: R_AtCardinality,
        private val limit: R_Expr?,
        private val offset: R_Expr?
): R_Expr(type) {
    protected fun evalLimit(frame: Rt_CallFrame): Long? = evalLimitOffset(frame, limit, "limit")
    protected fun evalOffset(frame: Rt_CallFrame): Long? = evalLimitOffset(frame, offset, "offset")

    private fun evalLimitOffset(frame: Rt_CallFrame, expr: R_Expr?, part: String): Long? {
        if (expr == null) {
            return null
        }

        val v0 = expr.evaluate(frame)
        val v = v0.asInteger()

        if (v < 0) {
            val codeFmt = "expr:at:$part:negative:$v"
            val msgFmt = "Negative $part: $v"
            throw Rt_Error(codeFmt, msgFmt)
        }

        return v
    }

    protected fun evalResult(list: MutableList<Rt_Value>): Rt_Value {
        if (cardinality.many) {
            return Rt_ListValue(type, list)
        } else if (list.isNotEmpty()) {
            return list[0]
        } else {
            return Rt_NullValue
        }
    }

    companion object {
        fun checkCount(cardinality: R_AtCardinality, count: Int, itemMsg: String) {
            if (!cardinality.matches(count)) {
                val code = "at:wrong_count:$count"
                val msg = if (count == 0) "No $itemMsg found" else "Multiple $itemMsg found: $count"
                throw Rt_Error(code, msg)
            }
        }
    }
}

class R_DbAtExpr(
        type: R_Type,
        val base: R_DbAtExprBase,
        cardinality: R_AtCardinality,
        limit: R_Expr?,
        offset: R_Expr?,
        val rowDecoder: R_AtExprRowDecoder
): R_AtExpr(type, cardinality, limit, offset) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val limit = evalLimit(frame)
        val offset = evalOffset(frame)

        val records = base.execute(frame, listOf(), limit, offset)
        checkCount(cardinality, records.count(), "records")

        val values = MutableList(records.size) { rowDecoder.decode(records[it]) }
        return evalResult(values)
    }
}
