package net.postchain.rell.model

import net.postchain.gtx.decodeGTXValue
import net.postchain.gtx.encodeGTXValue
import net.postchain.rell.hexStringToByteArray
import net.postchain.rell.module.GtxToRtContext
import net.postchain.rell.runtime.*
import java.lang.IllegalArgumentException
import java.lang.NumberFormatException
import java.util.*

sealed class R_CallExpr(type: R_Type, val args: List<R_Expr>): R_Expr(type) {
    abstract fun call(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value

    override fun evaluate(frame: Rt_CallFrame): Rt_Value {
        val values = args.map { it.evaluate(frame) }
        val res = call(frame, values)
        return res
    }
}

class R_SysCallExpr(type: R_Type, val fn: R_SysFunction, args: List<R_Expr>): R_CallExpr(type, args) {
    override fun call(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
        val res = fn.call(frame.entCtx.modCtx.globalCtx, values)
        return res
    }
}

class R_UserCallExpr(type: R_Type, val name: String, val fnKey: Int, args: List<R_Expr>): R_CallExpr(type, args) {
    override fun call(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
        val fn = frame.entCtx.modCtx.module.functionsTable[fnKey]
        check(fn.fnKey == fnKey)
        val res = fn.call(frame, values)
        return res
    }
}

sealed class R_SysFunction {
    abstract fun call(ctx: Rt_GlobalContext, args: List<Rt_Value>): Rt_Value
}

sealed class R_SysFunction_0: R_SysFunction() {
    abstract fun call(): Rt_Value

    override fun call(ctx: Rt_GlobalContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 0)
        val res = call()
        return res
    }
}

sealed class R_SysFunction_1: R_SysFunction() {
    abstract fun call(arg: Rt_Value): Rt_Value

    override fun call(ctx: Rt_GlobalContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 1)
        val res = call(args[0])
        return res
    }
}

sealed class R_SysFunction_2: R_SysFunction() {
    abstract fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value

    override fun call(ctx: Rt_GlobalContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 2)
        val res = call(args[0], args[1])
        return res
    }
}

abstract class R_SysFunction_Generic<T>: R_SysFunction() {
    abstract fun extract(v: Rt_Value): T

    open fun call(type: R_Type, obj: T): Rt_Value = call(obj)
    open fun call(type: R_Type, obj: T, a: Rt_Value): Rt_Value = call(obj, a)
    open fun call(type: R_Type, obj: T, a: Rt_Value, b: Rt_Value): Rt_Value = call(obj, a, b)

    open fun call(obj: T): Rt_Value = call(obj, listOf())
    open fun call(obj: T, a: Rt_Value): Rt_Value = call(obj, listOf(a))
    open fun call(obj: T, a: Rt_Value, b: Rt_Value): Rt_Value = call(obj, listOf(a, b))

    open fun call(obj: T, args: List<Rt_Value>): Rt_Value = throw errArgCnt(args.size)

    final override fun call(ctx: Rt_GlobalContext, args: List<Rt_Value>): Rt_Value {
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

sealed class R_SysFunction_Common: R_SysFunction_Generic<Rt_Value>() {
    override fun extract(v: Rt_Value): Rt_Value = v
}

object R_SysFn_Unit: R_SysFunction_0() {
    override fun call(): Rt_Value {
        return Rt_UnitValue
    }
}

object R_SysFn_Abs: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val a = arg.asInteger()
        val r = Math.abs(a)
        return Rt_IntValue(r)
    }
}

object R_SysFn_Min: R_SysFunction_2() {
    override fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
        val a1 = arg1.asInteger()
        val a2 = arg2.asInteger()
        val r = Math.min(a1, a2)
        return Rt_IntValue(r)
    }
}

object R_SysFn_Max: R_SysFunction_2() {
    override fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
        val a1 = arg1.asInteger()
        val a2 = arg2.asInteger()
        val r = Math.max(a1, a2)
        return Rt_IntValue(r)
    }
}

object R_SysFn_IsSigner: R_SysFunction() {
    override fun call(ctx: Rt_GlobalContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 1)
        val a = args[0].asByteArray()
        val r = if (ctx.opCtx == null) false else  ctx.opCtx.signers.any { Arrays.equals(it, a) }
        return Rt_BooleanValue(r)
    }
}

object R_SysFn_Json: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val a = arg.asString()

        val r = try { Rt_JsonValue.parse(a) }
        catch (e: IllegalArgumentException) {
            throw Rt_Error("fn_json_badstr", "Bad JSON: $a")
        }

        return r
    }
}

object R_SysFn_Range: R_SysFunction_Common() {
    override fun call(a: Rt_Value): Rt_Value = call0(0, a.asInteger(), 1)
    override fun call(a: Rt_Value, b: Rt_Value): Rt_Value = call0(a.asInteger(), b.asInteger(), 1)
    override fun call(a: Rt_Value, b: Rt_Value, c: Rt_Value): Rt_Value = call0(a.asInteger(), b.asInteger(), c.asInteger())

    private fun call0(start: Long, end: Long, step: Long): Rt_Value {
        if (step == 0L || (step > 0 && start > end) || (step < 0 && start < end)) {
            throw Rt_Error("fn_range_args:$start:$end:$step",
                    "Invalid arguments for range: start = $start, end = $end, step = $step")
        }
        return Rt_RangeValue(start, end, step)
    }
}

object R_SysFn_Int_Str: R_SysFunction_Common() {
    override fun call(a: Rt_Value): Rt_Value = Rt_TextValue(a.asInteger().toString())

    override fun call(a: Rt_Value, b: Rt_Value): Rt_Value {
        val v = a.asInteger()
        val r = b.asInteger()
        if (r < Character.MIN_RADIX || r > Character.MAX_RADIX) {
            throw Rt_Error("fn_int_str_radix:$r", "Invalid radix: $r")
        }
        val s = v.toString(r.toInt())
        return Rt_TextValue(s)
    }
}

object R_SysFn_Int_Hex: R_SysFunction_Common() {
    override fun call(a: Rt_Value): Rt_Value = Rt_TextValue(java.lang.Long.toHexString(a.asInteger()))
}

object R_SysFn_Int_Signum: R_SysFunction_Common() {
    override fun call(a: Rt_Value): Rt_Value = Rt_IntValue(java.lang.Long.signum(a.asInteger()).toLong())
}

object R_SysFn_Int_Parse: R_SysFunction_Common() {
    override fun call(a: Rt_Value): Rt_Value = parse(a, 10)

    override fun call(a: Rt_Value, b: Rt_Value): Rt_Value {
        val r = b.asInteger()
        if (r < Character.MIN_RADIX || r > Character.MAX_RADIX) {
            throw Rt_Error("fn_int_parse_radix:$r", "Invalid radix: $r")
        }
        return parse(a, r.toInt())
    }

    private fun parse(a: Rt_Value, radix: Int): Rt_Value {
        val s = a.asString()
        val r = try {
            java.lang.Long.parseLong(s, radix)
        } catch (e: NumberFormatException) {
            throw Rt_Error("fn_int_parse:$s", "Invalid number: '$s'")
        }
        return Rt_IntValue(r)
    }
}

object R_SysFn_Int_ParseHex: R_SysFunction_Common() {
    override fun call(a: Rt_Value): Rt_Value {
        val s = a.asString()
        val r = try {
            java.lang.Long.parseUnsignedLong(s, 16)
        } catch (e: NumberFormatException) {
            throw Rt_Error("fn_int_parseHex:$s", "Invalid hex number: '$s'")
        }
        return Rt_IntValue(r)
    }
}

sealed class R_SysFn_ByteArray: R_SysFunction_Generic<ByteArray>() {
    override fun extract(v: Rt_Value): ByteArray = v.asByteArray()
}

object R_SysFn_ByteArray_Empty: R_SysFn_ByteArray() {
    override fun call(obj: ByteArray): Rt_Value = Rt_BooleanValue(obj.isEmpty())
}

object R_SysFn_ByteArray_Size: R_SysFn_ByteArray() {
    override fun call(obj: ByteArray): Rt_Value = Rt_IntValue(obj.size.toLong())
}

object R_SysFn_ByteArray_Sub: R_SysFn_ByteArray() {
    override fun call(obj: ByteArray, a: Rt_Value): Rt_Value {
        val start = a.asInteger()
        return call0(obj, start, obj.size.toLong())
    }

    override fun call(obj: ByteArray, a: Rt_Value, b: Rt_Value): Rt_Value {
        val start = a.asInteger()
        val end = b.asInteger()
        return call0(obj, start, end)
    }

    private fun call0(obj: ByteArray, start: Long, end: Long): Rt_Value {
        val len = obj.size
        if (start < 0 || start > len || end < start || end > len) {
            throw Rt_Error("fn_bytearray_sub_range:$len:$start:$end",
                    "Invalid range: start = $start, end = $end (length $len)")
        }
        val r = Arrays.copyOfRange(obj, start.toInt(), end.toInt())
        return Rt_ByteArrayValue(r)
    }
}

object R_SysFn_ByteArray_Decode: R_SysFn_ByteArray() {
    override fun call(obj: ByteArray): Rt_Value = Rt_TextValue(String(obj))
}

object R_SysFn_ByteArray_ToList: R_SysFn_ByteArray() {
    private val type = R_ListType(R_IntegerType)

    override fun call(obj: ByteArray): Rt_Value {
        val list = MutableList<Rt_Value>(obj.size) { Rt_IntValue(obj[it].toLong() and 0xFF) }
        return Rt_ListValue(type, list)
    }
}

object R_SysFn_ByteArray_New_Text: R_SysFunction_Common() {
    override fun call(a: Rt_Value): Rt_Value {
        val s = a.asString()
        val r = try {
            s.hexStringToByteArray()
        } catch (e: IllegalArgumentException) {
            throw Rt_Error("fn_bytearray_new_text:$s", "Invalid byte_array value: '$s'")
        }
        return Rt_ByteArrayValue(r)
    }
}

object R_SysFn_ByteArray_New_List: R_SysFunction_Common() {
    override fun call(a: Rt_Value): Rt_Value {
        val s = a.asList()
        val r = ByteArray(s.size)
        for (i in s.indices) {
            val b = s[i].asInteger()
            if (b < 0 || b > 255) throw Rt_Error("fn_bytearray_new_list:$b", "Byte value out of range: $b")
            r[i] = b.toByte()
        }
        return Rt_ByteArrayValue(r)
    }
}

object R_SysFn_Json_Str: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val a = arg.asJsonString()
        return Rt_TextValue(a)
    }
}

object R_SysFn_ToString: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val a = arg.toString()
        return Rt_TextValue(a)
    }
}

class R_SysFn_Print(val log: Boolean): R_SysFunction() {
    override fun call(ctx: Rt_GlobalContext, args: List<Rt_Value>): Rt_Value {
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

        return Rt_UnitValue
    }
}

object R_SysFn_OpContext_LastBlockTime: R_SysFunction() {
    override fun call(ctx: Rt_GlobalContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 0)
        if (ctx.opCtx == null) throw Rt_Error("fn_last_block_time_noop", "Operation context not available")
        return Rt_IntValue(ctx.opCtx.lastBlockTime)
    }
}

class R_SysFn_OpContext_Transaction(private val type: R_ClassType): R_SysFunction() {
    override fun call(ctx: Rt_GlobalContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 0)
        if (ctx.opCtx == null) throw Rt_Error("fn_opctx_transaction_noop", "Operation context not available")
        return Rt_ClassValue(type, ctx.opCtx.transactionIid)
    }
}

object R_SysFn_ChainContext_RawConfig: R_SysFunction() {
    override fun call(ctx: Rt_GlobalContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 0)
        return Rt_GtxValue(ctx.chainCtx.rawConfig)
    }
}

object R_SysFn_ChainContext_Args: R_SysFunction() {
    override fun call(ctx: Rt_GlobalContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 0)
        return ctx.chainCtx.args
    }
}

object R_SysFn_StrictStr: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val s = arg.toStrictString()
        return Rt_TextValue(s)
    }
}

object R_SysFn_GtxValue_ToBytes: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val gtx = arg.asGtxValue()
        val bytes = encodeGTXValue(gtx)
        return Rt_ByteArrayValue(bytes)
    }
}

object R_SysFn_GtxValue_ToJson: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val gtx = arg.asGtxValue()
        val json = Rt_GtxValue.gtxValueToJsonString(gtx)
        //TODO consider making a separate function toJSONStr() to avoid unnecessary conversion str -> json -> str.
        return Rt_JsonValue.parse(json)
    }
}

object R_SysFn_GtxValue_FromBytes: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val bytes = arg.asByteArray()
        return Rt_Utils.wrapErr("fn:GTXValue.fromBytes") {
            val gtx = decodeGTXValue(bytes)
            Rt_GtxValue(gtx)
        }
    }
}

object R_SysFn_GtxValue_FromJson_Text: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val str = arg.asString()
        return Rt_Utils.wrapErr("fn:GTXValue.fromJSON(text)") {
            val gtx = Rt_GtxValue.jsonStringToGtxValue(str)
            Rt_GtxValue(gtx)
        }
    }
}

object R_SysFn_GtxValue_FromJson_Json: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val str = arg.asJsonString()
        return Rt_Utils.wrapErr("fn:GTXValue.fromJSON(json)") {
            val gtx = Rt_GtxValue.jsonStringToGtxValue(str)
            Rt_GtxValue(gtx)
        }
    }
}

class R_SysFn_Record_ToBytes(val type: R_RecordType): R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val gtx = type.rtToGtx(arg, false)
        val bytes = encodeGTXValue(gtx)
        return Rt_ByteArrayValue(bytes)
    }
}

class R_SysFn_Record_ToGtx(val type: R_RecordType, val human: Boolean): R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val gtx = type.rtToGtx(arg, human)
        return Rt_GtxValue(gtx)
    }
}

class R_SysFn_Record_FromBytes(val type: R_RecordType): R_SysFunction() {
    override fun call(ctx: Rt_GlobalContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 1)
        val arg = args[0]
        val bytes = arg.asByteArray()
        return Rt_Utils.wrapErr("fn:record:fromBytes") {
            val gtx = decodeGTXValue(bytes)
            val convCtx = GtxToRtContext()
            val res = type.gtxToRt(convCtx, gtx, false)
            convCtx.finish(ctx.sqlExec, ctx.sqlMapper)
            res
        }
    }
}

class R_SysFn_Record_FromGtx(val type: R_RecordType, val human: Boolean): R_SysFunction() {
    override fun call(ctx: Rt_GlobalContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 1)
        val arg = args[0]
        val gtx = arg.asGtxValue()
        return Rt_Utils.wrapErr("fn:record:fromGtx") {
            val convCtx = GtxToRtContext()
            val res = type.gtxToRt(convCtx, gtx, human)
            convCtx.finish(ctx.sqlExec, ctx.sqlMapper)
            res
        }
    }
}
