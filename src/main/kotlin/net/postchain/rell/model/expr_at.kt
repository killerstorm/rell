package net.postchain.rell.model

import net.postchain.rell.runtime.*
import net.postchain.rell.sql.ROWID_COLUMN

class RAtClass(val rClass: RClass, val alias: String, val index: Int) {
    val type: RType = RInstanceRefType(rClass)
}

sealed class RAtExprRowType {
    abstract fun decode(row: Array<RtValue>): RtValue
}

object RAtExprRowTypeSimple: RAtExprRowType() {
    override fun decode(row: Array<RtValue>): RtValue {
        check(row.size == 1) { "row.size == ${row.size}" }
        return row[0]
    }
}

class RAtExprRowTypeTuple(val type: RTupleType): RAtExprRowType() {
    override fun decode(row: Array<RtValue>): RtValue {
        check(row.size == type.fields.size) { "row.size == ${row.size}, not ${type.fields.size}" }
        return RtTupleValue(type, row.toList())
    }
}

class RAtExprBase(
        val from: List<RAtClass>,
        val what: List<DbExpr>,
        val where: DbExpr?,
        val sort: List<Pair<DbExpr, Boolean>>,
        val zero: Boolean,
        val many: Boolean
) {
    init {
        from.withIndex().forEach { check(it.index == it.value.index) }
    }

    fun execute(frame: RtCallFrame, params: List<RtValue>, limit: RExpr?): List<Array<RtValue>> {
        val rtSql = buildSql(params, limit)
        val resultTypes = what.map { it.type }
        val select = RtSelect(rtSql, resultTypes)
        val records = select.execute(frame)
        return records
    }

    private fun buildSql(params: List<RtValue>, limit: RExpr?): RtSql {
        val ctx = SqlGenContext(from, params)
        val fromInfo = buildFromInfo(ctx)

        val b = RtSqlBuilder()

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
            b.appendColumn(alias, ROWID_COLUMN)
        }

        if (limit != null) {
            b.append(" LIMIT ")
            b.append(limit)
        }

        return b.build()
    }

    private fun buildFromInfo(ctx: SqlGenContext): SqlFromInfo {
        val b = RtSqlBuilder()
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

    private fun appendFrom(b: RtSqlBuilder, fromInfo: SqlFromInfo) {
        b.append(" FROM ")
        b.append(fromInfo.classes, ", ") { cls ->
            b.appendName(cls.alias.cls.name)
            b.append(" ")
            b.append(cls.alias.str)

            for (join in cls.joins) {
                b.append(" INNER JOIN ")
                b.appendName(join.alias.cls.name)
                b.append(" ")
                b.append(join.alias.str)
                b.append(" ON ")
                b.appendColumn(join.baseAlias, join.attr)
                b.append(" = ")
                b.appendColumn(join.alias, ROWID_COLUMN)
            }
        }
    }
}

class RAtExpr(
        type: RType,
        val base: RAtExprBase,
        val limit: RExpr?,
        val rowType: RAtExprRowType
): RExpr(type)
{
    override fun evaluate(frame: RtCallFrame): RtValue {
        val records = base.execute(frame, listOf(), limit)
        return decodeResult(records)
    }

    private fun decodeResult(records: List<Array<RtValue>>): RtValue {
        val list = MutableList(records.size) { rowType.decode(records[it]) }

        val count = list.size
        if (count == 0 && !base.zero || count > 1 && !base.many) {
            throw errWrongCount(count)
        }

        if (base.many) {
            return RtListValue(type, list)
        } else if (count > 0) {
            return list[0]
        } else {
            return RtNullValue
        }
    }

    companion object {
        fun errWrongCount(count: Int): RtError {
            val msg = if (count == 0) "No records found" else "Multiple records found: $count"
            return RtError("at:wrong_count:$count", msg)
        }
    }
}
