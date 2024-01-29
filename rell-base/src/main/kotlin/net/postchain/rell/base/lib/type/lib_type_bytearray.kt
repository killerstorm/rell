/*
 * Copyright (C) 2024 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.compiler.base.lib.C_SysFunctionBody
import net.postchain.rell.base.compiler.base.utils.C_MessageType
import net.postchain.rell.base.lib.Lib_Crypto
import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_ByteArrayType
import net.postchain.rell.base.model.R_IntegerType
import net.postchain.rell.base.model.R_ListType
import net.postchain.rell.base.model.expr.Db_SysFunction
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.runtime.utils.Rt_Utils
import net.postchain.rell.base.sql.SqlConstants
import net.postchain.rell.base.utils.CommonUtils
import java.util.*

object Lib_Type_ByteArray {
    val DB_SUBSCRIPT: Db_SysFunction = Db_SysFunction.template("byte_array.[]", 2, "GET_BYTE(#0, (#1)::INT)")

    private val FromHex = C_SysFunctionBody.simple(pure = true) { a ->
        val s = a.asString()
        val bytes = Rt_Utils.wrapErr("fn:byte_array.from_hex") {
            CommonUtils.hexToBytes(s)
        }
        Rt_ByteArrayValue.get(bytes)
    }

    private val FromList = C_SysFunctionBody.simple(pure = true) { a ->
        val s = a.asList()
        val r = ByteArray(s.size)
        for (i in s.indices) {
            val b = s[i].asInteger()
            if (b < 0 || b > 255) throw Rt_Exception.common("fn:byte_array.from_list:$b", "Byte value out of range: $b")
            r[i] = b.toByte()
        }
        Rt_ByteArrayValue.get(r)
    }

    private val LIST_OF_INTEGER = R_ListType(R_IntegerType)

    val NAMESPACE = Ld_NamespaceDsl.make {
        alias("pubkey", "byte_array")

        type("byte_array", rType = R_ByteArrayType) {
            parent(type = "iterable<integer>")

            constructor {
                param("hex", type = "text")
                bodyRaw(FromHex)
            }

            constructor {
                deprecated(newName = "byte_array.from_list")
                param("list", type = "list<integer>")
                bodyRaw(FromList)
            }

            staticFunction("from_list", result = "byte_array") {
                param("list", type = "list<integer>")
                bodyRaw(FromList)
            }

            staticFunction("from_hex", result = "byte_array") {
                param("value", type = "text")
                bodyRaw(FromHex)
            }

            staticFunction("from_base64", result = "byte_array") {
                param("value", type = "text")
                body { a ->
                    val s = a.asString()
                    val bytes = Rt_Utils.wrapErr("fn:byte_array.from_base64") {
                        Base64.getDecoder().decode(s)
                    }
                    Rt_ByteArrayValue.get(bytes)
                }
            }

            function("empty", "boolean", pure = true) {
                dbFunctionTemplate("byte_array.empty", 1, "(LENGTH(#0) = 0)")
                body { a ->
                    val ba = a.asByteArray()
                    Rt_BooleanValue.get(ba.isEmpty())
                }
            }

            function("size", "integer", pure = true) {
                alias("len", C_MessageType.ERROR)
                dbFunctionTemplate("byte_array.size", 1, "LENGTH(#0)")
                body { a ->
                    val ba = a.asByteArray()
                    Rt_IntValue.get(ba.size.toLong())
                }
            }

            function("decode", "text", pure = true) {
                deprecated(newName = "text.from_bytes")
                body { a ->
                    val ba = a.asByteArray()
                    Rt_TextValue.get(String(ba))
                }
            }

            function("to_list", "list<integer>", pure = true) {
                alias("toList", C_MessageType.ERROR)
                body { a ->
                    val ba = a.asByteArray()
                    val list = MutableList<Rt_Value>(ba.size) { Rt_IntValue.get(ba[it].toLong() and 0xFF) }
                    Rt_ListValue(LIST_OF_INTEGER, list)
                }
            }

            function("repeat", "byte_array", pure = true) {
                param("n", "integer")
                body { a, b ->
                    val bs = a.asByteArray()
                    val n = b.asInteger()
                    val s = bs.size
                    val total = Lib_Type_List.rtCheckRepeatArgs(s, n, "byte_array")
                    if (bs.isEmpty() || n == 1L) a else {
                        val res = ByteArray(total) { bs[it % s] }
                        Rt_ByteArrayValue.get(res)
                    }
                }
            }

            function("reversed", "byte_array", pure = true) {
                body { a ->
                    val bs = a.asByteArray()
                    if (bs.size <= 1) a else {
                        val n = bs.size
                        val res = ByteArray(n) { bs[n - 1 - it] }
                        Rt_ByteArrayValue.get(res)
                    }
                }
            }

            function("sub", "byte_array", pure = true) {
                param("start", "integer")
                dbFunctionTemplate("byte_array.sub/1", 2, "${SqlConstants.FN_BYTEA_SUBSTR1}(#0, (#1)::INT)")
                body { a, b ->
                    val ba = a.asByteArray()
                    val start = b.asInteger()
                    calcSub(ba, start, ba.size.toLong())
                }
            }

            function("sub", "byte_array", pure = true) {
                param("start", "integer")
                param("end", "integer")
                dbFunctionTemplate("byte_array.sub/2", 3, "${SqlConstants.FN_BYTEA_SUBSTR2}(#0, (#1)::INT, (#2)::INT)")
                body { a, b, c ->
                    val ba = a.asByteArray()
                    val start = b.asInteger()
                    val end = c.asInteger()
                    calcSub(ba, start, end)
                }
            }

            function("to_hex", "text", pure = true) {
                dbFunctionTemplate("byte_array.to_hex", 1, "ENCODE(#0, 'HEX')")
                body { a ->
                    val ba = a.asByteArray()
                    val r = CommonUtils.bytesToHex(ba)
                    Rt_TextValue.get(r)
                }
            }

            function("to_base64", "text", pure = true) {
                dbFunctionTemplate("byte_array.to_base64", 1, "ENCODE(#0, 'BASE64')")
                body { a ->
                    val ba = a.asByteArray()
                    val r = Base64.getEncoder().encodeToString(ba)
                    Rt_TextValue.get(r)
                }
            }

            function("sha256", "byte_array") {
                bodyRaw(Lib_Crypto.Sha256)
            }
        }
    }

    private fun calcSub(obj: ByteArray, start: Long, end: Long): Rt_Value {
        val len = obj.size
        if (start < 0 || start > len || end < start || end > len) {
            throw Rt_Exception.common("fn:byte_array.sub:range:$len:$start:$end",
                "Invalid range: start = $start, end = $end (length $len)")
        }
        val r = obj.copyOfRange(start.toInt(), end.toInt())
        return Rt_ByteArrayValue.get(r)
    }
}
