package net.postchain.rell.rt

import net.postchain.rell.*

typealias Args = Array<Any>
typealias RTF<T> = (Args) -> T
typealias EnvMap = (n: String) -> Int

fun r_add_long(a: RTF<Long>, b: RTF<Long>): RTF<Long> {
    return { a(it) + b(it) }
}

fun make_long_bin_op(em: EnvMap, exp: RBinOpExpr): RTF<Long> {
    return when (exp.op) {
        "+" -> r_add_long(make_long_fun(em, exp.left), make_long_fun(em, exp.right))
        else -> throw Exception("Cannot handle binop ${exp.op}")
    }
}

fun <T>make_var_ref(idx: Int): RTF<T> {
    return { env -> (env[idx] as T)}
}

fun make_long_fun(em: EnvMap, exp: RExpr): RTF<Long> {
    return when (exp) {
        is RBinOpExpr -> make_long_bin_op(em, exp)
        is RVarRef -> make_var_ref(em(exp._var.name))
        else -> throw Exception("Cannot handle expresssion type")
    }
}

fun make_fun(em: EnvMap, exp: RExpr): RTF<Any> {
    return when (exp.type) {
        is RIntegerType -> make_long_fun(em, exp)
        else -> throw Exception("Cannot handle expresssion type")
    }
}

fun make_seq(statements: Array<RTF<Unit>>): RTF<Unit> {
    return {
        for (s in statements) s(it)
    }
}
/*
fun make_statement(em: EnvMap, s: RStatement): RTF<Unit> {
    return when (s) {

    }
}*/