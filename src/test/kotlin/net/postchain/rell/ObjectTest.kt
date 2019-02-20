package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.SqlTestUtils
import org.junit.Test

class ObjectTest: BaseRellTest() {
    @Test fun testInitialization() {
        tst.defs = listOf("object foo { n: integer = 123; s: text = 'Hello'; }")
        chkData("foo(0,123,Hello)")
    }

    @Test fun testRead() {
        tst.defs = listOf("object foo { n: integer = 123; s: text = 'Hello'; }")
        chk("foo.n", "int[123]")
        chk("foo.s", "text[Hello]")
    }

    @Test fun testReadUnderAt() {
        tst.defs = listOf("object foo { n: integer = 123; s: text = 'Hello'; }", "class user {name;}")
        chkOp("create user('Bob');")
        chk("user @{} ( foo.n )", "int[123]")
        chk("user @{} ( foo.s )", "text[Hello]")
    }

    @Test fun testAttributes() {
        chkCompile("object foo { x: integer; }", "ct_err:object_attr_novalue:foo:x")
        chkCompile("object foo { x: integer = 123; s: text; }", "ct_err:object_attr_novalue:foo:s")
        chkCompile("object foo { n = 123; }", "ct_err:unknown_name_type:n")

        tst.defs = listOf("object foo{}")
        chk("'' + foo", "text[foo]")
    }

    @Test fun testBadType() {
        chkCompile("object foo { x: list<integer> = list<integer>(); }", "ct_err:class_attr_type:x:list<integer>")
        chkCompile("object foo { x: set<integer> = set<integer>(); }", "ct_err:class_attr_type:x:set<integer>")
        chkCompile("object foo { x: map<integer,text> = map<integer, text>(); }", "ct_err:class_attr_type:x:map<integer,text>")
        chkCompile("object foo { x: (integer,text) = (123,'Hello'); }", "ct_err:class_attr_type:x:(integer,text)")
        chkCompile("object foo { x: integer? = 123; }", "ct_err:class_attr_type:x:integer?")
    }

    @Test fun testKeyIndex() {
        chkCompile("object foo { x: integer = 123; key x; }", "ct_err:object_keyindex:foo")
        chkCompile("object foo { x: integer = 123; index x; }", "ct_err:object_keyindex:foo")
    }

    @Test fun testUseAsType() {
        tst.defs = listOf("object foo { x: integer = 123; }")
        chkCompile("function g(f: foo){}", "ct_err:object_astype:foo")
        chkCompile("function g(): foo {}", "ct_err:object_astype:foo")
        chkCompile("function g() { var f: foo; }", "ct_err:object_astype:foo")
        chkCompile("class bar { f: foo; }", "ct_err:object_astype:foo")
        chkCompile("function g() { var l: list<foo>; }", "ct_err:object_astype:foo")
        chkCompile("function g() { var l: set<foo>; }", "ct_err:object_astype:foo")
        chkCompile("function g() { var l: map<integer, foo>; }", "ct_err:object_astype:foo")
        chkCompile("function g() { var l: map<foo, integer>; }", "ct_err:object_astype:foo")
        chkCompile("record bar { foo; }", "ct_err:object_astype:foo")
    }

    @Test fun testCreateDelete() {
        tst.defs = listOf("object foo { x: integer = 123; }")
        chkOp("create foo(x=123);", "ct_err:object_not_class:foo")
        chkOp("delete foo @* {};", "ct_err:object_not_class:foo")
        chkOp("delete foo;", "ct_err:stmt_delete_obj:foo")
    }

    @Test fun testComplexExpressions() {
        tst.defs = listOf(
                "object foo { p: integer = sq(5); }",
                "object bar { q: integer = sq(foo.p); }",
                "function sq(x: integer): integer = x * x;"
        )
        chk("foo.p", "int[25]")
        chk("bar.q", "int[625]")
    }

    @Test fun testObjectValue() {
        tst.defs = listOf("object foo { x: integer = 123; }")

        chk("'' + foo", "text[foo]")
        chk("_typeOf(foo)", "text[foo]")
        chk("abs(foo)", "ct_err:expr_call_argtypes:abs:foo")
        chk("foo", "foo")

        chk("foo == foo", "ct_err:binop_operand_type:==:foo:foo")
        chk("foo != foo", "ct_err:binop_operand_type:!=:foo:foo")
        chk("foo === foo", "ct_err:binop_operand_type:===:foo:foo")
        chk("foo !== foo", "ct_err:binop_operand_type:!==:foo:foo")
        chk("foo < foo", "ct_err:binop_operand_type:<:foo:foo")
        chk("foo <= foo", "ct_err:binop_operand_type:<=:foo:foo")
        chk("foo + foo", "ct_err:binop_operand_type:+:foo:foo")
        chk("not foo", "ct_err:unop_operand_type:not:foo")

        chkEx("{ foo = 123; return 456; }", "ct_err:expr_bad_dst")
        chkEx("{ foo += 123; return 456; }", "ct_err:expr_bad_dst")

        chk("foo?.x", "ct_err:expr_safemem_type:foo")
    }

    @Test fun testGtx() {
        tst.defs = listOf("object foo { x: integer = 123; }")
        tst.gtx = true
        chk("foo", "ct_err:result_nogtx:q:foo")
        chkQueryEx("query q() { return foo; }", "ct_err:result_nogtx:q:foo")
    }

    @Test fun testForwardReference() {
        tst.defs = listOf(
                "function f(): integer = foo.x;",
                "query q() = foo.x;",
                "object foo { x: integer = 123; }"
        )
        chkQueryEx("", "int[123]")
        chkFnEx("", "int[123]")
    }

    @Test fun testForwardReferenceErr() {
        chkCompile("object foo { x: integer = 123; y: integer = x; }", "ct_err:unknown_name:x")
        chkCompile("object foo { x: integer = 123; y: integer = foo.x; }", "ct_err:object_fwdref:foo")
        chkCompile("object foo { x: integer = bar.y; } object bar { y: integer = 123; }", "ct_err:object_fwdref:bar")
    }

    @Test fun testForwardReferenceRt() {
        tst.defs = listOf(
                "object foo { x: integer = g(); }",
                "object bar { y: integer = 123; }",
                "function g(): integer = bar.y;"
        )
        tst.autoInitObjects = false
        tst.chkInitObjects("rt_err:obj_norec:bar")
    }

    @Test fun testUpdate() {
        tst.defs = listOf("object foo { mutable x: integer = 123; mutable s: text = 'Hello'; c: integer = 999; }")

        chkData("foo(0,123,Hello,999)")
        chk("(foo.x,foo.s,foo.c)", "(int[123],text[Hello],int[999])")

        chkOp("update foo (x += 456, s += 'World'); print((foo.x,foo.s,foo.c));")
        chkData("foo(0,579,HelloWorld,999)")
        chk("(foo.x,foo.s,foo.c)", "(int[579],text[HelloWorld],int[999])")

        chkOp("update foo (c = 111);", "ct_err:update_attr_not_mutable:c")
        chkData("foo(0,579,HelloWorld,999)")
        chk("(foo.x,foo.s,foo.c)", "(int[579],text[HelloWorld],int[999])")

        chkOp("update foo ( 555, 'Bye' );")
        chkData("foo(0,555,Bye,999)")
        chk("(foo.x,foo.s,foo.c)", "(int[555],text[Bye],int[999])")

        chkOp("update foo ( 33 );")
        chkData("foo(0,33,Bye,999)")
        chk("(foo.x,foo.s,foo.c)", "(int[33],text[Bye],int[999])")

        chkOp("update foo ( 'Tschuss' );")
        chkData("foo(0,33,Tschuss,999)")
        chk("(foo.x,foo.s,foo.c)", "(int[33],text[Tschuss],int[999])")
    }

    @Test fun testUpdateMemory() {
        tst.defs = listOf("object foo { mutable x: integer = 123; }")

        chkData("foo(0,123)")

        chkOp("""
            print(foo.x);
            update foo (x = 456);
            print(foo.x);
        """.trimIndent())

        chkData("foo(0,456)")
        chk("foo.x", "int[456]")
        chkStdout("123", "456")
    }

    @Test fun testInitSideEffects() {
        tst.defs = listOf(
                "class journal { n: integer; s: text; }",
                "object a { x: integer = f('a', 123); }",
                "object b { x: integer = f('b', 456); }",
                "object c { x: integer = f('c', 789); }",
                """
                    function f(s: text, v: integer): integer {
                        val n = (journal @* {}).size();
                        create journal ( n, s );
                        return v;
                    }
                """.trimIndent()
        )

        chkData("journal(1,0,a)", "journal(2,1,b)", "journal(3,2,c)", "a(0,123)", "b(0,456)", "c(0,789)")
    }

    @Test fun testNameResolution() {
        tst.defs = listOf("object foo { x: integer = 123; }", "class user { name; }")
        tst.insert("c0_user", "name", "1,'Bob'")

        // Object vs. local: error.
        chkEx("{ val foo = (s = 'Hello'); return foo.s; }", "ct_err:expr_name_locglob:foo")
        chkEx("{ val foo = (s = 'Hello'); return foo.x; }", "ct_err:expr_name_locglob:foo")
        chkEx("{ val foo = (x = 456); return foo.x; }", "ct_err:expr_name_locglob:foo")

        // Object vs. alias: error.
        chk("(foo: user) @* {}", "list<user>[user[1]]")
        chk("(foo: user) @* { foo.name == 'Bob' }", "ct_err:expr_name_clsglob:foo")
    }

    @Test fun testMultipleRecords() {
        tst.defs = listOf("object foo { x: integer = 123; }")
        tst.insert("c0_foo", "x", "1,123")
        tst.insert("c0_foo", "x", "2,456")
        tst.insert("c0_foo", "x", "3,789")
        tst.autoInitObjects = false

        chk("foo.x", "rt_err:obj_multirec:foo:3")
    }

    @Test fun testUpdateShortSyntax() {
        tst.defs = listOf("object foo { mutable x: integer = 100; y: integer = 250; }")
        chkData("foo(0,100,250)")

        chkOp("foo.x = 50;")
        chkData("foo(0,50,250)")

        chkOp("foo.x += 33;")
        chkData("foo(0,83,250)")

        chkOp("foo.x *= 55;")
        chkData("foo(0,4565,250)")

        chkOp("foo.y = 10;", "ct_err:update_attr_not_mutable:y")
        chkData("foo(0,4565,250)")

        chkOp("foo.y += 10;", "ct_err:update_attr_not_mutable:y")
        chkData("foo(0,4565,250)")
    }
}
