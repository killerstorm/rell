/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_LibUtils.depError
import net.postchain.rell.compiler.base.utils.C_MemberFuncBuilder
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.lib.C_Lib_Math
import net.postchain.rell.model.R_BooleanType
import net.postchain.rell.model.R_DecimalType
import net.postchain.rell.model.R_IntegerType
import net.postchain.rell.model.R_TextType
import net.postchain.rell.model.expr.Db_SysFunction
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.immListOf
import org.jooq.impl.SQLDataType
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

object C_Lib_Type_Decimal: C_Lib_Type("decimal", R_DecimalType) {
    val ToInteger = DecFns.ToInteger
    val FromInteger = DecFns.FromInteger
    val FromInteger_Db = DecFns.FromInteger_Db
    val ToText_Db: Db_SysFunction = DecFns.ToText_1_Db

    override fun bindConstructors(b: C_GlobalFuncBuilder) {
        b.add(typeName.str, type, listOf(R_TextType), DecFns.FromText)
        b.add(typeName.str, type, listOf(R_IntegerType), DecFns.FromInteger)
    }

    override fun bindConstants() = immListOf(
        C_LibUtils.constValue("PRECISION", (Lib_DecimalMath.DECIMAL_INT_DIGITS + Lib_DecimalMath.DECIMAL_FRAC_DIGITS).toLong()),
        C_LibUtils.constValue("SCALE", Lib_DecimalMath.DECIMAL_FRAC_DIGITS.toLong()),
        C_LibUtils.constValue("INT_DIGITS", Lib_DecimalMath.DECIMAL_INT_DIGITS.toLong()),
        C_LibUtils.constValue("MIN_VALUE", Lib_DecimalMath.DECIMAL_MIN_VALUE),
        C_LibUtils.constValue("MAX_VALUE", Lib_DecimalMath.DECIMAL_MAX_VALUE)
    )

    override fun bindStaticFunctions(b: C_GlobalFuncBuilder) {
        b.add("from_text", R_DecimalType, listOf(R_TextType), DecFns.FromText)
    }

    override fun bindMemberFunctions(b: C_MemberFuncBuilder) {
        b.add("abs", R_DecimalType, listOf(), C_Lib_Math.Abs_Decimal)
        b.add("ceil", R_DecimalType, listOf(), DecFns.Ceil)
        b.add("floor", R_DecimalType, listOf(), DecFns.Floor)
        b.add("min", R_DecimalType, listOf(R_DecimalType), C_Lib_Math.Min_Decimal)
        b.add("max", R_DecimalType, listOf(R_DecimalType), C_Lib_Math.Max_Decimal)
        b.add("round", R_DecimalType, listOf(), DecFns.Round_1)
        b.add("round", R_DecimalType, listOf(R_IntegerType), DecFns.Round_2)
        //b.add("pow", R_DecimalType, listOf(R_IntegerType), R_SysFn_Decimal.Pow)
        b.add("signum", R_IntegerType, listOf(), DecFns.Sign, depError("sign"))
        b.add("sign", R_IntegerType, listOf(), DecFns.Sign)
        //b.add("sqrt", R_DecimalType, listOf(), R_SysFn_Decimal.Sqrt)
        b.add("to_integer", R_IntegerType, listOf(), DecFns.ToInteger)
        b.add("to_text", R_TextType, listOf(), DecFns.ToText_1)
        b.add("to_text", R_TextType, listOf(R_BooleanType), DecFns.ToText_2)
    }

    fun calcFromInteger(a: Rt_Value): Rt_Value = DecFns.calcFromInteger(a)
}

object Lib_DecimalMath {
    const val DECIMAL_INT_DIGITS = 131072
    const val DECIMAL_FRAC_DIGITS = 20
    const val DECIMAL_SQL_TYPE_STR = "NUMERIC"

    val DECIMAL_SQL_TYPE = SQLDataType.DECIMAL

    const val DECIMAL_PRECISION = DECIMAL_INT_DIGITS + DECIMAL_FRAC_DIGITS

    val DECIMAL_MIN_VALUE = BigDecimal.ONE.divide(BigDecimal.TEN.pow(DECIMAL_FRAC_DIGITS))
    val DECIMAL_MAX_VALUE = BigDecimal.TEN.pow(DECIMAL_PRECISION).subtract(BigDecimal.ONE)
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
    val Ceil = C_SysFunction.simple1(Db_SysFunction.simple("decimal.ceil", "CEIL"), pure = true) { a ->
        val v = a.asDecimal()
        val r = v.setScale(0, RoundingMode.CEILING)
        Rt_DecimalValue.of(r)
    }

    val Floor = C_SysFunction.simple1(Db_SysFunction.simple("decimal.floor", "FLOOR"), pure = true) { a ->
        val v = a.asDecimal()
        val r = v.setScale(0, RoundingMode.FLOOR)
        Rt_DecimalValue.of(r)
    }

    val Round_1 = C_SysFunction.simple1(Db_SysFunction.template("decimal.round", 1, "ROUND(#0)"), pure = true) { a ->
        val v = a.asDecimal()
        val r = v.setScale(0, RoundingMode.HALF_UP)
        Rt_DecimalValue.of(r)
    }

    // Argument #2 has to be casted to INT, PostgreSQL doesn't allow BIGINT.
    val Round_2 = C_SysFunction.simple2(Db_SysFunction.template("decimal.round", 2, "ROUND(#0,(#1)::INT)"), pure = true) { a, b ->
        val v = a.asDecimal()
        var scale = b.asInteger()
        scale = Math.max(scale, -Lib_DecimalMath.DECIMAL_INT_DIGITS.toLong())
        scale = Math.min(scale, Lib_DecimalMath.DECIMAL_FRAC_DIGITS.toLong())
        val r = v.setScale(scale.toInt(), RoundingMode.HALF_UP)
        Rt_DecimalValue.of(r)
    }

    val Pow = C_SysFunction.simple2(Db_SysFunction.simple("decimal.pow", "POW"), pure = true) { a, b ->
        val v = a.asDecimal()
        val power = b.asInteger()
        if (power < 0) {
            throw Rt_Exception.common("decimal.pow:negative_power:$power", "Negative power: $power")
        }

        val r = Lib_DecimalMath.power(v, power.toInt())
        Rt_DecimalValue.of(r)
    }

    val Sqrt = C_SysFunction.simple1(Db_SysFunction.simple("decimal.sqrt", "SQRT"), pure = true) { a ->
        val v = a.asDecimal()
        if (v < BigDecimal.ZERO) {
            throw Rt_Exception.common("decimal.sqrt:negative:$v", "Negative value")
        }
        TODO()
    }

    val Sign = C_SysFunction.simple1(Db_SysFunction.simple("decimal.sign", "SIGN"), pure = true) { a ->
        val v = a.asDecimal()
        val r = v.signum()
        Rt_IntValue(r.toLong())
    }

    private val BIG_INT_MIN = BigInteger.valueOf(Long.MIN_VALUE)
    private val BIG_INT_MAX = BigInteger.valueOf(Long.MAX_VALUE)

    val ToInteger = C_SysFunction.simple1(Db_SysFunction.template("decimal.to_integer", 1, "TRUNC(#0)::BIGINT"), pure = true) { a ->
        val v = a.asDecimal()
        val bi = v.toBigInteger()
        if (bi < BIG_INT_MIN || bi > BIG_INT_MAX) {
            val s = v.round(MathContext(20, RoundingMode.DOWN))
            throw Rt_Exception.common("decimal.to_integer:overflow:$s", "Value out of range: $s")
        }
        val r = bi.toLong()
        Rt_IntValue(r)
    }

    // Using regexp to remove trailing zeros.
    // Clever regexp: can handle special cases like "0.0", "0.000000", etc.
    val ToText_1_Db = Db_SysFunction.template("decimal.to_text", 1,
        "REGEXP_REPLACE((#0)::TEXT, '(([.][0-9]*[1-9])(0+)\$)|([.]0+\$)', '\\2')"
    )

    val ToText_1 = C_SysFunction.simple1(ToText_1_Db, pure = true) { a ->
        val v = a.asDecimal()
        val r = Lib_DecimalMath.toString(v)
        Rt_TextValue(r)
    }

    val ToText_2 = C_SysFunction.simple2(pure = true) { a, b ->
        val v = a.asDecimal()
        val sci = b.asBoolean()
        val r = if (sci) {
            Lib_DecimalMath.toSciString(v)
        } else {
            Lib_DecimalMath.toString(v)
        }
        Rt_TextValue(r)
    }

    fun calcFromInteger(a: Rt_Value): Rt_Value {
        val i = a.asInteger()
        return Rt_DecimalValue.of(i)
    }

    val FromInteger_Db = Db_SysFunction.cast("decimal(integer)", Lib_DecimalMath.DECIMAL_SQL_TYPE_STR)

    val FromInteger = C_SysFunction.simple1(FromInteger_Db, pure = true) { a ->
        calcFromInteger(a)
    }

    val FromText = C_SysFunction.simple1(
        Db_SysFunction.cast("decimal(text)", Lib_DecimalMath.DECIMAL_SQL_TYPE_STR),
        pure = true
    ) { a ->
        val s = a.asString()
        Rt_DecimalValue.of(s)
    }
}
