/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.runtime

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.math.LongMath
import mu.KLogging
import net.postchain.base.BlockchainRid
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvVirtual
import net.postchain.rell.compiler.C_Constants
import net.postchain.rell.model.*
import net.postchain.rell.utils.*
import java.math.BigDecimal
import java.util.*
import java.util.regex.Pattern

abstract class Rt_ValueRef {
    abstract fun get(): Rt_Value
    abstract fun set(value: Rt_Value)
}

enum class Rt_ValueType {
    UNIT,
    BOOLEAN,
    INTEGER,
    DECIMAL,
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
    STRUCT,
    ENUM,
    OBJECT,
    JSON,
    RANGE,
    GTV,
    OPERATION,
    GTX_BLOCK,
    GTX_TX,
    VIRTUAL,
    VIRTUAL_COLLECTION,
    VIRTUAL_LIST,
    VIRTUAL_SET,
    VIRTUAL_MAP,
    VIRTUAL_TUPLE,
    VIRTUAL_STRUCT,
}

sealed class Rt_Value {
    abstract fun type(): R_Type
    abstract fun valueType(): Rt_ValueType

    open fun asBoolean(): Boolean = throw errType(Rt_ValueType.BOOLEAN)
    open fun asInteger(): Long = throw errType(Rt_ValueType.INTEGER)
    open fun asDecimal(): BigDecimal = throw errType(Rt_ValueType.DECIMAL)
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
    open fun asStruct(): Rt_StructValue = throw errType(Rt_ValueType.STRUCT)
    open fun asVirtual(): Rt_VirtualValue = throw errType(Rt_ValueType.VIRTUAL)
    open fun asVirtualStruct(): Rt_VirtualStructValue = throw errType(Rt_ValueType.VIRTUAL_STRUCT)
    open fun asEnum(): R_EnumAttr = throw errType(Rt_ValueType.ENUM)
    open fun asRange(): Rt_RangeValue = throw errType(Rt_ValueType.RANGE)
    open fun asObjectId(): Long = throw errType(Rt_ValueType.CLASS)
    open fun asGtv(): Gtv = throw errType(Rt_ValueType.GTV)
    open fun asOperation(): Rt_OperationValue = throw errType(Rt_ValueType.OPERATION)
    open fun asGtxTx(): Rt_GtxTxValue = throw errType(Rt_ValueType.GTX_TX)
    open fun asGtxBlock(): Rt_GtxBlockValue = throw errType(Rt_ValueType.GTX_BLOCK)
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
    override fun toStrictString(showTupleFieldNames: Boolean) = "unit"
    override fun toString() = "unit"
}

class Rt_BooleanValue(val value: Boolean): Rt_Value() {
    override fun type() = R_BooleanType
    override fun valueType() = Rt_ValueType.BOOLEAN
    override fun asBoolean() = value
    override fun asFormatArg() = value
    override fun toStrictString(showTupleFieldNames: Boolean) = "boolean[$value]"
    override fun toString() = "" + value
    override fun equals(other: Any?) = other is Rt_BooleanValue && value == other.value
    override fun hashCode() = java.lang.Boolean.hashCode(value)
}

class Rt_IntValue(val value: Long): Rt_Value() {
    override fun type() = R_IntegerType
    override fun valueType() = Rt_ValueType.INTEGER
    override fun asInteger() = value
    override fun asFormatArg() = value
    override fun toStrictString(showTupleFieldNames: Boolean) = "int[$value]"
    override fun toString() = "" + value
    override fun equals(other: Any?) = other is Rt_IntValue && value == other.value
    override fun hashCode() = java.lang.Long.hashCode(value)
}

class Rt_DecimalValue private constructor(val value: BigDecimal): Rt_Value() {
    override fun type() = R_DecimalType
    override fun valueType() = Rt_ValueType.DECIMAL
    override fun asDecimal() = value
    override fun asFormatArg() = value
    override fun toStrictString(showTupleFieldNames: Boolean) = "dec[$this]"
    override fun toString() = Rt_DecimalUtils.toString(value)
    override fun equals(other: Any?) = other is Rt_DecimalValue && value == other.value
    override fun hashCode() = value.hashCode()

    companion object : KLogging() {
        val ZERO = Rt_DecimalValue(BigDecimal.ZERO)

        fun of(v: BigDecimal): Rt_Value {
            val t = v.unscaledValue()
            if (t.signum() == 0) {
                return ZERO
            }

            val res = ofTry(v)
            return res ?: throw errOverflow("decimal:overflow", "Decimal value overflow")
        }

        fun ofTry(v: BigDecimal): Rt_Value? {
            val t = Rt_DecimalUtils.scale(v)
            return if (t == null) null else Rt_DecimalValue(t)
        }

        fun of(s: String): Rt_Value {
            val v = try {
                Rt_DecimalUtils.parse(s)
            } catch (e: NumberFormatException) {
                throw Rt_Error("decimal:invalid:$s", "Invalid decimal value: '$s'")
            }
            return of(v)
        }

        fun of(v: Long): Rt_Value {
            val bd = BigDecimal(v)
            return of(bd)
        }

        fun errOverflow(code: String, msg: String): Rt_Error {
            val p = C_Constants.DECIMAL_INT_DIGITS
            return Rt_Error(code, "$msg (allowed range is -10^$p..10^$p, exclusive)")
        }
    }
}

class Rt_TextValue(val value: String): Rt_Value() {
    override fun type() = R_TextType
    override fun valueType() = Rt_ValueType.TEXT
    override fun asString() = value
    override fun asFormatArg() = value

    override fun toStrictString(showTupleFieldNames: Boolean): String {
        val esc = escape(value)
        return "text[$esc]"
    }

    override fun toString(): String = value
    override fun equals(other: Any?) = other is Rt_TextValue && value == other.value
    override fun hashCode() = value.hashCode()

    companion object {
        fun like(s: String, pattern: String): Boolean {
            val regex = likePatternToRegex(pattern)
            val m = regex.matcher(s)
            return m.matches()
        }

        private fun likePatternToRegex(pattern: String): Pattern {
            val buf = StringBuilder()
            val raw = StringBuilder()
            var esc = false

            for (c in pattern) {
                if (esc) {
                    raw.append(c)
                    esc = false
                } else if (c == '\\') {
                    esc = true
                } else if (c == '%' || c == '_') {
                    if (raw.isNotEmpty()) buf.append(Pattern.quote(raw.toString()))
                    raw.setLength(0)
                    buf.append(if (c == '%') ".*" else ".")
                } else {
                    raw.append(c)
                }
            }

            if (raw.isNotEmpty()) buf.append(Pattern.quote(raw.toString()))
            val s = buf.toString()
            return Pattern.compile(s, Pattern.DOTALL)
        }

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
    override fun asByteArray() = value
    override fun asFormatArg() = toString()
    override fun toStrictString(showTupleFieldNames: Boolean) = "byte_array[${CommonUtils.bytesToHex(value)}]"
    override fun toString() = "0x" + CommonUtils.bytesToHex(value)
    override fun equals(other: Any?) = other is Rt_ByteArrayValue && Arrays.equals(value, other.value)
    override fun hashCode() = Arrays.hashCode(value)
}

class Rt_RowidValue(val value: Long): Rt_Value() {
    init {
        check(value >= 0) { "Negative rowid value: $value" }
    }

    override fun type() = R_RowidType
    override fun valueType() = Rt_ValueType.ROWID
    override fun asRowid() = value
    override fun asFormatArg() = value
    override fun toStrictString(showTupleFieldNames: Boolean) = "rowid[$value]"
    override fun toString() = "" + value
    override fun equals(other: Any?) = other is Rt_RowidValue && value == other.value
    override fun hashCode() = java.lang.Long.hashCode(value)
}

class Rt_EntityValue(val type: R_EntityType, val rowid: Long): Rt_Value() {
    override fun type() = type
    override fun valueType() = Rt_ValueType.CLASS
    override fun asObjectId() = rowid
    override fun asFormatArg() = toString()
    override fun toStrictString(showTupleFieldNames: Boolean) = "${type.name}[$rowid]"
    override fun toString() = toStrictString()
    override fun equals(other: Any?) = other is Rt_EntityValue && type == other.type && rowid == other.rowid
    override fun hashCode() = Objects.hash(type, rowid)
}

object Rt_NullValue: Rt_Value() {
    override fun type() = R_NullType
    override fun valueType() = Rt_ValueType.NULL
    override fun asFormatArg() = toString()
    override fun toStrictString(showTupleFieldNames: Boolean) = "null"
    override fun toString() = "null"
}

class Rt_ListValue(private val type: R_Type, private val elements: MutableList<Rt_Value>): Rt_Value() {
    init {
        check(type is R_ListType) { "wrong type: $type" }
    }

    override fun type() = type
    override fun valueType() = Rt_ValueType.LIST
    override fun asCollection() = elements
    override fun asList() = elements
    override fun asFormatArg() = elements

    override fun toStrictString(showTupleFieldNames: Boolean) = toStrictString(type, elements)
    override fun toString() = elements.toString()
    override fun equals(other: Any?) = other is Rt_ListValue && elements == other.elements
    override fun hashCode() = elements.hashCode()

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
    override fun asFormatArg() = elements
    override fun toStrictString(showTupleFieldNames: Boolean) = Rt_ListValue.toStrictString(type, elements)
    override fun toString() = elements.toString()
    override fun equals(other: Any?) = other is Rt_VirtualListValue && elements == other.elements
    override fun hashCode() = elements.hashCode()

    override fun toFull0(): Rt_Value {
        val resElements = elements.map { toFull(it!!) }.toMutableList()
        return Rt_ListValue(type.innerType, resElements)
    }

    override fun size() = elements.size
    override fun iterable() = elements.filterNotNull()

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
    init {
        check(type is R_SetType) { "wrong type: $type" }
    }

    override fun type() = type
    override fun valueType() = Rt_ValueType.SET
    override fun asCollection() = elements
    override fun asSet() = elements
    override fun asFormatArg() = elements
    override fun toStrictString(showTupleFieldNames: Boolean) = toStrictString(type, elements, showTupleFieldNames)
    override fun toString() = elements.toString()
    override fun equals(other: Any?) = other is Rt_SetValue && elements == other.elements
    override fun hashCode() = elements.hashCode()

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
    override fun asFormatArg() = elements
    override fun toStrictString(showTupleFieldNames: Boolean) = Rt_SetValue.toStrictString(type, elements, showTupleFieldNames)
    override fun toString() = elements.toString()
    override fun equals(other: Any?) = other is Rt_VirtualSetValue && elements == other.elements
    override fun hashCode() = elements.hashCode()

    override fun toFull0(): Rt_Value {
        val resElements = elements.map { toFull(it) }.toMutableSet()
        return Rt_SetValue(type.innerType, resElements)
    }

    override fun size() = elements.size
    override fun iterable() = elements

    fun contains(value: Rt_Value) = elements.contains(value)
}

class Rt_MapValue(private val type: R_Type, private val map: MutableMap<Rt_Value, Rt_Value>): Rt_Value() {
    override fun type() = type
    override fun valueType() = Rt_ValueType.MAP
    override fun asMap() = map
    override fun asMutableMap() = map
    override fun asFormatArg() = map
    override fun toStrictString(showTupleFieldNames: Boolean) = toStrictString(type, showTupleFieldNames, map)
    override fun toString() = map.toString()
    override fun equals(other: Any?) = other is Rt_MapValue && map == other.map
    override fun hashCode() = map.hashCode()

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
    override fun asFormatArg() = map
    override fun toStrictString(showTupleFieldNames: Boolean) = Rt_MapValue.toStrictString(type, showTupleFieldNames, map)
    override fun toString() = map.toString()
    override fun equals(other: Any?) = other is Rt_VirtualMapValue && map == other.map
    override fun hashCode() = map.hashCode()

    override fun toFull0(): Rt_Value {
        val resMap = map
                .mapKeys { (k, _) -> toFull(k) }
                .mapValues { (_, v) -> toFull(v) }
                .toMutableMap()
        return Rt_MapValue(type.innerType, resMap)
    }
}

class Rt_TupleValue(val type: R_TupleType, val elements: List<Rt_Value>): Rt_Value() {
    override fun type() = type
    override fun valueType() = Rt_ValueType.TUPLE
    override fun asTuple() = elements
    override fun asFormatArg() = toString()
    override fun equals(other: Any?) = other is Rt_TupleValue && elements == other.elements
    override fun hashCode() = elements.hashCode()

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
    override fun asFormatArg() = toString()
    override fun equals(other: Any?) = other is Rt_VirtualTupleValue && elements == other.elements
    override fun hashCode() = elements.hashCode()

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
}

class Rt_StructValue(private val type: R_StructType, private val attributes: MutableList<Rt_Value>): Rt_Value() {
    override fun type() = type
    override fun valueType() = Rt_ValueType.STRUCT
    override fun asStruct() = this
    override fun asFormatArg() = toString()
    override fun equals(other: Any?) = other is Rt_StructValue && attributes == other.attributes
    override fun hashCode() = type.hashCode() * 31 + attributes.hashCode()

    override fun toString() = toString(type.struct, attributes)
    override fun toStrictString(showTupleFieldNames: Boolean) = toStrictString(type, type.struct, attributes)

    fun get(index: Int): Rt_Value {
        return attributes[index]
    }

    fun set(index: Int, value: Rt_Value) {
        attributes[index] = value
    }

    companion object {
        fun toString(struct: R_Struct, attributes: List<out Rt_Value?>): String {
            val attrs = attributes.withIndex().joinToString(",") { (i, attr) ->
                val n = struct.attributesList[i].name
                val v = attr?.toString()
                "$n=$v"
            }
            return "${struct.name}{$attrs}"
        }

        fun toStrictString(type: R_Type, struct: R_Struct, attributes: List<out Rt_Value?>): String {
            val attrs = attributes.indices.joinToString(",") { attributeToStrictString(struct, attributes, it) }
            return "${type.name}[$attrs]"
        }

        private fun attributeToStrictString(struct: R_Struct, attributes: List<out Rt_Value?>, idx: Int): String {
            val name = struct.attributesList[idx].name
            val value = attributes[idx]
            val valueStr = value?.toStrictString()
            return "$name=$valueStr"
        }
    }
}

class Rt_VirtualStructValue(
        gtv: Gtv,
        private val type: R_VirtualStructType,
        private val attributes: List<Rt_Value?>
): Rt_VirtualValue(gtv) {
    override fun type() = type
    override fun valueType() = Rt_ValueType.VIRTUAL_STRUCT
    override fun asVirtualStruct() = this
    override fun asFormatArg() = toString()
    override fun equals(other: Any?) = other is Rt_VirtualStructValue && attributes == other.attributes
    override fun hashCode() = type.hashCode() * 31 + attributes.hashCode()

    override fun toString() = Rt_StructValue.toString(type.innerType.struct, attributes)
    override fun toStrictString(showTupleFieldNames: Boolean) =
            Rt_StructValue.toStrictString(type, type.innerType.struct, attributes)

    fun get(index: Int): Rt_Value {
        val value = attributes[index]
        if (value == null) {
            val typeName = type.innerType.name
            val attr = type.innerType.struct.attributesList[index].name
            throw Rt_Error("virtual_struct:get:novalue:$typeName:$attr", "Attribute '$typeName.$attr' has no value")
        }
        return value
    }

    override fun toFull0(): Rt_Value {
        val fullAttrValues = attributes.map { toFull(it!!) }.toMutableList()
        return Rt_StructValue(type.innerType, fullAttrValues)
    }
}

class Rt_EnumValue(private val type: R_EnumType, private val attr: R_EnumAttr): Rt_Value() {
    override fun type() = type
    override fun valueType() = Rt_ValueType.ENUM
    override fun asEnum() = attr
    override fun asFormatArg() = attr.name
    override fun equals(other: Any?) = other is Rt_EnumValue && attr == other.attr
    override fun hashCode() = type.hashCode() * 31 + attr.value

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
    override fun asJsonString() = str
    override fun asFormatArg() = str
    override fun toString() = str
    override fun toStrictString(showTupleFieldNames: Boolean) = "json[$str]"
    override fun equals(other: Any?) = other is Rt_JsonValue && str == other.str
    override fun hashCode() = str.hashCode()

    companion object {
        fun parse(s: String): Rt_Value {
            if (s.isBlank()) {
                throw IllegalArgumentException(s)
            }

            val mapper = ObjectMapper()

            val json = try {
                mapper.readTree(s)
            } catch (e: JsonProcessingException) {
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
    override fun asRange() = this
    override fun asFormatArg() = toString()
    override fun toString() = "range($start,$end,$step)"
    override fun toStrictString(showTupleFieldNames: Boolean) = "range[$start,$end,$step]"

    override fun iterator(): Iterator<Rt_Value> = RangeIterator(this)

    override fun equals(other: Any?) = other is Rt_RangeValue && start == other.start && end == other.end && step == other.step
    override fun hashCode() = Objects.hash(start, end, step)

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

    override fun toStrictString(showTupleFieldNames: Boolean) = "gtv[$this]"

    override fun toString(): String {
        try {
            return PostchainUtils.gtvToJson(value)
        } catch (e: Exception) {
            return value.toString() // Fallback, just in case (did not happen).
        }
    }

    override fun equals(other: Any?) = other is Rt_GtvValue && value == other.value
    override fun hashCode() = value.hashCode()
}

class Rt_OperationValue(val op: R_MountName, args: List<Rt_Value>): Rt_Value() {
    val args = args.toImmList()

    override fun type() = R_OperationType
    override fun valueType() = Rt_ValueType.OPERATION
    override fun asOperation() = this

    override fun toStrictString(showTupleFieldNames: Boolean): String {
        val argsStr = args.joinToString(",") { it.toStrictString() }
        return "op[$op($argsStr)]"
    }

    override fun toString(): String {
        val argsStr = args.joinToString(",")
        return "$op($argsStr)"
    }

    override fun equals(other: Any?) = other is Rt_OperationValue && op == other.op && args == other.args
    override fun hashCode() = Objects.hash(op, args)
}

class Rt_GtxBlockValue(txs: List<Rt_GtxTxValue>): Rt_Value() {
    val txs = txs.toImmList()

    override fun type() = R_GtxBlockType
    override fun valueType() = Rt_ValueType.GTX_BLOCK
    override fun asGtxBlock() = this

    override fun toStrictString(showTupleFieldNames: Boolean) =
            "${type().toStrictString()}[${txs.joinToString(",") { it.toStrictString() }}]"

    override fun toString() = "block(${txs.joinToString(",")})"

    override fun equals(other: Any?) = other is Rt_GtxBlockValue && txs == other.txs
    override fun hashCode() = Objects.hash(txs)
}

class Rt_GtxTxValue(
        val blockchainRid: BlockchainRid,
        ops: List<Rt_OperationValue>,
        signers: List<Bytes33>,
        signatures: List<Bytes32>
): Rt_Value() {
    val ops = ops.toImmList()
    val signers = signers.toImmList()
    val signatures = signatures.toImmList()

    override fun type() = R_GtxTxType
    override fun valueType() = Rt_ValueType.GTX_TX
    override fun asGtxTx() = this

    override fun toStrictString(showTupleFieldNames: Boolean) =
        "${type().toStrictString()}[${ops.joinToString(",") { it.toStrictString() }}]"

    override fun toString() = "tx(${ops.joinToString(",")})"

    override fun equals(other: Any?) = other is Rt_GtxTxValue && ops == other.ops
    override fun hashCode() = Objects.hash(ops)
}
