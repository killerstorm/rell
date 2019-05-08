package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class OperationTest: BaseRellTest() {
    @Test fun testReturn() {
        chkOp("print('Hello'); print('World');")
        chkStdout("Hello", "World")

        chkOp("print('Hello'); return;")
        chkStdout("Hello")

        chkOp("print('Hello'); return; print('World');", "ct_err:stmt_deadcode")
        chkStdout()

        chkOp("return 123;", "ct_err:stmt_return_op_value")
    }
}
