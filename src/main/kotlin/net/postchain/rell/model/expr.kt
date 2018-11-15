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

class RConstantExpr(val value: RtValue): RExpr(value.type()) {
    override fun evaluate(frame: RtCallFrame): RtValue = value

    companion object {
        fun makeNull() = RConstantExpr(RtNullValue)
        fun makeBool(v: Boolean) = RConstantExpr(RtBooleanValue(v))
        fun makeInt(v: Long) = RConstantExpr(RtIntValue(v))
        fun makeText(v: String) = RConstantExpr(RtTextValue(v))
        fun makeBytes(v: ByteArray) = RConstantExpr(RtByteArrayValue(v))
    }
}

class RMemberExpr(val base: RExpr, val safe: Boolean, val calculator: RMemberCalculator): RExpr(resultType(safe, calculator)) {
    override fun evaluate(frame: RtCallFrame): RtValue {
        val baseValue = base.evaluate(frame)
        if (safe && baseValue == RtNullValue) {
            return RtNullValue
        }
        check(baseValue != RtNullValue)
        check(baseValue != RtUnitValue)
        val value = calculator.calculate(frame, baseValue)
        return value
    }

    companion object {
        private fun resultType(safe: Boolean, calculator: RMemberCalculator): RType {
            if (safe && calculator.type !is RNullableType && calculator.type != RNullType) {
                return RNullableType(calculator.type)
            } else {
                return calculator.type
            }
        }
    }
}

sealed class RMemberCalculator(val type: RType) {
    abstract fun calculate(frame: RtCallFrame, baseValue: RtValue): RtValue
}

class RMemberCalculator_TupleField(type: RType, val fieldIndex: Int): RMemberCalculator(type) {
    override fun calculate(frame: RtCallFrame, baseValue: RtValue): RtValue {
        val tupleValue = baseValue as RtTupleValue
        return tupleValue.elements[fieldIndex]
    }
}

class RMemberCalculator_DataAttribute(type: RType, val atBase: RAtExprBase): RMemberCalculator(type) {
    override fun calculate(frame: RtCallFrame, baseValue: RtValue): RtValue {
        val list = atBase.execute(frame, listOf(baseValue), null)
        if (list.size != 1) {
            throw RAtExpr.errWrongCount(list.size)
        }

        check(list[0].size == 1)
        val res = list[0][0]
        return res
    }
}

class RMemberCalculator_SysFn(type: RType, val fn: RSysFunction, val args: List<RExpr>): RMemberCalculator(type) {
    override fun calculate(frame: RtCallFrame, baseValue: RtValue): RtValue {
        val vArgs = args.map { it.evaluate(frame) }
        val vFullArgs = listOf(baseValue) + vArgs
        return fn.call(frame.entCtx.modCtx.globalCtx, vFullArgs)
    }
}

class RTupleExpr(val tupleType: RTupleType, val exprs: List<RExpr>): RExpr(tupleType) {
    override fun evaluate(frame: RtCallFrame): RtValue {
        val values = exprs.map { it.evaluate(frame) }
        return RtTupleValue(tupleType, values)
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
                throw RtError("expr_map_dupkey:${key.toStrictString()}", "Duplicate map key: $key")
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

class RElvisExpr(type: RType, val left: RExpr, val right: RExpr): RExpr(type) {
    override fun evaluate(frame: RtCallFrame): RtValue {
        val leftVal = left.evaluate(frame)
        if (leftVal != RtNullValue) {
            return leftVal
        }

        val rightVal = right.evaluate(frame)
        return rightVal
    }
}

class RNotNullExpr(type: RType, val expr: RExpr): RExpr(type) {
    override fun evaluate(frame: RtCallFrame): RtValue {
        val v = expr.evaluate(frame)
        if (v == RtNullValue) {
            throw RtError("null_value", "Null value")
        }
        return v
    }
}

sealed class RRequireExpr(type: RType, val expr: RExpr, val msgExpr: RExpr?): RExpr(type) {
    abstract fun calculate(v: RtValue): RtValue?

    final override fun evaluate(frame: RtCallFrame): RtValue {
        val value = expr.evaluate(frame)
        val res = calculate(value)
        if (res != null) {
            return res
        }

        val msg = if (msgExpr == null) null else {
            val msgValue = msgExpr.evaluate(frame)
            msgValue.asString()
        }
        throw RtRequireError(msg)
    }
}

class RRequireExpr_Boolean(expr: RExpr, msgExpr: RExpr?): RRequireExpr(RUnitType, expr, msgExpr) {
    override fun calculate(v: RtValue) = if (v.asBoolean()) RtUnitValue else null
}

class RRequireExpr_Nullable(type: RType, expr: RExpr, msgExpr: RExpr?): RRequireExpr(type, expr, msgExpr) {
    override fun calculate(v: RtValue) = if (v != RtNullValue) v else null
}

class RRequireExpr_Collection(type: RType, expr: RExpr, msgExpr: RExpr?): RRequireExpr(type, expr, msgExpr) {
    override fun calculate(v: RtValue) = if (v != RtNullValue && !v.asCollection().isEmpty()) v else null
}

class RRequireExpr_Map(type: RType, expr: RExpr, msgExpr: RExpr?): RRequireExpr(type, expr, msgExpr) {
    override fun calculate(v: RtValue) = if (v != RtNullValue && !v.asMap().isEmpty()) v else null
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
