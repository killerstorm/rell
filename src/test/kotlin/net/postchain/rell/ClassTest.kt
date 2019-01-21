package net.postchain.rell

import org.junit.Test
import kotlin.test.assertEquals

class ClassTest: BaseRellTest(false) {
    @Test fun testAttrNoType() {
        chkCompile("class foo { name; }", "OK")
        chkCompile("class foo { name123; }", "ct_err:unknown_name_type:name123")
        chkCompile("class foo { index name123; }", "ct_err:unknown_name_type:name123")
        chkCompile("class foo { key name123; }", "ct_err:unknown_name_type:name123")
    }

    @Test fun testIndex() {
        chkCompile("class foo { name; index name; }", "OK")
        chkCompile("class foo { name; index name: text; }", "ct_err:class_keyindex_def:name")
        chkCompile("class foo { index name: text; name; }", "ct_err:class_keyindex_def:name")
        chkCompile("class foo { name; index name; index name; }", "ct_err:class_index_dup:name")

        chkCompile("class foo { name1: text; name2: text; index name1, name2; }", "OK")
        chkCompile("class foo { name1: text; name2: text; index name1; index name2; index name1, name2; }", "OK")
        chkCompile("class foo { name1: text; name2: text; index name1, name2; index name2, name1; }",
                "ct_err:class_index_dup:name1,name2")

        chkCompile("class foo { name1: text; name2: text; index name1, name1; }", "ct_err:class_keyindex_dup:name1")

        chkCompile("class foo { name1: text; index name1, name2: text; }", "OK")
        chkCompile("class foo { name1: text; index name, name1: text; }", "ct_err:class_keyindex_def:name1")

        chkCompile("class foo { mutable name: text; index name; }", "OK")

        chkCompile("class foo { index name; mutable name: text; }", "OK")
        chkCompile("class foo { index name: text; name; }", "ct_err:class_keyindex_def:name")
    }

    @Test fun testIndexWithoutAttr() {
        tst.useSql = true
        tst.defs = listOf("class foo { index name; }", "class bar { index name: text; }")
        execOp("create foo(name = 'A');")
        execOp("create bar(name = 'B');")
        chk("foo @ {} (.name)", "text[A]")
        chk("bar @ {} (.name)", "text[B]")
    }

    @Test fun testIndexWithAttr() {
        tst.useSql = true
        tst.defs = listOf(
                "class A { name; index name; }",
                "class B { index name; name: text; }",
                "class C { name1: text; index name1, name2: text; }",
                "class D { mutable name: text; index name; }"
        )

        execOp("create A(name = 'A');")
        execOp("create B(name = 'B');")
        execOp("create C(name1 = 'C1', name2 = 'C2');")
        execOp("create D(name = 'D1');")

        chk("A @ {} (.name)", "text[A]")
        chk("B @ {} (.name)", "text[B]")
        chk("C @ {} (=.name1,=.name2)", "(text[C1],text[C2])")
        chk("D @ {} (.name)", "text[D1]")

        execOp("update D @ {} (name = 'D2');")
        chk("D @ {} (.name)", "text[D2]")
    }

    @Test fun testKey() {
        chkCompile("class foo { name; key name; }", "OK")
        chkCompile("class foo { name; key name: text; }", "ct_err:class_keyindex_def:name")
        chkCompile("class foo { key name: text; name; }", "ct_err:class_keyindex_def:name")
        chkCompile("class foo { name; key name; key name; }", "ct_err:class_key_dup:name")

        chkCompile("class foo { name1: text; name2: text; key name1, name2; }", "OK")
        chkCompile("class foo { name1: text; name2: text; key name1; key name2; key name1, name2; }", "OK")
        chkCompile("class foo { name1: text; name2: text; key name1, name2; key name2, name1; }",
                "ct_err:class_key_dup:name1,name2")

        chkCompile("class foo { name1: text; name2: text; key name1, name1; }", "ct_err:class_keyindex_dup:name1")

        chkCompile("class foo { name1: text; key name1, name2: text; }", "OK")
        chkCompile("class foo { name1: text; key name, name1: text; }", "ct_err:class_keyindex_def:name1")

        chkCompile("class foo { mutable name: text; key name; }", "OK")

        chkCompile("class foo { key name; mutable name: text; }", "OK")
        chkCompile("class foo { key name: text; name; }", "ct_err:class_keyindex_def:name")
    }

    @Test fun testKeyWithoutAttr() {
        tst.useSql = true
        tst.defs = listOf("class foo { key name; }", "class bar { key name: text; }")
        execOp("create foo(name = 'A');")
        execOp("create bar(name = 'B');")
        chk("foo @ {} (.name)", "text[A]")
        chk("bar @ {} (.name)", "text[B]")
    }

    @Test fun testKeyWithAttr() {
        tst.useSql = true
        tst.defs = listOf(
                "class A { name; key name; }",
                "class B { key name; name: text; }",
                "class C { name1: text; key name1, name2: text; }",
                "class D { mutable name: text; key name; }"
        )

        execOp("create A(name = 'A');")
        execOp("create B(name = 'B');")
        execOp("create C(name1 = 'C1', name2 = 'C2');")
        execOp("create D(name = 'D1');")

        chk("A @ {} (.name)", "text[A]")
        chk("B @ {} (.name)", "text[B]")
        chk("C @ {} (=.name1,=.name2)", "(text[C1],text[C2])")
        chk("D @ {} (.name)", "text[D1]")

        execOp("update D @ {} (name = 'D2');")
        chk("D @ {} (.name)", "text[D2]")
    }

    @Test fun testKeyIndexDupValue() {
        tst.useSql = true
        tst.defs = listOf("class foo { mutable k: text; mutable i: text; key k; index i; }")

        chkOp("create foo(k = 'K1', i = 'I1');", "")
        chkOp("create foo(k = 'K1', i = 'I2');", "rt_err:sqlerr:0")
        chkOp("create foo(k = 'K2', i = 'I1');", "")
        chkData("foo(1,K1,I1)", "foo(2,K2,I1)")

        chkOp("update foo @ { .k == 'K2' } ( k = 'K1' );", "rt_err:sqlerr:0")
        chkData("foo(1,K1,I1)", "foo(2,K2,I1)")
    }

    @Test fun testDeclarationOrder() {
        chkCompile("class user { c: company; } class company { name; }", "OK")
        chkCompile("class user { c: company; } class company { u: user; }", "OK")
        chkCompile("query q() = user @* {}; class user { name; }", "OK")
    }

    @Test fun testForwardReferenceInAttributeValue() {
        tst.useSql = true
        tst.defs = listOf(
                "class foo { x: integer; k: integer = (bar@*{ .v > 0 }).size(); }",
                "class bar { v: integer; }"
        )

        execOp("""
            create foo(x = 1);
            create bar(-1);
            create foo(x = 2);
            create bar(5);
            create foo(x = 3);
            create bar(6);
            create foo(x = 4);
        """.trimIndent())

        chk("foo @ {.x == 1}(.k)", "int[0]")
        chk("foo @ {.x == 2}(.k)", "int[0]")
        chk("foo @ {.x == 3}(.k)", "int[1]")
        chk("foo @ {.x == 4}(.k)", "int[2]")
    }

    @Test fun testAnnotations() {
        chkCompile("class user (log) {}", "OK")
        chkCompile("class user (foo) {}", "ct_err:class_ann_bad:foo")
        chkCompile("class user (log, log) {}", "ct_err:class_ann_dup:log")

        val m1 = tst.compileModuleEx("class user {}")
        val c1 = m1.classes["user"]!!
        assertEquals(false, c1.flags.log)

        val m2 = tst.compileModuleEx("class user (log) {}")
        val c2 = m2.classes["user"]!!
        assertEquals(true, c2.flags.log)
    }
}
