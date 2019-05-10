package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class EnumTest: BaseRellTest() {
    @Test fun testBasics() {
        tst.defs = listOf("enum foo { A, B, C }")
        chk("foo.A", "foo[A]")
        chk("foo.B", "foo[B]")
        chk("foo.C", "foo[C]")
        chk("foo.X", "ct_err:unknown_name:foo.X")

        chk("foo", "ct_err:expr_novalue:enum")
        chk("'' + foo", "ct_err:expr_novalue:enum")
        chk("_type_of(foo.A)", "text[foo]")
        chk("_type_of(foo)", "ct_err:expr_novalue:enum")
    }

    @Test fun testOperators() {
        tst.defs = listOf("enum foo { A, B, C }")
        chk("foo.A == foo.A", "boolean[true]")
        chk("foo.A == foo.B", "boolean[false]")
        chk("foo.A != foo.A", "boolean[false]")
        chk("foo.A != foo.B", "boolean[true]")
        chk("foo.A === foo.A", "ct_err:binop_operand_type:===:foo:foo")
        chk("foo.A !== foo.A", "ct_err:binop_operand_type:!==:foo:foo")
        chk("foo.A < foo.A", "boolean[false]")
        chk("foo.A < foo.B", "boolean[true]")
        chk("foo.A > foo.A", "boolean[false]")
        chk("foo.A > foo.B", "boolean[false]")
        chk("foo.B > foo.A", "boolean[true]")
        chk("foo.A + foo.A", "ct_err:binop_operand_type:+:foo:foo")
        chk("foo.A - foo.B", "ct_err:binop_operand_type:-:foo:foo")
        chk("foo.A + 1", "ct_err:binop_operand_type:+:foo:integer")
        chk("foo.A - 1", "ct_err:binop_operand_type:-:foo:integer")
        chk("foo.A + ''", "text[A]")
        chk("'' + foo.A", "text[A]")
    }

    @Test fun testMisc() {
        chkCompile("enum foo { A, B, C, }", "OK")
        chkCompile("enum foo {}", "OK")
        chkCompile("enum foo { A, B, C, A }", "ct_err:enum_dup:A")
        chkCompile("enum foo {} enum foo {}", "ct_err:name_conflict:enum:foo")
        chkCompile("object foo {} enum foo {}", "ct_err:name_conflict:object:foo")
        chkCompile("class foo {} enum foo {}", "ct_err:name_conflict:class:foo")
        chkCompile("record foo {} enum foo {}", "ct_err:name_conflict:record:foo")
        chkCompile("enum foo {} object foo {}", "ct_err:name_conflict:enum:foo")
        chkCompile("enum foo {} class foo {}", "ct_err:name_conflict:enum:foo")
        chkCompile("enum foo {} record foo {}", "ct_err:name_conflict:enum:foo")
    }

    @Test fun testTypeCompatibility() {
        tst.defs = listOf("enum foo { A, B, C }", "enum bar { A, B, C }")
        chkEx(": foo = bar.A;", "ct_err:entity_rettype:foo:bar")
        chkEx(": bar = foo.A;", "ct_err:entity_rettype:bar:foo")
        chkEx(": foo = null;", "ct_err:entity_rettype:foo:null")
        chkEx(": foo = 0;", "ct_err:entity_rettype:foo:integer")
        chkEx(": foo = 1;", "ct_err:entity_rettype:foo:integer")
        chkEx(": foo = 'Hello';", "ct_err:entity_rettype:foo:text")
        chkEx(": foo = true;", "ct_err:entity_rettype:foo:boolean")
        chkEx(": foo? = foo.A;", "foo[A]")
        chkEx(": foo? = null;", "null")
    }

    @Test fun testClassAttribute() {
        tst.defs = listOf(
                "enum foo { A, B, C }",
                "class cls { name; f: foo; }",
                "object obj { mutable f: foo = foo.A; }"
        )
        tst.insert("c0.cls", "name,f", "0,'Bob',0")

        chk("cls @* {} ( =.name, =.f )", "list<(text,foo)>[(text[Bob],foo[A])]")
        chkData("cls(0,Bob,0)", "obj(0,0)")

        chkOp("create cls('Alice', foo.B);")
        chk("cls @* {} ( =.name, =.f )", "list<(text,foo)>[(text[Bob],foo[A]),(text[Alice],foo[B])]")
        chkData("cls(0,Bob,0)", "cls(1,Alice,1)", "obj(0,0)")

        chk("obj.f", "foo[A]")
        chkOp("update obj (f = foo.C);")
        chk("obj.f", "foo[C]")
        chkData("cls(0,Bob,0)", "cls(1,Alice,1)", "obj(0,2)")

        chk("cls @? { .f == foo.A }", "cls[0]")
        chk("cls @? { .f == foo.B }", "cls[1]")
        chk("cls @? { .f == foo.C }", "null")
    }

    @Test fun testNameConflicts() {
        tst.defs = listOf("enum foo { A, B, C }", "class user { name: text; f: foo; }")
        tst.insert("c0.user", "name,f", "0,'Bob',1")

        chkEx("{ return user @ {} ( =.f ); }", "foo[B]")
        chkEx("{ val foo = 'Bob'; return user @? { .name == foo }; }", "user[0]")
        chkEx("{ val foo = 'Bob'; return user @? { .f == foo.B }; }", "ct_err:unknown_member:text:B")
        chkEx("{ val foo = 'Bob'; return foo; }", "text[Bob]")
        chkEx("{ val foo = 'Bob'; return foo.C; }", "ct_err:unknown_member:text:C")
    }

    @Test fun testStaticFunctions() {
        tst.defs = listOf("enum foo { A, B, C }")

        chk("foo.values()", "list<foo>[foo[A],foo[B],foo[C]]")

        chk("foo.value(0)", "foo[A]")
        chk("foo.value(1)", "foo[B]")
        chk("foo.value(2)", "foo[C]")
        chk("foo.value(3)", "rt_err:enum_badvalue:foo:3")

        chk("foo.value('A')", "foo[A]")
        chk("foo.value('B')", "foo[B]")
        chk("foo.value('C')", "foo[C]")
        chk("foo.value('D')", "rt_err:enum_badname:foo:D")

        chk("foo.value(true)", "ct_err:expr_call_argtypes:value:boolean")
        chk("foo.value(null)", "ct_err:expr_call_argtypes:value:null")
        chk("foo.value(foo.A)", "ct_err:expr_call_argtypes:value:foo")
    }

    @Test fun testMemberFunctions() {
        tst.defs = listOf("enum foo { A, B, C }")

        chk("foo.A.name", "text[A]")
        chk("foo.B.name", "text[B]")
        chk("foo.C.name", "text[C]")

        chk("foo.A.value", "int[0]")
        chk("foo.B.value", "int[1]")
        chk("foo.C.value", "int[2]")
    }

    @Test fun testNullable() {
        tst.defs = listOf("enum foo { A, B, C }", "function nop(x: foo?): foo? = x;")

        chkEx("{ val f: foo = foo.A; return f.name; }", "text[A]")
        chkEx("{ val f: foo = foo.A; return f?.name; }", "ct_err:expr_safemem_type:foo")
        chkEx("{ val f: foo = foo.A; return f!!.name; }", "ct_err:unop_operand_type:!!:foo")
        chkEx("{ val f: foo = foo.A; return f ?: foo.C; }", "ct_err:binop_operand_type:?::foo:foo")

        chkEx("{ val f: foo? = nop(foo.A); return f.name; }", "ct_err:expr_mem_null:name")
        chkEx("{ val f: foo? = nop(foo.A); return f?.name; }", "text[A]")
        chkEx("{ val f: foo? = nop(foo.A); return f!!.name; }", "text[A]")
        chkEx("{ val f: foo? = nop(foo.A); return f ?: foo.C; }", "foo[A]")

        chkEx("{ val f: foo? = nop(null); return f?.name; }", "null")
        chkEx("{ val f: foo? = nop(null); return f!!.name; }", "rt_err:null_value")
        chkEx("{ val f: foo? = nop(null); return f ?: foo.C; }", "foo[C]")
    }
}
