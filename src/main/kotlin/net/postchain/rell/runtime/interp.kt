package net.postchain.rell.runtime

import net.postchain.rell.model.*

typealias RTEnv = Array<Any>
typealias RTF<T> = (RTEnv) -> T
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
        is RIntegerLiteral -> { _ -> exp.literal }
        else -> throw Exception("Cannot handle expresssion type")
    }
}

fun make_require(em: EnvMap, exp: RFunCallExpr): RTF<Unit> {
    val condition = make_boolean_fun(em, exp.args[0])
    return  {
        e ->
        if (!condition(e)) throw Exception("condition not met")
    }
}

fun make_unit_fun(em: EnvMap, exp: RExpr): RTF<Unit> {
    return when (exp) {
        is RFunCallExpr -> {
            if (exp.fname == "require") {
                make_require(em, exp)
            } else {
                throw Exception("function not supported")
            }
        }
        else -> throw Exception("not supported")

    }
}

fun r_long_eqls(left: RTF<Long>, right: RTF<Long>): RTF<Boolean> {
    return {
        e -> left(e) == right(e)
    }
}

fun make_boolean_fun(em: EnvMap, exp: RExpr): RTF<Boolean> {
    return when (exp) {
        is RBinOpExpr -> {
            if (exp.op == "==") {
                val le = make_long_fun(em, exp.left)
                val re = make_long_fun(em, exp.right)
                r_long_eqls(le, re)
            } else {
                TODO("Not supported")
            }
        }
        else -> TODO("Not supported")
    }
}


fun make_fun(em: EnvMap, exp: RExpr): RTF<Any> {
    return when (exp.type) {
        is RIntegerType -> make_long_fun(em, exp)
        is RUnitType -> make_unit_fun(em, exp)
        is RBooleanType -> make_boolean_fun(em, exp)
        else -> throw Exception("Cannot handle expresssion type")
    }
}

fun make_seq(statements: List<RTF<Unit>>): RTF<Unit> {
    return {
        for (s in statements) s(it)
    }
}

fun silence(rtf: RTF<Any>): RTF<Unit> {
    return { args -> rtf(args); Unit }
}

fun make_statement(em: EnvMap, s: RStatement): RTF<Unit> {
    return when (s) {
        is RCallStatement -> silence(make_fun(em, s.expr))
        else -> throw Exception("Not implemented")
    }
}

fun buildEnvMap(op: ROperation): EnvMap {
    val m = mutableMapOf<String, Int>()
    //m["*conn*"] = 0
    fun add(n: String) {
        m[n] = m.size
    }
    for (p in op.params) add(p.name)
    for (s in op.statements) {
    }
    return { name -> m[name]!!}
}

fun make_operation(op: ROperation): RTF<Unit> {
    val em = buildEnvMap(op)
    val stmts = op.statements.map { make_statement(em, it) }
    return make_seq(stmts)
}

