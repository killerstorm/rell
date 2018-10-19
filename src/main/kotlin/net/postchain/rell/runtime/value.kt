package net.postchain.rell.runtime

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.ObjectMapper
import net.postchain.rell.model.*
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.toHex
import java.lang.IllegalArgumentException

sealed class RtValue {
    abstract fun type(): RType

    open fun asBoolean(): Boolean = throw errType()
    open fun asInteger(): Long = throw errType()
    open fun asString(): String = throw errType()
    open fun asByteArray(): ByteArray = throw errType()
    open fun asJsonString(): String = throw errType()
    open fun asList(): List<RtValue> = throw errType()
    open fun asObjectId(): Long = throw errType()

    abstract fun toStrictString(showTupleFieldNames: Boolean = true): String

    private fun errType() = IllegalStateException("$javaClass")
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

class RtListValue(val type: RType, val elements: List<RtValue>): RtValue() {
    override fun type(): RType = type
    override fun asList(): List<RtValue> = elements
    override fun toString(): String = toStrictString()

    override fun toStrictString(showTupleFieldNames: Boolean): String =
            "${type.toStrictString()}[${elements.joinToString(",") { it.toStrictString(false) }}]"
}

class RtTupleValue(val type: RTupleType, val elements: List<RtValue>): RtValue() {
    override fun type(): RType = type
    override fun toString(): String = toStrictString()

    override fun toStrictString(showTupleFieldNames: Boolean): String {
        return "(${elements.indices.joinToString(",") { elementToString(showTupleFieldNames, it) }})"
    }

    private fun elementToString(showTupleFieldNames: Boolean, idx: Int): String {
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

class RtEnv(val sqlExec: SqlExecutor, private val dbUpdateAllowed: Boolean) {
    private val values = mutableListOf<RtValue?>()

    fun set(offset: Int, value: RtValue) {
        while (values.size <= offset) {
            values.add(null)
        }
        values[offset] = value
    }

    fun get(offset: Int): RtValue {
        val value = values[offset]
        if (value == null) {
            throw RuntimeException("Value not set: $offset")
        } else {
            return value
        }
    }

    fun checkDbUpdateAllowed() {
        if (!dbUpdateAllowed) {
            throw RtError("no_db_update", "Database modifications are not allowed in this context")
        }
    }
}
