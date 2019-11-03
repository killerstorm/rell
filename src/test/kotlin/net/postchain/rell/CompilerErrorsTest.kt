package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class CompilerErrorsTest: BaseRellTest(false) {
    @Test fun testFunctionParamBadType() {
        def("function foo(x: integer, y: UNKNOWN_TYPE, z: text) {}")
        chkFunctionParamBadType("unknown_type:UNKNOWN_TYPE")
    }

    @Test fun testFunctionParamNoType() {
        def("function foo(x: integer, y, z: text) {}")
        chkFunctionParamBadType("unknown_name_type:y")
    }

    private fun chkFunctionParamBadType(typeErr: String) {
        tst.wrapInit = true

        chkCompile("function m() { foo(123, null, 'Hello'); }", "ct_err:$typeErr")
        chkCompile("function m() { foo(123, false, 'Hello'); }", "ct_err:$typeErr")
        chkCompile("function m() { foo(123, 0, 'Hello'); }", "ct_err:$typeErr")

        chkCompile("function m() { foo(); }", "ct_err:[$typeErr][expr_call_argcnt:foo:3:0]")
        chkCompile("function m() { foo(123, 'Hello'); }", "ct_err:[$typeErr][expr_call_argcnt:foo:3:2]")
        chkCompile("function m() { foo(123, null, 'Hello', false); }", "ct_err:[$typeErr][expr_call_argcnt:foo:3:4]")

        chkCompile("function m() { foo('Bye', null, 'Hello'); }", "ct_err:[$typeErr][expr_call_argtype:foo:0:x:integer:text]")
        chkCompile("function m() { foo(123, null, 456); }", "ct_err:[$typeErr][expr_call_argtype:foo:2:z:text:integer]")

        chkCompile("function m() { foo('Hello', null, 123); }", """ct_err:[$typeErr]
            [expr_call_argtype:foo:0:x:integer:text]
            [expr_call_argtype:foo:2:z:text:integer]
        """)
    }

    @Test fun testFunctionParamDuplicate() {
        chkCompile("function foo(x: integer, x: text) {}", "ct_err:dup_param_name:x")
        chkCompile("function foo(x: integer, x: text) { val t: integer = x; }", "ct_err:dup_param_name:x")
        chkCompile("function foo(x: integer, x: text) { val t: text = x; }", "ct_err:[dup_param_name:x][stmt_var_type:t:text:integer]")
    }

    @Test fun testStmt() {
        chkStmt("print(X); print(Y); print(Z);", "ct_err:[unknown_name:X][unknown_name:Y][unknown_name:Z]")

        chkStmt("if ('not_a_boolean') print(X); else print(Y);", "ct_err:[stmt_if_expr_type:boolean:text][unknown_name:X][unknown_name:Y]")
        chkStmt("if (BAD) print(X); else print(Y);", "ct_err:[unknown_name:BAD][unknown_name:X][unknown_name:Y]")

        chkStmt("while ('not_a_boolean') print(X);", "ct_err:[stmt_while_expr_type:boolean:text][unknown_name:X]")
        chkStmt("while (BAD) print(X);", "ct_err:[unknown_name:BAD][unknown_name:X]")

        chkStmt("for (i in true) print(X);", "ct_err:[stmt_for_expr_type:boolean][unknown_name:X]")
        chkStmt("for (i in BAD) print(X);", "ct_err:[unknown_name:BAD][unknown_name:X]")

        chkStmt("X = Y;", "ct_err:[unknown_name:X][unknown_name:Y]")
        chkStmt("X += Y;", "ct_err:[unknown_name:X][unknown_name:Y]")

        chkStmt("when (BAD) { 0 -> print(X); 'hello' -> print(Y); A -> B; else -> print(Z); }", """ct_err:
            [unknown_name:BAD]
            [unknown_name:X]
            [unknown_name:Y]
            [unknown_name:A]
            [unknown_name:B]
            [unknown_name:Z]
        """)
    }

    @Test fun testStmtReturn() {
        chkCompile("function f(): integer {}", "ct_err:fun_noreturn:f")
        chkCompile("function f(): integer { return BAD; }", "ct_err:unknown_name:BAD")
    }

    private fun chkStmt(code: String, expected: String) {
        chkCompile("function f() { $code }", expected)
    }

    //TODO
    // bad record attr type
    // bad class attr type
    // bad tuple element type
    // bad variable type
    // bad type (other cases)
}
