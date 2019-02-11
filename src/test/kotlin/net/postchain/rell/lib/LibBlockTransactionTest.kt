package net.postchain.rell.lib

import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.RellCodeTester
import net.postchain.rell.test.SqlTestUtils
import org.junit.Test

class LibBlockTransactionTest: BaseRellTest() {
    companion object {
        val BLOCK_INSERTS = listOf(
                insBlock(111, 333, 222, "DEADBEEF", "5678", 1500000000000),
                insTx(444, 333, "FADE", "EDAF", "1234", 111),
                insBlock(1, 555, 35, "FEEBDAED", "8765", 1400000000000),
                insTx(2, 555, "CEED", "FEED", "4321", 1),
                insBlock(3, 600, 46, "BADBAD", "FEDC", 1300000000000),
                insTx(4, 601, "CEED", "CDEF", "F00D", 3)
        )

        private fun insBlock(iid: Long, chainId: Long, height: Long, rid: String, header: String, timestamp: Long): String {
            return """INSERT INTO "blocks"(block_iid,block_height,block_rid,chain_id,block_header_data,block_witness,timestamp)
                VALUES($iid,$height,E'\\x$rid',$chainId,E'\\x$header',NULL,$timestamp);""".trimIndent()
        }

        private fun insTx(iid: Long, chainId: Long, rid: String, data: String, hash: String, block: Long): String {
            return """INSERT INTO "transactions"(tx_iid,chain_id,tx_rid,tx_data,tx_hash,block_iid)
                VALUES($iid,$chainId,E'\\x$rid',E'\\x$data',E'\\x$hash',$block);""".trimIndent()
        }
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
        chkOp("create blocks(block_height = 123, block_rid = x'deadbeef', timestamp = 456);", "ct_err:unknown_class:blocks")
        chkOp("create transactions(tx_rid = x'dead', tx_hash = x'beef', tx_data = x'cafe', block = block@{});", "ct_err:unknown_class:transactions")

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

        chkCompile("class transaction{}", "ct_err:name_conflict:type:transaction")
        chkCompile("class block{}", "ct_err:name_conflict:type:block")

        chk("block @* {}", "list<block>[block[111]]")
        chk("transaction @* {}", "list<transaction>[transaction[444]]")
        chk("blocks @* {}", "ct_err:unknown_class:blocks")
        chk("transactions @* {}", "ct_err:unknown_class:transactions")
    }

    @Test fun testBlockRef() {
        tst.defs = listOf("class foo { x: integer; block; }")
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
        tst.defs = listOf("class foo { x: integer; trans: transaction; }")
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
        t.chkQuery("foo @* {} (=foo,=.t)", "[]")
        t.chkQuery("foo @* {} (=foo,=.b,=.t)", "[]")
        t.chkQuery("foo @* {} (=.value)", "[0]")
        t.chkOp("update foo @* {} ( value = 100 );")
        t.chkQuery("foo @* {} (=.value)", "[100]")
        t.chkOp("update foo @* { .b.block_height >= 0 } ( value = 200 );")
        t.chkQuery("foo @* {} (=.value)", "[200]")
        t.chkOp("update foo @* { .t.tx_hash != x'' } ( value = 300 );")
        t.chkQuery("foo @* {} (=.value)", "[200]")
        t.chkOp("delete foo @* { .t.tx_hash != x'' };")
        t.chkQuery("foo @* {} (=.value)", "[200]")
        t.chkOp("delete foo @* { .b.block_height >= 0 };")
        t.chkQuery("foo @* {} (=.value)", "[]")

        t = createChainIdTester(601, 3, 4)

        t.chkQuery(expr, "[]")
        t.chkQuery("transaction @* {} ( =transaction, =.tx_rid, =.tx_hash, =.tx_data )", "[(transaction[4],0xceed,0xf00d,0xcdef)]")
        t.chkQuery("transaction @* { .block }", "[]")
        t.chkQuery("block @* {}", "[]")
        t.chkQuery("(b: block, t: transaction) @* {}", "[]")

        t.chkQuery("foo @* {}", "[foo[1]]")
        t.chkQuery("foo @* {} (=foo,=.b)", "[]")
        t.chkQuery("foo @* {} (=foo,=.t)", "[(foo[1],transaction[4])]")
        t.chkQuery("foo @* {} (=foo,=.b,=.t)", "[]")
        t.chkQuery("foo @* {} (=.value)", "[0]")
        t.chkOp("update foo @* {} ( value = 100 );")
        t.chkQuery("foo @* {} (=.value)", "[100]")
        t.chkOp("update foo @* { .b.block_height >= 0 } ( value = 200 );")
        t.chkQuery("foo @* {} (=.value)", "[100]")
        t.chkOp("update foo @* { .t.tx_hash != x'' } ( value = 300 );")
        t.chkQuery("foo @* {} (=.value)", "[300]")
        t.chkOp("delete foo @* { .b.block_height >= 0 };")
        t.chkQuery("foo @* {} (=.value)", "[300]")
        t.chkOp("delete foo @* { .t.tx_hash != x'' };")
        t.chkQuery("foo @* {} (=.value)", "[]")
    }

    private fun createChainIdTester(chainId: Long, blockIid: Long, txIid: Long): RellCodeTester {
        val t = resource(RellCodeTester())
        t.defs = listOf("class foo { b: block; t: transaction; mutable value: integer; }")
        t.inserts = listOf(SqlTestUtils.mkins("c${chainId}_foo", "b,t,value", "1,$blockIid,$txIid,0"))
        t.strictToString = false
        t.dropTables = false
        t.autoInitObjects = false
        t.createSystemTables = false
        t.chainId = chainId
        return t
    }
}
