package net.postchain.rell.model

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

class R_AtClass(val rClass: R_Class, val alias: String, val index: Int) {
    val type: R_Type = R_ClassType(rClass)
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
        val from: List<R_AtClass>,
        val what: List<Db_Expr>,
        val where: Db_Expr?,
        val sort: List<Pair<Db_Expr, Boolean>>
) {
    init {
        from.withIndex().forEach { check(it.index == it.value.index) }
    }

    fun execute(frame: Rt_CallFrame, params: List<Rt_Value>, limit: R_Expr?): List<Array<Rt_Value>> {
        val sqlMapper = frame.entCtx.modCtx.globalCtx.sqlMapper
        val rtSql = buildSql(sqlMapper, params, limit)
        val resultTypes = what.map { it.type }
        val select = SqlSelect(rtSql, resultTypes)
        val records = select.execute(frame)
        return records
    }

    private fun buildSql(sqlMapper: Rt_SqlMapper, params: List<Rt_Value>, limit: R_Expr?): ParameterizedSql {
        val ctx = SqlGenContext(from, params)
        val fromInfo = buildFromInfo(ctx)

        val b = SqlBuilder()

        b.append("SELECT ")
        b.append(what, ", ") {
            it.toSql(ctx, b)
        }

        appendFrom(b, sqlMapper, fromInfo)
        appendWhere(b, ctx, sqlMapper, fromInfo)

        b.append(" ORDER BY ")
        val orderByList = b.listBuilder()
        for ((expr, asc) in sort) {
            orderByList.nextItem()
            expr.toSql(ctx, b)
            if (!asc) {
                b.append(" DESC")
            }
        }
        for (cls in from) {
            orderByList.nextItem()
            val alias = ctx.getClassAlias(cls)
            b.appendColumn(alias, cls.rClass.mapping.rowidColumn)
        }

        if (limit != null) {
            b.append(" LIMIT ")
            b.append(limit)
        }

        return b.build()
    }

    private fun buildFromInfo(ctx: SqlGenContext): SqlFromInfo {
        val b = SqlBuilder()
        for (w in what) {
            w.toSql(ctx, b)
        }
        where?.toSql(ctx, b)
        for ((expr, _) in sort) {
            expr.toSql(ctx, b)
        }
        for (cls in from) {
            ctx.getClassAlias(cls)
        }
        return ctx.getFromInfo()
    }

    private fun appendFrom(b: SqlBuilder, sqlMapper: Rt_SqlMapper, fromInfo: SqlFromInfo) {
        b.append(" FROM ")
        b.append(fromInfo.classes, ", ") { cls ->
            val table = cls.alias.cls.mapping.table(sqlMapper)
            b.appendName(table)
            b.append(" ")
            b.append(cls.alias.str)

            for (join in cls.joins) {
                b.append(" INNER JOIN ")
                val joinTable = join.alias.cls.mapping.table(sqlMapper)
                b.appendName(joinTable)
                b.append(" ")
                b.append(join.alias.str)
                b.append(" ON ")
                b.appendColumn(join.baseAlias, join.attr)
                b.append(" = ")
                b.appendColumn(join.alias, join.alias.cls.mapping.rowidColumn)
            }
        }
    }

    private fun appendWhere(b: SqlBuilder, ctx: SqlGenContext, sqlMapper: Rt_SqlMapper, fromInfo: SqlFromInfo) {
        val whereB = SqlBuilder()
        where?.toSql(ctx, whereB)
        appendExtraWhere(whereB, sqlMapper, fromInfo)

        if (!whereB.isEmpty()) {
            b.append(" WHERE ")
            b.append(whereB)
        }
    }

    companion object {
        fun appendExtraWhere(b: SqlBuilder, sqlMapper: Rt_SqlMapper, fromInfo: SqlFromInfo) {
            for (cls in fromInfo.classes) {
                cls.alias.cls.mapping.extraWhere(b, sqlMapper, cls.alias)
                for (join in cls.joins) {
                    join.alias.cls.mapping.extraWhere(b, sqlMapper, join.alias)
                }
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
    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
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
