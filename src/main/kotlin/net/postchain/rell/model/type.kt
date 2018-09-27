package net.postchain.rell.model

import org.jooq.DataType
import org.jooq.SQLDialect
import org.jooq.impl.DefaultDataType
import org.jooq.impl.SQLDataType
import org.jooq.util.postgres.PostgresDataType

sealed class RType(val name: String)
open class RPrimitiveType(name: String, val sqlType: DataType<*>): RType(name)

object RUnitType: RPrimitiveType("unit", SQLDataType.OTHER)
object RBooleanType: RPrimitiveType("boolean", SQLDataType.BOOLEAN)
object RTextType: RPrimitiveType("text", PostgresDataType.TEXT)
object RIntegerType: RPrimitiveType("integer", SQLDataType.BIGINT)
object RByteArrayType: RPrimitiveType("byte_array", PostgresDataType.BYTEA)
object RTimestampType: RPrimitiveType("timestamp", SQLDataType.BIGINT)
object RGUIDType: RPrimitiveType("guid", PostgresDataType.BYTEA)

val gtxSignerSQLDataType = DefaultDataType(null as SQLDialect?, ByteArray::class.java, "gtx_signer")

object RSignerType: RPrimitiveType("signer", gtxSignerSQLDataType)

val jsonSQLDataType = DefaultDataType(null as SQLDialect?, String::class.java, "jsonb")

object RJSONType: RPrimitiveType("json", jsonSQLDataType)
class RInstanceRefType(className: String, val rclass: RClass): RType(className)

// TODO: make this more elaborate
class RClosureType(name: String): RType(name)

sealed class SqlType
class ClassSqlType(cls: RClass): SqlType()
