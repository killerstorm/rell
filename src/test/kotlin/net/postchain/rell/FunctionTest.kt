package net.postchain.rell

import org.junit.Test

class FunctionTest: BaseRellTest(false) {
    @Test fun testAbs() {
        chk("abs(a)", 0, "int[0]")
        chk("abs(a)", -123, "int[123]")
        chk("abs(a)", 123, "int[123]")
        chk("abs('Hello')", "ct_err:expr_call_argtype:abs:0:integer:text")
        chk("abs()", "ct_err:expr_call_argcnt:abs:1:0")
        chk("abs(1, 2)", "ct_err:expr_call_argcnt:abs:1:2")
    }

    @Test fun testMinMax() {
        chk("min(a, b)", 100, 200, "int[100]")
        chk("min(a, b)", 200, 100, "int[100]")
        chk("max(a, b)", 100, 200, "int[200]")
        chk("max(a, b)", 200, 100, "int[200]")
    }

    @Test fun testMemberFunctions() {
        chkEx("{ return ''.len(); }", "int[0]")
        chkEx("{ return 'Hello'.len(); }", "int[5]")
        chkEx("{ val s = ''; return s.len(); }", "int[0]")
        chkEx("{ val s = 'Hello'; return s.len(); }", "int[5]")
        chkEx("""{ val s = json('{  "x":5, "y" : 10  }'); return s.str(); }""", """text[{"x":5,"y":10}]""")
        chkEx("{ val s = 'Hello'; return s.badfunc(); }", "ct_err:expr_call_unknown:text:badfunc")
    }

    @Test fun testListLen() {
        tst.useSql = true
        tst.classDefs = listOf("class user { name: text; }")

        tst.execOp("create user('Bob');")
        tst.execOp("create user('Alice');")
        tst.execOp("create user('Trudy');")

        chkEx("{ val s = user @* { name = 'James' }; return s.len(); }", "int[0]")
        chkEx("{ val s = user @* { name = 'Bob' }; return s.len(); }", "int[1]")
        chkEx("{ val s = user @* {}; return s.len(); }", "int[3]")
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
}
