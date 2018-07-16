package net.postchain.rell

import org.jooq.DataType
import org.jooq.SQLDialect
import org.jooq.impl.DefaultDataType
import org.jooq.impl.SQLDataType
import org.jooq.util.postgres.PostgresDataType

sealed class RType(val name: String)

open class RPrimitiveType(name: String,
                          val sqlType: DataType<*>): RType(name)

object RBooleanType: RPrimitiveType("boolean", SQLDataType.BOOLEAN)
object RTextType: RPrimitiveType("text", PostgresDataType.TEXT)
object RIntegerType: RPrimitiveType("integer", SQLDataType.BIGINT)
object RByteArrayType: RPrimitiveType("byte_array", PostgresDataType.BYTEA)
object RTimestampType: RPrimitiveType("timestamp", SQLDataType.BIGINT)
object RGUIDType: RPrimitiveType("guid", PostgresDataType.BYTEA)
val gtxSignerSQLDataType = DefaultDataType(null as SQLDialect?, ByteArray::class.java, "gtx_signer")
object RSignerType: RPrimitiveType("signer", gtxSignerSQLDataType)

class RInstanceRefType (className: String, val rclass: RClass): RType(className)

class RKey(val attribs: Array<String>)
class RIndex(val attribs: Array<String>)
class RAttrib(val name: String, val type: RType)
open class RRel (val name: String, val keys: Array<RKey>, val indexes: Array<RIndex>, val attributes: Array<RAttrib>)

class RClass (name: String, keys: Array<RKey>, indexes: Array<RIndex>, attributes: Array<RAttrib>)
    :RRel(name, keys, indexes, attributes)

sealed class RExpr(val type: RType)
class RVarRef(type: RType, val _var: RAttrib): RExpr(type)
class RAtExpr(type: RType, val rel: RRel, val attrConditions: List<Pair<RAttrib, RExpr>>): RExpr(type)
class RBinOpExpr(type: RType, val op: String, val left: RExpr, val right: RExpr): RExpr(type)
class RStringLiteral(type: RType, val literal: String): RExpr(type)
class RByteALiteral(type: RType, val literal: ByteArray): RExpr(type)
class RIntegerLiteral(type: RType, val literal: Long): RExpr(type)

class RAttrExpr(val attr: RAttrib, val expr: RExpr)

sealed class RStatement
class RCreateStatement(val rclass: RClass, val attrs: Array<RAttrExpr>): RStatement()
class RUpdateStatement(val atExpr: RAtExpr, val setAttrs: Array<RAttrExpr>): RStatement()
class RDeleteStatement(val atExpr: RAtExpr): RStatement()
class RCallStatement(val fname: String, val args: Array<RExpr>): RStatement()
class RFromStatement(val atExpr: RAttrExpr, val attrs: Array<RAttrib>): RStatement()

class ROperation(val name: String, val params: Array<RAttrib>, val statements: Array<RStatement>)

class RModule(val relations: Array<RRel>, val operations: Array<ROperation>)

typealias EnvMap = Map<String, RAttrib>
typealias TypeMap = Map<String, RType>

fun makeRAttrib(a: S_Attribute, types: TypeMap): RAttrib {
    val type = types[a.type]
    if (type != null)
        return RAttrib(a.name, types[a.type]!!)
    else
        throw Exception("Undefined type ${a.type}")
}

fun makeRClass(sClass: S_ClassDefinition, types: TypeMap): RClass {
    val attrs = sClass.attributes.map { makeRAttrib(it, types) }
    val keys = sClass.keys.map { RKey(it.attrNames.toTypedArray()) }
    val indexes = sClass.indices.map { RIndex(it.attrNames.toTypedArray()) }
    return RClass(sClass.identifier, keys.toTypedArray(), indexes.toTypedArray(), attrs.toTypedArray())
}

fun makeAtExpr(s: S_AtExpr, types: TypeMap, env: EnvMap): RAtExpr {
    val type = types[s.clasname]
    if ((type != null) && (type is RInstanceRefType)) {
        val conditions = s.where.map {
            if (it.op != "=") throw Exception("Only = is supported in AtExpr")
            if (! (it.left is S_VarRef)) throw Exception("AtExpr: LHS must be varrref")
            val expr = makeExpr(it.right, types, env)
            val attrname = it.left.varname
            val attr = type.rclass.attributes.find { it.name == attrname}
            if (attr == null) throw Exception("${type.rclass.name} does not have attribute ${attrname}")
            if (expr.type != attr.type) {
                println("Warning: type mismatch")
            }
            Pair(attr, expr)
        }
        return RAtExpr(type, type.rclass, conditions)
    } else
        throw Exception("Undefined type ${s.clasname}")
}

fun makeExpr(e: S_Expression, types: TypeMap, env: EnvMap): RExpr {
    return when (e) {
        is S_AtExpr -> makeAtExpr(e, types, env)
        is S_VarRef -> {
            val attr = env[e.varname]!!
            RVarRef(attr.type, attr)
        }
        is S_BinOp -> {
            // TODO: actual type
            RBinOpExpr(RBooleanType, e.op, makeExpr(e.left, types, env), makeExpr(e.right, types, env))
        }
        is S_StringLiteral -> RStringLiteral(RTextType, e.literal)
        is S_ByteALiteral -> RByteALiteral(RByteArrayType, e.bytes)
        is S_IntLiteral -> RIntegerLiteral(RIntegerType, e.value)
    }
}

fun makeAttrExpr(s_attr_expr: S_AttrExpr, rclass: RClass, types: TypeMap, env: EnvMap): RAttrExpr {
    val expr = makeExpr(s_attr_expr.expr, types, env)
    val attrname = s_attr_expr.name
    val class_attr = rclass.attributes.find { it.name == attrname }
    if (class_attr == null)
        throw Exception("${rclass.name} does not have attribute ${attrname}")
    if (expr.type != class_attr.type) {
        println("Warning: type mismatch")
    }
    return RAttrExpr(class_attr, expr)
}

fun makeCreateStatmenet(s: S_CreateStatement, types: TypeMap, env: EnvMap): RCreateStatement {
    val type = types[s.classname]
    if ((type != null) && (type is RInstanceRefType)) {
        return RCreateStatement(type.rclass, s.attrs.map {
            makeAttrExpr(it, type.rclass, types, env)
        }.toTypedArray())
    } else
        throw Exception("Undefined type ${s.classname}")
}

fun makeUpdateStatement(s: S_UpdateStatement, types: TypeMap, env: EnvMap): RUpdateStatement {
    val type = types[s.what.clasname]
    if ((type != null) && (type is RInstanceRefType)) {
        return RUpdateStatement(
                makeAtExpr(s.what, types, env),
                s.attrs.map {
                    makeAttrExpr(it, type.rclass, types, env)
                }.toTypedArray()
        )
    } else
        throw Exception("Undefined type ${s.what.clasname}")
}

fun makeDeleteStatement(s: S_DeleteStatement, types: TypeMap, env: EnvMap): RDeleteStatement {
    val type = types[s.what.clasname]
    if ((type != null) && (type is RInstanceRefType)) {
        return RDeleteStatement(makeAtExpr(s.what, types, env))
    } else
        throw Exception("Undefined type ${s.what.clasname}")
}

fun makeCallStatement(s: S_CallStatement, types: TypeMap, env: EnvMap): RStatement {
    return RCallStatement(
            s.fname,
            s.args.map { makeExpr(it, types, env)  }.toTypedArray()
    )
}

fun makeROperation(opDef: S_OpDefinition, types: TypeMap): ROperation {
    val args = opDef.args.map { makeRAttrib(it, types) }
    val env = args.associate { it.name to it }
    return ROperation(opDef.identifier,
            args.toTypedArray(),
            opDef.statements.map {
                when (it) {
                    is S_CreateStatement -> makeCreateStatmenet(it, types, env)
                    is S_CallStatement -> makeCallStatement(it, types, env)
                    is S_DeleteStatement -> makeDeleteStatement(it, types, env)
                    is S_UpdateStatement -> makeUpdateStatement(it, types, env)
                    else -> throw Exception("Statement not supported (yet)")
                }
            }.toTypedArray()
    )
}

fun makeModule(md: S_ModuleDefinition): RModule {
    val typeMap = mutableMapOf<String, RType>(
            "text" to RTextType,
            "byte_array" to RByteArrayType,
            "integer" to RIntegerType,
            "pubkey" to RByteArrayType,
            "name" to RTextType,
            "timestamp" to RTimestampType,
            "signer" to RSignerType,
            "guid" to RGUIDType
    )
    val relations = mutableListOf<RRel>()
    val operations = mutableListOf<ROperation>()
    for (def in md.definitions) {
        when (def) {
            is S_ClassDefinition -> {
                val rclass = makeRClass(def, typeMap)
                relations.add(rclass)
                typeMap[rclass.name] = RInstanceRefType(rclass.name, rclass)
            }
            is S_OpDefinition -> {
                operations.add(makeROperation(def, typeMap))
            }
        }
    }
    return RModule(relations.toTypedArray(), operations.toTypedArray())
}