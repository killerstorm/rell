package net.postchain.rell

import org.junit.Test

class OperationTest: BaseRellTest() {
    @Test fun testReturn() {
        tst.execOp("print('Hello'); print('World');")
        tst.chkStdout("Hello", "World")

        tst.execOp("print('Hello'); return; print('World');")
        tst.chkStdout("Hello")

        tst.chkOp("return 123;", "ct_err:stmt_return_op_value")
    }
}
