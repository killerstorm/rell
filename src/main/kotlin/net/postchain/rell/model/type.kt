package net.postchain.rell.model

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
    abstract fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue)
    abstract fun fromSql(rs: ResultSet, idx: Int): RtValue
    abstract fun toStrictString(): String
    override fun toString(): String = toStrictString()

    fun accepts(valueType: RType): Boolean {
        return valueType == this //TODO allow subtypes
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

class RInstanceRefType(val rclass: RClass): RType(rclass.name) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = stmt.setLong(idx, value.asObjectId())
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = RtObjectValue(this, rs.getLong(idx))
    override fun toStrictString(): String = name
    override fun equals(other: Any?): Boolean = other is RInstanceRefType && other.rclass == rclass
}

// TODO: make this more elaborate
class RClosureType(name: String): RType(name) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = TODO("TODO")
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = TODO("TODO")
    override fun toStrictString(): String = TODO("TODO")
}

class RListType(val elementType: RType): RType("list<${elementType.toStrictString()}>") {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = throw UnsupportedOperationException()
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = throw UnsupportedOperationException()
    override fun toStrictString(): String = name
}

class RTupleField(val name: String?, val type: RType) {
    fun toStrictString(): String {
        return if (name != null) "${name}:${type.toStrictString()}" else type.toStrictString()
    }

    override fun toString(): String = toStrictString()
}

class RTupleType(val fields: List<RTupleField>): RType("(${fields.joinToString(",") { it.toStrictString() }})") {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = TODO()
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = TODO()
    override fun toStrictString(): String = name
}
