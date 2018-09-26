package net.postchain.rell.runtime

import net.postchain.rell.model.RFuncall
import net.postchain.rell.model.RLambda

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

fun make_closure(parentEM: EnvMap, lambda: RLambda): RTF<RTClosure> {
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
    val expr = make_fun(closureEM, lambda.expr)
    return {
        env -> RTClosure(env, lambda.args.size, expr)
    }
}

fun make_funcall(em: EnvMap, funcall: RFuncall): RTF<Any> {
    val closureRTF = make_closure(em, funcall.lambdaExpr)
    val argsRTFs = funcall.args.map { make_fun(em, it) }
    return {
        env ->
        val closure = closureRTF(env)
        val evaluatedArgs = argsRTFs.map { it(env) }
        closure.call(evaluatedArgs)
    }
}