package net.postchain.rell.model

import net.postchain.rell.runtime.RtEnv
import net.postchain.rell.runtime.RtValue
import net.postchain.rell.sql.ROWID_COLUMN
import java.lang.IllegalStateException

sealed class DbBinaryOp(val code: String, val sql: String)
object DbBinaryOpEq: DbBinaryOp("==", "=")
object DbBinaryOpNe: DbBinaryOp("!=", "<>")
object DbBinaryOpLt: DbBinaryOp("<", "<")
object DbBinaryOpGt: DbBinaryOp(">", ">")
object DbBinaryOpLe: DbBinaryOp("<=", "<=")
object DbBinaryOpGe: DbBinaryOp(">=", ">=")
object DbBinaryOpPlus: DbBinaryOp("+", "+")
object DbBinaryOpMinus: DbBinaryOp("-", "-")
object DbBinaryOpMul: DbBinaryOp("*", "*")
object DbBinaryOpDiv: DbBinaryOp("/", "/")
object DbBinaryOpMod: DbBinaryOp("%", "%")
object DbBinaryOpAnd: DbBinaryOp("and", "AND")
object DbBinaryOpOr: DbBinaryOp("or", "OR")

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
    internal open fun visit(visitor: (DbExpr) -> Unit) = visitor(this)
    internal abstract fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder)
}

class InterpretedDbExpr(val expr: RExpr): DbExpr(expr.type) {
    override fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder) {
        bld.append(expr)
    }
}

class BinaryDbExpr(type: RType, val left: DbExpr, val right: DbExpr, val op: DbBinaryOp): DbExpr(type) {
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

class PathDbExprStep(val attr: String, val targetClass: RClass) {
    override fun equals(other: Any?): Boolean {
        return other is PathDbExprStep && attr == other.attr && targetClass === other.targetClass
    }
}

class PathDbExpr(type: RType, val cls: RAtClass, val path: List<PathDbExprStep>, val attr: String?): DbExpr(type) {
    override fun toSql(ctx: SqlGenContext, bld: RtSqlBuilder) {
        val alias = ctx.getPathAlias(cls, path)
        val field = if (attr != null) attr else ROWID_COLUMN //TODO do not hardcode rowid
        bld.appendName(alias, field)
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

    fun appendName(name1: String, name2: String) {
        appendName(name1)
        append(".")
        appendName(name2)
    }

    fun append(sql: String) {
        sqlBuf.append(sql)
    }

    fun append(param: RExpr) {
        sqlBuf.append("?")
        paramsBuf.add(param)
    }

    fun append(builder: RtSqlBuilder) {
        sqlBuf.append(builder.sqlBuf)
        paramsBuf.addAll(builder.paramsBuf)
    }

    fun build(): RtSql = RtSql(sqlBuf.toString(), paramsBuf.toList())
}

internal class RtSql(val sql: String, val params: List<RExpr>)

internal class RtSelect(val rtSql: RtSql, val resultTypes: List<RType>) {
    fun execute(env: RtEnv): List<Array<RtValue>> {
        val args = rtSql.params.map { it.evaluate(env) }

        val result = mutableListOf<Array<RtValue>>()

        env.sqlExec.executeQuery(rtSql.sql,
                { stmt ->
                    for (i in args.indices) {
                        val expr = rtSql.params[i]
                        val arg = args[i]
                        expr.type.toSql(stmt, i + 1, arg)
                    }
                },
                { rs ->
                    val list = mutableListOf<RtValue>()
                    for (i in resultTypes.indices) {
                        val type = resultTypes[i]
                        val value = type.fromSql(rs, i + 1)
                        list.add(value)
                    }
                    result.add(list.toTypedArray())
                }
        )

        return result
    }
}
