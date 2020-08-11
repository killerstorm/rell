/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell

import net.postchain.rell.compiler.C_Constants
import net.postchain.rell.test.BaseResourcefulTest
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals

abstract class OperatorsBaseTest: BaseResourcefulTest() {
    @Test fun testCmpBoolean() {
        chkOpBool("==", vBool(false), vBool(false), true)
        chkOpBool("==", vBool(false), vBool(true), false)
        chkOpBool("==", vBool(true), vBool(false), false)
        chkOpBool("==", vBool(true), vBool(true), true)

        chkOpBool("!=", vBool(false), vBool(false), false)
        chkOpBool("!=", vBool(false), vBool(true), true)
        chkOpBool("!=", vBool(true), vBool(false), true)
        chkOpBool("!=", vBool(true), vBool(true), false)

        chkOpErr("boolean < boolean")
        chkOpErr("boolean > boolean")
        chkOpErr("boolean <= boolean")
        chkOpErr("boolean >= boolean")
    }

    @Test fun testCmpInteger() {
        chkCmpCommon(vInt(55), vInt(122), vInt(123), vInt(124), vInt(456))
    }

    @Test fun testCmpDecimal() {
        chkCmpCommon(vDec("55"), vDec("122"), vDec("123"), vDec("124"), vDec("456"))
        chkCmpCommon(vDec("55e500"), vDec("122e500"), vDec("123e500"), vDec("124e500"), vDec("456e500"))
        chkCmpCommon(vDec("1e500+55"), vDec("1e500+122"), vDec("1e500+123"), vDec("1e500+124"), vDec("1e500+456"))

        // Extreme values.
        val maxp = C_Constants.DECIMAL_INT_DIGITS - 1
        val minp = -C_Constants.DECIMAL_FRAC_DIGITS
        chkCmpCommon(
                vDec("1e$maxp+55e$minp"),
                vDec("1e$maxp+122e$minp"),
                vDec("1e$maxp+123e$minp"),
                vDec("1e$maxp+124e$minp"),
                vDec("1e$maxp+456e$minp")
        )

        // Mixed integer and decimal.
        chkOpEq(vInt(123), vDec("123"), true)
        chkOpEq(vInt(123), vDec("321"), false)
        chkCmpCommon(vDec("55"), vInt(122), vDec("123"), vInt(124), vDec("456"))
        chkCmpCommon(vInt(55), vDec("122"), vInt(123), vDec("124"), vInt(456))
    }

    @Test fun testCmpRowid() {
        chkCmpCommon(vRowid(55), vRowid(122), vRowid(123), vRowid(124), vRowid(456))
    }

    @Test fun testCmpText() {
        chkCmpCommon(vText("Apple"), vText("Helln"), vText("Hello"), vText("Hellp"), vText("World"))
        chkCmpCommon(vText("HELLO"), vText("Hello"))
        chkCmpCommon(vText("Hello"), vText("hello"))
    }

    @Test fun testCmpByteArray() {
        chkCmpCommon(vBytes("0000"), vBytes("0122"), vBytes("0123"), vBytes("0124"), vBytes("abcd"))
        chkCmpCommon(vBytes("beef"), vBytes("cafd"), vBytes("cafe"), vBytes("caff"), vBytes("dead"))
        chkCmpCommon(vBytes("0123"), vBytes("abcd"))
        chkCmpCommon(vBytes("0123"), vBytes("0123abcd"))
        chkCmpCommon(vBytes("0123"), vBytes("02"))
        chkCmpCommon(vBytes("01"), vBytes("0123"))
        chkCmpCommon(vBytes("00"), vBytes("ff"))
        chkCmpCommon(vBytes(""), vBytes("0123abcd"))
    }

    @Test fun testCmpObject() {
        chkCmpCommon(vObj("user", 1000), vObj("user", 2000))
    }

    private fun chkCmpCommon(vLow: TstVal, vMinusOne: TstVal, v: TstVal, vPlusOne: TstVal, vHigh: TstVal) {
        chkOpBool("==", v, v, true)
        chkOpBool("==", v, vLow, false)
        chkOpBool("==", v, vHigh, false)
        chkOpBool("==", v, vMinusOne, false)
        chkOpBool("==", v, vPlusOne, false)

        chkOpBool("!=", v, v, false)
        chkOpBool("!=", v, vLow, true)
        chkOpBool("!=", v, vHigh, true)
        chkOpBool("!=", v, vMinusOne, true)
        chkOpBool("!=", v, vPlusOne, true)

        chkOpBool("<", v, vLow, false)
        chkOpBool("<", v, vMinusOne, false)
        chkOpBool("<", v, v, false)
        chkOpBool("<", v, vPlusOne, true)
        chkOpBool("<", v, vHigh, true)

        chkOpBool("<=", v, vLow, false)
        chkOpBool("<=", v, vMinusOne, false)
        chkOpBool("<=", v, v, true)
        chkOpBool("<=", v, vPlusOne, true)
        chkOpBool("<=", v, vHigh, true)

        chkOpBool(">", v, vLow, true)
        chkOpBool(">", v, vMinusOne, true)
        chkOpBool(">", v, v, false)
        chkOpBool(">", v, vPlusOne, false)
        chkOpBool(">", v, vHigh, false)

        chkOpBool(">=", v, vLow, true)
        chkOpBool(">=", v, vMinusOne, true)
        chkOpBool(">=", v, v, true)
        chkOpBool(">=", v, vPlusOne, false)
        chkOpBool(">=", v, vHigh, false)
    }

    private fun chkCmpCommon(v1: TstVal, v2: TstVal) {
        chkOpEq(v1, v2, false)

        chkOpBool("<", v1, v1, false)
        chkOpBool("<", v2, v2, false)
        chkOpBool("<", v1, v2, true)
        chkOpBool("<", v2, v1, false)

        chkOpBool("<=", v1, v1, true)
        chkOpBool("<=", v2, v2, true)
        chkOpBool("<=", v1, v2, true)
        chkOpBool("<=", v2, v1, false)

        chkOpBool(">", v1, v1, false)
        chkOpBool(">", v2, v2, false)
        chkOpBool(">", v1, v2, false)
        chkOpBool(">", v2, v1, true)

        chkOpBool(">=", v1, v1, true)
        chkOpBool(">=", v2, v2, true)
        chkOpBool(">=", v1, v2, false)
        chkOpBool(">=", v2, v1, true)
    }

    private fun chkOpEq(v1: TstVal, v2: TstVal, eq: Boolean) {
        chkOpBool("==", v1, v1, true)
        chkOpBool("==", v2, v2, true)
        chkOpBool("==", v1, v2, eq)
        chkOpBool("==", v2, v1, eq)

        chkOpBool("!=", v1, v1, false)
        chkOpBool("!=", v2, v2, false)
        chkOpBool("!=", v1, v2, !eq)
        chkOpBool("!=", v2, v1, !eq)
    }

    @Test fun testCmpErr() {
        chkCmpErr("==")
        chkCmpErr("!=")
        chkCmpErr("<")
        chkCmpErr(">")
        chkCmpErr("<=")
        chkCmpErr(">=")
    }

    private fun chkCmpErr(op: String) {
        chkOpErr("boolean $op integer")
        chkOpErr("boolean $op decimal")
        chkOpErr("boolean $op text")
        chkOpErr("boolean $op rowid")
        chkOpErr("boolean $op user")
        chkOpErr("integer $op boolean")
        chkOpErr("integer $op text")
        chkOpErr("integer $op rowid")
        chkOpErr("integer $op user")
        chkOpErr("text $op boolean")
        chkOpErr("text $op integer")
        chkOpErr("text $op decimal")
        chkOpErr("text $op rowid")
        chkOpErr("text $op user")
        chkOpErr("user $op boolean")
        chkOpErr("user $op integer")
        chkOpErr("user $op decimal")
        chkOpErr("user $op text")
        chkOpErr("user $op rowid")
        chkOpErr("user $op company")
    }

    @Test fun testAnd() {
        chkOpBool("and", vBool(false), vBool(false), false)
        chkOpBool("and", vBool(false), vBool(true), false)
        chkOpBool("and", vBool(true), vBool(false), false)
        chkOpBool("and", vBool(true), vBool(true), true)
    }

    @Test fun testOr() {
        chkOpBool("or", vBool(false), vBool(false), false)
        chkOpBool("or", vBool(false), vBool(true), true)
        chkOpBool("or", vBool(true), vBool(false), true)
        chkOpBool("or", vBool(true), vBool(true), true)
    }

    @Test fun testNot() {
        chkOpBool("not", vBool(false), true)
        chkOpBool("not", vBool(true), false)
    }

    @Test fun testPlusInteger() {
        chkOp("+", vInt(123), vInt(456), "int[579]")
        chkOp("+", vInt(12345), vInt(67890), "int[80235]")
        chkOp("+", vInt(9223372036854775806), vInt(1), "int[9223372036854775807]")
        chkIntOverflow("+", 9223372036854775807, 1)
    }

    @Test fun testPlusDecimal() {
        chkDecimalCases(DecimalTest.ADD_TEST_CASES)

        // Mixed integer and decimal.
        chkOp("+", vDec("123"), vInt(456), "dec[579]")
        chkOp("+", vInt(123), vDec("456"), "dec[579]")
        chkOp("+", vDec("12345"), vInt(67890), "dec[80235]")
        chkOp("+", vInt(12345), vDec("67890"), "dec[80235]")

        val d = DV
        chkOp("+", vDec("${d.lim2}"), vInt(1), "dec[${d.lim1}]")
        chkDecOverflow("+", vDec("${d.lim2}"), vInt(2))
        chkOp("+", vDec("-${d.lim2}"), vInt(-1), "dec[-${d.lim1}]")
        chkDecOverflow("+", vDec("-${d.lim2}"), vInt(-2))
    }

    private fun chkDecimalCases(cases: List<DecimalTest.DecAddCase>) {
        for (case in cases) {
            if (case.expected != null) {
                chkOp(case.op, vDec(case.a), vDec(case.b), "dec[${case.expected}]")
            } else {
                chkDecOverflow(case.op, vDec(case.a), vDec(case.b))
            }
        }
    }

    @Test fun testPlusSpecial() {
        chkOp("+", vText("Hello"), vText("World"), "text[HelloWorld]")
        chkOp("+", vBytes("0123456789"), vBytes("abcdef"), "byte_array[0123456789abcdef]")

        chkPlusText("Hello", vBool(true), "true")
        chkPlusText("Hello", vInt(123), "123")
        chkPlusText("Hello", vDec("123.456"), "123.456")
        chkPlusText("Hello", vJson("[{}]"), "[{}]")
        chkPlusText("Hello", vRowid(123), "123")
        //chkPlusText("Hello", vObj("user", 1000), "user[1000]")
    }

    private fun chkPlusText(a: String, b: TstVal, bs: String) {
        chkOp("+", vText(a), b, "text[$a$bs]")
        chkOp("+", b, vText(a), "text[$bs$a]")
    }

    @Test fun testPlusUnary() {
        chkOp("+", vInt(123), "int[123]")
        chkOp("+", vInt(-123), "int[-123]")

        chkOp("+", vDec("123.456"), "dec[123.456]")
        chkOp("+", vDec("-123.456"), "dec[-123.456]")
    }

    @Test fun testMinusInteger() {
        chkOp("-", vInt(123), vInt(456), "int[-333]")
        chkOp("-", vInt(456), vInt(123), "int[333]")
        chkOp("-", vInt(12345), vInt(67890), "int[-55545]")
        chkOp("-", vInt(67890), vInt(12345), "int[55545]")
        chkOp("-", vInt(-9223372036854775807), vInt(1), "int[-9223372036854775808]")
        chkIntOverflow("-", -9223372036854775807-1, 1)
    }

    @Test fun testMinusDecimal() {
        chkDecimalCases(DecimalTest.SUB_TEST_CASES)

        // Mixed integer and decimal.
        chkOp("-", vDec("12345"), vInt(67890), "dec[-55545]")
        chkOp("-", vInt(12345), vDec("67890"), "dec[-55545]")
        chkOp("-", vDec("67890"), vInt(12345), "dec[55545]")
        chkOp("-", vInt(67890), vDec("12345"), "dec[55545]")

        val d = DV
        chkOp("-", vDec("-${d.lim2}"), vInt(1), "dec[-${d.lim1}]")
        chkDecOverflow("-", vDec("-${d.lim2}"), vInt(2))
        chkOp("-", vDec("${d.lim2}"), vInt(-1), "dec[${d.lim1}]")
        chkDecOverflow("-", vDec("${d.lim2}"), vInt(-2))
    }

    @Test fun testMinusUnary() {
        chkOp("-", vInt(123), "int[-123]")
        chkOp("-", vInt(-123), "int[123]")
        chkOp("-", vInt(Long.MIN_VALUE), errRt("expr:-:overflow:-9223372036854775808"))

        chkOp("-", vDec("123"), "dec[-123]")
        chkOp("-", vDec("-123"), "dec[123]")
        chkOp("-", vDec("123.456"), "dec[-123.456]")
        chkOp("-", vDec("-123.456"), "dec[123.456]")
    }

    @Test fun testMulInteger() {
        chkOp("*", vInt(123), vInt(456), "int[56088]")
        chkOp("*", vInt(123), vInt(0), "int[0]")
        chkOp("*", vInt(0), vInt(456), "int[0]")
        chkOp("*", vInt(123), vInt(1), "int[123]")
        chkOp("*", vInt(1), vInt(456), "int[456]")
        chkOp("*", vInt(-1), vInt(456), "int[-456]")
        chkOp("*", vInt(4294967296-1), vInt(2147483648), "int[9223372034707292160]")
        chkIntOverflow("*", 4294967296, 2147483648)
    }

    @Test fun testMulDecimal() {
        chkOp("*", vDec("123"), vDec("456"), "dec[56088]")
        chkOp("*", vDec("123"), vDec("0"), "dec[0]")
        chkOp("*", vDec("0"), vDec("456"), "dec[0]")
        chkOp("*", vDec("123"), vDec("1"), "dec[123]")
        chkOp("*", vDec("1"), vDec("456"), "dec[456]")
        chkOp("*", vDec("-1"), vDec("456"), "dec[-456]")
        chkOp("*", vDec("4294967295"), vDec("2147483648"), "dec[9223372034707292160]")
        chkOp("*", vDec("4294967296"), vDec("2147483648"), "dec[9223372036854775808]")
        chkOp("*", vDec("9223372036854775808"), vDec("9223372036854775807"), "dec[85070591730234615856620279821087277056]")

        // Extreme values.
        val d = DV

        val exp = "99999" + "0".repeat(d.intDigs - 5)
        chkOp("*", vDec("${d.limDiv10}"), vDec("9.9999"), "dec[$exp]")
        chkDecOverflow("*", vDec("${d.limDiv10}"), vDec("10"))

        // Rounding.
        chkOp("*", vDec("0.${d.frac0}50"), vDec("0.1"), "dec[0.${d.frac0}05]")
        chkOp("*", vDec("0.${d.frac0}54"), vDec("0.1"), "dec[0.${d.frac0}05]")
        chkOp("*", vDec("0.${d.frac0}55"), vDec("0.1"), "dec[0.${d.frac0}06]")
        chkOp("*", vDec("0.${d.frac0}59"), vDec("0.1"), "dec[0.${d.frac0}06]")

        // Mixed integer and decimal.
        chkOp("*", vDec("123"), vInt(456), "dec[56088]")
        chkOp("*", vInt(123), vDec("456"), "dec[56088]")
        chkOp("*", vDec("123"), vInt(0), "dec[0]")
        chkOp("*", vInt(123), vDec("0"), "dec[0]")
        chkOp("*", vDec("123"), vInt(1), "dec[123]")
        chkOp("*", vInt(123), vDec("1"), "dec[123]")
        chkOp("*", vDec("-1"), vInt(456), "dec[-456]")
        chkOp("*", vInt(-1), vDec("456"), "dec[-456]")
        chkOp("*", vDec("4294967296"), vInt(2147483648), "dec[9223372036854775808]")
        chkOp("*", vInt(4294967296), vDec("2147483648"), "dec[9223372036854775808]")
        chkOp("*", vDec("9223372036854775808"), vInt(9223372036854775807), "dec[85070591730234615856620279821087277056]")
        chkDecOverflow("*", vDec("${d.limDiv10}"), vInt(10))
    }

    @Test fun testDivInteger() {
        chkOp("/", vInt(123), vInt(456), "int[0]")
        chkOp("/", vInt(456), vInt(123), "int[3]")
        chkOp("/", vInt(1000000), vInt(1), "int[1000000]")
        chkOp("/", vInt(1000000), vInt(2), "int[500000]")
        chkOp("/", vInt(1000000), vInt(9), "int[111111]")
        chkOp("/", vInt(1000000), vInt(10), "int[100000]")
        chkOp("/", vInt(1000000), vInt(11), "int[90909]")
        chkOp("/", vInt(1000000), vInt(333333), "int[3]")
        chkOp("/", vInt(1000000), vInt(333334), "int[2]")
        chkOp("/", vInt(1000000), vInt(499999), "int[2]")
        chkOp("/", vInt(1000000), vInt(500000), "int[2]")
        chkOp("/", vInt(1000000), vInt(500001), "int[1]")
        chkOp("/", vInt(1), vInt(0), errRt("expr:/:div0:1"))
        chkOp("/", vInt(123456789), vInt(0), errRt("expr:/:div0:123456789"))
    }

    @Test fun testDivDecimal() {
        chkOp("/", vDec("42883369"), vDec("7717"), "dec[5557]")
        chkOp("/", vDec("42883369"), vDec("5557"), "dec[7717]")
        chkOp("/", vDec("123456789"), vDec("0"), errRt("expr:/:div0"))

        chkOp("/", vDec("123"), vDec("456"), "dec[0.26973684210526315789]")
        chkOp("/", vDec("456"), vDec("123"), "dec[3.70731707317073170732]")
        chkOp("/", vDec("12.34"), vDec("56.78"), "dec[0.21733004579077139838]")
        chkOp("/", vDec("56.78"), vDec("12.34"), "dec[4.60129659643435980551]")
        chkOp("/", vDec("1"), vDec("7"), "dec[0.14285714285714285714]")

        // Extreme values.
        val d = DV

        chkOp("/", vDec("1e${d.intDigs-2}"), vDec("0.1"), "dec[${d.limDiv10}]")
        chkOp("/", vDec("1e${d.intDigs-2}"), vDec("0.01"), errRt("expr:/:overflow"))
        chkOp("/", vDec("1e${d.intDigs-1}"), vDec("0.1"), errRt("expr:/:overflow"))

        // Rounding.
        chkOp("/", vDec("0.${d.frac0}77"), vDec("2"), "dec[0.${d.frac0}39]")
        chkOp("/", vDec("0.${d.frac0}77"), vDec("3"), "dec[0.${d.frac0}26]")
        chkOp("/", vDec("0.${d.frac0}77"), vDec("4"), "dec[0.${d.frac0}19]")
        chkOp("/", vDec("0.${d.frac0}77"), vDec("5"), "dec[0.${d.frac0}15]")
        chkOp("/", vDec("0.${d.frac0}77"), vDec("6"), "dec[0.${d.frac0}13]")
        chkOp("/", vDec("0.${d.frac0}77"), vDec("7"), "dec[0.${d.frac0}11]")
        chkOp("/", vDec("0.${d.frac0}50"), vDec("10"), "dec[0.${d.frac0}05]")
        chkOp("/", vDec("0.${d.frac0}54"), vDec("10"), "dec[0.${d.frac0}05]")
        chkOp("/", vDec("0.${d.frac0}55"), vDec("10"), "dec[0.${d.frac0}06]")
        chkOp("/", vDec("0.${d.frac0}59"), vDec("10"), "dec[0.${d.frac0}06]")

        // Mixed integer and decimal.
        chkOp("/", vDec("42883369"), vInt(7717), "dec[5557]")
        chkOp("/", vInt(42883369), vDec("7717"), "dec[5557]")
        chkOp("/", vDec("42883369"), vInt(5557), "dec[7717]")
        chkOp("/", vInt(42883369), vDec("5557"), "dec[7717]")
        chkOp("/", vDec("123456789"), vInt(0), errRt("expr:/:div0"))
        chkOp("/", vInt(123456789), vDec("0"), errRt("expr:/:div0"))
        chkOp("/", vDec("123"), vInt(456), "dec[0.26973684210526315789]")
        chkOp("/", vInt(123), vDec("456"), "dec[0.26973684210526315789]")
        chkOp("/", vDec("456"), vInt(123), "dec[3.70731707317073170732]")
        chkOp("/", vInt(456), vDec("123"), "dec[3.70731707317073170732]")
    }

    @Test fun testModInteger() {
        chkOp("%", vInt(123), vInt(456), "int[123]")
        chkOp("%", vInt(456), vInt(123), "int[87]")
        chkOp("%", vInt(1000000), vInt(2), "int[0]")
        chkOp("%", vInt(1000000), vInt(3), "int[1]")
        chkOp("%", vInt(1000000), vInt(9999), "int[100]")
        chkOp("%", vInt(123), vInt(0), errRt("expr:%:div0:123"))
    }

    @Test fun testModDecimal() {
        chkOp("%", vDec("123"), vDec("456"), "dec[123]")
        chkOp("%", vDec("456"), vDec("123"), "dec[87]")
        chkOp("%", vDec("12.34"), vDec("56.78"), "dec[12.34]")
        chkOp("%", vDec("56.78"), vDec("12.34"), "dec[7.42]")
        chkOp("%", vDec("12.34"), vDec("0"), errRt("expr:%:div0"))
        chkOp("%", vDec("123"), vDec("123"), "dec[0]")
        chkOp("%", vDec("123456789"), vDec("123456789"), "dec[0]")
        chkOp("%", vDec("123456789"), vDec("123456788"), "dec[1]")
        chkOp("%", vDec("0.123456789"), vDec("0.123456789"), "dec[0]")
        chkOp("%", vDec("0.123456789"), vDec("0.123456788"), "dec[0.000000001]")

        // Mixed integer and decimal.
        chkOp("%", vDec("123"), vInt(456), "dec[123]")
        chkOp("%", vInt(123), vDec("456"), "dec[123]")
        chkOp("%", vDec("456"), vInt(123), "dec[87]")
        chkOp("%", vInt(456), vDec("123"), "dec[87]")
    }

    private fun chkIntOverflow(op: String, left: Long, right: Long) {
        chkOp(op, vInt(left), vInt(right), errRt("expr:$op:overflow:$left:$right"))
        chkOp(op, vInt(right), vInt(left), errRt("expr:$op:overflow:$right:$left"))
    }

    private fun chkDecOverflow(op: String, left: TstVal, right: TstVal) {
        chkOp(op, left, right, errRt("expr:$op:overflow"))
        chkOp(op, right, left, errRt("expr:$op:overflow"))
    }

    @Test fun testErr() {
        chkOpErr("boolean + integer")
        chkOpErr("boolean + decimal")
        chkOpErr("boolean + user")
        chkOpErr("integer + boolean")
        chkOpErr("integer + user")
        chkOpErr("user + boolean")
        chkOpErr("user + integer")
        chkOpErr("user + company")

        chkErrSub("-")
        chkErrSub("*")
        chkErrSub("/")
        chkErrSub("%")
        chkErrSub("and")
        chkErrSub("or")

        chkErrSubBin("+", "integer", "boolean", "user")
        chkErrSubBin("-", "integer", "boolean", "text", "user")
        chkErrSubBin("*", "integer", "boolean", "text", "user")
        chkErrSubBin("/", "integer", "boolean", "text", "user")
        chkErrSubBin("%", "integer", "boolean", "text", "user")
        chkErrSubBin("and", "boolean", "integer", "decimal", "text", "user")
        chkErrSubBin("or", "boolean", "integer", "decimal", "text", "user")

        chkErrSubUn("+", "boolean", "text", "user", "rowid")
        chkErrSubUn("-", "boolean", "text", "user", "rowid")
        chkErrSubUn("not", "integer", "decimal", "text", "user", "rowid")
    }

    private fun chkErrSub(op: String) {
        chkOpErr("boolean $op integer")
        chkOpErr("boolean $op decimal")
        chkOpErr("boolean $op text")
        chkOpErr("boolean $op rowid")
        chkOpErr("boolean $op user")

        chkOpErr("integer $op boolean")
        chkOpErr("integer $op text")
        chkOpErr("integer $op rowid")
        chkOpErr("integer $op user")

        chkOpErr("text $op boolean")
        chkOpErr("text $op integer")
        chkOpErr("text $op decimal")
        chkOpErr("text $op rowid")
        chkOpErr("text $op user")

        chkOpErr("user $op boolean")
        chkOpErr("user $op integer")
        chkOpErr("user $op decimal")
        chkOpErr("user $op rowid")
        chkOpErr("user $op text")
        chkOpErr("user $op company")

        chkOpErr("rowid $op boolean")
        chkOpErr("rowid $op integer")
        chkOpErr("rowid $op decimal")
        chkOpErr("rowid $op text")
        chkOpErr("rowid $op user")
    }

    private fun chkErrSubBin(op: String, goodType: String, vararg badTypes: String) {
        for (badType in badTypes) {
            chkOpErr(op, goodType, badType)
            chkOpErr(op, badType, goodType)
            chkOpErr(op, badType, badType)
        }
    }

    private fun chkErrSubUn(op: String, vararg types: String) {
        for (type in types) {
            chkOpErr(op, type)
        }
    }

    @Test fun testFnJson() {
        chkExpr("json(#0)", """json[{"a":10,"b":[1,2,3],"c":{"x":999}}]""",
                vText("""{ "a" : 10, "b" : [1, 2, 3], "c" : { "x" : 999 } }"""))

        chkExpr("#0.str()", "text[{}]", vJson("{}"))
        chkExpr("#0.str()", "text[[]]", vJson("[]"))
        chkExpr("#0.str()", "text[[12345]]", vJson("[12345]"))
        chkExpr("#0.str()", """text[["Hello"]]""", vJson("""["Hello"]"""))
    }

    @Test fun testLibMathInteger() {
        chkExpr("_type_of(abs(#0))", "text[integer]", vInt(12345))
        chkExpr("abs(#0)", "int[12345]", vInt(12345))
        chkExpr("abs(#0)", "int[12345]", vInt(-12345))

        chkExpr("_type_of(min(#0, #1))", "text[integer]", vInt(12345), vInt(67890))
        chkExpr("min(#0, #1)", "int[12345]", vInt(12345), vInt(67890))
        chkExpr("min(#0, #1)", "int[12345]", vInt(67890), vInt(12345))

        chkExpr("_type_of(max(#0, #1))", "text[integer]", vInt(12345), vInt(67890))
        chkExpr("max(#0, #1)", "int[67890]", vInt(12345), vInt(67890))
        chkExpr("max(#0, #1)", "int[67890]", vInt(67890), vInt(12345))
    }

    @Test fun testLibMathDecimal() {
        // Decimal.
        chkExpr("_type_of(abs(#0))", "text[decimal]", vDec("123.456"))
        chkExpr("_type_of(abs(#0))", "text[decimal]", vDec("123456"))
        chkExpr("abs(#0)", "dec[123.456]", vDec("123.456"))
        chkExpr("abs(#0)", "dec[123.456]", vDec("-123.456"))

        chkExpr("_type_of(min(#0, #1))", "text[decimal]", vDec("12.34"), vDec("56.78"))
        chkExpr("_type_of(min(#0, #1))", "text[decimal]", vDec("1234"), vDec("5678"))
        chkExpr("min(#0, #1)", "dec[12.34]", vDec("12.34"), vDec("56.78"))
        chkExpr("min(#0, #1)", "dec[12.34]", vDec("56.78"), vDec("12.34"))

        chkExpr("_type_of(max(#0, #1))", "text[decimal]", vDec("12.34"), vDec("56.78"))
        chkExpr("_type_of(max(#0, #1))", "text[decimal]", vDec("1234"), vDec("5678"))
        chkExpr("max(#0, #1)", "dec[56.78]", vDec("12.34"), vDec("56.78"))
        chkExpr("max(#0, #1)", "dec[56.78]", vDec("56.78"), vDec("12.34"))

        // Mixed.
        chkExpr("_type_of(min(#0, #1))", "text[integer]", vInt(123), vInt(456))
        chkExpr("_type_of(min(#0, #1))", "text[decimal]", vDec("123"), vInt(456))
        chkExpr("_type_of(min(#0, #1))", "text[decimal]", vInt(123), vDec("456"))
        chkExpr("min(#0, #1)", "dec[123]", vDec("123"), vInt(456))
        chkExpr("min(#0, #1)", "dec[123]", vInt(123), vDec("456"))
        chkExpr("min(#0, #1)", "dec[123]", vDec("456"), vInt(123))
        chkExpr("min(#0, #1)", "dec[123]", vInt(456), vDec("123"))

        chkExpr("_type_of(max(#0, #1))", "text[integer]", vInt(123), vInt(456))
        chkExpr("_type_of(max(#0, #1))", "text[decimal]", vDec("123"), vInt(456))
        chkExpr("_type_of(max(#0, #1))", "text[decimal]", vInt(123), vDec("456"))
        chkExpr("max(#0, #1)", "dec[456]", vDec("123"), vInt(456))
        chkExpr("max(#0, #1)", "dec[456]", vInt(123), vDec("456"))
        chkExpr("max(#0, #1)", "dec[456]", vDec("456"), vInt(123))
        chkExpr("max(#0, #1)", "dec[456]", vInt(456), vDec("123"))
    }

    @Test fun testLibByteArrayEmpty() {
        chkExpr("#0.empty()", "boolean[true]", vBytes(""))
        chkExpr("#0.empty()", "boolean[false]", vBytes("00"))
        chkExpr("#0.empty()", "boolean[false]", vBytes("123456789A"))
    }

    @Test fun testLibByteArraySize() {
        chkExpr("#0.size()", "int[0]", vBytes(""))
        chkExpr("#0.size()", "int[1]", vBytes("00"))
        chkExpr("#0.size()", "int[5]", vBytes("123456789A"))
    }

    @Test fun testLibByteArraySub() {
        chkExpr("#0.sub(#1)", "byte_array[0123abcd]", vBytes("0123ABCD"), vInt(0))
        chkExpr("#0.sub(#1)", "byte_array[abcd]", vBytes("0123ABCD"), vInt(2))
        chkExpr("#0.sub(#1)", "byte_array[cd]", vBytes("0123ABCD"), vInt(3))
        chkExpr("#0.sub(#1)", "byte_array[]", vBytes("0123ABCD"), vInt(4))
        //chkExpr("#0.sub(#1)", errRt("fn:byte_array.sub:range:4:5:4"), vBytes("0123ABCD"), vInt(5))
        //chkExpr("#0.sub(#1)", errRt("fn:byte_array.sub:range:4:-1:4"), vBytes("0123ABCD"), vInt(-1))
        chkExpr("#0.sub(#1, #2)", "byte_array[23ab]", vBytes("0123ABCD"), vInt(1), vInt(3))
        chkExpr("#0.sub(#1, #2)", "byte_array[0123abcd]", vBytes("0123ABCD"), vInt(0), vInt(4))
        chkExpr("#0.sub(#1, #2)", errRt("fn:byte_array.sub:range:4:1:0"), vBytes("0123ABCD"), vInt(1), vInt(0))
        //chkExpr("#0.sub(#1, #2)", errRt("fn:byte_array.sub:range:4:1:5"), vBytes("0123ABCD"), vInt(1), vInt(5))
    }

    @Test fun testLibByteArrayToHex() {
        chkExpr("#0.to_hex()", "text[]", vBytes(""))
        chkExpr("#0.to_hex()", "text[deadbeef]", vBytes("DEADBEEF"))
    }

    @Test fun testLibByteArrayToBase64() {
        chkExpr("#0.to_base64()", "text[]", vBytes(""))
        chkExpr("#0.to_base64()", "text[AA==]", vBytes("00"))
        chkExpr("#0.to_base64()", "text[AAA=]", vBytes("0000"))
        chkExpr("#0.to_base64()", "text[AAAA]", vBytes("000000"))
        chkExpr("#0.to_base64()", "text[AAAAAA==]", vBytes("00000000"))
        chkExpr("#0.to_base64()", "text[////]", vBytes("FFFFFF"))
        chkExpr("#0.to_base64()", "text[EjRWeA==]", vBytes("12345678"))
        chkExpr("#0.to_base64()", "text[3q2+7w==]", vBytes("deadbeef"))
        chkExpr("#0.to_base64()", "text[yv66vt6tvu8=]", vBytes("cafebabedeadbeef"))
    }

    @Test fun testIf() {
        chkExpr("if (#0) 1 else 2", "int[1]", vBool(true))
        chkExpr("if (#0) 1 else 2", "int[2]", vBool(false))
        chkExpr("if (#0) 'Yes' else 'No'", "text[Yes]", vBool(true))
        chkExpr("if (#0) 'Yes' else 'No'", "text[No]", vBool(false))

        chkExpr("if (#0) #1 else #2", "int[123]", vBool(true), vInt(123), vInt(456))
        chkExpr("if (#0) #1 else #2", "int[456]", vBool(false), vInt(123), vInt(456))
        chkExpr("if (#0) #1 else #2", "text[Yes]", vBool(true), vText("Yes"), vText("No"))
        chkExpr("if (#0) #1 else #2", "text[No]", vBool(false), vText("Yes"), vText("No"))

        chkExprErr("if (true) 'Hello' else 123", listOf(), "ct_err:expr_if_restype:[text]:[integer]")
        chkExprErr("if (true) 'Hello' else decimal(123)", listOf(), "ct_err:expr_if_restype:[text]:[decimal]")
        chkExprErr("if (123) 'A' else 'B'", listOf(), "ct_err:expr_if_cond_type:[boolean]:[integer]")
        chkExprErr("if (decimal(123)) 'A' else 'B'", listOf(), "ct_err:expr_if_cond_type:[boolean]:[decimal]")
        chkExprErr("if ('Hello') 'A' else 'B'", listOf(), "ct_err:expr_if_cond_type:[boolean]:[text]")
        chkExprErr("if (null) 'A' else 'B'", listOf(), "ct_err:expr_if_cond_type:[boolean]:[null]")
        chkExprErr("if (unit()) 'A' else 'B'", listOf(), "ct_err:expr_if_cond_type:[boolean]:[unit]")
    }

    @Test fun testIfDecimal() {
        chkExpr("if (#0) #1 else #2", "dec[123]", vBool(true), vDec("123"), vInt(456))
        chkExpr("if (#0) #1 else #2", "dec[123]", vBool(true), vInt(123), vDec("456"))
        chkExpr("if (#0) #1 else #2", "dec[456]", vBool(false), vDec("123"), vInt(456))
        chkExpr("if (#0) #1 else #2", "dec[456]", vBool(false), vInt(123), vDec("456"))
    }

    @Test fun testWhenDecimal() {
        chkWhenDecimal(vDec("123"), vDec("456"), vDec("789"))

        chkWhenDecimal(vInt(123), vDec("456"), vDec("789"))
        chkWhenDecimal(vDec("123"), vInt(456), vDec("789"))
        chkWhenDecimal(vDec("123"), vDec("456"), vInt(789))

        chkWhenDecimal(vDec("123"), vInt(456), vInt(789))
        chkWhenDecimal(vInt(123), vDec("456"), vInt(789))
        chkWhenDecimal(vInt(123), vInt(456), vDec("789"))
    }

    private fun chkWhenDecimal(v1: TstVal, v2: TstVal, v3: TstVal) {
        val expr = "when (#0) { 'A' -> #1; 'B' -> #2; else -> #3; }"
        chkExpr("_type_of($expr)", "text[decimal]", vText(""), v1, v2, v3)
        chkExpr(expr, "dec[123]", vText("A"), v1, v2, v3)
        chkExpr(expr, "dec[456]", vText("B"), v1, v2, v3)
        chkExpr(expr, "dec[789]", vText("C"), v1, v2, v3)
    }

    @Test fun testSubscriptByteArray() {
        chkExpr("#0[#1]", "int[1]", vBytes("0123ABCD"), vInt(0))
        chkExpr("#0[#1]", "int[35]", vBytes("0123ABCD"), vInt(1))
        chkExpr("#0[#1]", "int[171]", vBytes("0123ABCD"), vInt(2))
        chkExpr("#0[#1]", "int[205]", vBytes("0123ABCD"), vInt(3))
        chkExpr("#0[#1]", errRt("expr_bytearray_subscript_index:4:4"), vBytes("0123ABCD"), vInt(4))
        chkExpr("#0[#1]", errRt("expr_bytearray_subscript_index:4:-1"), vBytes("0123ABCD"), vInt(-1))
    }

    @Test fun testSubscriptText() {
        chkExpr("#0[#1]", "text[H]", vText("Hello"), vInt(0))
        chkExpr("#0[#1]", "text[e]", vText("Hello"), vInt(1))
        chkExpr("#0[#1]", "text[l]", vText("Hello"), vInt(2))
        chkExpr("#0[#1]", "text[l]", vText("Hello"), vInt(3))
        chkExpr("#0[#1]", "text[o]", vText("Hello"), vInt(4))
        //chkExpr("#0[#1]", "rt_err:expr_text_subscript_index:5:-1", vText("Hello"), vInt(-1))
        //chkExpr("#0[#1]", "rt_err:expr_text_subscript_index:5:5", vText("Hello"), vInt(5))
    }

    @Test fun testLibIntegerAbs() {
        chkExpr("#0.abs()", "int[0]", vInt(0))
        chkExpr("#0.abs()", "int[12345]", vInt(-12345))
        chkExpr("#0.abs()", "int[67890]", vInt(67890))
        chkExpr("#0.abs()", errRt("abs:integer:overflow:-9223372036854775808"), vInt(Long.MIN_VALUE))
    }

    @Test fun testLibIntegerMinMax() {
        chkExpr("#0.min(#1)", "int[123]", vInt(123), vInt(456))
        chkExpr("#0.min(#1)", "int[123]", vInt(456), vInt(123))
        chkExpr("#0.max(#1)", "int[456]", vInt(123), vInt(456))
        chkExpr("#0.max(#1)", "int[456]", vInt(456), vInt(123))
        chkExpr("#0.min(#1)", "dec[123]", vInt(123), vDec("456"))
        chkExpr("#0.min(#1)", "dec[123]", vInt(456), vDec("123"))
        chkExpr("#0.max(#1)", "dec[456]", vInt(123), vDec("456"))
        chkExpr("#0.max(#1)", "dec[456]", vInt(456), vDec("123"))
    }

    @Test fun testLibIntegerSign() {
        chkExpr("#0.sign()", "int[0]", vInt(0))
        chkExpr("#0.sign()", "int[1]", vInt(12345))
        chkExpr("#0.sign()", "int[-1]", vInt(-12345))
    }

    @Test fun testLibTextCharAt() {
        chkExpr("#0.char_at(#1)", "int[72]", vText("Hello"), vInt(0))
        chkExpr("#0.char_at(#1)", "int[101]", vText("Hello"), vInt(1))
        chkExpr("#0.char_at(#1)", "int[108]", vText("Hello"), vInt(2))
        chkExpr("#0.char_at(#1)", "int[108]", vText("Hello"), vInt(3))
        chkExpr("#0.char_at(#1)", "int[111]", vText("Hello"), vInt(4))
        //chkExpr("#0.char_at(#1)", "rt_err:fn:text.char_at:index:5:5", vText("Hello"), vInt(5))
        //chkExpr("#0.char_at(#1)", "rt_err:fn:text.char_at:index:5:-1", vText("Hello"), vInt(-1))
        chkExpr("#0.char_at(#1)", "int[32]", vText("Hello World"), vInt(5))
    }

    @Test fun testLibTextContains() {
        chkExpr("#0.contains('Hello')", "boolean[true]", vText("Hello"), vText("Hello"))
        chkExpr("#0.contains('ello')", "boolean[true]", vText("Hello"), vText("ello"))
        chkExpr("#0.contains('ll')", "boolean[true]", vText("Hello"), vText("ll"))
        chkExpr("#0.contains('lo')", "boolean[true]", vText("Hello"), vText("lo"))
        chkExpr("#0.contains('hello')", "boolean[false]", vText("Hello"), vText("hello"))
        chkExpr("#0.contains('L')", "boolean[false]", vText("Hello"), vText("L"))
        chkExpr("#0.contains('Hello1')", "boolean[false]", vText("Hello"), vText("Hello1"))
    }

    @Test fun testLibTextEmpty() {
        chkExpr("#0.empty()", "boolean[true]", vText(""))
        chkExpr("#0.empty()", "boolean[false]", vText(" "))
        chkExpr("#0.empty()", "boolean[false]", vText("X"))
        chkExpr("#0.empty()", "boolean[false]", vText("Hello"))
    }

    @Test fun testLibTextEndsWith() {
        chkExpr("#0.ends_with(#1)", "boolean[true]", vText("Hello"), vText("Hello"))
        chkExpr("#0.ends_with(#1)", "boolean[true]", vText("Hello"), vText("ello"))
        chkExpr("#0.ends_with(#1)", "boolean[true]", vText("Hello"), vText("o"))
        chkExpr("#0.ends_with(#1)", "boolean[true]", vText("Hello"), vText(""))
        chkExpr("#0.ends_with(#1)", "boolean[false]", vText("Hello"), vText("hello"))
        chkExpr("#0.ends_with(#1)", "boolean[false]", vText("Hello"), vText("XHello"))
    }

    @Test fun testLibTextIndexOf() {
        chkExpr("#0.index_of(#1)", "int[0]", vText("Hello"), vText("Hello"))
        chkExpr("#0.index_of(#1)", "int[1]", vText("Hello"), vText("ello"))
        chkExpr("#0.index_of(#1)", "int[2]", vText("Hello"), vText("ll"))
        chkExpr("#0.index_of(#1)", "int[2]", vText("Hello"), vText("l"))
        chkExpr("#0.index_of(#1)", "int[3]", vText("Hello"), vText("lo"))
        chkExpr("#0.index_of(#1)", "int[-1]", vText("Hello"), vText("hello"))
        chkExpr("#0.index_of(#1)", "int[-1]", vText("Hello"), vText("L"))
    }

    @Test fun testLibTextReplace() {
        chkExpr("#0.replace(#1, #2)", "text[Bye World]", vText("Hello World"), vText("Hello"), vText("Bye"))
        chkExpr("#0.replace(#1, #2)", "text[Hell0 W0rld]", vText("Hello World"), vText("o"), vText("0"))
        chkExpr("#0.replace(#1, #2)", "text[Hello World]", vText("Hello World"), vText("Bye"), vText("Tschus"))
    }

    @Test fun testLibTextSize() {
        chkExpr("#0.size()", "int[0]", vText(""))
        chkExpr("#0.size()", "int[1]", vText(" "))
        chkExpr("#0.size()", "int[1]", vText("X"))
        chkExpr("#0.size()", "int[5]", vText("Hello"))
    }

    @Test fun testLibTextStartsWith() {
        chkExpr("#0.starts_with(#1)", "boolean[true]", vText("Hello"), vText("Hello"))
        chkExpr("#0.starts_with(#1)", "boolean[true]", vText("Hello"), vText("Hell"))
        chkExpr("#0.starts_with(#1)", "boolean[true]", vText("Hello"), vText("H"))
        chkExpr("#0.starts_with(#1)", "boolean[true]", vText("Hello"), vText(""))
        chkExpr("#0.starts_with(#1)", "boolean[false]", vText("Hello"), vText("hello"))
        chkExpr("#0.starts_with(#1)", "boolean[false]", vText("Hello"), vText("Hellou"))
    }

    @Test fun testLibTextSub() {
        chkExpr("#0.sub(#1)", "text[World]", vText("Hello World"), vInt(6))
        chkExpr("#0.sub(#1)", "text[]", vText("Hello World"), vInt(11))
        chkExpr("#0.sub(#1)", "text[Hello World]", vText("Hello World"), vInt(0))
        //chkExpr("#0.sub(#1)", "rt_err:fn:text.sub:range:11:12:11", vText("Hello World"), vInt(12))
        chkExpr("#0.sub(#1, #2)", "text[Wor]", vText("Hello World"), vInt(6), vInt(9))
        chkExpr("#0.sub(#1, #2)", "text[]", vText("Hello World"), vInt(6), vInt(6))
        chkExpr("#0.sub(#1, #2)", "text[World]", vText("Hello World"), vInt(6), vInt(11))
        //chkExpr("#0.sub(#1, #2)", "rt_err:fn:text.sub:range:11:6:12", vText("Hello World"), vInt(6), vInt(12))
        chkExpr("#0.sub(#1, #2)", errRt("fn:text.sub:range:11:6:5"), vText("Hello World"), vInt(6), vInt(5))
        chkExpr("#0.sub(#1, #2)", "text[Hello World]", vText("Hello World"), vInt(0), vInt(11))
    }

    @Test fun testLibTextTrim() {
        chkExpr("#0.trim()", "text[Hello]", vText("Hello"))
        chkExpr("#0.trim()", "text[Hello]", vText("  Hello   "))
        chkExpr("#0.trim()", "text[Hello]", vText("  \t\t   Hello   \t  "))
        chkExpr("#0.trim()", "text[Hello]", vText(" \n\t\r\n Hello \n\r\t "))
    }

    @Test fun testLibDecimalAbs() {
        chkExpr("#0.abs()", "dec[0]", vDec("0"))
        chkExpr("#0.abs()", "dec[12345]", vDec("-12345"))
        chkExpr("#0.abs()", "dec[67890]", vDec("67890"))
        chkExpr("#0.abs()", "dec[9223372036854775808]", vDec("-9223372036854775808"))
        chkExpr("#0.abs()", "dec[123000000000000000000000000000000]", vDec("-123E30"))
    }

    @Test fun testLibDecimalMinMax() {
        chkExpr("#0.min(#1)", "dec[123]", vDec(123), vDec(456))
        chkExpr("#0.min(#1)", "dec[123]", vDec(456), vDec(123))
        chkExpr("#0.max(#1)", "dec[456]", vDec(123), vDec(456))
        chkExpr("#0.max(#1)", "dec[456]", vDec(456), vDec(123))
        chkExpr("#0.min(#1)", "dec[123]", vDec(123), vInt(456))
        chkExpr("#0.min(#1)", "dec[123]", vDec(456), vInt(123))
        chkExpr("#0.max(#1)", "dec[456]", vDec(123), vInt(456))
        chkExpr("#0.max(#1)", "dec[456]", vDec(456), vInt(123))
        chkExpr("#0.min(#1)", "dec[456000000000000000000000000000000]", vDec("123E31"), vDec("456E30"))
        chkExpr("#0.min(#1)", "dec[456000000000000000000000000000000]", vDec("456E30"), vDec("123E31"))
        chkExpr("#0.max(#1)", "dec[1230000000000000000000000000000000]", vDec("123E31"), vDec("456E30"))
        chkExpr("#0.max(#1)", "dec[1230000000000000000000000000000000]", vDec("456E30"), vDec("123E31"))
        chkExpr("#0.min(#1)", "dec[456]", vDec("123E31"), vInt(456))
        chkExpr("#0.max(#1)", "dec[1230000000000000000000000000000000]", vDec("123E31"), vInt(456))
    }

    @Test fun testLibDecimalConstructorText() {
        chkDecimalConstructor("0", "dec[0]")
        chkDecimalConstructor("12345", "dec[12345]")
        chkDecimalConstructor("10000", "dec[10000]")
        chkDecimalConstructor("1000000000000", "dec[1000000000000]")
        chkDecimalConstructor("0000", "dec[0]")
        chkDecimalConstructor("0001", "dec[1]")

        chkDecimalConstructor("-0", "dec[0]")
        chkDecimalConstructor("-1", "dec[-1]")
        chkDecimalConstructor("-12345689", "dec[-12345689]")
        chkDecimalConstructor("-0001", "dec[-1]")
        chkDecimalConstructor("+0", "dec[0]")
        chkDecimalConstructor("+1", "dec[1]")
        chkDecimalConstructor("+12345689", "dec[12345689]")
        chkDecimalConstructor("+0001", "dec[1]")

        chkDecimalConstructor("123E0", "dec[123]")
        chkDecimalConstructor("123E5", "dec[12300000]")
        chkDecimalConstructor("123E+5", "dec[12300000]")
        chkDecimalConstructor("123e5", "dec[12300000]")
        chkDecimalConstructor("123e+5", "dec[12300000]")
        chkDecimalConstructor("123E15", "dec[123000000000000000]")
        chkDecimalConstructor("123E-1", "dec[12.3]")
        chkDecimalConstructor("123E-2", "dec[1.23]")
        chkDecimalConstructor("123E-3", "dec[0.123]")
        chkDecimalConstructor("123E-10", "dec[0.0000000123]")
        chkDecimalConstructor("123e-10", "dec[0.0000000123]")
        chkDecimalConstructor("-123E-10", "dec[-0.0000000123]")
        chkDecimalConstructor("+123e-10", "dec[0.0000000123]")

        chkDecimalConstructor("123.456", "dec[123.456]")
        chkDecimalConstructor("123456E-3", "dec[123.456]")
        chkDecimalConstructor(".1", "dec[0.1]")
        chkDecimalConstructor(".000000000001", "dec[0.000000000001]")
        chkDecimalConstructor("-.1", "dec[-0.1]")
        chkDecimalConstructor("-.000000000001", "dec[-0.000000000001]")
        chkDecimalConstructor("+.1", "dec[0.1]")
        chkDecimalConstructor("+.000000000001", "dec[0.000000000001]")
        chkDecimalConstructor("0.0", "dec[0]")
        chkDecimalConstructor("0.00000", "dec[0]")
        chkDecimalConstructor("1.0", "dec[1]")
        chkDecimalConstructor("1.00000", "dec[1]")

        chkExpr("decimal(false)", "ct_err:expr_call_argtypes:decimal:boolean")
        chkDecimalConstructor("", errRt("decimal:invalid:"))
        chkDecimalConstructor("Hello", errRt("decimal:invalid:Hello"))
        chkDecimalConstructor("0x1234", errRt("decimal:invalid:0x1234"))
    }

    @Test fun testLibDecimalConstructorTextRounding() {
        val rep0 = DecimalTest.fracBase("0")
        val rep9 = DecimalTest.fracBase("9")

        chkDecimalConstructor("0.${rep9}90", "dec[0.${rep9}9]")
        chkDecimalConstructor("0.${rep9}91", "dec[0.${rep9}91]")
        chkDecimalConstructor("0.${rep9}99", "dec[0.${rep9}99]")
        chkDecimalConstructor("0.${rep9}990", "dec[0.${rep9}99]")
        chkDecimalConstructor("0.${rep9}991", "dec[0.${rep9}99]")
        chkDecimalConstructor("0.${rep9}992", "dec[0.${rep9}99]")
        chkDecimalConstructor("0.${rep9}993", "dec[0.${rep9}99]")
        chkDecimalConstructor("0.${rep9}994", "dec[0.${rep9}99]")
        chkDecimalConstructor("0.${rep9}995", "dec[1]")
        chkDecimalConstructor("0.${rep9}996", "dec[1]")
        chkDecimalConstructor("0.${rep9}997", "dec[1]")
        chkDecimalConstructor("0.${rep9}998", "dec[1]")
        chkDecimalConstructor("0.${rep9}999", "dec[1]")

        chkDecimalConstructor("0.${rep0}100", "dec[0.${rep0}1]")
        chkDecimalConstructor("0.${rep0}101", "dec[0.${rep0}1]")
        chkDecimalConstructor("0.${rep0}104", "dec[0.${rep0}1]")
        chkDecimalConstructor("0.${rep0}105", "dec[0.${rep0}11]")
        chkDecimalConstructor("0.${rep0}109", "dec[0.${rep0}11]")
        chkDecimalConstructor("0.${rep0}194", "dec[0.${rep0}19]")
        chkDecimalConstructor("0.${rep0}195", "dec[0.${rep0}2]")

        val fracExp = C_Constants.DECIMAL_FRAC_DIGITS - 2
        chkDecimalConstructor("0.100e-$fracExp", "dec[0.${rep0}1]")
        chkDecimalConstructor("0.101e-$fracExp", "dec[0.${rep0}1]")
        chkDecimalConstructor("0.104e-$fracExp", "dec[0.${rep0}1]")
        chkDecimalConstructor("0.105e-$fracExp", "dec[0.${rep0}11]")
        chkDecimalConstructor("0.109e-$fracExp", "dec[0.${rep0}11]")
        chkDecimalConstructor("0.194e-$fracExp", "dec[0.${rep0}19]")
        chkDecimalConstructor("0.195e-$fracExp", "dec[0.${rep0}2]")
    }

    @Test fun testLibDecimalConstructorTextOverflow() {
        val limitMinusOne = DecimalTest.limitMinus(1)
        val overLimit = "" + (DecimalTest.LIMIT * BigInteger.valueOf(3))
        val fracLimit = BigInteger.TEN.pow(C_Constants.DECIMAL_FRAC_DIGITS) - BigInteger.ONE
        val fracLimitMinusOne = fracLimit - BigInteger.ONE

        chkDecimalConstructor("$limitMinusOne", "dec[$limitMinusOne]")
        chkDecimalConstructor("-$limitMinusOne", "dec[-$limitMinusOne]")
        chkDecimalConstructor("${DecimalTest.LIMIT}", errRt("decimal:overflow"))
        chkDecimalConstructor("-${DecimalTest.LIMIT}", errRt("decimal:overflow"))
        chkDecimalConstructor("$overLimit", errRt("decimal:overflow"))
        chkDecimalConstructor("-$overLimit", errRt("decimal:overflow"))
        chkDecimalConstructor("$limitMinusOne.$fracLimit", "dec[$limitMinusOne.$fracLimit]")
        chkDecimalConstructor("-$limitMinusOne.$fracLimit", "dec[-$limitMinusOne.$fracLimit]")
        chkDecimalConstructor("$limitMinusOne.${fracLimit}9", errRt("decimal:overflow"))
        chkDecimalConstructor("-$limitMinusOne.${fracLimit}9", errRt("decimal:overflow"))
        chkDecimalConstructor("$limitMinusOne.${fracLimitMinusOne}9", "dec[$limitMinusOne.${fracLimit}]")
        chkDecimalConstructor("-$limitMinusOne.${fracLimitMinusOne}9", "dec[-$limitMinusOne.${fracLimit}]")
    }

    private fun chkDecimalConstructor(s: String, exp: String) {
        chkExpr("decimal(#0)", exp, vText(s))
    }

    @Test fun testLibDecimalConstructorInteger() {
        chkExpr("decimal(#0)", "dec[0]", vInt(0))
        chkExpr("decimal(#0)", "dec[1]", vInt(1))
        chkExpr("decimal(#0)", "dec[-1]", vInt(-1))
        chkExpr("decimal(#0)", "dec[9223372036854775807]", vInt(9223372036854775807))
        chkExpr("decimal(#0)", "dec[-9223372036854775808]", vInt("-9223372036854775808".toLong()))
    }

    @Test fun testLibDecimalCeil() {
        chkExpr("decimal(#0).ceil()", "dec[0]", vText("0"))
        chkExpr("decimal(#0).ceil()", "dec[1]", vText("0.001"))
        chkExpr("decimal(#0).ceil()", "dec[1]", vText("0.999"))
        chkExpr("decimal(#0).ceil()", "dec[123]", vText("123"))
        chkExpr("decimal(#0).ceil()", "dec[124]", vText("123.0000000000001"))
        chkExpr("decimal(#0).ceil()", "dec[124]", vText("123.9999999999999"))
        chkExpr("decimal(#0).ceil()", "dec[124]", vText("124.0000000000000"))
        chkExpr("decimal(#0).ceil()", "dec[0]", vText("-0.001"))
        chkExpr("decimal(#0).ceil()", "dec[0]", vText("-0.999"))
        chkExpr("decimal(#0).ceil()", "dec[-1]", vText("-1.000"))
        chkExpr("decimal(#0).ceil()", "dec[-1]", vText("-1.999"))

        val maxIntPart = "9".repeat(C_Constants.DECIMAL_INT_DIGITS)
        chkExpr("#0.ceil()", errRt("decimal:overflow"), vDec(C_Constants.DECIMAL_MAX_VALUE))
        chkExpr("(-#0).ceil()", "dec[-$maxIntPart]", vDec(C_Constants.DECIMAL_MAX_VALUE))
        chkExpr("#0.ceil()", "dec[1]", vDec(C_Constants.DECIMAL_MIN_VALUE))
        chkExpr("(-#0).ceil()", "dec[0]", vDec(C_Constants.DECIMAL_MIN_VALUE))
    }

    @Test fun testLibDecimalFloor() {
        chkExpr("decimal(#0).floor()", "dec[0]", vText("0"))
        chkExpr("decimal(#0).floor()", "dec[0]", vText("0.001"))
        chkExpr("decimal(#0).floor()", "dec[0]", vText("0.999"))
        chkExpr("decimal(#0).floor()", "dec[1]", vText("1"))
        chkExpr("decimal(#0).floor()", "dec[1]", vText("1.00000"))
        chkExpr("decimal(#0).floor()", "dec[1]", vText("1.99999"))
        chkExpr("decimal(#0).floor()", "dec[2]", vText("2"))
        chkExpr("decimal(#0).floor()", "dec[123]", vText("123"))
        chkExpr("decimal(#0).floor()", "dec[123]", vText("123.0000000000001"))
        chkExpr("decimal(#0).floor()", "dec[123]", vText("123.9999999999999"))
        chkExpr("decimal(#0).floor()", "dec[124]", vText("124.0000000000000"))
        chkExpr("decimal(#0).floor()", "dec[-1]", vText("-0.001"))
        chkExpr("decimal(#0).floor()", "dec[-1]", vText("-0.999"))
        chkExpr("decimal(#0).floor()", "dec[-1]", vText("-1.000"))
        chkExpr("decimal(#0).floor()", "dec[-2]", vText("-1.0000000000001"))

        val maxIntPart = "9".repeat(C_Constants.DECIMAL_INT_DIGITS)
        chkExpr("#0.floor()", "dec[$maxIntPart]", vDec(C_Constants.DECIMAL_MAX_VALUE))
        chkExpr("(-#0).floor()", errRt("decimal:overflow"), vDec(C_Constants.DECIMAL_MAX_VALUE))
        chkExpr("#0.floor()", "dec[0]", vDec(C_Constants.DECIMAL_MIN_VALUE))
        chkExpr("(-#0).floor()", "dec[-1]", vDec(C_Constants.DECIMAL_MIN_VALUE))
    }

    @Test fun testLibDecimalRound() {
        chkExpr("decimal(#0).round()", "dec[0]", vText("0"))
        chkExpr("decimal(#0).round()", "dec[0]", vText("0.1"))
        chkExpr("decimal(#0).round()", "dec[0]", vText("0.4"))
        chkExpr("decimal(#0).round()", "dec[1]", vText("0.5"))
        chkExpr("decimal(#0).round()", "dec[1]", vText("0.9"))

        chkExpr("decimal(#0).round()", "dec[123456]", vText("123456.1"))
        chkExpr("decimal(#0).round()", "dec[123456]", vText("123456.4"))
        chkExpr("decimal(#0).round()", "dec[123457]", vText("123456.5"))
        chkExpr("decimal(#0).round()", "dec[123457]", vText("123456.9"))
        chkExpr("decimal(#0).round()", "dec[-123456]", vText("-123456.1"))
        chkExpr("decimal(#0).round()", "dec[-123456]", vText("-123456.4"))
        chkExpr("decimal(#0).round()", "dec[-123457]", vText("-123456.5"))
        chkExpr("decimal(#0).round()", "dec[-123457]", vText("-123456.9"))

        chkExpr("#0.round()", errRt("decimal:overflow"), vDec(C_Constants.DECIMAL_MAX_VALUE))
        chkExpr("(-#0).round()", errRt("decimal:overflow"), vDec(C_Constants.DECIMAL_MAX_VALUE))
        chkExpr("#0.round()", "dec[0]", vDec(C_Constants.DECIMAL_MIN_VALUE))
        chkExpr("(-#0).round()", "dec[0]", vDec(C_Constants.DECIMAL_MIN_VALUE))
    }

    @Test fun testLibDecimalRoundScalePositive() {
        chkRoundScale("123456.1", 0, "123456")
        chkRoundScale("123456.4", 0, "123456")
        chkRoundScale("123456.5", 0, "123457")
        chkRoundScale("123456.9", 0, "123457")

        chkRoundScale("123456.789", 0, "123457")
        chkRoundScale("123456.789", 1, "123456.8")
        chkRoundScale("123456.789", 2, "123456.79")
        chkRoundScale("123456.789", 3, "123456.789")

        chkRoundScale("123.41", 1, "123.4")
        chkRoundScale("123.44", 1, "123.4")
        chkRoundScale("123.45", 1, "123.5")
        chkRoundScale("123.49", 1, "123.5")

        chkRoundScale("123.451", 2, "123.45")
        chkRoundScale("123.454", 2, "123.45")
        chkRoundScale("123.455", 2, "123.46")
        chkRoundScale("123.459", 2, "123.46")

        chkRoundScale("123.01234567891", 10, "123.0123456789")
        chkRoundScale("123.01234567894", 10, "123.0123456789")
        chkRoundScale("123.01234567895", 10, "123.012345679")
        chkRoundScale("123.01234567899", 10, "123.012345679")

        chkRoundScale("123.456", 100, "123.456")
        chkRoundScale("123.456", 1000000, "123.456")
        chkRoundScale("123.456", 1000000000, "123.456")
        chkRoundScale("123.98765432100123456789", 100, "123.98765432100123456789")
        chkRoundScale("123.98765432100123456789", 1000000, "123.98765432100123456789")
        chkRoundScale("123.98765432100123456789", 1000000000, "123.98765432100123456789")
    }

    @Test open fun testLibDecimalRoundScaleNegative() {
        chkRoundScale("123456.789", 0, "123457")
        chkRoundScale("123456.789", -1, "123460")
        chkRoundScale("123456.789", -2, "123500")
        chkRoundScale("123456.789", -3, "123000")
        chkRoundScale("123456.789", -4, "120000")
        chkRoundScale("123456.789", -5, "100000")
        chkRoundScale("123456.789", -6, "0")
        chkRoundScale("123456.789", -7, "0")
        chkRoundScale("123456.789", -100, "0")
        chkRoundScale("123456.789", -1000000, "0")
        chkRoundScale("123456.789", -1000000000, "0")

        chkRoundScale("1231", -1, "1230")
        chkRoundScale("1234", -1, "1230")
        chkRoundScale("1235", -1, "1240")
        chkRoundScale("1239", -1, "1240")

        chkRoundScale("1211", -2, "1200")
        chkRoundScale("1242", -2, "1200")
        chkRoundScale("1253", -2, "1300")
        chkRoundScale("1294", -2, "1300")

        chkRoundScale("2101", -3, "2000")
        chkRoundScale("2402", -3, "2000")
        chkRoundScale("2503", -3, "3000")
        chkRoundScale("2904", -3, "3000")

        chkRoundScale("1234", -4, "0")
        chkRoundScale("4234", -4, "0")
        chkRoundScale("5234", -4, "10000")
        chkRoundScale("9234", -4, "10000")

        chkLibDecimalRoundScaleNegativeZeros(99)
        chkLibDecimalRoundScaleNegativeZeros(999)
        chkLibDecimalRoundScaleNegativeZeros(1999)

        // TODO Rounding in PostgreSQL is wrong for zeros = 2000+, do something about that.
        //chkLibDecimalRoundScaleNegativeZeros(2000)
        //chkLibDecimalRoundScaleNegativeZeros(9999)
    }

    private fun chkLibDecimalRoundScaleNegativeZeros(zeros: Int) {
        val zs = "0".repeat(zeros)
        chkRoundScale("1231$zs", -zeros-1, "1230$zs")
        chkRoundScale("1234$zs", -zeros-1, "1230$zs")
        chkRoundScale("1235$zs", -zeros-1, "1240$zs")
        chkRoundScale("1239$zs", -zeros-1, "1240$zs")
    }

    private fun chkRoundScale(v: String, scale: Int, expected: String) {
        val expectedNeg = if (expected == "0") "0" else "-$expected"
        chkExpr("decimal(#0).round($scale)", "dec[$expected]", vText("$v"))
        chkExpr("decimal(#0).round($scale)", "dec[$expectedNeg]", vText("-$v"))
    }

    @Test fun testLibDecimalSign() {
        chkExpr("#0.sign()", "int[0]", vDec("0"))
        chkExpr("#0.sign()", "int[1]", vDec("12345"))
        chkExpr("#0.sign()", "int[-1]", vDec("-12345"))
        chkExpr("#0.sign()", "int[1]", vDec("123456789101112131415161718192021222324252627282930"))
        chkExpr("#0.sign()", "int[-1]", vDec("-123456789101112131415161718192021222324252627282930"))
    }

    @Test open fun testLibDecimalToInteger() {
        chkExpr("decimal(#0).to_integer()", "int[0]", vText("0"))
        chkExpr("decimal(#0).to_integer()", "int[123]", vText("123.456"))
        chkExpr("decimal(#0).to_integer()", "int[123]", vText("123.999"))
        chkExpr("decimal(#0).to_integer()", "int[-123]", vText("-123.456"))
        chkExpr("decimal(#0).to_integer()", "int[-123]", vText("-123.999"))
        chkExpr("decimal(#0).to_integer()", "int[9223372036854775807]", vText("9223372036854775807"))
        chkExpr("decimal(#0).to_integer()", "int[9223372036854775807]", vText("9223372036854775807.999999999"))
        chkExpr("decimal(#0).to_integer()", errRt("decimal.to_integer:overflow:9223372036854775808"), vText("9223372036854775808"))
        chkExpr("decimal(#0).to_integer()", "int[-9223372036854775808]", vText("-9223372036854775808"))
        chkExpr("decimal(#0).to_integer()", "int[-9223372036854775808]", vText("-9223372036854775808.999999999"))
        chkExpr("decimal(#0).to_integer()", errRt("decimal.to_integer:overflow:-9223372036854775809"), vText("-9223372036854775809"))
        chkExpr("#0.to_integer()", "int[0]", vDec(C_Constants.DECIMAL_MIN_VALUE))
        chkExpr("(-#0).to_integer()", "int[0]", vDec(C_Constants.DECIMAL_MIN_VALUE))
        chkExpr("#0.to_integer()", errRt("decimal.to_integer:overflow:9.9999999999999999999E+131071"), vDec(C_Constants.DECIMAL_MAX_VALUE))
        chkExpr("(-#0).to_integer()", errRt("decimal.to_integer:overflow:-9.9999999999999999999E+131071"), vDec(C_Constants.DECIMAL_MAX_VALUE))
    }

    @Test open fun testLibDecimalToText() {
        chkExpr("decimal(#0).to_text()", "text[0]", vText("0"))
        chkExpr("decimal(#0).to_text()", "text[123.456]", vText("123.456"))
        chkExpr("decimal(#0).to_text()", "text[-123.456]", vText("-123.456"))
        chkExpr("decimal(#0).to_text()", "text[1234000000000000000000]", vText("12.34e20"))
        chkExpr("decimal(#0).to_text()", "text[-1234000000000000000000]", vText("-12.34e20"))
        chkExpr("decimal(#0).to_text()", "text[0.00000000000000001234]", vText("12.34e-18"))
        chkExpr("decimal(#0).to_text()", "text[-0.00000000000000001234]", vText("-12.34e-18"))
        chkExpr("'' + decimal(#0)", "text[123.456]", vText("123.456"))
    }

    private fun chkOpErr(expr: String) {
        val (left, op, right) = expr.split(" ")
        chkOpErr(op, left, right)
    }

    private fun chkOpErr(op: String, left: String, right: String) {
        chkExprErr("#0 $op #1", listOf(left, right), "ct_err:binop_operand_type:$op:[$left]:[$right]")
    }

    private fun chkOpErr(op: String, right: String) {
        chkExprErr("$op #0", listOf(right), "ct_err:unop_operand_type:$op:[$right]")
    }

    private fun chkExprErr(expr: String, types: List<String>, expected: String) {
        val actual = compileExpr(expr, types)
        assertEquals(expected, actual)
    }

    private fun chkOpBool(op: String, left: TstVal, right: TstVal, expected: Boolean) {
        chkExpr("#0 $op #1", listOf(left, right), expected)
    }

    private fun chkOpBool(op: String, right: TstVal, expected: Boolean) {
        chkExpr("$op #0", listOf(right), expected)
    }

    fun chkOp(op: String, left: TstVal, right: TstVal, expected: String) {
        chkExpr("#0 $op #1", expected, left, right)
    }

    fun chkOp(op: String, right: TstVal, expected: String) {
        chkExpr("$op #0", expected, right)
    }

    private fun chkExpr(expr: String, expected: String, vararg args: TstVal) {
        val actual = calcExpr(expr, args.toList())
        assertEquals(expected, actual)
    }

    abstract fun chkExpr(expr: String, args: List<TstVal>, expected: Boolean)
    abstract fun calcExpr(expr: String, args: List<TstVal>): String
    abstract fun compileExpr(expr: String, types: List<String>): String

    abstract fun errRt(code: String): String

    abstract fun vBool(v: Boolean): TstVal
    abstract fun vInt(v: Long): TstVal
    abstract fun vDec(v: BigDecimal): TstVal
    abstract fun vText(v: String): TstVal
    abstract fun vBytes(v: String): TstVal
    abstract fun vRowid(v: Long): TstVal
    abstract fun vJson(v: String): TstVal
    abstract fun vObj(ent: String, id: Long): TstVal

    fun vDec(s: String) = vDec(parseDec(s))
    fun vDec(v: Long) = vDec(BigDecimal(v))

    private fun parseDec(s: String): BigDecimal {
        var v = BigDecimal.ZERO
        for (p in s.split("+")) {
            v += BigDecimal(p)
        }
        return v
    }

    companion object {
        private val DV = DecimalTest.DecVals()
    }

    abstract class TstVal
}
