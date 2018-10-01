package net.postchain.rell.runtime

import net.postchain.rell.model.*
import net.postchain.rell.sql.SqlExecutor

sealed class RtValue {
    abstract fun type(): RType

    open fun asBoolean(): Boolean = throw IllegalStateException("$javaClass")
    open fun asInteger(): Long = throw IllegalStateException("$javaClass")
    open fun asString(): String = throw IllegalStateException("$javaClass")

    abstract fun toStrictString(): String
}

class RtBooleanValue(val value: Boolean): RtValue() {
    override fun type(): RType = RBooleanType
    override fun asBoolean(): Boolean = value
    override fun toStrictString(): String = "boolean[$value]"
    override fun toString(): String = "" + value
}

class RtIntValue(val value: Long): RtValue() {
    override fun type(): RType = RIntegerType
    override fun asInteger(): Long = value
    override fun toStrictString(): String = "int[$value]"
    override fun toString(): String = "" + value
}

class RtTextValue(val value: String): RtValue() {
    override fun type(): RType = RTextType
    override fun asString(): String = value
    override fun toStrictString(): String = "text[$value]"
    override fun toString(): String = value
}

class RtObjectValue(val type: RInstanceRefType, val rowid: Long): RtValue() {
    override fun type(): RType = type
    override fun toStrictString(): String = "${type.name}[$rowid]"
    override fun toString(): String = toStrictString()
}

class RtListValue(val type: RType, val elements: List<RtValue>): RtValue() {
    override fun type(): RType = type
    override fun toStrictString(): String = "${type.toStrictString()}[${elements.joinToString(",") { it.toStrictString() }}]"
    override fun toString(): String = elements.toString()
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
