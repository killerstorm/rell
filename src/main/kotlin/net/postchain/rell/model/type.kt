package net.postchain.rell.model

import net.postchain.rell.hexStringToByteArray
import net.postchain.rell.parser.CtUtils
import net.postchain.rell.parser.S_Pos
import net.postchain.rell.runtime.*
import org.jooq.DataType
import org.jooq.SQLDialect
import org.jooq.impl.DefaultDataType
import org.jooq.impl.SQLDataType
import org.jooq.util.postgres.PostgresDataType
import org.postgresql.util.PGobject
import java.lang.IllegalArgumentException
import java.lang.UnsupportedOperationException
import java.sql.PreparedStatement
import java.sql.ResultSet

sealed class RType(val name: String) {
    open fun allowedForAttributes(): Boolean = true

    open fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) {
        throw RtUtils.errNotSupported("Type cannot be passed to SQL: ${toStrictString()}")
    }

    open fun fromSql(rs: ResultSet, idx: Int): RtValue =
        throw RtUtils.errNotSupported("Type cannot be read from SQL: ${toStrictString()}")

    open fun fromCli(s: String): RtValue = throw UnsupportedOperationException()
    abstract fun toStrictString(): String
    override fun toString(): String = toStrictString()

    open fun isAssignableFrom(type: RType): Boolean = type == this

    open fun calcCommonType(other: RType): RType? = null

    companion object {
        fun commonTypeOpt(a: RType, b: RType): RType? {
            if (a.isAssignableFrom(b)) {
                return a
            } else if (b.isAssignableFrom(a)) {
                return b
            }

            val res = a.calcCommonType(b) ?: b.calcCommonType(a)
            return res
        }
    }
}

sealed class RPrimitiveType(name: String, val sqlType: DataType<*>): RType(name) {
    override fun toStrictString(): String = name
}

object RUnitType: RPrimitiveType("unit", SQLDataType.OTHER) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = throw UnsupportedOperationException()
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = throw UnsupportedOperationException()
}

object RBooleanType: RPrimitiveType("boolean", SQLDataType.BOOLEAN) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) {
        stmt.setBoolean(idx, value.asBoolean())
    }

    override fun fromSql(rs: ResultSet, idx: Int): RtValue = RtBooleanValue(rs.getBoolean(idx))

    override fun fromCli(s: String): RtValue {
        if (s == "false") return RtBooleanValue(false)
        else if (s == "true") return RtBooleanValue(true)
        else throw IllegalArgumentException(s)
    }
}

object RTextType: RPrimitiveType("text", PostgresDataType.TEXT) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) {
        stmt.setString(idx, value.asString())
    }

    override fun fromSql(rs: ResultSet, idx: Int): RtValue = RtTextValue(rs.getString(idx))

    override fun fromCli(s: String): RtValue = RtTextValue(s)
}

object RIntegerType: RPrimitiveType("integer", SQLDataType.BIGINT) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) {
        stmt.setLong(idx, value.asInteger())
    }

    override fun fromSql(rs: ResultSet, idx: Int): RtValue = RtIntValue(rs.getLong(idx))

    override fun fromCli(s: String): RtValue = RtIntValue(s.toLong())
}

object RByteArrayType: RPrimitiveType("byte_array", PostgresDataType.BYTEA) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = stmt.setBytes(idx, value.asByteArray())
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = RtByteArrayValue(rs.getBytes(idx))
    override fun fromCli(s: String): RtValue = RtByteArrayValue(s.hexStringToByteArray())
}

object RTimestampType: RPrimitiveType("timestamp", SQLDataType.BIGINT)

object RGUIDType: RPrimitiveType("guid", PostgresDataType.BYTEA)

val gtxSignerSQLDataType = DefaultDataType(null as SQLDialect?, ByteArray::class.java, "gtx_signer")

object RSignerType: RPrimitiveType("signer", gtxSignerSQLDataType)

val jsonSQLDataType = DefaultDataType(null as SQLDialect?, String::class.java, "jsonb")

object RJSONType: RPrimitiveType("json", jsonSQLDataType) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) {
        val str = value.asJsonString()
        val obj = PGobject()
        obj.type = "json"
        obj.value = str
        stmt.setObject(idx, obj)
    }

    override fun fromSql(rs: ResultSet, idx: Int): RtValue {
        val str = rs.getString(idx)
        return RtJsonValue.parse(str)
    }

    override fun fromCli(s: String): RtValue = RtJsonValue.parse(s)
}

object RNullType: RType("null") {
    override fun toStrictString() = "null"
    override fun calcCommonType(other: RType): RType? = RNullableType(other)
}

class RInstanceRefType(val rClass: RClass): RType(rClass.name) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = stmt.setLong(idx, value.asObjectId())
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = RtObjectValue(this, rs.getLong(idx))
    override fun fromCli(s: String): RtValue = RtObjectValue(this, s.toLong())
    override fun toStrictString(): String = name
    override fun equals(other: Any?): Boolean = other is RInstanceRefType && other.rClass == rClass
    override fun hashCode(): Int = rClass.hashCode()
}

class RNullableType(val valueType: RType): RType(valueType.name + "?") {
    init {
        check(valueType != RNullType)
        check(valueType != RUnitType)
        check(valueType !is RNullableType)
    }

    override fun allowedForAttributes() = false
    override fun fromCli(s: String): RtValue = if (s == "null") RtNullValue else valueType.fromCli(s)
    override fun toStrictString() = name
    override fun equals(other: Any?) = other is RNullableType && valueType == other.valueType
    override fun hashCode() = valueType.hashCode()

    override fun isAssignableFrom(type: RType): Boolean {
        return type == this || type == RNullType || valueType.isAssignableFrom(type)
    }
}

// TODO: make this more elaborate
class RClosureType(name: String): RType(name) {
    override fun allowedForAttributes(): Boolean = false
    override fun toStrictString(): String = TODO("TODO")
}

sealed class RCollectionType(val elementType: RType, baseName: String): RType("$baseName<${elementType.toStrictString()}>") {
    override fun allowedForAttributes(): Boolean = false
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = throw UnsupportedOperationException()
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = throw UnsupportedOperationException()
    override fun toStrictString(): String = name
}

class RListType(elementType: RType): RCollectionType(elementType, "list") {
    override fun fromCli(s: String): RtValue = RtListValue(this, s.split(",").map { elementType.fromCli(it) }.toMutableList())
    override fun equals(other: Any?): Boolean = other is RListType && elementType == other.elementType
}

class RSetType(elementType: RType): RCollectionType(elementType, "set") {
    override fun fromCli(s: String): RtValue = RtSetValue(this, s.split(",").map { elementType.fromCli(it) }.toMutableSet())
    override fun equals(other: Any?): Boolean = other is RSetType && elementType == other.elementType
}

class RMapType(val keyType: RType, val valueType: RType): RType("map<${keyType.toStrictString()},${valueType.toStrictString()}>") {
    override fun allowedForAttributes(): Boolean = false
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = throw UnsupportedOperationException()
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = throw UnsupportedOperationException()
    override fun toStrictString(): String = name
    override fun equals(other: Any?): Boolean = other is RMapType && keyType == other.keyType && valueType == other.valueType

    override fun fromCli(s: String): RtValue {
        val map = s.split(",").associate {
            val (k, v) = it.split("=")
            Pair(keyType.fromCli(k), valueType.fromCli(v))
        }
        return RtMapValue(this, map.toMutableMap())
    }
}

class RTupleField(val name: String?, val type: RType) {
    fun toStrictString(): String {
        return if (name != null) "${name}:${type.toStrictString()}" else type.toStrictString()
    }

    override fun toString(): String = toStrictString()
    override fun equals(other: Any?): Boolean = other is RTupleField && name == other.name && type == other.type
}

class RTupleType(val fields: List<RTupleField>): RType("(${fields.joinToString(",") { it.toStrictString() }})") {
    override fun allowedForAttributes(): Boolean = false
    override fun toStrictString(): String = name
    override fun equals(other: Any?): Boolean = other is RTupleType && fields == other.fields

    override fun isAssignableFrom(type: RType): Boolean {
        if (type !is RTupleType) return false
        if (fields.size != type.fields.size) return false

        for (i in fields.indices) {
            val field = fields[i]
            val otherField = type.fields[i]
            if (field.name != otherField.name) return false
            if (!field.type.isAssignableFrom(otherField.type)) return false
        }

        return true
    }

    override fun calcCommonType(other: RType): RType? {
        if (other !is RTupleType) return null
        if (fields.size != other.fields.size) return null

        val resFields = fields.mapIndexed { i, field ->
            val otherField = other.fields[i]
            if (field.name != otherField.name) return null
            val type = RType.commonTypeOpt(field.type, otherField.type)
            if (type == null) return null
            RTupleField(field.name, type)
        }

        return RTupleType(resFields)
    }
}

object RRangeType: RType("range") {
    override fun allowedForAttributes(): Boolean = false
    override fun toStrictString(): String = "range"
}
