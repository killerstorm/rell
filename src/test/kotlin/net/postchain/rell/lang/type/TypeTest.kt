/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lang.type

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class TypeTest: BaseRellTest() {
    @Test fun testByteArrayLiteral() {
        chkEx("""= x"" ;""", "byte_array[]")
        chkEx("= x'' ;", "byte_array[]")

        chkEx("""= x"0123456789ABCDEF" ;""", "byte_array[0123456789abcdef]")
        chkEx("""= x"0123456789abcdef" ;""", "byte_array[0123456789abcdef]")
        chkEx("= x'0123456789abcdef' ;", "byte_array[0123456789abcdef]")

        chkEx("= x'0' ;", "ct_err:lex:bad_hex:0")
        chkEx("= x'F' ;", "ct_err:lex:bad_hex:F")
        chkEx("= x'abc' ;", "ct_err:lex:bad_hex:abc")

        chkEx("= x'0g' ;", "ct_err:lex:bad_hex:0g")
        chkEx("= x'x' ;", "ct_err:lex:bad_hex:x")
        chkEx("= x'0x' ;", "ct_err:lex:bad_hex:0x")
        chkEx("= x'12345Z' ;", "ct_err:lex:bad_hex:12345Z")
    }

    @Test fun testByteArraySql() {
        def("entity foo { mutable x: byte_array; }")

        chkOp("create foo(x'0123456789abcdef');")
        chkData("foo(1,0x0123456789abcdef)")

        chkOp("update foo @ {} ( x'fedcba9876543210' );")
        chkData("foo(1,0xfedcba9876543210)")
    }

    @Test fun testJsonValue() {
        chkEx("""= json('{ "a" : 5, "b" : [1,2,3], "c": { "x":10,"y":20 } }');""",
                """json[{"a":5,"b":[1,2,3],"c":{"x":10,"y":20}}]""")

        // Bad argument
        chkEx("= json();", "ct_err:expr_call_argtypes:json:")
        chkEx("= json(1234);", "ct_err:expr_call_argtypes:json:integer")
        chkEx("= json(json('{}'));", "ct_err:expr_call_argtypes:json:json")
        chkEx("= json('');", "rt_err:fn_json_badstr")
        chkEx("= json('{]');", "rt_err:fn_json_badstr")
    }

    @Test fun testJsonSql() {
        def("entity foo { mutable j: json; }")

        chkOp("""create foo(json('{ "a" : 5, "b" : [1,2,3], "c": { "x":10,"y":20 } }'));""")
        chkData("""foo(1,{"a": 5, "b": [1, 2, 3], "c": {"x": 10, "y": 20}})""")

        chkOp("""update foo @ {} (json('{ "a" : 999, "b": [5,4,3,2,1] }'));""")
        chkData("""foo(1,{"a": 999, "b": [5, 4, 3, 2, 1]})""")
    }

    @Test fun testTuple() {
        chkEx("{ var x: (integer, text); x = (123, 'Hello'); return x; }", "(int[123],text[Hello])");

        chkEx("{ var x: (a: integer, b: text); x = (123, 'Hello'); return 0; }",
                "ct_err:stmt_assign_type:[(a:integer,b:text)]:[(integer,text)]");

        chkEx("{ var x: (a: integer, b: text) = (a=1,b=''); var y: (integer, text) = (2,''); y = x; return 0; }",
                "ct_err:stmt_assign_type:[(integer,text)]:[(a:integer,b:text)]");

        chkEx("{ var x: (a: integer, b: text) = (a=1,b=''); var y: (p: integer, q: text) = (p=2,q=''); y = x; return 0; }",
                "ct_err:stmt_assign_type:[(p:integer,q:text)]:[(a:integer,b:text)]");
    }

    @Test fun testTupleSingleField() {
        chkEx("{ var x: (integer); return _type_of(x); }", "text[integer]")
        chkEx("{ var x: (integer)?; return _type_of(x); }", "text[integer?]")
        chkEx("{ var x: (integer,); return _type_of(x); }", "text[(integer,)]")
        chkEx("{ var x: (integer,)?; return _type_of(x); }", "text[(integer,)?]")
    }

    @Test fun testRange() {
        chkEx("{ var x: range; x = range(0,100); return x; }", "range[0,100,1]")
        chkEx("{ var x: range; x = 12345; return 0; }", "ct_err:stmt_assign_type:[range]:[integer]")
    }

    @Test fun testList() {
        chkEx("{ var x: list<integer>; x = [1, 2, 3]; return x; }", "list<integer>[int[1],int[2],int[3]]")
        chkEx("{ var x: list<integer>; x = ['Hello', 'World']; return 0; }",
                "ct_err:stmt_assign_type:[list<integer>]:[list<text>]")
        chkEx("{ var x: list<list<text>>; x = [['Hello', 'World']]; return x; }",
                "list<list<text>>[list<text>[text[Hello],text[World]]]")
        chkEx("{ var x: list<integer>; x = 123; return 0; }", "ct_err:stmt_assign_type:[list<integer>]:[integer]")
    }

    @Test fun testSet() {
        chkEx("{ var x: set<integer>; x = set([1, 2, 3]); return x; }", "set<integer>[int[1],int[2],int[3]]")
        chkEx("{ var x: set<integer>; x = [1, 2, 3]; return 0; }", "ct_err:stmt_assign_type:[set<integer>]:[list<integer>]")
        chkEx("{ var x: set<integer>; x = set(['Hello', 'World']); return 0; }",
                "ct_err:stmt_assign_type:[set<integer>]:[set<text>]")
        chkEx("{ var x: set<integer>; x = 123; return 0; }", "ct_err:stmt_assign_type:[set<integer>]:[integer]")
    }

    @Test fun testMap() {
        chkEx("{ var x: map<text,integer>; x = ['Bob':123]; return x; }", "map<text,integer>[text[Bob]=int[123]]")
        chkEx("{ var x: map<text,integer>; x = [1, 2, 3]; return 0; }",
                "ct_err:stmt_assign_type:[map<text,integer>]:[list<integer>]")
        chkEx("{ var x: map<text,integer>; x = set(['Hello', 'World']); return 0; }",
                "ct_err:stmt_assign_type:[map<text,integer>]:[set<text>]")
    }

    @Test fun testEntityAttributeTypeErr() {
        chkFull("entity foo { x: (integer,); } query q() = 0;", listOf(), "ct_err:entity_attr_type:x:(integer,)")
        chkFull("entity foo { x: (integer, text); } query q() = 0;", listOf(), "ct_err:entity_attr_type:x:(integer,text)")
        chkFull("entity foo { x: range; } query q() = 0;", listOf(), "ct_err:entity_attr_type:x:range")
        chkFull("entity foo { x: list<integer>; } query q() = 0;", listOf(), "ct_err:entity_attr_type:x:list<integer>")
    }

    @Test fun testRowid() {
        def("entity user { name; }")

        chkCompile("function f(x: integer): rowid = x;", "ct_err:fn_rettype:[rowid]:[integer]")
        chkCompile("function f(x: rowid): integer = x;", "ct_err:fn_rettype:[integer]:[rowid]")
        chkCompile("function f(x: rowid): rowid = x;", "OK")

        chkCompile("function f(x: user): rowid = x;", "ct_err:fn_rettype:[rowid]:[user]")
        chkCompile("function f(x: rowid): user = x;", "ct_err:fn_rettype:[user]:[rowid]")
        chkCompile("function f(x: user): rowid = x.rowid;", "OK")
        chkCompile("function f(x: rowid): user = user @ { .rowid == x };", "OK")
    }

    @Test fun testInferNameLocalVar() {
        def("struct foo { x: integer = 123; }")
        def("namespace ns { struct bar { y: integer = 456; } }")

        chkEx("{ val foo; return _type_of(foo); }", "text[foo]")
        chkEx("{ val foo?; return _type_of(foo); }", "text[foo?]")
        chkEx("{ val ns.bar; return _type_of(bar); }", "text[ns.bar]")
        chkEx("{ val ns.bar?; return _type_of(bar); }", "text[ns.bar?]")
        chkEx("{ val ns.bar?; bar = ns.bar(); return bar; }", "ns.bar[y=int[456]]")

        chkEx("{ val foo = 123; return foo; }", "int[123]")
        chkEx("{ val foo = ns.bar(); return foo; }", "ns.bar[y=int[456]]")

        chkEx("{ val foo: integer = 123; return foo; }", "int[123]")
        chkEx("{ val foo: ns.bar = ns.bar(); return foo; }", "ns.bar[y=int[456]]")
    }

    @Test fun testInferNameParameter() {
        def("struct foo { x: integer = 123; }")
        def("namespace ns { struct bar { y: integer = 456; } }")

        chkFull("function f(foo): text = _type_of(foo); query q() = f(foo());", "text[foo]")
        chkFull("function f(foo): foo = foo; query q() = f(foo());", "foo[x=int[123]]")
        chkFull("function f(foo?): text = _type_of(foo); query q() = f(foo());", "text[foo?]")
        chkFull("function f(foo?): foo? = foo; query q() = f(foo());", "foo[x=int[123]]")

        chkFull("function f(ns.bar): text = _type_of(bar); query q() = f(ns.bar());", "text[ns.bar]")
        chkFull("function f(ns.bar): ns.bar = bar; query q() = f(ns.bar());", "ns.bar[y=int[456]]")
        chkFull("function f(ns.bar?): text = _type_of(bar); query q() = f(ns.bar());", "text[ns.bar?]")
        chkFull("function f(ns.bar?): ns.bar? = bar; query q() = f(ns.bar());", "ns.bar[y=int[456]]")

        chkFull("function f(foo: integer): text = _type_of(foo); query q() = f(789);", "text[integer]")
        chkFull("function f(foo: integer): integer = foo; query q() = f(789);", "int[789]")
        chkFull("function f(foo: ns.bar): text = _type_of(foo); query q() = f(ns.bar());", "text[ns.bar]")
        chkFull("function f(foo: ns.bar): ns.bar = foo; query q() = f(ns.bar());", "ns.bar[y=int[456]]")
    }

    @Test fun testInferNameStructAttr() {
        def("struct foo { x: integer = 123; }")
        def("namespace ns { struct bar { y: integer = 456; } }")

        chkFull("struct rec { foo; } query q() = _type_of(rec(foo()).foo);", "text[foo]")
        chkFull("struct rec { foo; } query q() = rec(foo()).foo;", "foo[x=int[123]]")
        chkFull("struct rec { foo?; } query q() = _type_of(rec(foo()).foo);", "text[foo?]")
        chkFull("struct rec { foo?; } query q() = rec(foo()).foo;", "foo[x=int[123]]")

        chkFull("struct rec { ns.bar; } query q() = _type_of(rec(ns.bar()).bar);", "text[ns.bar]")
        chkFull("struct rec { ns.bar; } query q() = rec(ns.bar()).bar;", "ns.bar[y=int[456]]")
        chkFull("struct rec { ns.bar?; } query q() = _type_of(rec(ns.bar()).bar);", "text[ns.bar?]")
        chkFull("struct rec { ns.bar?; } query q() = rec(ns.bar()).bar;", "ns.bar[y=int[456]]")

        chkFull("struct rec { foo: integer; } query q() = _type_of(rec(789).foo);", "text[integer]")
        chkFull("struct rec { foo: integer; } query q() = rec(789).foo;", "int[789]")
    }

    @Test fun testInferNameEntityAttr() {
        def("entity foo { x: integer = 123; }")
        def("namespace ns { entity bar { y: integer = 456; } }")
        def("entity cls_foo { foo; }")
        def("entity cls_bar { ns.bar; }")
        def("entity mixed { index foo, ns.bar; }")

        chk("_type_of((cls_foo@{}).foo)", "text[foo]")
        chk("_type_of((cls_bar@{}).bar)", "text[ns.bar]")
        chk("_type_of((mixed@{}).foo)", "text[foo]")
        chk("_type_of((mixed@{}).bar)", "text[ns.bar]")

        chkCompile("entity bad { foo; index foo; }", "OK")
        chkCompile("entity bad { ns.bar; index ns.bar; }", "OK")
        chkCompile("entity bad { ns.bar; index bar; }", "OK")
    }

    @Test fun testUnitType() {
        chkCompile("function f(): unit {}", "OK")
        chkCompile("function f(): unit = print(123);", "OK")
        chkCompile("function f(): unit = 123;", "ct_err:fn_rettype:[unit]:[integer]")
        chkCompile("function f(): unit { print(123); }", "OK")
        chkCompile("function f(): unit { return print(123); }", "ct_err:stmt_return_unit")
        chkCompile("function f(): unit { return 123; }", "ct_err:fn_rettype:[unit]:[integer]")

        chkCompile("function f() { var x: unit; }", "ct_err:type:attr_var:unit:x")
        chkCompile("function f() { var (x: unit, y: unit); }", "ct_err:[type:attr_var:unit:x][type:attr_var:unit:y]")

        chkCompile("function f(x: unit) {}", "ct_err:type:attr_var:unit:x")
        chkCompile("function f(unit) {}", "ct_err:type:attr_var:unit:unit")
        chkCompile("query q(x: unit) = 0;", "ct_err:type:attr_var:unit:x")
        chkCompile("operation op(x: unit) {}", "ct_err:type:attr_var:unit:x")
        chkCompile("struct s { x: unit; }", "ct_err:type:attr_var:unit:x")
        chkCompile("struct s { unit; }", "ct_err:type:attr_var:unit:unit")
        chkCompile("object o { x: unit; }", "ct_err:[object_attr_novalue:o:x][type:attr_var:unit:x]")
        chkCompile("entity e { x: unit; }", "ct_err:type:attr_var:unit:x")
        chkCompile("entity e { unit; }", "ct_err:type:attr_var:unit:unit")
        chkCompile("entity e { key x: unit; }", "ct_err:type:attr_var:unit:x")
        chkCompile("entity e { index x: unit; }", "ct_err:type:attr_var:unit:x")
        chkCompile("entity e { key unit; }", "ct_err:type:attr_var:unit:unit")
        chkCompile("entity e { index unit; }", "ct_err:type:attr_var:unit:unit")

        chkCompile("function g(x: (unit) -> integer) {}", "ct_err:type:fntype_param:unit:?")
        chkCompile("function g(x: (integer,unit)) {}", "ct_err:type:tuple_field:unit:?")
        chkCompile("function g(x: (a:integer,b:unit)) {}", "ct_err:type:tuple_field:unit:b")
        chkCompile("function g(x: list<unit>) {}", "ct_err:type:list:elem:unit:?")
        chkCompile("function g(x: set<unit>) {}", "ct_err:type:set:elem:unit:?")
        chkCompile("function g(x: map<integer,unit>) {}", "ct_err:type:map_elem:unit:?")
        chkCompile("function g(x: map<unit,integer>) {}", "ct_err:type:map_elem:unit:?")
        chkCompile("function g(x: virtual<unit>) {}", "ct_err:type:virtual:bad_inner_type:unit")
    }

    @Test fun testUnitTypeImplicit() {
        chkCompile("struct data { unit; }", "ct_err:type:attr_var:unit:unit")
        chkCompile("struct data { unit = 123; }", "ct_err:type:attr_var:unit:unit")
        chkCompile("struct data { unit: integer; }", "OK")

        chkCompile("function f() { var unit; }", "ct_err:type:attr_var:unit:unit")
        chkCompile("function f() { var unit = 123; }", "OK")
        chkCompile("function f() { var unit: integer; }", "OK")

        chkCompile("function f(unit) {}", "ct_err:type:attr_var:unit:unit")
        chkCompile("function f(unit = 123) {}", "ct_err:type:attr_var:unit:unit")
        chkCompile("function f(unit: integer) {}", "OK")
    }
}
