package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class UserFunctionTest: BaseRellTest(false) {
    @Test fun testSimple() {
        chkFn("function f(): integer = 123;", "f()", "int[123]")
        chkFn("function f(): integer { return 123; }", "f()", "int[123]")
        chkFn("function f(x: integer): integer { return x; }", "f(123)", "int[123]")
        chkFn("function f(x: integer): integer { return x; }", "f(456)", "int[456]")
    }

    @Test fun testNoReturnValue() {
        chkFn("function f(){}", "123", "int[123]")
        chkFn("function f(): integer {}", "123", "ct_err:fun_noreturn:f")
        chkFn("function f(): integer { if (1 > 0) return 123; }", "123", "ct_err:fun_noreturn:f")
        chkFn("function f() = print('Hello');", "f()", "ct_err:query_exprtype_unit")

        chkFnEx("function f() = print('Hello');", "{ f(); return 123; }", "int[123]")
        chkStdout("Hello")
    }

    @Test fun testReturnType() {
        chkFn("function f(): integer = 123;", "f()", "int[123]")
        chkFn("function f(): integer = 'Hello';", "f()", "ct_err:entity_rettype:integer:text")
        chkFn("function f(): integer { return 'Hello'; }", "f()", "ct_err:entity_rettype:integer:text")
        chkFn("function f(): integer { if (1 > 0) return 123; return 'Hello'; }", "f()", "ct_err:entity_rettype:integer:text")
        chkFn("function f(): integer { if (1 > 0) return 123; return 456; }", "f()", "int[123]")
        chkFn("function g(x: integer): integer = 123; function f(x: integer) = g(x);", "f(123)", "ct_err:entity_rettype:unit:integer")
    }

    @Test fun testDbSelect() {
        tstCtx.useSql = true
        tst.defs = listOf("class user { name: text; }")
        chkOp("create user('Bob'); create user('Alice');")

        chkFn("function f(name: text): user = user @ { name };", "f('Bob')", "user[1]")
        chkFn("function f(name: text): user = user @ { name };", "f('Alice')", "user[2]")
    }

    @Test fun testDbUpdate() {
        tstCtx.useSql = true
        tst.defs = listOf("class user { name: text; mutable score: integer; }")
        chkOp("create user('Bob', 100); create user('Alice', 250);")

        val fn = "function f(name: text, s: integer): integer { update user @ { name } ( score += s ); return s; }"

        // Database modifications must fail at run-time when indirectly invoked from a query.
        chkFn(fn, "f('Bob', 500)", "rt_err:no_db_update")
        tst.chkData("user(1,Bob,100)", "user(2,Alice,250)")

        // When f() is called from an operation, everything must work.
        chkFnOp(fn, "f('Bob', 500);")
        tst.chkData("user(1,Bob,600)", "user(2,Alice,250)")
        chkFnOp(fn, "f('Alice', 750);")
        tst.chkData("user(1,Bob,600)", "user(2,Alice,1000)")
    }

    @Test fun testResultType() {
        chkFnEx("function f(): integer = 123;", "{ var x: text; x = f(); return 123; }", "ct_err:stmt_assign_type:text:integer")
        chkFnEx("function f() {}", "{ var x: text; x = f(); return 123; }", "ct_err:stmt_assign_type:text:unit")
        chkFnEx("function f() {}", "{ val x = f(); return 123; }", "ct_err:stmt_var_unit:x")
    }

    @Test fun testWrongArgs() {
        chkFn("function f(){}", "f(123)", "ct_err:expr_call_argcnt:f:0:1")
        chkFn("function f(x:integer){}", "f()", "ct_err:expr_call_argcnt:f:1:0")
        chkFn("function f(x:integer){}", "f(123, 456)", "ct_err:expr_call_argcnt:f:1:2")
        chkFn("function f(x:integer,y:text){}", "f()", "ct_err:expr_call_argcnt:f:2:0")
        chkFn("function f(x:integer,y:text){}", "f(123)", "ct_err:expr_call_argcnt:f:2:1")
        chkFn("function f(x:integer,y:text){}", "f(123,'Hello','World')", "ct_err:expr_call_argcnt:f:2:3")

        chkFn("function f(x:integer){}", "f('Hello')", "ct_err:expr_call_argtype:f:0:integer:text")
        chkFn("function f(x:integer,y:text){}", "f('Hello','World')", "ct_err:expr_call_argtype:f:0:integer:text")
        chkFn("function f(x:integer,y:text){}", "f('Hello',123)", "ct_err:expr_call_argtype:f:0:integer:text")
        chkFn("function f(x:integer,y:text){}", "f(123,456)", "ct_err:expr_call_argtype:f:1:text:integer")
    }

    @Test fun testRecursion() {
        val fn = """
            function f(x: integer): integer {
                if (x == 0) {
                    return 1;
                } else {
                    return x * f(x - 1);
                }
            }
        """.trimIndent()

        chkFn(fn, "f(0)", "int[1]")
        chkFn(fn, "f(1)", "int[1]")
        chkFn(fn, "f(2)", "int[2]")
        chkFn(fn, "f(3)", "int[6]")
        chkFn(fn, "f(4)", "int[24]")
        chkFn(fn, "f(5)", "int[120]")
        chkFn(fn, "f(6)", "int[720]")
        chkFn(fn, "f(7)", "int[5040]")
        chkFn(fn, "f(8)", "int[40320]")
        chkFn(fn, "f(9)", "int[362880]")
        chkFn(fn, "f(10)", "int[3628800]")
    }

    @Test fun testMutualRecursion() {
        val fn = """
            function foo(n: integer) {
                print('foo', n);
                if (n > 0) bar(n - 1);
            }
            function bar(n: integer) {
                print('bar', n);
                if (n > 0) foo(n - 1);
            }
        """.trimIndent()

        chkFnEx(fn, "{ foo(5); return 0; }", "int[0]")
        chkStdout("foo 5", "bar 4", "foo 3", "bar 2", "foo 1", "bar 0")
    }

    @Test fun testCallUnderAt() {
        tstCtx.useSql = true
        tst.defs = listOf("class user { name: text; id: integer; }")
        chkOp("create user('Bob',123); create user('Alice',456);")

        val fn = "function foo(a: text): text = a.upperCase();"

        chkFnEx(fn, "= user @ { .name.upperCase() == foo('bob') };", "user[1]")
        chkFnEx(fn, "= user @ { .name.upperCase() == foo('alice') };", "user[2]")

        chkFnEx(fn, "= user @ { foo(.name) == 'BOB' };", "ct_err:expr_sqlnotallowed")
        chkFnEx(fn, "= user @ { .id == 123 } ( foo(.name) );", "ct_err:expr_sqlnotallowed")
    }

    private fun chkFn(fnCode: String, callCode: String, expected: String) {
        chkFnEx(fnCode, "= $callCode ;", expected)
    }

    private fun chkFnEx(fnCode: String, queryCode: String, expected: String) {
        val code = "$fnCode query q() $queryCode"
        tst.chkQueryEx(code, listOf(), expected)
    }

    private fun chkFnOp(fnCode: String, callCode: String, expected: String = "OK") {
        val code = "$fnCode operation o() { $callCode }"
        tst.chkOpEx(code, expected)
    }
}
