package net.postchain.rell

import net.postchain.rell.runtime.RtIntValue
import net.postchain.rell.runtime.RtValue
import org.junit.After
import org.junit.Test

class StatementTest {
    private val tst = RellSqlTester()

    @After fun after() = tst.destroy()

    @Test fun testVal() {
        chk("val x = 123; return x;", "int[123]")
        chk("val x = 123; x = 456; return x;", "ct_err:stmt_assign_val:x")
        chk("val x: integer = 123; return x;", "int[123]")
        chk("val x: text = 123; return x;", "ct_err:stmt_val_type:x:text:integer")
        chk("val x: integer = 'Hello'; return x;", "ct_err:stmt_val_type:x:integer:text")
    }

    @Test fun testVar() {
        chk("var x = 123; return x;", "int[123]")
        chk("var x = 123; x = 456; return x;", "int[456]")
        chk("var x;", "ct_err:stmt_var_notypeexpr:x")
        chk("var x: integer = 123; return x;", "int[123]")
        chk("var x: integer; x = 123; return x;", "int[123]")
        chk("var x: integer; x = 'Hello'; return x;", "ct_err:stmt_assign_type:x:integer:text")
        chk("var x: text; x = 123; return x;", "ct_err:stmt_assign_type:x:text:integer")
        chk("var x: integer = 'Hello'; return x;", "ct_err:stmt_var_type:x:integer:text")
        chk("var x: text = 123; return x;", "ct_err:stmt_var_type:x:text:integer")
        chk("var x = 123; x = 'Hello'; return x;", "ct_err:stmt_assign_type:x:integer:text")
    }

    @Test fun testNameConflict() {
        chk("val x = 123; val x = 456;", "ct_err:var_dupname:x")
        chk("val x = 123; var x = 456;", "ct_err:var_dupname:x")
        chk("var x = 123; val x = 456;", "ct_err:var_dupname:x")
        chk("var x = 123; var x = 456;", "ct_err:var_dupname:x")
        chk("val x = 123; { val x = 456; }", "ct_err:var_dupname:x")
        chk("val x = 123; if (2 > 1) { val x = 456; }", "ct_err:var_dupname:x")
    }

    @Test fun testIf() {
        chk("if (true) return 123; return 456;", "int[123]")
        chk("if (false) return 123; return 456;", "int[456]")

        chk("if ('Hello') return 123; return 456;", "ct_err:stmt_if_expr_type:text")
        chk("if (555) return 123; return 456;", "ct_err:stmt_if_expr_type:integer")

        chk("if (a == 0) return 123; return 456;", 0, "int[123]")
        chk("if (a == 0) return 123; return 456;", 1, "int[456]")
        chk("if (a == 0) return 123; else return 456; return 789;", 0, "int[123]")
        chk("if (a == 0) return 123; else return 456; return 789;", 1, "int[456]")

        // Dangling else
        chk("if (false) if (false) return 123; else return 456; return 789;", "int[789]")
        chk("if (false) if (true) return 123; else return 456; return 789;", "int[789]")
        chk("if (true) if (false) return 123; else return 456; return 789;", "int[456]")
        chk("if (true) if (true) return 123; else return 456; return 789;", "int[123]")
    }

    @Test fun testRequire() {
        chk("require(true); return 123;", "int[123]")
        chk("require(false); return 123;", "req_err:")

        chk("require(a != 0); return 123;", 1, "int[123]")
        chk("require(a != 0); return 123;", 0, "req_err:")

        chk("require(false, 'Hello'); return 123;", "req_err:Hello")
        chk("require(123);", "ct_err:stmt_require_expr_type:integer")
        chk("require('Hello');", "ct_err:stmt_require_expr_type:text")
        chk("require(true, true);", "ct_err:stmt_require_msg_type:boolean")
        chk("require(true, 123);", "ct_err:stmt_require_msg_type:integer")
    }

    @Test fun testAtAsBoolean() {
        tst.classDefs = listOf("class user { name: text; }")
        tst.execOp("create user('Bob'); create user('Alice');")

        chk("require(user @ { name = 'Bob' }); return 123;", "int[123]")
        chk("require(user @ { name = 'Alice' }); return 123;", "int[123]")
        chk("require(user @ { name = 'Trudy' }); return 123;", "req_err:")
        chk("require(user @ { name != 'Trudy' }); return 123;", "rt_err:at:wrong_count:2")

        chk("if (user @ { name = 'Bob' }) return 123; return 456;", "int[123]")
        chk("if (user @ { name = 'Alice' }) return 123; return 456;", "int[123]")
        chk("if (user @ { name = 'Trudy' }) return 123; return 456;", "int[456]")
        chk("if (user @ { name != 'Trudy' }) return 123; return 456;", "rt_err:at:wrong_count:2")

        chk("require(all user @ { name = 'Trudy' }); return 123;", "req_err:")
        chk("require(all user @ { name != 'Trudy' }); return 123;", "int[123]")
        chk("if (all user @ { name = 'Trudy' }) return 123; return 456;", "int[456]")
        chk("if (all user @ { name != 'Trudy' }) return 123; return 456;", "int[123]")

        // Currently not allowed, may reconsider later
        chk("require(not user @ { name = 'Bob' });", "ct_err:unop_operand_type:not:user")
        chk("require(true and user @ { name = 'Bob' });", "ct_err:binop_operand_type:and:boolean:user")
        chk("require(false or user @ { name = 'Bob' });", "ct_err:binop_operand_type:or:boolean:user")
        chk("val u = user @ { name = 'Bob' }; require(u);", "ct_err:stmt_require_expr_type:user")
        chk("val u = user @ { name = 'Bob' }; if (u) return 123;", "ct_err:stmt_if_expr_type:user")
        chk("val u: boolean = user @ { name = 'Bob' };", "ct_err:stmt_val_type:u:boolean:user")
    }

    @Test fun testWhile() {
        val code = """
            var s = '';
            var i = 0;
            while (i < 5) {
                s = s + i.str();
                i = i + 1;
            }
            return s;
        """.trimIndent()

        chk(code, "text[01234]")
    }

    @Test fun testWhileBreak() {
        val code = """
            var s = '';
            var i = 0;
            while (i < 10) {
                s = s + i.str();
                i = i + 1;
                if (i >= 5) break;
            }
            return s;
        """.trimIndent()

        chk(code, "text[01234]")
    }

    @Test fun testWhileBreakComplex() {
        val code = """
            var s = '';
            var i = 0;
            while (i < 10) {
                var j = 0;
                while (j < 10) {
                    s = s + i.str() + ',' + j.str() + ' ';
                    j = j + 1;
                    if (j >= 5) break;
                }
                i = i + 1;
                if (i >= 3) break;
            }
            return s;
        """.trimIndent()

        chk(code, "text[0,0 0,1 0,2 0,3 0,4 1,0 1,1 1,2 1,3 1,4 2,0 2,1 2,2 2,3 2,4 ]")
    }

    @Test fun testBreakWithoutLoop() {
        chk("break; return 123;", "ct_err:stmt_break_noloop")
        chk("if (2 > 1) break; return 123;", "ct_err:stmt_break_noloop")
    }

    @Test fun testFor() {
        tst.classDefs = listOf("class user { name: text; }")
        tst.execOp("create user('Bob'); create user('Alice'); create user('Trudy');")

        val code = """
            var s = '';
            for (name in all user@{}.name) {
                if (s.len() > 0) s = s + ',';
                s = s + name;
            }
            return s;
        """.trimIndent()

        chk(code, "text[Bob,Alice,Trudy]")
    }

    @Test fun testForBreak() {
        tst.classDefs = listOf("class user { name: text; }")
        tst.execOp("create user('Bob'); create user('Alice'); create user('Trudy');")

        val code = """
            var s = '';
            var n = 0;
            for (name in all user@{}.name) {
                if (s.len() > 0) s = s + ',';
                s = s + name;
                n = n + 1;
                if (n >= 2) break;
            }
            return s;
        """.trimIndent()

        chk(code, "text[Bob,Alice]")
    }

    private fun chk(code: String, arg: Long, expectedResult: String) {
        chkFull("query q(a: integer) { $code }", listOf(RtIntValue(arg)), expectedResult)
    }

    private fun chk(code: String, expectedResult: String) {
        chkFull("query q() { $code }", listOf(), expectedResult)
    }

    private fun chkFull(code: String, args: List<RtValue>, expected: String) {
        tst.chkQueryEx(code, args, expected)
    }
}
