package net.postchain.rell.model

import net.postchain.rell.parser.C_Utils
import net.postchain.rell.runtime.*

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

class R_AtClass(val rClass: R_Class, val index: Int) {
    override fun equals(other: Any?) = other is R_AtClass && rClass == other.rClass && index == other.index
    override fun hashCode() = rClass.hashCode() * 31 + index
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

class R_AtExprBase(
        private val from: List<R_AtClass>,
        private val what: List<Db_Expr>,
        private val where: Db_Expr?,
        private val sort: List<Pair<Db_Expr, Boolean>>
) {
    init {
        from.withIndex().forEach { check(it.index == it.value.index) }
    }

    fun execute(frame: Rt_CallFrame, params: List<Rt_Value>, limit: R_Expr?): List<Array<Rt_Value>> {
        val rtSql = buildSql(frame, params, limit)
        val resultTypes = what.map { it.type }
        val select = SqlSelect(rtSql, resultTypes)
        val records = select.execute(frame)
        return records
    }

    private fun buildSql(frame: Rt_CallFrame, params: List<Rt_Value>, limit: R_Expr?): ParameterizedSql {
        val redWhere = makeFullWhere(frame)
        val redWhat = what.map { it.toRedExpr(frame) }
        val redSort = sort.map { (s, b) -> Pair(s.toRedExpr(frame), b) }

        val ctx = SqlGenContext.create(frame, from, params)
        val fromInfo = buildFromInfo(ctx, redWhere, redWhat, redSort)

        val b = SqlBuilder()

        b.append("SELECT ")
        b.append(redWhat, ", ") {
            it.toSql(ctx, b)
        }

        appendFrom(b, ctx.sqlCtx, fromInfo)
        appendWhere(b, ctx, redWhere, fromInfo)

        b.append(" ORDER BY ")
        val orderByList = b.listBuilder()
        for ((expr, asc) in redSort) {
            orderByList.nextItem()
            expr.toSql(ctx, b)
            if (!asc) {
                b.append(" DESC")
            }
        }
        for (cls in from) {
            orderByList.nextItem()
            val alias = ctx.getClassAlias(cls)
            b.appendColumn(alias, cls.rClass.sqlMapping.rowidColumn())
        }

        if (limit != null) {
            b.append(" LIMIT ")
            b.append(limit)
        }

        return b.build()
    }

    private fun buildFromInfo(
            ctx: SqlGenContext,
            redWhere: RedDb_Expr?,
            redWhat: List<RedDb_Expr>,
            redSort: List<Pair<RedDb_Expr, Boolean>>
    ): SqlFromInfo {
        val b = SqlBuilder()
        for (w in redWhat) {
            w.toSql(ctx, b)
        }
        redWhere?.toSql(ctx, b)
        for ((expr, _) in redSort) {
            expr.toSql(ctx, b)
        }
        for (cls in from) {
            ctx.getClassAlias(cls)
        }
        return ctx.getFromInfo()
    }

    private fun appendFrom(b: SqlBuilder, sqlCtx: Rt_SqlContext, fromInfo: SqlFromInfo) {
        b.append(" FROM ")
        b.append(fromInfo.classes, ", ") { cls ->
            val table = cls.alias.cls.sqlMapping.table(sqlCtx)
            b.appendName(table)
            b.append(" ")
            b.append(cls.alias.str)

            for (join in cls.joins) {
                b.append(" INNER JOIN ")
                val joinMapping = join.alias.cls.sqlMapping
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
        appendExtraWhere(whereB, ctx.sqlCtx, fromInfo)

        if (!whereB.isEmpty()) {
            b.append(" WHERE ")
            b.append(whereB)
        }
    }

    private fun makeFullWhere(frame: Rt_CallFrame): RedDb_Expr? {
        val exprs = mutableListOf<Db_Expr?>()
        exprs.add(where)

        for (atCls in from) {
            val expr = atCls.rClass.sqlMapping.extraWhereExpr(atCls)
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

    companion object {
        fun appendExtraWhere(b: SqlBuilder, sqlCtx: Rt_SqlContext, fromInfo: SqlFromInfo) {
            for (cls in fromInfo.classes) {
                cls.alias.cls.sqlMapping.appendExtraWhere(b, sqlCtx, cls.alias)
            }
        }
    }
}

class R_AtExpr(
        type: R_Type,
        val base: R_AtExprBase,
        val cardinality: R_AtCardinality,
        val limit: R_Expr?,
        val rowType: R_AtExprRowType
): R_Expr(type)
{
    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val records = base.execute(frame, listOf(), limit)
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
