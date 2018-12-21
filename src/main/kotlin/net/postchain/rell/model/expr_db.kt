package net.postchain.rell.model

import net.postchain.rell.runtime.RtCallFrame
import net.postchain.rell.runtime.RtValue
import net.postchain.rell.sql.ROWID_COLUMN
import java.sql.PreparedStatement

sealed class DbBinaryOp(val code: String, val sql: String)
object DbBinaryOp_Eq: DbBinaryOp("==", "=")
object DbBinaryOp_Ne: DbBinaryOp("!=", "<>")
object DbBinaryOp_Lt: DbBinaryOp("<", "<")
object DbBinaryOp_Gt: DbBinaryOp(">", ">")
object DbBinaryOp_Le: DbBinaryOp("<=", "<=")
object DbBinaryOp_Ge: DbBinaryOp(">=", ">=")
object DbBinaryOp_Add: DbBinaryOp("+", "+")
object DbBinaryOp_Sub: DbBinaryOp("-", "-")
object DbBinaryOp_Mul: DbBinaryOp("*", "*")
object DbBinaryOp_Div: DbBinaryOp("/", "/")
object DbBinaryOp_Mod: DbBinaryOp("%", "%")
object DbBinaryOp_And: DbBinaryOp("and", "AND")
object DbBinaryOp_Or: DbBinaryOp("or", "OR")
object DbBinaryOp_Concat: DbBinaryOp("+", "||")

sealed class DbUnaryOp(val code: String, val sql: String)
object DbUnaryOp_Minus: DbUnaryOp("-", "-")
object DbUnaryOp_Not: DbUnaryOp("not", "NOT")

data class SqlTableAlias(val cls: RClass, val str: String)

class SqlFromInfo(val classes: List<SqlFromClass>)
class SqlFromClass(val alias: SqlTableAlias, val joins: List<SqlFromJoin>)
class SqlFromJoin(val baseAlias: SqlTableAlias, val attr: String, val alias: SqlTableAlias)

class SqlGenContext(classes: List<RAtClass>, private val parameters: List<RtValue>) {
    private var aliasCtr = 0
    private val clsAliasMap = mutableMapOf<RAtClass, ClassAliasTbl>()
    private val aliasTblMap = mutableMapOf<SqlTableAlias, ClassAliasTbl>()

    init {
        classes.withIndex().forEach { (i, cls) -> check(cls.index == i) }
        for (cls in classes) {
            getClassAlias(cls)
        }
    }

    fun getParameter(index: Int): RtValue {
        return parameters[index]
    }

    fun getClassAlias(cls: RAtClass): SqlTableAlias {
        val tbl = clsAliasMap.computeIfAbsent(cls) {
            val tbl = ClassAliasTbl(nextAlias(cls.rClass))
            aliasTblMap[tbl.alias] = tbl
            tbl
        }
        return tbl.alias
    }

    fun getRelAlias(baseAlias: SqlTableAlias, rel: String, cls: RClass): SqlTableAlias {
        val tbl = aliasTblMap.getValue(baseAlias)
        val map = tbl.subAliases.computeIfAbsent(baseAlias) { mutableMapOf() }
        return map.computeIfAbsent(rel) {
            val alias = nextAlias(cls)
            aliasTblMap[alias] = tbl
            alias
        }
    }

    fun getFromInfo(): SqlFromInfo {
        val classes = clsAliasMap.entries.map { (cls, tbl) ->
            val joins = tbl.subAliases.entries.flatMap { (alias, map) ->
                map.entries.map { (attr, alias2) -> SqlFromJoin(alias, attr, alias2) }
            }
            SqlFromClass(tbl.alias, joins)
        }
        return SqlFromInfo(classes)
    }

    private fun nextAlias(cls: RClass) = SqlTableAlias(cls, String.format("A%02d", aliasCtr++))

    private class ClassAliasTbl(val alias: SqlTableAlias) {
        val subAliases = mutableMapOf<SqlTableAlias, MutableMap<String, SqlTableAlias>>()
    }
}

sealed class DbExpr(val type: RType) {
    open fun implicitName(): String? = null
    abstract fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder)
}

class InterpretedDbExpr(val expr: RExpr): DbExpr(expr.type) {
    override fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder) {
        bld.append(expr)
    }
}

class BinaryDbExpr(type: RType, val op: DbBinaryOp, val left: DbExpr, val right: DbExpr): DbExpr(type) {
    override fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder) {
        bld.append("(")
        left.toSql(ctx, bld)
        bld.append(" ")
        bld.append(op.sql)
        bld.append(" ")
        right.toSql(ctx, bld)
        bld.append(")")
    }
}

class UnaryDbExpr(type: RType, val op: DbUnaryOp, val expr: DbExpr): DbExpr(type) {
    override fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder) {
        bld.append("(")
        bld.append(op.sql)
        bld.append(" ")
        expr.toSql(ctx, bld)
        bld.append(")")
    }
}

sealed class TableDbExpr(val rClass: RClass): DbExpr(RInstanceRefType(rClass)) {
    abstract fun alias(ctx: SqlGenContext): SqlTableAlias

    final override fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder) {
        val alias = alias(ctx)
        bld.appendColumn(alias, ROWID_COLUMN)
    }
}

class ClassDbExpr(val cls: RAtClass): TableDbExpr(cls.rClass) {
    override fun alias(ctx: SqlGenContext) = ctx.getClassAlias(cls)
}

class RelDbExpr(val base: TableDbExpr, val attr: RAttrib, targetClass: RClass): TableDbExpr(targetClass) {
    override fun implicitName(): String? {
        return if (base is ClassDbExpr) attr.name else null
    }

    override fun alias(ctx: SqlGenContext): SqlTableAlias {
        val baseAlias = base.alias(ctx)
        return ctx.getRelAlias(baseAlias, attr.name, rClass)
    }
}

class AttrDbExpr(val base: TableDbExpr, val attr: RAttrib): DbExpr(attr.type) {
    override fun implicitName(): String? {
        return if (base is ClassDbExpr) attr.name else null
    }

    override fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder) {
        val alias = base.alias(ctx)
        bld.appendColumn(alias, attr.name)
    }
}

class ParameterDbExpr(type: RType, val index: Int): DbExpr(type) {
    override fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder) {
        val value = ctx.getParameter(index)
        bld.append(type, value)
    }
}

sealed class DbSysFunction(val name: String) {
    abstract fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder, args: List<DbExpr>)
}

sealed class DbSysFunction_Simple(name: String, val sql: String): DbSysFunction(name) {
    override fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder, args: List<DbExpr>) {
        bld.append(sql)
        bld.append("(")
        bld.append(args, ", ") {
            it.toSql(ctx, bld)
        }
        bld.append(")")
    }
}

sealed class DbSysFunction_Cast(name: String, val type: String): DbSysFunction(name) {
    override fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder, args: List<DbExpr>) {
        check(args.size == 1)
        bld.append("((")
        args[0].toSql(ctx, bld)
        bld.append(")::$type)")
    }
}

object DbSysFunction_Int_Str: DbSysFunction_Cast("int.str", "TEXT")
object DbSysFunction_Abs: DbSysFunction_Simple("abs", "ABS")
object DbSysFunction_Min: DbSysFunction_Simple("min", "LEAST")
object DbSysFunction_Max: DbSysFunction_Simple("max", "GREATEST")
object DbSysFunction_Text_Size: DbSysFunction_Simple("text.len", "LENGTH")
object DbSysFunction_Text_UpperCase: DbSysFunction_Simple("text.len", "UPPER")
object DbSysFunction_Text_LowerCase: DbSysFunction_Simple("text.len", "LOWER")
object DbSysFunction_ByteArray_Size: DbSysFunction_Simple("byte_array.len", "LENGTH")
object DbSysFunction_Json: DbSysFunction_Cast("json", "JSONB")
object DbSysFunction_Json_Str: DbSysFunction_Cast("json.str", "TEXT")
object DbSysFunction_ToString: DbSysFunction_Cast("toString", "TEXT")

class CallDbExpr(type: RType, val fn: DbSysFunction, val args: List<DbExpr>): DbExpr(type) {
    override fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder) = fn.toSql(ctx, bld, args)
}

sealed class RtSqlParam {
    abstract fun type(): RType
    abstract fun evaluate(frame: RtCallFrame): RtValue
}

class RtSqlParam_Expr(private val expr: RExpr): RtSqlParam() {
    override fun type() = expr.type
    override fun evaluate(frame: RtCallFrame) = expr.evaluate(frame)
}

class RtSqlParam_Value(private val type: RType, private val value: RtValue): RtSqlParam() {
    override fun type() = type
    override fun evaluate(frame: RtCallFrame) = value
}

class RtSqlBuilder {
    private val sqlBuf = StringBuilder()
    private val paramsBuf = mutableListOf<RtSqlParam>()

    fun <T> append(list: List<T>, sep: String, block: (T) -> Unit) {
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

    fun append(param: RExpr) {
        sqlBuf.append("?")
        paramsBuf.add(RtSqlParam_Expr(param))
    }

    fun append(type: RType, value: RtValue) {
        sqlBuf.append("?")
        paramsBuf.add(RtSqlParam_Value(type, value))
    }

    fun append(sql: RtSql) {
        sqlBuf.append(sql.sql)
        paramsBuf.addAll(sql.params)
    }

    fun listBuilder(sep: String = ", "): RtSqlListBuilder = RtSqlListBuilder(this, sep)

    fun build(): RtSql = RtSql(sqlBuf.toString(), paramsBuf.toList())
}

class RtSqlListBuilder(private val builder: RtSqlBuilder, private val sep: String) {
    private var first = true

    fun nextItem() {
        if (!first) {
            builder.append(sep)
        }
        first = false
    }
}

class RtSql(val sql: String, val params: List<RtSqlParam>) {
    fun calcArgs(frame: RtCallFrame): RtSqlArgs {
        val types = params.map { it.type() }
        val values = params.map { it.evaluate(frame) }
        return RtSqlArgs(types, values)
    }
}

class RtSqlArgs(val types: List<RType>, val values: List<RtValue>) {
    fun bind(stmt: PreparedStatement) {
        for (i in values.indices) {
            val type = types[i]
            val arg = values[i]
            type.toSql(stmt, i + 1, arg)
        }
    }
}

class RtSelect(val rtSql: RtSql, val resultTypes: List<RType>) {
    fun execute(frame: RtCallFrame): List<Array<RtValue>> {
        val result = mutableListOf<Array<RtValue>>()

        val args = rtSql.calcArgs(frame)

        frame.entCtx.modCtx.globalCtx.sqlExec.executeQuery(rtSql.sql, args::bind) { rs ->
            val list = mutableListOf<RtValue>()
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

class RtUpdate(val rtSql: RtSql) {
    fun execute(frame: RtCallFrame) {
        val args = rtSql.calcArgs(frame)
        frame.entCtx.modCtx.globalCtx.sqlExec.execute(rtSql.sql, args::bind)
    }
}
