package net.postchain.rell.lib

import net.postchain.rell.runtime.Rt_OpContext
import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LibOpContextTest: BaseRellTest(false) {
    @Test fun testLastBlockTime() {
        tst.opContext = Rt_OpContext(12345, -1, listOf())
        chkOp("print(op_context.last_block_time);")
        chkStdout("12345")

        chkOp("val t: timestamp = op_context.last_block_time; print(t);") // Will fail when timestamp type becomes intependent.
        chkStdout("12345")
        chkOp("val t: integer = op_context.last_block_time; print(t);")
        chkStdout("12345")

        chkOpFull("function f(): timestamp = op_context.last_block_time; operation o() { print(f()); }")
        chkStdout("12345")

        tst.opContext = null
        chk("op_context.last_block_time", "ct_err:op_ctx_noop")
        chkFull("function f(): timestamp = op_context.last_block_time; query q() = f();", listOf(),
                "rt_err:fn:op_context.last_block_time:noop")

        chk("op_context", "ct_err:expr_novalue:namespace")
    }

    @Test fun testLastBlockTimeAsDefaultValue() {
        tstCtx.useSql = true
        def("class foo { t: integer = op_context.last_block_time; }")
        tst.opContext = Rt_OpContext(12345, -1, listOf())

        chkOp("create foo();")
        chkData("foo(1,12345)")
    }

    @Test fun testTransaction() {
        tstCtx.useSql = true
        tst.inserts = LibBlockTransactionTest.BLOCK_INSERTS
        tst.chainId = 333
        tst.opContext = Rt_OpContext(-1, 444, listOf())

        chkOp("print(_type_of(op_context.transaction));")
        chkStdout("transaction")

        chkOp("print(_strict_str(op_context.transaction));")
        chkStdout("transaction[444]")
    }

    @Test fun testTransactionAsDefaultValue() {
        tstCtx.useSql = true
        def("class foo { t: transaction = op_context.transaction; }")
        tst.inserts = LibBlockTransactionTest.BLOCK_INSERTS
        tst.chainId = 333
        tst.opContext = Rt_OpContext(-1, 444, listOf())

        chkOp("create foo();")
        chkData("foo(1,444)")
    }

    @Test fun testAssignmentValue() {
        chkOp("op_context.last_block_time = 0;", "ct_err:expr_bad_dst")
        chkOp("op_context.transaction = 0;", "ct_err:expr_bad_dst")
    }
}
