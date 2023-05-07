/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.base.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.base.compiler.base.utils.C_LibUtils.depError
import net.postchain.rell.base.compiler.base.utils.C_MemberFuncBuilder
import net.postchain.rell.base.compiler.base.utils.C_SysFunction
import net.postchain.rell.base.lib.C_Lib_Crypto
import net.postchain.rell.base.model.*
import net.postchain.rell.base.model.expr.Db_SysFunction
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.runtime.utils.Rt_Utils
import net.postchain.rell.base.sql.SqlConstants
import net.postchain.rell.base.utils.CommonUtils
import java.util.*

object C_Lib_Type_ByteArray: C_Lib_Type("byte_array", R_ByteArrayType) {
    val DB_SUBSCRIPT: Db_SysFunction = BytesFns.Subscript_Db

    override fun bindConstructors(b: C_GlobalFuncBuilder) {
        b.add("byte_array", R_ByteArrayType, listOf(R_TextType), BytesFns.FromHex)
        b.add("byte_array", R_ByteArrayType, listOf(R_ListType(R_IntegerType)), BytesFns.FromList,
            depError("byte_array.from_list"))
    }

    override fun bindStaticFunctions(b: C_GlobalFuncBuilder) {
        b.add("from_list", R_ByteArrayType, listOf(R_ListType(R_IntegerType)), BytesFns.FromList)
        b.add("from_hex", R_ByteArrayType, listOf(R_TextType), BytesFns.FromHex)
        b.add("from_base64", R_ByteArrayType, listOf(R_TextType), BytesFns.FromBase64)
    }

    override fun bindMemberFunctions(b: C_MemberFuncBuilder) {
        b.add("empty", R_BooleanType, listOf(), BytesFns.Empty)
        b.add("size", R_IntegerType, listOf(), BytesFns.Size)
        b.add("len", R_IntegerType, listOf(), BytesFns.Size, depError("size"))
        b.add("decode", R_TextType, listOf(), BytesFns.Decode, depError("text.from_bytes"))
        b.add("toList", R_ListType(R_IntegerType), listOf(), BytesFns.ToList, depError("to_list"))
        b.add("to_list", R_ListType(R_IntegerType), listOf(), BytesFns.ToList)
        b.add("repeat", R_ByteArrayType, listOf(R_IntegerType), BytesFns.Repeat)
        b.add("reversed", R_ByteArrayType, listOf(), BytesFns.Reversed)
        b.add("sub", R_ByteArrayType, listOf(R_IntegerType), BytesFns.Sub_2)
        b.add("sub", R_ByteArrayType, listOf(R_IntegerType, R_IntegerType), BytesFns.Sub_3)
        b.add("to_hex", R_TextType, listOf(), BytesFns.ToHex)
        b.add("to_base64", R_TextType, listOf(), BytesFns.ToBase64)
        b.add("sha256", R_ByteArrayType, listOf(), C_Lib_Crypto.Sha256)
    }

    override fun bindAliases(b: C_SysNsProtoBuilder) {
        bindAlias(b, "pubkey")
    }
}

private object BytesFns {
    val Empty = C_SysFunction.simple1(Db_SysFunction.template("byte_array.empty", 1, "(LENGTH(#0) = 0)"), pure = true) { a ->
        val ba = a.asByteArray()
        Rt_BooleanValue(ba.isEmpty())
    }

    val Size = C_SysFunction.simple1(Db_SysFunction.template("byte_array.size", 1, "LENGTH(#0)"), pure = true) { a ->
        val ba = a.asByteArray()
        Rt_IntValue(ba.size.toLong())
    }

    val Sub_2 = C_SysFunction.simple2(
        Db_SysFunction.template("byte_array.sub/1", 2, "${SqlConstants.FN_BYTEA_SUBSTR1}(#0, (#1)::INT)"),
        pure = true
    ) { a, b ->
        val ba = a.asByteArray()
        val start = b.asInteger()
        calcSub(ba, start, ba.size.toLong())
    }

    val Sub_3 = C_SysFunction.simple3(
        Db_SysFunction.template("byte_array.sub/2", 3, "${SqlConstants.FN_BYTEA_SUBSTR2}(#0, (#1)::INT, (#2)::INT)"),
        pure = true
    ) { a, b, c ->
        val ba = a.asByteArray()
        val start = b.asInteger()
        val end = c.asInteger()
        calcSub(ba, start, end)
    }

    private fun calcSub(obj: ByteArray, start: Long, end: Long): Rt_Value {
        val len = obj.size
        if (start < 0 || start > len || end < start || end > len) {
            throw Rt_Exception.common("fn:byte_array.sub:range:$len:$start:$end",
                "Invalid range: start = $start, end = $end (length $len)")
        }
        val r = Arrays.copyOfRange(obj, start.toInt(), end.toInt())
        return Rt_ByteArrayValue(r)
    }

    val Decode = C_SysFunction.simple1(pure = true) { a ->
        val ba = a.asByteArray()
        Rt_TextValue(String(ba))
    }

    private val LIST_TYPE = R_ListType(R_IntegerType)

    val ToList = C_SysFunction.simple1(pure = true) { a ->
        val ba = a.asByteArray()
        val list = MutableList<Rt_Value>(ba.size) { Rt_IntValue(ba[it].toLong() and 0xFF) }
        Rt_ListValue(LIST_TYPE, list)
    }

    val ToHex = C_SysFunction.simple1(Db_SysFunction.template("byte_array.to_hex", 1, "ENCODE(#0, 'HEX')"), pure = true) { a ->
        val ba = a.asByteArray()
        val r = CommonUtils.bytesToHex(ba)
        Rt_TextValue(r)
    }

    val ToBase64 = C_SysFunction.simple1(
        Db_SysFunction.template("byte_array.to_base64", 1, "ENCODE(#0, 'BASE64')"),
        pure = true
    ) { a ->
        val ba = a.asByteArray()
        val r = Base64.getEncoder().encodeToString(ba)
        Rt_TextValue(r)
    }

    val FromHex = C_SysFunction.simple1(pure = true) { a ->
        val s = a.asString()
        val bytes = Rt_Utils.wrapErr("fn:byte_array.from_hex") {
            CommonUtils.hexToBytes(s)
        }
        Rt_ByteArrayValue(bytes)
    }

    val FromBase64 = C_SysFunction.simple1 { a ->
        val s = a.asString()
        val bytes = Rt_Utils.wrapErr("fn:byte_array.from_base64") {
            Base64.getDecoder().decode(s)
        }
        Rt_ByteArrayValue(bytes)
    }

    val FromList = C_SysFunction.simple1(pure = true) { a ->
        val s = a.asList()
        val r = ByteArray(s.size)
        for (i in s.indices) {
            val b = s[i].asInteger()
            if (b < 0 || b > 255) throw Rt_Exception.common("fn:byte_array.from_list:$b", "Byte value out of range: $b")
            r[i] = b.toByte()
        }
        Rt_ByteArrayValue(r)
    }

    val Repeat = C_SysFunction.simple2(pure = true) { a, b ->
        val bs = a.asByteArray()
        val n = b.asInteger()
        val s = bs.size
        val total = C_Lib_Type_List.rtCheckRepeatArgs(s, n, "byte_array")
        if (bs.isEmpty() || n == 1L) a else {
            val res = ByteArray(total) { bs[it % s] }
            Rt_ByteArrayValue(res)
        }
    }

    val Reversed = C_SysFunction.simple1(pure = true) { a ->
        val bs = a.asByteArray()
        if (bs.size <= 1) a else {
            val n = bs.size
            val res = ByteArray(n) { bs[n - 1 - it] }
            Rt_ByteArrayValue(res)
        }
    }

    val Subscript_Db = Db_SysFunction.template("byte_array.[]", 2, "GET_BYTE(#0, (#1)::INT)")
}
