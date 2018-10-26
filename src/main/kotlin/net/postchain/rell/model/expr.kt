package net.postchain.rell.model

import net.postchain.rell.runtime.*
import net.postchain.rell.sql.MAKE_ROWID_FUNCTION
import net.postchain.rell.sql.ROWID_COLUMN

abstract class RExpr(val type: RType) {
    abstract fun evaluate(env: RtEnv): RtValue
}

class RVarExpr(type: RType, val offset: Int, val name: String): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue {
        val value = env.getOpt(offset)
        if (value == null) {
            throw RtError("expr_var_uninit:$name", "Variable '$name' has not been initialized")
        }
        return value
    }
}

class RStringLiteralExpr(literal: String): RExpr(RTextType) {
    val value = RtTextValue(literal)
    override fun evaluate(env: RtEnv): RtValue = value
}

class RByteArrayLiteralExpr(literal: ByteArray): RExpr(RByteArrayType) {
    val value = RtByteArrayValue(literal)
    override fun evaluate(env: RtEnv): RtValue = value
}

class RIntegerLiteralExpr(literal: Long): RExpr(RIntegerType) {
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

class RTupleExpr(val tupleType: RTupleType, val exprs: List<RExpr>): RExpr(tupleType) {
    override fun evaluate(env: RtEnv): RtValue {
        val values = exprs.map { it.evaluate(env) }
        return RtTupleValue(tupleType, values)
    }
}

class RTupleCastExpr private constructor(
        type: RType,
        private val expr: RExpr,
        private val caster: ValueCaster
): RExpr(type)
{
    override fun evaluate(env: RtEnv): RtValue {
        val value = expr.evaluate(env)
        val value2 = caster.cast(value)
        return value2
    }

    companion object {
        fun create(srcExpr: RExpr, dstType: RType): RExpr? {
            val caster = createCaster(srcExpr.type, dstType)
            return if (caster == null) null else RTupleCastExpr(dstType, srcExpr, caster)
        }

        private fun createCaster(srcType: RType, dstType: RType): ValueCaster? {
            if (srcType == dstType) {
                return LeafValueCaster
            }

            if (!(srcType is RTupleType) || !(dstType is RTupleType)) {
                return null
            }

            if (srcType.fields.size != dstType.fields.size) {
                return null
            }

            val subCasters = mutableListOf<ValueCaster>()
            for (i in srcType.fields.indices) {
                val srcFieldType = srcType.fields[i].type
                val dstFieldType = dstType.fields[i].type
                val subCaster = createCaster(srcFieldType, dstFieldType)
                if (subCaster == null) {
                    return null
                }
                subCasters.add(subCaster)
            }

            return TupleValueCaster(dstType, subCasters)
        }

        private abstract class ValueCaster {
            abstract fun cast(v: RtValue): RtValue
        }

        private object LeafValueCaster: ValueCaster() {
            override fun cast(v: RtValue): RtValue = v
        }

        private class TupleValueCaster(val type: RTupleType, val casters: List<ValueCaster>): ValueCaster() {
            override fun cast(v: RtValue): RtValue {
                val values = v.asTuple().withIndex().map { (i, subv) -> casters[i].cast(subv) }
                return RtTupleValue(type, values)
            }
        }
    }
}

class RListExpr(type: RListType, val exprs: List<RExpr>): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue {
        val values = exprs.map { it.evaluate(env) }
        return RtListValue(type, values)
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
