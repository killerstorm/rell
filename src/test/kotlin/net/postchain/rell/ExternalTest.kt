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
        def("external 'foo' { @log class user { name; } }")
        tst.chainDependency("foo", "deadbeef", 1000)
        chk("user @ {} ( =user, =.name )", "(user[1],text[Bob])")
    }

    @Test fun testExternalInsideNamespace() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        def("namespace bar { external 'foo' { @log class user { name; } } }")
        tst.chainDependency("foo", "deadbeef", 1000)
        chk("bar.user @ {} ( =user, =.name )", "(bar.user[1],text[Bob])")
        chk("user @ {} ( =user, =.name )", "ct_err:unknown_class:user")
    }

    @Test fun testNamespaceInsideExternal() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain(333, "foo.bar.user", "namespace foo { namespace bar { @log class user { name; } } }")
        def("namespace abc { external 'ext' { namespace foo { namespace bar { @log class user { name; } } } } }")
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
        chkQueryEx("external 'bar' { @log class user { name; } } query q() = 123;", "rt_err:external_chain_unknown:bar")
    }

    @Test fun testReferenceInternalToExternal() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        def("external 'foo' { @log class user { name; } }")
        def("class local { user; }")
        insert("c0.local", "user", "1,1")
        tst.chainDependency("foo", "deadbeef", 1000)
        chk("local @ {} ( =local, =.user, =.user.name )", "(local[1],user[1],text[Bob])")
    }

    @Test fun testReferenceExternalToInternal() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.blockchain(555, "cafebabe")

        // Init chain "bar" (create meta info)
        run {
            val t = RellCodeTester(tstCtx)
            t.def("@log class company { name; }")
            t.chainId = 555
            t.insert(LibBlockTransactionTest.BLOCK_INSERTS)
            t.init()
        }

        // Init chain "foo" (create meta info)
        run {
            val t = RellCodeTester(tstCtx)
            t.def("external 'bar' { @log class company { name; } }")
            t.def("@log class user { name; company; }")
            t.chainId = 333
            t.dropTables = false
            t.chainDependency("bar", "cafebabe", 1000)
            t.init()
        }

        def("@log class company { name; }")
        def("external 'foo' { @log class user { name; company; } }")
        tst.chainId = 555
        tst.createTables = false
        tst.dropTables = false
        tst.chainDependency("foo", "deadbeef", 1000)
        insert("c555.company", "name,transaction", "33,'Google',444")
        insert("c333.user", "name,transaction,company", "17,'Bob',2,33")

        chk("user @ {} ( =user, =.name, =.company, =.company.name )", "(user[17],text[Bob],company[33],text[Google])")
    }

    @Test fun testAccessTransaction() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        def("external 'foo' { @log class user { name; } }")
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
            t.def("@log class company { name; }")
            t.def("@log class user { name; company; }")
            t.chainId = 333
            t.insert(LibBlockTransactionTest.BLOCK_INSERTS)
            t.insert("c333.company", "name,transaction", "1,'Google',444")
            t.insert("c333.user", "name,company,transaction", "1,'Bob',1,444")
            t.chkQuery("user @ {} ( =user, =.name, =.company, =.company.name, =.transaction )",
                    "(user[1],text[Bob],company[1],text[Google],transaction[444])")
        }
        tst.dropTables = false

        def("external 'foo' { @log class company { name; } @log class user { name; company; } }")
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
            t.def("external 'foo' { @log class user { name; } }")
            t.def("class local { user; }")
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
        def("namespace foo { external 'foo' { @log class user { name; } } }")
        def("namespace bar { external 'bar' { @log class user { name; } } }")
        def("@log class local_user { name; }")
        tst.chainDependency("foo", "deadbeef", 1000)
        tst.chainDependency("bar", "beefdead", 1000)
        insert("c0.local_user", "name,transaction", "1,'Bob',2")

        chk("_type_of((foo.user @ {}).transaction)", "text[external[foo].transaction]")
        chk("_type_of((foo.user @ {}).transaction.block)", "text[external[foo].block]")
        chk("_type_of((bar.user @ {}).transaction)", "text[external[bar].transaction]")
        chk("_type_of((bar.user @ {}).transaction.block)", "text[external[bar].block]")
        chk("_type_of((local_user @ {}).transaction)", "text[transaction]")
        chk("_type_of((local_user @ {}).transaction.block)", "text[block]")

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
        chkCompile("class transaction;", "ct_err:def_class_hdr_noexternal:transaction")
        chkCompile("class block;", "ct_err:def_class_hdr_noexternal:block")
        chkCompile("class foo;", "ct_err:[def_class_hdr_name:foo][def_class_hdr_noexternal:foo]")
        chkCompile("external 'foo' { class transaction {} }", "ct_err:def_class_external_unallowed:transaction")
        chkCompile("external 'foo' { class block {} }", "ct_err:def_class_external_unallowed:block")

        chkCompile("namespace abc { external 'foo' { class transaction; } }", "OK")
        chkCompile("namespace abc { external 'foo' { class block; } }", "OK")
        chkCompile("namespace abc { external 'foo' { class transaction; class block; } }", "OK")

        chkCompile("namespace abc { external 'foo' { class transaction; class transaction; } }", """ct_err:
            [name_conflict:user:transaction:CLASS:main.rell(1:59)]
            [name_conflict:user:transaction:CLASS:main.rell(1:40)]
        """)

        chkCompile("namespace abc { external 'foo' { class block; class block; } }", """ct_err:
            [name_conflict:user:block:CLASS:main.rell(1:53)]
            [name_conflict:user:block:CLASS:main.rell(1:40)]
        """)

        chkCompile("external 'foo' { class foo; }", "ct_err:def_class_hdr_name:foo")
        chkCompile("namespace abc { external 'foo' { class foo; } }", "ct_err:def_class_hdr_name:foo")

        chkCompile("namespace abc { external 'foo' { @log class transaction; } }", "ct_err:ann:log:not_allowed:CLASS:transaction")
        chkCompile("namespace abc { external 'foo' { @log class block; } }", "ct_err:ann:log:not_allowed:CLASS:block")
        chkCompile("namespace abc { external 'foo' { @aaa class block; } }", "ct_err:ann:invalid:aaa")
        chkCompile("namespace abc { external 'foo' { class transaction(log); } }", "ct_err:def_class_hdr_annotations:transaction")
        chkCompile("namespace abc { external 'foo' { class block(log); } }", "ct_err:def_class_hdr_annotations:block")
        chkCompile("namespace abc { external 'foo' { class block(aaa); } }", "ct_err:def_class_hdr_annotations:block")

        chkCompile("namespace abc { external 'foo' { @log class transaction {} } }", "ct_err:def_class_external_unallowed:transaction")
        chkCompile("namespace abc { external 'foo' { @log class block {} } }", "ct_err:def_class_external_unallowed:block")
        chkCompile("namespace abc { external 'foo' { namespace xyz { @log class transaction {} } } }", "OK")
        chkCompile("namespace abc { external 'foo' { namespace xyz { @log class block {} } } }", "OK")

        chkCompile("external 'foo' { namespace xyz { class transaction; } }", "OK")
        chkCompile("external 'foo' { namespace xyz { class block; } }", "OK")
        chkCompile("namespace abc { external 'foo' { class transaction; } }", "OK")
        chkCompile("namespace abc { external 'foo' { class block; } }", "OK")
    }

    @Test fun testTxExplicitTypeMount() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)
        tst.chainDependency("foo", "deadbeef", 1000)
        initExternalChain()

        chkCompile("namespace ns { external 'foo' { @mount('') class transaction; } }", "ct_err:ann:mount:not_allowed:CLASS:transaction")
        chkCompile("namespace ns { external 'foo' { @mount('') class block; } }", "ct_err:ann:mount:not_allowed:CLASS:block")
        chkCompile("namespace ns { external 'foo' { @mount('bar') class transaction; } }", "ct_err:ann:mount:not_allowed:CLASS:transaction")
        chkCompile("namespace ns { external 'foo' { @mount('bar') class block; } }", "ct_err:ann:mount:not_allowed:CLASS:block")

        def("namespace ns1 { @mount('bar') external 'foo' { class transaction; class block; } }")
        def("@mount('bar') namespace ns2 { external 'foo' { class transaction; class block; } }")

        chk("ns1.transaction @ {}", "external[foo].transaction[444]")
        chk("ns2.transaction @ {}", "external[foo].transaction[444]")
        chk("ns1.block @ {}", "external[foo].block[111]")
        chk("ns2.block @ {}", "external[foo].block[111]")
    }

    @Test fun testTxExplicitTypeCompatibility() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.blockchain(555, "beefdead")
        def("@log class user{ name; }")
        def("namespace foo { external 'foo' { class transaction; class block; @log class user {name;} } }")
        def("namespace bar { external 'bar' { class transaction; class block; @log class user {name;} } }")
        tst.chainDependency("foo", "deadbeef", 1000)
        tst.chainDependency("bar", "beefdead", 1000)

        chkCompile("function f(u: foo.user): transaction = u.transaction;", "ct_err:entity_rettype:transaction:external[foo].transaction")
        chkCompile("function f(u: user): foo.transaction = u.transaction;", "ct_err:entity_rettype:external[foo].transaction:transaction")
        chkCompile("function f(u: foo.user): block = u.transaction.block;", "ct_err:entity_rettype:block:external[foo].block")
        chkCompile("function f(u: user): foo.block = u.transaction.block;", "ct_err:entity_rettype:external[foo].block:block")

        chkCompile("function f(u: bar.user): foo.transaction = u.transaction;", "ct_err:entity_rettype:external[foo].transaction:external[bar].transaction")
        chkCompile("function f(u: foo.user): bar.transaction = u.transaction;", "ct_err:entity_rettype:external[bar].transaction:external[foo].transaction")
        chkCompile("function f(u: bar.user): foo.block = u.transaction.block;", "ct_err:entity_rettype:external[foo].block:external[bar].block")
        chkCompile("function f(u: foo.user): bar.block = u.transaction.block;", "ct_err:entity_rettype:external[bar].block:external[foo].block")
    }

    @Test fun testTxExplicitTypeCompatibility2() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.blockchain(555, "beefdead")
        def("@log class user { name; }")
        def("namespace foo { external 'foo' { class transaction; class block; @log class user {name;} } }")
        def("namespace bar { external 'bar' { class transaction; class block; @log class user {name;} } }")
        tst.chainDependency("foo", "deadbeef", 1000)
        tst.chainDependency("bar", "beefdead", 1000)

        fun chkOpErr(type1: String, typeStr1: String, type2: String, typeStr2: String, op: String) {
            chkCompile("function f(x: $type1, y: $type2): boolean = (x $op y);",
                    "ct_err:binop_operand_type:$op:$typeStr1:$typeStr2")
        }

        fun chkTypes(type1: String, typeStr1: String, type2: String, typeStr2: String) {
            chkOpErr(type1, typeStr1, type2, typeStr2, "==")
            chkOpErr(type1, typeStr1, type2, typeStr2, "!=")
            chkOpErr(type1, typeStr1, type2, typeStr2, "<")
            chkOpErr(type1, typeStr1, type2, typeStr2, ">")
        }

        chkTypes("transaction", "transaction", "foo.transaction", "external[foo].transaction")
        chkTypes("foo.transaction", "external[foo].transaction", "transaction", "transaction")
        chkTypes("foo.transaction", "external[foo].transaction", "bar.transaction", "external[bar].transaction")
        chkTypes("block", "block", "foo.block", "external[foo].block")
        chkTypes("foo.block", "external[foo].block", "block", "block")
        chkTypes("foo.block", "external[foo].block", "bar.block", "external[bar].block")
    }

    @Test fun testTxExplicitTypeCompatibility3() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)
        tst.chainDependency("foo", "deadbeef", 1000)
        initExternalChain()

        def("namespace ns1 { @mount('bar') external 'foo' { class transaction; class block; } }")
        def("@mount('bar') namespace ns2 { external 'foo' { class transaction; class block; } }")

        chkEx("{ val tx: ns1.transaction = ns2.transaction @ {}; return tx; }", "external[foo].transaction[444]")
        chkEx("{ val tx: ns2.transaction = ns1.transaction @ {}; return tx; }", "external[foo].transaction[444]")
        chkEx("{ val b: ns1.block = ns2.block @ {}; return b; }", "external[foo].block[111]")
        chkEx("{ val b: ns2.block = ns1.block @ {}; return b; }", "external[foo].block[111]")
    }

    @Test fun testTxExplicitTypeLocalVar() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        def("namespace foo { external 'foo' { class transaction; class block; @log class user { name; } } }")
        tst.chainDependency("foo", "deadbeef", 1000)

        val tx = "val t: foo.transaction = foo.user @ {} (.transaction);"
        val block = "$tx; val b: foo.block = t.block;"
        chkEx("{ $tx; return t; }", "external[foo].transaction[444]")
        chkEx("{ $tx; return t.tx_rid; }", "byte_array[fade]")
        chkEx("{ $tx; return t.tx_hash; }", "byte_array[1234]")
        chkEx("{ $tx; return t.tx_data; }", "byte_array[edaf]")
        chkEx("{ $block; return b; }", "external[foo].block[111]")
        chkEx("{ $block; return b.block_height; }", "int[222]")
        chkEx("{ $block; return b.block_rid; }", "byte_array[deadbeef]")
        chkEx("{ $block; return b.timestamp; }", "int[1500000000000]")
    }

    @Test fun testTxExplicitTypeAttribute() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        def("namespace foo { external 'foo' { class transaction; class block; @log class user { name; } } }")
        def("class local { tx: foo.transaction; blk: foo.block; }")
        tst.chainDependency("foo", "deadbeef", 1000)

        chkOp("val u = foo.user @ {}; create local(u.transaction, u.transaction.block);")
        chkData("local(1,444,111)")

        chk("(local @ {}).tx", "external[foo].transaction[444]")
        chk("(local @ {}).blk", "external[foo].block[111]")
    }

    @Test fun testTxExplicitTypeSelect() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkTxExplicitTypeSelect(10, "[]", "[]")
        chkTxExplicitTypeSelect(221, "[]", "[]")
        chkTxExplicitTypeSelect(222, "[external[foo].transaction[444]]", "[external[foo].block[111]]")
        chkTxExplicitTypeSelect(1000, "[external[foo].transaction[444]]", "[external[foo].block[111]]")
        chkTxExplicitTypeSelect(1000000, "[external[foo].transaction[444]]", "[external[foo].block[111]]")
    }

    private fun chkTxExplicitTypeSelect(height: Long, expectedTx: String, expectedBlock: String) {
        initExternalChain()

        run {
            val t = RellCodeTester(tstCtx)
            t.dropTables = false
            t.def("namespace foo { external 'foo' { class block; class transaction; } }")
            t.strictToString = false
            t.chainDependency("foo", "deadbeef", height)
            t.chkQuery("transaction @* {}", "[]")
            t.chkQuery("block @* {}", "[]")
            t.chkQuery("foo.transaction @* {}", expectedTx)
            t.chkQuery("foo.block @* {}", expectedBlock)
        }
    }

    @Test fun testGtvExternalClass() {
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
            t.def("@log class user { name; }")
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
        def("external 'foo' { @log class user { name; } }")
        def("record rec { u: user; }")
        tst.chainDependency("foo", "deadbeef", 3)

        fun code(id: Long) = """rec.from_gtv_pretty(gtv.from_json('{"u":$id}'))"""
        chk(code(1), "rec[u=user[1]]")
        chk(code(2), "rec[u=user[2]]")
        chk(code(3), "rec[u=user[3]]")
        chk(code(4), "gtv_err:obj_missing:user:4")
        chk(code(5), "gtv_err:obj_missing:user:5")
        chk(code(321), "gtv_err:obj_missing:user:321")
    }

    @Test fun testGtvExternalTransaction() {
        def("namespace foo { external 'foo' { class block; class transaction; } }")
        def("record r_tx { t: transaction; }")
        def("record r_block { b: block; }")
        def("record r_foo_tx { t: foo.transaction; }")
        def("record r_foo_block { b: foo.block; }")
        tst.gtv = true

        fun chkType(type: String, typeStr: String = type) {
            chkCompile("function nop(x: $type?): $type? = x; query q(): $type { var t: $type? = nop(null); return t!!; }",
                    "ct_err:result_nogtv:q:$typeStr")
            chkCompile("query q(x: $type) = 0;", "ct_err:param_nogtv:x:$typeStr")
            chkCompile("operation o(x: $type) {}", "ct_err:param_nogtv:x:$typeStr")
        }

        fun chkRecType(type: String) {
            chkType(type)

            val err1 = "ct_err:fn:invalid:$type:$type"
            chkCompile("function f(x: $type) { x.to_bytes(); }", "$err1.to_bytes")
            chkCompile("function f(x: $type) { x.to_gtv(); }", "$err1.to_gtv")
            chkCompile("function f(x: $type) { x.to_gtv_pretty(); }", "$err1.to_gtv_pretty")

            val err2 = "ct_err:fn:invalid:$type"
            chkCompile("function f() { $type.from_bytes(x''); }", "$err2:from_bytes")
            chkCompile("function f() { $type.from_gtv(gtv.from_bytes(x'')); }", "$err2:from_gtv")
            chkCompile("function f() { $type.from_gtv_pretty(gtv.from_bytes(x'')); }", "$err2:from_gtv_pretty")
        }

        chkType("transaction")
        chkType("block")
        chkType("foo.transaction", "external[foo].transaction")
        chkType("foo.block", "external[foo].block")

        chkRecType("r_tx")
        chkRecType("r_block")
        chkRecType("r_foo_tx")
        chkRecType("r_foo_block")
    }

    @Test fun testCreateExternalClass() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        def("external 'foo' { @log class user { name; } }")
        tst.chainDependency("foo", "deadbeef", 1000)
        chkOp("create user (name = 'Alice');", "ct_err:expr_create_cant:user")
    }

    @Test fun testDeleteExternalClass() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain()
        def("external 'foo' { @log class user { name; } }")
        tst.chainDependency("foo", "deadbeef", 1000)
        chkOp("delete user @* {};", "ct_err:stmt_delete_cant:user")
    }

    @Test fun testMetaClassNotFound() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkMetaClass(
                "@log class company {}",
                "external 'foo' { @log class user {} }",
                "rt_err:external_meta_nocls:foo:user"
        )
    }

    @Test fun testMetaClassObject() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkMetaClass(
                "object user { name: text = 'Bob'; }",
                "external 'foo' { @log class user {} }",
                "rt_err:external_meta_nocls:foo:user"
        )
    }

    @Test fun testMetaClassNoLog() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkMetaClass(
                "class user {}",
                "external 'foo' { @log class user {} }",
                "rt_err:external_meta_nolog:foo:user"
        )
    }

    @Test fun testMetaAttributeNotFound() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkMetaClass(
                "@log class user { name: text; }",
                "external 'foo' { @log class user { fullName: text; } }",
                "rt_err:external_meta_noattrs:foo:user:fullName"
        )
    }

    @Test fun testMetaAttributeWrongType() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkMetaClass(
                "@log class user { attr: integer; }",
                "external 'foo' { @log class user { attr: text; } }",
                "rt_err:external_meta_attrtype:foo:user:attr:[sys:text]:[sys:integer]"
        )

        chkMetaClass(
                "@log class user { attr: text; }",
                "external 'foo' { @log class user { attr: byte_array; } }",
                "rt_err:external_meta_attrtype:foo:user:attr:[sys:byte_array]:[sys:text]"
        )

        chkMetaClass(
                "@log class user { name: text; }",
                "external 'foo' { @log class user { name; } }",
                "OK"
        )

        chkMetaClass(
                "@log class user { name; }",
                "external 'foo' { @log class user { name: text; } }",
                "OK"
        )
    }

    @Test fun testMetaAttributeWrongChain() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkMetaClass(
                "@log class company {} @log class user { company; }",
                "@log class company {} external 'foo' { @log class user { company; } }",
                "rt_err:external_meta_attrtype:foo:user:company:[class:0:company]:[class:333:company]"
        )
    }

    @Test fun testMetaAttributeSubset() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkMetaClass(
                "@log class user { x: integer; y: text; z: boolean; }",
                "external 'foo' { @log class user { x: integer; z: boolean; } }",
                "OK"
        )

        chkMetaClass(
                "@log class user { x: integer; y: text; z: boolean; }",
                "external 'foo' { @log class user { y: text; } }",
                "OK"
        )
    }

    @Test fun testMetaClassNamespace() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        chkMetaClass(
                "namespace x { @log class user {} }",
                "external 'foo' { @log class user {} }",
                "rt_err:external_meta_nocls:foo:user"
        )

        chkMetaClass(
                "namespace x { @log class user {} }",
                "external 'foo' { namespace x { @log class user {} } }",
                "OK"
        )

        chkMetaClass(
                "namespace x { @log class user {} }",
                "namespace y { external 'foo' { namespace x { @log class user {} } } }",
                "OK"
        )
    }

    @Test fun testMetaAttributeNamespace() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        val extDefs = "namespace x { @log class company {} } namespace y { @log class user { c: x.company; } }"

        chkMetaClass(
                extDefs,
                "namespace z { external 'foo' { namespace x { @log class company {} } namespace y { @log class user { c: z.x.company; } } } }",
                "OK"
        )

        chkMetaClass(
                extDefs,
                "namespace z { external 'foo' { namespace x { @log class company {} @log class user { c: company; } } } }",
                "rt_err:external_meta_nocls:foo:x.user"
        )

        chkMetaClass(
                extDefs,
                "namespace z { external 'foo' { namespace x { @log class company {} } @log class user { c: x.company; } } }",
                "rt_err:external_meta_nocls:foo:user"
        )

        chkMetaClass(
                extDefs,
                "namespace z { external 'foo' { @log class company {} namespace y { @log class user { c: company; } } } }",
                "rt_err:external_meta_nocls:foo:company"
        )
    }

    @Test fun testMetaData() {
        tstCtx.blockchain(333, "deadbeef")

        run {
            val t = RellCodeTester(tstCtx)
            t.def("@log class ext_a { value: integer; }")
            t.def("namespace y { @log class ext_b {} }")
            t.chainId = 333
            t.insert(LibBlockTransactionTest.BLOCK_INSERTS)
            t.init()
        }

        def("@log class helper { id: integer; data: byte_array; }")
        def("namespace x { external 'foo' { @log class ext_a { value: integer; } namespace y { @log class ext_b {} } } }")
        def("class my_class { b: boolean; i: integer; t: text; n: name; h: helper; ea: x.ext_a; eb: x.y.ext_b; }")
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
            t.def(externalDefs)
            t.chainId = 333
            t.chkQuery("123", "int[123]") // Initializes database
        }

        run {
            val t = RellCodeTester(tstCtx)
            t.def(localDefs)
            t.dropTables = false
            t.strictToString = false
            t.chainDependency("foo", "deadbeef", 1000)
            t.chkQuery("'OK'", expected)
        }
    }

    @Test fun testMountClass() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        initExternalChain(333, "foo.bar.user", "namespace foo { namespace bar { @log class user { name; } } }")

        tst.chainDependency("foo", "deadbeef", 1000)

        chkQueryEx("external 'foo' { @log class user { name; } } query q() = user @ {} ( =user, =.name );",
                "rt_err:external_meta_nocls:foo:user")

        chkQueryEx("external 'foo' { namespace foo { namespace bar { @log class user { name; } } } } " +
                "query q() = foo.bar.user @ {} ( =user, =.name );",
                "(foo.bar.user[1],text[Bob])")

        chkQueryEx("external 'foo' { @mount('foo.bar.user') @log class admin { name; } } " +
                "query q() = admin @ {} ( =admin, =.name );",
                "(admin[1],text[Bob])")

        chkQueryEx("@mount('foo.bar') external 'foo' { @log class user { name; } } " +
                "query q() = user @ {} ( =user, =.name );",
                "(user[1],text[Bob])")

        chkQueryEx("external 'foo' { @mount('foo.bar') namespace ns { @log class user { name; } } } " +
                "query q() = ns.user @ {} ( =user, =.name );",
                "(ns.user[1],text[Bob])")

        chkQueryEx("@mount('junk') external 'foo' { @mount('foo.bar') namespace ns { @log class user { name; } } } " +
                "query q() = ns.user @ {} ( =user, =.name );",
                "(ns.user[1],text[Bob])")

        chkQueryEx("@mount('junk') external 'foo' { @mount('trash') namespace ns { @mount('foo.bar.user') @log class user { name; } } } " +
                "query q() = ns.user @ {} ( =user, =.name );",
                "(ns.user[1],text[Bob])")
    }

    @Test fun testDuplicateExternalBlock() {
        chkCompile("namespace ns1 { external 'foo' { class transaction; } } namespace ns2 { external 'foo' { class transaction; } }", "OK")
        chkCompile("namespace ns1 { external 'foo' { class block; } } namespace ns2 { external 'foo' { class block; } }", "OK")
        chkCompile("namespace ns1 { external 'foo' { @log class user {} } } namespace ns2 { external 'foo' { @log class company {} } }", "OK")

        chkCompile("namespace ns1 { external 'foo' { @log class user {} } } namespace ns2 { external 'foo' { @log class user {} } }", """ct_err:
            [mnt_conflict:user:ns1.user:user:CLASS:ns2.user:main.rell(1:101)]
            [mnt_conflict:user:ns2.user:user:CLASS:ns1.user:main.rell(1:45)]
        """)
    }

    private fun initExternalChain(
            chainId: Long = 333,
            className: String = "user",
            def: String = "@log class user { name; }",
            resetDatabase: Boolean = true
    ) {
        run {
            val t = RellCodeTester(tstCtx)
            t.def(def)
            t.chainId = chainId
            t.dropTables = resetDatabase
            t.insert("c$chainId.$className", "name,transaction", "1,'Bob',444")
            t.chkQuery("$className @ {} ( =user, =.name )", "($className[1],text[Bob])")
        }
        tst.dropTables = false
    }
}
