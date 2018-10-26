package net.postchain.rell.model

import net.postchain.rell.runtime.*
import java.lang.IllegalArgumentException

sealed class RCallExpr(type: RType, val args: List<RExpr>): RExpr(type) {
    abstract fun call(env: RtEnv, values: List<RtValue>): RtValue

    override fun evaluate(env: RtEnv): RtValue {
        val values = args.map { it.evaluate(env) }
        val res = call(env, values)
        return res
    }
}

class RSysCallExpr(type: RType, val fn: RSysFunction, args: List<RExpr>): RCallExpr(type, args) {
    override fun call(env: RtEnv, values: List<RtValue>): RtValue {
        val res = fn.call(env.modCtx.globalCtx, values)
        return res
    }
}

class RUserCallExpr(type: RType, val name: String, val fnKey: Int, args: List<RExpr>): RCallExpr(type, args) {
    override fun call(env: RtEnv, values: List<RtValue>): RtValue {
        val fn = env.modCtx.module.functions[fnKey]
        check(fn.fnKey == fnKey)
        val res = fn.call(env, values)
        return res
    }
}

sealed class RSysFunction {
    abstract fun call(ctx: RtGlobalContext, args: List<RtValue>): RtValue
}

sealed class RSysFunction_Unary: RSysFunction() {
    abstract fun call(arg: RtValue): RtValue

    override fun call(ctx: RtGlobalContext, args: List<RtValue>): RtValue {
        check(args.size == 1)
        val res = call(args[0])
        return res
    }
}

sealed class RSysFunction_Binary: RSysFunction() {
    abstract fun call(arg1: RtValue, arg2: RtValue): RtValue

    override fun call(ctx: RtGlobalContext, args: List<RtValue>): RtValue {
        check(args.size == 2)
        val res = call(args[0], args[1])
        return res
    }
}

object RSysFunction_Unit: RSysFunction() {
    override fun call(ctx: RtGlobalContext, args: List<RtValue>): RtValue {
        check(args.size == 0)
        return RtUnitValue
    }
}

object RSysFunction_Abs: RSysFunction_Unary() {
    override fun call(arg: RtValue): RtValue {
        val a = arg.asInteger()
        val r = Math.abs(a)
        return RtIntValue(r)
    }
}

object RSysFunction_Min: RSysFunction_Binary() {
    override fun call(arg1: RtValue, arg2: RtValue): RtValue {
        val a1 = arg1.asInteger()
        val a2 = arg2.asInteger()
        val r = Math.min(a1, a2)
        return RtIntValue(r)
    }
}

object RSysFunction_Max: RSysFunction_Binary() {
    override fun call(arg1: RtValue, arg2: RtValue): RtValue {
        val a1 = arg1.asInteger()
        val a2 = arg2.asInteger()
        val r = Math.max(a1, a2)
        return RtIntValue(r)
    }
}

object RSysFunction_Json: RSysFunction_Unary() {
    override fun call(arg: RtValue): RtValue {
        val a = arg.asString()

        val r = try { RtJsonValue.parse(a) }
        catch (e: IllegalArgumentException) {
            throw RtError("fn_json_badstr", "Bad JSON: $a")
        }

        return r
    }
}

object RSysFunction_Range: RSysFunction() {
    override fun call(ctx: RtGlobalContext, args: List<RtValue>): RtValue {
        check(args.size == 3)

        val start = args[0].asInteger()
        val end = args[1].asInteger()
        val step = args[2].asInteger()

        if (step == 0L || (step > 0 && start > end) || (step < 0 && start < end)) {
            throw RtError("fn_range_args:$start:$end:$step",
                    "Invalid arguments for range: start = $start, end = $end, step = $step")
        }

        return RtRangeValue(start, end, step)
    }
}

object RSysFunction_Int_Str: RSysFunction_Unary() {
    override fun call(arg: RtValue): RtValue {
        val a = arg.asInteger()
        val r = a.toString()
        return RtTextValue(r)
    }
}

object RSysFunction_Text_Len: RSysFunction_Unary() {
    override fun call(arg: RtValue): RtValue {
        val a = arg.asString()
        val r = a.length.toLong()
        return RtIntValue(r)
    }
}

object RSysFunction_ByteArray_Len: RSysFunction_Unary() {
    override fun call(arg: RtValue): RtValue {
        val a = arg.asByteArray()
        val r = a.size.toLong()
        return RtIntValue(r)
    }
}

object RSysFunction_List_Len: RSysFunction_Unary() {
    override fun call(arg: RtValue): RtValue {
        val a = arg.asList()
        val r = a.size.toLong()
        return RtIntValue(r)
    }
}

object RSysFunction_Json_Str: RSysFunction_Unary() {
    override fun call(arg: RtValue): RtValue {
        val a = arg.asJsonString()
        return RtTextValue(a)
    }
}

object RSysFunction_ToString: RSysFunction_Unary() {
    override fun call(arg: RtValue): RtValue {
        val a = arg.toString()
        return RtTextValue(a)
    }
}

class RSysFunction_Print(val log: Boolean): RSysFunction() {
    override fun call(ctx: RtGlobalContext, args: List<RtValue>): RtValue {
        val buf = StringBuilder()
        for (arg in args) {
            if (!buf.isEmpty()) {
                buf.append(" ")
            }
            buf.append(arg)
        }

        val str = buf.toString()

        val printer = if (log) ctx.logPrinter else ctx.stdoutPrinter
        printer.print(str)

        return RtUnitValue
    }
}
