package net.postchain.rell.model

import net.postchain.rell.parser.CtUtils
import net.postchain.rell.runtime.*
import org.jooq.DataType
import org.jooq.SQLDialect
import org.jooq.impl.DefaultDataType
import org.jooq.impl.SQLDataType
import org.jooq.util.postgres.PostgresDataType
import org.postgresql.util.PGobject
import java.lang.UnsupportedOperationException
import java.sql.PreparedStatement
import java.sql.ResultSet

sealed class RType(val name: String) {
    open fun allowedForAttributes(): Boolean = true
    abstract fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue)
    abstract fun fromSql(rs: ResultSet, idx: Int): RtValue
    abstract fun toStrictString(): String
    override fun toString(): String = toStrictString()

    fun match(otherType: RType, errCode: String, errMsg: String) {
        if (!isAssignableFrom(otherType)) {
            throw CtUtils.errTypeMissmatch(otherType, this, errCode, errMsg)
        }
    }

    open fun isAssignableFrom(type: RType): Boolean = type == this

    open fun calcCommonType(other: RType): RType? = null

    companion object {
        fun commonType(a: RType, b: RType, errCode: String, errMsg: String): RType {
            val res = commonTypeOpt(a, b)
            return res ?: throw CtUtils.errTypeMissmatch(b, a, errCode, errMsg)
        }

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
}

object RTextType: RPrimitiveType("text", PostgresDataType.TEXT) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) {
        stmt.setString(idx, value.asString())
    }

    override fun fromSql(rs: ResultSet, idx: Int): RtValue = RtTextValue(rs.getString(idx))
}

object RIntegerType: RPrimitiveType("integer", SQLDataType.BIGINT) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) {
        stmt.setLong(idx, value.asInteger())
    }

    override fun fromSql(rs: ResultSet, idx: Int): RtValue = RtIntValue(rs.getLong(idx))
}

object RByteArrayType: RPrimitiveType("byte_array", PostgresDataType.BYTEA) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = stmt.setBytes(idx, value.asByteArray())
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = RtByteArrayValue(rs.getBytes(idx))
}

object RTimestampType: RPrimitiveType("timestamp", SQLDataType.BIGINT) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = TODO("TODO")
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = TODO("TODO")
}

object RGUIDType: RPrimitiveType("guid", PostgresDataType.BYTEA) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = TODO("TODO")
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = TODO("TODO")
}

val gtxSignerSQLDataType = DefaultDataType(null as SQLDialect?, ByteArray::class.java, "gtx_signer")

object RSignerType: RPrimitiveType("signer", gtxSignerSQLDataType) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = TODO("TODO")
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = TODO("TODO")
}

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
}

object RNullType: RType("null") {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = throw UnsupportedOperationException()
    override fun fromSql(rs: ResultSet, idx: Int) = throw UnsupportedOperationException()
    override fun toStrictString() = "null"

    override fun calcCommonType(other: RType): RType? = RNullableType(other)
}

class RInstanceRefType(val rClass: RClass): RType(rClass.name) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = stmt.setLong(idx, value.asObjectId())
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = RtObjectValue(this, rs.getLong(idx))
    override fun toStrictString(): String = name
    override fun equals(other: Any?): Boolean = other is RInstanceRefType && other.rClass == rClass
    override fun hashCode(): Int = rClass.hashCode()
}

class RNullableType(val valueType: RType): RType(valueType.name + "?") {
    init {
        check(valueType != RNullType)
        check(valueType != RUnitType)
        check(!(valueType is RNullableType))
    }

    override fun allowedForAttributes() = false
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = TODO()
    override fun fromSql(rs: ResultSet, idx: Int) = TODO()
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
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = TODO("TODO")
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = TODO("TODO")
    override fun toStrictString(): String = TODO("TODO")
}

sealed class RCollectionType(val elementType: RType, baseName: String): RType("$baseName<${elementType.toStrictString()}>") {
    override fun allowedForAttributes(): Boolean = false
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = throw UnsupportedOperationException()
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = throw UnsupportedOperationException()
    override fun toStrictString(): String = name
}

class RListType(elementType: RType): RCollectionType(elementType, "list") {
    override fun equals(other: Any?): Boolean = other is RListType && elementType == other.elementType
}

class RSetType(elementType: RType): RCollectionType(elementType, "set") {
    override fun equals(other: Any?): Boolean = other is RSetType && elementType == other.elementType
}

class RMapType(val keyType: RType, val valueType: RType): RType("map<${keyType.toStrictString()},${valueType.toStrictString()}>") {
    override fun allowedForAttributes(): Boolean = false
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = throw UnsupportedOperationException()
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = throw UnsupportedOperationException()
    override fun toStrictString(): String = name
    override fun equals(other: Any?): Boolean = other is RMapType && keyType == other.keyType && valueType == other.valueType
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
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = TODO()
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = TODO()
    override fun toStrictString(): String = name
    override fun equals(other: Any?): Boolean = other is RTupleType && fields == other.fields

    override fun isAssignableFrom(type: RType): Boolean {
        if (!(type is RTupleType)) return false
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
        if (!(other is RTupleType)) return null
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
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = TODO() // Not allowed
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = TODO() // Not allowed
    override fun toStrictString(): String = "range"
}
