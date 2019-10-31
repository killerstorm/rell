package net.postchain.rell

import net.postchain.rell.lib.LibBlockTransactionTest
import net.postchain.rell.runtime.Rt_OpContext
import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LogAnnotationTest: BaseRellTest() {
    @Test fun testLegacyAnnotation() {
        chkCompile("class foo (log) { x: integer; }", "OK")
        tst.chkWarn("ann:legacy:log")

        chkCompile("class foo (log) { x: integer; transaction: integer; }", "ct_err:dup_attr:transaction")
        chkCompile("class foo (log) { x: integer; transaction; }", "ct_err:dup_attr:transaction")

        chkCompile("@log class foo (log) { x: integer; }", "ct_err:class_ann_dup:log")
        tst.chkWarn("ann:legacy:log")
    }

    @Test fun testGeneral() {
        chkCompile("class foo { mutable x: integer; }", "OK")
        chkCompile("@log class foo { mutable x: integer; }", "ct_err:class_attr_mutable_log:foo:x")

        chkCompile("@log class foo { x: integer; }", "OK")
        chkCompile("@log class foo { x: integer; transaction: integer; }", "ct_err:dup_attr:transaction")
        chkCompile("@log class foo { x: integer; transaction; }", "ct_err:dup_attr:transaction")

        chkCompile("@log @log class foo { x: integer; }", "ct_err:ann:log:dup")

        chkCompile("@log() class foo { x: integer; }", "OK")
        chkCompile("@log(123) class foo { x: integer; }", "ct_err:ann:log:args:1")
    }

    @Test fun testSysAttributes() {
        def("@log class foo { x: integer; }")
        tst.inserts = LibBlockTransactionTest.BLOCK_INSERTS
        tst.chainId = 333
        tst.opContext = Rt_OpContext(-1, 444, -1, listOf())

        chkOp("create foo(x = 123);")
        tst.chkData("foo(1,444,123)")

        val base = "val f = foo@{}"
        chkEx("{ $base; return f.x; }", "int[123]")
        chkEx("{ $base; return f.transaction.tx_rid; }", "byte_array[fade]")
        chkEx("{ $base; return f.transaction.tx_hash; }", "byte_array[1234]")
        chkEx("{ $base; return f.transaction.tx_data; }", "byte_array[edaf]")
        chkEx("{ $base; return f.transaction.block; }", "block[111]")
        chkEx("{ $base; return f.transaction.block.block_height; }", "int[222]")
        chkEx("{ $base; return f.transaction.block.block_rid; }", "byte_array[deadbeef]")
        chkEx("{ $base; return f.transaction.block.timestamp; }", "int[1500000000000]")

        chk("foo@{} ( .x )", "int[123]")
        chk("foo@{} ( .transaction.tx_rid )", "byte_array[fade]")
        chk("foo@{} ( .transaction.tx_hash )", "byte_array[1234]")
        chk("foo@{} ( .transaction.tx_data )", "byte_array[edaf]")
        chk("foo@{} ( .transaction.block )", "block[111]")
        chk("foo@{} ( .transaction.block.block_height )", "int[222]")
        chk("foo@{} ( .transaction.block.block_rid )", "byte_array[deadbeef]")
        chk("foo@{} ( .transaction.block.timestamp )", "int[1500000000000]")
    }

    @Test fun testSysAttributesModify() {
        def("@log class foo { x: integer; }")
        tst.inserts = LibBlockTransactionTest.BLOCK_INSERTS
        tst.chainId = 333
        tst.opContext = Rt_OpContext(-1, 444, -1, listOf())

        chkOp("create foo(x = 123, transaction@{});", "ct_err:create_attr_cantset:transaction")
        tst.chkData()

        chkOp("create foo(x = 123);")
        tst.chkData("foo(1,444,123)")

        chkOp("update foo@{} ( transaction = transaction@{} );", "ct_err:update_attr_not_mutable:transaction")
    }

    @Test fun testDelete() {
        def("@log class foo { x: integer; }")
        def("class bar { x: integer; }")
        chkOp("delete foo @* {};", "ct_err:stmt_delete_cant:foo")
        chkOp("delete bar @* {};")
    }
}
