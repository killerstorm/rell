/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.runtime.Rt_OpContext
import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.utils.CommonUtils
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

    @Test fun testIsSigner() {
        tst.opContext = Rt_OpContext(-1, -1, -1, listOf(CommonUtils.hexToBytes("1234"), CommonUtils.hexToBytes("abcd")))

        chk("op_context.is_signer(x'1234')", "boolean[true]")
        chk("op_context.is_signer(x'abcd')", "boolean[true]")
        chk("op_context.is_signer(x'1234abcd')", "boolean[false]")
        chk("op_context.is_signer(x'')", "boolean[false]")

        chk("op_context.is_signer()", "ct_err:expr_call_argtypes:is_signer:")
        chk("op_context.is_signer(123)", "ct_err:expr_call_argtypes:is_signer:integer")
        chk("op_context.is_signer('1234')", "ct_err:expr_call_argtypes:is_signer:text")
        chk("op_context.is_signer(x'12', x'34')", "ct_err:expr_call_argtypes:is_signer:byte_array,byte_array")
    }

    @Test fun testIsSignerGlobalScope() {
        tst.opContext = Rt_OpContext(-1, -1, -1, listOf(CommonUtils.hexToBytes("1234"), CommonUtils.hexToBytes("abcd")))
        chk("is_signer(x'1234')", "boolean[true]")
        chk("is_signer(x'abcd')", "boolean[true]")
        chk("is_signer(x'beef')", "boolean[false]")
    }

    @Test fun testGetSigners() {
        tst.opContext = Rt_OpContext(-1, -1, -1, listOf(CommonUtils.hexToBytes("1234"), CommonUtils.hexToBytes("abcd")))
        chk("op_context.get_signers()", "list<byte_array>[byte_array[1234],byte_array[abcd]]")
    }
}
