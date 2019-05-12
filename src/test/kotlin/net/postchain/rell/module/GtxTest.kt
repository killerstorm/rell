package net.postchain.rell.module

import net.postchain.rell.test.BaseGtxTest
import org.junit.Test

class GtxTest : BaseGtxTest() {
    @Test fun testChainContextRawConfig() {
        tst.chkQuery("chain_context.raw_config",
                "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.8':{'main.rell':'query q() \\u003d chain_context.raw_config;'}}}}")

        tst.moduleArgs = "'bar'"
        tst.chkQuery("chain_context.raw_config",
                "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.8':{'main.rell':'query q() \\u003d chain_context.raw_config;'},'moduleArgs':'bar'}}}")
    }

    @Test fun testChainContextModuleArgs() {
        tst.defs = listOf("record module_args { s: text; n: integer; }")
        tst.chkQuery("chain_context.args", "null")

        tst.moduleArgs = "{'s':'Hello','n':123}"
        tst.chkQuery("chain_context.args", "{'s':'Hello','n':123}")
        tst.chkQuery("chain_context.args.s", "ct_err:expr_mem_null:s")
        tst.chkQuery("chain_context.args?.s", "'Hello'")
        tst.chkQuery("chain_context.args?.n", "123")
        tst.chkQuery("chain_context.args?.x", "ct_err:unknown_member:module_args:x")

        tst.moduleArgs = null
        tst.chkQuery("chain_context.args", "null")

        tst.moduleArgs = "12345"
        tst.chkUserMistake("", "Type error: ")

        tst.moduleArgs = "{'p':'Hello','q':123}"
        tst.chkUserMistake("", "Key missing in Gtv dictionary: ")

        tst.moduleArgs = "{'n':'Hello','s':123}"
        tst.chkUserMistake("", "Type error: ")

        tst.moduleArgs = "{'s':'Hello','n':123}"
        tst.chkQuery("chain_context.args", "{'s':'Hello','n':123}")
    }

    @Test fun testChainContextModuleArgsNoRecord() {
        tst.chkQuery("chain_context.args", "ct_err:expr_chainctx_args_norec")
    }

    @Test fun testModuleArgsRecord() {
        tst.chkCompile("record module_args {}", "OK")
        tst.chkCompile("record module_args { x: map<text, integer>; }", "OK")
        tst.chkCompile("record module_args { x: range; }", "ct_err:module_args_nogtv")
        tst.chkCompile("record module_args { x: (a: integer, text); }", "OK")
        tst.chkCompile("class module_args {}", "OK")
    }

    @Test fun testObject() {
        tst.defs = listOf("object foo { x: integer = 123; s: text = 'Hello'; }")
        tst.chkQuery("foo.x", "123")
        tst.chkQuery("foo.s", "'Hello'")
    }

    @Test fun testInclude() {
        tst.file("foo.rell", "function f(): integer = 123;")
        tst.defs = listOf("namespace foo { include 'foo'; }")
        tst.chkQuery("foo.f()", "123")
    }

    @Test fun testNamespaceOperation() {
        tst.defs = listOf("namespace foo { operation bar() {print('Hello');} }")
        tst.chkCallOperation("foo.bar", listOf())
        tst.chkStdout("Hello")
    }

    @Test fun testNamespaceQuery() {
        tst.defs = listOf("namespace foo { query bar() = 123; }")
        tst.chkCallQuery("foo.bar", "", "123")
    }
}
