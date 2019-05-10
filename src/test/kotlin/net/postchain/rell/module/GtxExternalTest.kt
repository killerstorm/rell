package net.postchain.rell.module

import net.postchain.rell.lib.LibBlockTransactionTest
import net.postchain.rell.test.BaseGtxTest
import net.postchain.rell.test.RellCodeTester
import org.junit.Test

class GtxExternalTest: BaseGtxTest() {
    @Test fun testUnknownChain() {
        tst.defs = listOf("external 'foo' {}")
        tst.wrapRtErrors = false
        tst.chkQuery("123", "rt_err:external_chain_unknown:foo")
    }

    @Test fun testUnknownChain2() {
        tst.defs = listOf("external 'foo' {}")
        tst.wrapRtErrors = false
        tst.extraModuleConfig["dependencies"] = "{'foo':'deadbeef'}"
        tst.chkQuery("123", "rt_err:external_chain_norid:foo:deadbeef")
    }

    @Test fun testUnknownChain3() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        run {
            val t = RellCodeTester(tstCtx)
            t.chainId = 333
            t.init()
        }

        tst.defs = listOf("external 'foo' {}")
        tst.wrapRtErrors = false
        tst.extraModuleConfig["dependencies"] = "{'foo':'deadbeef'}"
        tst.chkQuery("123", "123")
    }

    @Test fun testUnknownExternalClass() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        run {
            val t = RellCodeTester(tstCtx)
            t.chainId = 333
            t.init()
        }

        tst.defs = listOf("external 'foo' { class user(log) {} }")
        tst.wrapRtErrors = false
        tst.extraModuleConfig["dependencies"] = "{'foo':'deadbeef'}"
        tst.chkQuery("123", "rt_err:external_meta_nocls:foo:user")
    }

    @Test fun testExternalClassOK() {
        tstCtx.blockchain(333, "deadbeef")
        tstCtx.insert(LibBlockTransactionTest.BLOCK_INSERTS)

        run {
            val t = RellCodeTester(tstCtx)
            t.defs = listOf("class user(log) { name; }")
            t.chainId = 333
            t.insert("c333.user", "name,transaction", "15,'Bob',444")
            t.init()
        }

        tst.defs = listOf("external 'foo' { class user(log) { name; } }")
        tst.wrapRtErrors = false
        tst.extraModuleConfig["dependencies"] = "{'foo':'deadbeef'}"
        tst.chkQuery("_strict_str(user @{} ( =user, =.name ))", "'(user[15],text[Bob])'")
    }
}
