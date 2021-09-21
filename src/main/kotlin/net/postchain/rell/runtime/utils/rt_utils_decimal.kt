/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.runtime.utils

import net.postchain.rell.compiler.base.utils.C_Constants
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

object Rt_DecimalUtils {
    private val UPPER_LIMIT = BigDecimal.TEN.pow(C_Constants.DECIMAL_INT_DIGITS)
    private val LOWER_LIMIT = -UPPER_LIMIT

    private val POSITIVE_MIN = BigDecimal.ONE.divide(BigDecimal.TEN.pow(C_Constants.DECIMAL_FRAC_DIGITS + 1))
    private val NEGATIVE_MIN = POSITIVE_MIN.negate()

    fun parse(s: String): BigDecimal {
        var t = if (s.startsWith(".")) {
            "0$s"
        } else if (s.startsWith("+.")) {
            "0${s.substring(1)}"
        } else if (s.startsWith("-.")) {
            "-0${s.substring(1)}"
        } else {
            s
        }

        t = removeTrailingZeros(t)
        return BigDecimal(t)
    }

    fun scale(v: BigDecimal): BigDecimal? {
        var t = v

        if (t >= NEGATIVE_MIN && t <= POSITIVE_MIN) {
            return BigDecimal.ZERO
        } else if (t <= LOWER_LIMIT || t >= UPPER_LIMIT) {
            return null
        }

        val scale = t.scale()
        if (scale > C_Constants.DECIMAL_FRAC_DIGITS) {
            t = v.setScale(C_Constants.DECIMAL_FRAC_DIGITS, RoundingMode.HALF_UP)
            if (t <= LOWER_LIMIT || t >= UPPER_LIMIT) {
                return null
            }
        }

        t = stripTrailingZeros(t)
        return t
    }

    private fun stripTrailingZeros(v: BigDecimal): BigDecimal {
        val scale = v.scale()
        if (scale <= 0) {
            return v
        }

        var s = scale

        var q = v.unscaledValue()
        while (s > 0) {
            val arr = q.divideAndRemainder(BigInteger.TEN)
            val div = arr[0]
            val mod = arr[1]
            if (mod != BigInteger.ZERO) break
            --s
            q = div
        }

        if (s == scale) {
            return v
        }

        return BigDecimal(q, s)
    }

    fun add(a: BigDecimal, b: BigDecimal): BigDecimal {
        return a.add(b)
    }

    fun subtract(a: BigDecimal, b: BigDecimal): BigDecimal {
        return a.subtract(b)
    }

    fun multiply(a: BigDecimal, b: BigDecimal): BigDecimal {
        return a.multiply(b)
    }

    fun divide(a: BigDecimal, b: BigDecimal): BigDecimal {
        val r = a.divide(b, C_Constants.DECIMAL_FRAC_DIGITS, RoundingMode.HALF_UP)
        return r
    }

    fun remainder(a: BigDecimal, b: BigDecimal): BigDecimal {
        return a.remainder(b)
    }

    fun power(a: BigDecimal, b: Int): BigDecimal {
        TODO() // Need to handle rounding and precision carefully.
    }

    private fun mathContext(a: BigDecimal, b: BigDecimal): MathContext {
        val p = a.precision() + b.precision() + 2
        return MathContext(p, RoundingMode.HALF_UP)
    }

    fun toString(v: BigDecimal): String {
        val s = v.toPlainString()
        val r = removeTrailingZeros(s)
        return r
    }

    fun toSciString(v: BigDecimal): String {
        return v.toString()
    }

    fun removeTrailingZeros(s: String): String {
        // Verify that the string is a valid number and find the fractional part.
        val (fracStart, fracEnd) = parseString(s)

        var i = fracEnd
        while (i > fracStart && s[i - 1] == '0') --i
        if (i > fracStart && s[i - 1] == '.') --i

        return if (i == fracEnd) {
            s
        } else if (fracEnd == s.length) {
            s.substring(0, i)
        } else {
            s.substring(0, i) + s.substring(fracEnd)
        }
    }

    private fun parseString(s: String): Pair<Int, Int> {
        val n = s.length

        var fracStart = n
        var fracEnd = n
        var i = 0

        if (i < n && (s[i] == '-' || s[i] == '+')) ++i
        verifyDigit(s, i++)
        while (i < n && isDigit(s, i)) ++i

        if (i < n && s[i] == '.') {
            fracStart = i
            ++i
            verifyDigit(s, i++)
            while (i < n && isDigit(s, i)) ++i
        }

        if (i < n && (s[i] == 'E' || s[i] == 'e')) {
            if (fracStart == n) fracStart = i
            fracEnd = i
            ++i
            if (i < n && (s[i] == '+' || s[i] == '-')) ++i
            verifyDigit(s, i++)
            while (i < n && isDigit(s, i)) ++i
        }

        if (i != n) {
            throw NumberFormatException()
        }

        return Pair(fracStart, fracEnd)
    }

    private fun verifyDigit(s: String, i: Int) {
        if (i >= s.length || !isDigit(s, i)) {
            throw NumberFormatException()
        }
    }

    private fun isDigit(s: String, i: Int): Boolean {
        val c = s[i]
        return c >= '0' && c <= '9'
    }
}
