package net.postchain.rell.model

import net.postchain.rell.runtime.*
import net.postchain.rell.sql.ROWID_COLUMN

class RAtClass(val rClass: RClass, val alias: String, val index: Int)

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

class RAtExpr(
        type: RType,
        val from: List<RAtClass>,
        val what: List<DbExpr>,
        val where: DbExpr?,
        val all: Boolean,
        val rowType: RAtExprRowType
): RExpr(type)
{
    init {
        from.withIndex().forEach { check(it.index == it.value.index) }
    }

    override fun evaluate(env: RtEnv): RtValue {
        val rtSql = buildSql()

        val resultTypes = what.map { it.type }
        val select = RtSelect(rtSql, resultTypes)
        val records = select.execute(env)

        val result = decodeResult(records)
        return result
    }

    private fun buildSql(): RtSql {
        val builder = RtSqlBuilder()

        val fromInfo = buildFromInfo()
        val ctx = SqlGenContext(fromInfo)

        builder.append("SELECT ")

        builder.append(what, ", ") {
            it.toSql(ctx, builder)
        }

        appendFrom(builder, fromInfo)

        if (where != null) {
            builder.append(" WHERE ")
            where.toSql(ctx, builder)
        }

        builder.append(" ORDER BY ")
        builder.append(fromInfo.classes, ", ") {
            builder.appendColumn(it.alias, ROWID_COLUMN)
        }

        return builder.build()
    }

    private fun buildFromInfo(): SqlFromInfo {
        val exprs = mutableListOf<DbExpr>()
        exprs.addAll(what)
        if (where != null) {
            exprs.add(where)
        }

        return SqlFromInfo.create(from, exprs)
    }

    private fun appendFrom(builder: RtSqlBuilder, fromInfo: SqlFromInfo) {
        builder.append(" FROM ")

        builder.append(from.indices.toList(), ", ") {
            builder.appendName(from[it].rClass.name)
            builder.append(" ")
            builder.append(fromInfo.classes[it].alias)

            for (join in fromInfo.classes[it].joins) {
                builder.append(" INNER JOIN ")
                builder.appendName(join.cls.name)
                builder.append(" ")
                builder.append(join.alias)
                builder.append(" ON ")
                builder.appendColumn(join.baseAlias, join.attr)
                builder.append(" = ")
                builder.appendColumn(join.alias, ROWID_COLUMN)
            }
        }
    }

    private fun decodeResult(records: List<Array<RtValue>>): RtValue {
        val values = records.map { rowType.decode(it) }

        if (all) {
            return RtListValue(type, values)
        }

        val count = values.size
        if (count == 0) {
            throw RtError("at:wrong_count:$count", "No records found")
        } else if (count > 1) {
            throw RtError("at:wrong_count:$count", "Multiple records found: $count")
        } else {
            return values[0]
        }
    }
}

class SqlFromInfo(val classes: List<SqlFromClass>) {
    companion object {
        fun create(from: List<RAtClass>, exprs: List<DbExpr>): SqlFromInfo {
            val aliasGen = SqlAliasGenerator()
            val builders = from.map { SqlFromClassBuilder(aliasGen) }

            for (expr in exprs) {
                collectFromInfo(builders, expr)
            }

            val fromClasses = builders.map { it.build() }
            return SqlFromInfo(fromClasses)
        }

        private fun collectFromInfo(builders: List<SqlFromClassBuilder>, expr: DbExpr) {
            expr.visit {
                if (it is PathDbExpr) {
                    val builder = builders[it.cls.index]
                    builder.addPath(it.path)
                }
            }
        }
    }
}

class SqlFromClass(val alias: String, val joins: List<SqlFromJoin>, val pathAliases: Map<List<String>, SqlFromPathAlias>)
class SqlFromJoin(val baseAlias: String, val cls: RClass, val alias: String, val attr: String)
class SqlFromPathAlias(val alias: String, val path: List<PathDbExprStep>)

private class SqlAliasGenerator {
    private var counter = 0
    fun next(): String = String.format("A%02d", counter++)
}

private class SqlFromClassBuilder(private val aliasGen: SqlAliasGenerator) {
    private val alias = aliasGen.next()
    private val joins = mutableListOf<SqlFromJoin>()
    private val pathAliases = mutableMapOf<List<String>, SqlFromPathAlias>()

    init {
        pathAliases[listOf<String>()] = SqlFromPathAlias(alias, listOf<PathDbExprStep>())
    }

    fun addPath(path: List<PathDbExprStep>) {
        var baseAlias = alias

        for (ofs in 1 .. path.size) {
            val subPath = path.subList(0, ofs)
            val pathKey = subPath.map { it.attr }
            var pathAlias = pathAliases[pathKey]

            if (pathAlias == null) {
                val tail = path[ofs - 1]
                val nextAlias = aliasGen.next()
                pathAlias = SqlFromPathAlias(nextAlias, subPath)
                joins.add(SqlFromJoin(baseAlias, tail.targetClass, nextAlias, tail.attr))
                pathAliases[pathKey] = pathAlias
            }

            check(pathAlias.path == subPath)
            baseAlias = pathAlias.alias
        }
    }

    fun build(): SqlFromClass {
        return SqlFromClass(alias, joins, pathAliases)
    }
}
