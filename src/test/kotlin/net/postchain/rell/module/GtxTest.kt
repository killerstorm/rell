package net.postchain.rell.module

import net.postchain.rell.test.BaseGtxTest
import org.junit.Test

class GtxTest : BaseGtxTest() {
    @Test fun testChainContextRawConfig() {
        chk("chain_context.raw_config",
                "{'gtx':{'rell':{'mainFile':'main.rell','sources_v0.9':{'main.rell':'query q() = chain_context.raw_config;'}}}}")

        tst.moduleArgs = "'bar'"
        chk("chain_context.raw_config",
                "{'gtx':{'rell':{'mainFile':'main.rell','moduleArgs':'bar','sources_v0.9':{'main.rell':'query q() = chain_context.raw_config;'}}}}")
    }

    @Test fun testChainContextModuleArgs() {
        def("record module_args { s: text; n: integer; }")
        chk("chain_context.args", "null")

        tst.moduleArgs = "{'s':'Hello','n':123}"
        chk("chain_context.args", "{'n':123,'s':'Hello'}")
        chk("chain_context.args.s", "ct_err:expr_mem_null:s")
        chk("chain_context.args?.s", "'Hello'")
        chk("chain_context.args?.n", "123")
        chk("chain_context.args?.x", "ct_err:unknown_member:module_args:x")

        tst.moduleArgs = null
        chk("chain_context.args", "null")

        tst.moduleArgs = "12345"
        chkUserMistake("", "Module initialization failed: Type error: ")

        tst.moduleArgs = "{'p':'Hello','q':123}"
        chkUserMistake("", "Module initialization failed: Key missing in Gtv dictionary: ")

        tst.moduleArgs = "{'n':'Hello','s':123}"
        chkUserMistake("", "Module initialization failed: Type error: ")

        tst.moduleArgs = "{'s':'Hello','n':123}"
        chk("chain_context.args", "{'n':123,'s':'Hello'}")
    }

    @Test fun testChainContextModuleArgsNoRecord() {
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

    @Test fun testObject() {
        def("object foo { x: integer = 123; s: text = 'Hello'; }")
        chk("foo.x", "123")
        chk("foo.s", "'Hello'")
    }

    @Test fun testInclude() {
        tst.file("foo.rell", "function f(): integer = 123;")
        def("namespace foo { include 'foo'; }")
        chk("foo.f()", "123")
    }

    @Test fun testNamespaceOperation() {
        def("namespace foo { operation bar() {print('Hello');} }")
        chkCallOperation("foo.bar", listOf())
        chkStdout("Hello")
    }

    @Test fun testNamespaceQuery() {
        def("namespace foo { query bar() = 123; }")
        chkCallQuery("foo.bar", "", "123")
    }
}
