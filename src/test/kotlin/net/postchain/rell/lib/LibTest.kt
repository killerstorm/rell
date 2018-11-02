package net.postchain.rell.lib

import net.postchain.rell.BaseRellTest
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

    @Test fun testRange() {
        chk("range(10)", "range[0,10,1]")
        chk("range(5,10)", "range[5,10,1]")
        chk("range(5,10,3)", "range[5,10,3]")
        chk("range(0)", "range[0,0,1]")
        chk("range(-1)", "rt_err:fn_range_args:0:-1:1")
        chk("range(10,10)", "range[10,10,1]")
        chk("range(11,10)", "rt_err:fn_range_args:11:10:1")
        chk("range(1,0)", "rt_err:fn_range_args:1:0:1")

        chk("range(0,10,0)", "rt_err:fn_range_args:0:10:0")
        chk("range(0,10,-1)", "rt_err:fn_range_args:0:10:-1")
        chk("range(0,0,-1)", "range[0,0,-1]")
        chk("range(1,0,-1)", "range[1,0,-1]")
        chk("range(10,0,-1)", "range[10,0,-1]")

        chk("range()", "ct_err:expr_call_argcnt:range:1:0")
        chk("range(1,2,3,4)", "ct_err:expr_call_argcnt:range:3:4")
    }

    @Test fun testJsonStr() {
        chkEx("""{ val s = json('{  "x":5, "y" : 10  }'); return s.str(); }""", """text[{"x":5,"y":10}]""")
    }

    /*@Test*/ fun testByteArrayParse() {
        chk("byte_array.parse('0123abcd')", "byte_array[0123abcd]")
        chk("byte_array.parse('0123ABCD')", "byte_array[0123abcd]")
        chk("byte_array.parse('')", "byte_array[]")
        chk("byte_array.parse('0')", "*error*")
        chk("byte_array.parse('0g')", "*error*")
        chk("byte_array.parse(123)", "*error*")
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
}
