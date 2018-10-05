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
        check(row.size == type.elementTypes.size) { "row.size == ${row.size}, not ${type.elementTypes.size}" }
        return RtTupleValue(type, row.toList())
    }
}

class RAtExpr(
        type: RType,
        val classes: List<RAtClass>,
        val where: DbExpr?,
        val all: Boolean,
        val rowType: RAtExprRowType
): RExpr(type)
{
    init {
        for (i in classes.indices) {
            check(classes[i].index == i)
        }
    }

    override fun evaluate(env: RtEnv): RtValue {
        val builder = RtSqlBuilder()

        val fromInfo = buildFromInfo()
        val ctx = SqlGenContext(fromInfo)

        builder.append("SELECT ")

        builder.append(fromInfo.classes, ", ") {
            builder.appendName(it.alias, ROWID_COLUMN)
        }

        appendFrom(builder, fromInfo)

        if (where != null) {
            builder.append(" WHERE ")
            where.toSql(ctx, builder)
        }

        builder.append(" ORDER BY ")
        builder.append(fromInfo.classes, ", ") {
            builder.appendName(it.alias, ROWID_COLUMN)
        }

        val resultTypes = classes.map { RInstanceRefType(it.rClass) }
        val select = RtSelect(builder.build(), resultTypes)
        val records = select.execute(env)

        val result = decodeResult(records)
        return result
    }

    private fun buildFromInfo(): SqlFromInfo {
        val aliasGen = SqlAliasGenerator()
        val builders = classes.map { SqlFromClassBuilder(aliasGen) }

        if (where != null) {
            where.visit {
                if (it is PathDbExpr) {
                    val builder = builders[it.cls.index]
                    builder.addPath(it.path)
                }
            }
        }

        val fromClasses = builders.map { it.build() }
        return SqlFromInfo(fromClasses)
    }

    private fun appendFrom(builder: RtSqlBuilder, fromInfo: SqlFromInfo) {
        builder.append(" FROM ")

        builder.append(classes.indices.toList(), ", ") {
            builder.appendName(classes[it].rClass.name)
            builder.append(" ")
            builder.append(fromInfo.classes[it].alias)

            for (join in fromInfo.classes[it].joins) {
                builder.append(" INNER JOIN ")
                builder.appendName(join.cls.name)
                builder.append(" ")
                builder.appendName(join.alias)
                builder.append(" ON ")
                builder.appendName(join.baseAlias, join.attr)
                builder.append(" = ")
                builder.appendName(join.alias, ROWID_COLUMN)
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

class SqlFromInfo(val classes: List<SqlFromClass>)
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
