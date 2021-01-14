/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model

import net.postchain.core.Signature
import net.postchain.rell.compiler.C_Constants
import net.postchain.rell.module.GtvToRtContext
import net.postchain.rell.module.RELL_VERSION
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.CommonUtils
import net.postchain.rell.utils.PostchainUtils
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.*

object R_SysFn_Integer {
    abstract class MemFn: R_SysFunction_Generic<Long>() {
        override fun extract(v: Rt_Value) = v.asInteger()
    }

    object Min_Decimal: MemFn() {
        override fun call(obj: Long, a: Rt_Value): Rt_Value {
            val dec = Rt_DecimalValue.of(obj)
            return R_SysFn_Math.Min_Decimal.call(dec, a)
        }
    }

    object Max_Decimal: MemFn() {
        override fun call(obj: Long, a: Rt_Value): Rt_Value {
            val dec = Rt_DecimalValue.of(obj)
            return R_SysFn_Math.Max_Decimal.call(dec, a)
        }
    }

    object ToText: MemFn() {
        override fun call(obj: Long) = Rt_TextValue(obj.toString())

        override fun call(obj: Long, a: Rt_Value): Rt_Value {
            val r = a.asInteger()
            if (r < Character.MIN_RADIX || r > Character.MAX_RADIX) {
                throw Rt_Error("fn_int_str_radix:$r", "Invalid radix: $r")
            }
            val s = obj.toString(r.toInt())
            return Rt_TextValue(s)
        }
    }

    object ToHex: MemFn() {
        override fun call(obj: Long) = Rt_TextValue(java.lang.Long.toHexString(obj))
    }

    object Sign: MemFn() {
        override fun call(obj: Long) = Rt_IntValue(java.lang.Long.signum(obj).toLong())
    }

    object FromText: R_SysFunction_Common() {
        override fun call(obj: Rt_Value): Rt_Value = parse(obj, 10)

        override fun call(a: Rt_Value, b: Rt_Value): Rt_Value {
            val r = b.asInteger()
            if (r < Character.MIN_RADIX || r > Character.MAX_RADIX) {
                throw Rt_Error("fn:integer.from_text:radix:$r", "Invalid radix: $r")
            }
            return parse(a, r.toInt())
        }

        private fun parse(a: Rt_Value, radix: Int): Rt_Value {
            val s = a.asString()
            val r = try {
                java.lang.Long.parseLong(s, radix)
            } catch (e: NumberFormatException) {
                throw Rt_Error("fn:integer.from_text:$s", "Invalid number: '$s'")
            }
            return Rt_IntValue(r)
        }
    }

    object FromHex: R_SysFunction_Common() {
        override fun call(a: Rt_Value): Rt_Value {
            val s = a.asString()
            val r = try {
                java.lang.Long.parseUnsignedLong(s, 16)
            } catch (e: NumberFormatException) {
                throw Rt_Error("fn:integer.from_hex:$s", "Invalid hex number: '$s'")
            }
            return Rt_IntValue(r)
        }
    }
}

object R_SysFn_Decimal {
    abstract class MemFn: R_SysFunction_Generic<BigDecimal>() {
        override fun extract(v: Rt_Value) = v.asDecimal()
    }

    object Ceil: MemFn() {
        override fun call(obj: BigDecimal): Rt_Value {
            val r = obj.setScale(0, RoundingMode.CEILING)
            return Rt_DecimalValue.of(r)
        }
    }

    object Floor: MemFn() {
        override fun call(obj: BigDecimal): Rt_Value {
            val r = obj.setScale(0, RoundingMode.FLOOR)
            return Rt_DecimalValue.of(r)
        }
    }

    object Round: MemFn() {
        override fun call(obj: BigDecimal): Rt_Value {
            val r = obj.setScale(0, RoundingMode.HALF_UP)
            return Rt_DecimalValue.of(r)
        }

        override fun call(obj: BigDecimal, a: Rt_Value): Rt_Value {
            var scale = a.asInteger()
            scale = Math.max(scale, -C_Constants.DECIMAL_INT_DIGITS.toLong())
            scale = Math.min(scale, C_Constants.DECIMAL_FRAC_DIGITS.toLong())
            val r = obj.setScale(scale.toInt(), RoundingMode.HALF_UP)
            return Rt_DecimalValue.of(r)
        }
    }

    object Pow: MemFn() {
        override fun call(obj: BigDecimal, a: Rt_Value): Rt_Value {
            val power = a.asInteger()
            if (power < 0) {
                throw Rt_Error("decimal.pow:negative_power:$power", "Negative power: $power")
            }

            val r = Rt_DecimalUtils.power(obj, power.toInt())
            return Rt_DecimalValue.of(r)
        }
    }

    object Sqrt: MemFn() {
        override fun call(obj: BigDecimal): Rt_Value {
            if (obj < BigDecimal.ZERO) {
                throw Rt_Error("decimal.sqrt:negative:$obj", "Negative value")
            }
            TODO()
        }
    }

    object Sign: MemFn() {
        override fun call(obj: BigDecimal): Rt_Value {
            val r = obj.signum()
            return Rt_IntValue(r.toLong())
        }
    }

    object ToInteger: MemFn() {
        private val BIG_INT_MIN = BigInteger.valueOf(Long.MIN_VALUE)
        private val BIG_INT_MAX = BigInteger.valueOf(Long.MAX_VALUE)

        override fun call(obj: BigDecimal): Rt_Value {
            val bi = obj.toBigInteger()
            if (bi < BIG_INT_MIN || bi > BIG_INT_MAX) {
                val s = obj.round(MathContext(20, RoundingMode.DOWN))
                throw Rt_Error("decimal.to_integer:overflow:$s", "Decimal value is out of integer range: $s")
            }
            val r = bi.toLong()
            return Rt_IntValue(r)
        }
    }

    object ToText: MemFn() {
        override fun call(obj: BigDecimal): Rt_Value {
            val r = Rt_DecimalUtils.toString(obj)
            return Rt_TextValue(r)
        }

        override fun call(obj: BigDecimal, a: Rt_Value): Rt_Value {
            val sci = a.asBoolean()
            val r = if (sci) {
                Rt_DecimalUtils.toSciString(obj)
            } else {
                Rt_DecimalUtils.toString(obj)
            }
            return Rt_TextValue(r)
        }
    }

    object FromInteger: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val i = arg.asInteger()
            val r = Rt_DecimalValue.of(i)
            return r
        }
    }

    object FromText: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val s = arg.asString()
            val r = Rt_DecimalValue.of(s)
            return r
        }
    }
}

object R_SysFn_ByteArray {
    abstract class MemFn: R_SysFunction_Generic<ByteArray>() {
        override fun extract(v: Rt_Value): ByteArray = v.asByteArray()
    }

    object Sha256: MemFn() {
        override fun call(obj: ByteArray): Rt_Value {
            val md = MessageDigest.getInstance("SHA-256")
            return Rt_ByteArrayValue(md.digest(obj))
        }
    }

    object Empty: MemFn() {
        override fun call(obj: ByteArray): Rt_Value = Rt_BooleanValue(obj.isEmpty())
    }

    object Size: MemFn() {
        override fun call(obj: ByteArray): Rt_Value = Rt_IntValue(obj.size.toLong())
    }

    object Sub: MemFn() {
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

    object Decode: MemFn() {
        override fun call(obj: ByteArray): Rt_Value = Rt_TextValue(String(obj))
    }

    object ToList: MemFn() {
        private val type = R_ListType(R_IntegerType)

        override fun call(obj: ByteArray): Rt_Value {
            val list = MutableList<Rt_Value>(obj.size) { Rt_IntValue(obj[it].toLong() and 0xFF) }
            return Rt_ListValue(type, list)
        }
    }

    object ToHex: MemFn() {
        override fun call(obj: ByteArray): Rt_Value = Rt_TextValue(CommonUtils.bytesToHex(obj))
    }

    object ToBase64: MemFn() {
        override fun call(obj: ByteArray): Rt_Value = Rt_TextValue(Base64.getEncoder().encodeToString(obj))
    }

    object FromHex: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val s = arg.asString()
            val bytes = Rt_Utils.wrapErr("fn:byte_array.from_hex") {
                CommonUtils.hexToBytes(s)
            }
            return Rt_ByteArrayValue(bytes)
        }
    }

    object FromBase64: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val s = arg.asString()
            val bytes = Rt_Utils.wrapErr("fn:byte_array.from_base64") {
                Base64.getDecoder().decode(s)
            }
            return Rt_ByteArrayValue(bytes)
        }
    }

    object FromList: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val s = arg.asList()
            val r = ByteArray(s.size)
            for (i in s.indices) {
                val b = s[i].asInteger()
                if (b < 0 || b > 255) throw Rt_Error("fn:byte_array.from_list:$b", "Byte value out of range: $b")
                r[i] = b.toByte()
            }
            return Rt_ByteArrayValue(r)
        }
    }
}

object R_SysFn_Json {
    object FromText: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val a = arg.asString()

            val r = try {
                Rt_JsonValue.parse(a)
            } catch (e: IllegalArgumentException) {
                throw Rt_Error("fn_json_badstr", "Bad JSON: $a")
            }

            return r
        }
    }

    object ToText: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val a = arg.asJsonString()
            return Rt_TextValue(a)
        }
    }
}

object R_SysFn_OpContext {
    abstract class BaseFn(private val name: String): R_SysFunction() {
        abstract fun call(opCtx: Rt_OpContext): Rt_Value

        final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.size == 0)
            val opCtx = ctx.globalCtx.opCtx
            if (opCtx == null) throw Rt_Error("fn:op_context.$name:noop", "Operation context not available")
            return call(opCtx)
        }
    }

    object LastBlockTime: BaseFn("last_block_time") {
        override fun call(opCtx: Rt_OpContext) = Rt_IntValue(opCtx.lastBlockTime)
    }

    class Transaction(private val type: R_EntityType): BaseFn("transaction") {
        override fun call(opCtx: Rt_OpContext) = Rt_EntityValue(type, opCtx.transactionIid)
    }

    object BlockHeight: BaseFn("block_height") {
        override fun call(opCtx: Rt_OpContext) = Rt_IntValue(opCtx.blockHeight)
    }
}

object R_SysFn_ChainContext {
    object RawConfig: R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.size == 0)
            return Rt_GtvValue(ctx.chainCtx.rawConfig)
        }
    }

    object BlockchainRid: R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.size == 0)
            val bcRid = ctx.chainCtx.blockchainRid
            return Rt_ByteArrayValue(bcRid.data)
        }
    }

    class Args(private val moduleName: R_ModuleName): R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.size == 0)
            val res = ctx.chainCtx.args[moduleName]
            return res ?: throw Rt_Error("chain_context.args:no_module_args", "No module args")
        }
    }
}

object R_SysFn_Gtv {
    object ToBytes: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val gtv = arg.asGtv()
            val bytes = PostchainUtils.gtvToBytes(gtv)
            return Rt_ByteArrayValue(bytes)
        }
    }

    object ToJson: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val gtv = arg.asGtv()
            val json = PostchainUtils.gtvToJson(gtv)
            //TODO consider making a separate function toJSONStr() to avoid unnecessary conversion str -> json -> str.
            return Rt_JsonValue.parse(json)
        }
    }

    object FromBytes: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val bytes = arg.asByteArray()
            return Rt_Utils.wrapErr("fn:gtv.from_bytes") {
                val gtv = PostchainUtils.bytesToGtv(bytes)
                Rt_GtvValue(gtv)
            }
        }
    }

    object FromJson_Text: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val str = arg.asString()
            return Rt_Utils.wrapErr("fn:gtv.from_json(text)") {
                val gtv = PostchainUtils.jsonToGtv(str)
                Rt_GtvValue(gtv)
            }
        }
    }

    object FromJson_Json: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val str = arg.asJsonString()
            return Rt_Utils.wrapErr("fn:gtv.from_json(json)") {
                val gtv = PostchainUtils.jsonToGtv(str)
                Rt_GtvValue(gtv)
            }
        }
    }
}

object R_SysFn_Struct {
    class ToBytes(private val struct: R_Struct): R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val gtv = struct.type.rtToGtv(arg, false)
            val bytes = PostchainUtils.gtvToBytes(gtv)
            return Rt_ByteArrayValue(bytes)
        }
    }

    class ToGtv(private val struct: R_Struct, private val pretty: Boolean): R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val gtv = struct.type.rtToGtv(arg, pretty)
            return Rt_GtvValue(gtv)
        }
    }

    class FromBytes(private val struct: R_Struct): R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.size == 1)
            val arg = args[0]
            val bytes = arg.asByteArray()
            return Rt_Utils.wrapErr("fn:struct:from_bytes") {
                val gtv = PostchainUtils.bytesToGtv(bytes)
                val convCtx = GtvToRtContext(false)
                val res = struct.type.gtvToRt(convCtx, gtv)
                convCtx.finish(ctx.exeCtx)
                res
            }
        }
    }

    class FromGtv(private val struct: R_Struct, private val pretty: Boolean): R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.size == 1)
            val arg = args[0]
            val gtv = arg.asGtv()
            return Rt_Utils.wrapErr("fn:struct:from_gtv:$pretty") {
                val convCtx = GtvToRtContext(pretty)
                val res = struct.type.gtvToRt(convCtx, gtv)
                convCtx.finish(ctx.exeCtx)
                res
            }
        }
    }
}

object R_SysFn_Enum {
    class Values(private val enum: R_EnumDefinition): R_SysFunction_0() {
        private val listType = R_ListType(enum.type)

        override fun call(): Rt_Value {
            val list = ArrayList(enum.values())
            return Rt_ListValue(listType, list)
        }
    }

    class Value_Text(private val enum: R_EnumDefinition): R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val name = arg.asString()
            val attr = enum.attr(name)
            if (attr == null) {
                throw Rt_Error("enum_badname:${enum.appLevelName}:$name", "Enum '${enum.simpleName}' has no value '$name'")
            }
            return Rt_EnumValue(enum.type, attr)
        }
    }

    class Value_Int(private val enum: R_EnumDefinition): R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val value = arg.asInteger()
            val attr = enum.attr(value)
            if (attr == null) {
                throw Rt_Error("enum_badvalue:${enum.appLevelName}:$value", "Enum '${enum.simpleName}' has no value $value")
            }
            return Rt_EnumValue(enum.type, attr)
        }
    }

    object Name: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val attr = arg.asEnum()
            return Rt_TextValue(attr.name)
        }
    }

    object Value: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val attr = arg.asEnum()
            return Rt_IntValue(attr.value.toLong())
        }
    }
}

object R_SysFn_Virtual {
    object ToFull: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val virtual = arg.asVirtual()
            val full = virtual.toFull()
            return full
        }
    }

    object Hash: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val virtual = arg.asVirtual()
            val gtv = virtual.gtv
            val hash = Rt_Utils.wrapErr("fn:virtual:hash") {
                PostchainUtils.merkleHash(gtv)
            }
            return Rt_ByteArrayValue(hash)
        }
    }
}

object R_SysFn_Any {
    class Hash(val type: R_Type): R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val hash = Rt_Utils.wrapErr("fn:any:hash") {
                val gtv = type.rtToGtv(arg, false)
                PostchainUtils.merkleHash(gtv)
            }
            return Rt_ByteArrayValue(hash)
        }
    }

    object ToText: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val a = arg.toString()
            return Rt_TextValue(a)
        }
    }

    class ToGtv(val type: R_Type, val pretty: Boolean, val name: String): R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val gtv = try {
                type.rtToGtv(arg, pretty)
            } catch (e: Exception) {
                throw Rt_Error(name, e.message ?: "")
            }
            return Rt_GtvValue(gtv)
        }
    }

    class FromGtv(val type: R_Type, val pretty: Boolean, val name: String): R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.size == 1)
            val gtv = args[0].asGtv()
            val res = try {
                val gtvCtx = GtvToRtContext(pretty)
                val rt = type.gtvToRt(gtvCtx, gtv)
                gtvCtx.finish(ctx.exeCtx)
                rt
            } catch (e: Exception) {
                throw Rt_Error(name, e.message ?: "")
            }
            return res
        }
    }
}

object R_SysFn_General {
    object Unit: R_SysFunction_0() {
        override fun call(): Rt_Value {
            return Rt_UnitValue
        }
    }

    object Range: R_SysFunction_Common() {
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

    class Exists(private val condition: R_RequireCondition, private val not: Boolean): R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val value = condition.calculate(arg)
            val exists = value != null
            val res = if (not) !exists else exists
            return Rt_BooleanValue(res)
        }
    }

    class Print(private val log: Boolean, private val filePos: R_FilePos): R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            val buf = StringBuilder()
            for (arg in args) {
                if (!buf.isEmpty()) {
                    buf.append(" ")
                }
                buf.append(arg)
            }

            val str = buf.toString()

            val printer = if (log) ctx.globalCtx.logPrinter else ctx.globalCtx.outPrinter
            val fullStr = if (log) logStr(ctx, str) else str
            printer.print(fullStr)

            return Rt_UnitValue
        }

        private fun logStr(ctx: Rt_CallContext, str: String): String {
            val pos = R_StackPos(ctx.defCtx.pos, filePos)
            val posStr = "[$pos]"
            return if (str.isEmpty()) posStr else "$posStr $str"
        }
    }

    class Entity_ToStruct(): R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            TODO("Not yet implemented")
        }
    }
}

object R_SysFn_Math {
    object Abs_Integer: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val a = arg.asInteger()
            if (a == Long.MIN_VALUE) {
                throw Rt_Error("abs:integer:overflow:$a", "Integer overflow: $a")
            }
            val r = Math.abs(a)
            return Rt_IntValue(r)
        }
    }

    object Abs_Decimal: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val a = arg.asDecimal()
            val r = a.abs()
            return Rt_DecimalValue.of(r)
        }
    }

    object Min_Integer: R_SysFunction_2() {
        override fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            val a1 = arg1.asInteger()
            val a2 = arg2.asInteger()
            val r = Math.min(a1, a2)
            return Rt_IntValue(r)
        }
    }

    object Min_Decimal: R_SysFunction_2() {
        override fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            val a1 = arg1.asDecimal()
            val a2 = arg2.asDecimal()
            val r = a1.min(a2)
            return Rt_DecimalValue.of(r)
        }
    }

    object Max_Integer: R_SysFunction_2() {
        override fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            val a1 = arg1.asInteger()
            val a2 = arg2.asInteger()
            val r = Math.max(a1, a2)
            return Rt_IntValue(r)
        }
    }

    object Max_Decimal: R_SysFunction_2() {
        override fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            val a1 = arg1.asDecimal()
            val a2 = arg2.asDecimal()
            val r = a1.max(a2)
            return Rt_DecimalValue.of(r)
        }
    }
}

object R_SysFn_Crypto {
    object EthEcRecover: R_SysFunction_4() {
        override fun call(arg1: Rt_Value, arg2: Rt_Value, arg3: Rt_Value, arg4: Rt_Value): Rt_Value {
            val r = arg1.asByteArray()
            val s = arg2.asByteArray()
            val recId = arg3.asInteger()
            val hash = arg4.asByteArray()
            val res = Rt_CryptoUtils.ethereumPubkeyFromSignature(r, s, recId, hash)
            return Rt_ByteArrayValue(res)
        }
    }

    object IsSigner: R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.size == 1)
            val a = args[0].asByteArray()
            val opCtx = ctx.globalCtx.opCtx
            val r = if (opCtx == null) false else opCtx.signers.any { Arrays.equals(it, a) }
            return Rt_BooleanValue(r)
        }
    }

    object Keccak256: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val ba = arg.asByteArray()
            val res = Rt_CryptoUtils.keccak256(ba)
            return Rt_ByteArrayValue(res)
        }
    }

    object VerifySignature: R_SysFunction_3() {
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
}

object R_SysFn_Rell {
    object GetRellVersion: R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.size == 0)
            return Rt_TextValue(RELL_VERSION)
        }
    }

    object GetPostchainVersion: R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.size == 0)
            val ver = ctx.globalCtx.rellVersion()
            val postchainVer = ver.properties.getValue(Rt_RellVersionProperty.POSTCHAIN_VERSION)
            return Rt_TextValue(postchainVer)
        }
    }

    object GetBuild: R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.size == 0)
            val ver = ctx.globalCtx.rellVersion()
            return Rt_TextValue(ver.buildDescriptor)
        }
    }

    object GetBuildDetails: R_SysFunction() {
        val TYPE = R_MapType(R_TextType, R_TextType)

        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.size == 0)
            val ver = ctx.globalCtx.rellVersion()
            return Rt_MapValue(TYPE, ver.rtProperties.toMutableMap())
        }
    }

    object GetAppStructure: R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.size == 0)
            val v = ctx.appCtx.app.toMetaGtv()
            return Rt_GtvValue(v)
        }
    }
}

object R_SysFn_Gtx {
    object Tx {
        object NewEmpty: R_SysFunctionEx_0() {
            override fun call(ctx: Rt_CallContext): Rt_Value {
                val blockchainRid = ctx.globalCtx.chainCtx.blockchainRid
                return Rt_GtxTxValue(blockchainRid, listOf(), listOf(), listOf())
            }
        }

        object NewOneOp: R_SysFunctionEx_1() {
            override fun call(ctx: Rt_CallContext, arg: Rt_Value): Rt_Value {
                val blockchainRid = ctx.globalCtx.chainCtx.blockchainRid
                val ops = listOf(arg.asOperation())
                return Rt_GtxTxValue(blockchainRid, ops, listOf(), listOf())
            }
        }

        object NewListOfOps: R_SysFunctionEx_1() {
            override fun call(ctx: Rt_CallContext, arg: Rt_Value): Rt_Value {
                val blockchainRid = ctx.globalCtx.chainCtx.blockchainRid
                val ops = arg.asList().map { it.asOperation() }
                return Rt_GtxTxValue(blockchainRid, ops, listOf(), listOf())
            }
        }

        object Run: R_SysFunctionEx_1() {
            override fun call(ctx: Rt_CallContext, arg: Rt_Value): Rt_Value {
                val tx = arg.asGtxTx()
                val block = Rt_GtxBlockValue(listOf(tx))
                return Block.Run.call(ctx, block)
            }
        }
    }

    object Block {
        object NewEmpty: R_SysFunction_0() {
            override fun call() = Rt_GtxBlockValue(listOf())
        }

        object NewOneTx: R_SysFunction_1() {
            override fun call(arg: Rt_Value) = Rt_GtxBlockValue(listOf(arg.asGtxTx()))
        }

        object NewListOfTxs: R_SysFunction_1() {
            override fun call(arg: Rt_Value) = Rt_GtxBlockValue(arg.asList().map { it.asGtxTx() })
        }

        object Run: R_SysFunctionEx_1() {
            override fun call(ctx: Rt_CallContext, arg: Rt_Value): Rt_Value {
                if (!ctx.appCtx.repl && !ctx.appCtx.test) {
                    throw Rt_Error("fn:block.run:no_repl_test", "Block can be executed only in REPL or test")
                }
                val block = arg.asGtxBlock()
                PostchainBlockRunner.runBlock(ctx, block)
                return Rt_UnitValue
            }
        }
    }
}

object R_SysFn_Internal {
    object StrictStr: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val s = arg.toStrictString()
            return Rt_TextValue(s)
        }
    }

    object Nop: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            return arg
        }
    }

    object Crash: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val s = arg.asString()
            throw RellInterpreterCrashException(s)
        }
    }

    object IntToRowid: R_SysFunction_1() {
        override fun call(arg: Rt_Value): Rt_Value {
            val i = arg.asInteger()
            return Rt_RowidValue(i)
        }
    }
}
