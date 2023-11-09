/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.runtime

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.collect.Iterables
import com.google.common.math.LongMath
import mu.KLogging
import net.postchain.gtv.*
import net.postchain.rell.base.lib.type.Lib_BigIntegerMath
import net.postchain.rell.base.lib.type.Lib_DecimalMath
import net.postchain.rell.base.model.*
import net.postchain.rell.base.model.expr.R_FunctionCallTarget
import net.postchain.rell.base.model.expr.R_PartialArgMapping
import net.postchain.rell.base.model.expr.R_PartialCallMapping
import net.postchain.rell.base.runtime.utils.Rt_ValueRecursionDetector
import net.postchain.rell.base.utils.*
import java.math.BigDecimal
import java.math.BigInteger
import java.util.*
import java.util.regex.Pattern
import kotlin.reflect.KClass
import kotlin.reflect.cast

abstract class Rt_ValueRef {
    abstract fun get(): Rt_Value
    abstract fun set(value: Rt_Value)
}

sealed class Rt_ValueType(val name: String) {
    final override fun toString() = name
}

enum class Rt_CoreValueTypes {
    UNIT,
    BOOLEAN,
    INTEGER,
    BIG_INTEGER,
    DECIMAL,
    TEXT,
    BYTE_ARRAY,
    ROWID,
    ENTITY,
    NULL,
    ITERABLE,
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
    FUNCTION,
    VIRTUAL,
    VIRTUAL_COLLECTION,
    VIRTUAL_LIST,
    VIRTUAL_SET,
    VIRTUAL_MAP,
    VIRTUAL_TUPLE,
    VIRTUAL_STRUCT,
    LAZY,
    ;

    fun type(): Rt_ValueType = Rt_CoreValueType(this)
}

private class Rt_CoreValueType(coreType: Rt_CoreValueTypes): Rt_ValueType(coreType.name)

class Rt_LibValueType private constructor(name: String): Rt_ValueType(name) {
    init {
        val v = try { Rt_CoreValueTypes.valueOf(name) } catch (e: java.lang.IllegalArgumentException) { null }
        check(v == null) { name }
    }

    companion object {
        fun of(name: String): Rt_ValueType = Rt_LibValueType(name)
    }
}

abstract class Rt_Value {
    protected abstract val valueType: Rt_ValueType

    abstract fun type(): R_Type

    open fun asBoolean(): Boolean = throw errType(Rt_CoreValueTypes.BOOLEAN)
    open fun asInteger(): Long = throw errType(Rt_CoreValueTypes.INTEGER)
    open fun asBigInteger(): BigInteger = throw errType(Rt_CoreValueTypes.BIG_INTEGER)
    open fun asDecimal(): BigDecimal = throw errType(Rt_CoreValueTypes.DECIMAL)
    open fun asRowid(): Long = throw errType(Rt_CoreValueTypes.ROWID)
    open fun asString(): String = throw errType(Rt_CoreValueTypes.TEXT)
    open fun asByteArray(): ByteArray = throw errType(Rt_CoreValueTypes.BYTE_ARRAY)
    open fun asJsonString(): String = throw errType(Rt_CoreValueTypes.JSON)
    open fun asIterable(): Iterable<Rt_Value> = throw errType(Rt_CoreValueTypes.ITERABLE)
    open fun asCollection(): MutableCollection<Rt_Value> = throw errType(Rt_CoreValueTypes.COLLECTION)
    open fun asList(): MutableList<Rt_Value> = throw errType(Rt_CoreValueTypes.LIST)
    open fun asVirtualCollection(): Rt_VirtualCollectionValue = throw errType(Rt_CoreValueTypes.VIRTUAL_COLLECTION)
    open fun asVirtualList(): Rt_VirtualListValue = throw errType(Rt_CoreValueTypes.VIRTUAL_LIST)
    open fun asVirtualSet(): Rt_VirtualSetValue = throw errType(Rt_CoreValueTypes.VIRTUAL_SET)
    open fun asSet(): MutableSet<Rt_Value> = throw errType(Rt_CoreValueTypes.SET)
    open fun asMap(): Map<Rt_Value, Rt_Value> = throw errType(Rt_CoreValueTypes.MAP)
    open fun asMutableMap(): MutableMap<Rt_Value, Rt_Value> = throw errType(Rt_CoreValueTypes.MUTABLE_MAP)
    open fun asMapValue(): Rt_MapValue = throw errType(Rt_CoreValueTypes.MAP)
    open fun asTuple(): List<Rt_Value> = throw errType(Rt_CoreValueTypes.TUPLE)
    open fun asVirtualTuple(): Rt_VirtualTupleValue = throw errType(Rt_CoreValueTypes.VIRTUAL_TUPLE)
    open fun asStruct(): Rt_StructValue = throw errType(Rt_CoreValueTypes.STRUCT)
    open fun asVirtual(): Rt_VirtualValue = throw errType(Rt_CoreValueTypes.VIRTUAL)
    open fun asVirtualStruct(): Rt_VirtualStructValue = throw errType(Rt_CoreValueTypes.VIRTUAL_STRUCT)
    open fun asEnum(): R_EnumAttr = throw errType(Rt_CoreValueTypes.ENUM)
    open fun asRange(): Rt_RangeValue = throw errType(Rt_CoreValueTypes.RANGE)
    open fun asObjectId(): Long = throw errType(Rt_CoreValueTypes.ENTITY)
    open fun asGtv(): Gtv = throw errType(Rt_CoreValueTypes.GTV)
    open fun asFunction(): Rt_FunctionValue = throw errType(Rt_CoreValueTypes.FUNCTION)
    open fun asLazyValue(): Rt_Value = throw errType(Rt_CoreValueTypes.LAZY)

    fun <T: Rt_Value> asType(cls: KClass<T>, valueType: Rt_ValueType): T {
        if (!cls.isInstance(this)) {
            throw errType(valueType)
        }
        return cls.cast(this)
    }

    open fun toFormatArg(): Any = toString()

    abstract fun str(): String
    abstract fun strCode(showTupleFieldNames: Boolean = true): String

    final override fun toString(): String {
        // Calling toString() is considered wrong. Throwing exception in unit tests and returning str() in production
        // mode as a fallback.
        CommonUtils.failIfUnitTest()
        return str()
    }

    private fun errType(expected: Rt_CoreValueTypes) = errType(expected.type())
    private fun errType(expected: Rt_ValueType) = Rt_ValueTypeError.exception(expected, valueType)
}

sealed class Rt_VirtualValue(val gtv: Gtv): Rt_Value() {
    override fun asVirtual() = this

    fun toFull(): Rt_Value {
        if (gtv is GtvVirtual) {
            val typeStr = type().name
            throw Rt_Exception.common("virtual:to_full:notfull:$typeStr", "Value of type $typeStr is not full")
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
    override val valueType = Rt_CoreValueTypes.UNIT.type()

    override fun type() = R_UnitType
    override fun strCode(showTupleFieldNames: Boolean) = "unit"
    override fun str() = "unit"
}

class Rt_BooleanValue private constructor(val value: Boolean): Rt_Value() {
    override val valueType = Rt_CoreValueTypes.BOOLEAN.type()

    override fun type() = R_BooleanType
    override fun asBoolean() = value
    override fun toFormatArg() = value
    override fun strCode(showTupleFieldNames: Boolean) = "boolean[$value]"
    override fun str() = "" + value
    override fun equals(other: Any?) = other is Rt_BooleanValue && value == other.value
    override fun hashCode() = java.lang.Boolean.hashCode(value)

    companion object {
        val TRUE: Rt_Value = Rt_BooleanValue(true)
        val FALSE: Rt_Value = Rt_BooleanValue(false)

        val ALL_VALUES: Set<Rt_Value> = immSetOf(FALSE, TRUE)

        fun get(value: Boolean): Rt_Value {
            return if (value) TRUE else FALSE
        }
    }
}

class Rt_IntValue private constructor(val value: Long): Rt_Value() {
    override val valueType = Rt_CoreValueTypes.INTEGER.type()

    override fun type() = R_IntegerType
    override fun asInteger() = value
    override fun toFormatArg() = value
    override fun strCode(showTupleFieldNames: Boolean) = "int[$value]"
    override fun str() = "" + value
    override fun equals(other: Any?) = other is Rt_IntValue && value == other.value
    override fun hashCode() = java.lang.Long.hashCode(value)

    companion object {
        private const val NVALUES = 1000

        private val VALUES: List<Rt_Value> = (-NVALUES .. NVALUES).map { Rt_IntValue(it.toLong()) }.toImmList()

        val ZERO: Rt_Value = get(0)

        fun get(v: Long): Rt_Value {
            return if (v >= -NVALUES && v <= NVALUES) {
                VALUES[(v + NVALUES).toInt()]
            } else {
                Rt_IntValue(v)
            }
        }
    }
}

class Rt_BigIntegerValue private constructor(val value: BigInteger): Rt_Value() {
    override val valueType = Rt_CoreValueTypes.BIG_INTEGER.type()

    override fun type() = R_BigIntegerType
    override fun asBigInteger() = value
    override fun toFormatArg() = value
    override fun strCode(showTupleFieldNames: Boolean) = "bigint[${str()}]"
    override fun str() = value.toString()
    override fun equals(other: Any?) = other === this || (other is Rt_BigIntegerValue && value == other.value)
    override fun hashCode() = value.hashCode()

    companion object : KLogging() {
        val ZERO = Rt_BigIntegerValue(BigInteger.ZERO)

        fun get(v: BigInteger): Rt_Value {
            if (v.signum() == 0) {
                return ZERO
            }

            val res = getTry(v)
            if (res != null) {
                return res
            }

            val p = Lib_BigIntegerMath.PRECISION
            val msg = "Big integer value out of range (allowed range is -10^$p..10^$p, exclusive)"
            throw Rt_Exception.common("bigint:overflow", msg)
        }

        fun getTry(v: BigInteger): Rt_Value? {
            return if (v < Lib_BigIntegerMath.MIN_VALUE || v > Lib_BigIntegerMath.MAX_VALUE) null else Rt_BigIntegerValue(v)
        }

        fun get(v: BigDecimal): Rt_Value {
            val bigInt = try {
                v.toBigIntegerExact()
            } catch (e: ArithmeticException) {
                throw Rt_Exception.common("bigint:nonint:$v", "Value is not an integer: '$v'")
            }
            return get(bigInt)
        }

        fun get(s: String): Rt_Value {
            val v = try {
                BigInteger(s)
            } catch (e: NumberFormatException) {
                throw Rt_Exception.common("bigint:invalid:$s", "Invalid big integer value: '$s'")
            }
            return get(v)
        }

        fun get(v: Long): Rt_Value {
            val bi = BigInteger.valueOf(v)
            return get(bi)
        }
    }
}

class Rt_DecimalValue private constructor(val value: BigDecimal): Rt_Value() {
    override val valueType = Rt_CoreValueTypes.DECIMAL.type()

    override fun type() = R_DecimalType
    override fun asDecimal() = value
    override fun toFormatArg() = value
    override fun strCode(showTupleFieldNames: Boolean) = "dec[${str()}]"
    override fun str() = Lib_DecimalMath.toString(value)
    override fun equals(other: Any?) = other === this || (other is Rt_DecimalValue && value == other.value)
    override fun hashCode() = value.hashCode()

    companion object : KLogging() {
        val ZERO = Rt_DecimalValue(BigDecimal.ZERO)

        fun get(v: BigDecimal): Rt_Value {
            val t = v.unscaledValue()
            if (t.signum() == 0) {
                return ZERO
            }

            val res = getTry(v)
            return res ?: throw errOverflow("decimal:overflow", "Decimal value out of range")
        }

        fun getTry(v: BigDecimal): Rt_Value? {
            val t = Lib_DecimalMath.scale(v)
            return if (t == null) null else Rt_DecimalValue(t)
        }

        fun get(s: String): Rt_Value {
            val v = try {
                Lib_DecimalMath.parse(s)
            } catch (e: NumberFormatException) {
                throw Rt_Exception.common("decimal:invalid:$s", "Invalid decimal value: '$s'")
            }
            return get(v)
        }

        fun get(v: Long): Rt_Value {
            val bd = BigDecimal(v)
            return get(bd)
        }

        fun errOverflow(code: String, msg: String): Rt_Exception {
            val p = Lib_DecimalMath.DECIMAL_INT_DIGITS
            return Rt_Exception.common(code, "$msg (allowed range is -10^$p..10^$p, exclusive)")
        }
    }
}

class Rt_TextValue private constructor(val value: String): Rt_Value() {
    override val valueType = Rt_CoreValueTypes.TEXT.type()

    override fun type() = R_TextType
    override fun asString() = value
    override fun toFormatArg() = value

    override fun strCode(showTupleFieldNames: Boolean): String {
        val esc = escape(value)
        return "text[$esc]"
    }

    override fun str(): String = value
    override fun equals(other: Any?) = other === this || (other is Rt_TextValue && value == other.value)
    override fun hashCode() = value.hashCode()

    companion object {
        val EMPTY: Rt_Value = Rt_TextValue("")

        fun get(s: String): Rt_Value {
            return if (s.isEmpty()) EMPTY else Rt_TextValue(s)
        }

        fun like(s: String, pattern: String): Boolean {
            val regex = likePatternToRegex(pattern, '_', '%')
            val m = regex.matcher(s)
            return m.matches()
        }

        fun likePatternToRegex(pattern: String, one: Char, many: Char): Pattern {
            val buf = StringBuilder()
            val raw = StringBuilder()
            var esc = false

            for (c in pattern) {
                if (esc) {
                    raw.append(c)
                    esc = false
                } else if (c == '\\') {
                    esc = true
                } else if (c == one || c == many) {
                    if (raw.isNotEmpty()) buf.append(Pattern.quote(raw.toString()))
                    raw.setLength(0)
                    buf.append(if (c == many) ".*" else ".")
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

class Rt_ByteArrayValue private constructor(private val value: ByteArray): Rt_Value() {
    override val valueType = Rt_CoreValueTypes.BYTE_ARRAY.type()

    override fun type() = R_ByteArrayType
    override fun asByteArray() = value
    override fun toFormatArg() = str()
    override fun strCode(showTupleFieldNames: Boolean) = "byte_array[${CommonUtils.bytesToHex(value)}]"
    override fun str() = "0x" + CommonUtils.bytesToHex(value)
    override fun equals(other: Any?) = other === this || (other is Rt_ByteArrayValue && value.contentEquals(other.value))
    override fun hashCode() = value.contentHashCode()

    override fun asIterable(): Iterable<Rt_Value> {
        return Iterables.transform(value.asIterable()) {
            val signed = it!!.toInt()
            val unsigned = if (signed >= 0) signed else (signed + 256)
            Rt_IntValue.get(unsigned.toLong())
        }
    }

    companion object {
        val EMPTY: Rt_Value = Rt_ByteArrayValue(ByteArray(0))

        fun get(value: ByteArray): Rt_Value {
            return if (value.isEmpty()) EMPTY else Rt_ByteArrayValue(value)
        }
    }
}

class Rt_RowidValue private constructor(val value: Long): Rt_Value() {
    init {
        check(value >= 0) { "Negative rowid value: $value" }
    }

    override val valueType = Rt_CoreValueTypes.ROWID.type()

    override fun type() = R_RowidType
    override fun asRowid() = value
    override fun toFormatArg() = value
    override fun strCode(showTupleFieldNames: Boolean) = "rowid[$value]"
    override fun str() = "" + value
    override fun equals(other: Any?) = other is Rt_RowidValue && value == other.value
    override fun hashCode() = java.lang.Long.hashCode(value)

    companion object {
        private val VALUES: List<Rt_Value> = (0 .. 1000).map { Rt_RowidValue(it.toLong()) }.toImmList()

        val ZERO = VALUES[0]

        fun get(value: Long): Rt_Value {
            return if (value >= 0 && value < VALUES.size) VALUES[value.toInt()] else Rt_RowidValue(value)
        }
    }
}

class Rt_EntityValue(val type: R_EntityType, val rowid: Long): Rt_Value() {
    override val valueType = Rt_CoreValueTypes.ENTITY.type()

    override fun type() = type
    override fun asObjectId() = rowid
    override fun toFormatArg() = str()
    override fun strCode(showTupleFieldNames: Boolean) = "${type.name}[$rowid]"
    override fun str() = strCode()
    override fun equals(other: Any?) = other === this || (other is Rt_EntityValue && type == other.type && rowid == other.rowid)
    override fun hashCode() = Objects.hash(type, rowid)
}

object Rt_NullValue: Rt_Value() {
    override val valueType = Rt_CoreValueTypes.NULL.type()

    override fun type() = R_NullType
    override fun toFormatArg() = str()
    override fun strCode(showTupleFieldNames: Boolean) = "null"
    override fun str() = "null"
}

class Rt_ListValue(private val type: R_Type, private val elements: MutableList<Rt_Value>): Rt_Value() {
    init {
        check(type is R_ListType) { "wrong type: $type" }
    }

    override val valueType = Rt_CoreValueTypes.LIST.type()

    override fun type() = type
    override fun asIterable(): Iterable<Rt_Value> = elements
    override fun asCollection() = elements
    override fun asList() = elements
    override fun toFormatArg() = elements

    override fun strCode(showTupleFieldNames: Boolean) = strCode(type, elements)
    override fun str() = elements.joinToString(", ", "[", "]") { it.str() }
    override fun equals(other: Any?) = other === this || (other is Rt_ListValue && elements == other.elements)
    override fun hashCode() = elements.hashCode()

    companion object {
        fun checkIndex(size: Int, index: Long) {
            if (index < 0 || index >= size) {
                throw Rt_Exception.common("list:index:$size:$index", "List index out of bounds: $index (size $size)")
            }
        }

        fun strCode(type: R_Type, elements: List<out Rt_Value?>): String {
            val elems = elements.joinToString(",") { it?.strCode(false) ?: "null" }
            return "${type.strCode()}[$elems]"
        }
    }
}

sealed class Rt_VirtualCollectionValue(gtv: Gtv): Rt_VirtualValue(gtv) {
    override fun asVirtualCollection() = this
    abstract fun size(): Int
    abstract override fun asIterable(): Iterable<Rt_Value>
}

class Rt_VirtualListValue(
        gtv: Gtv,
        private val type: R_VirtualListType,
        private val elements: List<Rt_Value?>
): Rt_VirtualCollectionValue(gtv) {
    override val valueType = Rt_CoreValueTypes.VIRTUAL_LIST.type()

    override fun type() = type
    override fun asVirtualCollection() = this
    override fun asVirtualList() = this
    override fun toFormatArg() = elements
    override fun strCode(showTupleFieldNames: Boolean) = Rt_ListValue.strCode(type, elements)
    override fun str() = elements.joinToString(", ", "[", "]") { it?.str() ?: "null" }
    override fun equals(other: Any?) = other === this || (other is Rt_VirtualListValue && elements == other.elements)
    override fun hashCode() = elements.hashCode()

    override fun toFull0(): Rt_Value {
        val resElements = elements.map { toFull(it!!) }.toMutableList()
        return Rt_ListValue(type.innerType, resElements)
    }

    override fun size() = elements.size
    override fun asIterable() = elements.filterNotNull()

    fun contains(index: Long) = index >= 0 && index < elements.size && elements[index.toInt()] != null

    fun get(index: Long): Rt_Value {
        Rt_ListValue.checkIndex(elements.size, index)
        val value = elements[index.toInt()]
        if (value == null) {
            throw Rt_Exception.common("virtual_list:get:novalue:$index", "Element $index has no value")
        }
        return value
    }
}

class Rt_SetValue(private val type: R_Type, private val elements: MutableSet<Rt_Value>): Rt_Value() {
    init {
        check(type is R_SetType) { "wrong type: $type" }
    }

    override val valueType = Rt_CoreValueTypes.SET.type()

    override fun type() = type
    override fun asIterable(): Iterable<Rt_Value> = elements
    override fun asCollection() = elements
    override fun asSet() = elements
    override fun toFormatArg() = elements
    override fun strCode(showTupleFieldNames: Boolean) = strCode(type, elements, showTupleFieldNames)
    override fun str() = elements.joinToString(", ", "[", "]") { it.str() }
    override fun equals(other: Any?) = other === this || (other is Rt_SetValue && elements == other.elements)
    override fun hashCode() = elements.hashCode()

    companion object {
        fun strCode(type: R_Type, elements: Set<Rt_Value>, showTupleFieldNames: Boolean): String =
                "${type.strCode()}[${elements.joinToString(",") { it.strCode(false) }}]"
    }
}

class Rt_VirtualSetValue(
        gtv: Gtv,
        private val type: R_VirtualSetType,
        private val elements: Set<Rt_Value>
): Rt_VirtualCollectionValue(gtv) {
    override val valueType = Rt_CoreValueTypes.VIRTUAL_SET.type()

    override fun type() = type
    override fun asVirtualCollection() = this
    override fun asVirtualSet() = this
    override fun toFormatArg() = elements
    override fun strCode(showTupleFieldNames: Boolean) = Rt_SetValue.strCode(type, elements, showTupleFieldNames)
    override fun str() = elements.joinToString(", ", "[", "]") { it.str() }
    override fun equals(other: Any?) = other === this || (other is Rt_VirtualSetValue && elements == other.elements)
    override fun hashCode() = elements.hashCode()

    override fun toFull0(): Rt_Value {
        val resElements = elements.map { toFull(it) }.toMutableSet()
        return Rt_SetValue(type.innerType, resElements)
    }

    override fun size() = elements.size
    override fun asIterable() = elements

    fun contains(value: Rt_Value) = elements.contains(value)
}

class Rt_MapValue(val type: R_MapType, map: MutableMap<Rt_Value, Rt_Value>): Rt_Value() {
    val map: Map<Rt_Value, Rt_Value> = map

    private val mutableMap = map

    override val valueType = Rt_CoreValueTypes.MAP.type()

    override fun type() = type
    override fun asMap() = map
    override fun asMutableMap() = mutableMap
    override fun asMapValue() = this
    override fun toFormatArg() = map
    override fun strCode(showTupleFieldNames: Boolean) = strCode(type, showTupleFieldNames, map)
    override fun str() = map.entries.joinToString(", ", "{", "}") { "${it.key.str()}=${it.value.str()}" }
    override fun equals(other: Any?) = other === this || (other is Rt_MapValue && map == other.map)
    override fun hashCode() = map.hashCode()

    override fun asIterable(): Iterable<Rt_Value> {
        return asIterable(false)
    }

    fun asIterable(legacy: Boolean): Iterable<Rt_Value> {
        val entryType = if (legacy) type.legacyEntryType else type.entryType
        return Iterables.transform(map.entries) { entry ->
            Rt_TupleValue(entryType, immListOf(entry.key, entry.value))
        }
    }

    companion object {
        fun strCode(type: R_Type, showTupleFieldNames: Boolean, map: Map<Rt_Value, Rt_Value>): String {
            val entries = map.entries.joinToString(",") { (key, value) ->
                key.strCode(false) + "=" + value.strCode(false)
            }
            return "${type.strCode()}[$entries]"
        }
    }
}

class Rt_VirtualMapValue(
    gtv: Gtv,
    private val type: R_VirtualMapType,
    private val map: Map<Rt_Value, Rt_Value>,
): Rt_VirtualValue(gtv) {
    override val valueType = Rt_CoreValueTypes.VIRTUAL_MAP.type()

    override fun type() = type
    override fun asMap() = map
    override fun toFormatArg() = map
    override fun strCode(showTupleFieldNames: Boolean) = Rt_MapValue.strCode(type, showTupleFieldNames, map)
    override fun str() = map.entries.joinToString(", ", "{", "}") { "${it.key.str()}=${it.value.str()}" }
    override fun equals(other: Any?) = other === this || (other is Rt_VirtualMapValue && map == other.map)
    override fun hashCode() = map.hashCode()

    override fun asIterable(): Iterable<Rt_Value> {
        return Iterables.transform(map.entries) { entry ->
            Rt_TupleValue(type.virtualEntryType, immListOf(entry.key, entry.value))
        }
    }

    override fun toFull0(): Rt_Value {
        val resMap = map
                .mapKeys { (k, _) -> toFull(k) }
                .mapValues { (_, v) -> toFull(v) }
                .toMutableMap()
        return Rt_MapValue(type.innerType, resMap)
    }
}

class Rt_TupleValue(val type: R_TupleType, val elements: List<Rt_Value>): Rt_Value() {
    init {
        checkEquals(elements.size, type.fields.size)
    }

    override val valueType = Rt_CoreValueTypes.TUPLE.type()

    override fun type() = type
    override fun asTuple() = elements
    override fun toFormatArg() = str()
    override fun equals(other: Any?) = other === this || (other is Rt_TupleValue && elements == other.elements)
    override fun hashCode() = elements.hashCode()

    override fun str() = str("", type, elements)
    override fun strCode(showTupleFieldNames: Boolean) = strCode("", type, elements, showTupleFieldNames)

    companion object {
        fun make(type: R_TupleType, vararg elements: Rt_Value): Rt_Value {
            return Rt_TupleValue(type, elements.toImmList())
        }

        fun str(prefix: String, type: R_TupleType, elements: List<Rt_Value?>): String {
            val elems = elements.indices.joinToString(",") { elementStr(type, elements, it) }
            return "$prefix($elems)"
        }

        private fun elementStr(type: R_TupleType, elements: List<Rt_Value?>, idx: Int): String {
            val name = type.fields[idx].name
            val value = elements[idx]
            val valueStr = value?.str() ?: "null"
            return if (name == null) valueStr else "$name=$valueStr"
        }

        fun strCode(prefix: String, type: R_TupleType, elements: List<Rt_Value?>, showTupleFieldNames: Boolean): String {
            val elems = elements.indices.joinToString(",") {
                elementStrCode(type, elements, showTupleFieldNames, it)
            }
            return "$prefix($elems)"
        }

        private fun elementStrCode(
            type: R_TupleType,
            elements: List<Rt_Value?>,
            showTupleFieldNames: Boolean,
            idx: Int,
        ): String {
            val name = type.fields[idx].name
            val value = elements[idx]
            val valueStr = value?.strCode() ?: "null"
            return if (name == null || !showTupleFieldNames) valueStr else "$name=$valueStr"
        }
    }
}

class Rt_VirtualTupleValue(
        gtv: Gtv,
        private val type: R_VirtualTupleType,
        private val elements: List<Rt_Value?>
): Rt_VirtualValue(gtv) {
    override val valueType = Rt_CoreValueTypes.VIRTUAL_TUPLE.type()

    override fun type() = type
    override fun asVirtualTuple() = this
    override fun toFormatArg() = str()
    override fun equals(other: Any?) = other === this || (other is Rt_VirtualTupleValue && elements == other.elements)
    override fun hashCode() = elements.hashCode()

    override fun str() = Rt_TupleValue.str("virtual", type.innerType, elements)
    override fun strCode(showTupleFieldNames: Boolean) =
            Rt_TupleValue.strCode("virtual", type.innerType, elements, showTupleFieldNames)

    override fun toFull0(): Rt_Value {
        val resElements = elements.map { toFull(it!!) }
        return Rt_TupleValue(type.innerType, resElements)
    }

    fun get(index: Int): Rt_Value {
        val value = elements[index]
        if (value == null) {
            val attr = type.innerType.fields[index].name ?: "$index"
            throw Rt_Exception.common("virtual_tuple:get:novalue:$attr", "Field '$attr' has no value")
        }
        return value
    }
}

class Rt_StructValue(private val type: R_StructType, private val attributes: MutableList<Rt_Value>): Rt_Value() {
    override val valueType = Rt_CoreValueTypes.STRUCT.type()

    override fun type() = type
    override fun asStruct() = this
    override fun toFormatArg() = str()
    override fun equals(other: Any?) = other === this || (other is Rt_StructValue && attributes == other.attributes)
    override fun hashCode() = type.hashCode() * 31 + attributes.hashCode()

    override fun str() = str(this, type, type.struct, attributes)
    override fun strCode(showTupleFieldNames: Boolean) = strCode(this, type, type.struct, attributes)

    fun get(index: Int): Rt_Value {
        return attributes[index]
    }

    fun set(index: Int, value: Rt_Value) {
        attributes[index] = value
    }

    class Builder(private val type: R_StructType) {
        private val v0: Rt_Value = Rt_RangeValue(0, 0, 0)
        private val values = MutableList(type.struct.attributes.size) { v0 }
        private var done = false

        fun set(attr: R_Attribute, value: Rt_Value) {
            check(!done)
            require(value !== v0)
            val index = attr.index
            require(values[index] === v0) { "$index $attr" }
            values[index] = value
        }

        fun build(): Rt_Value {
            check(!done)
            done = true
            for (index in values.indices) {
                require(values[index] !== v0) { index }
            }
            return Rt_StructValue(type, values)
        }
    }

    companion object {
        private val STR_RECURSION_DETECTOR = Rt_ValueRecursionDetector()

        fun str(self: Rt_Value, type: R_Type, struct: R_Struct, attributes: List<out Rt_Value?>): String {
            return STR_RECURSION_DETECTOR.calculate(self) {
                val attrs = attributes.withIndex().joinToString(",") { (i, attr) ->
                    val n = struct.attributesList[i].name
                    val v = attr?.str()
                    "$n=$v"
                }
                "${type.name}{$attrs}"
            } ?: "${type.name}{...}"
        }

        fun strCode(self: Rt_Value, type: R_Type, struct: R_Struct, attributes: List<out Rt_Value?>): String {
            return STR_RECURSION_DETECTOR.calculate(self) {
                val attrs = attributes.indices.joinToString(",") { attributeStrCode(struct, attributes, it) }
                "${type.name}[$attrs]"
            } ?: "${type.name}[...]"
        }

        private fun attributeStrCode(struct: R_Struct, attributes: List<out Rt_Value?>, idx: Int): String {
            val name = struct.attributesList[idx].name
            val value = attributes[idx]
            val valueStr = value?.strCode()
            return "$name=$valueStr"
        }
    }
}

class Rt_VirtualStructValue(
        gtv: Gtv,
        private val type: R_VirtualStructType,
        private val attributes: List<Rt_Value?>
): Rt_VirtualValue(gtv) {
    override val valueType = Rt_CoreValueTypes.VIRTUAL_STRUCT.type()

    override fun type() = type
    override fun asVirtualStruct() = this
    override fun toFormatArg() = str()
    override fun equals(other: Any?) = other === this || (other is Rt_VirtualStructValue && attributes == other.attributes)
    override fun hashCode() = type.hashCode() * 31 + attributes.hashCode()

    override fun str() = Rt_StructValue.str(this, type, type.innerType.struct, attributes)
    override fun strCode(showTupleFieldNames: Boolean) =
            Rt_StructValue.strCode(this, type, type.innerType.struct, attributes)

    fun get(index: Int): Rt_Value {
        val value = attributes[index]
        if (value == null) {
            val typeName = type.innerType.name
            val attr = type.innerType.struct.attributesList[index].name
            throw Rt_Exception.common("virtual_struct:get:novalue:$typeName:$attr", "Attribute '$typeName.$attr' has no value")
        }
        return value
    }

    override fun toFull0(): Rt_Value {
        val fullAttrValues = attributes.map { toFull(it!!) }.toMutableList()
        return Rt_StructValue(type.innerType, fullAttrValues)
    }
}

class Rt_ObjectValue(private val type: R_ObjectType): Rt_Value() {
    override val valueType = Rt_CoreValueTypes.OBJECT.type()

    override fun type() = type
    override fun strCode(showTupleFieldNames: Boolean) = type.name
    override fun str() = type.name
}

class Rt_JsonValue private constructor(private val str: String): Rt_Value() {
    override val valueType = Rt_CoreValueTypes.JSON.type()

    override fun type() = R_JsonType
    override fun asJsonString() = str
    override fun toFormatArg() = str
    override fun str() = str
    override fun strCode(showTupleFieldNames: Boolean) = "json[$str]"
    override fun equals(other: Any?) = other === this || (other is Rt_JsonValue && str == other.str)
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
    override val valueType = Rt_CoreValueTypes.RANGE.type()

    override fun type() = R_RangeType
    override fun asRange() = this
    override fun toFormatArg() = str()
    override fun str() = "range($start,$end,$step)"
    override fun strCode(showTupleFieldNames: Boolean) = "range[$start,$end,$step]"

    override fun asIterable(): Iterable<Rt_Value> = this
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
                return Rt_IntValue.get(res)
            }
        }
    }
}

class Rt_GtvValue private constructor(val value: Gtv): Rt_Value() {
    override val valueType = Rt_CoreValueTypes.GTV.type()

    override fun type() = R_GtvType
    override fun asGtv() = value

    override fun strCode(showTupleFieldNames: Boolean) = "gtv[${str()}]"
    override fun str() = toString(value)

    override fun equals(other: Any?) = other === this || (other is Rt_GtvValue && value == other.value)
    override fun hashCode() = value.hashCode()

    companion object {
        val NULL: Rt_Value = Rt_GtvValue(GtvNull)

        private val ZERO_INTEGER: Rt_Value = Rt_GtvValue(GtvFactory.gtv(0))
        private val ZERO_BIG_INTEGER: Rt_Value = Rt_GtvValue(GtvFactory.gtv(BigInteger.ZERO))
        private val EMPTY_STRING: Rt_Value = Rt_GtvValue(GtvFactory.gtv(""))
        private val EMPTY_BYTE_ARRAY: Rt_Value = Rt_GtvValue(GtvFactory.gtv(ByteArray(0)))
        private val EMPTY_ARRAY: Rt_Value = Rt_GtvValue(GtvFactory.gtv(immListOf()))
        private val EMPTY_DICT: Rt_Value = Rt_GtvValue(GtvFactory.gtv(immMapOf()))

        fun get(value: Gtv): Rt_Value {
            return when (value) {
                GtvNull -> NULL
                is GtvInteger -> if (value.integer == 0L) ZERO_INTEGER else Rt_GtvValue(value)
                is GtvBigInteger -> if (value.integer == BigInteger.ZERO) ZERO_BIG_INTEGER else Rt_GtvValue(value)
                is GtvString -> if (value.string.isEmpty()) EMPTY_STRING else Rt_GtvValue(value)
                is GtvByteArray -> if (value.bytearray.isEmpty()) EMPTY_BYTE_ARRAY else Rt_GtvValue(value)
                is GtvArray -> if (value.array.isEmpty()) EMPTY_ARRAY else Rt_GtvValue(value)
                is GtvDictionary -> if (value.dict.isEmpty()) EMPTY_DICT else Rt_GtvValue(value)
                else -> Rt_GtvValue(value)
            }
        }

        fun toString(value: Gtv): String {
            return try {
                PostchainGtvUtils.gtvToJson(value)
            } catch (e: Exception) {
                value.toString() // Fallback, just in case (did not happen).
            }
        }
    }
}

class Rt_FunctionValue(
        private val type: R_Type,
        private val mapping: R_PartialCallMapping,
        private val target: R_FunctionCallTarget,
        private val baseValue: Rt_Value?,
        exprValues: List<Rt_Value>
): Rt_Value() {
    private val exprValues = let {
        checkEquals(exprValues.size, mapping.exprCount)
        exprValues.toImmList()
    }

    override val valueType = Rt_CoreValueTypes.FUNCTION.type()

    override fun type() = type
    override fun asFunction() = this

    override fun strCode(showTupleFieldNames: Boolean): String {
        return STR_RECURSION_DETECTOR.calculate(this) {
            val argsStr = mapping.args.joinToString(",") { if (it.wild) "*" else exprValues[it.index].strCode() }
            "fn[${target.strCode(baseValue)}($argsStr)]"
        } ?: "fn[...]"
    }

    override fun str() = "${target.str(baseValue)}(*)"

    fun call(callCtx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        checkEquals(args.size, mapping.wildCount)
        val combinedArgs = mapping.args.map { if (it.wild) args[it.index] else exprValues[it.index] }
        return target.call(callCtx, baseValue, combinedArgs)
    }

    fun combine(newType: R_Type, newMapping: R_PartialCallMapping, newArgs: List<Rt_Value>): Rt_Value {
        checkEquals(newMapping.args.size, mapping.wildCount)
        checkEquals(newArgs.size, newMapping.exprCount)

        val resExprValues = exprValues + newArgs

        val resArgMappings = mapping.args.map { m1 ->
            if (m1.wild) {
                val m2 = newMapping.args[m1.index]
                if (m2.wild) m2 else R_PartialArgMapping(false, mapping.exprCount + m2.index)
            } else {
                m1
            }
        }

        val resMapping = R_PartialCallMapping(resExprValues.size, newMapping.wildCount, resArgMappings)
        return Rt_FunctionValue(newType, resMapping, target, baseValue, resExprValues)
    }

    companion object {
        private val STR_RECURSION_DETECTOR = Rt_ValueRecursionDetector()
    }
}
