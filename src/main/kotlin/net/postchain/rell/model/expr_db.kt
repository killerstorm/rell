package net.postchain.rell.model

import net.postchain.rell.runtime.RtEnv
import net.postchain.rell.runtime.RtValue
import net.postchain.rell.sql.ROWID_COLUMN
import java.lang.IllegalStateException
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

class SqlGenContext(private val fromInfo: SqlFromInfo) {
    fun getPathAlias(cls: RAtClass, path: List<PathDbExprStep>): String {
        val fromCls = fromInfo.classes[cls.index]
        val pathKey = path.map { it.attr }
        val pathAlias = fromCls.pathAliases[pathKey]
        if (pathAlias == null) {
            throw IllegalStateException("Join not found for path $pathKey")
        } else if (pathAlias.path != path) {
            throw IllegalStateException("Join path differs: ${pathAlias.path} instead of ${path}")
        }
        return pathAlias.alias
    }
}

sealed class DbExpr(val type: RType) {
    internal open fun implicitName(): String? = null
    internal open fun visit(visitor: (DbExpr) -> Unit) = visitor(this)
    internal abstract fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder)
}

class InterpretedDbExpr(val expr: RExpr): DbExpr(expr.type) {
    override fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder) {
        bld.append(expr)
    }
}

class BinaryDbExpr(type: RType, val op: DbBinaryOp, val left: DbExpr, val right: DbExpr): DbExpr(type) {
    override fun visit(visitor: (DbExpr) -> Unit) {
        left.visit(visitor)
        right.visit(visitor)
        super.visit(visitor)
    }

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
    override fun visit(visitor: (DbExpr) -> Unit) {
        expr.visit(visitor)
        super.visit(visitor)
    }

    override fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder) {
        bld.append("(")
        bld.append(op.sql)
        bld.append(" ")
        expr.toSql(ctx, bld)
        bld.append(")")
    }
}

class PathDbExprStep(val attr: String, val targetClass: RClass) {
    override fun equals(other: Any?): Boolean {
        return other is PathDbExprStep && attr == other.attr && targetClass === other.targetClass
    }
}

class PathDbExpr(type: RType, val cls: RAtClass, val path: List<PathDbExprStep>, val attr: String?): DbExpr(type) {
    override fun implicitName(): String? {
        if (path.size == 0 && attr != null) {
            return attr
        } else if (path.size == 1 && attr == null) {
            return path[0].attr
        } else {
            return null
        }
    }

    override fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder) {
        val alias = ctx.getPathAlias(cls, path)
        val field = if (attr != null) attr else ROWID_COLUMN //TODO do not hardcode rowid
        bld.appendColumn(alias, field)
    }
}

internal class RtSqlBuilder {
    private val sqlBuf = StringBuilder()
    private val paramsBuf = mutableListOf<RExpr>()

    fun isEmpty(): Boolean = sqlBuf.isEmpty() && paramsBuf.isEmpty()

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
        paramsBuf.add(param)
    }

    fun build(): RtSql = RtSql(sqlBuf.toString(), paramsBuf.toList())
}

internal class RtSql(val sql: String, val params: List<RExpr>) {
    fun calcArgs(env: RtEnv): RtSqlArgs {
        val types = params.map { it.type }
        val values = params.map { it.evaluate(env) }
        return RtSqlArgs(types, values)
    }
}

internal class RtSqlArgs(val types: List<RType>, val values: List<RtValue>) {
    fun bind(stmt: PreparedStatement) {
        for (i in values.indices) {
            val type = types[i]
            val arg = values[i]
            type.toSql(stmt, i + 1, arg)
        }
    }
}

internal class RtSelect(val rtSql: RtSql, val resultTypes: List<RType>) {
    fun execute(env: RtEnv): List<Array<RtValue>> {
        val result = mutableListOf<Array<RtValue>>()

        val args = rtSql.calcArgs(env)

        env.sqlExec.executeQuery(rtSql.sql, args::bind) { rs ->
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

internal class RtUpdate(val rtSql: RtSql) {
    fun execute(env: RtEnv) {
        val args = rtSql.calcArgs(env)
        env.sqlExec.execute(rtSql.sql, args::bind)
    }
}
