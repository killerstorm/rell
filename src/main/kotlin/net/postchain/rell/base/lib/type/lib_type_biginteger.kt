/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.type

import net.postchain.rell.base.compiler.base.utils.*
import net.postchain.rell.base.lib.C_Lib_Math
import net.postchain.rell.base.model.*
import net.postchain.rell.base.model.expr.Db_SysFunction
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.runtime.utils.Rt_Utils
import net.postchain.rell.base.sql.SqlConstants
import net.postchain.rell.base.utils.checkEquals
import net.postchain.rell.base.utils.immListOf
import org.jooq.DataType
import org.jooq.impl.SQLDataType
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.util.*

object C_Lib_Type_BigInteger: C_Lib_Type("big_integer", R_BigIntegerType) {
    val FromInteger = BigIntFns.FromInteger
    val FromInteger_Db = BigIntFns.FromInteger_Db

    override fun bindConstructors(b: C_GlobalFuncBuilder) {
        b.add(typeName.str, type, listOf(R_TextType), BigIntFns.FromText_1)
        b.add(typeName.str, type, listOf(R_IntegerType), BigIntFns.FromInteger)
    }

    override fun bindConstants() = immListOf(
        C_LibUtils.constValue("PRECISION", (Lib_BigIntegerMath.PRECISION).toLong()),
        C_LibUtils.constValue("MIN_VALUE", Lib_BigIntegerMath.MIN_VALUE),
        C_LibUtils.constValue("MAX_VALUE", Lib_BigIntegerMath.MAX_VALUE)
    )

    override fun bindStaticFunctions(b: C_GlobalFuncBuilder) {
        b.add("from_bytes", R_BigIntegerType, listOf(R_ByteArrayType), BigIntFns.FromBytes)
        b.add("from_bytes_unsigned", R_BigIntegerType, listOf(R_ByteArrayType), BigIntFns.FromBytesUnsigned)
        b.add("from_text", R_BigIntegerType, listOf(R_TextType), BigIntFns.FromText_1)
        b.add("from_text", R_BigIntegerType, listOf(R_TextType, R_IntegerType), BigIntFns.FromText_2)
        b.add("from_hex", R_BigIntegerType, listOf(R_TextType), BigIntFns.FromHex)
    }

    override fun bindMemberFunctions(b: C_MemberFuncBuilder) {
        b.add("abs", R_BigIntegerType, listOf(), C_Lib_Math.Abs_BigInteger)
        b.add("min", R_BigIntegerType, listOf(R_BigIntegerType), C_Lib_Math.Min_BigInteger)
        b.add("min", R_DecimalType, listOf(R_DecimalType), BigIntFns.Min_Decimal)
        b.add("max", R_BigIntegerType, listOf(R_BigIntegerType), C_Lib_Math.Max_BigInteger)
        b.add("max", R_DecimalType, listOf(R_DecimalType), BigIntFns.Max_Decimal)
        b.add("sign", R_IntegerType, listOf(), BigIntFns.Sign)
        b.add("to_bytes", R_ByteArrayType, listOf(), BigIntFns.ToBytes)
        b.add("to_bytes_unsigned", R_ByteArrayType, listOf(), BigIntFns.ToBytesUnsigned)
        b.add("to_decimal", R_DecimalType, listOf(), C_Lib_Type_Decimal.FromBigInteger)
        b.add("to_hex", R_TextType, listOf(), BigIntFns.ToHex)
        b.add("to_integer", R_IntegerType, listOf(), BigIntFns.ToInteger)
        b.add("to_text", R_TextType, listOf(), BigIntFns.ToText_1)
        b.add("to_text", R_TextType, listOf(R_IntegerType), BigIntFns.ToText_2)
    }

    fun calcFromInteger(a: Rt_Value): Rt_Value = BigIntFns.calcFromInteger(a)
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
}

private object BigIntFns {
    val Min_Decimal = C_SysFunction.simple2(Db_SysFunction.simple("big_integer.min", "LEAST"), pure = true) { a, b ->
        val v1 = a.asBigInteger()
        val v2 = b.asDecimal()
        val r = v1.toBigDecimal().min(v2)
        Rt_DecimalValue.of(r)
    }

    val Max_Decimal = C_SysFunction.simple2(Db_SysFunction.simple("big_integer.max", "GREATEST"), pure = true) { a, b ->
        val v1 = a.asBigInteger()
        val v2 = b.asDecimal()
        val r = v1.toBigDecimal().max(v2)
        Rt_DecimalValue.of(r)
    }

    val Sign = C_SysFunction.simple1(Db_SysFunction.simple("big_integer.sign", "SIGN"), pure = true) { a ->
        val v = a.asBigInteger()
        val r = v.signum()
        Rt_IntValue(r.toLong())
    }

    private val BIGINT_MIN_LONG = BigInteger.valueOf(Long.MIN_VALUE)
    private val BIGINT_MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE)

    val ToInteger = C_SysFunction.simple1(Db_SysFunction.template("big_integer.to_integer", 1, "(#0)::BIGINT"), pure = true) { a ->
        val v = a.asBigInteger()
        if (v < BIGINT_MIN_LONG || v > BIGINT_MAX_LONG) {
            val s = v.toBigDecimal().round(MathContext(20, RoundingMode.DOWN))
            throw Rt_Exception.common("big_integer.to_integer:overflow:$s", "Value out of range: $s")
        }
        val r = v.toLong()
        Rt_IntValue(r)
    }

    val ToHex = C_SysFunction.simple1(pure = true) { a ->
        val v = a.asBigInteger()
        val s = v.toString(16)
        Rt_TextValue(s)
    }

    val ToText_1 = C_SysFunction.simple1(Db_SysFunction.template("decimal.to_text", 1, "(#0)::TEXT"), pure = true) { a ->
        val v = a.asBigInteger()
        val r = v.toString()
        Rt_TextValue(r)
    }

    val ToText_2 = C_SysFunction.simple2(pure = true) { a, b ->
        val v = a.asBigInteger()
        val r = b.asInteger()
        if (r < Character.MIN_RADIX || r > Character.MAX_RADIX) {
            throw Rt_Exception.common("fn:big_integer.to_text:radix:$r", "Invalid radix: $r")
        }
        val s = v.toString(r.toInt())
        Rt_TextValue(s)
    }

    fun calcFromInteger(a: Rt_Value): Rt_Value {
        val i = a.asInteger()
        return Rt_BigIntegerValue.of(i)
    }

    val FromBytes = C_SysFunction.simple1(pure = true) { a ->
        val bytes = a.asByteArray()
        val bigInt = BigInteger(bytes)
        Rt_BigIntegerValue.of(bigInt)
    }

    val ToBytes = C_SysFunction.simple1(pure = true) { a ->
        val bigInt = a.asBigInteger()
        val bytes = bigInt.toByteArray()
        Rt_ByteArrayValue(bytes)
    }

    val FromBytesUnsigned = C_SysFunction.simple1(pure = true) { a ->
        val bytes = a.asByteArray()
        val bigInt = BigInteger(1, bytes)
        Rt_BigIntegerValue.of(bigInt)
    }

    val ToBytesUnsigned = C_SysFunction.simple1(pure = true) { a ->
        val bigInt = a.asBigInteger()
        Rt_Utils.check(bigInt.signum() >= 0) { "fn:big_integer.to_bytes_unsigned:negative" toCodeMsg "Value is negative" }
        var bytes = bigInt.toByteArray()
        val n = (bigInt.bitLength() + 7) / 8
        if (n != bytes.size) {
            checkEquals(n, bytes.size - 1)
            bytes = Arrays.copyOfRange(bytes, 1, bytes.size)
        }
        Rt_ByteArrayValue(bytes)
    }

    val FromInteger_Db = Db_SysFunction.cast("big_integer(integer)", Lib_BigIntegerMath.SQL_TYPE_STR)

    val FromInteger = C_SysFunction.simple1(FromInteger_Db, pure = true) { a ->
        calcFromInteger(a)
    }

    val FromText_1 = C_SysFunction.simple1(
        Db_SysFunction.simple("big_integer(text)", SqlConstants.FN_BIGINTEGER_FROM_TEXT),
        pure = true
    ) { a ->
        val s = a.asString()
        Rt_BigIntegerValue.of(s)
    }

    val FromText_2 = C_SysFunction.simple2(pure = true) { a, b ->
        val s = a.asString()
        val r = b.asInteger()
        if (r < Character.MIN_RADIX || r > Character.MAX_RADIX) {
            throw Rt_Exception.common("fn:big_integer.from_text:radix:$r", "Invalid radix: $r")
        }
        calcFromText(s, r.toInt(), "from_text")
    }

    val FromHex = C_SysFunction.simple1(pure = true) { a ->
        val s = a.asString()
        calcFromText(s, 16, "from_hex")
    }

    private fun calcFromText(s: String, radix: Int, fnName: String): Rt_Value {
        val r = try {
            BigInteger(s, radix)
        } catch (e: NumberFormatException) {
            throw Rt_Exception.common("fn:big_integer.$fnName:$s", "Invalid number: '$s'")
        }
        return Rt_BigIntegerValue.of(r)
    }
}
