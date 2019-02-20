package net.postchain.rell.runtime

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.math.LongMath
import net.postchain.gtx.GTXNull
import net.postchain.gtx.GTXValue
import net.postchain.gtx.make_gtx_gson
import net.postchain.rell.model.*
import net.postchain.rell.toHex
import java.lang.IllegalArgumentException
import java.util.*

abstract class Rt_ValueRef {
    abstract fun get(): Rt_Value
    abstract fun set(value: Rt_Value)
}

sealed class Rt_Value {
    abstract fun type(): R_Type
    abstract fun valueType(): String

    open fun asBoolean(): Boolean = throw errType("Boolean")
    open fun asInteger(): Long = throw errType("Integer")
    open fun asString(): String = throw errType("String")
    open fun asByteArray(): ByteArray = throw errType("ByteArray")
    open fun asJsonString(): String = throw errType("Json")
    open fun asCollection(): MutableCollection<Rt_Value> = throw errType("Collection")
    open fun asList(): MutableList<Rt_Value> = throw errType("List")
    open fun asSet(): MutableSet<Rt_Value> = throw errType("Set")
    open fun asMap(): MutableMap<Rt_Value, Rt_Value> = throw errType("Map")
    open fun asTuple(): List<Rt_Value> = throw errType("Tuple")
    open fun asRecord(): Rt_RecordValue = throw errType("Record")
    open fun asEnum(): R_EnumAttr = throw errType("Enum")
    open fun asRange(): Rt_RangeValue = throw errType("Range")
    open fun asObjectId(): Long = throw errType("Class")
    open fun asGtxValue(): GTXValue = throw errType("GTXValue")
    open fun asFormatArg(): Any = toString()

    abstract fun toStrictString(showTupleFieldNames: Boolean = true): String

    private fun errType(expected: String) = Rt_ValueTypeError(expected, valueType())
}

object Rt_UnitValue: Rt_Value() {
    override fun type() = R_UnitType
    override fun valueType() = "Unit"
    override fun toStrictString(showTupleFieldNames: Boolean): String = "unit"
    override fun toString(): String = "unit"
}

class Rt_BooleanValue(val value: Boolean): Rt_Value() {
    override fun type() = R_BooleanType
    override fun valueType() = "Boolean"
    override fun asBoolean(): Boolean = value
    override fun asFormatArg(): Any = value
    override fun toStrictString(showTupleFieldNames: Boolean): String = "boolean[$value]"
    override fun toString(): String = "" + value
    override fun equals(other: Any?): Boolean = other is Rt_BooleanValue && value == other.value
    override fun hashCode(): Int = java.lang.Boolean.hashCode(value)
}

class Rt_IntValue(val value: Long): Rt_Value() {
    override fun type() = R_IntegerType
    override fun valueType() = "Integer"
    override fun asInteger(): Long = value
    override fun asFormatArg(): Any = value
    override fun toStrictString(showTupleFieldNames: Boolean): String = "int[$value]"
    override fun toString(): String = "" + value
    override fun equals(other: Any?): Boolean = other is Rt_IntValue && value == other.value
    override fun hashCode(): Int = java.lang.Long.hashCode(value)
}

class Rt_TextValue(val value: String): Rt_Value() {
    override fun type() = R_TextType
    override fun valueType() = "Text"
    override fun asString(): String = value
    override fun asFormatArg(): Any = value

    override fun toStrictString(showTupleFieldNames: Boolean): String {
        val esc = escape(value)
        return "text[$esc]"
    }

    override fun toString(): String = value
    override fun equals(other: Any?): Boolean = other is Rt_TextValue && value == other.value
    override fun hashCode(): Int = value.hashCode()

    companion object {
        private fun escape(s: String): String {
            if (s.isEmpty()) return ""

            val buf = StringBuilder(s.length)
            for (c in s) {
                if (c == '\t') {
                    buf.append("\\t")
                } else if (c == '\r') {
                    buf.append("\\r")
                } else if (c == '\n') {
                    buf.append("\\n")
                } else if (c == '\b') {
                    buf.append("\\b")
                } else if (c == '\\') {
                    buf.append("\\\\")
                } else if (c >= '\u0020' && c < '\u0080') {
                    buf.append(c)
                } else {
                    buf.append("\\u")
                    buf.append(String.format("%04x", c.toInt()))
                }
            }

            return buf.toString()
        }
    }
}

class Rt_ByteArrayValue(val value: ByteArray): Rt_Value() {
    override fun type() = R_ByteArrayType
    override fun valueType() = "ByteArray"
    override fun asByteArray(): ByteArray = value
    override fun asFormatArg(): Any = toString()
    override fun toStrictString(showTupleFieldNames: Boolean): String = "byte_array[${value.toHex()}]"
    override fun toString(): String = "0x" + value.toHex()
    override fun equals(other: Any?): Boolean = other is Rt_ByteArrayValue && Arrays.equals(value, other.value)
    override fun hashCode(): Int = Arrays.hashCode(value)
}

class Rt_ClassValue(val type: R_ClassType, val rowid: Long): Rt_Value() {
    override fun type() = type
    override fun valueType() = "Object"
    override fun asObjectId(): Long = rowid
    override fun asFormatArg(): Any = toString()
    override fun toStrictString(showTupleFieldNames: Boolean): String = "${type.name}[$rowid]"
    override fun toString(): String = toStrictString()
    override fun equals(other: Any?): Boolean = other is Rt_ClassValue && type == other.type && rowid == other.rowid
    override fun hashCode(): Int = Objects.hash(type, rowid)
}

object Rt_NullValue: Rt_Value() {
    override fun type() = R_NullType
    override fun valueType() = "Null"
    override fun asFormatArg(): Any = toString()
    override fun toStrictString(showTupleFieldNames: Boolean): String = "null"
    override fun toString(): String = "null"
}

class Rt_ListValue(private val type: R_Type, private val elements: MutableList<Rt_Value>): Rt_Value() {
    override fun type() = type
    override fun valueType() = "List"
    override fun asCollection(): MutableCollection<Rt_Value> = elements
    override fun asList(): MutableList<Rt_Value> = elements
    override fun asFormatArg(): Any = elements

    override fun toStrictString(showTupleFieldNames: Boolean): String =
            "${type.toStrictString()}[${elements.joinToString(",") { it.toStrictString(false) }}]"

    override fun toString(): String = elements.toString()
    override fun equals(other: Any?): Boolean = other is Rt_ListValue && elements == other.elements
    override fun hashCode(): Int = elements.hashCode()
}

class Rt_SetValue(private val type: R_Type, private val elements: MutableSet<Rt_Value>): Rt_Value() {
    override fun type() = type
    override fun valueType() = "Set"
    override fun asCollection(): MutableCollection<Rt_Value> = elements
    override fun asSet(): MutableSet<Rt_Value> = elements
    override fun asFormatArg(): Any = elements

    override fun toStrictString(showTupleFieldNames: Boolean): String =
            "${type.toStrictString()}[${elements.joinToString(",") { it.toStrictString(false) }}]"

    override fun toString(): String = elements.toString()
    override fun equals(other: Any?): Boolean = other is Rt_SetValue && elements == other.elements
    override fun hashCode(): Int = elements.hashCode()
}

class Rt_MapValue(private val type: R_Type, private val map: MutableMap<Rt_Value, Rt_Value>): Rt_Value() {
    override fun type() = type
    override fun valueType() = "Map"
    override fun asMap(): MutableMap<Rt_Value, Rt_Value> = map
    override fun asFormatArg(): Any = map

    override fun toStrictString(showTupleFieldNames: Boolean): String {
        val entries = map.entries.joinToString(",") { (key, value) ->
            key.toStrictString(false) + "=" + value.toStrictString(false)
        }
        return "${type.toStrictString()}[$entries]"
    }

    override fun toString(): String = map.toString()
    override fun equals(other: Any?): Boolean = other is Rt_MapValue && map == other.map
    override fun hashCode(): Int = map.hashCode()
}

class Rt_TupleValue(val type: R_TupleType, val elements: List<Rt_Value>): Rt_Value() {
    override fun type() = type
    override fun valueType() = "Tuple"
    override fun asTuple(): List<Rt_Value> = elements
    override fun asFormatArg(): Any = toString()
    override fun equals(other: Any?): Boolean = other is Rt_TupleValue && elements == other.elements
    override fun hashCode(): Int = elements.hashCode()

    override fun toString(): String = "(${elements.indices.joinToString(",") { elementToString(it) }})"

    private fun elementToString(idx: Int): String {
        val name = type.fields[idx].name
        val value = elements[idx]
        val valueStr = value.toString()
        return if (name == null) valueStr else "$name=$valueStr"
    }

    override fun toStrictString(showTupleFieldNames: Boolean): String {
        return "(${elements.indices.joinToString(",") { elementToStrictString(showTupleFieldNames, it) }})"
    }

    private fun elementToStrictString(showTupleFieldNames: Boolean, idx: Int): String {
        val name = type.fields[idx].name
        val value = elements[idx]
        val valueStr = value.toStrictString()
        return if (name == null || !showTupleFieldNames) valueStr else "$name=$valueStr"
    }
}

class Rt_RecordValue(private val type: R_RecordType, private val attributes: MutableList<Rt_Value>): Rt_Value() {
    override fun type() = type
    override fun valueType() = "Record"
    override fun asRecord() = this
    override fun asFormatArg(): Any = toString()
    override fun equals(other: Any?): Boolean = other is Rt_RecordValue && attributes == other.attributes
    override fun hashCode(): Int = type.hashCode() * 31 + attributes.hashCode()

    override fun toString(): String {
        val attrs = attributes.withIndex().joinToString(",") { (i, attr) ->
            val n = type.attributesList[i].name
            val v = attr.toString()
            "$n=$v"
        }
        return "${type.name}{$attrs}"
    }

    override fun toStrictString(showTupleFieldNames: Boolean): String {
        return "${type.name}[${attributes.indices.joinToString(",") { attributeToStrictString(it) }}]"
    }

    private fun attributeToStrictString(idx: Int): String {
        val name = type.attributesList[idx].name
        val value = attributes[idx]
        val valueStr = value.toStrictString()
        return "$name=$valueStr"
    }

    fun get(index: Int): Rt_Value {
        return attributes[index]
    }

    fun set(index: Int, value: Rt_Value) {
        attributes[index] = value
    }
}

class Rt_EnumValue(private val type: R_EnumType, private val attr: R_EnumAttr): Rt_Value() {
    override fun type() = type
    override fun valueType() = "Enum"
    override fun asEnum() = attr
    override fun asFormatArg(): Any = attr.name
    override fun equals(other: Any?): Boolean = other is Rt_EnumValue && attr == other.attr
    override fun hashCode(): Int = type.hashCode() * 31 + attr.value

    override fun toString(): String {
        return attr.name
    }

    override fun toStrictString(showTupleFieldNames: Boolean): String {
        return "${type.name}[${attr.name}]"
    }
}

class Rt_ObjectValue(private val type: R_ObjectType): Rt_Value() {
    override fun type() = type
    override fun valueType() = "Object"
    override fun toStrictString(showTupleFieldNames: Boolean) = type.name
    override fun toString() = type.name
}

class Rt_JsonValue private constructor(private val str: String): Rt_Value() {
    override fun type() = R_JSONType
    override fun valueType() = "Json"
    override fun asJsonString(): String = str
    override fun asFormatArg(): Any = str
    override fun toString(): String = str
    override fun toStrictString(showTupleFieldNames: Boolean): String = "json[$str]"
    override fun equals(other: Any?): Boolean = other is Rt_JsonValue && str == other.str
    override fun hashCode(): Int = str.hashCode()

    companion object {
        fun parse(s: String): Rt_Value {
            val mapper = ObjectMapper()

            val json = try { mapper.readTree(s) }
            catch (e: JsonProcessingException) {
                throw IllegalArgumentException(s)
            }

            val str = json.toString()
            return Rt_JsonValue(str)
        }
    }
}

class Rt_RangeValue(val start: Long, val end: Long, val step: Long): Rt_Value(), Iterable<Rt_Value> {
    override fun type() = R_RangeType
    override fun valueType() = "Range"
    override fun asRange(): Rt_RangeValue = this
    override fun asFormatArg(): Any = toString()
    override fun toString(): String = "range($start,$end,$step)"
    override fun toStrictString(showTupleFieldNames: Boolean): String = "range[$start,$end,$step]"

    override fun iterator(): Iterator<Rt_Value> = RangeIterator(this)

    override fun equals(other: Any?): Boolean = other is Rt_RangeValue && start == other.start && end == other.end && step == other.step
    override fun hashCode(): Int = Objects.hash(start, end, step)

    fun contains(v: Long): Boolean {
        if (step > 0) {
            if (v < start || v >= end) return false
        } else {
            check(step < 0)
            if (v > start || v <= end) return false
        }
        val m1 = valueMod(start, step)
        val m2 = valueMod(v, step)
        return m1 == m2
    }

    companion object {
        private fun valueMod(v: Long, m: Long): Long {
            val r = v % m
            if (r >= 0) return r
            return if (m > 0) r + m else r - m
        }

        private class RangeIterator(private val range: Rt_RangeValue): Iterator<Rt_Value> {
            private var current = range.start

            override fun hasNext(): Boolean {
                if (range.step > 0) {
                    return current < range.end
                } else {
                    return current > range.end
                }
            }

            override fun next(): Rt_Value {
                val res = current
                current = LongMath.saturatedAdd(current, range.step)
                return Rt_IntValue(res)
            }
        }
    }
}

class Rt_GtxValue(val value: GTXValue): Rt_Value() {
    override fun type() = R_GtxValueType
    override fun valueType() = "GTXValue"
    override fun asGtxValue() = value

    override fun toStrictString(showTupleFieldNames: Boolean): String = "gtx[$this]"

    override fun toString(): String {
        try {
            return gtxValueToJsonString(value)
        } catch (e: Exception) {
            return value.toString() // Fallback, just in case (did not happen).
        }
    }

    override fun equals(other: Any?): Boolean = other is Rt_GtxValue && value == other.value
    override fun hashCode(): Int = value.hashCode()

    companion object {
        private val GSON = make_gtx_gson()

        fun gtxValueToJsonString(v: GTXValue): String {
            val s = GSON.toJson(v, GTXValue::class.java)
            return s
        }

        fun jsonStringToGtxValue(s: String): GTXValue {
            val v = GSON.fromJson<GTXValue>(s, GTXValue::class.java)
            return v ?: GTXNull
        }
    }
}
