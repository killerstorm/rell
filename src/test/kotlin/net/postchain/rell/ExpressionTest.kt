package net.postchain.rell

import net.postchain.rell.runtime.RtIntValue
import net.postchain.rell.runtime.RtValue
import org.junit.Test

class ExpressionTest {
    private val tst = RellSqlTester(useSql = false)

    @Test fun testPrecedence() {
        chk("2 * 3 + 4", "int[10]")
        chk("4 + 2 * 3", "int[10]")

        chk("5 * 4 / 3", "int[6]")
        chk("5 * (4 / 3)", "int[5]")
        chk("555 / 13 % 5", "int[2]")
        chk("7 - 555 / 13 % 5", "int[5]")

        chk("1 == 1 and 2 == 2 and 3 == 3", "boolean[true]")
        chk("1 == 1 and 2 == 0 and 3 == 3", "boolean[false]")
        chk("1 == 2 or 3 == 4 or 5 == 6", "boolean[false]")
        chk("1 == 2 or 3 == 3 or 5 == 6", "boolean[true]")

        chk("false or false and false", "boolean[false]")
        chk("false or false and true", "boolean[false]")
        chk("false or true and false", "boolean[false]")
        chk("false or true and true", "boolean[true]")
        chk("true or false and false", "boolean[true]")
        chk("true or false and true", "boolean[true]")
        chk("true or true and false", "boolean[true]")
        chk("true or true and true", "boolean[true]")

        chk("false and false or false", "boolean[false]")
        chk("false and false or true", "boolean[true]")
        chk("false and true or false", "boolean[false]")
        chk("false and true or true", "boolean[true]")
        chk("true and false or false", "boolean[false]")
        chk("true and false or true", "boolean[true]")
        chk("true and true or false", "boolean[true]")
        chk("true and true or true", "boolean[true]")

        chk("5 > 3 and 8 == 2 or 1 < 9", "boolean[true]")
        chk("5 > 3 and 8 == 2 or 9 == 6", "boolean[false]")
        chk("3 > 5 or 0 < 8 and 5 == 5", "boolean[true]")
        chk("5 == 5 or 3 > 5 and 8 < 0", "boolean[true]")

        chk("2 + 3 * 4 == 114 % 100", "boolean[true]")

        chk("2 * 3 + 4 * 5 + 6 * 7 + 8 * 9 + 10 * 11 + 12 * 13 + 14 * 15 + 16 * 17 + 18 * 19", "int[1230]")
        chk("2 * 3 + 4 * 5 - 6 * 7 + 8 * 9 - 10 * 11 + 12 * 13 - 14 * 15 + 16 * 17 - 18 * 19", "int[-178]")
    }

    @Test fun testShortCircuitEvaluation() {
        chk("1 / 0", "rt_err:expr_div_by_zero")
        chk("a != 0 and 15 / a > 3", 0, "boolean[false]")
        chk("a != 0 and 15 / a > 3", 5, "boolean[false]")
        chk("a != 0 and 15 / a > 3", 3, "boolean[true]")
        chk("a == 0 or 15 / a > 3", 0, "boolean[true]")
        chk("a == 0 or 15 / a > 3", 5, "boolean[false]")
        chk("a == 0 or 15 / a > 3", 3, "boolean[true]")
    }

    @Test fun testFunctions() {
        chk("abs(a)", 0, "int[0]")
        chk("abs(a)", -123, "int[123]")
        chk("abs(a)", 123, "int[123]")
        chk("abs('Hello')", "ct_err:expr_call_argtype:abs:0:integer:text")
        chk("abs()", "ct_err:expr_call_argcnt:abs:1:0")
        chk("abs(1, 2)", "ct_err:expr_call_argcnt:abs:1:2")

        chk("min(a, b)", 100, 200, "int[100]")
        chk("min(a, b)", 200, 100, "int[100]")
        chk("max(a, b)", 100, 200, "int[200]")
        chk("max(a, b)", 200, 100, "int[200]")
    }

    @Test fun testFunctionVsVariable() {
        chkEx("{ val abs = a; return abs(abs); }", 123, "int[123]")
        chkEx("{ val abs = a; return abs(abs); }", -123, "int[123]")
        chkEx("{ val abs = a; return abs; }", -123, "int[-123]")
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

        chkEx("{ val s = all user @ { name = 'James' }; return s.len(); }", "int[0]")
        chkEx("{ val s = all user @ { name = 'Bob' }; return s.len(); }", "int[1]")
        chkEx("{ val s = all user @ {}; return s.len(); }", "int[3]")
    }

    @Test fun testListItems() {
        tst.useSql = true
        tst.classDefs = listOf("class user { name: text; }")

        tst.execOp("create user('Bob');")
        tst.execOp("create user('Alice');")
        tst.execOp("create user('Trudy');")

        chkEx("{ val s = all user @ {}; return s[0]; }", "user[1]")
        chkEx("{ val s = all user @ {}; return s[1]; }", "user[2]")
        chkEx("{ val s = all user @ {}; return s[2]; }", "user[3]")

        chkEx("{ val s = all user @ {}; return s[-1]; }", "rt_err:expr_lookup_index:3:-1")
        chkEx("{ val s = all user @ {}; return s[3]; }", "rt_err:expr_lookup_index:3:3")
    }

    @Test fun testFunctionsUnderAt() {
        tst.useSql = true
        tst.classDefs = listOf("class user { name: text; score: integer; }")
        tst.execOp("create user('Bob',-5678);")

        chkEx("{ val s = 'Hello'; return user @ {} ( name.len() + s.len() ); }", "int[8]")
        chkEx("{ val x = -1234; return user @ {} ( abs(x), abs(score) ); }", "(int[1234],int[5678])")
    }

    private fun chk(code: String, expected: String) = chkEx("= $code ;", expected)
    private fun chk(code: String, arg: Long, expected: String) = chkEx("= $code ;", arg, expected)
    private fun chk(code: String, arg1: Long, arg2: Long, expected: String) = chkEx("= $code ;", arg1, arg2, expected)

    private fun chkEx(code: String, expected: String) = chkFull("query q() $code", listOf(), expected)

    private fun chkEx(code: String, arg: Long, expected: String) {
        chkFull("query q(a: integer) $code", listOf(RtIntValue(arg)), expected)
    }

    private fun chkEx(code: String, arg1: Long, arg2: Long, expected: String) {
        chkFull("query q(a: integer, b: integer) $code", listOf(RtIntValue(arg1), RtIntValue(arg2)), expected)
    }

    private fun chkFull(code: String, args: List<RtValue>, expected: String) {
        tst.chkQueryEx(code, args, expected)
    }
}
