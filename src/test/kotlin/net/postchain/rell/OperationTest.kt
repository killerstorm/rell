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
}
