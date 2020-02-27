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
}
