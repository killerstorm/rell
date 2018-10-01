package net.postchain.rell.model

import com.google.common.base.Preconditions
import net.postchain.rell.runtime.*

sealed class RExpr(val type: RType) {
    abstract fun evaluate(env: RtEnv): RtValue
}

class RVarExpr(type: RType, val offset: Int, val attr: RAttrib): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue {
        val value = env.get(offset)
        return value
    }
}

class RBinOpExpr(type: RType, val op: String, val left: RExpr, val right: RExpr): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RStringLiteralExpr(type: RType, literal: String): RExpr(type) {
    val value = RtTextValue(literal)
    override fun evaluate(env: RtEnv): RtValue = value
}

class RByteArrayLiteralExpr(type: RType, val literal: ByteArray): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RIntegerLiteralExpr(type: RType, literal: Long): RExpr(type) {
    val value = RtIntValue(literal)
    override fun evaluate(env: RtEnv): RtValue = value
}

class RFunCallExpr(type: RType, val fname: String, val args: List<RExpr>): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RAttrExprPair(val attr: RAttrib, val expr: RExpr) {
}

class RLambdaExpr(type: RType, val args: List<RAttrib>, val expr: RExpr): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RAtClass(val rClass: RClass, val index: Int) {
    fun alias(): String = String.format("A%02d", index) //TODO do properly
}

class RAtExpr(type: RType, val cls: RAtClass, val where: DbExpr?): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue {
        val builder = RtSqlBuilder()

        //TODO specify correct columns
        //TODO do not hardcode rowid
        builder.append("SELECT ")
        builder.append(cls.alias())
        builder.append(".\"rowid\"")
        builder.append(" FROM \"")
        builder.append(cls.rClass.name)
        builder.append("\" ")
        builder.append(cls.alias())

        if (where != null) {
            builder.append(" WHERE ")
            where.toSql(builder)
        }

        val clsType = RInstanceRefType(cls.rClass)
        val select = RtSelect(builder.build(), listOf(clsType))
        val resultList = select.execute(env)

        val result = resultList.map {
            Preconditions.checkState(it.size == 1)
            it[0]
        }

        return RtListValue(type, result)
    }
}

// TODO: RFuncall is probably unnecessary
class RFuncall(type: RType, val lambdaExpr: RLambdaExpr, val args: List<RExpr>): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

sealed class DbBinaryOp(val sql: String)
object DbBinaryOpEq: DbBinaryOp("=")
object DbBinaryOpNe: DbBinaryOp("<>")
object DbBinaryOpLt: DbBinaryOp("<")
object DbBinaryOpGt: DbBinaryOp(">")
object DbBinaryOpLe: DbBinaryOp("<=")
object DbBinaryOpGe: DbBinaryOp(">=")
object DbBinaryOpPlus: DbBinaryOp("+")
object DbBinaryOpMinus: DbBinaryOp("-")
object DbBinaryOpMul: DbBinaryOp("*")
object DbBinaryOpDiv: DbBinaryOp("/")
object DbBinaryOpMod: DbBinaryOp("%")
object DbBinaryOpAnd: DbBinaryOp("AND")
object DbBinaryOpOr: DbBinaryOp("OR")

sealed class DbExpr(val type: RType) {
    internal abstract fun toSql(bld: RtSqlBuilder)
}

class InterpretedDbExpr(val expr: RExpr): DbExpr(expr.type) {
    override fun toSql(bld: RtSqlBuilder) {
        bld.append(expr)
    }
}

class ClassDbExpr(type: RType, val cls: RAtClass): DbExpr(type) {
    override fun toSql(bld: RtSqlBuilder) {
        bld.append(cls.alias())
    }
}

class AttributeDbExpr(type: RType, val base: DbExpr, val attrIndex: Int, val attrName: String): DbExpr(type) {
    override fun toSql(bld: RtSqlBuilder) {
        base.toSql(bld)
        bld.append(".\"")
        bld.append(attrName)
        bld.append("\"")
    }
}

class BinaryDbExpr(type: RType, val left: DbExpr, val right: DbExpr, val op: DbBinaryOp): DbExpr(type) {
    override fun toSql(bld: RtSqlBuilder) {
        bld.append("(")
        left.toSql(bld)
        bld.append(" ")
        bld.append(op.sql)
        bld.append(" ")
        right.toSql(bld)
        bld.append(")")
    }
}

internal class RtSqlBuilder {
    private val sqlBuf = StringBuilder()
    private val paramsBuf = mutableListOf<RExpr>()

    fun isEmpty(): Boolean = sqlBuf.isEmpty() && paramsBuf.isEmpty()

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

private class RtSelect(val rtSql: RtSql, val resultTypes: List<RType>) {
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
