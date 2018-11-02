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
        chk("val x = unit(); return 123;", "ct_err:stmt_val_unit:x")
    }

    @Test fun testVar() {
        chk("var x = 123; return x;", "int[123]")
        chk("var x = 123; x = 456; return x;", "int[456]")
        chk("var x;", "ct_err:stmt_var_notypeexpr:x")
        chk("var x: integer = 123; return x;", "int[123]")
        chk("var x: integer; x = 123; return x;", "int[123]")
        chk("var x: integer; x = 'Hello'; return x;", "ct_err:stmt_assign_type:integer:text")
        chk("var x: text; x = 123; return x;", "ct_err:stmt_assign_type:text:integer")
        chk("var x: integer = 'Hello'; return x;", "ct_err:stmt_var_type:x:integer:text")
        chk("var x: text = 123; return x;", "ct_err:stmt_var_type:x:text:integer")
        chk("var x = 123; x = 'Hello'; return x;", "ct_err:stmt_assign_type:integer:text")
        chk("var x = unit(); return 123;", "ct_err:stmt_var_unit:x")
    }

    @Test fun testVarUninit() {
        chk("var x: integer; return x;", "rt_err:expr_var_uninit:x")
        chk("{ var x = 123; } { var y: integer; return y; }", "rt_err:expr_var_uninit:y")
        chk("for (i in range(2)) { var x: integer; if (i == 0) x = 123; else return x; } return 456;", "rt_err:expr_var_uninit:x")
    }

    @Test fun testNameConflict() {
        chk("val x = 123; val x = 456;", "ct_err:var_dupname:x")
        chk("val x = 123; var x = 456;", "ct_err:var_dupname:x")
        chk("var x = 123; val x = 456;", "ct_err:var_dupname:x")
        chk("var x = 123; var x = 456;", "ct_err:var_dupname:x")
        chk("val x = 123; { val x = 456; } return 789;", "ct_err:var_dupname:x")
        chk("val x = 123; if (2 > 1) { val x = 456; } return 789;", "ct_err:var_dupname:x")

        chk("{ val x = 123; } val x = 456; return x;", "int[456]")
        chk("{ val x = 123; } return x;", "ct_err:unknown_name:x")
    }

    @Test fun testIf() {
        chk("if (true) return 123; return 456;", "int[123]")
        chk("if (false) return 123; return 456;", "int[456]")

        chk("if ('Hello') return 123; return 456;", "ct_err:stmt_if_expr_type:boolean:text")
        chk("if (555) return 123; return 456;", "ct_err:stmt_if_expr_type:boolean:integer")

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
        chk("require(123);", "ct_err:stmt_require_expr_type:boolean:integer")
        chk("require('Hello');", "ct_err:stmt_require_expr_type:boolean:text")
        chk("require(true, true);", "ct_err:stmt_require_msg_type:text:boolean")
        chk("require(true, 123);", "ct_err:stmt_require_msg_type:text:integer")
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

        chk("require(user @* { name = 'Trudy' }); return 123;", "req_err:")
        chk("require(user @* { name != 'Trudy' }); return 123;", "int[123]")
        chk("if (user @* { name = 'Trudy' }) return 123; return 456;", "int[456]")
        chk("if (user @* { name != 'Trudy' }) return 123; return 456;", "int[123]")

        // Currently not allowed, may reconsider later
        chk("require(not user @ { name = 'Bob' });", "ct_err:unop_operand_type:not:user")
        chk("require(true and user @ { name = 'Bob' });", "ct_err:binop_operand_type:and:boolean:user")
        chk("require(false or user @ { name = 'Bob' });", "ct_err:binop_operand_type:or:boolean:user")
        chk("val u = user @ { name = 'Bob' }; require(u);", "ct_err:stmt_require_expr_type:boolean:user")
        chk("val u = user @ { name = 'Bob' }; if (u) return 123;", "ct_err:stmt_if_expr_type:boolean:user")
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
            for (name in user@*{}.name) {
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
            for (name in user@*{}.name) {
                if (s.len() > 0) s = s + ',';
                s = s + name;
                n = n + 1;
                if (n >= 2) break;
            }
            return s;
        """.trimIndent()

        chk(code, "text[Bob,Alice]")
    }

    @Test fun testForReturn() {
        tst.classDefs = listOf("class user { name: text; }")
        tst.execOp("create user('Bob'); create user('Alice'); create user('Trudy');")

        val code = """
            for (i in range(10)) {
                print(i);
                if (i == 3) return 123;
            }
            return 456;
        """.trimIndent()

        chk(code, "int[123]")
        tst.chkStdout("0", "1", "2", "3")
    }

    @Test fun testForRange() {
        tst.execOp("for (i in range(5)) print(i);")
        tst.chkStdout("0", "1", "2", "3", "4")

        tst.execOp("for (i in range(5, 0, -1)) print(i);")
        tst.chkStdout("5", "4", "3", "2", "1")

        tst.execOp("for (i in range(0)) print(i);")
        tst.chkStdout()

        tst.execOp("for (i in range(1000,1000)) print(i);")
        tst.chkStdout()

        tst.execOp("for (i in range(9223372036854775800,9223372036854775807,5)) print(i);")
        tst.chkStdout("9223372036854775800", "9223372036854775805")

        tst.execOp("for (i in range(-9223372036854775807-1,9223372036854775807,9223372036854775807)) print(i);")
        tst.chkStdout("-9223372036854775808", "-1", "9223372036854775806")

        tst.execOp("for (i in range(9223372036854775807,-9223372036854775807-1,-9223372036854775807)) print(i);")
        tst.chkStdout("9223372036854775807", "0", "-9223372036854775807")

        tst.execOp("for (i in range(9223372036854775807,-9223372036854775807-1,-9223372036854775807-1)) print(i);")
        tst.chkStdout("9223372036854775807", "-1")
    }

    @Test fun testEmptyStatement() {
        chk(";;;;; return 123;", "int[123]")
    }

    @Test fun testEmptyBlock() {
        chk("if (1 > 0) {} else {}; return 123;", "int[123]")
    }

    @Test fun testAssignment() {
        chk("var x = 123; x += 456; return x;", "int[579]")
        chk("var x = 123; x -= 456; return x;", "int[-333]")
        chk("var x = 123; x *= 456; return x;", "int[56088]")
        chk("var x = 123456; x /= 789; return x;", "int[156]")
        chk("var x = 123456; x %= 789; return x;", "int[372]")

        chk("var x = 'Hello'; x += 'World'; return x;", "text[HelloWorld]")
        chk("var x = 'Hello'; x += 12345; return x;", "text[Hello12345]")
        chk("var x = 'Hello'; x += true; return x;", "text[Hellotrue]")
        chk("var x = 'Hello'; x += x'1234ABCD'; return x;", "text[Hello0x1234abcd]")
    }

    @Test fun testAssignmentErr() {
        chk("var x = true; x += false; return x;", "ct_err:binop_operand_type:+=:boolean:boolean")
        chk("var x = true; x += 123; return x;", "ct_err:binop_operand_type:+=:boolean:integer")
        chk("var x = true; x += 'Hello'; return x;", "ct_err:binop_operand_type:+=:boolean:text")
        chk("var x = 123; x += false; return x;", "ct_err:binop_operand_type:+=:integer:boolean")
        chk("var x = 123; x += 'Hello'; return x;", "ct_err:binop_operand_type:+=:integer:text")

        chkAssignmentErr("-=")
        chkAssignmentErr("*=")
        chkAssignmentErr("/=")
        chkAssignmentErr("%=")
    }

    @Test fun testComplexListAssignment() {
        val code = """
            function f(x: list<integer>): list<integer> {
                for (i in range(5)) x.add(i);
                return x;
            }
        """.trimIndent()
        chkFull("$code query q() { val p = list<integer>(); f(p)[2] = 777; return ''+p; }", listOf(), "text[[0, 1, 777, 3, 4]]")
    }

    @Test fun testComplexListAssignment2() {
        val code = """
            query q(x: integer, y: integer): text {
                val p = list<list<integer>>();
                for (i in range(3)) {
                    p.add(list<integer>());
                    for (j in range(3)) {
                        p[i].add(i * 10 + j);
                    }
                }
                p[x][y] = 777;
                return ''+p;
            }
        """.trimIndent()

        chkFull(code, listOf(RtIntValue(0), RtIntValue(0)), "text[[[777, 1, 2], [10, 11, 12], [20, 21, 22]]]")
        chkFull(code, listOf(RtIntValue(1), RtIntValue(1)), "text[[[0, 1, 2], [10, 777, 12], [20, 21, 22]]]")
        chkFull(code, listOf(RtIntValue(2), RtIntValue(2)), "text[[[0, 1, 2], [10, 11, 12], [20, 21, 777]]]")
    }

    @Test fun testCallChain() {
        val code = """
            function f(x: integer): map<integer, text> = [x:'Bob',x*2:'Alice'];
            query q(i: integer)
        """.trimIndent()

        chkFull("$code = f(123);", listOf(RtIntValue(0)), "map<integer,text>[int[123]=text[Bob],int[246]=text[Alice]]")
        chkFull("$code = f(123).values();", listOf(RtIntValue(0)), "list<text>[text[Bob],text[Alice]]")
        chkFull("$code = f(123).values().get(i);", listOf(RtIntValue(0)), "text[Bob]")
        chkFull("$code = f(123).values().get(i);", listOf(RtIntValue(1)), "text[Alice]")
        chkFull("$code = f(123).values().get(i).upperCase();", listOf(RtIntValue(0)), "text[BOB]")
        chkFull("$code = f(123).values().get(i).upperCase();", listOf(RtIntValue(1)), "text[ALICE]")
        chkFull("$code = f(123).values().get(i).upperCase().size();", listOf(RtIntValue(0)), "int[3]")
        chkFull("$code = f(123).values().get(i).upperCase().size();", listOf(RtIntValue(1)), "int[5]")
    }

    private fun chkAssignmentErr(op: String) {
        chk("var x = true; x $op false; return x;", "ct_err:binop_operand_type:$op:boolean:boolean")
        chk("var x = true; x $op 123; return x;", "ct_err:binop_operand_type:$op:boolean:integer")
        chk("var x = true; x $op 'Hello'; return x;", "ct_err:binop_operand_type:$op:boolean:text")
        chk("var x = 123; x $op false; return x;", "ct_err:binop_operand_type:$op:integer:boolean")
        chk("var x = 123; x $op 'Hello'; return x;", "ct_err:binop_operand_type:$op:integer:text")
        chk("var x = 'Hello'; x $op false; return x;", "ct_err:binop_operand_type:$op:text:boolean")
        chk("var x = 'Hello'; x $op 123; return x;", "ct_err:binop_operand_type:$op:text:integer")
        chk("var x = 'Hello'; x $op 'Hello'; return x;", "ct_err:binop_operand_type:$op:text:text")
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
