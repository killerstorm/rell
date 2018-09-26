package net.postchain.rell.model

import net.postchain.rell.parser.*

typealias EnvMap = Map<String, RAttrib>
typealias TypeMap = Map<String, RType>

private fun makeRAttrib(a: S_Attribute, types: TypeMap): RAttrib {
    val type = types[a.type]
    if (type != null)
        return RAttrib(a.name, types[a.type]!!)
    else
        throw Exception("Undefined type ${a.type}")
}

private fun makeRClass(sClass: S_ClassDefinition, types: TypeMap): RClass {
    val attrs = sClass.attributes.map { makeRAttrib(it, types) }
    val keys = sClass.keys.map { RKey(it.attrNames.toTypedArray()) }
    val indexes = sClass.indices.map { RIndex(it.attrNames.toTypedArray()) }
    return RClass(sClass.identifier, keys.toTypedArray(), indexes.toTypedArray(), attrs.toTypedArray())
}

//private fun makeAtExpr(s: S_AtExpr, types: TypeMap, env: EnvMap): RAtExpr {
//    val type = types[s.className]
//    if ((type != null) && (type is RInstanceRefType)) {
//        val conditions = s.where.map {
//            if (it.op != "=") throw Exception("Only = is supported in AtExpr")
//            if (! (it.left is S_VarRef)) throw Exception("AtExpr: LHS must be varrref")
//            val expr = makeExpr(it.right, types, env)
//            val attrname = it.left.name
//            val attr = type.rclass.attributes.find { it.name == attrname}
//            if (attr == null) throw Exception("${type.rclass.name} does not have attribute ${attrname}")
//            if (expr.type != attr.type) {
//                println("Warning: type mismatch")
//            }
//            Pair(attr, expr)
//        }
//        return RAtExpr(type, type.rclass, conditions)
//    } else
//        throw Exception("Undefined type ${s.className}")
//}

//private fun makeExpr(e: S_Expression, types: TypeMap, env: EnvMap): RExpr {
//    return when (e) {
//        is S_AtExpr -> makeAtExpr(e, types, env)
//        is S_VarRef -> {
//            val attr = env[e.name]!!
//            RVarRef(attr.type, 0, attr)
//        }
//        is S_FunCallExpr -> {
//            val fun_ret_type = types["retval ${e.fname}"]!! // TODO: hackish
//            RFunCallExpr(fun_ret_type, e.fname, e.args.map { makeExpr(it, types, env) })
//        }
//        is S_BinOp -> {
//            // TODO: actual type
//            RBinOpExpr(RBooleanType, e.op, makeExpr(e.left, types, env), makeExpr(e.right, types, env))
//        }
//        is S_StringLiteral -> RStringLiteral(RTextType, e.literal)
//        is S_ByteALiteral -> RByteALiteral(RByteArrayType, e.bytes)
//        is S_IntLiteral -> RIntegerLiteral(RIntegerType, e.value)
//    }
//}

//private fun makeAttrExpr(s_attr_expr: S_AttrExpr, rclass: RClass, types: TypeMap, env: EnvMap): RAttrExpr {
//    val expr = makeExpr(s_attr_expr.expr, types, env)
//    val attrname = s_attr_expr.name
//    val class_attr = rclass.attributes.find { it.name == attrname }
//    if (class_attr == null)
//        throw Exception("${rclass.name} does not have attribute ${attrname}")
//    if (expr.type != class_attr.type) {
//        println("Warning: type mismatch")
//    }
//    return RAttrExpr(class_attr, expr)
//}

//private fun makeCreateStatmenet(s: S_CreateStatement, types: TypeMap, env: EnvMap): RCreateStatement {
//    val type = types[s.classname]
//    if ((type != null) && (type is RInstanceRefType)) {
//        return RCreateStatement(type.rclass, s.attrs.map {
//            makeAttrExpr(it, type.rclass, types, env)
//        }.toTypedArray())
//    } else
//        throw Exception("Undefined type ${s.classname}")
//}
//
//private fun makeUpdateStatement(s: S_UpdateStatement, types: TypeMap, env: EnvMap): RUpdateStatement {
//    val type = types[s.what.className]
//    if ((type != null) && (type is RInstanceRefType)) {
//        return RUpdateStatement(
//                makeAtExpr(s.what, types, env),
//                s.attrs.map {
//                    makeAttrExpr(it, type.rclass, types, env)
//                }.toTypedArray()
//        )
//    } else
//        throw Exception("Undefined type ${s.what.className}")
//}
//
//private fun makeDeleteStatement(s: S_DeleteStatement, types: TypeMap, env: EnvMap): RDeleteStatement {
//    val type = types[s.what.className]
//    if ((type != null) && (type is RInstanceRefType)) {
//        return RDeleteStatement(makeAtExpr(s.what, types, env))
//    } else
//        throw Exception("Undefined type ${s.what.className}")
//}

//private fun makeCallStatement(s: S_CallStatement, types: TypeMap, env: EnvMap): RStatement {
//    val fun_ret_type = types["retval ${s.fname}"]!! // TODO: hackish
//    val expr = RFunCallExpr(fun_ret_type, s.fname, s.args.map { makeExpr(it, types, env) })
//    return RCallStatement(expr)
//}

//private fun makeStatement(stmt: S_Statement, types: TypeMap, env: EnvMap): RStatement {
//    return when (stmt) {
//        is S_CreateStatement -> makeCreateStatmenet(stmt, types, env)
//        is S_CallStatement -> makeCallStatement(stmt, types, env)
//        is S_DeleteStatement -> makeDeleteStatement(stmt, types, env)
//        is S_UpdateStatement -> makeUpdateStatement(stmt, types, env)
//        else -> throw Exception("Statement not supported (yet): ${stmt.javaClass.simpleName}")
//    }
//}

//private fun makeROperation(opDef: S_OpDefinition, types: TypeMap): ROperation {
//    val args = opDef.args.map { makeRAttrib(it, types) }
//    val env = args.associate { it.name to it }
//    return ROperation(
//            opDef.identifier,
//            args.toTypedArray(),
//            opDef.statements.map { makeStatement(it, types, env) }.toTypedArray()
//    )
//}

fun makeModule(md: S_ModuleDefinition): RModule {
    return md.compile()
}
