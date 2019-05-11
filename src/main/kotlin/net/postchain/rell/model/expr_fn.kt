package net.postchain.rell.model

import net.postchain.core.Signature
import net.postchain.rell.PostchainUtils
import net.postchain.rell.hexStringToByteArray
import net.postchain.rell.module.GtvToRtContext
import net.postchain.rell.runtime.*
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
        val res = fn.call(frame.entCtx.modCtx, values)
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
    abstract fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value
}

sealed class R_SysFunction_0: R_SysFunction() {
    abstract fun call(): Rt_Value

    override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 0)
        val res = call()
        return res
    }
}

sealed class R_SysFunction_1: R_SysFunction() {
    abstract fun call(arg: Rt_Value): Rt_Value

    override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 1)
        val res = call(args[0])
        return res
    }
}

sealed class R_SysFunction_2: R_SysFunction() {
    abstract fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value

    override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 2)
        val res = call(args[0], args[1])
        return res
    }
}

sealed class R_SysFunction_3: R_SysFunction() {
    abstract fun call(arg1: Rt_Value, arg2: Rt_Value, arg3: Rt_Value): Rt_Value

    override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 3)
        val res = call(args[0], args[1], args[2])
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

    final override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value {
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
    override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 1)
        val a = args[0].asByteArray()
        val opCtx = modCtx.globalCtx.opCtx
        val r = if (opCtx == null) false else opCtx.signers.any { Arrays.equals(it, a) }
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

object R_SysFn_Exists: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val b = arg != Rt_NullValue
        return Rt_BooleanValue(b)
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
            throw Rt_Error("fn:integer.parse:radix:$r", "Invalid radix: $r")
        }
        return parse(a, r.toInt())
    }

    private fun parse(a: Rt_Value, radix: Int): Rt_Value {
        val s = a.asString()
        val r = try {
            java.lang.Long.parseLong(s, radix)
        } catch (e: NumberFormatException) {
            throw Rt_Error("fn:integer.parse:$s", "Invalid number: '$s'")
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
            throw Rt_Error("fn:integer.parse_hex:$s", "Invalid hex number: '$s'")
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
            throw Rt_Error("fn:byte_array.sub:range:$len:$start:$end",
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
            throw Rt_Error("fn:byte_array.new(text):$s", "Invalid byte_array value: '$s'")
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
            if (b < 0 || b > 255) throw Rt_Error("fn:byte_array.new(list):$b", "Byte value out of range: $b")
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
    override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value {
        val buf = StringBuilder()
        for (arg in args) {
            if (!buf.isEmpty()) {
                buf.append(" ")
            }
            buf.append(arg)
        }

        val str = buf.toString()

        val ctx = modCtx.globalCtx
        val printer = if (log) ctx.logPrinter else ctx.stdoutPrinter
        printer.print(str)

        return Rt_UnitValue
    }
}

object R_SysFn_OpContext_LastBlockTime: R_SysFunction() {
    override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 0)
        val opCtx = modCtx.globalCtx.opCtx
        if (opCtx == null) throw Rt_Error("fn:op_context.last_block_time:noop", "Operation context not available")
        return Rt_IntValue(opCtx.lastBlockTime)
    }
}

class R_SysFn_OpContext_Transaction(private val type: R_ClassType): R_SysFunction() {
    override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 0)
        val opCtx = modCtx.globalCtx.opCtx
        if (opCtx == null) throw Rt_Error("fn:op_context.transaction:noop", "Operation context not available")
        return Rt_ClassValue(type, opCtx.transactionIid)
    }
}

object R_SysFn_ChainContext_RawConfig: R_SysFunction() {
    override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 0)
        return Rt_GtvValue(modCtx.globalCtx.chainCtx.rawConfig)
    }
}

object R_SysFn_ChainContext_Args: R_SysFunction() {
    override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 0)
        return modCtx.globalCtx.chainCtx.args
    }
}

object R_SysFn_StrictStr: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val s = arg.toStrictString()
        return Rt_TextValue(s)
    }
}

object R_SysFn_Nop: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        return arg
    }
}

object R_SysFn_Gtv_ToBytes: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val gtv = arg.asGtv()
        val bytes = PostchainUtils.gtvToBytes(gtv)
        return Rt_ByteArrayValue(bytes)
    }
}

object R_SysFn_Gtv_ToJson: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val gtv = arg.asGtv()
        val json = Rt_GtvValue.gtvToJsonString(gtv)
        //TODO consider making a separate function toJSONStr() to avoid unnecessary conversion str -> json -> str.
        return Rt_JsonValue.parse(json)
    }
}

object R_SysFn_Gtv_FromBytes: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val bytes = arg.asByteArray()
        return Rt_Utils.wrapErr("fn:gtv.from_bytes") {
            val gtv = PostchainUtils.bytesToGtv(bytes)
            Rt_GtvValue(gtv)
        }
    }
}

object R_SysFn_Gtv_FromJson_Text: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val str = arg.asString()
        return Rt_Utils.wrapErr("fn:gtv.from_json(text)") {
            val gtv = Rt_GtvValue.jsonStringToGtv(str)
            Rt_GtvValue(gtv)
        }
    }
}

object R_SysFn_Gtv_FromJson_Json: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val str = arg.asJsonString()
        return Rt_Utils.wrapErr("fn:gtv.from_json(json)") {
            val gtv = Rt_GtvValue.jsonStringToGtv(str)
            Rt_GtvValue(gtv)
        }
    }
}

class R_SysFn_Record_ToBytes(val type: R_RecordType): R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val gtv = type.rtToGtv(arg, false)
        val bytes = PostchainUtils.gtvToBytes(gtv)
        return Rt_ByteArrayValue(bytes)
    }
}

class R_SysFn_Record_ToGtv(val type: R_RecordType, val human: Boolean): R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val gtv = type.rtToGtv(arg, human)
        return Rt_GtvValue(gtv)
    }
}

class R_SysFn_Record_FromBytes(val type: R_RecordType): R_SysFunction() {
    override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 1)
        val arg = args[0]
        val bytes = arg.asByteArray()
        return Rt_Utils.wrapErr("fn:record:from_bytes") {
            val gtv = PostchainUtils.bytesToGtv(bytes)
            val convCtx = GtvToRtContext(false)
            val res = type.gtvToRt(convCtx, gtv)
            convCtx.finish(modCtx)
            res
        }
    }
}

class R_SysFn_Record_FromGtv(val type: R_RecordType, val human: Boolean): R_SysFunction() {
    override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 1)
        val arg = args[0]
        val gtv = arg.asGtv()
        return Rt_Utils.wrapErr("fn:record:from_gtv:$human") {
            val convCtx = GtvToRtContext(human)
            val res = type.gtvToRt(convCtx, gtv)
            convCtx.finish(modCtx)
            res
        }
    }
}

class R_SysFn_Enum_Values(private val type: R_EnumType): R_SysFunction_0() {
    private val listType = R_ListType(type)

    override fun call(): Rt_Value {
        val list = ArrayList(type.values())
        return Rt_ListValue(listType, list)
    }
}

class R_SysFn_Enum_Value_Text(private val type: R_EnumType): R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val name = arg.asString()
        val attr = type.attr(name)
        if (attr == null) {
            throw Rt_Error("enum_badname:${type.name}:$name", "Enum '${type.name}' has no value '$name'")
        }
        return Rt_EnumValue(type, attr)
    }
}

class R_SysFn_Enum_Value_Int(private val type: R_EnumType): R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val value = arg.asInteger()
        val attr = type.attr(value)
        if (attr == null) {
            throw Rt_Error("enum_badvalue:${type.name}:$value", "Enum '${type.name}' has no value $value")
        }
        return Rt_EnumValue(type, attr)
    }
}

object R_SysFn_Enum_Name: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val attr = arg.asEnum()
        return Rt_TextValue(attr.name)
    }
}

object R_SysFn_Enum_Value: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val attr = arg.asEnum()
        return Rt_IntValue(attr.value.toLong())
    }
}

class R_SysFn_ThrowCrash(private val msg: String): R_SysFunction_0() {
    override fun call() = throw IllegalStateException(msg)
}

object R_SysFn_VerifySignature: R_SysFunction_3() {
    override fun call(arg1: Rt_Value, arg2: Rt_Value, arg3: Rt_Value): Rt_Value {
        val digest = arg1.asByteArray()
        val res = try {
            val signature = Signature(arg2.asByteArray(), arg3.asByteArray())
            PostchainUtils.cryptoSystem.verifyDigest(digest, signature)
        } catch (e: Exception) {
            throw Rt_Error("verify_signature", e.message ?: "")
        }
        return Rt_BooleanValue(res)
    }
}

class R_SysFn_Any_Hash(val type: R_Type): R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val hash = try {
            val gtv = type.rtToGtv(arg, false)
            PostchainUtils.merkleHash(gtv)
        } catch (e: Exception) {
            throw Rt_Error("hash", e.message ?: "")
        }
        return Rt_ByteArrayValue(hash)
    }
}

class R_SysFn_Any_ToGtv(val type: R_Type, val human: Boolean, val name: String): R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val gtv = try {
            type.rtToGtv(arg, human)
        } catch (e: Exception) {
            throw Rt_Error(name, e.message ?: "")
        }
        return Rt_GtvValue(gtv)
    }
}

class R_SysFn_Any_FromGtv(val type: R_Type, val human: Boolean, val name: String): R_SysFunction() {
    override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 1)
        val gtv = args[0].asGtv()
        val res = try {
            val gtvCtx = GtvToRtContext(human)
            val rt = type.gtvToRt(gtvCtx, gtv)
            gtvCtx.finish(modCtx)
            rt
        } catch (e: Exception) {
            throw Rt_Error(name, e.message ?: "")
        }
        return res
    }
}
