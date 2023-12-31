/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib

import net.postchain.rell.base.testutils.BaseRellTest
import org.junit.Test

class LibMetaTest: BaseRellTest(false) {
    @Test fun testType() {
        chkCompile("struct data { m: rell.meta; }", "OK")
        chkCompile("struct data { m: set<rell.meta>; }", "OK")
        chkCompile("function f(m: rell.meta) = m.to_gtv();", "ct_err:fn:invalid:rell.meta:to_gtv")
        chkCompile("function f() = rell.meta.from_gtv(gtv.from_bytes(x''));", "ct_err:fn:invalid:rell.meta:from_gtv")
    }

    @Test fun testConstructor() {
        def("entity data {}")
        def("object state {}")
        def("operation op() {}")
        def("query qq() = 0;")

        chk("_type_of(rell.meta(data))", "text[rell.meta]")
        chk("_type_of(rell.meta(state))", "text[rell.meta]")
        chk("_type_of(rell.meta(op))", "text[rell.meta]")
        chk("_type_of(rell.meta(qq))", "text[rell.meta]")

        chk("rell.meta(data)", "rell.meta[:data]")
        chk("rell.meta(state)", "rell.meta[:state]")
        chk("rell.meta(op)", "rell.meta[:op]")
        chk("rell.meta(qq)", "rell.meta[:qq]")
    }

    @Test fun testConstructorComplexName() {
        file("lib.rell", """
            module;
            namespace b {
                entity data {}
                object state {}
                operation op() {}
                query qq() = 0;
            }
        """)
        def("namespace a { import lib; }")

        chk("rell.meta(a.lib.b.data)", "rell.meta[lib:b.data]")
        chk("rell.meta(a.lib.b.state)", "rell.meta[lib:b.state]")
        chk("rell.meta(a.lib.b.op)", "rell.meta[lib:b.op]")
        chk("rell.meta(a.lib.b.qq)", "rell.meta[lib:b.qq]")
    }

    @Test fun testConstructorUnsupportedDef() {
        file("lib.rell", "module;")
        def("import lib;")
        def("struct rec {}")
        def("enum color { red }")
        def("function f() {}")
        def("val X = 123;")
        def("namespace ns {}")

        chk("rell.meta(lib)", "ct_err:expr_call:bad_arg:[rell.meta]")
        chk("rell.meta(rec)", "ct_err:expr_call:bad_arg:[rell.meta]")
        chk("rell.meta(color)", "ct_err:expr_call:bad_arg:[rell.meta]")
        chk("rell.meta(f)", "ct_err:expr_call:bad_arg:[rell.meta]")
        chk("rell.meta(X)", "ct_err:expr_call:bad_arg:[rell.meta]")
        chk("rell.meta(ns)", "ct_err:expr_call:bad_arg:[rell.meta]")
    }

    @Test fun testConstructorBadArgument() {
        def("entity data {}")
        chk("rell.meta()", "ct_err:fn:sys:wrong_arg_count:1:1:0")
        chk("rell.meta(0)", "ct_err:expr_call:bad_arg:[rell.meta]")
        chk("rell.meta('data')", "ct_err:expr_call:bad_arg:[rell.meta]")
        chk("rell.meta(data, 0)", "ct_err:fn:sys:wrong_arg_count:1:1:2")
        chk("rell.meta(foo)", "ct_err:unknown_name:foo")
    }

    @Test fun testUseAsConstant() {
        def("entity data {}")
        def("operation op() {}")
        def("val data_meta = rell.meta(data);")
        def("val op_meta = rell.meta(op);")

        chk("_type_of(data_meta)", "text[rell.meta]")
        chk("_type_of(op_meta)", "text[rell.meta]")
        chk("data_meta", "rell.meta[:data]")
        chk("op_meta", "rell.meta[:op]")
    }

    @Test fun testDefinitionNames() {
        file("app/lib.rell", "module; namespace a.b { entity data {} }")
        def("object state {}")
        def("import app.lib.{a.b.data};")

        chk("rell.meta(data)", "rell.meta[app.lib:a.b.data]")
        chk("rell.meta(data).simple_name", "text[data]")
        chk("rell.meta(data).module_name", "text[app.lib]")
        chk("rell.meta(data).full_name", "text[app.lib:a.b.data]")

        chk("rell.meta(state)", "rell.meta[:state]")
        chk("rell.meta(state).simple_name", "text[state]")
        chk("rell.meta(state).module_name", "text[]")
        chk("rell.meta(state).full_name", "text[:state]")
    }

    @Test fun testMountName() {
        def("entity data {}")
        def("object state {}")
        def("operation op() {}")
        def("query qq() = 0;")
        def("@mount('my_data') entity data_2 {}")
        def("@mount('my_state') object state_2 {}")
        def("@mount('my_op') operation op_2() {}")
        def("@mount('my_qq') query qq_2() = 0;")
        def("namespace ns { entity data_3 {} }")
        def("@mount('foo.bar') object state_3 {}")

        chk("_type_of(rell.meta(data).mount_name)", "text[text]")

        chk("rell.meta(data).mount_name", "text[data]")
        chk("rell.meta(state).mount_name", "text[state]")
        chk("rell.meta(op).mount_name", "text[op]")
        chk("rell.meta(qq).mount_name", "text[qq]")

        chk("rell.meta(data_2).mount_name", "text[my_data]")
        chk("rell.meta(state_2).mount_name", "text[my_state]")
        chk("rell.meta(op_2).mount_name", "text[my_op]")
        chk("rell.meta(qq_2).mount_name", "text[my_qq]")

        chk("rell.meta(ns.data_3).mount_name", "text[ns.data_3]")
        chk("rell.meta(state_3).mount_name", "text[foo.bar]")
    }

    @Test fun testToText() {
        file("app/lib.rell", "module; namespace a.b { entity data {} }")
        def("namespace ns { object state {} }")
        def("operation op() {}")
        def("import app.lib.{a.b.data};")

        chk("rell.meta(data).to_text()", "ct_err:unknown_member:[rell.meta]:to_text")
        chk("rell.meta(ns.state).to_text()", "ct_err:unknown_member:[rell.meta]:to_text")
        chk("rell.meta(op).to_text()", "ct_err:unknown_member:[rell.meta]:to_text")

        chk("'' + rell.meta(data)", "text[meta[app.lib:a.b.data]]")
        chk("'' + rell.meta(ns.state)", "text[meta[:ns.state]]")
        chk("'' + rell.meta(op)", "text[meta[:op]]")
    }
}
