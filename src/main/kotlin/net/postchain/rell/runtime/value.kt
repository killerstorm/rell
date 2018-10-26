package net.postchain.rell.runtime

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import net.postchain.rell.model.*
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.toHex
import java.lang.IllegalArgumentException
import java.lang.UnsupportedOperationException

sealed class RtValue {
    abstract fun type(): RType

    open fun asBoolean(): Boolean = throw errType()
    open fun asInteger(): Long = throw errType()
    open fun asString(): String = throw errType()
    open fun asByteArray(): ByteArray = throw errType()
    open fun asJsonString(): String = throw errType()
    open fun asList(): List<RtValue> = throw errType()
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
}

class RtIntValue(val value: Long): RtValue() {
    override fun type(): RType = RIntegerType
    override fun asInteger(): Long = value
    override fun toStrictString(showTupleFieldNames: Boolean): String = "int[$value]"
    override fun toString(): String = "" + value
}

class RtTextValue(val value: String): RtValue() {
    override fun type(): RType = RTextType
    override fun asString(): String = value
    override fun toStrictString(showTupleFieldNames: Boolean): String = "text[$value]"
    override fun toString(): String = value
}

class RtByteArrayValue(val value: ByteArray): RtValue() {
    override fun type(): RType = RByteArrayType
    override fun asByteArray(): ByteArray = value
    override fun toStrictString(showTupleFieldNames: Boolean): String = "byte_array[${value.toHex()}]"
    override fun toString(): String = "0x" + value.toHex()
}

class RtObjectValue(val type: RInstanceRefType, val rowid: Long): RtValue() {
    override fun type(): RType = type
    override fun asObjectId(): Long = rowid
    override fun toStrictString(showTupleFieldNames: Boolean): String = "${type.name}[$rowid]"
    override fun toString(): String = toStrictString()
}

class RtNullValue(val type: RType): RtValue() {
    override fun type(): RType = type
    override fun toStrictString(showTupleFieldNames: Boolean): String = "null[${type.toStrictString()}]"
    override fun toString(): String = "null"
}

class RtListValue(val type: RType, val elements: List<RtValue>): RtValue() {
    override fun type(): RType = type
    override fun asList(): List<RtValue> = elements
    override fun toString(): String = elements.toString()

    override fun toStrictString(showTupleFieldNames: Boolean): String =
            "${type.toStrictString()}[${elements.joinToString(",") { it.toStrictString(false) }}]"
}

class RtTupleValue(val type: RTupleType, val elements: List<RtValue>): RtValue() {
    override fun type(): RType = type
    override fun asTuple(): List<RtValue> = elements

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

abstract class RtPrinter {
    abstract fun print(str: String)
}

object FailingRtPrinter: RtPrinter() {
    override fun print(str: String) {
        throw UnsupportedOperationException()
    }
}

class RtGlobalContext(val stdoutPrinter: RtPrinter, val logPrinter: RtPrinter, val sqlExec: SqlExecutor)

class RtModuleContext(val globalCtx: RtGlobalContext, val module: RModule)

class RtEnv(val modCtx: RtModuleContext, val dbUpdateAllowed: Boolean) {
    private val values = mutableListOf<RtValue?>()

    fun set(offset: Int, value: RtValue) {
        while (values.size <= offset) {
            values.add(null)
        }
        values[offset] = value
    }

    fun get(offset: Int): RtValue {
        val value = getOpt(offset)
        check(value != null) { "Variable not initialized: offset = $offset" }
        return value!!
    }

    fun getOpt(offset: Int): RtValue? {
        check(offset >= 0)
        return if (offset < values.size) values[offset] else null
    }

    fun checkDbUpdateAllowed() {
        if (!dbUpdateAllowed) {
            throw RtError("no_db_update", "Database modifications are not allowed in this context")
        }
    }
}

object RtUtils {
    // https://stackoverflow.com/a/2632501
    fun saturatedAdd(a: Long, b: Long): Long {
        if (a == 0L || b == 0L || ((a > 0) != (b > 0))) {
            return a + b
        } else if (a > 0) {
            return if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else (a + b)
        } else {
            return if (Long.MIN_VALUE - a > b) Long.MIN_VALUE else (a + b)
        }
    }
}
