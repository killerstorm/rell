package net.postchain.rell.runtime

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.collect.Iterables
import com.google.common.math.LongMath
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvVirtual
import net.postchain.rell.CommonUtils
import net.postchain.rell.PostchainUtils
import net.postchain.rell.model.*
import java.util.*

abstract class Rt_ValueRef {
    abstract fun get(): Rt_Value
    abstract fun set(value: Rt_Value)
}

enum class Rt_ValueType {
    UNIT,
    BOOLEAN,
    INTEGER,
    TEXT,
    BYTE_ARRAY,
    ROWID,
    CLASS,
    NULL,
    COLLECTION,
    LIST,
    SET,
    MAP,
    MUTABLE_MAP,
    TUPLE,
    RECORD,
    ENUM,
    OBJECT,
    JSON,
    RANGE,
    GTV,
    VIRTUAL,
    VIRTUAL_COLLECTION,
    VIRTUAL_LIST,
    VIRTUAL_SET,
    VIRTUAL_MAP,
    VIRTUAL_TUPLE,
    VIRTUAL_RECORD,
}

sealed class Rt_Value {
    abstract fun type(): R_Type
    abstract fun valueType(): Rt_ValueType

    open fun asBoolean(): Boolean = throw errType(Rt_ValueType.BOOLEAN)
    open fun asInteger(): Long = throw errType(Rt_ValueType.INTEGER)
    open fun asRowid(): Long = throw errType(Rt_ValueType.ROWID)
    open fun asString(): String = throw errType(Rt_ValueType.TEXT)
    open fun asByteArray(): ByteArray = throw errType(Rt_ValueType.BYTE_ARRAY)
    open fun asJsonString(): String = throw errType(Rt_ValueType.JSON)
    open fun asCollection(): MutableCollection<Rt_Value> = throw errType(Rt_ValueType.COLLECTION)
    open fun asList(): MutableList<Rt_Value> = throw errType(Rt_ValueType.LIST)
    open fun asVirtualCollection(): Rt_VirtualCollectionValue = throw errType(Rt_ValueType.VIRTUAL_COLLECTION)
    open fun asVirtualList(): Rt_VirtualListValue = throw errType(Rt_ValueType.VIRTUAL_LIST)
    open fun asVirtualSet(): Rt_VirtualSetValue = throw errType(Rt_ValueType.VIRTUAL_SET)
    open fun asSet(): MutableSet<Rt_Value> = throw errType(Rt_ValueType.SET)
    open fun asMap(): Map<Rt_Value, Rt_Value> = throw errType(Rt_ValueType.MAP)
    open fun asMutableMap(): MutableMap<Rt_Value, Rt_Value> = throw errType(Rt_ValueType.MUTABLE_MAP)
    open fun asTuple(): List<Rt_Value> = throw errType(Rt_ValueType.TUPLE)
    open fun asVirtualTuple(): Rt_VirtualTupleValue = throw errType(Rt_ValueType.VIRTUAL_TUPLE)
    open fun asRecord(): Rt_RecordValue = throw errType(Rt_ValueType.RECORD)
    open fun asVirtual(): Rt_VirtualValue = throw errType(Rt_ValueType.VIRTUAL)
    open fun asVirtualRecord(): Rt_VirtualRecordValue = throw errType(Rt_ValueType.VIRTUAL_RECORD)
    open fun asEnum(): R_EnumAttr = throw errType(Rt_ValueType.ENUM)
    open fun asRange(): Rt_RangeValue = throw errType(Rt_ValueType.RANGE)
    open fun asObjectId(): Long = throw errType(Rt_ValueType.CLASS)
    open fun asGtv(): Gtv = throw errType(Rt_ValueType.GTV)
    open fun asFormatArg(): Any = toString()

    abstract fun toStrictString(showTupleFieldNames: Boolean = true): String

    private fun errType(expected: Rt_ValueType) = Rt_ValueTypeError(expected, valueType())
}

sealed class Rt_VirtualValue(val gtv: Gtv): Rt_Value() {
    override fun asVirtual() = this

    fun toFull(): Rt_Value {
        if (gtv is GtvVirtual) {
            val typeStr = type().name
            throw Rt_Error("virtual:to_full:notfull:$typeStr", "to_full: value of type $typeStr is not full")
        }
        val res = toFull0()
        return res
    }

    protected abstract fun toFull0(): Rt_Value

    companion object {
        fun toFull(v: Rt_Value): Rt_Value {
            return if (v is Rt_VirtualValue) v.toFull() else v
        }
    }
}

object Rt_UnitValue: Rt_Value() {
    override fun type() = R_UnitType
    override fun valueType() = Rt_ValueType.UNIT
    override fun toStrictString(showTupleFieldNames: Boolean): String = "unit"
    override fun toString(): String = "unit"
}

class Rt_BooleanValue(val value: Boolean): Rt_Value() {
    override fun type() = R_BooleanType
    override fun valueType() = Rt_ValueType.BOOLEAN
    override fun asBoolean(): Boolean = value
    override fun asFormatArg(): Any = value
    override fun toStrictString(showTupleFieldNames: Boolean): String = "boolean[$value]"
    override fun toString(): String = "" + value
    override fun equals(other: Any?): Boolean = other is Rt_BooleanValue && value == other.value
    override fun hashCode(): Int = java.lang.Boolean.hashCode(value)
}

class Rt_IntValue(val value: Long): Rt_Value() {
    override fun type() = R_IntegerType
    override fun valueType() = Rt_ValueType.INTEGER
    override fun asInteger(): Long = value
    override fun asFormatArg(): Any = value
    override fun toStrictString(showTupleFieldNames: Boolean): String = "int[$value]"
    override fun toString(): String = "" + value
    override fun equals(other: Any?): Boolean = other is Rt_IntValue && value == other.value
    override fun hashCode(): Int = java.lang.Long.hashCode(value)
}

class Rt_TextValue(val value: String): Rt_Value() {
    override fun type() = R_TextType
    override fun valueType() = Rt_ValueType.TEXT
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
    override fun valueType() = Rt_ValueType.BYTE_ARRAY
    override fun asByteArray(): ByteArray = value
    override fun asFormatArg(): Any = toString()
    override fun toStrictString(showTupleFieldNames: Boolean): String = "byte_array[${CommonUtils.bytesToHex(value)}]"
    override fun toString(): String = "0x" + CommonUtils.bytesToHex(value)
    override fun equals(other: Any?): Boolean = other is Rt_ByteArrayValue && Arrays.equals(value, other.value)
    override fun hashCode(): Int = Arrays.hashCode(value)
}

class Rt_RowidValue(val value: Long): Rt_Value() {
    init {
        check(value >= 0) { "Negative rowid value: $value" }
    }

    override fun type() = R_RowidType
    override fun valueType() = Rt_ValueType.ROWID
    override fun asRowid(): Long = value
    override fun asFormatArg(): Any = value
    override fun toStrictString(showTupleFieldNames: Boolean): String = "rowid[$value]"
    override fun toString(): String = "" + value
    override fun equals(other: Any?): Boolean = other is Rt_RowidValue && value == other.value
    override fun hashCode(): Int = java.lang.Long.hashCode(value)
}

class Rt_ClassValue(val type: R_ClassType, val rowid: Long): Rt_Value() {
    override fun type() = type
    override fun valueType() = Rt_ValueType.CLASS
    override fun asObjectId(): Long = rowid
    override fun asFormatArg(): Any = toString()
    override fun toStrictString(showTupleFieldNames: Boolean): String = "${type.name}[$rowid]"
    override fun toString(): String = toStrictString()
    override fun equals(other: Any?): Boolean = other is Rt_ClassValue && type == other.type && rowid == other.rowid
    override fun hashCode(): Int = Objects.hash(type, rowid)
}

object Rt_NullValue: Rt_Value() {
    override fun type() = R_NullType
    override fun valueType() = Rt_ValueType.NULL
    override fun asFormatArg(): Any = toString()
    override fun toStrictString(showTupleFieldNames: Boolean): String = "null"
    override fun toString(): String = "null"
}

class Rt_ListValue(private val type: R_Type, private val elements: MutableList<Rt_Value>): Rt_Value() {
    override fun type() = type
    override fun valueType() = Rt_ValueType.LIST
    override fun asCollection(): MutableCollection<Rt_Value> = elements
    override fun asList(): MutableList<Rt_Value> = elements
    override fun asFormatArg(): Any = elements

    override fun toStrictString(showTupleFieldNames: Boolean) = toStrictString(type, elements)
    override fun toString(): String = elements.toString()
    override fun equals(other: Any?): Boolean = other is Rt_ListValue && elements == other.elements
    override fun hashCode(): Int = elements.hashCode()

    companion object {
        fun checkIndex(size: Int, index: Long) {
            if (index < 0 || index >= size) {
                throw Rt_Error("list:index:$size:$index", "List index out of bounds: $index (size $size)")
            }
        }

        fun toStrictString(type: R_Type, elements: List<out Rt_Value?>): String {
            val elems = elements.joinToString(",") { it?.toStrictString(false) ?: "null" }
            return "${type.toStrictString()}[$elems]"
        }
    }
}

sealed class Rt_VirtualCollectionValue(gtv: Gtv): Rt_VirtualValue(gtv) {
    override fun asVirtualCollection() = this
    abstract fun size(): Int
    abstract fun iterable(): Iterable<Rt_Value>
}

class Rt_VirtualListValue(
        gtv: Gtv,
        private val type: R_VirtualListType,
        private val elements: List<Rt_Value?>
): Rt_VirtualCollectionValue(gtv) {
    override fun type() = type
    override fun valueType() = Rt_ValueType.VIRTUAL_LIST
    override fun asVirtualCollection() = this
    override fun asVirtualList() = this
    override fun asFormatArg(): Any = elements
    override fun toStrictString(showTupleFieldNames: Boolean) = Rt_ListValue.toStrictString(type, elements)
    override fun toString(): String = elements.toString()
    override fun equals(other: Any?): Boolean = other is Rt_VirtualListValue && elements == other.elements
    override fun hashCode(): Int = elements.hashCode()

    override fun toFull0(): Rt_Value {
        val resElements = elements.map { toFull(it!!) }.toMutableList()
        return Rt_ListValue(type.innerType, resElements)
    }

    override fun size() = elements.size
    override fun iterable(): Iterable<Rt_Value> = Iterables.filter(elements) { it != null } as Iterable<Rt_Value>

    fun contains(index: Long) = index >= 0 && index < elements.size && elements[index.toInt()] != null

    fun get(index: Long): Rt_Value {
        Rt_ListValue.checkIndex(elements.size, index)
        val value = elements[index.toInt()]
        if (value == null) {
            throw Rt_Error("virtual_list:get:novalue:$index", "Element $index has no value")
        }
        return value
    }
}

class Rt_SetValue(private val type: R_Type, private val elements: MutableSet<Rt_Value>): Rt_Value() {
    override fun type() = type
    override fun valueType() = Rt_ValueType.SET
    override fun asCollection(): MutableCollection<Rt_Value> = elements
    override fun asSet(): MutableSet<Rt_Value> = elements
    override fun asFormatArg(): Any = elements
    override fun toStrictString(showTupleFieldNames: Boolean) = toStrictString(type, elements, showTupleFieldNames)
    override fun toString(): String = elements.toString()
    override fun equals(other: Any?): Boolean = other is Rt_SetValue && elements == other.elements
    override fun hashCode(): Int = elements.hashCode()

    companion object {
        fun toStrictString(type: R_Type, elements: Set<Rt_Value>, showTupleFieldNames: Boolean): String =
                "${type.toStrictString()}[${elements.joinToString(",") { it.toStrictString(false) }}]"
    }
}

class Rt_VirtualSetValue(
        gtv: Gtv,
        private val type: R_VirtualSetType,
        private val elements: Set<Rt_Value>
): Rt_VirtualCollectionValue(gtv) {
    override fun type() = type
    override fun valueType() = Rt_ValueType.VIRTUAL_LIST
    override fun asVirtualCollection() = this
    override fun asVirtualSet() = this
    override fun asFormatArg(): Any = elements
    override fun toStrictString(showTupleFieldNames: Boolean) = Rt_SetValue.toStrictString(type, elements, showTupleFieldNames)
    override fun toString(): String = elements.toString()
    override fun equals(other: Any?): Boolean = other is Rt_VirtualSetValue && elements == other.elements
    override fun hashCode(): Int = elements.hashCode()

    override fun toFull0(): Rt_Value {
        val resElements = elements.map { toFull(it!!) }.toMutableSet()
        return Rt_SetValue(type.innerType, resElements)
    }

    override fun size() = elements.size
    override fun iterable(): Iterable<Rt_Value> = elements

    fun contains(value: Rt_Value): Boolean = elements.contains(value)
}

class Rt_MapValue(private val type: R_Type, private val map: MutableMap<Rt_Value, Rt_Value>): Rt_Value() {
    override fun type() = type
    override fun valueType() = Rt_ValueType.MAP
    override fun asMap() = map
    override fun asMutableMap() = map
    override fun asFormatArg(): Any = map
    override fun toStrictString(showTupleFieldNames: Boolean) = toStrictString(type, showTupleFieldNames, map)
    override fun toString(): String = map.toString()
    override fun equals(other: Any?): Boolean = other is Rt_MapValue && map == other.map
    override fun hashCode(): Int = map.hashCode()

    companion object {
        fun toStrictString(type: R_Type, showTupleFieldNames: Boolean, map: Map<Rt_Value, Rt_Value>): String {
            val entries = map.entries.joinToString(",") { (key, value) ->
                key.toStrictString(false) + "=" + value.toStrictString(false)
            }
            return "${type.toStrictString()}[$entries]"
        }
    }
}

class Rt_VirtualMapValue(
        gtv: Gtv,
        private val type: R_VirtualMapType,
        private val map: Map<Rt_Value, Rt_Value>
): Rt_VirtualValue(gtv) {
    override fun type() = type
    override fun valueType() = Rt_ValueType.VIRTUAL_MAP
    override fun asMap() = map
    override fun asFormatArg(): Any = map
    override fun toStrictString(showTupleFieldNames: Boolean) = Rt_MapValue.toStrictString(type, showTupleFieldNames, map)
    override fun toString(): String = map.toString()
    override fun equals(other: Any?): Boolean = other is Rt_VirtualMapValue && map == other.map
    override fun hashCode(): Int = map.hashCode()

    override fun toFull0(): Rt_Value {
        val resMap = map
                .mapKeys { (k, v) -> toFull(k) }
                .mapValues { (k, v) -> toFull(v) }
                .toMutableMap()
        return Rt_MapValue(type.innerType, resMap)
    }
}

class Rt_TupleValue(val type: R_TupleType, val elements: List<Rt_Value>): Rt_Value() {
    override fun type() = type
    override fun valueType() = Rt_ValueType.TUPLE
    override fun asTuple(): List<Rt_Value> = elements
    override fun asFormatArg(): Any = toString()
    override fun equals(other: Any?): Boolean = other is Rt_TupleValue && elements == other.elements
    override fun hashCode(): Int = elements.hashCode()

    override fun toString() = toString("", type, elements)
    override fun toStrictString(showTupleFieldNames: Boolean) = toStrictString("", type, elements, showTupleFieldNames)

    companion object {
        fun toString(prefix: String, type: R_TupleType, elements: List<Rt_Value?>): String {
            val elems = elements.indices.joinToString(",") { elementToString(type, elements, it) }
            return "$prefix($elems)"
        }

        private fun elementToString(type: R_TupleType, elements: List<Rt_Value?>, idx: Int): String {
            val name = type.fields[idx].name
            val value = elements[idx]
            val valueStr = value.toString()
            return if (name == null) valueStr else "$name=$valueStr"
        }

        fun toStrictString(prefix: String, type: R_TupleType, elements: List<Rt_Value?>, showTupleFieldNames: Boolean): String {
            val elems = elements.indices.joinToString(",") {
                elementToStrictString(type, elements, showTupleFieldNames, it)
            }
            return "$prefix($elems)"
        }

        private fun elementToStrictString(
                type: R_TupleType,
                elements: List<Rt_Value?>,
                showTupleFieldNames: Boolean,
                idx: Int
        ): String {
            val name = type.fields[idx].name
            val value = elements[idx]
            val valueStr = value?.toStrictString() ?: "null"
            return if (name == null || !showTupleFieldNames) valueStr else "$name=$valueStr"
        }
    }
}

class Rt_VirtualTupleValue(
        gtv: Gtv,
        private val type: R_VirtualTupleType,
        private val elements: List<Rt_Value?>
): Rt_VirtualValue(gtv) {
    override fun type() = type
    override fun valueType() = Rt_ValueType.VIRTUAL_TUPLE
    override fun asVirtualTuple() = this
    override fun asFormatArg(): Any = toString()
    override fun equals(other: Any?): Boolean = other is Rt_VirtualTupleValue && elements == other.elements
    override fun hashCode(): Int = elements.hashCode()

    override fun toString() = Rt_TupleValue.toString("virtual", type.innerType, elements)
    override fun toStrictString(showTupleFieldNames: Boolean) =
            Rt_TupleValue.toStrictString("virtual", type.innerType, elements, showTupleFieldNames)

    override fun toFull0(): Rt_Value {
        val resElements = elements.map { toFull(it!!) }
        return Rt_TupleValue(type.innerType, resElements)
    }

    fun get(index: Int): Rt_Value {
        val value = elements[index]
        if (value == null) {
            val attr = type.innerType.fields[index].name ?: "$index"
            throw Rt_Error("virtual_tuple:get:novalue:$attr", "Field '$attr' has no value")
        }
        return value
    }

    private fun errNoValue(op: String, attr: String) =
            Rt_Error("virtual_tuple:$op:novalue:$attr", "Field '$attr' has no value")
}

class Rt_RecordValue(private val type: R_RecordType, private val attributes: MutableList<Rt_Value>): Rt_Value() {
    override fun type() = type
    override fun valueType() = Rt_ValueType.RECORD
    override fun asRecord() = this
    override fun asFormatArg(): Any = toString()
    override fun equals(other: Any?): Boolean = other is Rt_RecordValue && attributes == other.attributes
    override fun hashCode(): Int = type.hashCode() * 31 + attributes.hashCode()

    override fun toString() = toString(type, attributes)
    override fun toStrictString(showTupleFieldNames: Boolean) = toStrictString(type, type, attributes)

    fun get(index: Int): Rt_Value {
        return attributes[index]
    }

    fun set(index: Int, value: Rt_Value) {
        attributes[index] = value
    }

    companion object {
        fun toString(type: R_RecordType, attributes: List<out Rt_Value?>): String {
            val attrs = attributes.withIndex().joinToString(",") { (i, attr) ->
                val n = type.attributesList[i].name
                val v = attr?.toString()
                "$n=$v"
            }
            return "${type.name}{$attrs}"
        }

        fun toStrictString(type: R_Type, recordType: R_RecordType, attributes: List<out Rt_Value?>): String {
            val attrs = attributes.indices.joinToString(",") {attributeToStrictString(recordType, attributes, it) }
            return "${type.name}[$attrs]"
        }

        private fun attributeToStrictString(type: R_RecordType, attributes: List<out Rt_Value?>, idx: Int): String {
            val name = type.attributesList[idx].name
            val value = attributes[idx]
            val valueStr = value?.toStrictString()
            return "$name=$valueStr"
        }
    }
}

class Rt_VirtualRecordValue(
        gtv: Gtv,
        private val type: R_VirtualRecordType,
        private val attributes: List<Rt_Value?>
): Rt_VirtualValue(gtv) {
    override fun type() = type
    override fun valueType() = Rt_ValueType.VIRTUAL_RECORD
    override fun asVirtualRecord() = this
    override fun asFormatArg(): Any = toString()
    override fun equals(other: Any?): Boolean = other is Rt_VirtualRecordValue && attributes == other.attributes
    override fun hashCode(): Int = type.hashCode() * 31 + attributes.hashCode()

    override fun toString() = Rt_RecordValue.toString(type.innerType, attributes)
    override fun toStrictString(showTupleFieldNames: Boolean) =
            Rt_RecordValue.toStrictString(type, type.innerType, attributes)

    fun get(index: Int): Rt_Value {
        val value = attributes[index]
        if (value == null) {
            val typeName = type.innerType.name
            val attr = type.innerType.attributesList[index].name
            throw Rt_Error("virtual_record:get:novalue:$typeName:$attr", "Attribute '$typeName.$attr' has no value")
        }
        return value
    }

    override fun toFull0(): Rt_Value {
        val fullAttrValues = attributes.map { toFull(it!!) }.toMutableList()
        return Rt_RecordValue(type.innerType, fullAttrValues)
    }
}

class Rt_EnumValue(private val type: R_EnumType, private val attr: R_EnumAttr): Rt_Value() {
    override fun type() = type
    override fun valueType() = Rt_ValueType.ENUM
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
    override fun valueType() = Rt_ValueType.OBJECT
    override fun toStrictString(showTupleFieldNames: Boolean) = type.name
    override fun toString() = type.name
}

class Rt_JsonValue private constructor(private val str: String): Rt_Value() {
    override fun type() = R_JsonType
    override fun valueType() = Rt_ValueType.JSON
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

            if (json == null) {
                throw IllegalArgumentException(s)
            }

            val str = json.toString()
            return Rt_JsonValue(str)
        }
    }
}

class Rt_RangeValue(val start: Long, val end: Long, val step: Long): Rt_Value(), Iterable<Rt_Value>, Comparable<Rt_RangeValue> {
    override fun type() = R_RangeType
    override fun valueType() = Rt_ValueType.RANGE
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

    override fun compareTo(other: Rt_RangeValue): Int {
        var c = start.compareTo(other.start)
        if (c == 0) c = end.compareTo(other.end)
        if (c == 0) c = step.compareTo(other.step)
        return c
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

class Rt_GtvValue(val value: Gtv): Rt_Value() {
    override fun type() = R_GtvType
    override fun valueType() = Rt_ValueType.GTV
    override fun asGtv() = value

    override fun toStrictString(showTupleFieldNames: Boolean): String = "gtv[$this]"

    override fun toString(): String {
        try {
            return PostchainUtils.gtvToJson(value)
        } catch (e: Exception) {
            return value.toString() // Fallback, just in case (did not happen).
        }
    }

    override fun equals(other: Any?): Boolean = other is Rt_GtvValue && value == other.value
    override fun hashCode(): Int = value.hashCode()
}
