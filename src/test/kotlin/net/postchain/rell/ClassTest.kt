package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.RellCodeTester
import net.postchain.rell.test.SqlTestUtils
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
        chkOp("create foo(name = 'A');")
        chkOp("create bar(name = 'B');")
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

        chkOp("create A(name = 'A');")
        chkOp("create B(name = 'B');")
        chkOp("create C(name1 = 'C1', name2 = 'C2');")
        chkOp("create D(name = 'D1');")

        chk("A @ {} (.name)", "text[A]")
        chk("B @ {} (.name)", "text[B]")
        chk("C @ {} (=.name1,=.name2)", "(text[C1],text[C2])")
        chk("D @ {} (.name)", "text[D1]")

        chkOp("update D @ {} (name = 'D2');")
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
        chkOp("create foo(name = 'A');")
        chkOp("create bar(name = 'B');")
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

        chkOp("create A(name = 'A');")
        chkOp("create B(name = 'B');")
        chkOp("create C(name1 = 'C1', name2 = 'C2');")
        chkOp("create D(name = 'D1');")

        chk("A @ {} (.name)", "text[A]")
        chk("B @ {} (.name)", "text[B]")
        chk("C @ {} (=.name1,=.name2)", "(text[C1],text[C2])")
        chk("D @ {} (.name)", "text[D1]")

        chkOp("update D @ {} (name = 'D2');")
        chk("D @ {} (.name)", "text[D2]")
    }

    @Test fun testKeyIndexDupValue() {
        tst.useSql = true
        tst.defs = listOf("class foo { mutable k: text; mutable i: text; key k; index i; }")

        chkOp("create foo(k = 'K1', i = 'I1');")
        chkOp("create foo(k = 'K1', i = 'I2');", "rt_err:sqlerr:0")
        chkOp("create foo(k = 'K2', i = 'I1');")
        chkData("foo(1,K1,I1)", "foo(2,K2,I1)")

        chkOp("update foo @ { .k == 'K2' } ( k = 'K1' );", "rt_err:sqlerr:0")
        chkData("foo(1,K1,I1)", "foo(2,K2,I1)")
    }

    @Test fun testDeclarationOrder() {
        chkCompile("class user { c: company; } class company { name; }", "OK")
        chkCompile("query q() = user @* {}; class user { name; }", "OK")
    }

    @Test fun testForwardReferenceInAttributeValue() {
        tst.useSql = true
        tst.defs = listOf(
                "class foo { x: integer; k: integer = (bar@*{ .v > 0 }).size(); }",
                "class bar { v: integer; }"
        )

        chkOp("""
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

    @Test fun testBugSqlCreateTableOrder() {
        // Bug: SQL tables must be created in topological order because of foreign key constraints.
        tst.defs = listOf("class user { name: text; company; }", "class company { name: text; }")
        tst.useSql = true
        chkOp("val c = create company('Amazon'); create user ('Bob', c);")
        chkData("user(2,Bob,1)", "company(1,Amazon)")
        chk("company @* {} ( =.name )", "list<text>[text[Amazon]]")
        chk("user @* {} ( =.name, =.company )", "list<(text,company)>[(text[Bob],company[1])]")
    }

    @Test fun testCycle() {
        chkCompile("class foo { bar; } class bar { foo; }", "ct_err:class_cycle:foo,bar")
    }

    @Test fun testTablePrefix() {
        tst.useSql = true
        tst.chkData() // Does database reset, creates system tables

        val tst1 = createTablePrefixTester(123, 100, "Amazon", "Bob")
        val tst2 = createTablePrefixTester(456, 200, "Google", "Alice")

        tst1.chkData("user(101,Bob,100)", "company(100,Amazon)")
        tst1.chkQuery("company @* {}( =company, =.name )", "[(company[100],Amazon)]")
        tst1.chkQuery("user @* {}( =user, =.name, =.company.name )", "[(user[101],Bob,Amazon)]")

        tst2.chkData("user(201,Alice,200)", "company(200,Google)")
        tst2.chkQuery("company @* {}( =company, =.name )", "[(company[200],Google)]")
        tst2.chkQuery("user @* {}( =user, =.name, =.company.name )", "[(user[201],Alice,Google)]")

        tst1.chkOp("val c = create company('Facebook'); create user ('Trudy', c);")
        tst2.chkOp("val c = create company('Microsoft'); create user ('James', c);")

        tst1.chkData("user(2,Trudy,1)", "user(101,Bob,100)", "company(1,Facebook)", "company(100,Amazon)")
        tst1.chkQuery("company @* {}( =company, =.name )", "[(company[1],Facebook), (company[100],Amazon)]")
        tst1.chkQuery("user @* {}( =user, =.name, =.company.name )", "[(user[2],Trudy,Facebook), (user[101],Bob,Amazon)]")

        tst2.chkData("user(2,James,1)", "user(201,Alice,200)", "company(1,Microsoft)", "company(200,Google)")
        tst2.chkQuery("company @* {}( =company, =.name )", "[(company[1],Microsoft), (company[200],Google)]")
        tst2.chkQuery("user @* {}( =user, =.name, =.company.name )", "[(user[2],James,Microsoft), (user[201],Alice,Google)]")
    }

    private fun createTablePrefixTester(chainId: Long, rowid: Long, company: String, user: String): RellCodeTester {
        val t = resource(RellCodeTester())
        t.useSql = true
        t.defs = listOf("class user { name: text; company; }", "class company { name: text; }")
        t.chainId = chainId
        t.insert("c${chainId}_company", "name","${rowid},'$company'")
        t.insert("c${chainId}_user", "name,company","${rowid+1},'$user',${rowid}")
        t.dropTables = false
        t.createSystemTables = false
        t.strictToString = false
        return t
    }
}
