/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model

import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvNull
import net.postchain.rell.compiler.C_Constants
import net.postchain.rell.module.*
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.CommonUtils
import org.jooq.DataType
import org.jooq.SQLDialect
import org.jooq.impl.DefaultDataType
import org.jooq.impl.SQLDataType
import org.jooq.util.postgres.PostgresDataType
import org.postgresql.util.PGobject
import org.spongycastle.util.Arrays
import java.sql.PreparedStatement
import java.sql.ResultSet

class R_GtvCompatibility(val fromGtv: Boolean, val toGtv: Boolean)

class R_TypeFlags(val mutable: Boolean, val gtv: R_GtvCompatibility, val virtualable: Boolean) {
    companion object {
        fun combine(flags: Collection<R_TypeFlags>): R_TypeFlags {
            var mutable = false
            var fromGtv = true
            var toGtv = true
            var virtualable = true

            for (f in flags) {
                mutable = mutable or f.mutable
                fromGtv = fromGtv and f.gtv.fromGtv
                toGtv = toGtv and f.gtv.toGtv
                virtualable = virtualable and f.virtualable
            }

            return R_TypeFlags(
                    mutable = mutable,
                    gtv = R_GtvCompatibility(fromGtv, toGtv),
                    virtualable = virtualable
            )
        }
    }
}

sealed class R_TypeSqlAdapter {
    abstract fun isSqlCompatible(): Boolean
    abstract fun toSqlValue(value: Rt_Value): Any
    abstract fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value)
    abstract fun fromSql(rs: ResultSet, idx: Int): Rt_Value
    abstract fun metaName(sqlCtx: Rt_SqlContext): String
}

private class R_TypeSqlAdapter_None(private val type: R_Type): R_TypeSqlAdapter() {
    override fun isSqlCompatible(): Boolean = false

    override fun toSqlValue(value: Rt_Value): Any {
        throw Rt_Utils.errNotSupported("Type cannot be converted to SQL: ${type.toStrictString()}")
    }

    override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) {
        throw Rt_Utils.errNotSupported("Type cannot be converted to SQL: ${type.toStrictString()}")
    }

    override fun fromSql(rs: ResultSet, idx: Int): Rt_Value {
        throw Rt_Utils.errNotSupported("Type cannot be converted from SQL: ${type.toStrictString()}")
    }

    override fun metaName(sqlCtx: Rt_SqlContext): String {
        throw Rt_Utils.errNotSupported("Type has no meta name: ${type.toStrictString()}")
    }
}

private abstract class R_TypeSqlAdapter_Some: R_TypeSqlAdapter() {
    final override fun isSqlCompatible() = true

    protected fun checkSqlNull(suspect: Boolean, rs: ResultSet, type: R_Type) {
        if (suspect && rs.wasNull()) {
            throw errSqlNull(type)
        }
    }

    protected fun checkSqlNull(value: Any?, type: R_Type) {
        if (value == null) {
            throw errSqlNull(type)
        }
    }

    private fun errSqlNull(type: R_Type) = Rt_Error("sql_null:${type.toStrictString()}", "Got NULL from SQL where expected $type")
}

private abstract class R_TypeSqlAdapter_Primitive(private val name: String): R_TypeSqlAdapter_Some() {
    override final fun metaName(sqlCtx: Rt_SqlContext): String = "sys:$name"
}

sealed class R_Type(val name: String) {
    val toTextFunction = "$name.to_text"

    private val gtvConversion by lazy { createGtvConversion() }
    val sqlAdapter = createSqlAdapter()

    open fun isReference(): Boolean = false
    open fun defaultValue(): Rt_Value? = null
    open fun comparator(): Comparator<Rt_Value>? = null
    protected open fun isDirectMutable(): Boolean = false
    protected open fun isDirectVirtualable(): Boolean = true

    fun directFlags(): R_TypeFlags {
        val gtvConv = gtvConversion
        return R_TypeFlags(isDirectMutable(), gtvConv.directCompatibility(), isDirectVirtualable())
    }

    open fun completeFlags(): R_TypeFlags {
        val flags = mutableListOf(directFlags())
        for (sub in componentTypes()) {
            flags.add(sub.completeFlags())
        }
        return R_TypeFlags.combine(flags)
    }

    protected open fun createSqlAdapter(): R_TypeSqlAdapter = R_TypeSqlAdapter_None(this)

    open fun fromCli(s: String): Rt_Value = throw UnsupportedOperationException()

    fun rtToGtv(rt: Rt_Value, pretty: Boolean): Gtv = gtvConversion.rtToGtv(rt, pretty)
    fun gtvToRt(ctx: GtvToRtContext, gtv: Gtv) = gtvConversion.gtvToRt(ctx, gtv)
    protected abstract fun createGtvConversion(): GtvRtConversion

    abstract fun toStrictString(): String
    final override fun toString(): String = toStrictString()

    open fun componentTypes(): List<R_Type> = listOf()

    open fun isAssignableFrom(type: R_Type): Boolean = type == this
    protected open fun calcCommonType(other: R_Type): R_Type? = null

    open fun getTypeAdapter(sourceType: R_Type): R_TypeAdapter? {
        val assignable = isAssignableFrom(sourceType)
        return if (assignable) R_TypeAdapter_Direct else null
    }

    abstract fun toMetaGtv(): Gtv

    companion object {
        fun commonTypeOpt(a: R_Type, b: R_Type): R_Type? {
            if (a == R_CtErrorType) {
                return b
            } else if (b == R_CtErrorType) {
                return a
            }

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

object R_CtErrorType: R_Type("<error>") {
    override fun createGtvConversion() = GtvRtConversion_None
    override fun toStrictString() = "<error>"
    override fun isAssignableFrom(type: R_Type): Boolean = true
    override open fun calcCommonType(other: R_Type): R_Type? = other
    override fun toMetaGtv(): Gtv = throw UnsupportedOperationException()
}

sealed class R_PrimitiveType(name: String, val sqlType: DataType<*>): R_Type(name) {
    final override fun toStrictString(): String = name
    final override fun toMetaGtv() = name.toGtv()
}

object R_UnitType: R_PrimitiveType("unit", SQLDataType.OTHER) {
    override fun createGtvConversion() = GtvRtConversion_None
}

object R_BooleanType: R_PrimitiveType("boolean", SQLDataType.BOOLEAN) {
    override fun defaultValue() = Rt_BooleanValue(false)
    override fun comparator() = Rt_Comparator.create { it.asBoolean() }

    override fun fromCli(s: String): Rt_Value {
        if (s == "false") return Rt_BooleanValue(false)
        else if (s == "true") return Rt_BooleanValue(true)
        else throw IllegalArgumentException(s)
    }

    override fun createGtvConversion() = GtvRtConversion_Boolean
    override fun createSqlAdapter(): R_TypeSqlAdapter = R_TypeSqlAdapter_Boolean

    private object R_TypeSqlAdapter_Boolean: R_TypeSqlAdapter_Primitive("boolean") {
        override fun toSqlValue(value: Rt_Value) = value.asBoolean()

        override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) {
            stmt.setBoolean(idx, value.asBoolean())
        }

        override fun fromSql(rs: ResultSet, idx: Int): Rt_Value {
            val v = rs.getBoolean(idx)
            checkSqlNull(!v, rs, R_BooleanType)
            return Rt_BooleanValue(v)
        }
    }
}

object R_TextType: R_PrimitiveType("text", PostgresDataType.TEXT) {
    override fun defaultValue() = Rt_TextValue("")
    override fun comparator() = Rt_Comparator.create { it.asString() }
    override fun fromCli(s: String): Rt_Value = Rt_TextValue(s)
    override fun createGtvConversion() = GtvRtConversion_Text
    override fun createSqlAdapter(): R_TypeSqlAdapter = R_TypeSqlAdapter_Text

    private object R_TypeSqlAdapter_Text: R_TypeSqlAdapter_Primitive("text") {
        override fun toSqlValue(value: Rt_Value) = value.asString()

        override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) {
            stmt.setString(idx, value.asString())
        }

        override fun fromSql(rs: ResultSet, idx: Int): Rt_Value {
            val v = rs.getString(idx)
            checkSqlNull(v, R_TextType)
            return Rt_TextValue(v)
        }
    }
}

object R_IntegerType: R_PrimitiveType("integer", SQLDataType.BIGINT) {
    override fun defaultValue() = Rt_IntValue(0)
    override fun comparator() = Rt_Comparator.create { it.asInteger() }
    override fun fromCli(s: String): Rt_Value = Rt_IntValue(s.toLong())

    override fun createGtvConversion() = GtvRtConversion_Integer
    override fun createSqlAdapter(): R_TypeSqlAdapter = R_TypeSqlAdapter_Integer

    private object R_TypeSqlAdapter_Integer: R_TypeSqlAdapter_Primitive("integer") {
        override fun toSqlValue(value: Rt_Value) = value.asInteger()

        override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) {
            stmt.setLong(idx, value.asInteger())
        }

        override fun fromSql(rs: ResultSet, idx: Int): Rt_Value {
            val v = rs.getLong(idx)
            checkSqlNull(v == 0L, rs, R_IntegerType)
            return Rt_IntValue(v)
        }
    }
}

object R_DecimalType: R_PrimitiveType("decimal", C_Constants.DECIMAL_SQL_TYPE) {
    override fun defaultValue() = Rt_DecimalValue.ZERO
    override fun comparator() = Rt_Comparator.create { it.asDecimal() }
    override fun fromCli(s: String): Rt_Value = Rt_DecimalValue.of(s)

    override fun createGtvConversion() = GtvRtConversion_Decimal
    override fun createSqlAdapter(): R_TypeSqlAdapter = R_TypeSqlAdapter_Decimal

    override fun getTypeAdapter(sourceType: R_Type): R_TypeAdapter? {
        return if (sourceType == R_IntegerType) {
            R_TypeAdapter_IntegerToDecimal
        } else {
            super.getTypeAdapter(sourceType)
        }
    }

    private object R_TypeSqlAdapter_Decimal: R_TypeSqlAdapter_Primitive("decimal") {
        override fun toSqlValue(value: Rt_Value) = value.asDecimal()

        override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) {
            stmt.setBigDecimal(idx, value.asDecimal())
        }

        override fun fromSql(rs: ResultSet, idx: Int): Rt_Value {
            val v = rs.getBigDecimal(idx)
            checkSqlNull(v, R_DecimalType)
            return Rt_DecimalValue.of(v)
        }
    }
}

object R_ByteArrayType: R_PrimitiveType("byte_array", PostgresDataType.BYTEA) {
    override fun defaultValue() = Rt_ByteArrayValue(byteArrayOf())
    override fun comparator() = Rt_Comparator({ it.asByteArray() }, Comparator { x, y -> Arrays.compareUnsigned(x, y) })
    override fun fromCli(s: String): Rt_Value = Rt_ByteArrayValue(CommonUtils.hexToBytes(s))

    override fun createGtvConversion() = GtvRtConversion_ByteArray
    override fun createSqlAdapter(): R_TypeSqlAdapter = R_TypeSqlAdapter_ByteArray

    private object R_TypeSqlAdapter_ByteArray: R_TypeSqlAdapter_Primitive("byte_array") {
        override fun toSqlValue(value: Rt_Value) = value.asByteArray()
        override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) = stmt.setBytes(idx, value.asByteArray())

        override fun fromSql(rs: ResultSet, idx: Int): Rt_Value {
            val v = rs.getBytes(idx)
            checkSqlNull(v, R_ByteArrayType)
            return Rt_ByteArrayValue(v)
        }
    }
}

object R_RowidType: R_PrimitiveType("rowid", SQLDataType.BIGINT) {
    override fun defaultValue() = Rt_RowidValue(0)
    override fun comparator() = Rt_Comparator.create { it.asRowid() }
    override fun fromCli(s: String): Rt_Value = Rt_RowidValue(s.toLong())

    override fun createGtvConversion() = GtvRtConversion_Rowid
    override fun createSqlAdapter(): R_TypeSqlAdapter = R_TypeSqlAdapter_Rowid

    private object R_TypeSqlAdapter_Rowid: R_TypeSqlAdapter_Primitive("rowid") {
        override fun toSqlValue(value: Rt_Value) = value.asRowid()

        override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) {
            stmt.setLong(idx, value.asRowid())
        }

        override fun fromSql(rs: ResultSet, idx: Int): Rt_Value {
            val v = rs.getLong(idx)
            checkSqlNull(v == 0L, rs, R_RowidType)
            return Rt_RowidValue(v)
        }
    }
}

object R_TimestampType: R_PrimitiveType("timestamp", SQLDataType.BIGINT) {
    //TODO support Gtv
    override fun createGtvConversion() = GtvRtConversion_None
}

object R_GUIDType: R_PrimitiveType("guid", PostgresDataType.BYTEA) {
    //TODO support Gtv
    override fun createGtvConversion() = GtvRtConversion_None
}

private val GTX_SIGNER_SQL_DATA_TYPE = DefaultDataType(null as SQLDialect?, ByteArray::class.java, "gtx_signer")

object R_SignerType: R_PrimitiveType("signer", GTX_SIGNER_SQL_DATA_TYPE) {
    //TODO support Gtv
    override fun createGtvConversion() = GtvRtConversion_None
}

private val JSON_SQL_DATA_TYPE = DefaultDataType(null as SQLDialect?, String::class.java, "jsonb")

object R_JsonType: R_PrimitiveType("json", JSON_SQL_DATA_TYPE) {
    override fun comparator() = Rt_Comparator.create { it.asJsonString() }
    override fun fromCli(s: String): Rt_Value = Rt_JsonValue.parse(s)

    //TODO consider converting between Rt_JsonValue and arbitrary Gtv, not only String
    override fun createGtvConversion() = GtvRtConversion_Json

    override fun createSqlAdapter(): R_TypeSqlAdapter = R_TypeSqlAdapter_Json

    private object R_TypeSqlAdapter_Json: R_TypeSqlAdapter_Primitive("json") {
        override fun toSqlValue(value: Rt_Value): Any {
            val str = value.asJsonString()
            val obj = PGobject()
            obj.type = "json"
            obj.value = str
            return obj
        }

        override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) {
            val obj = toSqlValue(value)
            stmt.setObject(idx, obj)
        }

        override fun fromSql(rs: ResultSet, idx: Int): Rt_Value {
            val str = rs.getString(idx)
            checkSqlNull(str, R_JsonType)
            return Rt_JsonValue.parse(str)
        }
    }
}

object R_NullType: R_Type("null") {
    override fun defaultValue() = Rt_NullValue
    override fun comparator() = Rt_Comparator.create { 0 }
    override fun calcCommonType(other: R_Type): R_Type? = R_NullableType(other)
    override fun createGtvConversion() = GtvRtConversion_Null
    override fun toStrictString() = "null"
    override fun toMetaGtv() = "null".toGtv()
}

class R_EntityType(val rEntity: R_Entity): R_Type(rEntity.appLevelName) {
    override fun comparator() = Rt_Comparator.create { it.asObjectId() }
    override fun fromCli(s: String): Rt_Value = Rt_EntityValue(this, s.toLong())
    override fun toStrictString(): String = name
    override fun equals(other: Any?): Boolean = other is R_EntityType && other.rEntity == rEntity
    override fun hashCode(): Int = rEntity.hashCode()

    override fun createGtvConversion() = GtvRtConversion_Entity(this)
    override fun createSqlAdapter(): R_TypeSqlAdapter = R_TypeSqlAdapter_Entity(this)

    override fun toMetaGtv() = rEntity.appLevelName.toGtv()

    private class R_TypeSqlAdapter_Entity(private val type: R_EntityType): R_TypeSqlAdapter_Some() {
        override fun toSqlValue(value: Rt_Value) = value.asObjectId()
        override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) = stmt.setLong(idx, value.asObjectId())

        override fun fromSql(rs: ResultSet, idx: Int): Rt_Value {
            val v = rs.getLong(idx)
            checkSqlNull(v == 0L, rs, type)
            return Rt_EntityValue(type, v)
        }

        override fun metaName(sqlCtx: Rt_SqlContext): String {
            val rEntity = type.rEntity
            val chain = sqlCtx.chainMapping(rEntity.external?.chain)
            val metaName = rEntity.metaName
            return "class:${chain.chainId}:$metaName"
        }
    }
}

class R_ObjectType(val rObject: R_Object): R_Type(rObject.appLevelName) {
    override fun isDirectVirtualable() = false
    override fun equals(other: Any?): Boolean = other is R_ObjectType && other.rObject == rObject
    override fun hashCode(): Int = rObject.hashCode()
    override fun createGtvConversion() = GtvRtConversion_None
    override fun toStrictString(): String = name
    override fun toMetaGtv() = rObject.appLevelName.toGtv()
}

class R_StructType(val struct: R_Struct): R_Type(struct.appLevelName) {
    override fun isReference() = true
    override fun isDirectMutable() = struct.isDirectlyMutable()
    override fun completeFlags() = struct.flags.typeFlags

    override fun componentTypes() = struct.attributesList.map { it.type }.toList()
    override fun createGtvConversion() = GtvRtConversion_Struct(struct)

    override fun toStrictString(): String = name
    override fun toMetaGtv() = struct.appLevelName.toGtv()
}

class R_EnumType(val enum: R_Enum): R_Type(enum.appLevelName) {
    override fun comparator() = Rt_Comparator.create { it.asEnum().value }

    override fun fromCli(s: String): Rt_Value {
        val attr = enum.attr(s)
        requireNotNull(attr) { "$name: $s" }
        return Rt_EnumValue(this, attr)
    }

    override fun createGtvConversion() = GtvRtConversion_Enum(enum)

    override fun createSqlAdapter(): R_TypeSqlAdapter = R_TypeSqlAdapter_Enum(this)

    override fun toStrictString() = name
    override fun toMetaGtv() = enum.appLevelName.toGtv()

    private class R_TypeSqlAdapter_Enum(private val type: R_EnumType): R_TypeSqlAdapter_Some() {
        override fun toSqlValue(value: Rt_Value) = value.asEnum().value
        override fun toSql(stmt: PreparedStatement, idx: Int, value: Rt_Value) = stmt.setInt(idx, value.asEnum().value)

        override fun fromSql(rs: ResultSet, idx: Int): Rt_Value {
            val v = rs.getInt(idx).toLong()
            checkSqlNull(v == 0L, rs, type)
            val attr = type.enum.attr(v)
            requireNotNull(attr) { "$type: $v" }
            return Rt_EnumValue(type, attr)
        }

        override fun metaName(sqlCtx: Rt_SqlContext): String {
            return "enum:${type.name}"
        }
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

    override fun defaultValue() = Rt_NullValue
    override fun comparator() = valueType.comparator()
    override fun fromCli(s: String): Rt_Value = if (s == "null") Rt_NullValue else valueType.fromCli(s)
    override fun toStrictString() = name
    override fun componentTypes() = listOf(valueType)
    override fun equals(other: Any?) = other is R_NullableType && valueType == other.valueType
    override fun hashCode() = valueType.hashCode()

    override fun isAssignableFrom(type: R_Type): Boolean {
        return type == this
                || type == R_NullType
                || (type is R_NullableType && valueType.isAssignableFrom(type.valueType))
                || valueType.isAssignableFrom(type)
    }

    override fun getTypeAdapter(sourceType: R_Type): R_TypeAdapter? {
        var adapter = super.getTypeAdapter(sourceType)
        if (adapter != null) {
            return adapter
        }

        if (sourceType is R_NullableType) {
            adapter = valueType.getTypeAdapter(sourceType.valueType)
            return if (adapter == null) null else R_TypeAdapter_Nullable(this, adapter)
        } else {
            return valueType.getTypeAdapter(sourceType)
        }
    }

    override fun createGtvConversion() = GtvRtConversion_Nullable(this)

    override fun toMetaGtv() = mapOf(
            "type" to "nullable".toGtv(),
            "value" to valueType.toMetaGtv()
    ).toGtv()
}

// TODO: make this more elaborate
class R_ClosureType(name: String): R_Type(name) {
    override fun isDirectVirtualable() = false
    override fun createGtvConversion() = GtvRtConversion_None
    override fun toStrictString() = TODO()
    override fun toMetaGtv() = TODO()
}

sealed class R_CollectionType(val elementType: R_Type, val baseName: String): R_Type("$baseName<${elementType.toStrictString()}>") {
    final override fun isReference() = true
    final override fun isDirectMutable() = true
    final override fun componentTypes() = listOf(elementType)
    final override fun toStrictString() = name

    final override fun toMetaGtv() = mapOf(
            "type" to baseName.toGtv(),
            "value" to elementType.toMetaGtv()
    ).toGtv()
}

class R_ListType(elementType: R_Type): R_CollectionType(elementType, "list") {
    val virtualType = R_VirtualListType(this)

    override fun fromCli(s: String): Rt_Value = Rt_ListValue(this, s.split(",").map { elementType.fromCli(it) }.toMutableList())
    override fun equals(other: Any?): Boolean = other is R_ListType && elementType == other.elementType
    override fun createGtvConversion() = GtvRtConversion_List(this)

    override fun comparator(): Comparator<Rt_Value>? {
        val elemComparator = elementType.comparator()
        return if (elemComparator == null) null else Rt_ListComparator(elemComparator)
    }
}

class R_SetType(elementType: R_Type): R_CollectionType(elementType, "set") {
    val virtualType = R_VirtualSetType(this)

    override fun fromCli(s: String): Rt_Value = Rt_SetValue(this, s.split(",").map { elementType.fromCli(it) }.toMutableSet())
    override fun equals(other: Any?): Boolean = other is R_SetType && elementType == other.elementType
    override fun createGtvConversion() = GtvRtConversion_Set(this)
}

class R_MapKeyValueTypes(val key: R_Type, val value: R_Type)

class R_MapType(val keyValueTypes: R_MapKeyValueTypes): R_Type("map<${keyValueTypes.key.toStrictString()},${keyValueTypes.value.toStrictString()}>") {
    constructor(keyType: R_Type, valueType: R_Type): this(R_MapKeyValueTypes(keyType, valueType))

    val keyType = keyValueTypes.key
    val valueType = keyValueTypes.value
    val virtualType = R_VirtualMapType(this)

    override fun isReference() = true
    override fun isDirectMutable() = true
    override fun isDirectVirtualable() = keyType == R_TextType

    override fun toStrictString() = name
    override fun componentTypes() = listOf(keyType, valueType)
    override fun equals(other: Any?) = other is R_MapType && keyType == other.keyType && valueType == other.valueType

    override fun fromCli(s: String): Rt_Value {
        val map = s.split(",").associate {
            val (k, v) = it.split("=")
            Pair(keyType.fromCli(k), valueType.fromCli(v))
        }
        return Rt_MapValue(this, map.toMutableMap())
    }

    override fun createGtvConversion() = GtvRtConversion_Map(this)

    override fun toMetaGtv() = mapOf(
            "type" to "map".toGtv(),
            "key" to keyType.toMetaGtv(),
            "value" to valueType.toMetaGtv()
    ).toGtv()
}

class R_TupleField(val name: String?, val type: R_Type) {
    fun toStrictString(): String {
        return if (name != null) "${name}:${type.toStrictString()}" else type.toStrictString()
    }

    override fun toString(): String = toStrictString()
    override fun equals(other: Any?): Boolean = other is R_TupleField && name == other.name && type == other.type

    fun toMetaGtv() = mapOf(
            "name" to (name?.toGtv() ?: GtvNull),
            "type" to type.toMetaGtv()
    ).toGtv()
}

class R_TupleType(val fields: List<R_TupleField>): R_Type("(${fields.joinToString(",") { it.toStrictString() }})") {
    val virtualType = R_VirtualTupleType(this)

    override fun isReference() = true
    override fun isDirectMutable() = false

    override fun toStrictString() = name
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
            val type = commonTypeOpt(field.type, otherField.type)
            if (type == null) return null
            R_TupleField(field.name, type)
        }

        return R_TupleType(resFields)
    }

    override fun createGtvConversion() = GtvRtConversion_Tuple(this)

    override fun comparator(): Comparator<Rt_Value>? {
        val fieldComparators = mutableListOf<Comparator<Rt_Value>>()
        for (field in fields) {
            val comparator = field.type.comparator()
            comparator ?: return null
            fieldComparators.add(comparator)
        }
        return Rt_TupleComparator(fieldComparators)
    }

    override fun toMetaGtv() = mapOf(
            "type" to "tuple".toGtv(),
            "fields" to fields.map { it.toMetaGtv() }.toGtv()
    ).toGtv()
}

object R_RangeType: R_Type("range") {
    override fun isDirectVirtualable() = false
    override fun isReference() = true
    override fun comparator() = Rt_Comparator.create { it.asRange() }
    override fun createGtvConversion() = GtvRtConversion_None
    override fun toStrictString(): String = name
    override fun toMetaGtv() = name.toGtv()
}

object R_GtvType: R_Type("gtv") {
    override fun isReference() = true
    override fun createGtvConversion() = GtvRtConversion_Gtv
    override fun toStrictString() = name
    override fun toMetaGtv() = name.toGtv()
}

sealed class R_VirtualType(private val innerType: R_Type): R_Type("virtual<${innerType.name}>") {
    final override fun isReference() = true
    final override fun toStrictString() = name
    final override fun toMetaGtv() = mapOf(
            "type" to "virtual".toGtv(),
            "value" to innerType.toMetaGtv()
    ).toGtv()
}

sealed class R_VirtualCollectionType(innerType: R_Type): R_VirtualType(innerType) {
    abstract fun elementType(): R_Type
}

class R_VirtualListType(val innerType: R_ListType): R_VirtualCollectionType(innerType) {
    override fun createGtvConversion() = GtvRtConversion_VirtualList(this)
    override fun equals(other: Any?): Boolean = other is R_VirtualListType && innerType == other.innerType
    override fun elementType() = innerType.elementType
}

class R_VirtualSetType(val innerType: R_SetType): R_VirtualCollectionType(innerType) {
    override fun createGtvConversion() = GtvRtConversion_VirtualSet(this)
    override fun equals(other: Any?): Boolean = other is R_VirtualSetType && innerType == other.innerType
    override fun elementType() = innerType.elementType
}

class R_VirtualMapType(val innerType: R_MapType): R_VirtualType(innerType) {
    override fun createGtvConversion() = GtvRtConversion_VirtualMap(this)
    override fun equals(other: Any?): Boolean = other is R_VirtualMapType && innerType == other.innerType
}

class R_VirtualTupleType(val innerType: R_TupleType): R_VirtualType(innerType) {
    override fun createGtvConversion() = GtvRtConversion_VirtualTuple(this)
    override fun equals(other: Any?): Boolean = other is R_VirtualTupleType && innerType == other.innerType
}

class R_VirtualStructType(val innerType: R_StructType): R_VirtualType(innerType) {
    override fun createGtvConversion() = GtvRtConversion_VirtualStruct(this)
    override fun equals(other: Any?): Boolean = other is R_VirtualStructType && innerType == other.innerType
}

object R_OperationType: R_Type("operation") {
    override fun isReference() = true
    override fun createGtvConversion() = GtvRtConversion_Operation
    override fun toStrictString(): String = name
    override fun toMetaGtv() = name.toGtv()
}

object R_GtxBlockType: R_Type("rell.gtx.block") {
    override fun isReference() = true
    override fun createGtvConversion() = GtvRtConversion_GtxBlock
    override fun toStrictString(): String = name
    override fun toMetaGtv() = name.toGtv()
}

object R_GtxTxType: R_Type("rell.gtx.tx") {
    override fun isReference() = true
    override fun createGtvConversion() = GtvRtConversion_GtxTx
    override fun toStrictString(): String = name
    override fun toMetaGtv() = name.toGtv()
}
