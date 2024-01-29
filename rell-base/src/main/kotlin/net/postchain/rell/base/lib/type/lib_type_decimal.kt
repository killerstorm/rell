/*
 * Copyright (C) 2024 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.compiler.base.lib.C_SysFunctionBody
import net.postchain.rell.base.compiler.base.utils.C_MessageType
import net.postchain.rell.base.lib.Lib_Math
import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_DecimalType
import net.postchain.rell.base.model.expr.Db_SysFunction
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.sql.SqlConstants
import org.jooq.DataType
import org.jooq.impl.SQLDataType
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

object Lib_Type_Decimal {
    val ToInteger = DecFns.ToInteger
    val FromInteger = DecFns.FromInteger
    val FromInteger_Db = DecFns.FromInteger_Db
    val FromBigInteger = DecFns.FromBigInteger
    val FromBigInteger_Db = DecFns.FromBigInteger_Db

    // Using regexp (in a stored procedure) to remove trailing zeros.
    val ToText_Db: Db_SysFunction = Db_SysFunction.simple("decimal.to_text", SqlConstants.FN_DECIMAL_TO_TEXT)

    val NAMESPACE = Ld_NamespaceDsl.make {
        type("decimal", rType = R_DecimalType) {
            constant("PRECISION", (Lib_DecimalMath.DECIMAL_INT_DIGITS + Lib_DecimalMath.DECIMAL_FRAC_DIGITS).toLong())
            constant("SCALE", Lib_DecimalMath.DECIMAL_FRAC_DIGITS.toLong())
            constant("INT_DIGITS", Lib_DecimalMath.DECIMAL_INT_DIGITS.toLong())
            constant("MIN_VALUE", Lib_DecimalMath.DECIMAL_MIN_VALUE)
            constant("MAX_VALUE", Lib_DecimalMath.DECIMAL_MAX_VALUE)

            constructor {
                param("value", "text")
                bodyRaw(DecFns.FromText)
            }

            constructor {
                param("value", "integer")
                bodyRaw(DecFns.FromInteger)
            }

            constructor {
                param("value", "big_integer")
                bodyRaw(DecFns.FromBigInteger)
            }

            staticFunction("from_text", "decimal") {
                param("value", "text")
                bodyRaw(DecFns.FromText)
            }

            function("abs", "decimal") {
                bodyRaw(Lib_Math.Abs_Decimal)
            }

            function("ceil", "decimal", pure = true) {
                dbFunctionSimple("decimal.ceil", "CEIL")
                body { a ->
                    val v = a.asDecimal()
                    val r = v.setScale(0, RoundingMode.CEILING)
                    Rt_DecimalValue.get(r)
                }
            }

            function("floor", "decimal", pure = true) {
                dbFunctionSimple("decimal.floor", "FLOOR")
                body { a ->
                    val v = a.asDecimal()
                    val r = v.setScale(0, RoundingMode.FLOOR)
                    Rt_DecimalValue.get(r)
                }
            }

            function("min", "decimal") {
                param("value", "decimal")
                bodyRaw(Lib_Math.Min_Decimal)
            }

            function("max", "decimal") {
                param("value", "decimal")
                bodyRaw(Lib_Math.Max_Decimal)
            }

            function("round", "decimal", pure = true) {
                dbFunctionTemplate("decimal.round", 1, "ROUND(#0)")
                body { a ->
                    val v = a.asDecimal()
                    val r = v.setScale(0, RoundingMode.HALF_UP)
                    Rt_DecimalValue.get(r)
                }
            }

            function("round", "decimal", pure = true) {
                param("digits", "integer")
                // Argument #2 has to be casted to INT, as PostgreSQL doesn't allow BIGINT.
                dbFunctionTemplate("decimal.round", 2, "ROUND(#0,(#1)::INT)")
                body { a, b ->
                    val v = a.asDecimal()
                    var scale = b.asInteger()
                    scale = Math.max(scale, -Lib_DecimalMath.DECIMAL_INT_DIGITS.toLong())
                    scale = Math.min(scale, Lib_DecimalMath.DECIMAL_FRAC_DIGITS.toLong())
                    val r = v.setScale(scale.toInt(), RoundingMode.HALF_UP)
                    Rt_DecimalValue.get(r)
                }
            }

            //function("pow", "decimal", listOf("integer"), R_SysFn_Decimal.Pow)

            function("sign", "integer", pure = true) {
                alias("signum", C_MessageType.ERROR)
                dbFunctionSimple("decimal.sign", "SIGN")
                body { a ->
                    val v = a.asDecimal()
                    val r = v.signum()
                    Rt_IntValue.get(r.toLong())
                }
            }

            //function("sqrt", "decimal", listOf(), R_SysFn_Decimal.Sqrt)

            function("to_big_integer", "big_integer", pure = true) {
                dbFunctionTemplate("decimal.to_big_integer", 1, "TRUNC(#0)")
                body { a ->
                    val v = a.asDecimal()
                    val bi = v.toBigInteger()
                    Rt_BigIntegerValue.get(bi)
                }
            }

            function("to_integer", "integer") {
                bodyRaw(DecFns.ToInteger)
            }

            function("to_text", "text", pure = true) {
                dbFunction(ToText_Db)
                body { a ->
                    val v = a.asDecimal()
                    val r = Lib_DecimalMath.toString(v)
                    Rt_TextValue.get(r)
                }
            }

            function("to_text", "text", pure = true) {
                param("scientific", "boolean")
                body { a, b ->
                    val v = a.asDecimal()
                    val sci = b.asBoolean()
                    val r = if (sci) {
                        Lib_DecimalMath.toSciString(v)
                    } else {
                        Lib_DecimalMath.toString(v)
                    }
                    Rt_TextValue.get(r)
                }
            }
        }
    }

    fun calcFromInteger(a: Rt_Value): Rt_Value = DecFns.calcFromInteger(a)
}

object Lib_DecimalMath {
    const val DECIMAL_INT_DIGITS = 131072
    const val DECIMAL_FRAC_DIGITS = 20
    const val DECIMAL_SQL_TYPE_STR = "NUMERIC"

    val DECIMAL_SQL_TYPE: DataType<*> = SQLDataType.DECIMAL

    private const val DECIMAL_PRECISION = DECIMAL_INT_DIGITS + DECIMAL_FRAC_DIGITS

    val DECIMAL_MIN_VALUE: BigDecimal = BigDecimal.ONE.divide(BigDecimal.TEN.pow(DECIMAL_FRAC_DIGITS))
    val DECIMAL_MAX_VALUE: BigDecimal = BigDecimal.TEN.pow(DECIMAL_PRECISION).subtract(BigDecimal.ONE)
        .divide(BigDecimal.TEN.pow(DECIMAL_FRAC_DIGITS))
    private val UPPER_LIMIT = BigDecimal.TEN.pow(DECIMAL_INT_DIGITS)
    private val LOWER_LIMIT = -UPPER_LIMIT

    private val POSITIVE_MIN = BigDecimal.ONE.divide(BigDecimal.TEN.pow(DECIMAL_FRAC_DIGITS + 1))
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
        if (scale > DECIMAL_FRAC_DIGITS) {
            t = v.setScale(DECIMAL_FRAC_DIGITS, RoundingMode.HALF_UP)
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
        val r = a.divide(b, DECIMAL_FRAC_DIGITS, RoundingMode.HALF_UP)
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

private object DecFns {
    val Pow = C_SysFunctionBody.simple(Db_SysFunction.simple("decimal.pow", "POW"), pure = true) { a, b ->
        val v = a.asDecimal()
        val power = b.asInteger()
        if (power < 0) {
            throw Rt_Exception.common("decimal.pow:negative_power:$power", "Negative power: $power")
        }

        val r = Lib_DecimalMath.power(v, power.toInt())
        Rt_DecimalValue.get(r)
    }

    val Sqrt = C_SysFunctionBody.simple(Db_SysFunction.simple("decimal.sqrt", "SQRT"), pure = true) { a ->
        val v = a.asDecimal()
        if (v < BigDecimal.ZERO) {
            throw Rt_Exception.common("decimal.sqrt:negative:$v", "Negative value")
        }
        TODO()
    }

    private val BIG_INT_MIN = BigInteger.valueOf(Long.MIN_VALUE)
    private val BIG_INT_MAX = BigInteger.valueOf(Long.MAX_VALUE)

    val ToInteger = C_SysFunctionBody.simple(
        Db_SysFunction.template("decimal.to_integer", 1, "TRUNC(#0)::BIGINT"),
        pure = true,
    ) { a ->
        val v = a.asDecimal()
        val bi = v.toBigInteger()
        if (bi < BIG_INT_MIN || bi > BIG_INT_MAX) {
            val s = v.round(MathContext(20, RoundingMode.DOWN))
            throw Rt_Exception.common("decimal.to_integer:overflow:$s", "Value out of range: $s")
        }
        val r = bi.toLong()
        Rt_IntValue.get(r)
    }

    fun calcFromInteger(a: Rt_Value): Rt_Value {
        val i = a.asInteger()
        return Rt_DecimalValue.get(i)
    }

    val FromInteger_Db = Db_SysFunction.cast("decimal(integer)", Lib_DecimalMath.DECIMAL_SQL_TYPE_STR)

    val FromInteger = C_SysFunctionBody.simple(FromInteger_Db, pure = true) { a ->
        calcFromInteger(a)
    }

    val FromBigInteger_Db = Db_SysFunction.template("decimal(big_integer)", 1, "#0")

    val FromBigInteger = C_SysFunctionBody.simple(FromBigInteger_Db, pure = true) { a ->
        val bigInt = a.asBigInteger()
        val bigDec = bigInt.toBigDecimal()
        Rt_DecimalValue.get(bigDec)
    }

    val FromText = C_SysFunctionBody.simple(
        Db_SysFunction.simple("decimal(text)", SqlConstants.FN_DECIMAL_FROM_TEXT),
        pure = true
    ) { a ->
        val s = a.asString()
        Rt_DecimalValue.get(s)
    }
}
