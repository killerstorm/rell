package net.postchain.rell.runtime

import net.postchain.rell.model.*

typealias RTEnv = Array<*>
typealias RTF<T> = (RTEnv) -> T
typealias EnvMap = (n: String) -> List<Int>

class RTGlobalContext (val conn: java.sql.Connection?)

class RtError(val code: String, msg: String): Exception(msg)

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

fun make_seq(statements: List<RTF<Unit>>): RTF<Unit> {
    return {
        for (s in statements) s(it)
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
