package net.postchain.rell.lib

import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.RellCodeTester
import net.postchain.rell.test.RellTestContext
import org.junit.Test

class LibBlockTransactionTest: BaseRellTest() {
    companion object {
        val BLOCK_INSERTS = RellTestContext.BlockBuilder()
                .block(111, 333, 222, "DEADBEEF", "5678", 1500000000000)
                .block(1, 555, 35, "FEEBDAED", "8765", 1400000000000)
                .block(3, 600, 46, "BADBAD", "FEDC", 1300000000000)
                .tx(444, 333, 111, "FADE", "EDAF", "1234")
                .tx(2, 555, 1, "CEED", "FEED", "4321")
                .tx(4, 601, 3, "CEED", "CDEF", "F00D")
                .list()
    }

    @Test fun testBlockRead() {
        tst.inserts = BLOCK_INSERTS
        tst.chainId = 333

        chk("block @* {}", "list<block>[block[111]]")
        chk("transaction @* {}", "list<transaction>[transaction[444]]")

        val block = "val b = block@{}"
        val trans = "val t = transaction@{}"

        chkEx("{ $block; return b.block_height; }", "int[222]")
        chkEx("{ $block; return b.block_rid; }", "byte_array[deadbeef]")
        chkEx("{ $block; return b.timestamp; }", "int[1500000000000]")
        chkEx("{ $block; return b.block_iid; }", "ct_err:unknown_member:block:block_iid")

        chkEx("{ $trans; return t.tx_rid; }", "byte_array[fade]")
        chkEx("{ $trans; return t.tx_hash; }", "byte_array[1234]")
        chkEx("{ $trans; return t.tx_data; }", "byte_array[edaf]")
        chkEx("{ $trans; return t.tx_iid; }", "ct_err:unknown_member:transaction:tx_iid")

        chkEx("{ $trans; return t.block; }", "block[111]")
        chkEx("{ $trans; return t.block.block_height; }", "int[222]")
        chkEx("{ $trans; return t.block.block_rid; }", "byte_array[deadbeef]")
        chkEx("{ $trans; return t.block.timestamp; }", "int[1500000000000]")
        chkEx("{ $trans; return t.block.block_iid; }", "ct_err:unknown_member:block:block_iid")
    }

    @Test fun testBlockReadAt() {
        tst.inserts = BLOCK_INSERTS
        tst.chainId = 333

        chk("block @* {}", "list<block>[block[111]]")
        chk("transaction @* {}", "list<transaction>[transaction[444]]")

        chk("block@{} ( .block_height )", "int[222]")
        chk("block@{} ( .block_rid )", "byte_array[deadbeef]")
        chk("block@{} ( .timestamp )", "int[1500000000000]")
        chk("block@{} ( .block_iid )", "ct_err:expr_attr_unknown:block_iid")

        chk("transaction@{} ( .tx_rid )", "byte_array[fade]")
        chk("transaction@{} ( .tx_hash )", "byte_array[1234]")
        chk("transaction@{} ( .tx_data )", "byte_array[edaf]")
        chk("transaction@{} ( .tx_iid )", "ct_err:expr_attr_unknown:tx_iid")

        chk("transaction@{} ( .block )", "block[111]")
        chk("transaction@{} ( .block.block_height )", "int[222]")
        chk("transaction@{} ( .block.block_rid )", "byte_array[deadbeef]")
        chk("transaction@{} ( .block.timestamp )", "int[1500000000000]")
        chk("transaction@{} ( .block.block_iid )", "ct_err:unknown_member:block:block_iid")
    }

    @Test fun testBlockWrite() {
        tst.inserts = BLOCK_INSERTS
        tst.chainId = 333

        chkOp("create block(block_height = 123, block_rid = x'deadbeef', timestamp = 456);", "ct_err:expr_create_cant:block")
        chkOp("create transaction(tx_rid = x'dead', tx_hash = x'beef', tx_data = x'cafe', block = block@{});", "ct_err:expr_create_cant:transaction")
        chkOp("create blocks(block_height = 123, block_rid = x'deadbeef', timestamp = 456);", "ct_err:unknown_entity:blocks")
        chkOp("create transactions(tx_rid = x'dead', tx_hash = x'beef', tx_data = x'cafe', block = block@{});", "ct_err:unknown_entity:transactions")

        chkOp("update block@{}( block_height = 999 );", "ct_err:stmt_update_cant:block")
        chkOp("update block@{}( block_rid = x'cafe' );", "ct_err:stmt_update_cant:block")
        chkOp("update block@{}( timestamp = 999 );", "ct_err:stmt_update_cant:block")

        chkOp("update transaction@{}( tx_rid = 'dead' );", "ct_err:stmt_update_cant:transaction")
        chkOp("update transaction@{}( tx_hash = 'dead' );", "ct_err:stmt_update_cant:transaction")
        chkOp("update transaction@{}( tx_data = 'dead' );", "ct_err:stmt_update_cant:transaction")
        chkOp("update transaction@{}( block = block@{} );", "ct_err:stmt_update_cant:transaction")

        chkOp("delete block@{};", "ct_err:stmt_delete_cant:block")
        chkOp("delete transaction@{};", "ct_err:stmt_delete_cant:transaction")
    }

    @Test fun testBlockMisc() {
        tst.inserts = BLOCK_INSERTS
        tst.chainId = 333

        chkCompile("entity transaction{}", "ct_err:name_conflict:sys:transaction:ENTITY")
        chkCompile("entity block{}", "ct_err:name_conflict:sys:block:ENTITY")

        chk("block @* {}", "list<block>[block[111]]")
        chk("transaction @* {}", "list<transaction>[transaction[444]]")
        chk("blocks @* {}", "ct_err:unknown_entity:blocks")
        chk("transactions @* {}", "ct_err:unknown_entity:transactions")
    }

    @Test fun testBlockRef() {
        def("entity foo { x: integer; block; }")
        tst.inserts = BLOCK_INSERTS
        tst.chainId = 333

        chkOp("create foo (123, block@{});")

        val base = "val f = foo@{}"
        chkEx("{ $base; return f.block; }", "block[111]")
        chkEx("{ $base; return f.block.block_height; }", "int[222]")
        chkEx("{ $base; return f.block.block_rid; }", "byte_array[deadbeef]")
        chkEx("{ $base; return f.block.timestamp; }", "int[1500000000000]")

        chk("foo@{} ( .block )", "block[111]")
        chk("foo@{} ( .block.block_height )", "int[222]")
        chk("foo@{} ( .block.block_rid )", "byte_array[deadbeef]")
        chk("foo@{} ( .block.timestamp )", "int[1500000000000]")
    }

    @Test fun testTransactionRef() {
        def("entity foo { x: integer; trans: transaction; }")
        tst.inserts = BLOCK_INSERTS
        tst.chainId = 333

        chkOp("create foo (123, transaction@{});")

        val base = "val f = foo@{}"
        chkEx("{ $base; return f.trans.block; }", "block[111]")
        chkEx("{ $base; return f.trans.block.block_height; }", "int[222]")
        chkEx("{ $base; return f.trans.block.block_rid; }", "byte_array[deadbeef]")
        chkEx("{ $base; return f.trans.block.timestamp; }", "int[1500000000000]")

        chk("foo@{} ( .trans.block )", "block[111]")
        chk("foo@{} ( .trans.block.block_height )", "int[222]")
        chk("foo@{} ( .trans.block.block_rid )", "byte_array[deadbeef]")
        chk("foo@{} ( .trans.block.timestamp )", "int[1500000000000]")
    }

    @Test fun testBlockRefChainId() {
        def("entity foo { x: integer; b: block; t: transaction; }")
        tst.inserts = BLOCK_INSERTS
        insert("c0.foo", "x,b,t", "1,123,111,444")
        tst.chainId = 0

        val base = "val f = foo@{}"
        chkEx("{ $base; return f.b; }", "block[111]")
        chkEx("{ $base; return f.b.block_height; }", "int[222]")
        chkEx("{ $base; return f.b.block_rid; }", "byte_array[deadbeef]")
        chkEx("{ $base; return f.b.timestamp; }", "int[1500000000000]")
        chkEx("{ $base; return f.t.block; }", "block[111]")
        chkEx("{ $base; return f.t.block.block_height; }", "int[222]")
        chkEx("{ $base; return f.t.block.block_rid; }", "byte_array[deadbeef]")
        chkEx("{ $base; return f.t.block.timestamp; }", "int[1500000000000]")

        chk("foo@{} ( .b )", "block[111]")
        chk("foo@{} ( .b.block_height )", "int[222]")
        chk("foo@{} ( .b.block_rid )", "byte_array[deadbeef]")
        chk("foo@{} ( .b.timestamp )", "int[1500000000000]")
        chk("foo@{} ( .t.block )", "block[111]")
        chk("foo@{} ( .t.block.block_height )", "int[222]")
        chk("foo@{} ( .t.block.block_rid )", "byte_array[deadbeef]")
        chk("foo@{} ( .t.block.timestamp )", "int[1500000000000]")
    }

    @Test fun testBlockReadChainId() {
        tst.inserts = BLOCK_INSERTS
        tst.strictToString = false

        val expr = """
            transaction @* {} (
                =transaction,
                =.tx_rid,
                =.tx_hash,
                =.tx_data,
                =.block,
                =.block.block_height,
                =.block.block_rid,
                =.block.timestamp
            )
        """.trimIndent()

        chk(expr, "[]") // Does database initialization

        var t = createChainIdTester(333, 111, 444)

        t.chkQuery(expr, "[(transaction[444],0xfade,0x1234,0xedaf,block[111],222,0xdeadbeef,1500000000000)]")
        t.chkQuery("(b: block, t: transaction) @* {}", "[(b=block[111],t=transaction[444])]")

        t.chkQuery("foo @* {} (=foo,=.b,=.t)", "[(foo[1],block[111],transaction[444])]")
        t.chkQuery("foo @* {} (=.value)", "[0]")
        t.chkOp("update foo @* { .b.block_height >= 0, .t.tx_hash != x'' } ( value = 100 );")
        t.chkQuery("foo @* {} (=.value)", "[100]")
        t.chkOp("delete foo @* { .b.block_height >= 0, .t.tx_hash != x'' };")
        t.chkQuery("foo @* {} (=.value)", "[]")

        t = createChainIdTester(555, 1, 2)

        t.chkQuery(expr, "[(transaction[2],0xceed,0x4321,0xfeed,block[1],35,0xfeebdaed,1400000000000)]")
        t.chkQuery("(b: block, t: transaction) @* {}", "[(b=block[1],t=transaction[2])]")

        t.chkQuery("foo @* {} (=foo,=.b,=.t)", "[(foo[1],block[1],transaction[2])]")
        t.chkQuery("foo @* {} (=.value)", "[0]")
        t.chkOp("update foo @* { .b.block_height >= 0, .t.tx_hash != x'' } ( value = 100 );")
        t.chkQuery("foo @* {} (=.value)", "[100]")
        t.chkOp("delete foo @* { .b.block_height >= 0, .t.tx_hash != x'' };")
        t.chkQuery("foo @* {} (=.value)", "[]")

        t = createChainIdTester(600, 3, 4)

        t.chkQuery(expr, "[]")
        t.chkQuery("transaction @* {}", "[]")
        t.chkQuery("block @* {} ( =block, =.block_height, =.block_rid, =.timestamp )", "[(block[3],46,0xbadbad,1300000000000)]")
        t.chkQuery("(b: block, t: transaction) @* {}", "[]")

        t.chkQuery("foo @* {}", "[foo[1]]")
        t.chkQuery("foo @* {} (=foo,=.b)", "[(foo[1],block[3])]")
        t.chkQuery("foo @* {} (=foo,=.t)", "[(foo[1],transaction[4])]")
        t.chkQuery("foo @* {} (=foo,=.b,=.t)", "[(foo[1],block[3],transaction[4])]")
        t.chkQuery("foo @* {} (=.value)", "[0]")
        t.chkOp("update foo @* {} ( value = 100 );")
        t.chkQuery("foo @* {} (=.value)", "[100]")
        t.chkOp("update foo @* { .b.block_height >= 0 } ( value = 200 );")
        t.chkQuery("foo @* {} (=.value)", "[200]")
        t.chkOp("update foo @* { .t.tx_hash != x'' } ( value = 300 );")
        t.chkQuery("foo @* {} (=.value)", "[300]")
        t.chkOp("delete foo @* { .t.tx_hash != x'' };")
        t.chkQuery("foo @* {} (=.value)", "[]")

        t = createChainIdTester(601, 3, 4)

        t.chkQuery(expr, "[(transaction[4],0xceed,0xf00d,0xcdef,block[3],46,0xbadbad,1300000000000)]")
        t.chkQuery("transaction @* {} ( =transaction, =.tx_rid, =.tx_hash, =.tx_data )", "[(transaction[4],0xceed,0xf00d,0xcdef)]")
        t.chkQuery("transaction @* { .block == .block }", "[transaction[4]]")
        t.chkQuery("block @* {}", "[]")
        t.chkQuery("(b: block, t: transaction) @* {}", "[]")

        t.chkQuery("foo @* {}", "[foo[1]]")
        t.chkQuery("foo @* {} (=foo,=.b)", "[(foo[1],block[3])]")
        t.chkQuery("foo @* {} (=foo,=.t)", "[(foo[1],transaction[4])]")
        t.chkQuery("foo @* {} (=foo,=.b,=.t)", "[(foo[1],block[3],transaction[4])]")
        t.chkQuery("foo @* {} (=.value)", "[0]")
        t.chkOp("update foo @* {} ( value = 100 );")
        t.chkQuery("foo @* {} (=.value)", "[100]")
        t.chkOp("update foo @* { .b.block_height >= 0 } ( value = 200 );")
        t.chkQuery("foo @* {} (=.value)", "[200]")
        t.chkOp("update foo @* { .t.tx_hash != x'' } ( value = 300 );")
        t.chkQuery("foo @* {} (=.value)", "[300]")
        t.chkOp("delete foo @* { .b.block_height >= 0 };")
        t.chkQuery("foo @* {} (=.value)", "[]")
    }

    @Test fun textGtv() {
        def("struct r { t: transaction; }")
        tst.chainId = 333
        tst.inserts = BLOCK_INSERTS
        chk("transaction @ {}", "transaction[444]")
        chk("r(transaction @ {})", "r[t=transaction[444]]")
        chkEx("{ val r = r(transaction @ {}); return r.to_gtv_pretty(); }", "ct_err:fn:invalid:r:r.to_gtv_pretty")
        chk("""r.from_gtv_pretty(gtv.from_json('{"t":444}'))""", "ct_err:fn:invalid:r:from_gtv_pretty")
    }

    private fun createChainIdTester(chainId: Long, blockIid: Long, txIid: Long): RellCodeTester {
        val t = RellCodeTester(tstCtx)
        t.def("entity foo { b: block; t: transaction; mutable value: integer; }")
        t.insert("c${chainId}.foo", "b,t,value", "1,$blockIid,$txIid,0")
        t.strictToString = false
        t.dropTables = false
        t.chainId = chainId
        return t
    }
}
