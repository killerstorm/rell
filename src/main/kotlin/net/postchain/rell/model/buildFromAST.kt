package net.postchain.rell.model

import net.postchain.rell.parser.*

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

fun makeModule(md: S_ModuleDefinition): RModule {
    return md.compile()
}
