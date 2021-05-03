/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.common.hexStringToByteArray
import net.postchain.gtx.OpData
import net.postchain.rell.runtime.Rt_OpContext
import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.GtvTestUtils
import org.junit.Test

class LibOpContextTest: BaseRellTest(false) {
    @Test fun testLastBlockTime() {
        tst.opContext = opContext(lastBlockTime = 12345)
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
        tst.opContext = opContext(lastBlockTime = 12345)

        chkOp("create foo();")
        chkData("foo(1,12345)")
    }

    @Test fun testBlockHeight() {
        tst.opContext = opContext(lastBlockTime = 12345, blockHeight = 98765)
        chkOp("print(_type_of(op_context.block_height));")
        chkOut("integer")
        chkOp("print(_strict_str(op_context.block_height));")
        chkOut("int[98765]")
    }

    @Test fun testOpIndex() {
        tst.opContext = opContext(opIndex = 12345)
        chkOp("print(_type_of(op_context.op_index));")
        chkOut("integer")
        chkOp("print(_strict_str(op_context.op_index));")
        chkOut("int[12345]")
    }

    @Test fun testTransaction() {
        tstCtx.useSql = true
        tst.chainId = 333
        tst.inserts = LibBlockTransactionTest.BLOCK_INSERTS_333
        tst.opContext = opContext(transactionIid = 444)

        chkOp("print(_type_of(op_context.transaction));")
        chkOut("transaction")

        chkOp("print(_type_of(op_context.transaction.block));")
        chkOut("block")

        chkOp("print(_strict_str(op_context.transaction));")
        chkOut("transaction[444]")

        chkOp("print(_strict_str(op_context.transaction.block));")
        chkOut("block[111]")
    }

    @Test fun testTransactionAsDefaultValue() {
        tstCtx.useSql = true
        def("entity foo { t: transaction = op_context.transaction; }")
        tst.chainId = 333
        tst.inserts = LibBlockTransactionTest.BLOCK_INSERTS_333
        tst.opContext = opContext(transactionIid = 444)

        chkOp("create foo();")
        chkData("foo(1,444)")
    }

    @Test fun testCurrentTransactionBlock() {
        tstCtx.useSql = true
        tst.chainId = 333
        tst.inserts = LibBlockTransactionTest.BLOCK_INSERTS_CURRENT
        tst.opContext = opContext(transactionIid = 202)

        chkOpOut("print(_strict_str(op_context.transaction));", "transaction[202]")
        chkOpOut("print(_strict_str(op_context.transaction.tx_rid));", "byte_array[fade]")
        chkOpOut("print(_strict_str(op_context.transaction.tx_hash));", "byte_array[1234]")
        chkOpOut("print(_strict_str(op_context.transaction.tx_data));", "byte_array[edaf]")
        chkOpOut("print(_strict_str(op_context.transaction.block));", "block[102]")
        chkOpOut("print(_strict_str(op_context.transaction.block.block_height));", "int[20]")
        chkOp("print(_strict_str(op_context.transaction.block.timestamp));", "rt_err:sql_null:integer")
        chkOp("print(_strict_str(op_context.transaction.block.block_rid));", "rt_err:sql_null:byte_array")
    }

    @Test fun testAssignmentValue() {
        chkOp("op_context.last_block_time = 0;", "ct_err:expr_bad_dst")
        chkOp("op_context.transaction = 0;", "ct_err:expr_bad_dst")
    }

    @Test fun testIsSigner() {
        tst.opContext = opContext(signers = listOf("1234", "abcd"))

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
        tst.opContext = opContext(signers = listOf("1234", "abcd"))
        chk("is_signer(x'1234')", "boolean[true]")
        chk("is_signer(x'abcd')", "boolean[true]")
        chk("is_signer(x'beef')", "boolean[false]")
    }

    @Test fun testGetSigners() {
        tst.opContext = opContext(signers = listOf("1234", "abcd"))
        chk("_type_of(op_context.get_signers())", "text[list<byte_array>]")
        chk("op_context.get_signers()", "list<byte_array>[byte_array[1234],byte_array[abcd]]")
    }

    @Test fun testGetAllOperations() {
        tst.opContext = opContext(ops = listOf())
        chk("_type_of(op_context.get_all_operations())", "text[list<gtx_operation>]")
        chk("op_context.get_all_operations()", "list<gtx_operation>[]")

        tst.opContext = opContext(ops = listOf("""foo[123,"Bob"]""", """bar["Alice",456]"""))
        chk("op_context.get_all_operations()",
                """list<gtx_operation>["""
                    + """gtx_operation[name=text[foo],args=list<gtv>[gtv[123],gtv["Bob"]]]"""
                    + ","
                    + """gtx_operation[name=text[bar],args=list<gtv>[gtv["Alice"],gtv[456]]]"""
                    + "]"
        )
    }

    @Test fun testEmitEvent() {
        tst.opContext = opContext()
        chkOp("op_context.emit_event('bob', gtv.from_json('{}'));", "rt_err:not_supported")
        chkOp("op_context.emit_event();", "ct_err:expr_call_argtypes:emit_event:")
        chkOp("op_context.emit_event('bob');", "ct_err:expr_call_argtypes:emit_event:text")
        chkOp("op_context.emit_event('bob', 'alice');", "ct_err:expr_call_argtypes:emit_event:text,text")
        chkOp("op_context.emit_event('bob', gtv.from_json('{}'), 123);", "ct_err:expr_call_argtypes:emit_event:text,gtv,integer")
        chkOp("op_context.emit_event('bob', gtv.from_json('{}'), 'alice');", "ct_err:expr_call_argtypes:emit_event:text,gtv,text")
    }

    private fun opContext(
            lastBlockTime: Long = -1,
            transactionIid: Long = -1,
            blockHeight: Long = -1,
            opIndex: Int = -1,
            signers: List<String> = listOf(),
            ops: List<String> = listOf()
    ): Rt_OpContext {
        val signers2 = signers.map { it.hexStringToByteArray() }
        val ops2 = ops.map { parseOperation(it) }
        return Rt_OpContext(
                lastBlockTime = lastBlockTime,
                transactionIid = transactionIid,
                blockHeight = blockHeight,
                opIndex = opIndex,
                signers = signers2,
                allOperations = ops2
        )
    }

    private fun parseOperation(s: String): OpData {
        val i = s.indexOf("[")
        val name = s.substring(0, i)
        val args = GtvTestUtils.strToGtv(s.substring(i)).asArray().toList().toTypedArray()
        return OpData(name, args)
    }
}
