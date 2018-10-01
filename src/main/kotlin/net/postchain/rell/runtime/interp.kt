package net.postchain.rell.runtime

import net.postchain.rell.model.*

typealias RTEnv = Array<*>
typealias RTF<T> = (RTEnv) -> T
typealias EnvMap = (n: String) -> List<Int>

class RTGlobalContext (val conn: java.sql.Connection?)


fun r_add_long(a: RTF<Long>, b: RTF<Long>): RTF<Long> {
    return { a(it) + b(it) }
}

fun make_long_bin_op(em: EnvMap, exp: RBinOpExpr): RTF<Long> {
    return when (exp.op) {
        "+" -> r_add_long(make_long_fun(em, exp.left), make_long_fun(em, exp.right))
        else -> throw Exception("Cannot handle binop ${exp.op}")
    }
}

fun <T>make_var_ref(em: EnvMap, name: String): RTF<T> {
    val indexes = em(name)
    return when (indexes.size) {
        1 -> {
            val idx = indexes[0]
            { env -> env[idx] as T}
        }
        2 -> {
            val idx1 = indexes[0]
            val idx2 = indexes[1]
            { env ->
                val env2 = env[idx1] as RTEnv
                env2[idx2] as T
            }
        }
        else -> {
            env ->
            var x : Any? = env
            for (i in 0 until indexes.size) {
                x = (x as RTEnv)[i]
            }
            x as T
        }
    }
}

fun make_long_fun(em: EnvMap, exp: RExpr): RTF<Long> {
    return when (exp) {
        is RBinOpExpr -> make_long_bin_op(em, exp)
        is RVarExpr -> make_var_ref(em, exp.attr.name)
        is RIntegerLiteralExpr -> { _ -> exp.value.value }
        is RFuncall -> make_funcall(em, exp) as RTF<Long>
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
        is RClosureType -> make_closure(em, exp as RLambdaExpr)
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

fun buildEnvMap(op: ROperation): Pair<EnvMap, Int> {
    val m = mutableMapOf<String, Int>()
    m["*global*"] = 0
    fun add(n: String) {
        m[n] = m.size
    }
    for (p in op.params) add(p.name)
    // TODO: collect variable bindings
    for (s in op.statements) {
    }
    return Pair({ name -> listOf(m[name]!!)}, m.size)
}

fun make_operation_rtf(em: EnvMap, op: ROperation): RTF<Unit> {
    val stmts = op.statements.map { make_statement(em, it) }
    return make_seq(stmts)
}

class RTOperation(val opModel: ROperation,
                  val envSize: Int,
                  val nArgs: Int,
                  val rtf: RTF<Unit>) {
    fun call(context: RTGlobalContext, args: Array<Any?>) {
        val env = arrayOfNulls<Any>(envSize)
        env[0] = context
        assert(nArgs == args.size)
        for (i in 0 until nArgs) {
            env[1 + i] = args[i]
        }
        rtf(env)
    }
}

fun make_operation(op: ROperation): RTOperation {
    val envMap = buildEnvMap(op)
    val rtf = make_operation_rtf(envMap.first, op)
    return RTOperation(
            op,
            envMap.second,
            op.params.size,
            rtf
    )
}
