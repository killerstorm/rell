package net.postchain.rell.model

import net.postchain.gtx.*
import net.postchain.rell.hexStringToByteArray
import net.postchain.rell.module.*
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

class RTypeFlags(val mutable: Boolean, val gtxHuman: Boolean, val gtxCompact: Boolean) {
    companion object {
        fun combine(flags: Collection<RTypeFlags>): RTypeFlags {
            var mutable = false
            var gtxHuman = true
            var gtxCompact = true

            for (f in flags) {
                mutable = mutable or f.mutable
                gtxHuman = gtxHuman and f.gtxHuman
                gtxCompact = gtxCompact and f.gtxCompact
            }

            return RTypeFlags(mutable, gtxHuman, gtxCompact)
        }
    }
}

sealed class RType(val name: String) {
    private val gtxConversion by lazy { createGtxConversion() }

    open fun isAllowedForClassAttribute(): Boolean = true
    open fun isReference(): Boolean = false
    protected open fun isDirectMutable(): Boolean = false

    fun directFlags(): RTypeFlags {
        val gtxConv = gtxConversion
        return RTypeFlags(isDirectMutable(), gtxConv.directHuman(), gtxConv.directCompact())
    }

    open fun completeFlags(): RTypeFlags {
        val flags = mutableListOf(directFlags())
        for (sub in componentTypes()) {
            flags.add(sub.completeFlags())
        }
        return RTypeFlags.combine(flags)
    }

    open fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) {
        throw RtUtils.errNotSupported("Type cannot be passed to SQL: ${toStrictString()}")
    }

    open fun fromSql(rs: ResultSet, idx: Int): RtValue =
        throw RtUtils.errNotSupported("Type cannot be read from SQL: ${toStrictString()}")

    open fun fromCli(s: String): RtValue = throw UnsupportedOperationException()

    fun rtToGtx(rt: RtValue, human: Boolean): GTXValue = gtxConversion.rtToGtx(rt, human)
    fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean) = gtxConversion.gtxToRt(ctx, gtx, human)
    protected abstract fun createGtxConversion(): GtxRtConversion

    abstract fun toStrictString(): String
    override fun toString(): String = toStrictString()

    open fun componentTypes(): List<RType> = listOf()

    open fun isAssignableFrom(type: RType): Boolean = type == this
    protected open fun calcCommonType(other: RType): RType? = null

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
    override fun createGtxConversion() = GtxRtConversion_None
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

    override fun createGtxConversion() = GtxRtConversion_Boolean
}

object RTextType: RPrimitiveType("text", PostgresDataType.TEXT) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) {
        stmt.setString(idx, value.asString())
    }

    override fun fromSql(rs: ResultSet, idx: Int): RtValue = RtTextValue(rs.getString(idx))

    override fun fromCli(s: String): RtValue = RtTextValue(s)

    override fun createGtxConversion() = GtxRtConversion_Text
}

object RIntegerType: RPrimitiveType("integer", SQLDataType.BIGINT) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) {
        stmt.setLong(idx, value.asInteger())
    }

    override fun fromSql(rs: ResultSet, idx: Int): RtValue = RtIntValue(rs.getLong(idx))

    override fun fromCli(s: String): RtValue = RtIntValue(s.toLong())

    override fun createGtxConversion() = GtxRtConversion_Integer
}

object RByteArrayType: RPrimitiveType("byte_array", PostgresDataType.BYTEA) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = stmt.setBytes(idx, value.asByteArray())
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = RtByteArrayValue(rs.getBytes(idx))
    override fun fromCli(s: String): RtValue = RtByteArrayValue(s.hexStringToByteArray())

    override fun createGtxConversion() = GtxRtConversion_ByteArray
}

object RTimestampType: RPrimitiveType("timestamp", SQLDataType.BIGINT) {
    //TODO support GTX
    override fun createGtxConversion() = GtxRtConversion_None
}

object RGUIDType: RPrimitiveType("guid", PostgresDataType.BYTEA) {
    //TODO support GTX
    override fun createGtxConversion() = GtxRtConversion_None
}

val gtxSignerSQLDataType = DefaultDataType(null as SQLDialect?, ByteArray::class.java, "gtx_signer")

object RSignerType: RPrimitiveType("signer", gtxSignerSQLDataType) {
    //TODO support GTX
    override fun createGtxConversion() = GtxRtConversion_None
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

    override fun fromCli(s: String): RtValue = RtJsonValue.parse(s)

    //TODO consider converting between RtJsonValue and arbitrary GTXValue, not only String
    override fun createGtxConversion() = GtxRtConversion_Json
}

object RNullType: RType("null") {
    override fun toStrictString() = "null"
    override fun calcCommonType(other: RType): RType? = RNullableType(other)
    override fun createGtxConversion() = GtxRtConversion_Null
}

class RInstanceRefType(val rClass: RClass): RType(rClass.name) {
    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = stmt.setLong(idx, value.asObjectId())
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = RtObjectValue(this, rs.getLong(idx))
    override fun fromCli(s: String): RtValue = RtObjectValue(this, s.toLong())
    override fun toStrictString(): String = name
    override fun equals(other: Any?): Boolean = other is RInstanceRefType && other.rClass == rClass
    override fun hashCode(): Int = rClass.hashCode()

    override fun createGtxConversion() = GtxRtConversion_Object(this)
}

class RRecordFlags(val typeFlags: RTypeFlags, val cyclic: Boolean, val infinite: Boolean)

class RRecordType(name: String): RType(name) {
    private lateinit var bodyLate: RRecordBody
    private lateinit var flagsLate: RRecordFlags

    val attributes: Map<String, RAttrib> get() = bodyLate.attrMap
    val attributesList: List<RAttrib> get() = bodyLate.attrList
    val flags: RRecordFlags get() = flagsLate

    fun setAttributes(attrs: Map<String, RAttrib>) {
        val attrsList = attrs.values.toList()
        attrsList.withIndex().forEach { (idx, attr) -> check(attr.index == idx) }
        val attrMutable = attrs.values.any { it.mutable }
        bodyLate = RRecordBody(attrs, attrsList, attrMutable)
    }

    fun setFlags(flags: RRecordFlags) {
        flagsLate = flags
    }

    override fun isAllowedForClassAttribute() = false
    override fun isReference() = true
    override fun isDirectMutable() = bodyLate.attrMutable
    override fun completeFlags() = flagsLate.typeFlags

    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = throw UnsupportedOperationException()
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = throw UnsupportedOperationException()
    override fun fromCli(s: String): RtValue = throw UnsupportedOperationException()
    override fun toStrictString(): String = name
    override fun componentTypes() = attributesList.map { it.type }.toList()

    override fun createGtxConversion() = GtxRtConversion_Record(this)

    private class RRecordBody(val attrMap: Map<String, RAttrib>, val attrList: List<RAttrib>, val attrMutable: Boolean)
}

class RNullableType(val valueType: RType): RType(valueType.name + "?") {
    init {
        check(valueType != RNullType)
        check(valueType != RUnitType)
        check(valueType !is RNullableType)
    }

    override fun isAllowedForClassAttribute() = false
    override fun isReference() = valueType.isReference()
    override fun isDirectMutable() = false

    override fun fromCli(s: String): RtValue = if (s == "null") RtNullValue else valueType.fromCli(s)
    override fun toStrictString() = name
    override fun componentTypes() = listOf(valueType)
    override fun equals(other: Any?) = other is RNullableType && valueType == other.valueType
    override fun hashCode() = valueType.hashCode()

    override fun isAssignableFrom(type: RType): Boolean {
        return type == this || type == RNullType || valueType.isAssignableFrom(type)
    }

    override fun createGtxConversion() = GtxRtConversion_Nullable(this)
}

// TODO: make this more elaborate
class RClosureType(name: String): RType(name) {
    override fun isAllowedForClassAttribute(): Boolean = false
    override fun toStrictString(): String = TODO("TODO")
    override fun createGtxConversion() = GtxRtConversion_None
}

sealed class RCollectionType(val elementType: RType, baseName: String): RType("$baseName<${elementType.toStrictString()}>") {
    override fun isAllowedForClassAttribute(): Boolean = false
    override fun isReference() = true
    final override fun isDirectMutable() = true

    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = throw UnsupportedOperationException()
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = throw UnsupportedOperationException()
    override fun toStrictString(): String = name
    override fun componentTypes(): List<RType> = listOf(elementType)
}

class RListType(elementType: RType): RCollectionType(elementType, "list") {
    override fun fromCli(s: String): RtValue = RtListValue(this, s.split(",").map { elementType.fromCli(it) }.toMutableList())
    override fun equals(other: Any?): Boolean = other is RListType && elementType == other.elementType
    override fun createGtxConversion() = GtxRtConversion_List(this)
}

class RSetType(elementType: RType): RCollectionType(elementType, "set") {
    override fun fromCli(s: String): RtValue = RtSetValue(this, s.split(",").map { elementType.fromCli(it) }.toMutableSet())
    override fun equals(other: Any?): Boolean = other is RSetType && elementType == other.elementType
    override fun createGtxConversion() = GtxRtConversion_Set(this)
}

class RMapType(val keyType: RType, val valueType: RType): RType("map<${keyType.toStrictString()},${valueType.toStrictString()}>") {
    override fun isAllowedForClassAttribute(): Boolean = false
    override fun isReference() = true
    override fun isDirectMutable() = true

    override fun toSql(stmt: PreparedStatement, idx: Int, value: RtValue) = throw UnsupportedOperationException()
    override fun fromSql(rs: ResultSet, idx: Int): RtValue = throw UnsupportedOperationException()
    override fun toStrictString(): String = name
    override fun componentTypes(): List<RType> = listOf(keyType, valueType)
    override fun equals(other: Any?): Boolean = other is RMapType && keyType == other.keyType && valueType == other.valueType

    override fun fromCli(s: String): RtValue {
        val map = s.split(",").associate {
            val (k, v) = it.split("=")
            Pair(keyType.fromCli(k), valueType.fromCli(v))
        }
        return RtMapValue(this, map.toMutableMap())
    }

    override fun createGtxConversion() = GtxRtConversion_Map(this)
}

class RTupleField(val name: String?, val type: RType) {
    fun toStrictString(): String {
        return if (name != null) "${name}:${type.toStrictString()}" else type.toStrictString()
    }

    override fun toString(): String = toStrictString()
    override fun equals(other: Any?): Boolean = other is RTupleField && name == other.name && type == other.type
}

class RTupleType(val fields: List<RTupleField>): RType("(${fields.joinToString(",") { it.toStrictString() }})") {
    override fun isAllowedForClassAttribute(): Boolean = false
    override fun isReference() = true
    override fun isDirectMutable() = false

    override fun toStrictString(): String = name
    override fun equals(other: Any?): Boolean = other is RTupleType && fields == other.fields

    override fun componentTypes() = fields.map { it.type }.toList()

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

    override fun createGtxConversion() = GtxRtConversion_Tuple(this)
}

object RRangeType: RType("range") {
    override fun isAllowedForClassAttribute(): Boolean = false
    override fun isReference() = true
    override fun toStrictString(): String = "range"
    override fun createGtxConversion() = GtxRtConversion_None
}

object RGtxValueType: RType("GTXValue") {
    override fun isAllowedForClassAttribute(): Boolean = false
    override fun isReference() = true
    override fun toStrictString() = name
    override fun createGtxConversion() = GtxRtConversion_GtxValue
}
