package net.postchain.rell.lib

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LibByteArrayTest: BaseRellTest(false) {
    @Test fun testConstructorText() {
        chk("byte_array('0123abcd')", "byte_array[0123abcd]")
        chk("byte_array('0123ABCD')", "byte_array[0123abcd]")
        chk("byte_array('')", "byte_array[]")
        chk("byte_array('0')", "rt_err:fn:byte_array.from_hex")
        chk("byte_array('0g')", "rt_err:fn:byte_array.from_hex")
        chk("byte_array(123)", "ct_err:expr_call_argtypes:byte_array:integer")
    }

    @Test fun testSha256() {
        chk("'foo'.to_bytes().sha256()", "byte_array[2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae]")
        chk("'bar'.to_bytes().sha256()", "byte_array[fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9]")
    }

    @Test fun testFromList() {
        chk("byte_array.from_list(list<integer>())", "byte_array[]")
        chk("byte_array.from_list([123])", "byte_array[7b]")
        chk("byte_array.from_list([18, 52, 171, 205])", "byte_array[1234abcd]")
        chk("byte_array.from_list([0, 255])", "byte_array[00ff]")

        chk("byte_array.from_list()", "ct_err:expr_call_argtypes:from_list:")
        chk("byte_array.from_list(list<text>())", "ct_err:expr_call_argtypes:from_list:list<text>")
        chk("byte_array.from_list(['Hello'])", "ct_err:expr_call_argtypes:from_list:list<text>")
        chk("byte_array.from_list(set<integer>())", "ct_err:expr_call_argtypes:from_list:set<integer>")
        chk("byte_array.from_list([-1])", "rt_err:fn:byte_array.from_list:-1")
        chk("byte_array.from_list([256])", "rt_err:fn:byte_array.from_list:256")
    }

    @Test fun testEmpty() {
        chk("x''.empty()", "boolean[true]")
        chk("x'01'.empty()", "boolean[false]")
        chk("x'01234567'.empty()", "boolean[false]")
    }

    @Test fun testSize() {
        chk("x''.size()", "int[0]")
        chk("x'01'.size()", "int[1]")
        chk("x'ABCD'.size()", "int[2]")
        chk("x'0123ABCD'.size()", "int[4]")
    }

    @Test fun testConcat() {
        chk("x'0123' + x'ABCD'", "byte_array[0123abcd]")
    }

    @Test fun testSubscript() {
        chk("x'0123ABCD'[0]", "int[1]")
        chk("x'0123ABCD'[1]", "int[35]")
        chk("x'0123ABCD'[2]", "int[171]")
        chk("x'0123ABCD'[3]", "int[205]")
        chk("x'0123ABCD'[4]", "rt_err:expr_bytearray_subscript_index:4:4")
        chk("x'0123ABCD'[-1]", "rt_err:expr_bytearray_subscript_index:4:-1")

        chkEx("{ val x = x'0123ABCD'; x[1] = 123; return x; }", "ct_err:expr_unmodifiable:byte_array")
    }

    @Test fun testSub() {
        chk("x'0123ABCD'.sub(0)", "byte_array[0123abcd]")
        chk("x'0123ABCD'.sub(2)", "byte_array[abcd]")
        chk("x'0123ABCD'.sub(3)", "byte_array[cd]")
        chk("x'0123ABCD'.sub(4)", "byte_array[]")
        chk("x'0123ABCD'.sub(5)", "rt_err:fn:byte_array.sub:range:4:5:4")
        chk("x'0123ABCD'.sub(-1)", "rt_err:fn:byte_array.sub:range:4:-1:4")
        chk("x'0123ABCD'.sub(1, 3)", "byte_array[23ab]")
        chk("x'0123ABCD'.sub(0, 4)", "byte_array[0123abcd]")
        chk("x'0123ABCD'.sub(1, 0)", "rt_err:fn:byte_array.sub:range:4:1:0")
        chk("x'0123ABCD'.sub(1, 5)", "rt_err:fn:byte_array.sub:range:4:1:5")
    }

    @Test fun testToList() {
        chk("x''.to_list()", "list<integer>[]")
        chk("x'1234abcd'.to_list()", "list<integer>[int[18],int[52],int[171],int[205]]")
    }

    @Test fun testToFromHex() {
        chk("x''.to_hex()", "text[]")
        chk("x'deadbeef'.to_hex()", "text[deadbeef]")

        chk("byte_array.from_hex('')", "byte_array[]")
        chk("byte_array.from_hex('deadbeef')", "byte_array[deadbeef]")
        chk("byte_array.from_hex('DEADBEEF')", "byte_array[deadbeef]")
        chk("byte_array.from_hex('123456')", "byte_array[123456]")

        chk("byte_array.from_hex()", "ct_err:expr_call_argtypes:from_hex:")
        chk("byte_array.from_hex(1234)", "ct_err:expr_call_argtypes:from_hex:integer")
        chk("byte_array.from_hex(true)", "ct_err:expr_call_argtypes:from_hex:boolean")

        chk("byte_array.from_hex('0')", "rt_err:fn:byte_array.from_hex")
        chk("byte_array.from_hex('0g')", "rt_err:fn:byte_array.from_hex")
        chk("byte_array.from_hex('-1')", "rt_err:fn:byte_array.from_hex")
        chk("byte_array.from_hex('+1')", "rt_err:fn:byte_array.from_hex")
    }

    @Test fun testToFromBase64() {
        chk("x''.to_base64()", "text[]")
        chk("x'00'.to_base64()", "text[AA==]")
        chk("x'0000'.to_base64()", "text[AAA=]")
        chk("x'000000'.to_base64()", "text[AAAA]")
        chk("x'00000000'.to_base64()", "text[AAAAAA==]")
        chk("x'FFFFFF'.to_base64()", "text[////]")
        chk("x'12345678'.to_base64()", "text[EjRWeA==]")
        chk("x'deadbeef'.to_base64()", "text[3q2+7w==]")
        chk("x'cafebabedeadbeef'.to_base64()", "text[yv66vt6tvu8=]")

        chk("byte_array.from_base64('')", "byte_array[]")
        chk("byte_array.from_base64('AA==')", "byte_array[00]")
        chk("byte_array.from_base64('AA')", "byte_array[00]")
        chk("byte_array.from_base64('AA=')", "rt_err:fn:byte_array.from_base64")
        chk("byte_array.from_base64('AAA=')", "byte_array[0000]")
        chk("byte_array.from_base64('AAA')", "byte_array[0000]")
        chk("byte_array.from_base64('AAAA')", "byte_array[000000]")
        chk("byte_array.from_base64('AAAAAA==')", "byte_array[00000000]")
        chk("byte_array.from_base64('////')", "byte_array[ffffff]")
        chk("byte_array.from_base64('EjRWeA==')", "byte_array[12345678]")
        chk("byte_array.from_base64('EjRWeA')", "byte_array[12345678]")
        chk("byte_array.from_base64('EjRWeA=')", "rt_err:fn:byte_array.from_base64")
        chk("byte_array.from_base64('3q2+7w==')", "byte_array[deadbeef]")
        chk("byte_array.from_base64('yv66vt6tvu8=')", "byte_array[cafebabedeadbeef]")
        chk("byte_array.from_base64('!@#%^')", "rt_err:fn:byte_array.from_base64")

        chk("byte_array.from_base64()", "ct_err:expr_call_argtypes:from_base64:")
        chk("byte_array.from_base64(1234)", "ct_err:expr_call_argtypes:from_base64:integer")
        chk("byte_array.from_base64(true)", "ct_err:expr_call_argtypes:from_base64:boolean")
    }
}
