package net.postchain.rell.module

import net.postchain.rell.test.BaseGtxTest
import org.junit.Test

class ChainContextTest : BaseGtxTest() {
    @Test fun testRawConfig() {
        chk("chain_context.raw_config",
                "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.9':{'main.rell':'query q() = chain_context.raw_config;'}}}}")

        tst.moduleArgs = "'bar'"
        chk("chain_context.raw_config",
                "{'gtx':{'rell':{'mainFile':'main.rell','moduleArgs':'bar','sources_v0.9':{'main.rell':'query q() = chain_context.raw_config;'}}}}")
    }

    @Test fun testModuleArgs() {
        def("record module_args { s: text; n: integer; }")
        chkUserMistake("", "Module initialization failed: No moduleArgs in blockchain configuration")

        tst.moduleArgs = "{'s':'Hello','n':123}"
        chk("_type_of(chain_context.args)", "'module_args'")
        chk("chain_context.args", "{'n':123,'s':'Hello'}")
        chk("chain_context.args.s", "'Hello'")
        chk("chain_context.args.n", "123")
        chk("chain_context.args.x", "ct_err:unknown_member:module_args:x")

        tst.moduleArgs = null
        chkUserMistake("", "Module initialization failed: No moduleArgs in blockchain configuration")

        tst.moduleArgs = "12345"
        chkUserMistake("", "Module initialization failed: Type error: ")

        tst.moduleArgs = "{'p':'Hello','q':123}"
        chkUserMistake("", "Module initialization failed: Key missing in Gtv dictionary: ")

        tst.moduleArgs = "{'n':'Hello','s':123}"
        chkUserMistake("", "Module initialization failed: Type error: ")

        tst.moduleArgs = "{'s':'Hello','n':123}"
        chk("chain_context.args", "{'n':123,'s':'Hello'}")
    }

    @Test fun testModuleArgsNoRecord() {
        chk("chain_context.args", "ct_err:expr_chainctx_args_norec")
    }

    @Test fun testModuleArgsRecord() {
        chkCompile("record module_args {}", "OK")
        chkCompile("record module_args { x: map<text, integer>; }", "OK")
        chkCompile("record module_args { x: range; }", "ct_err:module_args_nogtv")
        chkCompile("record module_args { x: (a: integer, text); }", "OK")
        chkCompile("class module_args {}", "OK")
        chkCompile("record module_args { x: virtual<list<integer>>; }", "OK")
    }

    @Test fun testBlockchainRid() {
        chk("chain_context.blockchain_rid", "'DEADBEEF'")
    }
}
