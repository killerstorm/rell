package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.RellCodeTester
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
        tstCtx.useSql = true
        def("class foo { index name; }")
        def("class bar { index name: text; }")
        chkOp("create foo(name = 'A');")
        chkOp("create bar(name = 'B');")
        chk("foo @ {} (.name)", "text[A]")
        chk("bar @ {} (.name)", "text[B]")
    }

    @Test fun testIndexWithAttr() {
        tstCtx.useSql = true
        def("class A { name; index name; }")
        def("class B { index name; name: text; }")
        def("class C { name1: text; index name1, name2: text; }")
        def("class D { mutable name: text; index name; }")

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
        tstCtx.useSql = true
        def("class foo { key name; }")
        def("class bar { key name: text; }")
        chkOp("create foo(name = 'A');")
        chkOp("create bar(name = 'B');")
        chk("foo @ {} (.name)", "text[A]")
        chk("bar @ {} (.name)", "text[B]")
    }

    @Test fun testKeyWithAttr() {
        tstCtx.useSql = true
        def("class A { name; key name; }")
        def("class B { key name; name: text; }")
        def("class C { name1: text; key name1, name2: text; }")
        def("class D { mutable name: text; key name; }")

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
        tstCtx.useSql = true
        def("class foo { mutable k: text; mutable i: text; key k; index i; }")

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
        tstCtx.useSql = true
        def("class foo { x: integer; k: integer = (bar@*{ .v > 0 }).size(); }")
        def("class bar { v: integer; }")

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
        def("class user { name: text; company; }")
        def("class company { name: text; }")
        tstCtx.useSql = true
        chkOp("val c = create company('Amazon'); create user ('Bob', c);")
        chkData("user(2,Bob,1)", "company(1,Amazon)")
        chk("company @* {} ( =.name )", "list<text>[text[Amazon]]")
        chk("user @* {} ( =.name, =.company )", "list<(text,company)>[(text[Bob],company[1])]")
    }

    @Test fun testCycle() {
        chkCompile("class foo { bar; } class bar { foo; }", "ct_err:class_cycle:foo,bar")
    }

    @Test fun testTablePrefix() {
        tstCtx.useSql = true
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

    @Test fun testRowidAttr() {
        tstCtx.useSql = false

        chkCompile("class foo { rowid: integer; }", "ct_err:unallowed_attr_name:rowid")
        chkCompile("class foo { rowid: text; }", "ct_err:unallowed_attr_name:rowid")
        chkCompile("class foo { index rowid: integer; }", "ct_err:unallowed_attr_name:rowid")
        chkCompile("class foo { key rowid: integer; }", "ct_err:unallowed_attr_name:rowid")

        chkCompile("object foo { rowid: integer; }", "ct_err:unallowed_attr_name:rowid")
        chkCompile("object foo { rowid: text; }", "ct_err:unallowed_attr_name:rowid")

        chkCompile("record foo { rowid: integer; }", "OK")
        chkCompile("record foo { rowid: text; }", "OK")
    }

    @Test fun testClassRowidAttr() {
        initClassRowidAttr()

        chkEx("{ return _type_of((user @ { 'Bob' }).rowid); }", "text[rowid]")
        chkEx("{ val u = user @ { 'Bob' }; return _type_of(u.rowid); }", "text[rowid]")

        chkEx("{ val u = user @ { 'Bob' }; return u.rowid; }", "rowid[100]")
        chkEx("{ val u = user @ { 'Alice' }; return u.rowid; }", "rowid[200]")
        chkEx("{ val c = company @ { 'BobCorp' }; return c.boss.rowid; }", "rowid[100]")
        chkEx("{ val c = company @ { 'AliceCorp' }; return c.boss.rowid; }", "rowid[200]")

        chkEx("{ val u = user @? { 'Bob' }; return u.rowid; }", "ct_err:expr_mem_null:rowid")
        chkEx("{ val u = user @? { 'Bob' }; return u?.rowid; }", "rowid[100]")
        chkEx("{ val u = user @? { 'Alice' }; return u?.rowid; }", "rowid[200]")
        chkEx("{ val u = user @? { 'Trudy' }; return u?.rowid; }", "null")

        chkEx("{ val u = user @ { 'Bob' }; u.rowid = 999; return 0; }", "ct_err:expr_bad_dst:rowid")
    }

    @Test fun testClassRowidAttrAt() {
        initClassRowidAttr()

        chk("user @ { 'Bob' } ( _type_of(.rowid) )", "text[rowid]")
        chk("company @ { 'BobCorp' } ( _type_of(.boss.rowid) )", "text[rowid]")

        chk("user @ { 'Bob' } ( .rowid )", "rowid[100]")
        chk("user @ { 'Alice' } ( .rowid )", "rowid[200]")
        chk("user @ { 'Bob' } ( user.rowid )", "rowid[100]")
        chk("user @ { 'Alice' } ( user.rowid )", "rowid[200]")
        chk("company @ { 'BobCorp' } ( .boss.rowid )", "rowid[100]")
        chk("company @ { 'AliceCorp' } ( .boss.rowid )", "rowid[200]")

        chk("user @ { .rowid == to_rowid(100) } ( .name )", "text[Bob]")
        chk("user @ { .rowid == to_rowid(200) } ( .name )", "text[Alice]")
        chk("company @ { .boss.rowid == to_rowid(100) } ( .name )", "text[BobCorp]")
        chk("company @ { .boss.rowid == to_rowid(200) } ( .name )", "text[AliceCorp]")

        chk("user @ { 100 }", "ct_err:at_where_type:0:integer")
        chkEx("{ val x = 100; return user @ { x }; }", "ct_err:at_where:var_noattrs:0:x:integer")
        chkEx("{ val x = to_rowid(100); return user @ { x }; }", "user[100]")
        chkEx("{ val rowid = to_rowid(100); return user @ { rowid }; }", "user[100]")
        chkEx("{ val rowid = 100; return user @ { rowid }; }", "ct_err:at_where:var_noattrs:0:rowid:integer")
        chkEx("{ val rowid = 'Alice'; return user @ { rowid }; }", "ct_err:at_where:var_noattrs:0:rowid:text")
        chkEx("{ val x = 'Alice'; return user @ { x }; }", "user[200]")
    }

    private fun initClassRowidAttr() {
        tstCtx.useSql = true
        def("class user { name; }")
        def("class company { name; boss: user; }")
        def("function to_rowid(i: integer): rowid = rowid.from_gtv(i.to_gtv());")
        insert("c0.user", "name", "100,'Bob'")
        insert("c0.user", "name", "200,'Alice'")
        insert("c0.company", "name,boss", "300,'BobCorp',100")
        insert("c0.company", "name,boss", "400,'AliceCorp',200")
    }

    @Test fun testObjectRowidAttr() {
        tstCtx.useSql = true
        def("object state { mutable value: text = 'Unknown'; }")
        chk("state.rowid", "ct_err:unknown_name:state.rowid")
    }

    private fun createTablePrefixTester(chainId: Long, rowid: Long, company: String, user: String): RellCodeTester {
        val t = RellCodeTester(tstCtx)
        t.def("class user { name: text; company; }")
        t.def("class company { name: text; }")
        t.chainId = chainId
        t.insert("c${chainId}.company", "name","${rowid},'$company'")
        t.insert("c${chainId}.user", "name,company","${rowid+1},'$user',${rowid}")
        t.dropTables = false
        t.strictToString = false
        return t
    }
}
