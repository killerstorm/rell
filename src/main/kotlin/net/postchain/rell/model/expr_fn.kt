package net.postchain.rell.model

import net.postchain.rell.hexStringToByteArray
import net.postchain.rell.runtime.*
import java.lang.IllegalArgumentException
import java.lang.NumberFormatException
import java.util.*

sealed class RCallExpr(type: RType, val args: List<RExpr>): RExpr(type) {
    abstract fun call(frame: RtCallFrame, values: List<RtValue>): RtValue

    override fun evaluate(frame: RtCallFrame): RtValue {
        val values = args.map { it.evaluate(frame) }
        val res = call(frame, values)
        return res
    }
}

class RSysCallExpr(type: RType, val fn: RSysFunction, args: List<RExpr>): RCallExpr(type, args) {
    override fun call(frame: RtCallFrame, values: List<RtValue>): RtValue {
        val res = fn.call(frame.entCtx.modCtx.globalCtx, values)
        return res
    }
}

class RUserCallExpr(type: RType, val name: String, val fnKey: Int, args: List<RExpr>): RCallExpr(type, args) {
    override fun call(frame: RtCallFrame, values: List<RtValue>): RtValue {
        val fn = frame.entCtx.modCtx.module.functionsTable[fnKey]
        check(fn.fnKey == fnKey)
        val res = fn.call(frame, values)
        return res
    }
}

sealed class RSysFunction {
    abstract fun call(ctx: RtGlobalContext, args: List<RtValue>): RtValue
}

sealed class RSysFunction_1: RSysFunction() {
    abstract fun call(arg: RtValue): RtValue

    override fun call(ctx: RtGlobalContext, args: List<RtValue>): RtValue {
        check(args.size == 1)
        val res = call(args[0])
        return res
    }
}

sealed class RSysFunction_2: RSysFunction() {
    abstract fun call(arg1: RtValue, arg2: RtValue): RtValue

    override fun call(ctx: RtGlobalContext, args: List<RtValue>): RtValue {
        check(args.size == 2)
        val res = call(args[0], args[1])
        return res
    }
}

abstract class RSysFunction_Generic<T>: RSysFunction() {
    abstract fun extract(v: RtValue): T

    open fun call(type: RType, obj: T): RtValue = call(obj)
    open fun call(type: RType, obj: T, a: RtValue): RtValue = call(obj, a)
    open fun call(type: RType, obj: T, a: RtValue, b: RtValue): RtValue = call(obj, a, b)

    open fun call(obj: T): RtValue = call(obj, listOf())
    open fun call(obj: T, a: RtValue): RtValue = call(obj, listOf(a))
    open fun call(obj: T, a: RtValue, b: RtValue): RtValue = call(obj, listOf(a, b))

    open fun call(obj: T, args: List<RtValue>): RtValue = throw errArgCnt(args.size)

    final override fun call(ctx: RtGlobalContext, args: List<RtValue>): RtValue {
        check(args.size >= 1)

        val objVal = args[0]
        val type = objVal.type()
        val obj = extract(objVal)

        if (args.size == 1) {
            return call(type, obj)
        } else if (args.size == 2) {
            return call(type, obj, args[1])
        } else if (args.size == 3) {
            return call(type, obj, args[1], args[2])
        } else {
            throw errArgCnt(args.size)
        }
    }

    private fun errArgCnt(n: Int) = IllegalStateException("Wrong number of arguments for ${javaClass.simpleName}: $n")
}

sealed class RSysFunction_Common: RSysFunction_Generic<RtValue>() {
    override fun extract(v: RtValue): RtValue = v
}

object RSysFunction_Unit: RSysFunction() {
    override fun call(ctx: RtGlobalContext, args: List<RtValue>): RtValue {
        check(args.size == 0)
        return RtUnitValue
    }
}

object RSysFunction_Abs: RSysFunction_1() {
    override fun call(arg: RtValue): RtValue {
        val a = arg.asInteger()
        val r = Math.abs(a)
        return RtIntValue(r)
    }
}

object RSysFunction_Min: RSysFunction_2() {
    override fun call(arg1: RtValue, arg2: RtValue): RtValue {
        val a1 = arg1.asInteger()
        val a2 = arg2.asInteger()
        val r = Math.min(a1, a2)
        return RtIntValue(r)
    }
}

object RSysFunction_Max: RSysFunction_2() {
    override fun call(arg1: RtValue, arg2: RtValue): RtValue {
        val a1 = arg1.asInteger()
        val a2 = arg2.asInteger()
        val r = Math.max(a1, a2)
        return RtIntValue(r)
    }
}

object RSysFunction_IsSigner: RSysFunction() {
    override fun call(ctx: RtGlobalContext, args: List<RtValue>): RtValue {
        check(args.size == 1)
        val a = args[0].asByteArray()
        val r = if (ctx.opCtx == null) false else  ctx.opCtx.signers.any { Arrays.equals(it, a) }
        return RtBooleanValue(r)
    }
}

object RSysFunction_Json: RSysFunction_1() {
    override fun call(arg: RtValue): RtValue {
        val a = arg.asString()

        val r = try { RtJsonValue.parse(a) }
        catch (e: IllegalArgumentException) {
            throw RtError("fn_json_badstr", "Bad JSON: $a")
        }

        return r
    }
}

object RSysFunction_Range: RSysFunction_Common() {
    override fun call(a: RtValue): RtValue = call0(0, a.asInteger(), 1)
    override fun call(a: RtValue, b: RtValue): RtValue = call0(a.asInteger(), b.asInteger(), 1)
    override fun call(a: RtValue, b: RtValue, c: RtValue): RtValue = call0(a.asInteger(), b.asInteger(), c.asInteger())

    private fun call0(start: Long, end: Long, step: Long): RtValue {
        if (step == 0L || (step > 0 && start > end) || (step < 0 && start < end)) {
            throw RtError("fn_range_args:$start:$end:$step",
                    "Invalid arguments for range: start = $start, end = $end, step = $step")
        }
        return RtRangeValue(start, end, step)
    }
}

object RSysFunction_Int_Str: RSysFunction_Common() {
    override fun call(a: RtValue): RtValue = RtTextValue(a.asInteger().toString())

    override fun call(a: RtValue, b: RtValue): RtValue {
        val v = a.asInteger()
        val r = b.asInteger()
        if (r < Character.MIN_RADIX || r > Character.MAX_RADIX) {
            throw RtError("fn_int_str_radix:$r", "Invalid radix: $r")
        }
        val s = v.toString(r.toInt())
        return RtTextValue(s)
    }
}

object RSysFunction_Int_Hex: RSysFunction_Common() {
    override fun call(a: RtValue): RtValue = RtTextValue(java.lang.Long.toHexString(a.asInteger()))
}

object RSysFunction_Int_Signum: RSysFunction_Common() {
    override fun call(a: RtValue): RtValue = RtIntValue(java.lang.Long.signum(a.asInteger()).toLong())
}

object RSysFunction_Int_Parse: RSysFunction_Common() {
    override fun call(a: RtValue): RtValue = parse(a, 10)

    override fun call(a: RtValue, b: RtValue): RtValue {
        val r = b.asInteger()
        if (r < Character.MIN_RADIX || r > Character.MAX_RADIX) {
            throw RtError("fn_int_parse_radix:$r", "Invalid radix: $r")
        }
        return parse(a, r.toInt())
    }

    private fun parse(a: RtValue, radix: Int): RtValue {
        val s = a.asString()
        val r = try {
            java.lang.Long.parseLong(s, radix)
        } catch (e: NumberFormatException) {
            throw RtError("fn_int_parse:$s", "Invalid number: '$s'")
        }
        return RtIntValue(r)
    }
}

object RSysFunction_Int_ParseHex: RSysFunction_Common() {
    override fun call(a: RtValue): RtValue {
        val s = a.asString()
        val r = try {
            java.lang.Long.parseUnsignedLong(s, 16)
        } catch (e: NumberFormatException) {
            throw RtError("fn_int_parseHex:$s", "Invalid hex number: '$s'")
        }
        return RtIntValue(r)
    }
}

sealed class RSysFunction_ByteArray: RSysFunction_Generic<ByteArray>() {
    override fun extract(v: RtValue): ByteArray = v.asByteArray()
}

object RSysFunction_ByteArray_Empty: RSysFunction_ByteArray() {
    override fun call(obj: ByteArray): RtValue = RtBooleanValue(obj.isEmpty())
}

object RSysFunction_ByteArray_Size: RSysFunction_ByteArray() {
    override fun call(obj: ByteArray): RtValue = RtIntValue(obj.size.toLong())
}

object RSysFunction_ByteArray_Sub: RSysFunction_ByteArray() {
    override fun call(obj: ByteArray, a: RtValue): RtValue {
        val start = a.asInteger()
        return call0(obj, start, obj.size.toLong())
    }

    override fun call(obj: ByteArray, a: RtValue, b: RtValue): RtValue {
        val start = a.asInteger()
        val end = b.asInteger()
        return call0(obj, start, end)
    }

    private fun call0(obj: ByteArray, start: Long, end: Long): RtValue {
        val len = obj.size
        if (start < 0 || start > len || end < start || end > len) {
            throw RtError("fn_bytearray_sub_range:$len:$start:$end",
                    "Invalid range: start = $start, end = $end (length $len)")
        }
        val r = Arrays.copyOfRange(obj, start.toInt(), end.toInt())
        return RtByteArrayValue(r)
    }
}

object RSysFunction_ByteArray_Decode: RSysFunction_ByteArray() {
    override fun call(obj: ByteArray): RtValue = RtTextValue(String(obj))
}

object RSysFunction_ByteArray_ToList: RSysFunction_ByteArray() {
    private val type = RListType(RIntegerType)

    override fun call(obj: ByteArray): RtValue {
        val list = MutableList<RtValue>(obj.size) { RtIntValue(obj[it].toLong() and 0xFF) }
        return RtListValue(type, list)
    }
}

object RSysFunction_ByteArray_New_Text: RSysFunction_Common() {
    override fun call(a: RtValue): RtValue {
        val s = a.asString()
        val r = try {
            s.hexStringToByteArray()
        } catch (e: IllegalArgumentException) {
            throw RtError("fn_bytearray_new_text:$s", "Invalid byte_array value: '$s'")
        }
        return RtByteArrayValue(r)
    }
}

object RSysFunction_ByteArray_New_List: RSysFunction_Common() {
    override fun call(a: RtValue): RtValue {
        val s = a.asList()
        val r = ByteArray(s.size)
        for (i in s.indices) {
            val b = s[i].asInteger()
            if (b < 0 || b > 255) throw RtError("fn_bytearray_new_list:$b", "Byte value out of range: $b")
            r[i] = b.toByte()
        }
        return RtByteArrayValue(r)
    }
}

object RSysFunction_Json_Str: RSysFunction_1() {
    override fun call(arg: RtValue): RtValue {
        val a = arg.asJsonString()
        return RtTextValue(a)
    }
}

object RSysFunction_ToString: RSysFunction_1() {
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

object RSysFunction_LastBlockTime: RSysFunction() {
    override fun call(ctx: RtGlobalContext, args: List<RtValue>): RtValue {
        check(args.size == 0)
        if (ctx.opCtx == null) throw RtError("fn_last_block_time_noop", "Operation context not available")
        return RtIntValue(ctx.opCtx.lastBlockTime)
    }
}
