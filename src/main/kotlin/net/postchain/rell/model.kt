package net.postchain.rell

import org.jooq.DataType
import org.jooq.SQLDialect
import org.jooq.impl.DefaultDataType
import org.jooq.impl.SQLDataType

sealed class RType(val name: String)

open class RPrimitiveType(name: String,
                          val sqlType: DataType<*>): RType(name)

class RTextType: RPrimitiveType("text", SQLDataType.VARCHAR)
class RIntegerType: RPrimitiveType("integer", SQLDataType.BIGINT)
class RByteArrayType: RPrimitiveType("byte_array", SQLDataType.BINARY)
class RDateType: RPrimitiveType("date", SQLDataType.DATE)
val gtxSignerSQLDataType = DefaultDataType(null as SQLDialect?, ByteArray::class.java, "gtx_signer")
class RSignerType: RPrimitiveType("signer", gtxSignerSQLDataType)

class RInstanceRefType (className: String, val rclass: RClass): RType(className)

class RKey(val attribs: Array<String>)
class RIndex(val attribs: Array<String>)
class RAttrib(val name: String, val type: RType)
open class RRel (val name: String, val keys: Array<RKey>, val indexes: Array<RIndex>, val attributes: Array<RAttrib>)

class RClass (name: String, keys: Array<RKey>, indexes: Array<RIndex>, attributes: Array<RAttrib>)
    :RRel(name, keys, indexes, attributes)

sealed class RExpr
class RVarRef(val _var: RAttrib): RExpr()
class RAtExpr(val rel: RRel, val attr: RAttrib, val varRef: RVarRef): RExpr()

class RAttrExpr(val attr: RAttrib, val expr: RExpr)

sealed class RStatement
class RCreateStatement(val rclass: RClass, val attrs: Array<RAttrExpr>): RStatement()

class ROperation(val name: String, val params: Array<RAttrib>, val statements: Array<RStatement>)

class RModule(val relations: Array<RRel>, val operations: Array<ROperation>)

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

fun makeAtExpr(s: S_AtExpr, types: TypeMap): RAtExpr {
    val type = types[s.clasname]
    if ((type != null) && (type is RInstanceRefType)) {
        val where = s.where[0]
        val attrname = (where.left as S_VarRef).varname
        val varname = (where.right as S_VarRef).varname
        val attr = type.rclass.attributes.first { it.name == attrname }
        return RAtExpr(type.rclass,
                type.rclass.attributes.first { it.name == attrname},
                RVarRef(RAttrib(varname, attr.type))
                )
    } else
        throw Exception("Undefined type ${s.clasname}")
}

fun makeCreateStatmenet(s: S_CreateStatement, types: TypeMap): RCreateStatement {
    val type = types[s.classname]
    if ((type != null) && (type is RInstanceRefType)) {
        return RCreateStatement(type.rclass, s.attrs.map {
            s_attr ->
            when (s_attr) {
                is S_AtExpr -> {
                    val atExpr = makeAtExpr(s_attr, types)
                    RAttrExpr(atExpr.attr, atExpr)
                }
                is S_VarRef -> {
                    val varName = s_attr.varname
                    val attr = type.rclass.attributes.first { it.name == varName}
                    RAttrExpr(attr, RVarRef(attr))
                }
                else -> throw Exception("Not supported")
            }
        }.toTypedArray()
        )
    } else
        throw Exception("Undefined type ${s.classname}")
}

fun makeROperation(opDef: S_OpDefinition, types: TypeMap): ROperation {
    return ROperation(opDef.identifier,
            opDef.args.map { makeRAttrib(it, types) }.toTypedArray(),
            opDef.statements.map {
                when (it) {
                    is S_CreateStatement -> makeCreateStatmenet(it, types)
                    else -> throw Exception("Only create statement works so far")
                }
            }.toTypedArray()
    )
}

fun makeModule(md: S_ModuleDefinition): RModule {
    val typeMap = mutableMapOf<String, RType>(
            "text" to RTextType(),
            "byte_array" to RByteArrayType(),
            "integer" to RIntegerType(),
            "pubkey" to RByteArrayType(),
            "name" to RTextType(),
            "date" to RDateType(),
            "signer" to RSignerType()
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