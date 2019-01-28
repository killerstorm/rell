package net.postchain.rell.model

import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_Value
import java.sql.PreparedStatement

data class SqlTableAlias(val cls: R_Class, val str: String)
class SqlTableJoin(val attr: R_Attrib, val alias: SqlTableAlias)

class SqlFromInfo(val classes: List<SqlFromClass>)
class SqlFromClass(val alias: SqlTableAlias, val joins: List<SqlFromJoin>)
class SqlFromJoin(val baseAlias: SqlTableAlias, val attr: String, val alias: SqlTableAlias)

class SqlGenContext(classes: List<R_AtClass>, private val parameters: List<Rt_Value>) {
    private var aliasCtr = 0
    private val clsAliasMap = mutableMapOf<R_AtClass, ClassAliasTbl>()
    private val aliasTblMap = mutableMapOf<SqlTableAlias, ClassAliasTbl>()

    init {
        classes.withIndex().forEach { (i, cls) -> check(cls.index == i) }
        for (cls in classes) {
            getClassAlias(cls)
        }
    }

    fun getParameter(index: Int): Rt_Value {
        return parameters[index]
    }

    fun getClassAlias(cls: R_AtClass): SqlTableAlias {
        val tbl = clsAliasMap.computeIfAbsent(cls) {
            val tbl = ClassAliasTbl(nextAlias(cls.rClass))
            aliasTblMap[tbl.alias] = tbl
            tbl
        }
        return tbl.alias
    }

    fun getRelAlias(baseAlias: SqlTableAlias, rel: R_Attrib, cls: R_Class): SqlTableAlias {
        val tbl = aliasTblMap.getValue(baseAlias)
        val map = tbl.subAliases.computeIfAbsent(baseAlias) { mutableMapOf() }
        val join = map.computeIfAbsent(rel.name) {
            val alias = nextAlias(cls)
            aliasTblMap[alias] = tbl
            SqlTableJoin(rel, alias)
        }
        return join.alias
    }

    fun getFromInfo(): SqlFromInfo {
        val classes = clsAliasMap.entries.map { (cls, tbl) ->
            val joins = tbl.subAliases.entries.flatMap { (alias, map) ->
                map.values.map { tblJoin -> SqlFromJoin(alias, tblJoin.attr.sqlMapping, tblJoin.alias) }
            }
            SqlFromClass(tbl.alias, joins)
        }
        return SqlFromInfo(classes)
    }

    private fun nextAlias(cls: R_Class) = SqlTableAlias(cls, String.format("A%02d", aliasCtr++))

    private class ClassAliasTbl(val alias: SqlTableAlias) {
        val subAliases = mutableMapOf<SqlTableAlias, MutableMap<String, SqlTableJoin>>()
    }
}

sealed class SqlParam {
    abstract fun type(): R_Type
    abstract fun evaluate(frame: Rt_CallFrame): Rt_Value
}

class SqlParam_Expr(private val expr: R_Expr): SqlParam() {
    override fun type() = expr.type
    override fun evaluate(frame: Rt_CallFrame) = expr.evaluate(frame)
}

class SqlParam_Value(private val type: R_Type, private val value: Rt_Value): SqlParam() {
    override fun type() = type
    override fun evaluate(frame: Rt_CallFrame) = value
}

class SqlBuilder {
    private val sqlBuf = StringBuilder()
    private val paramsBuf = mutableListOf<SqlParam>()

    fun <T> append(list: Collection<T>, sep: String, block: (T) -> Unit) {
        var s = ""
        for (t in list) {
            append(s)
            block(t)
            s = sep
        }
    }

    fun appendName(name: String) {
        append("\"")
        append(name)
        append("\"")
    }

    fun appendColumn(alias: SqlTableAlias, column: String) {
        appendColumn(alias.str, column)
    }

    fun appendColumn(alias: String, column: String) {
        append(alias)
        append(".")
        appendName(column)
    }

    fun append(sql: String) {
        sqlBuf.append(sql)
    }

    fun append(param: R_Expr) {
        sqlBuf.append("?")
        paramsBuf.add(SqlParam_Expr(param))
    }

    fun append(type: R_Type, value: Rt_Value) {
        sqlBuf.append("?")
        paramsBuf.add(SqlParam_Value(type, value))
    }

    fun append(sql: ParameterizedSql) {
        sqlBuf.append(sql.sql)
        paramsBuf.addAll(sql.params)
    }

    fun listBuilder(sep: String = ", "): SqlListBuilder = SqlListBuilder(this, sep)

    fun build(): ParameterizedSql = ParameterizedSql(sqlBuf.toString(), paramsBuf.toList())
}

class SqlListBuilder(private val builder: SqlBuilder, private val sep: String) {
    private var first = true

    fun nextItem() {
        if (!first) {
            builder.append(sep)
        }
        first = false
    }
}

class ParameterizedSql(val sql: String, val params: List<SqlParam>) {
    fun calcArgs(frame: Rt_CallFrame): SqlArgs {
        val types = params.map { it.type() }
        val values = params.map { it.evaluate(frame) }
        return SqlArgs(types, values)
    }
}

class SqlArgs(val types: List<R_Type>, val values: List<Rt_Value>) {
    fun bind(stmt: PreparedStatement) {
        for (i in values.indices) {
            val type = types[i]
            val arg = values[i]
            type.toSql(stmt, i + 1, arg)
        }
    }
}

class SqlSelect(val pSql: ParameterizedSql, val resultTypes: List<R_Type>) {
    fun execute(frame: Rt_CallFrame): List<Array<Rt_Value>> {
        val result = mutableListOf<Array<Rt_Value>>()

        val args = pSql.calcArgs(frame)

        frame.entCtx.modCtx.globalCtx.sqlExec.executeQuery(pSql.sql, args::bind) { rs ->
            val list = mutableListOf<Rt_Value>()
            for (i in resultTypes.indices) {
                val type = resultTypes[i]
                val value = type.fromSql(rs, i + 1)
                list.add(value)
            }
            result.add(list.toTypedArray())
        }

        return result
    }
}

class SqlUpdate(val pSql: ParameterizedSql) {
    fun execute(frame: Rt_CallFrame) {
        val args = pSql.calcArgs(frame)
        frame.entCtx.modCtx.globalCtx.sqlExec.execute(pSql.sql, args::bind)
    }
}
