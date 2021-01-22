/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class OperationTest: BaseRellTest() {
    @Test fun testReturn() {
        chkOp("print('Hello'); print('World');")
        chkOut("Hello", "World")

        chkOp("print('Hello'); return;")
        chkOut("Hello")

        chkOp("print('Hello'); return; print('World');", "ct_err:stmt_deadcode")
        chkOut()

        chkOp("return 123;", "ct_err:stmt_return_op_value")
    }

    @Test fun testGuard() {
        chkOp("guard {}")

        chkOp("print('Bob'); guard { print('Alice'); } print('Trudy');")
        chkOut("Bob", "Alice", "Trudy")

        chkOp("guard {} guard {}", "ct_err:stmt_guard_after_guard")
        chkOp("{ guard {} }", "ct_err:stmt_guard_nested")
        chkOp("if (2 > 1) { guard {} }", "ct_err:stmt_guard_nested")
        chkOp("if (2 > 1) guard {}", "ct_err:stmt_guard_nested")
        chkOp("while (true) { guard {} }", "ct_err:stmt_guard_nested")
        chkOp("while (true) guard {}", "ct_err:stmt_guard_nested")
    }

    @Test fun testGuardUpdate() {
        def("entity user { name: text; mutable score: integer; }")

        chkOp("guard { create user(name = 'Bob', score = 123); }", "ct_err:no_db_update:guard")
        chkOp("guard { update user @*{} ( .score += 10 ); }", "ct_err:no_db_update:guard")
        chkOp("guard { delete user @*{}; }", "ct_err:no_db_update:guard")

        chkOp("create user(name = 'Bob', score = 123); guard {}", "ct_err:no_db_update:guard")
        chkOp("update user @*{} ( .score += 10 ); guard {}", "ct_err:no_db_update:guard")
        chkOp("delete user @*{}; guard {}", "ct_err:no_db_update:guard")

        chkOp("guard {} create user(name = 'Bob', score = 123);", "OK")
        chkOp("guard {} update user @*{} ( .score += 10 );", "OK")
        chkOp("guard {} delete user @*{};", "OK")
    }

    @Test fun testGuardUpdateFunction() {
        def("entity user { name: text; mutable score: integer; }")
        def("function f() { delete user @* {}; }")

        chkOp("f();", "OK")
        chkOp("guard { f(); }", "rt_err:no_db_update:def")
        chkOp("f(); guard {}", "rt_err:no_db_update:def")
    }

    @Test fun testGuardInQueryFunction() {
        chkCompile("query q() { guard {} return 123; }", "ct_err:stmt_guard_wrong_def:QUERY")
        chkCompile("function f() { guard {} }", "ct_err:stmt_guard_wrong_def:FUNCTION")
    }

    @Test fun testCallOperation() {
        def("operation op(x: integer, y: text) {}")
        chk("op(123, 'Hello')", "struct<op>[x=int[123],y=text[Hello]]")
        chk("op('Hello', 123)", "ct_err:[expr_call_argtype:op:0:x:integer:text][expr_call_argtype:op:1:y:text:integer]")
        chk("op(123, 'Hello', 456)", "ct_err:expr:call:arg_count:op:2:3")
        chk("op(123+456, 'Hello' + 'World')", "struct<op>[x=int[579],y=text[HelloWorld]]")
        chk("'' + op(123, 'Hello')", "text[struct<op>{x=123,y=Hello}]")
        chk("_type_of(op(123, 'Hello'))", "text[struct<op>]")
    }

    @Test fun testCallOperationSideEffect() {
        def("operation foo(x: integer, y: text) { print('foo'); }")
        def("function f() { print('f'); return 0; }")
        chk("f()", "int[0]")
        chkOut("f")
        chk("foo(123, 'Hello')", "struct<foo>[x=int[123],y=text[Hello]]")
        chkOut()
    }

    @Test fun testOperationTypeCompatibility() {
        def("operation bob(x: integer){}")
        def("operation alice(x: integer){}")
        def("operation trudy(t: text){}")
        chkEx("{ var v = bob(123).to_operation(); v = alice(456).to_operation(); return v; }", "op[alice(int[456])]")
        chkEx("{ var v = bob(123).to_operation(); v = trudy('Hello').to_operation(); return v; }", "op[trudy(text[Hello])]")
    }

    @Test fun testOperationTypeExplicit() {
        def("operation bob(x: integer){}")
        chkEx("{ var v: operation; v = bob(123); return 0; }", "ct_err:stmt_assign_type:[operation]:[struct<bob>]")
        chkEx("{ var v: struct<bob>; v = bob(123).to_operation(); return 0; }", "ct_err:stmt_assign_type:[struct<bob>]:[operation]")
        chkEx("{ var v: operation; v = bob(123).to_operation(); return v; }", "op[bob(int[123])]")
    }

    @Test fun testOperationTypeOps() {
        def("operation foo(x: integer, y: text){}")
        def("operation bar(x: integer, y: text){}")
        def("function foo_op(x: integer, y: text) = foo(x, y).to_operation();")
        def("function bar_op(x: integer, y: text) = bar(x, y).to_operation();")

        chk("foo_op(123,'Hello') == foo_op(456,'Bye')", "boolean[false]")
        chk("foo_op(123,'Hello') == foo_op(123,'Hello')", "boolean[true]")
        chk("foo_op(123,'Hello') == foo_op(579-456,'Hello')", "boolean[true]")
        chk("foo_op(123,'Hello') != foo_op(456,'Bye')", "boolean[true]")
        chk("foo_op(123,'Hello') != foo_op(123,'Hello')", "boolean[false]")

        chk("foo_op(123,'Hello') == bar_op(123,'Hello')", "boolean[false]")
        chk("foo_op(123,'Hello') != bar_op(123,'Hello')", "boolean[true]")

        chk("foo_op(123,'Hello') < foo_op(123,'Hello')", "ct_err:binop_operand_type:<:[operation]:[operation]")
        chk("foo_op(123,'Hello') > foo_op(123,'Hello')", "ct_err:binop_operand_type:>:[operation]:[operation]")
        chk("foo_op(123,'Hello') <= foo_op(123,'Hello')", "ct_err:binop_operand_type:<=:[operation]:[operation]")
        chk("foo_op(123,'Hello') >= foo_op(123,'Hello')", "ct_err:binop_operand_type:>=:[operation]:[operation]")
    }

    @Test fun testOperationTypeAsMapKey() {
        def("operation foo(x: integer, y: text){}")
        def("operation bar(p: text, q: integer){}")
        def("function foo_op(x: integer, y: text) = foo(x, y).to_operation();")
        def("function bar_op(p: text, q: integer) = bar(p, q).to_operation();")
        chk("[ foo_op(123,'Hello') : 'Bob', bar_op('Bye', 456) : 'Alice' ]",
                "map<operation,text>[op[foo(int[123],text[Hello])]=text[Bob],op[bar(text[Bye],int[456])]=text[Alice]]")
    }

    @Test fun testDefautParameters() {
        def("operation foo(x: integer = 123, y: text = 'Hello'){}")
        chk("foo()", "struct<foo>[x=int[123],y=text[Hello]]")
        chk("foo(456)", "struct<foo>[x=int[456],y=text[Hello]]")
        chk("foo(456,'Bye')", "struct<foo>[x=int[456],y=text[Bye]]")
    }
}
