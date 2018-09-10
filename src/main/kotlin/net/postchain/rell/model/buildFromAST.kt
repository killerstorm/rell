package net.postchain.rell.model

import net.postchain.rell.parser.*

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
        is S_FunCallExpr -> {
            val fun_ret_type = types["retval ${e.fname}"]!! // TODO: hackish
            RFunCallExpr(fun_ret_type, e.fname, e.args.map { makeExpr(it, types, env) })
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
    val fun_ret_type = types["retval ${s.fname}"]!! // TODO: hackish
    val expr = RFunCallExpr(fun_ret_type, s.fname, s.args.map { makeExpr(it, types, env) })
    return RCallStatement(expr)
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
            "guid" to RGUIDType,
            "tuid" to RTextType,
            "json" to RJSONType,
            "retval json" to RJSONType,
            "retval require" to RUnitType
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