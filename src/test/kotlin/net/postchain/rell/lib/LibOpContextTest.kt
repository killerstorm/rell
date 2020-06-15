/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.runtime.Rt_OpContext
import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LibOpContextTest: BaseRellTest(false) {
    @Test fun testLastBlockTime() {
        tst.opContext = Rt_OpContext(12345, -1, -1, listOf())
        chkOp("print(op_context.last_block_time);")
        chkOut("12345")

        chkOp("val t: timestamp = op_context.last_block_time; print(t);") // Will fail when timestamp type becomes intependent.
        chkOut("12345")
        chkOp("val t: integer = op_context.last_block_time; print(t);")
        chkOut("12345")

        chkOpFull("function f(): timestamp = op_context.last_block_time; operation o() { print(f()); }")
        chkOut("12345")

        tst.opContext = null
        chk("op_context.last_block_time", "ct_err:op_ctx_noop")
        chkFull("function f(): timestamp = op_context.last_block_time; query q() = f();", listOf(),
                "rt_err:fn:op_context.last_block_time:noop")

        chk("op_context", "ct_err:expr_novalue:namespace")
    }

    @Test fun testLastBlockTimeAsDefaultValue() {
        tstCtx.useSql = true
        def("entity foo { t: integer = op_context.last_block_time; }")
        tst.opContext = Rt_OpContext(12345, -1, -1, listOf())

        chkOp("create foo();")
        chkData("foo(1,12345)")
    }

    @Test fun testBlockHeight() {
        tst.opContext = Rt_OpContext(12345, -1, 98765, listOf())
        chkOp("print(op_context.block_height);")
        chkOut("98765")
    }

    @Test fun testTransaction() {
        tstCtx.useSql = true
        tst.chainId = 333
        tst.inserts = LibBlockTransactionTest.BLOCK_INSERTS_333
        tst.opContext = Rt_OpContext(-1, 444, -1, listOf())

        chkOp("print(_type_of(op_context.transaction));")
        chkOut("transaction")

        chkOp("print(_strict_str(op_context.transaction));")
        chkOut("transaction[444]")
    }

    @Test fun testTransactionAsDefaultValue() {
        tstCtx.useSql = true
        def("entity foo { t: transaction = op_context.transaction; }")
        tst.chainId = 333
        tst.inserts = LibBlockTransactionTest.BLOCK_INSERTS_333
        tst.opContext = Rt_OpContext(-1, 444, -1, listOf())

        chkOp("create foo();")
        chkData("foo(1,444)")
    }

    @Test fun testAssignmentValue() {
        chkOp("op_context.last_block_time = 0;", "ct_err:expr_bad_dst")
        chkOp("op_context.transaction = 0;", "ct_err:expr_bad_dst")
    }
}
