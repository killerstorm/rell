package net.postchain.rell

import net.postchain.rell.lib.LibBlockTransactionTest
import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.RellCodeTester
import net.postchain.rell.test.RellTestContext
import org.junit.Test

class ExternalTest: BaseRellTest() {
    @Test fun testSimple() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        tst.defs = listOf("external 'foo' { class user(log) { name; } }")
        tst.chainDependency("foo", "deadbeef", 1000)
        chk("user @ {} ( =user, =.name )", "(user[1],text[Bob])")
    }

    @Test fun testExternalInsideNamespace() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        tst.defs = listOf("namespace bar { external 'foo' { class user(log) { name; } } }")
        tst.chainDependency("foo", "deadbeef", 1000)
        chk("bar.user @ {} ( =user, =.name )", "(bar.user[1],text[Bob])")
        chk("user @ {} ( =user, =.name )", "ct_err:unknown_class:user")
    }

    @Test fun testNamespaceInsideExternal() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain(333, "foo.bar.user", "namespace foo { namespace bar { class user(log) { name; } } }")
        tst.defs = listOf("namespace abc { external 'ext' { namespace foo { namespace bar { class user(log) { name; } } } } }")
        tst.chainDependency("ext", "deadbeef", 1000)
        chk("abc.foo.bar.user @ {} ( =user, =.name )", "(abc.foo.bar.user[1],text[Bob])")
        chk("foo.bar.user @ {} ( =user, =.name )", "ct_err:unknown_class:foo.bar.user")
    }

    @Test fun testUnallowedDefs() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.blockchain(444, "cafebabe")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        tst.chainDependency("foo", "deadbeef", 1000)
        tst.chainDependency("bar", "cafebabe", 1000)
        chkCompile("external 'foo' { external 'bar' {} }", "ct_err:def_external:external")
        chkCompile("external 'foo' { object state { mutable x: integer = 123; } }", "ct_err:def_external:object")
        chkCompile("external 'foo' { record r { x: integer; } }", "ct_err:def_external:record")
        chkCompile("external 'foo' { enum e { A, B, C } }", "ct_err:def_external:enum")
        chkCompile("external 'foo' { function f(){} }", "ct_err:def_external:function")
        chkCompile("external 'foo' { operation o(){} }", "ct_err:def_external:operation")
        chkCompile("external 'foo' { query q() = 123; }", "ct_err:def_external:query")
    }

    @Test fun testNoLog() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        tst.chainDependency("foo", "deadbeef", 1000)
        chkCompile("external 'foo' { class user { name; } }", "ct_err:def_class_external_nolog:user")
    }

    @Test fun testDuplicateExternalBlock() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        tst.chainDependency("foo", "deadbeef", 1000)
        chkCompile("external 'foo' { class user(log) { name; } } external 'foo' {}", "ct_err:def_external_dup:foo")
    }

    @Test fun testDuplicateChainRID() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        tst.chainDependency("foo", "deadbeef", 1000)
        tst.chainDependency("bar", "deadbeef", 2000)
        chk("123", "rt_err:external_chain_dup_rid:bar:deadbeef")
    }

    @Test fun testUnknownChain() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        tst.chainDependency("foo", "deadbeef", 1000)
        chkQueryEx("external 'bar' { class user(log) { name; } } query q() = 123;", "rt_err:external_chain_unknown:bar")
    }

    @Test fun testReferenceInternalToExternal() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        tst.defs = listOf("external 'foo' { class user(log) { name; } }", "class local { user; }")
        tst.insert("c0.local", "user", "1,1")
        tst.chainDependency("foo", "deadbeef", 1000)
        chk("local @ {} ( =local, =.user, =.user.name )", "(local[1],user[1],text[Bob])")
    }

    @Test fun testReferenceExternalToInternal() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.blockchain(555, "cafebabe")

        // Init chain "bar" (create meta info)
        run {
            val t = RellCodeTester(tstCtx)
            t.defs = listOf("class company(log) { name; }")
            t.chainId = 555
            t.insert(LibBlockTransactionTest.BLOCK_INSERTS)
            t.init()
        }

        // Init chain "foo" (create meta info)
        run {
            val t = RellCodeTester(tstCtx)
            t.defs = listOf("external 'bar' { class company(log) { name; } }", "class user(log) { name; company; }")
            t.chainId = 333
            t.dropTables = false
            t.chainDependency("bar", "cafebabe", 1000)
            t.init()
        }

        tst.defs = listOf(
                "class company(log) { name; }",
                "external 'foo' { class user(log) { name; company; } }"
        )
        tst.chainId = 555
        tst.createTables = false
        tst.dropTables = false
        tst.chainDependency("foo", "deadbeef", 1000)
        tst.insert("c555.company", "name,transaction", "33,'Google',444")
        tst.insert("c333.user", "name,transaction,company", "17,'Bob',2,33")

        chk("user @ {} ( =user, =.name, =.company, =.company.name )", "(user[17],text[Bob],company[33],text[Google])")
    }

    @Test fun testAccessTransaction() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        tst.defs = listOf("external 'foo' { class user(log) { name; } }")
        tst.chainDependency("foo", "deadbeef", 1000)

        chk("user@{} ( .name )", "text[Bob]")
        chk("user@{} ( .transaction )", "external[foo].transaction[444]")
        chk("user@{} ( .transaction.tx_rid )", "byte_array[fade]")
        chk("user@{} ( .transaction.tx_hash )", "byte_array[1234]")
        chk("user@{} ( .transaction.tx_data )", "byte_array[edaf]")
        chk("user@{} ( .transaction.block )", "external[foo].block[111]")
        chk("user@{} ( .transaction.block.block_height )", "int[222]")
        chk("user@{} ( .transaction.block.block_rid )", "byte_array[deadbeef]")
        chk("user@{} ( .transaction.block.timestamp )", "int[1500000000000]")
    }

    @Test fun testAccessReference() {
        tstCtx.blockchain(333, "deadbeef")

        run {
            val t = RellCodeTester(tstCtx)
            t.defs = listOf("class company(log) { name; }", "class user(log) { name; company; }")
            t.chainId = 333
            t.insert(LibBlockTransactionTest.BLOCK_INSERTS)
            t.insert("c333.company", "name,transaction", "1,'Google',444")
            t.insert("c333.user", "name,company,transaction", "1,'Bob',1,444")
            t.chkQuery("user @ {} ( =user, =.name, =.company, =.company.name, =.transaction )",
                    "(user[1],text[Bob],company[1],text[Google],transaction[444])")
        }
        tst.dropTables = false

        tst.defs = listOf("external 'foo' { class company(log) { name; } class user(log) { name; company; } }")
        tst.chainDependency("foo", "deadbeef", 1000)
        chk("user @ {} ( =user, =.name, =.company, =.company.name )", "(user[1],text[Bob],company[1],text[Google])")
        chk("company @ {} ( =company, =.name )", "(company[1],text[Google])")
    }

    @Test fun testHeightCheck() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkHeight(10, "user @? {}", "null")
        chkHeight(221, "user @? {}", "null")
        chkHeight(222, "user @? {}", "user[1]")
        chkHeight(223, "user @? {}", "user[1]")
        chkHeight(1000, "user @? {}", "user[1]")

        chkHeight(10, "local @? {} ( .user )", "user[1]")
        chkHeight(222, "local @? {} ( .user )", "user[1]")
        chkHeight(223, "local @? {} ( .user )", "user[1]")
        chkHeight(1000, "local @? {} ( .user )", "user[1]")
    }

    private fun chkHeight(height: Long, code: String, expected: String) {
        initExternalChain()
        run {
            val t = RellCodeTester(tstCtx)
            t.dropTables = false
            t.defs = listOf("external 'foo' { class user(log) { name; } }", "class local { user; }")
            t.insert("c0.local", "user", "1,1")
            t.chainDependency("foo", "deadbeef", height)
            t.chkQuery(code, expected)
        }
    }

    @Test fun testMisc() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        tst.chainDependency("foo", "deadbeef", 1000)
        chkQueryEx("external 'foo' {} query q() = 123;", "int[123]") // Empty external block
        chkCompile("external '' {}", "ct_err:def_external_invalid:")
    }

    @Test fun testTxImplicitTransactionBlockTypes() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.blockchain(555, "beefdead")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain(chainId = 333)
        initExternalChain(chainId = 555, resetDatabase = false)
        tst.defs = listOf(
                "namespace foo { external 'foo' { class user(log) { name; } } }",
                "namespace bar { external 'bar' { class user(log) { name; } } }",
                "class local_user(log) { name; }"
        )
        tst.chainDependency("foo", "deadbeef", 1000)
        tst.chainDependency("bar", "beefdead", 1000)
        tst.insert("c0.local_user", "name,transaction", "1,'Bob',2")

        chk("_typeOf((foo.user @ {}).transaction)", "text[external[foo].transaction]")
        chk("_typeOf((foo.user @ {}).transaction.block)", "text[external[foo].block]")
        chk("_typeOf((bar.user @ {}).transaction)", "text[external[bar].transaction]")
        chk("_typeOf((bar.user @ {}).transaction.block)", "text[external[bar].block]")
        chk("_typeOf((local_user @ {}).transaction)", "text[transaction]")
        chk("_typeOf((local_user @ {}).transaction.block)", "text[block]")

        chkEx("{ val t: transaction = (foo.user @ {}).transaction; return 0; }",
                "ct_err:stmt_var_type:t:transaction:external[foo].transaction")
        chkEx("{ val b: block = (foo.user @ {}).transaction.block; return 0; }",
                "ct_err:stmt_var_type:b:block:external[foo].block")

        val txFn = "function f(t: transaction, u: foo.user)"
        chkCompile("$txFn = (t == u.transaction);", "ct_err:binop_operand_type:==:transaction:external[foo].transaction")
        chkCompile("$txFn = (t != u.transaction);", "ct_err:binop_operand_type:!=:transaction:external[foo].transaction")
        chkCompile("$txFn = (t < u.transaction);", "ct_err:binop_operand_type:<:transaction:external[foo].transaction")
        chkCompile("$txFn = (t > u.transaction);", "ct_err:binop_operand_type:>:transaction:external[foo].transaction")

        val blkFn = "function f(b: block, u: foo.user)"
        chkCompile("$blkFn = (b == u.transaction.block);", "ct_err:binop_operand_type:==:block:external[foo].block")
        chkCompile("$blkFn = (b != u.transaction.block);", "ct_err:binop_operand_type:!=:block:external[foo].block")
        chkCompile("$blkFn = (b < u.transaction.block);", "ct_err:binop_operand_type:<:block:external[foo].block")
        chkCompile("$blkFn = (b > u.transaction.block);", "ct_err:binop_operand_type:>:block:external[foo].block")
    }

    @Test fun testTxExplicitTypeDeclaration() {
        chkCompile("class transaction;", "ct_err:name_conflict:class:transaction")
        chkCompile("class block;", "ct_err:name_conflict:class:block")
        chkCompile("class foo;", "ct_err:def_class_hdr_name:foo")
        chkCompile("external 'foo' { class transaction {} }", "ct_err:name_conflict:class:transaction")
        chkCompile("external 'foo' { class block {} }", "ct_err:name_conflict:class:block")

        chkCompile("namespace abc { external 'foo' { class transaction; } }", "OK")
        chkCompile("namespace abc { external 'foo' { class block; } }", "OK")
        chkCompile("namespace abc { external 'foo' { class transaction; class block; } }", "OK")
        chkCompile("namespace abc { external 'foo' { class transaction; class transaction; } }",
                "ct_err:name_conflict:class:transaction")
        chkCompile("namespace abc { external 'foo' { class block; class block; } }", "ct_err:name_conflict:class:block")
        chkCompile("external 'foo' { class foo; }", "ct_err:def_class_hdr_name:foo")
        chkCompile("namespace abc { external 'foo' { class foo; } }", "ct_err:def_class_hdr_name:foo")

        chkCompile("namespace abc { external 'foo' { class transaction(log); } }", "ct_err:def_class_hdr_annotations:transaction")
        chkCompile("namespace abc { external 'foo' { class block(log); } }", "ct_err:def_class_hdr_annotations:block")
        chkCompile("namespace abc { external 'foo' { class block(aaa); } }", "ct_err:def_class_hdr_annotations:block")

        chkCompile("namespace abc { external 'foo' { class transaction(log) {} } }", "ct_err:def_class_external_unallowed:transaction")
        chkCompile("namespace abc { external 'foo' { class block(log) {} } }", "ct_err:def_class_external_unallowed:block")
        chkCompile("namespace abc { external 'foo' { namespace xyz { class transaction(log) {} } } }", "OK")
        chkCompile("namespace abc { external 'foo' { namespace xyz { class block(log) {} } } }", "OK")

        chkCompile("external 'foo' { namespace xyz { class transaction; } }", "ct_err:def_class_hdr_ns:transaction")
        chkCompile("external 'foo' { namespace xyz { class block; } }", "ct_err:def_class_hdr_ns:block")
        chkCompile("namespace abc { external 'foo' { class transaction; } }", "OK")
        chkCompile("namespace abc { external 'foo' { class block; } }", "OK")
    }

    @Test fun testTxExplicitTypeCompatibility() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.blockchain(555, "beefdead")
        tst.defs = listOf(
                "class user(log){ name; }",
                "namespace foo { external 'foo' { class transaction; class block; class user(log) {name;} } }",
                "namespace bar { external 'bar' { class transaction; class block; class user(log) {name;} } }"
        )
        tst.chainDependency("foo", "deadbeef", 1000)
        tst.chainDependency("bar", "beefdead", 1000)

        chkCompile("function f(u: foo.user): transaction = u.transaction;", "ct_err:entity_rettype:transaction:foo.transaction")
        chkCompile("function f(u: user): foo.transaction = u.transaction;", "ct_err:entity_rettype:foo.transaction:transaction")
        chkCompile("function f(u: foo.user): block = u.transaction.block;", "ct_err:entity_rettype:block:foo.block")
        chkCompile("function f(u: user): foo.block = u.transaction.block;", "ct_err:entity_rettype:foo.block:block")

        chkCompile("function f(u: bar.user): foo.transaction = u.transaction;", "ct_err:entity_rettype:foo.transaction:bar.transaction")
        chkCompile("function f(u: foo.user): bar.transaction = u.transaction;", "ct_err:entity_rettype:bar.transaction:foo.transaction")
        chkCompile("function f(u: bar.user): foo.block = u.transaction.block;", "ct_err:entity_rettype:foo.block:bar.block")
        chkCompile("function f(u: foo.user): bar.block = u.transaction.block;", "ct_err:entity_rettype:bar.block:foo.block")
    }

    @Test fun testTxExplicitTypeCompatibility2() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.blockchain(555, "beefdead")
        tst.defs = listOf(
                "class user(log){ name; }",
                "namespace foo { external 'foo' { class transaction; class block; class user(log) {name;} } }",
                "namespace bar { external 'bar' { class transaction; class block; class user(log) {name;} } }"
        )
        tst.chainDependency("foo", "deadbeef", 1000)
        tst.chainDependency("bar", "beefdead", 1000)

        fun chkOpErr(type1: String, type2: String, op: String) {
            chkCompile("function f(x: $type1, y: $type2): boolean = (x $op y);",
                    "ct_err:binop_operand_type:$op:$type1:$type2")
        }

        fun chkTypes(type1: String, type2: String) {
            chkOpErr(type1, type2, "==")
            chkOpErr(type1, type2, "!=")
            chkOpErr(type1, type2, "<")
            chkOpErr(type1, type2, ">")
        }

        chkTypes("transaction", "foo.transaction")
        chkTypes("foo.transaction", "transaction")
        chkTypes("foo.transaction", "bar.transaction")
        chkTypes("block", "foo.block")
        chkTypes("foo.block", "block")
        chkTypes("foo.block", "bar.block")
    }

    @Test fun testTxExplicitTypeLocalVar() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        tst.defs = listOf("namespace foo { external 'foo' { class transaction; class block; class user(log) { name; } } }")
        tst.chainDependency("foo", "deadbeef", 1000)

        val tx = "val t: foo.transaction = foo.user @ {} (.transaction);"
        val block = "$tx; val b: foo.block = t.block;"
        chkEx("{ $tx; return t; }", "foo.transaction[444]")
        chkEx("{ $tx; return t.tx_rid; }", "byte_array[fade]")
        chkEx("{ $tx; return t.tx_hash; }", "byte_array[1234]")
        chkEx("{ $tx; return t.tx_data; }", "byte_array[edaf]")
        chkEx("{ $block; return b; }", "foo.block[111]")
        chkEx("{ $block; return b.block_height; }", "int[222]")
        chkEx("{ $block; return b.block_rid; }", "byte_array[deadbeef]")
        chkEx("{ $block; return b.timestamp; }", "int[1500000000000]")
    }

    @Test fun testTxExplicitTypeAttribute() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        tst.defs = listOf(
                "namespace foo { external 'foo' { class transaction; class block; class user(log) { name; } } }",
                "class local { tx: foo.transaction; blk: foo.block; }"
        )
        tst.chainDependency("foo", "deadbeef", 1000)

        chkOp("val u = foo.user @ {}; create local(u.transaction, u.transaction.block);")
        chkData("local(1,444,111)")

        chk("(local @ {}).tx", "foo.transaction[444]")
        chk("(local @ {}).blk", "foo.block[111]")
    }

    @Test fun testTxExplicitTypeSelect() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkTxExplicitTypeSelect(10, "[]", "[]")
        chkTxExplicitTypeSelect(221, "[]", "[]")
        chkTxExplicitTypeSelect(222, "[foo.transaction[444]]", "[foo.block[111]]")
        chkTxExplicitTypeSelect(1000, "[foo.transaction[444]]", "[foo.block[111]]")
        chkTxExplicitTypeSelect(1000000, "[foo.transaction[444]]", "[foo.block[111]]")
    }

    private fun chkTxExplicitTypeSelect(height: Long, expectedTx: String, expectedBlock: String) {
        initExternalChain()

        run {
            val t = RellCodeTester(tstCtx)
            t.dropTables = false
            t.defs = listOf("namespace foo { external 'foo' { class block; class transaction; } }")
            t.strictToString = false
            t.chainDependency("foo", "deadbeef", height)
            t.chkQuery("transaction @* {}", "[]")
            t.chkQuery("block @* {}", "[]")
            t.chkQuery("foo.transaction @* {}", expectedTx)
            t.chkQuery("foo.block @* {}", expectedBlock)
        }
    }

    @Test fun testGtxExternalClass() {
        val blockInserts = RellTestContext.BlockBuilder()
                .block(1001, 123, 1, "DEAD01", "1001", 1510000000000)
                .block(1002, 123, 2, "DEAD02", "1002", 1520000000000)
                .block(1003, 123, 3, "DEAD03", "1003", 1530000000000)
                .block(1004, 123, 4, "DEAD04", "1004", 1540000000000)
                .block(1005, 123, 5, "DEAD05", "1005", 1550000000000)
                .tx(2001, 123, 1001, "BEEF01", "2001", "1234")
                .tx(2002, 123, 1002, "BEEF02", "2002", "1234")
                .tx(2003, 123, 1003, "BEEF03", "2003", "1234")
                .tx(2004, 123, 1004, "BEEF04", "2004", "1234")
                .tx(2005, 123, 1005, "BEEF05", "2005", "1234")
                .list()

        tstCtx.blockchain(123, "deadbeef")

        run {
            val t = RellCodeTester(tstCtx)
            t.defs = listOf("class user(log) { name; }")
            t.chainId = 123
            t.insert(blockInserts)
            t.insert("c123.user", "name,transaction", "1,'Alice',2001")
            t.insert("c123.user", "name,transaction", "2,'Bob',2002")
            t.insert("c123.user", "name,transaction", "3,'Calvin',2003")
            t.insert("c123.user", "name,transaction", "4,'Donald',2004")
            t.insert("c123.user", "name,transaction", "5,'Evan',2005")
            t.chkQuery("(user @* {}).size()", "int[5]")
        }

        tst.dropTables = false
        tst.defs = listOf("external 'foo' { class user(log) { name; } }", "record rec { u: user; }")
        tst.chainDependency("foo", "deadbeef", 3)

        fun code(id: Long) = """rec.fromPrettyGTXValue(GTXValue.fromJSON('{"u":$id}'))"""
        chk(code(1), "rec[u=user[1]]")
        chk(code(2), "rec[u=user[2]]")
        chk(code(3), "rec[u=user[3]]")
        chk(code(4), "gtx_err:obj_missing:user:4")
        chk(code(5), "gtx_err:obj_missing:user:5")
        chk(code(321), "gtx_err:obj_missing:user:321")
    }

    @Test fun testGtxExternalTransaction() {
        tst.defs = listOf(
                "namespace foo { external 'foo' { class block; class transaction; } }",
                "record r_tx { t: transaction; }",
                "record r_block { b: block; }",
                "record r_foo_tx { t: foo.transaction; }",
                "record r_foo_block { b: foo.block; }"
        )
        tst.gtx = true

        fun chkType(type: String) {
            chkCompile("query q(): $type { var t: $type? = null; return t!!; }", "ct_err:result_nogtx:q:$type")
            chkCompile("query q(x: $type) = 0;", "ct_err:param_nogtx:x:$type")
            chkCompile("operation o(x: $type) {}", "ct_err:param_nogtx:x:$type")
        }

        fun chkRecType(type: String) {
            chkType(type)

            val err1 = "ct_err:fn_record_invalid:$type:$type"
            chkCompile("function f(x: $type) { x.toBytes(); }", "$err1.toBytes")
            chkCompile("function f(x: $type) { x.toGTXValue(); }", "$err1.toGTXValue")
            chkCompile("function f(x: $type) { x.toPrettyGTXValue(); }", "$err1.toPrettyGTXValue")

            val err2 = "ct_err:fn_record_invalid:$type"
            chkCompile("function f() { $type.fromBytes(x''); }", "$err2:fromBytes")
            chkCompile("function f() { $type.fromGTXValue(GTXValue.fromBytes(x'')); }", "$err2:fromGTXValue")
            chkCompile("function f() { $type.fromPrettyGTXValue(GTXValue.fromBytes(x'')); }", "$err2:fromPrettyGTXValue")
        }

        chkType("transaction")
        chkType("block")
        chkType("foo.transaction")
        chkType("foo.block")

        chkRecType("r_tx")
        chkRecType("r_block")
        chkRecType("r_foo_tx")
        chkRecType("r_foo_block")
    }

    @Test fun testCreateExternalClass() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        tst.defs = listOf("external 'foo' { class user(log) { name; } }")
        tst.chainDependency("foo", "deadbeef", 1000)
        chkOp("create user (name = 'Alice');", "ct_err:expr_create_cant:user")
    }

    @Test fun testDeleteExternalClass() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        tst.defs = listOf("external 'foo' { class user(log) { name; } }")
        tst.chainDependency("foo", "deadbeef", 1000)
        chkOp("delete user @* {};", "ct_err:stmt_delete_cant:user")
    }

    @Test fun testMetaClassNotFound() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkMetaClass(
                "class company(log){}",
                "external 'foo' { class user(log){} }",
                "rt_err:external_meta_nocls:foo:user"
        )
    }

    @Test fun testMetaClassNoLog() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkMetaClass(
                "class user {}",
                "external 'foo' { class user(log){} }",
                "rt_err:external_meta_nolog:foo:user"
        )
    }

    @Test fun testMetaAttributeNotFound() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkMetaClass(
                "class user(log) { name: text; }",
                "external 'foo' { class user(log){ fullName: text; } }",
                "rt_err:external_meta_noattrs:foo:user:fullName"
        )
    }

    @Test fun testMetaAttributeWrongType() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkMetaClass(
                "class user(log) { attr: integer; }",
                "external 'foo' { class user(log){ attr: text; } }",
                "rt_err:external_meta_attrtype:foo:user:attr:[sys:text]:[sys:integer]"
        )

        chkMetaClass(
                "class user(log) { attr: text; }",
                "external 'foo' { class user(log){ attr: byte_array; } }",
                "rt_err:external_meta_attrtype:foo:user:attr:[sys:byte_array]:[sys:text]"
        )

        chkMetaClass(
                "class user(log) { name: text; }",
                "external 'foo' { class user(log){ name; } }",
                "OK"
        )

        chkMetaClass(
                "class user(log) { name; }",
                "external 'foo' { class user(log){ name: text; } }",
                "OK"
        )
    }

    @Test fun testMetaAttributeWrongChain() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkMetaClass(
                "class company(log){} class user(log) { company; }",
                "class company(log){} external 'foo' { class user(log){ company; } }",
                "rt_err:external_meta_attrtype:foo:user:company:[class:0:company]:[class:333:company]"
        )
    }

    @Test fun testMetaAttributeSubset() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkMetaClass(
                "class user(log) { x: integer; y: text; z: boolean; }",
                "external 'foo' { class user(log){ x: integer; z: boolean; } }",
                "OK"
        )

        chkMetaClass(
                "class user(log) { x: integer; y: text; z: boolean; }",
                "external 'foo' { class user(log){ y: text; } }",
                "OK"
        )
    }

    @Test fun testMetaClassNamespace() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkMetaClass(
                "namespace x { class user(log) {} }",
                "external 'foo' { class user(log){} }",
                "rt_err:external_meta_nocls:foo:user"
        )

        chkMetaClass(
                "namespace x { class user(log) {} }",
                "external 'foo' { namespace x { class user(log){} } }",
                "OK"
        )

        chkMetaClass(
                "namespace x { class user(log) {} }",
                "namespace y { external 'foo' { namespace x { class user(log){} } } }",
                "OK"
        )
    }

    @Test fun testMetaAttributeNamespace() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        val extDefs = "namespace x { class company(log){} } namespace y { class user(log){ c: x.company; } }"

        chkMetaClass(
                extDefs,
                "namespace z { external 'foo' { namespace x { class company(log){} } namespace y { class user(log){ c: z.x.company; } } } }",
                "OK"
        )

        chkMetaClass(
                extDefs,
                "namespace z { external 'foo' { namespace x { class company(log){} class user(log){ c: company; } } } }",
                "rt_err:external_meta_nocls:foo:x.user"
        )

        chkMetaClass(
                extDefs,
                "namespace z { external 'foo' { namespace x { class company(log){} } class user(log){ c: x.company; } } }",
                "rt_err:external_meta_nocls:foo:user"
        )

        chkMetaClass(
                extDefs,
                "namespace z { external 'foo' { class company(log){} namespace y { class user(log){ c: company; } } } }",
                "rt_err:external_meta_nocls:foo:company"
        )
    }

    @Test fun testMetaData() {
        tstCtx.blockchain(333, "deadbeef")

        run {
            val t = RellCodeTester(tstCtx)
            t.defs = listOf("class ext_a(log) { value: integer; }", "namespace y { class ext_b(log) {} }")
            t.chainId = 333
            t.insert(LibBlockTransactionTest.BLOCK_INSERTS)
            t.init()
        }

        tst.defs = listOf(
                "class helper(log) { id: integer; data: byte_array; }",
                "namespace x { external 'foo' { class ext_a(log) { value: integer; } namespace y { class ext_b(log) {} } } }",
                "class my_class { b: boolean; i: integer; t: text; n: name; h: helper; ea: x.ext_a; eb: x.y.ext_b; }"
        )
        tst.dropTables = false
        tst.chainDependency("foo", "deadbeef", 1000)
        tst.init()

        tst.chkDataSql("""SELECT C.name, C.log FROM "c0.sys.classes" C ORDER BY C.name;""",
                "helper,true",
                "my_class,false"
        )

        val sql = """SELECT C.name, A.name, A.type
            | FROM "c0.sys.attributes" A JOIN "c0.sys.classes" C ON A.class_id = C.id
            | ORDER BY C.name, A.name;""".trimMargin()

        tst.chkDataSql(sql,
                "helper,data,sys:byte_array",
                "helper,id,sys:integer",
                "helper,transaction,class:0:transaction",
                "my_class,b,sys:boolean",
                "my_class,ea,class:333:ext_a",
                "my_class,eb,class:333:y.ext_b",
                "my_class,h,class:0:helper",
                "my_class,i,sys:integer",
                "my_class,n,sys:text",
                "my_class,t,sys:text"
        )
    }

    private fun chkMetaClass(externalDefs: String, localDefs: String, expected: String) {
        run {
            val t = RellCodeTester(tstCtx)
            t.defs = listOf(externalDefs)
            t.chainId = 333
            t.chkQuery("123", "int[123]") // Initializes database
        }

        run {
            val t = RellCodeTester(tstCtx)
            t.defs = listOf(localDefs)
            t.dropTables = false
            t.strictToString = false
            t.chainDependency("foo", "deadbeef", 1000)
            t.chkQuery("'OK'", expected)
        }
    }

    private fun initExternalChain(
            chainId: Long = 333,
            className: String = "user",
            def: String = "class user(log) { name; }",
            resetDatabase: Boolean = true
    ) {
        run {
            val t = RellCodeTester(tstCtx)
            t.defs = listOf(def)
            t.chainId = chainId
            t.dropTables = resetDatabase
            t.insert("c$chainId.$className", "name,transaction", "1,'Bob',444")
            t.chkQuery("$className @ {} ( =user, =.name )", "($className[1],text[Bob])")
        }
        tst.dropTables = false
    }
}
