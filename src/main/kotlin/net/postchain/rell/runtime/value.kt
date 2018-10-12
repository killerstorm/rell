package net.postchain.rell.runtime

import net.postchain.rell.model.*
import net.postchain.rell.sql.SqlExecutor

sealed class RtValue {
    abstract fun type(): RType

    open fun asBoolean(): Boolean = throw IllegalStateException("$javaClass")
    open fun asInteger(): Long = throw IllegalStateException("$javaClass")
    open fun asString(): String = throw IllegalStateException("$javaClass")
    open fun asObjectId(): Long = throw IllegalStateException("$javaClass")

    abstract fun toStrictString(showTupleFieldNames: Boolean = true): String
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

class RtObjectValue(val type: RInstanceRefType, val rowid: Long): RtValue() {
    override fun type(): RType = type
    override fun asObjectId(): Long = rowid
    override fun toStrictString(showTupleFieldNames: Boolean): String = "${type.name}[$rowid]"
    override fun toString(): String = toStrictString()
}

class RtListValue(val type: RType, val elements: List<RtValue>): RtValue() {
    override fun type(): RType = type
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

class RtEnv(val sqlExec: SqlExecutor) {
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
}
