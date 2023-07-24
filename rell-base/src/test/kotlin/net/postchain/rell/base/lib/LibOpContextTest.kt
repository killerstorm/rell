/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib

import net.postchain.gtv.Gtv
import net.postchain.rell.base.runtime.Rt_NullOpContext
import net.postchain.rell.base.runtime.Rt_OpContext
import net.postchain.rell.base.testutils.BaseRellTest
import net.postchain.rell.base.testutils.GtvTestUtils
import net.postchain.rell.base.testutils.Rt_TestOpContext
import net.postchain.rell.base.utils.hexStringToBytes
import net.postchain.rell.base.utils.toImmList
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

        tst.opContext = Rt_NullOpContext
        chk("op_context.last_block_time", "ct_err:op_ctx:noop")
        chkFull("function f(): timestamp = op_context.last_block_time; query q() = f();", listOf(),
                "rt_err:op_context:noop")

        chk("op_context", "ct_err:expr_novalue:namespace:[op_context]")
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

        chkFn("= op_context.is_signer(x'1234');", "boolean[true]")
        chkFn("= op_context.is_signer(x'abcd');", "boolean[true]")
        chkFn("= op_context.is_signer(x'1234abcd');", "boolean[false]")
        chkFn("= op_context.is_signer(x'');", "boolean[false]")

        chkFn("= op_context.is_signer();", "ct_err:expr_call_argtypes:[op_context.is_signer]:")
        chkFn("= op_context.is_signer(123);", "ct_err:expr_call_argtypes:[op_context.is_signer]:integer")
        chkFn("= op_context.is_signer('1234');", "ct_err:expr_call_argtypes:[op_context.is_signer]:text")
        chkFn("= op_context.is_signer(x'12', x'34');", "ct_err:expr_call_argtypes:[op_context.is_signer]:byte_array,byte_array")
    }

    @Test fun testIsSignerGlobalScope() {
        tst.opContext = opContext(signers = listOf("1234", "abcd"))
        chkFn("= is_signer(x'1234');", "boolean[true]")
        chkFn("= is_signer(x'abcd');", "boolean[true]")
        chkFn("= is_signer(x'beef');", "boolean[false]")
    }

    @Test fun testGetSigners() {
        tst.opContext = opContext(signers = listOf("1234", "abcd"))
        chkFn("= _type_of(op_context.get_signers());", "text[list<byte_array>]")
        chkFn("= op_context.get_signers();", "list<byte_array>[byte_array[1234],byte_array[abcd]]")
    }

    @Test fun testGetAllOperations() {
        tst.opContext = opContext(ops = listOf())
        chkFn("= _type_of(op_context.get_all_operations());", "text[list<gtx_operation>]")
        chkFn("= op_context.get_all_operations();", "list<gtx_operation>[]")

        tst.opContext = opContext(ops = listOf("""foo[123,"Bob"]""", """bar["Alice",456]"""))
        chkFn("= op_context.get_all_operations();",
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
        chkOp("op_context.emit_event();", "ct_err:expr_call_argtypes:[op_context.emit_event]:")
        chkOp("op_context.emit_event('bob');", "ct_err:expr_call_argtypes:[op_context.emit_event]:text")
        chkOp("op_context.emit_event('bob', 'alice');", "ct_err:expr_call_argtypes:[op_context.emit_event]:text,text")
        chkOp("op_context.emit_event('bob', gtv.from_json('{}'), 123);", "ct_err:expr_call_argtypes:[op_context.emit_event]:text,gtv,integer")
        chkOp("op_context.emit_event('bob', gtv.from_json('{}'), 'alice');", "ct_err:expr_call_argtypes:[op_context.emit_event]:text,gtv,text")
    }

    @Test fun testCallFucntionsFromQuery() {
        chk("op_context.get_signers()", "ct_err:op_ctx:noop")
        chk("op_context.is_signer(x'1234')", "ct_err:op_ctx:noop")
        chk("op_context.get_all_operations()", "ct_err:op_ctx:noop")
        chk("op_context.emit_event('foo', null.to_gtv())", "ct_err:[query_exprtype_unit][op_ctx:noop]")
        chk("is_signer(x'1234')", "ct_err:op_ctx:noop")
    }

    @Test fun testExists() {
        tst.opContext = opContext(lastBlockTime = 12345)
        chkOp("print(op_context.exists);")
        chkOut("true")

        chk("op_context.exists", "boolean[true]")
        chk("op_context.last_block_time", "ct_err:op_ctx:noop")
        chkFn("= op_context.exists;", "boolean[true]")
        chkFn("= op_context.last_block_time;", "int[12345]")

        tst.opContext = Rt_NullOpContext
        chk("op_context.exists", "boolean[false]")
        chk("op_context.last_block_time", "ct_err:op_ctx:noop")
        chkFn("= op_context.exists;", "boolean[false]")
        chkFn("= op_context.last_block_time;", "rt_err:op_context:noop")
    }

    @Test fun testGtxOperationType() {
        chk("gtx_operation(name = 'foo', args = list<gtv>())", "gtx_operation[name=text[foo],args=list<gtv>[]]")
        chk("gtx_operation()", "ct_err:attr_missing:name,args")
        chkEx("{ var o: gtx_operation; o = gtx_operation('foo', ['Bob'.to_gtv()]); return o; }",
                """gtx_operation[name=text[foo],args=list<gtv>[gtv["Bob"]]]""")
    }

    @Test fun testGtxTransactionBodyType() {
        chk("gtx_transaction_body(blockchain_rid = x'1234', operations = list<gtx_operation>(), signers = list<gtv>())",
                "gtx_transaction_body[blockchain_rid=byte_array[1234],operations=list<gtx_operation>[],signers=list<gtv>[]]")
        chk("gtx_transaction_body()", "ct_err:attr_missing:blockchain_rid,operations,signers")
    }

    @Test fun testGtxTransactionType() {
        val expBody = "gtx_transaction_body[blockchain_rid=byte_array[1234],operations=list<gtx_operation>[],signers=list<gtv>[]]"
        chk("gtx_transaction(body = gtx_transaction_body(blockchain_rid = x'1234', operations = [], signers = []), signatures = [])",
                "gtx_transaction[body=$expBody,signatures=list<gtv>[]]")
        chk("gtx_transaction()", "ct_err:attr_missing:body,signatures")
    }

    @Test fun testTestModule() {
        file("module.rell", "@test module;")
        chkCompile("function f() = op_context.exists;", "OK")
        chkCompile("function f() = op_context.is_signer(x'');", "ct_err:op_ctx:test")
        chkCompile("function f() = op_context.get_signers();", "ct_err:op_ctx:test")
        chkCompile("function f() = is_signer(x'');", "OK")
        chkCompile("function f(x: gtx_operation) {}", "OK")
        chkCompile("function f(x: gtx_transaction_body) {}", "OK")
        chkCompile("function f(x: gtx_transaction) {}", "OK")
    }

    companion object {
        private fun opContext(
                lastBlockTime: Long = -1,
                transactionIid: Long = -1,
                blockHeight: Long = -1,
                opIndex: Int = -1,
                signers: List<String> = listOf(),
                ops: List<String> = listOf()
        ): Rt_OpContext {
            val signers2 = signers.map { it.hexStringToBytes() }
            val ops2 = ops.map { parseOperation(it) }
            return Rt_TestOpContext(
                    lastBlockTime = lastBlockTime,
                    transactionIid = transactionIid,
                    blockHeight = blockHeight,
                    opIndex = opIndex,
                    signers = signers2,
                    allOperations = ops2,
            )
        }

        private fun parseOperation(s: String): Pair<String, List<Gtv>> {
            val i = s.indexOf("[")
            val name = s.substring(0, i)
            val args = GtvTestUtils.strToGtv(s.substring(i)).asArray().toImmList()
            return name to args
        }
    }
}
