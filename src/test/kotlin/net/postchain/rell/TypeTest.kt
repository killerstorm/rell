package net.postchain.rell

import org.junit.Test

class TypeTest: BaseRellTest() {
    @Test fun testByteArrayLiteral() {
        chkEx("""= x"" ;""", "byte_array[]")
        chkEx("= x'' ;", "byte_array[]")

        chkEx("""= x"0123456789ABCDEF" ;""", "byte_array[0123456789abcdef]")
        chkEx("""= x"0123456789abcdef" ;""", "byte_array[0123456789abcdef]")
        chkEx("= x'0123456789abcdef' ;", "byte_array[0123456789abcdef]")

        chkEx("= x'0' ;", "ct_err:parser_bad_hex:0")
        chkEx("= x'F' ;", "ct_err:parser_bad_hex:F")
        chkEx("= x'abc' ;", "ct_err:parser_bad_hex:abc")

        chkEx("= x'0g' ;", "ct_err:parser_bad_hex:0g")
        chkEx("= x'x' ;", "ct_err:parser_bad_hex:x")
        chkEx("= x'0x' ;", "ct_err:parser_bad_hex:0x")
        chkEx("= x'12345Z' ;", "ct_err:parser_bad_hex:12345Z")
    }

    @Test fun testByteArraySql() {
        tst.defs = listOf("class foo { mutable x: byte_array; }")

        execOp("create foo(x'0123456789abcdef');")
        chkData("foo(1,0x0123456789abcdef)")

        execOp("update foo @ {} ( x'fedcba9876543210' );")
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
        tst.defs = listOf("class foo { mutable j: json; }")

        execOp("""create foo(json('{ "a" : 5, "b" : [1,2,3], "c": { "x":10,"y":20 } }'));""")
        chkData("""foo(1,{"a": 5, "b": [1, 2, 3], "c": {"x": 10, "y": 20}})""")

        execOp("""update foo @ {} (json('{ "a" : 999, "b": [5,4,3,2,1] }'));""")
        chkData("""foo(1,{"a": 999, "b": [5, 4, 3, 2, 1]})""")
    }

    @Test fun testExplicitUnitType() {
        tst.chkQueryEx("class foo { x: unit; } query q() = 0;", listOf(), "ct_err:unknown_type:unit")
        chkEx("{ var x: unit; return 123; }", "ct_err:unknown_type:unit")
    }

    @Test fun testTuple() {
        chkEx("{ var x: (integer, text); x = (123, 'Hello'); return x; }", "(int[123],text[Hello])");

        chkEx("{ var x: (a: integer, b: text); x = (123, 'Hello'); return x; }",
                "ct_err:stmt_assign_type:(a:integer,b:text):(integer,text)");

        chkEx("{ var x: (a: integer, b: text); var y: (integer, text); y = x; return y; }",
                "ct_err:stmt_assign_type:(integer,text):(a:integer,b:text)");

        chkEx("{ var x: (a: integer, b: text); var y: (p: integer, q: text); y = x; return y; }",
                "ct_err:stmt_assign_type:(p:integer,q:text):(a:integer,b:text)");
    }

    @Test fun testRange() {
        chkEx("{ var x: range; x = range(0,100); return x; }", "range[0,100,1]")
        chkEx("{ var x: range; x = 12345; return x; }", "ct_err:stmt_assign_type:range:integer")
    }

    @Test fun testList() {
        chkEx("{ var x: list<integer>; x = [1, 2, 3]; return x; }", "list<integer>[int[1],int[2],int[3]]")
        chkEx("{ var x: list<integer>; x = ['Hello', 'World']; return x; }",
                "ct_err:stmt_assign_type:list<integer>:list<text>")
        chkEx("{ var x: list<list<text>>; x = [['Hello', 'World']]; return x; }",
                "list<list<text>>[list<text>[text[Hello],text[World]]]")
        chkEx("{ var x: list<integer>; x = 123; return x; }", "ct_err:stmt_assign_type:list<integer>:integer")
    }

    @Test fun testSet() {
        chkEx("{ var x: set<integer>; x = set([1, 2, 3]); return x; }", "set<integer>[int[1],int[2],int[3]]")
        chkEx("{ var x: set<integer>; x = [1, 2, 3]; return x; }",
                "ct_err:stmt_assign_type:set<integer>:list<integer>")
        chkEx("{ var x: set<integer>; x = set(['Hello', 'World']); return x; }",
                "ct_err:stmt_assign_type:set<integer>:set<text>")
        chkEx("{ var x: set<integer>; x = 123; return x; }", "ct_err:stmt_assign_type:set<integer>:integer")
    }

    @Test fun testMap() {
        chkEx("{ var x: map<text,integer>; x = ['Bob':123]; return x; }", "map<text,integer>[text[Bob]=int[123]]")
        chkEx("{ var x: map<text,integer>; x = [1, 2, 3]; return x; }",
                "ct_err:stmt_assign_type:map<text,integer>:list<integer>")
        chkEx("{ var x: map<text,integer>; x = set(['Hello', 'World']); return x; }",
                "ct_err:stmt_assign_type:map<text,integer>:set<text>")
    }

    @Test fun testClassAttributeTypeErr() {
        tst.chkQueryEx("class foo { x: (integer); } query q() = 0;", listOf(),
                "ct_err:class_attr_type:x:(integer)")

        tst.chkQueryEx("class foo { x: (integer, text); } query q() = 0;", listOf(),
                "ct_err:class_attr_type:x:(integer,text)")

        tst.chkQueryEx("class foo { x: range; } query q() = 0;", listOf(),
                "ct_err:class_attr_type:x:range")

        tst.chkQueryEx("class foo { x: list<integer>; } query q() = 0;", listOf(),
                "ct_err:class_attr_type:x:list<integer>")
    }
}
