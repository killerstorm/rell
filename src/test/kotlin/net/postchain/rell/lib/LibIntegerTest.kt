/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LibIntegerTest: BaseRellTest(false) {
    @Test fun testConstants() {
        chk("integer.MIN_VALUE", "int[-9223372036854775808]")
        chk("integer.MAX_VALUE", "int[9223372036854775807]")
    }

    @Test fun testConstructorDecimal() {
        chk("integer(decimal('0'))", "int[0]")
        chk("integer(decimal('123456'))", "int[123456]")
        chk("integer(decimal('-123456'))", "int[-123456]")
        chk("integer(decimal('123.456'))", "int[123]")
        chk("integer(decimal('-123.456'))", "int[-123]")
        chk("integer(decimal('123.789'))", "int[123]")
        chk("integer(decimal('-123.789'))", "int[-123]")
        chk("integer(decimal('9223372036854775807'))", "int[9223372036854775807]")
        chk("integer(decimal('9223372036854775808'))", "rt_err:decimal.to_integer:overflow:9223372036854775808")
        chk("integer(decimal('-9223372036854775808'))", "int[-9223372036854775808]")
        chk("integer(decimal('-9223372036854775809'))", "rt_err:decimal.to_integer:overflow:-9223372036854775809")
    }

    @Test fun testFromText() {
        chk("integer.from_text('0')", "int[0]")
        chk("integer.from_text('123456789')", "int[123456789]")
        chk("integer.from_text('9223372036854775807')", "int[9223372036854775807]")
        chk("integer.from_text('9223372036854775808')", "rt_err:fn:integer.from_text:9223372036854775808")
        chk("integer.from_text('-123456789')", "int[-123456789]")
        chk("integer.from_text('-9223372036854775808')", "int[-9223372036854775808]")
        chk("integer.from_text('-9223372036854775809')", "rt_err:fn:integer.from_text:-9223372036854775809")
        chk("integer.from_text('')", "rt_err:fn:integer.from_text:")
        chk("integer.from_text(' 123')", "rt_err:fn:integer.from_text: 123")
        chk("integer.from_text('123 ')", "rt_err:fn:integer.from_text:123 ")
        chk("integer.from_text('0123')", "int[123]")
        chk("integer.from_text('0x123')", "rt_err:fn:integer.from_text:0x123")
        chk("integer.from_text(123)", "ct_err:expr_call_argtypes:[integer.from_text]:integer")
        chk("integer.from_text('aaa')", "rt_err:fn:integer.from_text:aaa")
        chk("integer.from_text('123', 0)", "rt_err:fn:integer.from_text:radix:0")
        chk("integer.from_text('123', 1)", "rt_err:fn:integer.from_text:radix:1")
        chk("integer.from_text('123', 40)", "rt_err:fn:integer.from_text:radix:40")
        chk("integer.from_text('123', -1)", "rt_err:fn:integer.from_text:radix:-1")
    }

    @Test fun testFromText2() {
        chk("integer.from_text('0', 2)", "int[0]")
        chk("integer.from_text('1111011', 2)", "int[123]")
        chk("integer.from_text('11111111', 2)", "int[255]")
        chk("integer.from_text('111111111111111111111111111111111111111111111111111111111111111', 2)", "int[9223372036854775807]")
        chk("integer.from_text('-1', 2)", "int[-1]")
        chk("integer.from_text('-1000000000000000000000000000000000000000000000000000000000000000', 2)", "int[-9223372036854775808]")
        chk("integer.from_text('1000000000000000000000000000000000000000000000000000000000000000', 2)",
                "rt_err:fn:integer.from_text:1000000000000000000000000000000000000000000000000000000000000000")
        chk("integer.from_text('-1000000000000000000000000000000000000000000000000000000000000001', 2)",
                "rt_err:fn:integer.from_text:-1000000000000000000000000000000000000000000000000000000000000001")
    }

    @Test fun testFromText8() {
        chk("integer.from_text('0', 8)", "int[0]")
        chk("integer.from_text('173', 8)", "int[123]")
        chk("integer.from_text('377', 8)", "int[255]")
        chk("integer.from_text('777777777777777777777', 8)", "int[9223372036854775807]")
        chk("integer.from_text('-1', 8)", "int[-1]")
        chk("integer.from_text('-1000000000000000000000', 8)", "int[-9223372036854775808]")
        chk("integer.from_text('1000000000000000000000', 8)", "rt_err:fn:integer.from_text:1000000000000000000000")
        chk("integer.from_text('-1000000000000000000001', 8)", "rt_err:fn:integer.from_text:-1000000000000000000001")
    }

    @Test fun testFromText10() {
        chk("integer.from_text('0', 10)", "int[0]")
        chk("integer.from_text('123456789', 10)", "int[123456789]")
        chk("integer.from_text('9223372036854775807', 10)", "int[9223372036854775807]")
        chk("integer.from_text('9223372036854775808', 10)", "rt_err:fn:integer.from_text:9223372036854775808")
        chk("integer.from_text('-123456789', 10)", "int[-123456789]")
        chk("integer.from_text('-9223372036854775808', 10)", "int[-9223372036854775808]")
        chk("integer.from_text('-9223372036854775809', 10)", "rt_err:fn:integer.from_text:-9223372036854775809")
    }

    @Test fun testFromText16() {
        chk("integer.from_text('0', 16)", "int[0]")
        chk("integer.from_text('7b', 16)", "int[123]")
        chk("integer.from_text('ff', 16)", "int[255]")
        chk("integer.from_text('7fffffffffffffff', 16)", "int[9223372036854775807]")
        chk("integer.from_text('-1', 16)", "int[-1]")
        chk("integer.from_text('-8000000000000000', 16)", "int[-9223372036854775808]")
        chk("integer.from_text('8000000000000000', 16)", "rt_err:fn:integer.from_text:8000000000000000")
        chk("integer.from_text('-8000000000000001', 16)", "rt_err:fn:integer.from_text:-8000000000000001")
    }

    @Test fun testFromHex() {
        chk("integer.from_hex('0')", "int[0]")
        chk("integer.from_hex('7b')", "int[123]")
        chk("integer.from_hex('ff')", "int[255]")
        chk("integer.from_hex('7fffffffffffffff')", "int[9223372036854775807]")
        chk("integer.from_hex('ffffffffffffffff')", "int[-1]")
        chk("integer.from_hex('8000000000000000')", "int[-9223372036854775808]")
        chk("integer.from_hex('-1')", "rt_err:fn:integer.from_hex:-1")
        chk("integer.from_hex('10000000000000000')", "rt_err:fn:integer.from_hex:10000000000000000")
        chk("integer.from_hex()", "ct_err:expr_call_argtypes:[integer.from_hex]:")
        chk("integer.from_hex(123)", "ct_err:expr_call_argtypes:[integer.from_hex]:integer")
        chk("integer.from_hex('')", "rt_err:fn:integer.from_hex:")
        chk("integer.from_hex('ghi')", "rt_err:fn:integer.from_hex:ghi")
    }

    @Test fun testToDecimal() {
        chk("(0).to_decimal()", "dec[0]")
        chk("(123456).to_decimal()", "dec[123456]")
        chk("(9223372036854775807).to_decimal()", "dec[9223372036854775807]")
        chk("(-9223372036854775807-1).to_decimal()", "dec[-9223372036854775808]")
    }

    @Test fun testToHex() {
        chk("(0).to_hex()", "text[0]")
        chk("(123).to_hex()", "text[7b]")
        chk("(255).to_hex()", "text[ff]")
        chk("(9223372036854775807).to_hex()", "text[7fffffffffffffff]")
        chk("(-1).to_hex()", "text[ffffffffffffffff]")
        chk("(-9223372036854775807-1).to_hex()", "text[8000000000000000]")
    }

    @Test fun testToText() {
        chk("(0).to_text()", "text[0]")
        chk("(123).to_text()", "text[123]")
        chk("(255).to_text()", "text[255]")
        chk("(9223372036854775807).to_text()", "text[9223372036854775807]")
        chk("(-1).to_text()", "text[-1]")
        chk("(-9223372036854775807-1).to_text()", "text[-9223372036854775808]")
    }

    @Test fun testToText2() {
        chk("(0).to_text(2)", "text[0]")
        chk("(123).to_text(2)", "text[1111011]")
        chk("(255).to_text(2)", "text[11111111]")
        chk("(9223372036854775807).to_text(2)", "text[111111111111111111111111111111111111111111111111111111111111111]")
        chk("(-1).to_text(2)", "text[-1]")
        chk("(-9223372036854775807-1).to_text(2)", "text[-1000000000000000000000000000000000000000000000000000000000000000]")
    }

    @Test fun testToText8() {
        chk("(0).to_text(8)", "text[0]")
        chk("(123).to_text(8)", "text[173]")
        chk("(255).to_text(8)", "text[377]")
        chk("(9223372036854775807).to_text(8)", "text[777777777777777777777]")
        chk("(-1).to_text(8)", "text[-1]")
        chk("(-9223372036854775807-1).to_text(8)", "text[-1000000000000000000000]")
    }

    @Test fun testToText10() {
        chk("(0).to_text(10)", "text[0]")
        chk("(123).to_text(10)", "text[123]")
        chk("(255).to_text(10)", "text[255]")
        chk("(9223372036854775807).to_text(10)", "text[9223372036854775807]")
        chk("(-1).to_text(10)", "text[-1]")
        chk("(-9223372036854775807-1).to_text(10)", "text[-9223372036854775808]")
    }

    @Test fun testToText16() {
        chk("(0).to_text(16)", "text[0]")
        chk("(123).to_text(16)", "text[7b]")
        chk("(255).to_text(16)", "text[ff]")
        chk("(9223372036854775807).to_text(16)", "text[7fffffffffffffff]")
        chk("(-1).to_text(16)", "text[-1]")
        chk("(-9223372036854775807-1).to_text(16)", "text[-8000000000000000]")
    }

    @Test fun testSign() {
        chk("(0).sign()", "int[0]")
        chk("(1).sign()", "int[1]")
        chk("(-1).sign()", "int[-1]")
        chk("(-123456).sign()", "int[-1]")
        chk("(123456).sign()", "int[1]")
    }
}
