package net.postchain.rell.lib

import net.postchain.rell.BaseRellTest
import org.junit.Test

class LibIntegerTest: BaseRellTest(false) {
    /*@Test*/ fun testIntegerParse() {
        chk("integer.parse('0')", "int[0]")
        chk("integer.parse('123456789')", "int[123456789]")
        chk("integer.parse('9223372036854775807')", "int[9223372036854775807]")
        chk("integer.parse('9223372036854775808')", "*error*")
        chk("integer.parse('-123456789')", "int[-123456789]")
        chk("integer.parse('-9223372036854775808')", "int[-9223372036854775808]")
        chk("integer.parse('-9223372036854775809')", "*error*")
        chk("integer.parse('')", "*error*")
        chk("integer.parse(' 123')", "*error*")
        chk("integer.parse('123 ')", "*error*")
        chk("integer.parse('0123')", "int[123]")
        chk("integer.parse('0x123')", "*error*")
        chk("integer.parse(123)", "*error*")
        chk("integer.parse('aaa')", "*error*")
        chk("integer.parse('123', 0)", "*error*")
        chk("integer.parse('123', -1)", "*error*")
    }

    /*@Test*/ fun testIntegerParse_2() {
        chk("integer.parse('0', 2)", "int[0]")
        chk("integer.parse('1111011', 2)", "int[123]")
        chk("integer.parse('11111111', 2)", "int[255]")
        chk("integer.parse('111111111111111111111111111111111111111111111111111111111111111', 2)", "int[9223372036854775807]")
        chk("integer.parse('-1', 2)", "int[-1]")
        chk("integer.parse('-1000000000000000000000000000000000000000000000000000000000000000', 2)", "int[-9223372036854775808]")
        chk("integer.parse('1000000000000000000000000000000000000000000000000000000000000000', 2)", "*error*")
        chk("integer.parse('-1000000000000000000000000000000000000000000000000000000000000001', 2)", "*error*")
    }

    /*@Test*/ fun testIntegerParse_8() {
        chk("integer.parse('0', 8)", "int[0]")
        chk("integer.parse('173', 8)", "int[123]")
        chk("integer.parse('377', 8)", "int[255]")
        chk("integer.parse('777777777777777777777', 8)", "int[9223372036854775807]")
        chk("integer.parse('-1', 8)", "int[-1]")
        chk("integer.parse('-1000000000000000000000', 8)", "int[-9223372036854775808]")
        chk("integer.parse('1000000000000000000000', 8)", "*error*")
        chk("integer.parse('-1000000000000000000001', 8)", "*error*")
    }

    /*@Test*/ fun testIntegerParse_10() {
        chk("integer.parse('0', 10)", "int[0]")
        chk("integer.parse('123456789', 10)", "int[123456789]")
        chk("integer.parse('9223372036854775807', 10)", "int[9223372036854775807]")
        chk("integer.parse('9223372036854775808', 10)", "*error*")
        chk("integer.parse('-123456789', 10)", "int[-123456789]")
        chk("integer.parse('-9223372036854775808', 10)", "int[-9223372036854775808]")
        chk("integer.parse('-9223372036854775809', 10)", "*error*")
    }

    /*@Test*/ fun testIntegerParse_16() {
        chk("integer.parse('0', 8)", "int[0]")
        chk("integer.parse('7b', 8)", "int[123]")
        chk("integer.parse('ff', 8)", "int[255]")
        chk("integer.parse('7fffffffffffffff', 8)", "int[9223372036854775807]")
        chk("integer.parse('-1', 8)", "int[-1]")
        chk("integer.parse('-8000000000000000', 8)", "int[-9223372036854775808]")
        chk("integer.parse('8000000000000000', 8)", "*error*")
        chk("integer.parse('-8000000000000001', 8)", "*error*")
    }

    /*@Test*/ fun testIntegerParseHex() {
        chk("integer.parseHex('0')", "int[0]")
        chk("integer.parseHex('7b')", "int[123]")
        chk("integer.parseHex('ff')", "int[255]")
        chk("integer.parseHex('7fffffffffffffff')", "int[9223372036854775807")
        chk("integer.parseHex('ffffffffffffffff')", "int[-1]")
        chk("integer.parseHex('8000000000000000')", "int[-9223372036854775808]")
        chk("integer.parseHex('-1')", "*error*")
        chk("integer.parseHex('10000000000000000')", "*error*")
        chk("integer.parseHex()", "*error*")
        chk("integer.parseHex(123)", "*error*")
        chk("integer.parseHex('')", "*error*")
        chk("integer.parseHex('ghi')", "*error*")
    }

    /*@Test*/ fun testIntegerHex() {
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
