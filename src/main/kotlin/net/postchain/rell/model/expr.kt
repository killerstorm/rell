package net.postchain.rell.model

import net.postchain.rell.runtime.RtEnv
import net.postchain.rell.runtime.RtIntValue
import net.postchain.rell.runtime.RtTextValue
import net.postchain.rell.runtime.RtValue

sealed class RExpr(val type: RType) {
    abstract fun evaluate(env: RtEnv): RtValue
}

class RVarRef(type: RType, val offset: Int, val attr: RAttrib): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue {
        val value = env.get(offset)
        return value
    }
}

class RAtExpr(type: RType, val cls: RClass, val attrConditions: List<Pair<RAttrib, RExpr>>): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RBinOpExpr(type: RType, val op: String, val left: RExpr, val right: RExpr): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RStringLiteral(type: RType, literal: String): RExpr(type) {
    val value = RtTextValue(literal)
    override fun evaluate(env: RtEnv): RtValue = value
}

class RByteALiteral(type: RType, val literal: ByteArray): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RIntegerLiteral(type: RType, literal: Long): RExpr(type) {
    val value = RtIntValue(literal)
    override fun evaluate(env: RtEnv): RtValue = value
}

class RFunCallExpr(type: RType, val fname: String, val args: List<RExpr>): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RAttrExpr(val attr: RAttrib, val expr: RExpr) {
}

class RLambda(type: RType, val args: List<RAttrib>, val expr: RExpr): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RSelectClass(val rClass: RClass, val index: Int) {
    fun alias(): String = String.format("A%02d", index) //TODO do properly
}

class RSelectExpr(type: RType, val cls: RSelectClass, val where: DbExpr): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue {
        val builder = RtSqlBuilder()

        //TODO specify correct columns
        //TODO do not hardcode rowid
        builder.append("SELECT rowid FROM ")
        builder.append(cls.rClass.name)
        builder.append(" ")
        builder.append(cls.alias())

        val whereBuilder = RtSqlBuilder()
        where.toSql(whereBuilder)

        if (!whereBuilder.isEmpty()) {
            builder.append(" WHERE ")
            builder.append(whereBuilder)
        }

        val select = RtSelect(builder.build())
        select.execute(env)

        TODO("TODO")
    }
}

// TODO: RFuncall is probably unnecessary
class RFuncall(type: RType, val lambdaExpr: RLambda, val args: List<RExpr>): RExpr(type) {
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

class ClassDbExpr(type: RType, val cls: RSelectClass): DbExpr(type) {
    override fun toSql(bld: RtSqlBuilder) {
        bld.append(cls.alias())
    }
}

class AttributeDbExpr(type: RType, val base: DbExpr, val attrIndex: Int, val attrName: String): DbExpr(type) {
    override fun toSql(bld: RtSqlBuilder) {
        base.toSql(bld)
        bld.append(".")
        bld.append(attrName)
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

private class RtSelect(val rtSql: RtSql) {
    fun execute(env: RtEnv) {
        println("SQL: " + rtSql.sql)
        TODO("TODO")
    }
}
