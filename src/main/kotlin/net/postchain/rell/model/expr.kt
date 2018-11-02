package net.postchain.rell.model

import net.postchain.rell.runtime.*
import net.postchain.rell.sql.MAKE_ROWID_FUNCTION
import net.postchain.rell.sql.ROWID_COLUMN

abstract class RExpr(val type: RType) {
    abstract fun evaluate(frame: RtCallFrame): RtValue
}

sealed class RDestinationExpr(type: RType): RExpr(type) {
    abstract fun evaluateRef(frame: RtCallFrame): RtValueRef

    override fun evaluate(frame: RtCallFrame): RtValue {
        val ref = evaluateRef(frame)
        val value = ref.get()
        return value
    }
}

class RVarExpr(type: RType, val ptr: RVarPtr, val name: String): RDestinationExpr(type) {
    override fun evaluateRef(frame: RtCallFrame): RtValueRef {
        return RtVarValueRef(ptr, name, frame)
    }

    private class RtVarValueRef(val ptr: RVarPtr, val name: String, val frame: RtCallFrame): RtValueRef() {
        override fun get(): RtValue {
            val value = frame.getOpt(ptr)
            if (value == null) {
                throw RtError("expr_var_uninit:$name", "Variable '$name' has not been initialized")
            }
            return value
        }

        override fun set(value: RtValue) {
            frame.set(ptr, value, true)
        }
    }
}

class RStringLiteralExpr(literal: String): RExpr(RTextType) {
    val value = RtTextValue(literal)
    override fun evaluate(frame: RtCallFrame): RtValue = value
}

class RByteArrayLiteralExpr(literal: ByteArray): RExpr(RByteArrayType) {
    val value = RtByteArrayValue(literal)
    override fun evaluate(frame: RtCallFrame): RtValue = value
}

class RIntegerLiteralExpr(literal: Long): RExpr(RIntegerType) {
    val value = RtIntValue(literal)
    override fun evaluate(frame: RtCallFrame): RtValue = value
}

class RBooleanLiteralExpr(literal: Boolean): RExpr(RBooleanType) {
    val value = RtBooleanValue(literal)
    override fun evaluate(frame: RtCallFrame): RtValue = value
}

class RTupleFieldExpr(type: RType, val baseExpr: RExpr, val fieldIndex: Int): RExpr(type) {
    override fun evaluate(frame: RtCallFrame): RtValue {
        val baseValue = baseExpr.evaluate(frame)
        val tupleValue = baseValue as RtTupleValue
        return tupleValue.elements[fieldIndex]
    }
}

class RLambdaExpr(type: RType, val args: List<RAttrib>, val expr: RExpr): RExpr(type) {
    override fun evaluate(frame: RtCallFrame): RtValue = TODO("TODO")
}

class RTupleExpr(val tupleType: RTupleType, val exprs: List<RExpr>): RExpr(tupleType) {
    override fun evaluate(frame: RtCallFrame): RtValue {
        val values = exprs.map { it.evaluate(frame) }
        return RtTupleValue(tupleType, values)
    }
}

class RTupleCastExpr private constructor(
        type: RType,
        private val expr: RExpr,
        private val caster: ValueCaster
): RExpr(type)
{
    override fun evaluate(frame: RtCallFrame): RtValue {
        val value = expr.evaluate(frame)
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

class RListLiteralExpr(type: RListType, val exprs: List<RExpr>): RExpr(type) {
    override fun evaluate(frame: RtCallFrame): RtValue {
        val list = MutableList(exprs.size) { exprs[it].evaluate(frame) }
        return RtListValue(type, list)
    }
}

class RMapLiteralExpr(type: RMapType, val entries: List<Pair<RExpr, RExpr>>): RExpr(type) {
    override fun evaluate(frame: RtCallFrame): RtValue {
        val map = mutableMapOf<RtValue, RtValue>()
        for ((keyExpr, valueExpr) in entries) {
            val key = keyExpr.evaluate(frame)
            val value = valueExpr.evaluate(frame)
            if (key in map) {
                throw RtError("expr_map_dupkey:${key.toStrictString()}", "Duplicated map key: $key")
            }
            map.put(key, value)
        }
        return RtMapValue(type, map)
    }
}

class RListExpr(type: RType, val arg: RExpr?): RExpr(type) {
    override fun evaluate(frame: RtCallFrame): RtValue {
        val list = mutableListOf<RtValue>()
        if (arg != null) {
            val c = arg.evaluate(frame).asCollection()
            list.addAll(c)
        }
        return RtListValue(type, list)
    }
}

class RSetExpr(type: RType, val arg: RExpr?): RExpr(type) {
    override fun evaluate(frame: RtCallFrame): RtValue {
        val set = mutableSetOf<RtValue>()
        if (arg != null) {
            val c = arg.evaluate(frame).asCollection()
            set.addAll(c)
        }
        return RtSetValue(type, set)
    }
}

class RMapExpr(type: RType, val arg: RExpr?): RExpr(type) {
    override fun evaluate(frame: RtCallFrame): RtValue {
        val map = mutableMapOf<RtValue, RtValue>()
        if (arg != null) {
            val m = arg.evaluate(frame).asMap()
            map.putAll(m)
        }
        return RtMapValue(type, map)
    }
}

class RListLookupExpr(type: RType, val base: RExpr, val expr: RExpr): RDestinationExpr(type) {
    override fun evaluateRef(frame: RtCallFrame): RtValueRef {
        val baseValue = base.evaluate(frame)
        val indexValue = expr.evaluate(frame)
        val list = baseValue.asList()
        val index = indexValue.asInteger()

        if (index < 0 || index >= list.size) {
            throw RtError("expr_list_lookup_index:${list.size}:$index",
                    "List index out of bounds: $index (size ${list.size})")
        }

        return RtListValueRef(list, index.toInt())
    }

    private class RtListValueRef(val list: MutableList<RtValue>, val index: Int): RtValueRef() {
        override fun get(): RtValue {
            return list[index]
        }

        override fun set(value: RtValue) {
            list[index] = value
        }
    }
}

class RMapLookupExpr(type: RType, val base: RExpr, val expr: RExpr): RDestinationExpr(type) {
    override fun evaluateRef(frame: RtCallFrame): RtValueRef {
        val baseValue = base.evaluate(frame)
        val keyValue = expr.evaluate(frame)
        val map = baseValue.asMap()
        return RtMapValueRef(map, keyValue)
    }

    private class RtMapValueRef(val map: MutableMap<RtValue, RtValue>, val key: RtValue): RtValueRef() {
        override fun get(): RtValue {
            val value = map[key]
            if (value == null) {
                throw RtError("fn_map_get_novalue:${key.toStrictString()}", "Key not in map: $key")
            }
            return value
        }

        override fun set(value: RtValue) {
            map.put(key, value)
        }
    }
}

class RTextSubscriptExpr(val base: RExpr, val expr: RExpr): RExpr(RTextType) {
    override fun evaluate(frame: RtCallFrame): RtValue {
        val baseValue = base.evaluate(frame)
        val indexValue = expr.evaluate(frame)
        val str = baseValue.asString()
        val index = indexValue.asInteger()

        if (index < 0 || index >= str.length) {
            throw RtError("expr_text_subscript_index:${str.length}:$index",
                    "Index out of bounds: $index (length ${str.length})")
        }

        val i = index.toInt()
        val res = str.substring(i, i + 1)
        return RtTextValue(res)
    }
}

class RByteArraySubscriptExpr(val base: RExpr, val expr: RExpr): RExpr(RTextType) {
    override fun evaluate(frame: RtCallFrame): RtValue {
        val baseValue = base.evaluate(frame)
        val indexValue = expr.evaluate(frame)
        val array = baseValue.asByteArray()
        val index = indexValue.asInteger()

        if (index < 0 || index >= array.size) {
            throw RtError("expr_bytearray_subscript_index:${array.size}:$index",
                    "Index out of bounds: $index (length ${array.size})")
        }

        val i = index.toInt()
        val v = array[i].toLong()
        val res = if (v > 0) v else v + 256
        return RtIntValue(res)
    }
}

class RCreateExprAttr(val attr: RAttrib, val expr: RExpr)

class RCreateExpr(type: RType, val rClass: RClass, val attrs: List<RCreateExprAttr>): RExpr(type) {
    override fun evaluate(frame: RtCallFrame): RtValue {
        frame.entCtx.checkDbUpdateAllowed()
        val rtSql = buildSql()
        val rtSel = RtSelect(rtSql, listOf(type))
        val res = rtSel.execute(frame)
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
