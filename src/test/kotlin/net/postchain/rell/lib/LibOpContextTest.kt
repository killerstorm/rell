package net.postchain.rell.lib

import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.runtime.Rt_OpContext
import org.junit.Test

class LibOpContextTest: BaseRellTest(false) {
    @Test fun testLastBlockTime() {
        tst.opContext = Rt_OpContext(12345, -1, listOf())
        chkOp("print(op_context.last_block_time);", "")
        tst.chkStdout("12345")

        chkOp("val t: timestamp = op_context.last_block_time; print(t);", "") // Will fail when timestamp type becomes intependent.
        tst.chkStdout("12345")
        chkOp("val t: integer = op_context.last_block_time; print(t);", "")
        tst.chkStdout("12345")

        chkOpFull("function f(): timestamp = op_context.last_block_time; operation o() { print(f()); }", "")
        tst.chkStdout("12345")

        tst.opContext = null
        chk("op_context.last_block_time", "ct_err:op_ctx_noop")
        chkFull("function f(): timestamp = op_context.last_block_time; query q() = f();", listOf(),
                "rt_err:fn_last_block_time_noop")

        chk("op_context", "ct_err:unknown_name:op_context")
    }

    @Test fun testLastBlockTimeAsDefaultValue() {
        tst.useSql = true
        tst.defs = listOf("class foo { t: integer = op_context.last_block_time; }")
        tst.opContext = Rt_OpContext(12345, -1, listOf())

        execOp("create foo();")
        chkData("foo(1,12345)")
    }

    @Test fun testTransaction() {
        tst.useSql = true
        tst.inserts = LibClassesTest.BLOCK_INSERTS
        tst.opContext = Rt_OpContext(-1, 444, listOf())

        execOp("print(_typeOf(op_context.transaction));")
        tst.chkStdout("transaction")

        execOp("print(_strictStr(op_context.transaction));")
        tst.chkStdout("transaction[444]")
    }

    @Test fun testTransactionAsDefaultValue() {
        tst.useSql = true
        tst.inserts = LibClassesTest.BLOCK_INSERTS
        tst.defs = listOf("class foo { t: transaction = op_context.transaction; }")
        tst.opContext = Rt_OpContext(-1, 444, listOf())

        execOp("create foo();")
        tst.chkData("foo(1,444)")
    }

    @Test fun testAssignmentValue() {
        chkOp("op_context.last_block_time = 0;", "ct_err:expr_bad_dst")
        chkOp("op_context.transaction = 0;", "ct_err:expr_bad_dst")
    }
}
