/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.gtx

import net.postchain.rell.test.BaseGtxTest
import net.postchain.rell.test.RellTestUtils
import org.junit.Test

class ChainContextTest : BaseGtxTest() {
    @Test fun testRawConfig() {
        val ver = RellTestUtils.RELL_VER

        chk("chain_context.raw_config",
                "{'gtx':{'rell':{'moduleArgs':{},'modules':[''],'sources':{'main.rell':'query q() = chain_context.raw_config;'},'version':'$ver'}}}")

        tst.moduleArgs("" to "'bar'")
        chk("chain_context.raw_config",
                "{'gtx':{'rell':{'moduleArgs':{'':'bar'},'modules':[''],'sources':{'main.rell':'query q() = chain_context.raw_config;'},'version':'$ver'}}}")
    }

    @Test fun testModuleArgs() {
        def("struct module_args { s: text; n: integer; }")
        chkUserMistake("", "Module initialization failed: No moduleArgs in blockchain configuration")

        tst.moduleArgs("" to "{'s':'Hello','n':123}")
        chk("_type_of(chain_context.args)", "'module_args'")
        chk("chain_context.args", "{'n':123,'s':'Hello'}")
        chk("chain_context.args.s", "'Hello'")
        chk("chain_context.args.n", "123")
        chk("chain_context.args.x", "ct_err:unknown_member:[module_args]:x")

        tst.moduleArgs()
        chkUserMistake("", "Module initialization failed: No moduleArgs in blockchain configuration")

        tst.moduleArgs("" to "12345")
        chkUserMistake("", "Module initialization failed: decoding type 'module_args': expected ARRAY, actual INTEGER")

        tst.moduleArgs("" to "{'p':'Hello','q':123}")
        chkUserMistake("", "Module initialization failed: Key missing in Gtv dictionary: ")

        tst.moduleArgs("" to "{'n':'Hello','s':123}")
        chkUserMistake("", "Module initialization failed: decoding type 'text': expected STRING, actual INTEGER")

        tst.moduleArgs("" to "{'s':'Hello','n':123}")
        chk("chain_context.args", "{'n':123,'s':'Hello'}")

        tst.moduleArgs("" to "{'s':'Hello','n':123}", "foo.bar" to "{'x':123}")
        chk("chain_context.args", "{'n':123,'s':'Hello'}")
    }

    @Test fun testModuleArgsNoStruct() {
        chkCompile("entity module_args {}", "OK")
        chk("chain_context.args", "ct_err:expr_chainctx_args_norec")
    }

    @Test fun testModuleArgsStruct() {
        def("struct module_args { x: integer; y: text; }")
        tst.moduleArgs("" to "[123,'Hello']")
        chk("chain_context.args", "{'x':123,'y':'Hello'}")
    }

    @Test fun testModuleArgsMultiModule() {
        file("lib/a.rell", "module; struct module_args { x: text; } function f(): module_args = chain_context.args;")
        file("lib/b.rell", "module; struct module_args { y: integer; } function f(): module_args = chain_context.args;")
        file("lib/c.rell", "module; struct module_args { z: decimal; } function f(): module_args = chain_context.args;")
        def("import lib.a; import lib.b; import lib.c;")

        tst.moduleArgs("lib.a" to "{x:'Hello'}", "lib.b" to "{'y':123}", "lib.c" to "{'z':'456.789'}")
        chk("a.f()", "{'x':'Hello'}")
        chk("b.f()", "{'y':123}")
        chk("c.f()", "{'z':'456.789'}")
        chk("a.chain_context", "ct_err:unknown_name:[a]:chain_context")
        chk("a.chain_context.args", "ct_err:unknown_name:[a]:chain_context")

        tst.moduleArgs("lib.a" to "{x:'Hello'}", "lib.b" to "{'y':123}", "lib.c" to "{'q':456.789}")
        chkUserMistake("", "Module initialization failed: Key missing in Gtv dictionary")

        tst.moduleArgs()
        chkUserMistake("", "Module initialization failed: No moduleArgs in blockchain configuration for module 'lib.a'")

        tst.moduleArgs("lib.a" to "{x:'Hello'}", "lib.b" to "{'y':123}", "lib.d" to "{'z':456.789}")
        chkUserMistake("", "Module initialization failed: No moduleArgs in blockchain configuration for module 'lib.c'")
    }

    @Test fun testModuleArgsAttrTypes() {
        chkCompile("struct module_args { x: integer; }", "OK")
        chkCompile("struct module_args { mutable x: integer; }", "ct_err:module_args:attr:mutable:module_args:x")

        chkCompile("struct module_args { x: list<integer>; }", "ct_err:module_args:attr:mutable_type:module_args:x")
        chkCompile("struct module_args { x: set<integer>; }", "ct_err:module_args:attr:mutable_type:module_args:x")
        chkCompile("struct module_args { x: map<text, integer>; }", "ct_err:module_args:attr:mutable_type:module_args:x")

        chkCompile("struct module_args { x: (a: integer, text); }", "OK")
        chkCompile("struct module_args { x: range; }", "ct_err:module_args:attr:no_gtv:module_args:x")
        chkCompile("struct module_args { x: virtual<list<integer>>; }", "ct_err:module_args:attr:not_pure:module_args:x")

        chkCompile("entity user { name; } struct module_args { u: user; }", "ct_err:module_args:attr:not_pure:module_args:u")
        chkCompile("entity user { name; } struct module_args { u: user?; }", "ct_err:module_args:attr:not_pure:module_args:u")
        chkCompile("entity user { name; } struct module_args { u: (user, text)?; }",
                "ct_err:module_args:attr:not_pure:module_args:u")

        chkCompile("entity user { name; } struct module_args { u: struct<user>; }", "OK")
        chkCompile("entity user { name; } struct module_args { u: struct<mutable user>; }",
                "ct_err:module_args:attr:mutable_type:module_args:u")

        chkCompile("entity user { name; } entity ref { u: user; } struct module_args { r: struct<ref>; }",
                "ct_err:module_args:attr:not_pure:module_args:r")
        chkCompile("entity user { name; } entity ref { u: user; } struct module_args { r: struct<mutable ref>; }",
                "ct_err:module_args:attr:mutable_type:module_args:r")

        chkCompile("entity user { name; } struct s { u: user?; } struct module_args { s: s; }",
                "ct_err:module_args:attr:not_pure:module_args:s")

        chkCompile("struct module_args { p: (integer) -> text; }", "ct_err:module_args:attr:no_gtv:module_args:p")
    }

    @Test fun testBlockchainRid() {
        val exp = RellTestUtils.strToRidHex("DEADBEEF")
        chk("chain_context.blockchain_rid", "'$exp'")
    }
}
