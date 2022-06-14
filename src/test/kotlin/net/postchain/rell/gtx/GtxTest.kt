/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.gtx

import net.postchain.rell.lib.LibBlockTransactionTest
import net.postchain.rell.test.BaseGtxTest
import org.junit.Test

class GtxTest : BaseGtxTest() {
    @Test fun testObject() {
        def("object foo { x: integer = 123; s: text = 'Hello'; }")
        chk("foo.x", "123")
        chk("foo.s", "'Hello'")
    }

    @Test fun testImport() {
        file("lib/foo.rell", "module; function f(): integer = 123;")
        def("import lib.foo;")
        chk("foo.f()", "123")
    }

    @Test fun testNamespaceOperation() {
        def("namespace foo { operation bar() {print('Hello');} }")
        chkCallOperation("foo.bar", listOf())
        chkOut("Hello")
    }

    @Test fun testNamespaceQuery() {
        def("namespace foo { query bar() = 123; }")
        chkCallQuery("foo.bar", "", "123")
    }

    @Test fun testModules() {
        file("lib/foo.rell", "module; function f(): integer = 123;")
        def("import lib.foo;")
        tst.modules = null
        chk("foo.f()", "123")
    }

    @Test fun testBlockTransactionOut() {
        tst.chainId = 333
        tst.inserts = LibBlockTransactionTest.BLOCK_INSERTS_CURRENT
        tst.gtv = true

        chkCompile("query q(): block? = null;", "OK")
        chkCompile("query q(): transaction? = null;", "OK")
        chkCompile("query q(v: block) = 0;", "OK")
        chkCompile("query q(v: transaction) = 0;", "OK")

        chk("block @ {.block_height == 10}", "101")
        chk("block @ {.block_height == 20}", "102")
        chk("transaction @ {.block.block_height == 10}", "201")
        chk("transaction @ {.block.block_height == 20}", "202")
    }

    @Test fun testBlockTransactionIn() {
        tst.chainId = 333
        tst.inserts = LibBlockTransactionTest.BLOCK_INSERTS_CURRENT
        tst.gtv = true
        tst.wrapRtErrors = false

        def("query blk(v: block) = (v.rowid, v.block_height);")
        def("query tx(v: transaction) = (v.rowid, v.block.rowid, v.block.block_height);")

        chkCallQuery("blk", "v:101", "[101,10]")
        chkCallQuery("blk", "v:102", "[102,20]")
        chkCallQuery("tx", "v:201", "[201,101,10]")
        chkCallQuery("tx", "v:202", "[202,102,20]")

        chkCallQuery("blk", "v:100", "gtv_err:obj_missing:[block]:100")
        chkCallQuery("blk", "v:103", "gtv_err:obj_missing:[block]:103")
        chkCallQuery("blk", "v:201", "gtv_err:obj_missing:[block]:201")
        chkCallQuery("blk", "v:202", "gtv_err:obj_missing:[block]:202")

        chkCallQuery("tx", "v:200", "gtv_err:obj_missing:[transaction]:200")
        chkCallQuery("tx", "v:203", "gtv_err:obj_missing:[transaction]:203")
        chkCallQuery("tx", "v:101", "gtv_err:obj_missing:[transaction]:101")
        chkCallQuery("tx", "v:102", "gtv_err:obj_missing:[transaction]:102")
    }
}
