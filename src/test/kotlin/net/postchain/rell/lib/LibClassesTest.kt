package net.postchain.rell.lib

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LibClassesTest: BaseRellTest() {
    companion object {
        val BLOCK_INSERTS = listOf(
                """INSERT INTO "blocks"(block_iid,block_height,block_rid,chain_id,block_header_data,block_witness,timestamp)
                VALUES(111,222,E'\\xDEADBEEF',333,E'\\x5678',NULL,1500000000000);""".trimIndent(),
                """INSERT INTO "transactions"(tx_iid,chain_id,tx_rid,tx_data,tx_hash,block_iid)
                VALUES(444,333,E'\\xFADE',E'\\xEDAF',E'\\x1234',111);""".trimIndent()
        )
    }

    @Test fun testBlockRead() {
        tst.inserts = BLOCK_INSERTS

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

        chkCompile("class transaction{}", "ct_err:name_conflict:type:transaction")
        chkCompile("class block{}", "ct_err:name_conflict:type:block")
        chkCompile("class transactions{}", "ct_err:name_conflict:table:transactions")
        chkCompile("class blocks{}", "ct_err:name_conflict:table:blocks")

        chk("block @* {}", "list<block>[block[111]]")
        chk("transaction @* {}", "list<transaction>[transaction[444]]")
        chk("blocks @* {}", "ct_err:unknown_class:blocks")
        chk("transactions @* {}", "ct_err:unknown_class:transactions")
    }

    @Test fun testBlockRef() {
        tst.defs = listOf("class foo { x: integer; block; }")
        tst.inserts = BLOCK_INSERTS

        chkOp("create foo (123, block@{});", "")

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

        chkOp("create foo (123, transaction@{});", "")

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
}
