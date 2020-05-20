package net.postchain.rell.repl

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class ReplTxTest: BaseRellTest(true) {
    private fun initStuff() {
        val chainId = 0L
        val chainRid = "DeadBeef".repeat(8)
        tst.replModule = ""
        tst.chainId = chainId
        tst.chainRid = chainRid
        tstCtx.blockchain(chainId, chainRid)
    }

    @Test fun testTxRun() {
        file("module.rell", "operation foo(x: integer) { print('x='+x); }")
        initStuff()

        repl.chk("val op = foo(123);")
        repl.chk("op", "RES:op[foo(int[123])]")
        repl.chk("val tx = rell.gtx.tx(op);")
        repl.chk("tx.run();", "OUT:x=123", "RES:unit")
    }

    @Test fun testBlockRun() {
        file("module.rell", "operation foo(x: integer) { print('x='+x); }")
        initStuff()

        repl.chk("val tx1 = rell.gtx.tx(foo(123));")
        repl.chk("val tx2 = rell.gtx.tx(foo(456));")
        repl.chk("val blk = rell.gtx.block([tx1, tx2]);")
        repl.chk("blk.run();", "OUT:x=123", "OUT:x=456", "RES:unit")
    }

    @Test fun testMultipleBlocks() {
        file("module.rell", """
            operation foo(x: integer) { print('foo('+x+')'); }
            operation bar(x: integer) { print('bar('+x+')'); }
        """)
        initStuff()

        repl.chk("rell.gtx.tx(foo(123)).run();", "OUT:foo(123)", "RES:unit")
        repl.chk("block@*{}", "RES:list<block>[block[1]]")
        repl.chk("transaction@*{}", "RES:list<transaction>[transaction[1]]")

        repl.chk("rell.gtx.tx(bar(456)).run();", "OUT:bar(456)", "RES:unit")
        repl.chk("block@*{}", "RES:list<block>[block[1],block[2]]")
        repl.chk("transaction@*{}", "RES:list<transaction>[transaction[1],transaction[2]]")

        repl.chk("rell.gtx.tx(foo(789)).run();", "OUT:foo(789)", "RES:unit")
        repl.chk("block@*{}", "RES:list<block>[block[1],block[2],block[3]]")
        repl.chk("transaction@*{}", "RES:list<transaction>[transaction[1],transaction[2],transaction[3]]")
    }

    @Test fun testNoRepl() {
        file("module.rell", "module;")
        def("operation foo(x: integer){}")
        chkEx("{ rell.gtx.tx(foo(123)).run(); return 0; }", "rt_err:fn:block.run:no_repl")
    }

    @Test fun testImportedOperations() {
        file("module.rell", "module;")
        file("a.rell", "module; operation foo(x: integer){print('foo:'+x);}")
        file("b.rell", "module; operation bar(y: integer){print('bar:'+y);}")
        initStuff()

        repl.chk("import a;")
        repl.chk("rell.gtx.tx(a.foo(123)).run();", "OUT:foo:123", "RES:unit")
        repl.chk("import b;")
        repl.chk("rell.gtx.tx(b.bar(456)).run();", "OUT:bar:456", "RES:unit")
    }
}
