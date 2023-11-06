/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.utils

import net.postchain.rell.base.compiler.vexpr.V_ConstantValueEvalContext
import net.postchain.rell.base.compiler.vexpr.V_Expr
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.utils.doc.DocCode
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.min

object C_DocUtils {
    fun valueToDoc(b: DocCode.Builder, value: Rt_Value?) {
        val code = if (value == null) null else valueToDoc(value)
        if (code == null) {
            b.raw("<...>")
        } else {
            b.append(code)
        }
    }

    fun valueToDoc(value: Rt_Value): DocCode? {
        val b = DocCode.builder()

        val b2: DocCode.Builder? = when (value) {
            Rt_NullValue -> b.keyword("null")
            Rt_UnitValue -> b.raw("unit")
            is Rt_IntValue -> b.raw(value.str())
            is Rt_BooleanValue -> b.keyword(value.str())
            is Rt_BigIntegerValue -> valueToDocBigInteger(b, value.value)
            is Rt_DecimalValue -> valueToDocDecimal(b, value.value)
            is Rt_TextValue -> valueToDocText(b, value.value)
            is Rt_ByteArrayValue -> valueToDocByteArray(b, value.asByteArray())
            is Rt_RowidValue -> b.raw("rowid(").raw(value.value.toString()).raw(")")
            else -> null
        }

        return b2?.build()
    }

    private fun valueToDocBigInteger(b: DocCode.Builder, value: BigInteger): DocCode.Builder? {
        val bits = value.bitLength()
        return if (bits > 4000) null else {
            b.raw(value.toString())
            b.raw("L")
        }
    }

    private fun valueToDocDecimal(b: DocCode.Builder, value: BigDecimal): DocCode.Builder? {
        val s = value.toPlainString()
        return if (s.length > 100) null else {
            b.raw(s)
            if ('.' !in s) b.raw(".0")
            b
        }
    }

    private fun valueToDocText(b: DocCode.Builder, value: String): DocCode.Builder {
        val maxLen = 100

        val buf = StringBuilder()
        buf.append("\"")
        var truncated = false

        for (c in value) {
            if (buf.length >= maxLen + 1) {
                buf.append("...")
                truncated = true
                break
            }
            if (c == '"') {
                buf.append("\\\"")
            } else if (c == '\r') {
                buf.append("\\r")
            } else if (c == '\n') {
                buf.append("\\n")
            } else if (c == '\t') {
                buf.append("\\t")
            } else if (c < '\u0020') {
                buf.append("\\u${"%04X".format(c.code)}")
            } else {
                buf.append(c)
            }
        }

        if (!truncated) {
            buf.append("\"")
        }

        val text = buf.toString()
        b.raw(text)
        return b
    }

    private fun valueToDocByteArray(b: DocCode.Builder, value: ByteArray): DocCode.Builder? {
        b.raw("x\"")

        val n = value.size
        val k = min(n, 50)

        val buf = StringBuilder()
        for (i in 0 until k) {
            buf.append("%02X".format(value[i]))
        }

        b.raw(buf.toString())

        if (k < n) {
            b.raw("...")
        } else {
            b.raw("\"")
        }

        return b
    }

    fun exprToDoc(b: DocCode.Builder, vExpr: V_Expr) {
        val value = try {
            vExpr.constantValue(V_ConstantValueEvalContext())
        } catch (e: Throwable) {
            null
        }
        valueToDoc(b, value)
    }
}
