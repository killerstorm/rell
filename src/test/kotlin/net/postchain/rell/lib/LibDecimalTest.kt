/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.compiler.base.utils.C_Constants
import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LibDecimalTest: BaseRellTest(false) {
    @Test fun testConstants() {
        chk("decimal.PRECISION", "int[131092]")
        chk("decimal.SCALE", "int[20]")
        chk("decimal.INT_DIGITS", "int[131072]")
        chk("decimal.MIN_VALUE", "dec[0.00000000000000000001]")

        val expMax = "9".repeat(C_Constants.DECIMAL_INT_DIGITS) + "." + "9".repeat(C_Constants.DECIMAL_FRAC_DIGITS)
        chk("decimal.MAX_VALUE", "dec[$expMax]")
    }

    //TODO move decimal library tests from OperatorsBaseTest here (need to support executing same test as interpreted and DB)

    //TODO support pow(), or delete the function and the test (if supporting, add non-integer power test cases to the test)
    /*@Test*/ fun testPow() {
        chk("decimal('2').pow(16)", "dec[65536]")
        chk("decimal('2').pow(decimal('4'))", "ct_err:expr_call_argtypes:decimal.pow:decimal")
        chk("decimal('2').pow(decimal('0.5'))", "ct_err:expr_call_argtypes:decimal.pow:decimal")
        chk("decimal('2').pow(-1)", "rt_err:decimal.pow:negative_power:-1")

        chk("decimal('0').pow(0)", "dec[1]")
        chk("decimal('1').pow(0)", "dec[1]")
        chk("decimal('0').pow(1)", "dec[0]")

        chk("decimal('2').pow(4)", "dec[16]")
        chk("decimal('2').pow(16)", "dec[65536]")
        chk("decimal('2').pow(256)", "dec[340282366920938463463374607431768211456]")
        chk("decimal('123456789').pow(5)", "dec[28679718602997181072337614380936720482949]")

        chk("decimal('123.456').pow(2)", "dec[15241.383936]")
        chk("decimal('123.456').pow(3)", "dec[1881640.295202816]")
        chk("decimal('123.456').pow(4)", "dec[232299784.284558852096]")
        chk("decimal('123.456').pow(5)", "dec[28678802168.634497644363776]")
        chk("decimal('123.456').pow(6)", "dec[3540570200530.940541182574329856]")
        chk("decimal('123.456').pow(7)", "dec[437104634676747.79545223589646670234]")
        chk("decimal('123.456').pow(8)", "dec[53963189778652575.83535123483419320359]")
        chk("decimal('123.456').pow(10)", "dec[822473693827674765061.94409151963591676602]")

        chk("decimal('10').pow(${C_Constants.DECIMAL_INT_DIGITS-1})", "...")
        chk("decimal('10').pow(${C_Constants.DECIMAL_INT_DIGITS})", "*error*")
        chk("decimal.MAX_VALUE.pow(1)", "...")
        chk("decimal.MAX_VALUE.pow(2)", "*error*")
        chk("decimal.MAX_VALUE.pow(100)", "*error*")
        chk("decimal.MAX_VALUE.pow(1000000)", "*error*")
        chk("decimal.MAX_VALUE.pow(1000000000000)", "*error*")
    }

    //TODO support decimal.sqrt() on Java 9
    /*@Test*/ fun testSqrt() {
        chk("decimal('0').sqrt()", "dec[0]")
        chk("decimal('1').sqrt()", "dec[1]")
        chk("decimal('0.01').sqrt()", "dec[0.1]")
        chk("decimal('-1').sqrt()", "*error*")
        chk("decimal('-123').sqrt()", "*error*")

        chk("decimal('4').sqrt()", "dec[2]")
        chk("decimal('64').sqrt()", "dec[8]")
        chk("decimal('65536').sqrt()", "dec[256]")
        chk("decimal('4294967296').sqrt()", "dec[65536]")
        chk("decimal('18446744073709551616').sqrt()", "dec[4294967296]")

        chk("decimal('2').sqrt()", "dec[1.4142135623730950488]")
        chk("decimal('3').sqrt()", "dec[1.7320508075688772935]")
        chk("decimal('123').sqrt()", "dec[11.09053650640941716205]")
        chk("decimal('123456').sqrt()", "dec[351.36306009596398663933]")
        chk("decimal('1234567891011121314151618192021222324252627282930').sqrt()", "dec[1111111106510560137399502.57419163236096335087]")
    }

    @Test fun testToTextScientific() {
        chk("decimal('0').to_text(false)", "text[0]")
        chk("decimal('0').to_text(true)", "text[0]")
        chk("decimal('123.456').to_text(false)", "text[123.456]")
        chk("decimal('123.456').to_text(true)", "text[123.456]")
        chk("decimal('-123.456').to_text(false)", "text[-123.456]")
        chk("decimal('-123.456').to_text(true)", "text[-123.456]")

        chk("decimal('12.34e20').to_text(false)", "text[1234000000000000000000]")
        chk("decimal('12.34e20').to_text(true)", "text[1.234E+21]")
        chk("decimal('-12.34e20').to_text(false)", "text[-1234000000000000000000]")
        chk("decimal('-12.34e20').to_text(true)", "text[-1.234E+21]")
        chk("decimal('12.34e500').to_text(false)", "text[1234${"0".repeat(498)}]")
        chk("decimal('12.34e500').to_text(true)", "text[1.234E+501]")

        val p = C_Constants.DECIMAL_INT_DIGITS - 3
        chk("decimal('123.45678910111213141516e$p').to_text(false)", "text[12345678910111213141516${"0".repeat(p-20)}]")
        chk("decimal('123.45678910111213141516e$p').to_text(true)", "text[1.2345678910111213141516E+${p+2}]")

        chk("decimal('12.34e-18').to_text(false)", "text[0.00000000000000001234]")
        chk("decimal('12.34e-18').to_text(true)", "text[1.234E-17]")
        chk("decimal('-12.34e-18').to_text(false)", "text[-0.00000000000000001234]")
        chk("decimal('-12.34e-18').to_text(true)", "text[-1.234E-17]")

        val t = "0123456789"
        val f = t.repeat(3)
        chk("decimal('1.${f}').to_text(false)", "text[1.${t}${t}]")
        chk("decimal('1.${f}').to_text(true)", "text[1.${t}${t}]")
        chk("decimal('1.${f}e5').to_text(false)", "text[101234.56789${t}01235]")
        chk("decimal('1.${f}e5').to_text(true)", "text[101234.56789${t}01235]")
        chk("decimal('1.${f}e10').to_text(false)", "text[1${t}.${t}${t}]")
        chk("decimal('1.${f}e10').to_text(true)", "text[1${t}.${t}${t}]")
        chk("decimal('1.${f}e10').to_text(false)", "text[1${t}.${t}${t}]")
        chk("decimal('1.${f}e10').to_text(true)", "text[1${t}.${t}${t}]")
        chk("decimal('1.${f}e40').to_text(false)", "text[1${f}0000000000]")
        chk("decimal('1.${f}e40').to_text(true)", "text[1.${f}E+40]")
        chk("decimal('1.${f}e60').to_text(false)", "text[1${f}000000000000000000000000000000]")
        chk("decimal('1.${f}e60').to_text(true)", "text[1.${f}E+60]")
        chk("decimal('1.${f}${f}e60').to_text(false)", "text[1${f}${f}]")
        chk("decimal('1.${f}${f}e60').to_text(true)", "text[1${f}${f}]")
        chk("decimal('1.${f}${f}e70').to_text(false)", "text[1${f}${f}0000000000]")
        chk("decimal('1.${f}${f}e70').to_text(true)", "text[1.${f}${f}E+70]")
    }

    @Test fun testCreateUpdate() {
        tstCtx.useSql = true
        def("entity user { name; mutable value: decimal; }")

        val expMin = "0." + "0".repeat(C_Constants.DECIMAL_FRAC_DIGITS - 1) + "1"
        val expMax = "9".repeat(C_Constants.DECIMAL_INT_DIGITS) + "." + "9".repeat(C_Constants.DECIMAL_FRAC_DIGITS)

        chkOp("{ create user('Bob', decimal.MAX_VALUE); }")
        chk("(user @ { 'Bob' }).value", "dec[$expMax]")

        chkOp("{ create user('Alice', decimal.MIN_VALUE); }")
        chk("(user @ { 'Alice' }).value", "dec[$expMin]")

        chkOp("{ update user @ { 'Bob' } ( value = decimal.MIN_VALUE ); }")
        chk("(user @ { 'Bob' }).value", "dec[$expMin]")

        chkOp("{ update user @ { 'Alice' } ( value = decimal.MAX_VALUE ); }")
        chk("(user @ { 'Alice' }).value", "dec[$expMax]")

        chkOp("{ update user @ { 'Bob' } ( value += decimal.MIN_VALUE ); }")
        chk("(user @ { 'Bob' }).value", "dec[${expMin.replace('1', '2')}]")

        chkOp("{ update user @ { 'Alice' } ( value += decimal.MIN_VALUE ); }", "rt_err:sqlerr:0") // Overflow
    }
}
