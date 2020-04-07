/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test
import kotlin.test.assertTrue

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
        chkOut("Hello")
    }

    @Test fun testReturnType() {
        chkFn("function f(): integer = 123;", "f()", "int[123]")
        chkFn("function f(): integer = 'Hello';", "f()", "ct_err:fn_rettype:[integer]:[text]")
        chkFn("function f(): integer { return 'Hello'; }", "f()", "ct_err:fn_rettype:[integer]:[text]")
        chkFn("function f(): integer { if (1 > 0) return 123; return 'Hello'; }", "f()", "ct_err:fn_rettype:[integer]:[text]")
        chkFn("function f(): integer { if (1 > 0) return 123; return 456; }", "f()", "int[123]")
        chkFn("function g(x: integer): integer = x + 1; function f(x: integer) = g(x);", "f(123)", "int[124]")
    }

    @Test fun testDbSelect() {
        tstCtx.useSql = true
        def("entity user { name: text; }")
        chkOp("create user('Bob'); create user('Alice');")

        chkFn("function f(name: text): user = user @ { name };", "f('Bob')", "user[1]")
        chkFn("function f(name: text): user = user @ { name };", "f('Alice')", "user[2]")
    }

    @Test fun testDbUpdate() {
        tstCtx.useSql = true
        def("entity user { name: text; mutable score: integer; }")
        chkOp("create user('Bob', 100); create user('Alice', 250);")

        val fn = "function f(name: text, s: integer): integer { update user @ { name } ( score += s ); return s; }"

        // Database modifications must fail at run-time when indirectly invoked from a query.
        chkFn(fn, "f('Bob', 500)", "rt_err:no_db_update:def")
        tst.chkData("user(1,Bob,100)", "user(2,Alice,250)")

        // When f() is called from an operation, everything must work.
        chkFnOp(fn, "f('Bob', 500);")
        tst.chkData("user(1,Bob,600)", "user(2,Alice,250)")
        chkFnOp(fn, "f('Alice', 750);")
        tst.chkData("user(1,Bob,600)", "user(2,Alice,1000)")
    }

    @Test fun testResultType() {
        chkFnEx("function f(): integer = 123;", "{ var x: text; x = f(); return 123; }", "ct_err:stmt_assign_type:[text]:[integer]")
        chkFnEx("function f() {}", "{ var x: text; x = f(); return 123; }", "ct_err:stmt_assign_type:[text]:[unit]")
        chkFnEx("function f() {}", "{ val x = f(); return 123; }", "ct_err:stmt_var_unit:x")
    }

    @Test fun testWrongArgs() {
        chkFnErr("function f(){}", "f(123)", "ct_err:expr_call_argcnt:f:0:1")
        chkFnErr("function f(x:integer){}", "f()", "ct_err:expr_call_argcnt:f:1:0")
        chkFnErr("function f(x:integer){}", "f(123, 456)", "ct_err:expr_call_argcnt:f:1:2")
        chkFnErr("function f(x:integer,y:text){}", "f()", "ct_err:expr_call_argcnt:f:2:0")
        chkFnErr("function f(x:integer,y:text){}", "f(123)", "ct_err:expr_call_argcnt:f:2:1")
        chkFnErr("function f(x:integer,y:text){}", "f(123,'Hello','World')", "ct_err:expr_call_argcnt:f:2:3")

        chkFnErr("function f(x:integer){}", "f('Hello')", "ct_err:expr_call_argtype:f:0:x:integer:text")
        chkFnErr("function f(x:integer,y:text){}", "f('Hello','World')", "ct_err:expr_call_argtype:f:0:x:integer:text")
        chkFnErr("function f(x:integer,y:text){}", "f(123,456)", "ct_err:expr_call_argtype:f:1:y:text:integer")

        chkFnErr("function f(x:integer,y:text){}", "f('Hello',123)", """ct_err:
            [expr_call_argtype:f:0:x:integer:text]
            [expr_call_argtype:f:1:y:text:integer]
        """)
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
        chkOut("foo 5", "bar 4", "foo 3", "bar 2", "foo 1", "bar 0")
    }

    @Test fun testCallUnderAt() {
        tstCtx.useSql = true
        def("entity user { name: text; id: integer; }")
        chkOp("create user('Bob',123); create user('Alice',456);")

        val fn = "function foo(a: text): text = a.upper_case();"

        chkFnEx(fn, "= user @ { .name.upper_case() == foo('bob') };", "user[1]")
        chkFnEx(fn, "= user @ { .name.upper_case() == foo('alice') };", "user[2]")

        chkFnEx(fn, "= user @ { foo(.name) == 'BOB' };", "ct_err:expr_sqlnotallowed")
        chkFnEx(fn, "= user @ { .id == 123 } ( foo(.name) );", "ct_err:expr_sqlnotallowed")
    }

    @Test fun testShortBodyUnitType() {
        def("function foo(x: list<integer>) { x.add(123); }")
        def("function bar(x: list<integer>) = foo(x);")
        chkEx("{ val x = list<integer>(); foo(x); return x; }", "list<integer>[int[123]]")
        chkEx("{ val x = list<integer>(); bar(x); return x; }", "list<integer>[int[123]]")
    }

    @Test fun testInferReturnType() {
        chkFn("function f() = 123;", "f()", "int[123]")
        chkFn("function f() = 123;", "_type_of(f())", "text[integer]")
        chkFn("function f() = 'Hello';", "_type_of(f())", "text[text]")
        chkFn("function f(x: integer) = null;", "_type_of(f(0))", "text[null]")
    }

    @Test fun testInferReturnTypeComplexBody() {
        chkFn("function f() { return 123; }", "f()", "int[123]")
        chkFn("function f() { return 'foobar'; }", "f()", "text[foobar]")
        chkFn("function f() { return 123; }", "_type_of(f())", "text[integer]")
        chkFn("function f(x: integer) { return null; }", "_type_of(f(0))", "text[null]")

        chkFn("function f(x: integer) { if (x > 0) return 123; return 456; }", "_type_of(f(0))", "text[integer]")
        chkFn("function f(x: integer) { if (x > 0) return 123; return null; }", "_type_of(f(0))", "text[integer?]")
        chkFn("function f(x: integer) { if (x > 0) return null; return 123; }", "_type_of(f(0))", "text[integer?]")
        chkFn("function f(x: integer) { if (x > 0) return 123; return 'Hello'; }", "_type_of(f(0))", "ct_err:fn_rettype:[integer]:[text]")
        chkFn("function f(x: integer) { if (x > 0) return 'Hello'; return 123; }", "_type_of(f(0))", "ct_err:fn_rettype:[text]:[integer]")
        chkFn("function f(x: integer) { if (x > 0) return 123; return; }", "_type_of(f(0))", "ct_err:fn_rettype:[integer]:[unit]")
        chkFn("function f(x: integer) { if (x > 0) return; return 123; }", "0", "ct_err:fn_rettype:[unit]:[integer]")
        chkFn("function f(x: integer) { if (x > 0) return 123; }", "_type_of(f(0))", "ct_err:fun_noreturn:f")
        chkFn("function f(x: integer) { if (x > 0) return; }", "f(0)", "ct_err:query_exprtype_unit")
        chkFn("function f(x: integer) { }", "f(0)", "ct_err:query_exprtype_unit")
    }

    @Test fun testInferReturnTypeDefinitionOrder() {
        chkFn("function f() = 123; function g(): text = _type_of(f());", "g()", "text[integer]")
        chkFn("function f() = 123; function g(): integer = f();", "g()", "int[123]")
        chkFn("function f() = 123; function g() = _type_of(f());", "g()", "text[integer]")
        chkFn("function f() = 123; function g() = f();", "_type_of(g())", "text[integer]")
        chkFn("function f() = 123; function g() = f();", "g()", "int[123]")

        chkFn("function g(): text = _type_of(f()); function f() = 123;", "g()", "text[integer]")
        chkFn("function g(): integer = f(); function f() = 123;", "g()", "int[123]")
        chkFn("function g() = _type_of(f()); function f() = 123;", "g()", "text[integer]")
        chkFn("function g() = f(); function f() = 123;", "_type_of(g())", "text[integer]")
        chkFn("function g() = f(); function f() = 123;", "g()", "int[123]")
    }

    @Test fun testInferReturnTypeDefinitionOrderMultiModule() {
        file("lib1/f1.rell", "function f(x: integer) = g(x);")
        file("lib1/f2.rell", "import lib2; function g(x: integer) = lib2.h(x);")
        file("lib2.rell", "module; function h(x: integer) = x + 1;")
        file("lib3.rell", "module; import lib1; function z(x: integer) = lib1.f(x);")
        chkFn("import lib1;", "lib1.f(123)", "int[124]")
        chkFn("import lib1;", "_type_of(lib1.f(123))", "text[integer]")
        chkFn("import lib3;", "lib3.z(123)", "int[124]")
        chkFn("import lib3;", "_type_of(lib3.z(123))", "text[integer]")
    }

    @Test fun testInferReturnTypeDirectRecursion() {
        chkFn("function f(x: integer) = if (x > 0) f(x - 1) else 0;", "0", "ct_err:fn_type_recursion:FUNCTION:f")
        chkFn("function f(x: integer) = if (x > 0) f(x - 1) else 0;", "f(0)",
                "ct_err:[fn_type_recursion:FUNCTION:f][fn_type_recursion:FUNCTION:f]")
        chkFn("function f(x: integer) = if (x > 0) f(x - 1) + 1 else 0;", "f(3)",
                "ct_err:[fn_type_recursion:FUNCTION:f][binop_operand_type:+:[<error>]:[integer]]")
        chkFn("function f(x: integer) { if (x > 0) return f(x - 1); return 0; }", "0", "ct_err:fn_type_recursion:FUNCTION:f")
        chkFn("function f(x: integer) { if (x > 0) return f(x - 1) + 1; return 0; }", "0",
                "ct_err:[fn_type_recursion:FUNCTION:f][binop_operand_type:+:[<error>]:[integer]]")
    }

    @Test fun testInferReturnTypeIndirectRecursion() {
        chkFn("function g(x: integer) = f(x); function f(x: integer) = if (x > 0) g(x - 1) else 0;", "0",
                "ct_err:[fn_type_recursion:FUNCTION:f][fn_type_recursion:FUNCTION:g]")
        chkFn("function g(x: integer) = f(x); function f(x: integer) = if (x > 0) g(x - 1) else 0;", "_type_of(f(0))",
                "ct_err:[fn_type_recursion:FUNCTION:f][fn_type_recursion:FUNCTION:g][fn_type_recursion:FUNCTION:f]")
        chkFn("function f(x: integer) = if (x > 0) g(x - 1) else 0; function g(x: integer) = f(x);", "0",
                "ct_err:[fn_type_recursion:FUNCTION:g][fn_type_recursion:FUNCTION:f]")
        chkFn("function g(x: integer) = f(x); function f(x: integer) { if (x > 0) return g(x - 1); return 0; }", "0",
                "ct_err:[fn_type_recursion:FUNCTION:f][fn_type_recursion:FUNCTION:g]")
        chkFn("function f(x: integer) { if (x > 0) return g(x - 1); return 0; } function g(x: integer) = f(x);", "0",
                "ct_err:[fn_type_recursion:FUNCTION:g][fn_type_recursion:FUNCTION:f]")
    }

    @Test fun testInferReturnTypeIndirectRecursionMultiFile() {
        file("lib/f1.rell", "function f(x: integer) = g(x);")
        file("lib/f2.rell", "function g(x: integer) = h(x);")
        file("lib/f3.rell", "function h(x: integer) = f(x);")

        chkCompile("import lib;", """ct_err:
            [lib/f1.rell:fn_type_recursion:FUNCTION:g]
            [lib/f2.rell:fn_type_recursion:FUNCTION:h]
            [lib/f3.rell:fn_type_recursion:FUNCTION:f]
        """)
    }

    @Test fun testInferReturnTypeIndirectRecursionMultiModule() {
        file("lib1.rell", "module; import lib2; function f(x: integer) = lib2.g(x);")
        file("lib2.rell", "module; import lib3; function g(x: integer) = lib3.h(x);")
        file("lib3.rell", "module; import lib1; function h(x: integer) = lib1.f(x);")

        chkCompile("import lib1;", """ct_err:
            [lib1.rell:fn_type_recursion:FUNCTION:g]
            [lib2.rell:fn_type_recursion:FUNCTION:h]
            [lib3.rell:fn_type_recursion:FUNCTION:f]
        """)
    }

    @Test fun testInferReturnTypeStackOverflow() {
        // Current behavior is not perfect: last 100 functions should be compiled without errors. But difficult to
        // achieve that - to be improved later.

        val n = 1000 // experimental threshold for real stack overflow is 500
        for (x in 0 until n) def("function f_$x(x: integer) = f_${x+1}(x);")

        val res = tst.compileModule("function f_$n(x: integer) = x + 1;")

        assertTrue(res.startsWith("ct_err:" +
                "[fn_type_stackoverflow:FUNCTION:f_1][fn_type_stackoverflow:FUNCTION:f_2][fn_type_stackoverflow:FUNCTION:f_3]" +
                "[fn_type_stackoverflow:FUNCTION:f_4][fn_type_stackoverflow:FUNCTION:f_5][fn_type_stackoverflow:FUNCTION:f_6]" +
                "[fn_type_stackoverflow:FUNCTION:f_7][fn_type_stackoverflow:FUNCTION:f_8][fn_type_stackoverflow:FUNCTION:f_9]" +
                "[fn_type_stackoverflow:FUNCTION:f_10]"),
                res
        )

        assertTrue(res.endsWith(
                "[fn_type_stackoverflow:FUNCTION:f_990][fn_type_stackoverflow:FUNCTION:f_991][fn_type_stackoverflow:FUNCTION:f_992]" +
                "[fn_type_stackoverflow:FUNCTION:f_993][fn_type_stackoverflow:FUNCTION:f_994][fn_type_stackoverflow:FUNCTION:f_995]" +
                "[fn_type_stackoverflow:FUNCTION:f_996][fn_type_stackoverflow:FUNCTION:f_997][fn_type_stackoverflow:FUNCTION:f_998]" +
                "[fn_type_stackoverflow:FUNCTION:f_999][fn_type_stackoverflow:FUNCTION:f_1000]"),
                res
        )
    }

    @Test fun testReturnTypeCtError() {
        chkCompile("function f(): unknown_type { return 123; }", "ct_err:unknown_type:unknown_type")
        chkCompile("function f(): unknown_type {}", "ct_err:[fun_noreturn:f][unknown_type:unknown_type]")
        chkCompile("function f(): unknown_type { val x: integer = 'Hello'; return 123; }",
                "ct_err:[unknown_type:unknown_type][stmt_var_type:x:[integer]:[text]]")
    }

    private fun chkFn(fnCode: String, callCode: String, expected: String) {
        chkFnEx(fnCode, "= $callCode ;", expected)
    }

    private fun chkFnErr(fnCode: String, callCode: String, expected: String) {
        chkFnEx(fnCode, "{ $callCode; return 0; }", expected)
    }

    private fun chkFnEx(fnCode: String, queryCode: String, expected: String) {
        val code = "$fnCode query q() $queryCode"
        tst.chkQueryEx(code, "q", listOf(), expected)
    }

    private fun chkFnOp(fnCode: String, callCode: String, expected: String = "OK") {
        val code = "$fnCode operation o() { $callCode }"
        chkOpFull(code, expected)
    }
}
