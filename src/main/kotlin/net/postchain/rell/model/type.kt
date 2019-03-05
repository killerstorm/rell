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

class R_TypeFlags(val mutable: Boolean, val gtxHuman: Boolean, val gtxCompact: Boolean) {
    companion object {
        fun combine(flags: Collection<R_TypeFlags>): R_TypeFlags {
            var mutable = false
            var gtxHuman = true
            var gtxCompact = true

            for (f in flags) {
                mutable = mutable or f.mutable
                gtxHuman = gtxHuman and f.gtxHuman
                gtxCompact = gtxCompact and f.gtxCompact
            }

            return R_TypeFlags(mutable, gtxHuman, gtxCompact)
        }
    }
}

sealed class R_Type(val name: String) {
    private val gtxConversion by lazy { createGtxConversion() }

    open fun isReference(): Boolean = false
    protected open fun isDirectMutable(): Boolean = false

    fun directFlags(): R_TypeFlags {
        val gtxConv = gtxConversion
        return R_TypeFlags(isDirectMutable(), gtxConv.directHuman(), gtxConv.directCompact())
    }

    open fun completeFlags(): R_TypeFlags {
        val flags = mutableListOf(directFlags())
        for (sub in componentTypes()) {
            flags.add(sub.completeFlags())
        }
        return R_TypeFlags.combine(flags)
    }

    open fun isSqlCompatible(): Boolean = false

    open fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) {
        throw Rt_Utils.errNotSupported("Type cannot be converted to SQL: ${toStrictString()}")
    }

    open fun fromSql(rs: ResultSet, idx: Int): Rt_Value {
        throw Rt_Utils.errNotSupported("Type cannot be converted from SQL: ${toStrictString()}")
    }

    open fun fromCli(s: String): Rt_Value = throw UnsupportedOperationException()

    fun rtToGtx(rt: Rt_Value, human: Boolean): GTXValue = gtxConversion.rtToGtx(rt, human)
    fun gtxToRt(ctx: GtxToRtContext, gtx: GTXValue, human: Boolean) = gtxConversion.gtxToRt(ctx, gtx, human)
    protected abstract fun createGtxConversion(): GtxRtConversion

    abstract fun toStrictString(): String
    override fun toString(): String = toStrictString()

    open fun componentTypes(): List<R_Type> = listOf()

    open fun isAssignableFrom(type: R_Type): Boolean = type == this
    protected open fun calcCommonType(other: R_Type): R_Type? = null

    companion object {
        fun commonTypeOpt(a: R_Type, b: R_Type): R_Type? {
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

sealed class R_PrimitiveType(name: String, val sqlType: DataType<*>): R_Type(name) {
    override fun toStrictString(): String = name
}

object R_UnitType: R_PrimitiveType("unit", SQLDataType.OTHER) {
    override fun createGtxConversion() = GtxRtConversion_None
}

object R_BooleanType: R_PrimitiveType("boolean", SQLDataType.BOOLEAN) {
    override fun isSqlCompatible(): Boolean = true

    override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) {
        stmt.setBoolean(idx, value.asBoolean())
    }

    override fun fromSql(rs: ResultSet, idx: Int): Rt_Value = Rt_BooleanValue(rs.getBoolean(idx))

    override fun fromCli(s: String): Rt_Value {
        if (s == "false") return Rt_BooleanValue(false)
        else if (s == "true") return Rt_BooleanValue(true)
        else throw IllegalArgumentException(s)
    }

    override fun createGtxConversion() = GtxRtConversion_Boolean
}

object R_TextType: R_PrimitiveType("text", PostgresDataType.TEXT) {
    override fun isSqlCompatible(): Boolean = true

    override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) {
        stmt.setString(idx, value.asString())
    }

    override fun fromSql(rs: ResultSet, idx: Int): Rt_Value = Rt_TextValue(rs.getString(idx))

    override fun fromCli(s: String): Rt_Value = Rt_TextValue(s)

    override fun createGtxConversion() = GtxRtConversion_Text
}

object R_IntegerType: R_PrimitiveType("integer", SQLDataType.BIGINT) {
    override fun isSqlCompatible(): Boolean = true

    override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) {
        stmt.setLong(idx, value.asInteger())
    }

    override fun fromSql(rs: ResultSet, idx: Int): Rt_Value = Rt_IntValue(rs.getLong(idx))

    override fun fromCli(s: String): Rt_Value = Rt_IntValue(s.toLong())

    override fun createGtxConversion() = GtxRtConversion_Integer
}

object R_ByteArrayType: R_PrimitiveType("byte_array", PostgresDataType.BYTEA) {
    override fun isSqlCompatible(): Boolean = true
    override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) = stmt.setBytes(idx, value.asByteArray())
    override fun fromSql(rs: ResultSet, idx: Int): Rt_Value = Rt_ByteArrayValue(rs.getBytes(idx))
    override fun fromCli(s: String): Rt_Value = Rt_ByteArrayValue(s.hexStringToByteArray())

    override fun createGtxConversion() = GtxRtConversion_ByteArray
}

object R_TimestampType: R_PrimitiveType("timestamp", SQLDataType.BIGINT) {
    //TODO support GTX
    override fun createGtxConversion() = GtxRtConversion_None
}

object R_GUIDType: R_PrimitiveType("guid", PostgresDataType.BYTEA) {
    //TODO support GTX
    override fun createGtxConversion() = GtxRtConversion_None
}

private val GTX_SIGNER_SQL_DATA_TYPE = DefaultDataType(null as SQLDialect?, ByteArray::class.java, "gtx_signer")

object R_SignerType: R_PrimitiveType("signer", GTX_SIGNER_SQL_DATA_TYPE) {
    //TODO support GTX
    override fun createGtxConversion() = GtxRtConversion_None
}

private val JSON_SQL_DATA_TYPE = DefaultDataType(null as SQLDialect?, String::class.java, "jsonb")

object R_JSONType: R_PrimitiveType("json", JSON_SQL_DATA_TYPE) {
    override fun isSqlCompatible(): Boolean = true

    override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) {
        val str = value.asJsonString()
        val obj = PGobject()
        obj.type = "json"
        obj.value = str
        stmt.setObject(idx, obj)
    }

    override fun fromSql(rs: ResultSet, idx: Int): Rt_Value {
        val str = rs.getString(idx)
        return Rt_JsonValue.parse(str)
    }

    override fun fromCli(s: String): Rt_Value = Rt_JsonValue.parse(s)

    //TODO consider converting between Rt_JsonValue and arbitrary GTXValue, not only String
    override fun createGtxConversion() = GtxRtConversion_Json
}

object R_NullType: R_Type("null") {
    override fun toStrictString() = "null"
    override fun calcCommonType(other: R_Type): R_Type? = R_NullableType(other)
    override fun createGtxConversion() = GtxRtConversion_Null
}

class R_ClassType(val rClass: R_Class): R_Type(rClass.name) {
    override fun isSqlCompatible(): Boolean = true
    override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) = stmt.setLong(idx, value.asObjectId())
    override fun fromSql(rs: ResultSet, idx: Int): Rt_Value = Rt_ClassValue(this, rs.getLong(idx))
    override fun fromCli(s: String): Rt_Value = Rt_ClassValue(this, s.toLong())
    override fun toStrictString(): String = name
    override fun equals(other: Any?): Boolean = other is R_ClassType && other.rClass == rClass
    override fun hashCode(): Int = rClass.hashCode()

    override fun createGtxConversion() = GtxRtConversion_Class(this)
}

class R_ObjectType(val rObject: R_Object): R_Type(rObject.rClass.name) {
    override fun toStrictString(): String = name
    override fun equals(other: Any?): Boolean = other is R_ObjectType && other.rObject == rObject
    override fun hashCode(): Int = rObject.hashCode()
    override fun createGtxConversion() = GtxRtConversion_None
}

class R_RecordFlags(val typeFlags: R_TypeFlags, val cyclic: Boolean, val infinite: Boolean)

class R_RecordType(name: String): R_Type(name) {
    private lateinit var bodyLate: RRecordBody
    private lateinit var flagsLate: R_RecordFlags

    val attributes: Map<String, R_Attrib> get() = bodyLate.attrMap
    val attributesList: List<R_Attrib> get() = bodyLate.attrList
    val flags: R_RecordFlags get() = flagsLate

    fun setAttributes(attrs: Map<String, R_Attrib>) {
        val attrsList = attrs.values.toList()
        attrsList.withIndex().forEach { (idx, attr) -> check(attr.index == idx) }
        val attrMutable = attrs.values.any { it.mutable }
        bodyLate = RRecordBody(attrs, attrsList, attrMutable)
    }

    fun setFlags(flags: R_RecordFlags) {
        flagsLate = flags
    }

    override fun isReference() = true
    override fun isDirectMutable() = bodyLate.attrMutable
    override fun completeFlags() = flagsLate.typeFlags

    override fun toStrictString(): String = name
    override fun componentTypes() = attributesList.map { it.type }.toList()

    override fun createGtxConversion() = GtxRtConversion_Record(this)

    private class RRecordBody(val attrMap: Map<String, R_Attrib>, val attrList: List<R_Attrib>, val attrMutable: Boolean)
}

class R_EnumAttr(val name: String, val value: Int)

class R_EnumType(name: String, val attrs: List<R_EnumAttr>): R_Type(name) {
    private val attrMap = attrs.map { Pair(it.name, it) }.toMap()
    private val rtValues = attrs.map { Rt_EnumValue(this, it) }

    override fun isSqlCompatible(): Boolean = true
    override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) = stmt.setInt(idx, value.asEnum().value)
    override fun fromSql(rs: ResultSet, idx: Int): Rt_Value = Rt_EnumValue(this, attrs[rs.getInt(idx)])
    override fun fromCli(s: String): Rt_Value = Rt_EnumValue(this, attrMap.getValue(s))
    override fun toStrictString(): String = name

    override fun createGtxConversion() = GtxRtConversion_Enum(this)

    fun attr(name: String): R_EnumAttr? {
        return attrMap[name]
    }

    fun attr(value: Long): R_EnumAttr? {
        if (value < 0 || value >= attrs.size) {
            return null
        }
        return attrs[value.toInt()]
    }

    fun values(): List<Rt_Value> {
        return rtValues
    }
}

class R_NullableType(val valueType: R_Type): R_Type(valueType.name + "?") {
    init {
        check(valueType != R_NullType)
        check(valueType != R_UnitType)
        check(valueType !is R_NullableType)
    }

    override fun isReference() = valueType.isReference()
    override fun isDirectMutable() = false

    override fun fromCli(s: String): Rt_Value = if (s == "null") Rt_NullValue else valueType.fromCli(s)
    override fun toStrictString() = name
    override fun componentTypes() = listOf(valueType)
    override fun equals(other: Any?) = other is R_NullableType && valueType == other.valueType
    override fun hashCode() = valueType.hashCode()

    override fun isAssignableFrom(type: R_Type): Boolean {
        return type == this || type == R_NullType || valueType.isAssignableFrom(type)
    }

    override fun createGtxConversion() = GtxRtConversion_Nullable(this)
}

// TODO: make this more elaborate
class R_ClosureType(name: String): R_Type(name) {
    override fun toStrictString(): String = TODO("TODO")
    override fun createGtxConversion() = GtxRtConversion_None
}

sealed class R_CollectionType(val elementType: R_Type, baseName: String): R_Type("$baseName<${elementType.toStrictString()}>") {
    final override fun isReference() = true
    final override fun isDirectMutable() = true
    final override fun toStrictString(): String = name
    final override fun componentTypes(): List<R_Type> = listOf(elementType)
}

class R_ListType(elementType: R_Type): R_CollectionType(elementType, "list") {
    override fun fromCli(s: String): Rt_Value = Rt_ListValue(this, s.split(",").map { elementType.fromCli(it) }.toMutableList())
    override fun equals(other: Any?): Boolean = other is R_ListType && elementType == other.elementType
    override fun createGtxConversion() = GtxRtConversion_List(this)
}

class R_SetType(elementType: R_Type): R_CollectionType(elementType, "set") {
    override fun fromCli(s: String): Rt_Value = Rt_SetValue(this, s.split(",").map { elementType.fromCli(it) }.toMutableSet())
    override fun equals(other: Any?): Boolean = other is R_SetType && elementType == other.elementType
    override fun createGtxConversion() = GtxRtConversion_Set(this)
}

class R_MapType(val keyType: R_Type, val valueType: R_Type): R_Type("map<${keyType.toStrictString()},${valueType.toStrictString()}>") {
    override fun isReference() = true
    override fun isDirectMutable() = true

    override fun toStrictString(): String = name
    override fun componentTypes(): List<R_Type> = listOf(keyType, valueType)
    override fun equals(other: Any?): Boolean = other is R_MapType && keyType == other.keyType && valueType == other.valueType

    override fun fromCli(s: String): Rt_Value {
        val map = s.split(",").associate {
            val (k, v) = it.split("=")
            Pair(keyType.fromCli(k), valueType.fromCli(v))
        }
        return Rt_MapValue(this, map.toMutableMap())
    }

    override fun createGtxConversion() = GtxRtConversion_Map(this)
}

class R_TupleField(val name: String?, val type: R_Type) {
    fun toStrictString(): String {
        return if (name != null) "${name}:${type.toStrictString()}" else type.toStrictString()
    }

    override fun toString(): String = toStrictString()
    override fun equals(other: Any?): Boolean = other is R_TupleField && name == other.name && type == other.type
}

class R_TupleType(val fields: List<R_TupleField>): R_Type("(${fields.joinToString(",") { it.toStrictString() }})") {
    override fun isReference() = true
    override fun isDirectMutable() = false

    override fun toStrictString(): String = name
    override fun equals(other: Any?): Boolean = other is R_TupleType && fields == other.fields

    override fun componentTypes() = fields.map { it.type }.toList()

    override fun isAssignableFrom(type: R_Type): Boolean {
        if (type !is R_TupleType) return false
        if (fields.size != type.fields.size) return false

        for (i in fields.indices) {
            val field = fields[i]
            val otherField = type.fields[i]
            if (field.name != otherField.name) return false
            if (!field.type.isAssignableFrom(otherField.type)) return false
        }

        return true
    }

    override fun calcCommonType(other: R_Type): R_Type? {
        if (other !is R_TupleType) return null
        if (fields.size != other.fields.size) return null

        val resFields = fields.mapIndexed { i, field ->
            val otherField = other.fields[i]
            if (field.name != otherField.name) return null
            val type = R_Type.commonTypeOpt(field.type, otherField.type)
            if (type == null) return null
            R_TupleField(field.name, type)
        }

        return R_TupleType(resFields)
    }

    override fun createGtxConversion() = GtxRtConversion_Tuple(this)
}

object R_RangeType: R_Type("range") {
    override fun isReference() = true
    override fun toStrictString(): String = "range"
    override fun createGtxConversion() = GtxRtConversion_None
}

object R_GtxValueType: R_Type("GTXValue") {
    override fun isReference() = true
    override fun toStrictString() = name
    override fun createGtxConversion() = GtxRtConversion_GtxValue
}
