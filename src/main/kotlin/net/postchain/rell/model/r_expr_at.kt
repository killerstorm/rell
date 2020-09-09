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

class R_AtEntity(val rEntity: R_Entity, val index: Int) {
    override fun equals(other: Any?) = other is R_AtEntity && rEntity == other.rEntity && index == other.index
    override fun hashCode() = rEntity.hashCode() * 31 + index
}

sealed class R_AtExprRowType {
    abstract fun decode(row: Array<Rt_Value>): Rt_Value
}

object R_AtExprRowType_Simple: R_AtExprRowType() {
    override fun decode(row: Array<Rt_Value>): Rt_Value {
        check(row.size == 1) { "row.size == ${row.size}" }
        return row[0]
    }
}

class R_AtExprRowType_Tuple(val type: R_TupleType): R_AtExprRowType() {
    override fun decode(row: Array<Rt_Value>): Rt_Value {
        check(row.size == type.fields.size) { "row.size == ${row.size}, not ${type.fields.size}" }
        return Rt_TupleValue(type, row.toList())
    }
}

enum class R_AtWhatSort(val asc: Boolean) {
    ASC(true),
    DESC(false),
}

class R_AtWhatFlags(val select: Boolean, val sort: R_AtWhatSort?, val group: Boolean, val aggregation: Boolean) {
    val ignore = !select && !group && sort == null

    companion object {
        val DEFAULT = R_AtWhatFlags(select = true, sort = null, group = false, aggregation = false)
    }
}

class R_AtWhatField(val expr: Db_Expr, val flags: R_AtWhatFlags)

class R_AtExprBase(
        private val from: List<R_AtEntity>,
        private val what: List<R_AtWhatField>,
        private val where: Db_Expr?
) {
    private val resultTypes = what.filter { it.flags.select }.map { it.expr.type }.toImmList()

    init {
        from.withIndex().forEach { check(it.index == it.value.index) }
        what.forEach { check(!it.flags.ignore) }
    }

    fun execute(frame: Rt_CallFrame, params: List<Rt_Value>, limit: R_Expr?, offset: R_Expr?): List<Array<Rt_Value>> {
        val rtSql = buildSql(frame, params, limit, offset)
        val select = SqlSelect(rtSql, resultTypes)
        val records = select.execute(frame)
        return records
    }

    private fun buildSql(frame: Rt_CallFrame, params: List<Rt_Value>, limit: R_Expr?, offset: R_Expr?): ParameterizedSql {
        val redWhere = makeFullWhere(frame)
        val redWhat = what.map { RedWhatField(it.expr.toRedExpr(frame), it.flags) }

        val ctx = SqlGenContext.create(frame, from, params)
        val fromInfo = buildFromInfo(ctx, redWhere, redWhat)

        val b = SqlBuilder()

        b.append("SELECT ")

        val selRedWhat = redWhat.filter { it.flags.select }
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
        if (redGroup.isNotEmpty() || redWhat.any { it.flags.aggregation }) {
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

    private class RedWhatField(val expr: RedDb_Expr, val flags: R_AtWhatFlags)

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

    private class OrderByElement_Entity(val entity: R_AtEntity): OrderByElement() {
        override fun toSql(ctx: SqlGenContext, b: SqlBuilder) {
            val alias = ctx.getEntityAlias(entity)
            b.appendColumn(alias, entity.rEntity.sqlMapping.rowidColumn())
        }
    }
}

class R_AtExpr(
        type: R_Type,
        val base: R_AtExprBase,
        val cardinality: R_AtCardinality,
        val limit: R_Expr?,
        val offset: R_Expr?,
        val rowType: R_AtExprRowType
): R_Expr(type) {
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val records = base.execute(frame, listOf(), limit, offset)
        return decodeResult(records)
    }

    private fun decodeResult(records: List<Array<Rt_Value>>): Rt_Value {
        val list = MutableList(records.size) { rowType.decode(records[it]) }

        val count = list.size
        checkCount(cardinality, count)

        if (cardinality.many) {
            return Rt_ListValue(type, list)
        } else if (count > 0) {
            return list[0]
        } else {
            return Rt_NullValue
        }
    }

    companion object {
        fun checkCount(cardinality: R_AtCardinality, count: Int) {
            if (!cardinality.matches(count)) {
                val msg = if (count == 0) "No records found" else "Multiple records found: $count"
                throw Rt_Error("at:wrong_count:$count", msg)
            }
        }
    }
}
