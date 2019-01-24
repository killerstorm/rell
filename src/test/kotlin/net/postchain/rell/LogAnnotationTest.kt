package net.postchain.rell

import net.postchain.rell.lib.LibClassesTest
import net.postchain.rell.runtime.Rt_OpContext
import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LogAnnotationTest: BaseRellTest() {
    @Test fun testMutableAttribute() {
        chkCompile("class foo { mutable x: integer; }", "OK")
        chkCompile("class foo (log) { mutable x: integer; }", "ct_err:class_attr_mutable_log:foo:x")
    }

    @Test fun testRedefineSysAttribute() {
        chkCompile("class foo (log) { x: integer; }", "OK")
        chkCompile("class foo (log) { x: integer; transaction: integer; }", "ct_err:dup_attr:transaction")
        chkCompile("class foo (log) { x: integer; transaction; }", "ct_err:dup_attr:transaction")
    }

    @Test fun testSysAttributes() {
        tst.defs = listOf("class foo(log) { x: integer; }")
        tst.inserts = LibClassesTest.BLOCK_INSERTS
        tst.opContext = Rt_OpContext(-1, 444, listOf())

        execOp("create foo(x = 123);")
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
        tst.defs = listOf("class foo(log) { x: integer; }")
        tst.inserts = LibClassesTest.BLOCK_INSERTS
        tst.opContext = Rt_OpContext(-1, 444, listOf())

        chkOp("create foo(x = 123, transaction@{});", "ct_err:create_attr_cantset:transaction")
        tst.chkData()

        execOp("create foo(x = 123);")
        tst.chkData("foo(1,444,123)")

        chkOp("update foo@{} ( transaction = transaction@{} );", "ct_err:update_attr_not_mutable:transaction")
    }

    @Test fun testDelete() {
        tst.defs = listOf("class foo(log) { x: integer; } class bar { x: integer; }")
        chkOp("delete foo @ {};", "ct_err:stmt_delete_cant:foo")
        chkOp("delete bar @ {};", "")
    }
}
