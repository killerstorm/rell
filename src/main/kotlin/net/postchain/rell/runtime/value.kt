package net.postchain.rell.runtime

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import net.postchain.rell.model.*
import net.postchain.rell.toHex
import java.lang.IllegalArgumentException
import java.util.*

sealed class RtValue {
    abstract fun type(): RType

    open fun asBoolean(): Boolean = throw errType()
    open fun asInteger(): Long = throw errType()
    open fun asString(): String = throw errType()
    open fun asByteArray(): ByteArray = throw errType()
    open fun asJsonString(): String = throw errType()
    open fun asCollection(): MutableCollection<RtValue> = throw errType()
    open fun asList(): MutableList<RtValue> = throw errType()
    open fun asSet(): MutableSet<RtValue> = throw errType()
    open fun asMap(): MutableMap<RtValue, RtValue> = throw errType()
    open fun asTuple(): List<RtValue> = throw errType()
    open fun asRange(): RtRangeValue = throw errType()
    open fun asObjectId(): Long = throw errType()

    abstract fun toStrictString(showTupleFieldNames: Boolean = true): String

    private fun errType() = IllegalStateException("$javaClass")
}

object RtUnitValue: RtValue() {
    override fun type(): RType = RUnitType
    override fun toStrictString(showTupleFieldNames: Boolean): String = "unit"
    override fun toString(): String = "unit"
}

class RtBooleanValue(val value: Boolean): RtValue() {
    override fun type(): RType = RBooleanType
    override fun asBoolean(): Boolean = value
    override fun toStrictString(showTupleFieldNames: Boolean): String = "boolean[$value]"
    override fun toString(): String = "" + value
    override fun equals(other: Any?): Boolean = other is RtBooleanValue && value == other.value
    override fun hashCode(): Int = java.lang.Boolean.hashCode(value)
}

class RtIntValue(val value: Long): RtValue() {
    override fun type(): RType = RIntegerType
    override fun asInteger(): Long = value
    override fun toStrictString(showTupleFieldNames: Boolean): String = "int[$value]"
    override fun toString(): String = "" + value
    override fun equals(other: Any?): Boolean = other is RtIntValue && value == other.value
    override fun hashCode(): Int = java.lang.Long.hashCode(value)
}

class RtTextValue(val value: String): RtValue() {
    override fun type(): RType = RTextType
    override fun asString(): String = value
    override fun toStrictString(showTupleFieldNames: Boolean): String = "text[$value]"
    override fun toString(): String = value
    override fun equals(other: Any?): Boolean = other is RtTextValue && value == other.value
    override fun hashCode(): Int = value.hashCode()
}

class RtByteArrayValue(val value: ByteArray): RtValue() {
    override fun type(): RType = RByteArrayType
    override fun asByteArray(): ByteArray = value
    override fun toStrictString(showTupleFieldNames: Boolean): String = "byte_array[${value.toHex()}]"
    override fun toString(): String = "0x" + value.toHex()
    override fun equals(other: Any?): Boolean = other is RtByteArrayValue && Arrays.equals(value, other.value)
    override fun hashCode(): Int = Arrays.hashCode(value)
}

class RtObjectValue(val type: RInstanceRefType, val rowid: Long): RtValue() {
    override fun type(): RType = type
    override fun asObjectId(): Long = rowid
    override fun toStrictString(showTupleFieldNames: Boolean): String = "${type.name}[$rowid]"
    override fun toString(): String = toStrictString()
    override fun equals(other: Any?): Boolean = other is RtObjectValue && type == other.type && rowid == other.rowid
    override fun hashCode(): Int = Objects.hash(type, rowid)
}

class RtNullValue(val type: RType): RtValue() {
    override fun type(): RType = type
    override fun toStrictString(showTupleFieldNames: Boolean): String = "null[${type.toStrictString()}]"
    override fun toString(): String = "null"
    override fun equals(other: Any?): Boolean = other is RtNullValue
    override fun hashCode(): Int = javaClass.hashCode()
}

class RtListValue(private val type: RType, private val elements: MutableList<RtValue>): RtValue() {
    override fun type(): RType = type
    override fun asCollection(): MutableCollection<RtValue> = elements
    override fun asList(): MutableList<RtValue> = elements

    override fun toStrictString(showTupleFieldNames: Boolean): String =
            "${type.toStrictString()}[${elements.joinToString(",") { it.toStrictString(false) }}]"

    override fun toString(): String = elements.toString()
    override fun equals(other: Any?): Boolean = other is RtListValue && elements == other.elements
    override fun hashCode(): Int = elements.hashCode()
}

class RtSetValue(private val type: RType, private val elements: MutableSet<RtValue>): RtValue() {
    override fun type(): RType = type
    override fun asCollection(): MutableCollection<RtValue> = elements
    override fun asSet(): MutableSet<RtValue> = elements

    override fun toStrictString(showTupleFieldNames: Boolean): String =
            "${type.toStrictString()}[${elements.joinToString(",") { it.toStrictString(false) }}]"

    override fun toString(): String = elements.toString()
    override fun equals(other: Any?): Boolean = other is RtSetValue && elements == other.elements
    override fun hashCode(): Int = elements.hashCode()
}

class RtMapValue(private val type: RType, private val map: MutableMap<RtValue, RtValue>): RtValue() {
    override fun type(): RType = type
    override fun asMap(): MutableMap<RtValue, RtValue> = map

    override fun toStrictString(showTupleFieldNames: Boolean): String {
        val entries = map.entries.joinToString(",") { (key, value) ->
            key.toStrictString(false) + "=" + value.toStrictString(false)
        }
        return "${type.toStrictString()}[$entries]"
    }

    override fun toString(): String = map.toString()
    override fun equals(other: Any?): Boolean = other is RtMapValue && map == other.map
    override fun hashCode(): Int = map.hashCode()
}

class RtTupleValue(val type: RTupleType, val elements: List<RtValue>): RtValue() {
    override fun type(): RType = type
    override fun asTuple(): List<RtValue> = elements
    override fun equals(other: Any?): Boolean = other is RtTupleValue && elements == other.elements
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
        return if (name == null || !showTupleFieldNames) valueStr else "$name:$valueStr"
    }
}

class RtJsonValue private constructor(private val str: String): RtValue() {
    override fun type(): RType = RJSONType
    override fun asJsonString(): String = str
    override fun toString(): String = str
    override fun toStrictString(showTupleFieldNames: Boolean): String = "json[$str]"
    override fun equals(other: Any?): Boolean = other is RtJsonValue && str == other.str
    override fun hashCode(): Int = str.hashCode()

    companion object {
        fun parse(s: String): RtValue {
            val mapper = ObjectMapper()

            val json = try { mapper.readTree(s) }
            catch (e: JsonProcessingException) {
                throw IllegalArgumentException(s)
            }

            val str = json.toString()
            return RtJsonValue(str)
        }
    }
}

class RtRangeValue(val start: Long, val end: Long, val step: Long): RtValue(), Iterable<RtValue> {
    override fun type(): RType = RRangeType
    override fun asRange(): RtRangeValue = this
    override fun toString(): String = "range($start,$end,$step)"
    override fun toStrictString(showTupleFieldNames: Boolean): String = "range[$start,$end,$step]"

    override fun iterator(): Iterator<RtValue> = RangeIterator(this)

    override fun equals(other: Any?): Boolean = other is RtRangeValue && start == other.start && end == other.end && step == other.step
    override fun hashCode(): Int = Objects.hash(start, end, step)

    companion object {
        private class RangeIterator(private val range: RtRangeValue): Iterator<RtValue> {
            private var current = range.start

            override fun hasNext(): Boolean {
                if (range.step > 0) {
                    return current < range.end
                } else {
                    return current > range.end
                }
            }

            override fun next(): RtValue {
                val res = current
                current = RtUtils.saturatedAdd(current, range.step)
                return RtIntValue(res)
            }
        }
    }
}

abstract class RtValueRef {
    abstract fun get(): RtValue
    abstract fun set(value: RtValue)
}
