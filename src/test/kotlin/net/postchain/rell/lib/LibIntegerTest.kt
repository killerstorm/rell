package net.postchain.rell.lib

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LibIntegerTest: BaseRellTest(false) {
    @Test fun testIntegerConstants() {
        chk("integer.MIN_VALUE", "int[-9223372036854775808]")
        chk("integer.MAX_VALUE", "int[9223372036854775807]")
    }

    @Test fun testIntegerParse() {
        chk("integer('0')", "int[0]")
        chk("integer('123456789')", "int[123456789]")
        chk("integer('9223372036854775807')", "int[9223372036854775807]")
        chk("integer('9223372036854775808')", "rt_err:fn_int_parse:9223372036854775808")
        chk("integer('-123456789')", "int[-123456789]")
        chk("integer('-9223372036854775808')", "int[-9223372036854775808]")
        chk("integer('-9223372036854775809')", "rt_err:fn_int_parse:-9223372036854775809")
        chk("integer('')", "rt_err:fn_int_parse:")
        chk("integer(' 123')", "rt_err:fn_int_parse: 123")
        chk("integer('123 ')", "rt_err:fn_int_parse:123 ")
        chk("integer('0123')", "int[123]")
        chk("integer('0x123')", "rt_err:fn_int_parse:0x123")
        chk("integer(123)", "ct_err:expr_call_argtypes:integer:integer")
        chk("integer('aaa')", "rt_err:fn_int_parse:aaa")
        chk("integer('123', 0)", "rt_err:fn_int_parse_radix:0")
        chk("integer('123', 1)", "rt_err:fn_int_parse_radix:1")
        chk("integer('123', 40)", "rt_err:fn_int_parse_radix:40")
        chk("integer('123', -1)", "rt_err:fn_int_parse_radix:-1")
    }

    @Test fun testIntegerParse_2() {
        chk("integer('0', 2)", "int[0]")
        chk("integer('1111011', 2)", "int[123]")
        chk("integer('11111111', 2)", "int[255]")
        chk("integer('111111111111111111111111111111111111111111111111111111111111111', 2)", "int[9223372036854775807]")
        chk("integer('-1', 2)", "int[-1]")
        chk("integer('-1000000000000000000000000000000000000000000000000000000000000000', 2)", "int[-9223372036854775808]")
        chk("integer('1000000000000000000000000000000000000000000000000000000000000000', 2)",
                "rt_err:fn_int_parse:1000000000000000000000000000000000000000000000000000000000000000")
        chk("integer('-1000000000000000000000000000000000000000000000000000000000000001', 2)",
                "rt_err:fn_int_parse:-1000000000000000000000000000000000000000000000000000000000000001")
    }

    @Test fun testIntegerParse_8() {
        chk("integer('0', 8)", "int[0]")
        chk("integer('173', 8)", "int[123]")
        chk("integer('377', 8)", "int[255]")
        chk("integer('777777777777777777777', 8)", "int[9223372036854775807]")
        chk("integer('-1', 8)", "int[-1]")
        chk("integer('-1000000000000000000000', 8)", "int[-9223372036854775808]")
        chk("integer('1000000000000000000000', 8)", "rt_err:fn_int_parse:1000000000000000000000")
        chk("integer('-1000000000000000000001', 8)", "rt_err:fn_int_parse:-1000000000000000000001")
    }

    @Test fun testIntegerParse_10() {
        chk("integer('0', 10)", "int[0]")
        chk("integer('123456789', 10)", "int[123456789]")
        chk("integer('9223372036854775807', 10)", "int[9223372036854775807]")
        chk("integer('9223372036854775808', 10)", "rt_err:fn_int_parse:9223372036854775808")
        chk("integer('-123456789', 10)", "int[-123456789]")
        chk("integer('-9223372036854775808', 10)", "int[-9223372036854775808]")
        chk("integer('-9223372036854775809', 10)", "rt_err:fn_int_parse:-9223372036854775809")
    }

    @Test fun testIntegerParse_16() {
        chk("integer('0', 16)", "int[0]")
        chk("integer('7b', 16)", "int[123]")
        chk("integer('ff', 16)", "int[255]")
        chk("integer('7fffffffffffffff', 16)", "int[9223372036854775807]")
        chk("integer('-1', 16)", "int[-1]")
        chk("integer('-8000000000000000', 16)", "int[-9223372036854775808]")
        chk("integer('8000000000000000', 16)", "rt_err:fn_int_parse:8000000000000000")
        chk("integer('-8000000000000001', 16)", "rt_err:fn_int_parse:-8000000000000001")
    }

    @Test fun testIntegerParseHex() {
        chk("integer.parseHex('0')", "int[0]")
        chk("integer.parseHex('7b')", "int[123]")
        chk("integer.parseHex('ff')", "int[255]")
        chk("integer.parseHex('7fffffffffffffff')", "int[9223372036854775807]")
        chk("integer.parseHex('ffffffffffffffff')", "int[-1]")
        chk("integer.parseHex('8000000000000000')", "int[-9223372036854775808]")
        chk("integer.parseHex('-1')", "rt_err:fn_int_parseHex:-1")
        chk("integer.parseHex('10000000000000000')", "rt_err:fn_int_parseHex:10000000000000000")
        chk("integer.parseHex()", "ct_err:expr_call_argtypes:parseHex:")
        chk("integer.parseHex(123)", "ct_err:expr_call_argtypes:parseHex:integer")
        chk("integer.parseHex('')", "rt_err:fn_int_parseHex:")
        chk("integer.parseHex('ghi')", "rt_err:fn_int_parseHex:ghi")
    }

    @Test fun testIntegerHex() {
        chk("(0).hex()", "text[0]")
        chk("(123).hex()", "text[7b]")
        chk("(255).hex()", "text[ff]")
        chk("(9223372036854775807).hex()", "text[7fffffffffffffff]")
        chk("(-1).hex()", "text[ffffffffffffffff]")
        chk("(-9223372036854775807-1).hex()", "text[8000000000000000]")
    }

    @Test fun testIntegerStr() {
        chk("(0).str()", "text[0]")
        chk("(123).str()", "text[123]")
        chk("(255).str()", "text[255]")
        chk("(9223372036854775807).str()", "text[9223372036854775807]")
        chk("(-1).str()", "text[-1]")
        chk("(-9223372036854775807-1).str()", "text[-9223372036854775808]")
    }

    @Test fun testIntegerStr_2() {
        chk("(0).str(2)", "text[0]")
        chk("(123).str(2)", "text[1111011]")
        chk("(255).str(2)", "text[11111111]")
        chk("(9223372036854775807).str(2)", "text[111111111111111111111111111111111111111111111111111111111111111]")
        chk("(-1).str(2)", "text[-1]")
        chk("(-9223372036854775807-1).str(2)", "text[-1000000000000000000000000000000000000000000000000000000000000000]")
    }

    @Test fun testIntegerStr_8() {
        chk("(0).str(8)", "text[0]")
        chk("(123).str(8)", "text[173]")
        chk("(255).str(8)", "text[377]")
        chk("(9223372036854775807).str(8)", "text[777777777777777777777]")
        chk("(-1).str(8)", "text[-1]")
        chk("(-9223372036854775807-1).str(8)", "text[-1000000000000000000000]")
    }

    @Test fun testIntegerStr_10() {
        chk("(0).str(10)", "text[0]")
        chk("(123).str(10)", "text[123]")
        chk("(255).str(10)", "text[255]")
        chk("(9223372036854775807).str(10)", "text[9223372036854775807]")
        chk("(-1).str(10)", "text[-1]")
        chk("(-9223372036854775807-1).str(10)", "text[-9223372036854775808]")
    }

    @Test fun testIntegerStr_16() {
        chk("(0).str(16)", "text[0]")
        chk("(123).str(16)", "text[7b]")
        chk("(255).str(16)", "text[ff]")
        chk("(9223372036854775807).str(16)", "text[7fffffffffffffff]")
        chk("(-1).str(16)", "text[-1]")
        chk("(-9223372036854775807-1).str(16)", "text[-8000000000000000]")
    }

    @Test fun testIntegerSignum() {
        chk("(0).signum()", "int[0]")
        chk("(1).signum()", "int[1]")
        chk("(-1).signum()", "int[-1]")
        chk("(-123456).signum()", "int[-1]")
        chk("(123456).signum()", "int[1]")
    }
}
