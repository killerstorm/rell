package net.postchain.rell.model

import net.postchain.rell.runtime.*
import net.postchain.rell.sql.ROWID_COLUMN

class R_AtClass(val rClass: R_Class, val alias: String, val index: Int) {
    val type: R_Type = R_ClassType(rClass)
}

sealed class R_AtExprRowType {
    abstract fun decode(row: Array<Rt_Value>): Rt_Value
}

object R_AtExprRowTypeSimple: R_AtExprRowType() {
    override fun decode(row: Array<Rt_Value>): Rt_Value {
        check(row.size == 1) { "row.size == ${row.size}" }
        return row[0]
    }
}

class R_AtExprRowTypeTuple(val type: R_TupleType): R_AtExprRowType() {
    override fun decode(row: Array<Rt_Value>): Rt_Value {
        check(row.size == type.fields.size) { "row.size == ${row.size}, not ${type.fields.size}" }
        return Rt_TupleValue(type, row.toList())
    }
}

class R_AtExprBase(
        val from: List<R_AtClass>,
        val what: List<Db_Expr>,
        val where: Db_Expr?,
        val sort: List<Pair<Db_Expr, Boolean>>,
        val zero: Boolean,
        val many: Boolean
) {
    init {
        from.withIndex().forEach { check(it.index == it.value.index) }
    }

    fun execute(frame: Rt_CallFrame, params: List<Rt_Value>, limit: R_Expr?): List<Array<Rt_Value>> {
        val rtSql = buildSql(params, limit)
        val resultTypes = what.map { it.type }
        val select = SqlSelect(rtSql, resultTypes)
        val records = select.execute(frame)
        return records
    }

    private fun buildSql(params: List<Rt_Value>, limit: R_Expr?): ParameterizedSql {
        val ctx = SqlGenContext(from, params)
        val fromInfo = buildFromInfo(ctx)

        val b = SqlBuilder()

        b.append("SELECT ")
        b.append(what, ", ") {
            it.toSql(ctx, b)
        }

        appendFrom(b, fromInfo)

        if (where != null) {
            b.append(" WHERE ")
            where.toSql(ctx, b)
        }

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

    private fun appendFrom(b: SqlBuilder, fromInfo: SqlFromInfo) {
        b.append(" FROM ")
        b.append(fromInfo.classes, ", ") { cls ->
            b.appendName(cls.alias.cls.mapping.table)
            b.append(" ")
            b.append(cls.alias.str)

            for (join in cls.joins) {
                b.append(" INNER JOIN ")
                b.appendName(join.alias.cls.mapping.table)
                b.append(" ")
                b.append(join.alias.str)
                b.append(" ON ")
                b.appendColumn(join.baseAlias, join.attr)
                b.append(" = ")
                b.appendColumn(join.alias, join.alias.cls.mapping.rowidColumn)
            }
        }
    }
}

class R_AtExpr(
        type: R_Type,
        val base: R_AtExprBase,
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
        if (count == 0 && !base.zero || count > 1 && !base.many) {
            throw errWrongCount(count)
        }

        if (base.many) {
            return Rt_ListValue(type, list)
        } else if (count > 0) {
            return list[0]
        } else {
            return Rt_NullValue
        }
    }

    companion object {
        fun errWrongCount(count: Int): Rt_Error {
            val msg = if (count == 0) "No records found" else "Multiple records found: $count"
            return Rt_Error("at:wrong_count:$count", msg)
        }
    }
}
