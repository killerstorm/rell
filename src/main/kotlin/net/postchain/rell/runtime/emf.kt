package net.postchain.rell.runtime

import net.postchain.rell.model.RIntegerType
import net.postchain.rell.model.RType

data class TypedRTF(val type: RType, val rtf: RTF<*>)

typealias FunctionResolver = (args: List<TypedRTF>) -> TypedRTF

fun add (args: List<TypedRTF>): TypedRTF {
    if (args.size != 2) throw Exception("Wrong number of arguments to add function")
    if (args[0].type != RIntegerType) throw Exception("Wrong number of arguments to add function")
    if (args[1].type != RIntegerType) throw Exception("Wrong number of arguments to add function")

    val l = args[0].rtf as RTF<Long>
    val r = args[1].rtf as RTF<Long>

    return TypedRTF(RIntegerType, { env -> l(env) + r(env)})
}

val standardFunction = mapOf<String, FunctionResolver>(
        "+" to ::add
)
