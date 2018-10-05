package net.postchain.rell.runtime

import net.postchain.rell.model.RFuncall
import net.postchain.rell.model.RLambdaExpr

class RTClosure (val parentEnv: RTEnv, val argCount: Int, val rtf: RTF<Any>) {
    fun call(args: List<Any?>): Any {
        assert(args.size == argCount)
        val env = arrayOfNulls<Any>(argCount + 2)
        env[0] = parentEnv[0] // copy GlobalContext
        env[1] = parentEnv
        for (i in 0 until argCount) {
            env[i + 2] = args[i]
        }
        return rtf(env)
    }
}

fun make_closure(parentEM: EnvMap, lambda: RLambdaExpr): RTF<RTClosure> {
    val closureOwnEM = mutableMapOf<String, Int>()
    closureOwnEM["*global*"] = 0
    closureOwnEM["*parentEnv*"] = 1
    lambda.args.forEach( { closureOwnEM[it.name] = closureOwnEM.size })
    val closureEM = {
        name: String ->
        if (name in closureOwnEM)
            listOf(closureOwnEM[name]!!)
        else {
            listOf(0) + parentEM(name)
        }
    }
    TODO()
}
