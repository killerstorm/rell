/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import com.google.common.math.LongMath
import net.postchain.rell.base.compiler.base.lib.C_SysFunctionBody
import net.postchain.rell.base.compiler.base.utils.toCodeMsg
import net.postchain.rell.base.lib.Lib_Math
import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_BigIntegerType
import net.postchain.rell.base.model.expr.Db_SysFunction
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.runtime.utils.Rt_Utils
import net.postchain.rell.base.sql.SqlConstants
import net.postchain.rell.base.utils.checkEquals
import org.jooq.DataType
import org.jooq.impl.SQLDataType
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.util.*

object Lib_Type_BigInteger {
    val FromInteger_Db = Db_SysFunction.cast("big_integer(integer)", Lib_BigIntegerMath.SQL_TYPE_STR)

    val FromInteger = C_SysFunctionBody.simple(FromInteger_Db, pure = true) { a ->
        calcFromInteger(a)
    }

    private val FromText_1 = C_SysFunctionBody.simple(
        Db_SysFunction.simple("big_integer(text)", SqlConstants.FN_BIGINTEGER_FROM_TEXT),
        pure = true
    ) { a ->
        val s = a.asString()
        Rt_BigIntegerValue.get(s)
    }

    private val BIGINT_MIN_LONG = BigInteger.valueOf(Long.MIN_VALUE)
    private val BIGINT_MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE)

    private val BIGINT_MINUS_ONE = BigInteger.ONE.negate()

    val NAMESPACE = Ld_NamespaceDsl.make {
        type("big_integer", rType = R_BigIntegerType) {
            constant("PRECISION", (Lib_BigIntegerMath.PRECISION).toLong())
            constant("MIN_VALUE", Lib_BigIntegerMath.MIN_VALUE)
            constant("MAX_VALUE", Lib_BigIntegerMath.MAX_VALUE)

            constructor {
                param(type = "text")
                bodyRaw(FromText_1)
            }

            constructor {
                param(type = "integer")
                bodyRaw(FromInteger)
            }

            staticFunction("from_bytes", result = "big_integer", pure = true) {
                param(type = "byte_array")
                body { a ->
                    val bytes = a.asByteArray()
                    val bigInt = BigInteger(bytes)
                    Rt_BigIntegerValue.get(bigInt)
                }
            }

            staticFunction("from_bytes_unsigned", result = "big_integer", pure = true) {
                param(type = "byte_array")
                body { a ->
                    val bytes = a.asByteArray()
                    val bigInt = BigInteger(1, bytes)
                    Rt_BigIntegerValue.get(bigInt)
                }
            }

            staticFunction("from_text", result = "big_integer") {
                param(type = "text")
                bodyRaw(FromText_1)
            }

            staticFunction("from_text", result = "big_integer", pure = true) {
                param(type = "text")
                param(type = "integer")
                body { a, b ->
                    val s = a.asString()
                    val r = b.asInteger()
                    if (r < Character.MIN_RADIX || r > Character.MAX_RADIX) {
                        throw Rt_Exception.common("fn:big_integer.from_text:radix:$r", "Invalid radix: $r")
                    }
                    calcFromText(s, r.toInt(), "from_text")
                }
            }

            staticFunction("from_hex", result = "big_integer", pure = true) {
                param(type = "text")
                body { a ->
                    val s = a.asString()
                    calcFromText(s, 16, "from_hex")
                }
            }

            function("abs", "big_integer") {
                bodyRaw(Lib_Math.Abs_BigInteger)
            }

            function("min", "big_integer") {
                param("big_integer")
                bodyRaw(Lib_Math.Min_BigInteger)
            }

            function("min", "decimal", pure = true) {
                param("decimal")
                dbFunctionSimple("big_integer.min", "LEAST")
                body { a, b ->
                    val v1 = a.asBigInteger()
                    val v2 = b.asDecimal()
                    val r = v1.toBigDecimal().min(v2)
                    Rt_DecimalValue.get(r)
                }
            }

            function("max", "big_integer") {
                param("big_integer")
                bodyRaw(Lib_Math.Max_BigInteger)
            }

            function("max", "decimal", pure = true) {
                param("decimal")
                dbFunctionSimple("big_integer.max", "GREATEST")
                body { a, b ->
                    val v1 = a.asBigInteger()
                    val v2 = b.asDecimal()
                    val r = v1.toBigDecimal().max(v2)
                    Rt_DecimalValue.get(r)
                }
            }

            function("pow", result = "big_integer", pure = true) {
                param(name = "exponent", type = "integer")
                dbFunctionSimple(fnSimpleName, SqlConstants.FN_BIGINTEGER_POWER)
                body { a, b ->
                    val base = a.asBigInteger()
                    val exp = b.asInteger()

                    val res = Lib_BigIntegerMath.genericPower(
                        fnSimpleName,
                        base,
                        exp,
                        Lib_BigIntegerMath.NumericType_BigInteger
                    )

                    Rt_BigIntegerValue.get(res)
                }
            }

            function("sign", "integer", pure = true) {
                dbFunctionSimple("big_integer.sign", "SIGN")
                body { a ->
                    val v = a.asBigInteger()
                    val r = v.signum()
                    Rt_IntValue.get(r.toLong())
                }
            }

            function("to_bytes", "byte_array", pure = true) {
                body { a ->
                    val bigInt = a.asBigInteger()
                    val bytes = bigInt.toByteArray()
                    Rt_ByteArrayValue.get(bytes)
                }
            }

            function("to_bytes_unsigned", "byte_array", pure = true) {
                body { a ->
                    val bigInt = a.asBigInteger()
                    Rt_Utils.check(bigInt.signum() >= 0) {
                        "fn:big_integer.to_bytes_unsigned:negative" toCodeMsg "Value is negative"
                    }
                    var bytes = bigInt.toByteArray()
                    val n = (bigInt.bitLength() + 7) / 8
                    if (n != bytes.size) {
                        checkEquals(n, bytes.size - 1)
                        bytes = Arrays.copyOfRange(bytes, 1, bytes.size)
                    }
                    Rt_ByteArrayValue.get(bytes)
                }
            }

            function("to_decimal", "decimal") {
                bodyRaw(Lib_Type_Decimal.FromBigInteger)
            }

            function("to_hex", "text", pure = true) {
                body { a ->
                    val v = a.asBigInteger()
                    val s = v.toString(16)
                    Rt_TextValue.get(s)
                }
            }

            function("to_integer", "integer", pure = true) {
                dbFunctionTemplate("big_integer.to_integer", 1, "(#0)::BIGINT")
                body { a ->
                    val v = a.asBigInteger()
                    if (v < BIGINT_MIN_LONG || v > BIGINT_MAX_LONG) {
                        val s = v.toBigDecimal().round(MathContext(20, RoundingMode.DOWN))
                        throw Rt_Exception.common("big_integer.to_integer:overflow:$s", "Value out of range: $s")
                    }
                    val r = v.toLong()
                    Rt_IntValue.get(r)
                }
            }

            function("to_text", "text", pure = true) {
                dbFunctionTemplate("decimal.to_text", 1, "(#0)::TEXT")
                body { a ->
                    val v = a.asBigInteger()
                    val r = v.toString()
                    Rt_TextValue.get(r)
                }
            }

            function("to_text", "text", pure = true) {
                param("integer")
                body { a, b ->
                    val v = a.asBigInteger()
                    val r = b.asInteger()
                    if (r < Character.MIN_RADIX || r > Character.MAX_RADIX) {
                        throw Rt_Exception.common("fn:big_integer.to_text:radix:$r", "Invalid radix: $r")
                    }
                    val s = v.toString(r.toInt())
                    Rt_TextValue.get(s)
                }
            }
        }
    }

    private fun calcFromText(s: String, radix: Int, fnName: String): Rt_Value {
        val r = try {
            BigInteger(s, radix)
        } catch (e: NumberFormatException) {
            throw Rt_Exception.common("fn:big_integer.$fnName:$s", "Invalid number: '$s'")
        }
        return Rt_BigIntegerValue.get(r)
    }

    fun calcFromInteger(a: Rt_Value): Rt_Value {
        val i = a.asInteger()
        return Rt_BigIntegerValue.get(i)
    }
}

object Lib_BigIntegerMath {
    const val PRECISION = 131072

    val MAX_VALUE: BigInteger = BigInteger.TEN.pow(PRECISION).subtract(BigInteger.ONE)
    val MIN_VALUE: BigInteger = -MAX_VALUE

    val SQL_TYPE: DataType<*> = SQLDataType.DECIMAL

    const val SQL_TYPE_STR = "NUMERIC"

    fun add(a: BigInteger, b: BigInteger): BigInteger {
        return a.add(b)
    }

    fun subtract(a: BigInteger, b: BigInteger): BigInteger {
        return a.subtract(b)
    }

    fun multiply(a: BigInteger, b: BigInteger): BigInteger {
        return a.multiply(b)
    }

    fun divide(a: BigInteger, b: BigInteger): BigInteger {
        val r = a.divide(b)
        return r
    }

    fun remainder(a: BigInteger, b: BigInteger): BigInteger {
        return a.remainder(b)
    }

    fun <T> genericPower(fnName: String, base: T, exp: Long, type: NumericType<T>): T {
        Rt_Utils.check(exp >= 0) {
            "$fnName:exp_negative:$exp" toCodeMsg "Negative exponent: $exp"
        }

        val res = when {
            exp == 0L -> type.one
            exp == 1L -> base
            base == type.zero -> type.zero
            base == type.one -> type.one
            base == type.minusOne -> if ((exp and 1L) == 0L) type.one else type.minusOne
            else -> {
                try {
                    val exp0 = Math.toIntExact(exp)
                    type.pow(base, exp0)
                } catch (e: ArithmeticException) {
                    val baseStr = type.errStr(base)
                    val msg = "Power overflow: $baseStr ^ $exp"
                    throw Rt_Exception.common("$fnName:overflow:$baseStr:$exp", msg)
                }
            }
        }

        return res
    }

    abstract class NumericType<T>(val zero: T, val one: T, val minusOne: T) {
        abstract fun pow(base: T, exp: Int): T
        abstract fun errStr(value: T): String
    }

    object NumericType_Long: NumericType<Long>(zero = 0, one = 1, minusOne = -1) {
        override fun pow(base: Long, exp: Int): Long {
            return LongMath.checkedPow(base, exp)
        }

        override fun errStr(value: Long): String {
            return value.toString()
        }
    }

    object NumericType_BigInteger: NumericType<BigInteger>(
        zero = BigInteger.ZERO,
        one = BigInteger.ONE,
        minusOne = BigInteger.ONE.negate(),
    ) {
        override fun pow(base: BigInteger, exp: Int): BigInteger {
            // Check overflow by examining the bit length. Without this check, some combinations of base and exp
            // will produce a very big number by performing heavy and slow computations - checking the overflow after
            // the computation is too inefficient in such cases (example: 1E+1000 ^ 250000 = 1E+250000000).
            val baseExp = (base.abs().bitLength() - 1).coerceAtLeast(0)
            val resExp = LongMath.checkedMultiply(baseExp.toLong(), exp.toLong())
            if (resExp + 1 > MAX_VALUE.bitLength()) {
                throw ArithmeticException("Big integer power result out of range")
            }

            val res = base.pow(exp)
            if (res < MIN_VALUE || res > MAX_VALUE) {
                throw ArithmeticException("Big integer power result out of range")
            }

            return res
        }

        override fun errStr(value: BigInteger): String {
            val s = value.abs().toString()
            val s0 = if (s.length <= 100) s else {
                val head = s.substring(0, 1)
                val tail = s.substring(1, 16)
                val exp = s.length - 1
                "$head.$tail(...)E+$exp"
            }
            return if (value.signum() >= 0) s0 else "-$s0"
        }
    }
}
