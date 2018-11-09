package net.postchain.rell.lib

import net.postchain.rell.BaseRellTest
import net.postchain.rell.hexStringToByteArray
import org.junit.Test

class LibTest: BaseRellTest(false) {
    @Test fun testAbs() {
        chk("abs(a)", 0, "int[0]")
        chk("abs(a)", -123, "int[123]")
        chk("abs(a)", 123, "int[123]")
        chk("abs('Hello')", "ct_err:expr_call_argtypes:abs:text")
        chk("abs()", "ct_err:expr_call_argtypes:abs:")
        chk("abs(1, 2)", "ct_err:expr_call_argtypes:abs:integer,integer")
    }

    @Test fun testMinMax() {
        chk("min(a, b)", 100, 200, "int[100]")
        chk("min(a, b)", 200, 100, "int[100]")
        chk("max(a, b)", 100, 200, "int[200]")
        chk("max(a, b)", 200, 100, "int[200]")
    }

    @Test fun testPrint() {
        chkEx("{ print('Hello'); return 123; }", "int[123]")
        tst.chkStdout("Hello")
        tst.chkLog()

        chkEx("{ print(12345); return 123; }", "int[123]")
        tst.chkStdout("12345")
        tst.chkLog()

        chkEx("{ print(1, 2, 3, 4, 5); return 123; }", "int[123]")
        tst.chkStdout("1 2 3 4 5")
        tst.chkLog()

        chkEx("{ print(); return 123; }", "int[123]")
        tst.chkStdout("")
        tst.chkLog()
    }

    @Test fun testLog() {
        chkEx("{ log('Hello'); return 123; }", "int[123]")
        tst.chkLog("Hello")
        tst.chkStdout()

        chkEx("{ log(12345); return 123; }", "int[123]")
        tst.chkLog("12345")
        tst.chkStdout()

        chkEx("{ log(1, 2, 3, 4, 5); return 123; }", "int[123]")
        tst.chkLog("1 2 3 4 5")
        tst.chkStdout()

        chkEx("{ log(); return 123; }", "int[123]")
        tst.chkStdout()
        tst.chkLog("")
    }

    @Test fun testIsSigner() {
        tst.signers = listOf("1234".hexStringToByteArray(), "abcd".hexStringToByteArray())

        chk("is_signer(x'1234')", "boolean[true]")
        chk("is_signer(x'abcd')", "boolean[true]")
        chk("is_signer(x'1234abcd')", "boolean[false]")
        chk("is_signer(x'')", "boolean[false]")

        chk("is_signer()", "ct_err:expr_call_argtypes:is_signer:")
        chk("is_signer(123)", "ct_err:expr_call_argtypes:is_signer:integer")
        chk("is_signer('1234')", "ct_err:expr_call_argtypes:is_signer:text")
        chk("is_signer(x'12', x'34')", "ct_err:expr_call_argtypes:is_signer:byte_array,byte_array")
    }

    @Test fun testJsonStr() {
        chkEx("""{ val s = json('{  "x":5, "y" : 10  }'); return s.str(); }""", """text[{"x":5,"y":10}]""")
    }

    @Test fun testByteArrayConstructorText() {
        chk("byte_array('0123abcd')", "byte_array[0123abcd]")
        chk("byte_array('0123ABCD')", "byte_array[0123abcd]")
        chk("byte_array('')", "byte_array[]")
        chk("byte_array('0')", "rt_err:fn_bytearray_new_text:0")
        chk("byte_array('0g')", "rt_err:fn_bytearray_new_text:0g")
        chk("byte_array(123)", "ct_err:expr_call_argtypes:byte_array:integer")
    }

    @Test fun testByteArrayConstructorList() {
        chk("byte_array(list<integer>())", "byte_array[]")
        chk("byte_array([123])", "byte_array[7b]")
        chk("byte_array([18, 52, 171, 205])", "byte_array[1234abcd]")
        chk("byte_array([0, 255])", "byte_array[00ff]")

        chk("byte_array()", "ct_err:expr_call_argtypes:byte_array:")
        chk("byte_array(list<text>())", "ct_err:expr_call_argtypes:byte_array:list<text>")
        chk("byte_array(['Hello'])", "ct_err:expr_call_argtypes:byte_array:list<text>")
        chk("byte_array(set<integer>())", "ct_err:expr_call_argtypes:byte_array:set<integer>")
        chk("byte_array([-1])", "rt_err:fn_bytearray_new_list:-1")
        chk("byte_array([256])", "rt_err:fn_bytearray_new_list:256")
    }

    @Test fun testByteArrayEmpty() {
        chk("x''.empty()", "boolean[true]")
        chk("x'01'.empty()", "boolean[false]")
        chk("x'01234567'.empty()", "boolean[false]")
    }

    @Test fun testByteArraySize() {
        chk("x''.size()", "int[0]")
        chk("x'01'.size()", "int[1]")
        chk("x'ABCD'.size()", "int[2]")
        chk("x'0123ABCD'.size()", "int[4]")
    }

    @Test fun testByteArrayConcat() {
        chk("x'0123' + x'ABCD'", "byte_array[0123abcd]")
    }

    @Test fun testByteArraySubscript() {
        chk("x'0123ABCD'[0]", "int[1]")
        chk("x'0123ABCD'[1]", "int[35]")
        chk("x'0123ABCD'[2]", "int[171]")
        chk("x'0123ABCD'[3]", "int[205]")
        chk("x'0123ABCD'[4]", "rt_err:expr_bytearray_subscript_index:4:4")
        chk("x'0123ABCD'[-1]", "rt_err:expr_bytearray_subscript_index:4:-1")

        chkEx("{ val x = x'0123ABCD'; x[1] = 123; return x; }", "ct_err:expr_lookup_base:byte_array")
    }

    @Test fun testByteArrayDecode() {
        chk("x''.decode()", "text[]")
        chk("x'48656c6c6f'.decode()", "text[Hello]")
        chk("x'd09fd180d0b8d0b2d0b5d182'.decode()", "text[\u041f\u0440\u0438\u0432\u0435\u0442]")
        chk("x'fefeffff'.decode()", "text[\ufffd\ufffd\ufffd\ufffd]")
    }

    @Test fun testByteArraySub() {
        chk("x'0123ABCD'.sub(0)", "byte_array[0123abcd]")
        chk("x'0123ABCD'.sub(2)", "byte_array[abcd]")
        chk("x'0123ABCD'.sub(3)", "byte_array[cd]")
        chk("x'0123ABCD'.sub(4)", "byte_array[]")
        chk("x'0123ABCD'.sub(5)", "rt_err:fn_bytearray_sub_range:4:5:4")
        chk("x'0123ABCD'.sub(-1)", "rt_err:fn_bytearray_sub_range:4:-1:4")
        chk("x'0123ABCD'.sub(1, 3)", "byte_array[23ab]")
        chk("x'0123ABCD'.sub(0, 4)", "byte_array[0123abcd]")
        chk("x'0123ABCD'.sub(1, 0)", "rt_err:fn_bytearray_sub_range:4:1:0")
        chk("x'0123ABCD'.sub(1, 5)", "rt_err:fn_bytearray_sub_range:4:1:5")
    }

    @Test fun testByteArrayToList() {
        chk("x''.toList()", "list<integer>[]")
        chk("x'1234abcd'.toList()", "list<integer>[int[18],int[52],int[171],int[205]]")
    }
}
