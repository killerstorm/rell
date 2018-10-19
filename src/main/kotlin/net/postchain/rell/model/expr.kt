package net.postchain.rell.model

import net.postchain.rell.runtime.*
import net.postchain.rell.sql.MAKE_ROWID_FUNCTION
import net.postchain.rell.sql.ROWID_COLUMN

abstract class RExpr(val type: RType) {
    abstract fun evaluate(env: RtEnv): RtValue
}

class RVarExpr(type: RType, val offset: Int): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue {
        val value = env.get(offset)
        return value
    }
}

class RStringLiteralExpr(type: RType, literal: String): RExpr(type) {
    val value = RtTextValue(literal)
    override fun evaluate(env: RtEnv): RtValue = value
}

class RByteArrayLiteralExpr(type: RType, literal: ByteArray): RExpr(type) {
    val value = RtByteArrayValue(literal)
    override fun evaluate(env: RtEnv): RtValue = value
}

class RIntegerLiteralExpr(type: RType, literal: Long): RExpr(type) {
    val value = RtIntValue(literal)
    override fun evaluate(env: RtEnv): RtValue = value
}

class RBooleanLiteralExpr(literal: Boolean): RExpr(RBooleanType) {
    val value = RtBooleanValue(literal)
    override fun evaluate(env: RtEnv): RtValue = value
}

class RTupleFieldExpr(type: RType, val baseExpr: RExpr, val fieldIndex: Int): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue {
        val baseValue = baseExpr.evaluate(env)
        val tupleValue = baseValue as RtTupleValue
        return tupleValue.elements[fieldIndex]
    }
}

class RLambdaExpr(type: RType, val args: List<RAttrib>, val expr: RExpr): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue = TODO("TODO")
}

class RLookupExpr(type: RType, val base: RExpr, val expr: RExpr): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue {
        val baseValue = base.evaluate(env)
        val keyValue = expr.evaluate(env)

        val list = baseValue.asList()
        val index = keyValue.asInteger()

        if (index < 0 || index >= list.size) {
            throw RtError("expr_lookup_index:${list.size}:$index", "Index out of bounds: $index (size ${list.size})")
        }

        return list[index.toInt()]
    }
}

class RCreateExprAttr(val attr: RAttrib, val expr: RExpr)

class RCreateExpr(type: RType, val rClass: RClass, val attrs: List<RCreateExprAttr>): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue {
        env.checkDbUpdateAllowed()
        val rtSql = buildSql()
        val rtSel = RtSelect(rtSql, listOf(type))
        val res = rtSel.execute(env)
        check(res.size == 1)
        check(res[0].size == 1)
        return res[0][0]
    }

    private fun buildSql(): RtSql {
        val builder = RtSqlBuilder()

        builder.append("INSERT INTO ")
        builder.appendName(rClass.name)

        builder.append("(")
        builder.appendName(ROWID_COLUMN)
        builder.append(", ")
        builder.append(attrs, ", ") { attr ->
            builder.appendName(attr.attr.name)
        }
        builder.append(")")

        builder.append(" VALUES (")
        builder.append("$MAKE_ROWID_FUNCTION(), ")
        builder.append(attrs, ", ") { attr ->
            builder.append(attr.expr)
        }
        builder.append(")")

        builder.append(" RETURNING ")
        builder.appendName(ROWID_COLUMN)

        return builder.build()
    }
}
