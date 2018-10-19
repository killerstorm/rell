package net.postchain.rell.model

import net.postchain.rell.runtime.*
import java.lang.IllegalArgumentException

class RCallExpr(type: RType, val fn: RSysFunction, val args: List<RExpr>): RExpr(type) {
    override fun evaluate(env: RtEnv): RtValue {
        val values = args.map { it.evaluate(env) }
        val res = fn.call(values)
        return res
    }
}

sealed class RSysFunction {
    abstract fun call(args: List<RtValue>): RtValue
}

object RSysFunction_Abs: RSysFunction() {
    override fun call(args: List<RtValue>): RtValue {
        check(args.size == 1)
        val a = args[0].asInteger()
        val r = Math.abs(a)
        return RtIntValue(r)
    }
}

object RSysFunction_Min: RSysFunction() {
    override fun call(args: List<RtValue>): RtValue {
        check(args.size == 2)
        val a1 = args[0].asInteger()
        val a2 = args[1].asInteger()
        val r = Math.min(a1, a2)
        return RtIntValue(r)
    }
}

object RSysFunction_Max: RSysFunction() {
    override fun call(args: List<RtValue>): RtValue {
        check(args.size == 2)
        val a1 = args[0].asInteger()
        val a2 = args[1].asInteger()
        val r = Math.max(a1, a2)
        return RtIntValue(r)
    }
}

object RSysFunction_Json: RSysFunction() {
    override fun call(args: List<RtValue>): RtValue {
        check(args.size == 1)
        val a = args[0].asString()

        val r = try { RtJsonValue.parse(a) }
        catch (e: IllegalArgumentException) {
            throw RtError("fn_json_badstr", "Bad JSON: $a")
        }

        return r
    }
}

object RSysFunction_Int_Str: RSysFunction() {
    override fun call(args: List<RtValue>): RtValue {
        check(args.size == 1)
        val a = args[0].asInteger()
        val r = a.toString()
        return RtTextValue(r)
    }
}

object RSysFunction_Text_Len: RSysFunction() {
    override fun call(args: List<RtValue>): RtValue {
        check(args.size == 1)
        val a = args[0].asString()
        val r = a.length.toLong()
        return RtIntValue(r)
    }
}

object RSysFunction_ByteArray_Len: RSysFunction() {
    override fun call(args: List<RtValue>): RtValue {
        check(args.size == 1)
        val a = args[0].asByteArray()
        val r = a.size.toLong()
        return RtIntValue(r)
    }
}

object RSysFunction_List_Len: RSysFunction() {
    override fun call(args: List<RtValue>): RtValue {
        check(args.size == 1)
        val a = args[0].asList()
        val r = a.size.toLong()
        return RtIntValue(r)
    }
}

object RSysFunction_Json_Str: RSysFunction() {
    override fun call(args: List<RtValue>): RtValue {
        check(args.size == 1)
        val a = args[0].asJsonString()
        return RtTextValue(a)
    }
}
