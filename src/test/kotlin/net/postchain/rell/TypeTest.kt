package net.postchain.rell

import com.google.common.collect.Iterators
import com.google.common.io.Resources
import org.apache.commons.configuration2.PropertiesConfiguration
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder
import org.apache.commons.configuration2.builder.fluent.Parameters
import org.apache.commons.configuration2.io.ClasspathLocationStrategy
import org.junit.After
import org.junit.Test
import java.io.File

class TypeTest {
    private val tst = RellSqlTester()

    @After fun after() {
        tst.destroy()
    }

    @Test fun testByteArrayLiteral() {
        chkQuery("""= x"" ;""", "byte_array[]")
        chkQuery("= x'' ;", "byte_array[]")

        chkQuery("""= x"0123456789ABCDEF" ;""", "byte_array[0123456789abcdef]")
        chkQuery("""= x"0123456789abcdef" ;""", "byte_array[0123456789abcdef]")
        chkQuery("= x'0123456789abcdef' ;", "byte_array[0123456789abcdef]")

        chkQuery("= x'0' ;", "ct_err:parser_bad_hex:0")
        chkQuery("= x'F' ;", "ct_err:parser_bad_hex:F")
        chkQuery("= x'abc' ;", "ct_err:parser_bad_hex:abc")

        chkQuery("= x'0g' ;", "ct_err:parser_bad_hex:0g")
        chkQuery("= x'x' ;", "ct_err:parser_bad_hex:x")
        chkQuery("= x'0x' ;", "ct_err:parser_bad_hex:0x")
        chkQuery("= x'12345Z' ;", "ct_err:parser_bad_hex:12345Z")
    }

    @Test fun testByteArraySql() {
        tst.classDefs = listOf("class foo { mutable x: byte_array; }")

        exec("create foo(x'0123456789abcdef');")
        chkData("foo(1,0x0123456789abcdef)")

        exec("update foo @ {} ( x'fedcba9876543210' );")
        chkData("foo(1,0xfedcba9876543210)")
    }

    @Test fun testJsonValue() {
        chkQuery("""= json('{ "a" : 5, "b" : [1,2,3], "c": { "x":10,"y":20 } }');""",
                """json[{"a":5,"b":[1,2,3],"c":{"x":10,"y":20}}]""")

        // Bad argument
        chkQuery("= json();", "ct_err:expr_call_argtypes:json:")
        chkQuery("= json(1234);", "ct_err:expr_call_argtypes:json:integer")
        chkQuery("= json(json('{}'));", "ct_err:expr_call_argtypes:json:json")
        chkQuery("= json('');", "rt_err:fn_json_badstr")
        chkQuery("= json('{]');", "rt_err:fn_json_badstr")
    }

    @Test fun testJsonSql() {
        tst.classDefs = listOf("class foo { mutable j: json; }")

        exec("""create foo(json('{ "a" : 5, "b" : [1,2,3], "c": { "x":10,"y":20 } }'));""")
        chkData("""foo(1,{"a": 5, "b": [1, 2, 3], "c": {"x": 10, "y": 20}})""")

        exec("""update foo @ {} (json('{ "a" : 999, "b": [5,4,3,2,1] }'));""")
        chkData("""foo(1,{"a": 999, "b": [5, 4, 3, 2, 1]})""")
    }

    @Test fun testExplicitUnitType() {
        tst.chkQueryEx("class foo { x: unit; } query q() = 0;", listOf(), "ct_err:unknown_type:unit")
        chkQuery("{ var x: unit; return 123; }", "ct_err:unknown_type:unit")
    }

    @Test fun testTuple() {
        chkQuery("{ var x: (integer, text); x = (123, 'Hello'); return x; }", "(int[123],text[Hello])");
        chkQuery("{ var x: (a: integer, b: text); x = (123, 'Hello'); return x; }", "(a:int[123],b:text[Hello])");

        chkQuery("{ var x: (a: integer, b: text); val y: (x: integer, y: text) = (123, 'Hello'); x = y; return x; }",
                "(a:int[123],b:text[Hello])");
    }

    @Test fun testRange() {
        chkQuery("{ var x: range; x = range(0,100); return x; }", "range[0,100,1]")
        chkQuery("{ var x: range; x = 12345; return x; }", "ct_err:stmt_assign_type:range:integer")
    }

    @Test fun testList() {
        chkQuery("{ var x: list<integer>; x = [1, 2, 3]; return x; }", "list<integer>[int[1],int[2],int[3]]")
        chkQuery("{ var x: list<integer>; x = ['Hello', 'World']; return x; }",
                "ct_err:stmt_assign_type:list<integer>:list<text>")
        chkQuery("{ var x: list<list<text>>; x = [['Hello', 'World']]; return x; }",
                "list<list<text>>[list<text>[text[Hello],text[World]]]")
        chkQuery("{ var x: list<integer>; x = 123; return x; }", "ct_err:stmt_assign_type:list<integer>:integer")
    }

    @Test fun testSet() {
        chkQuery("{ var x: set<integer>; x = set([1, 2, 3]); return x; }", "set<integer>[int[1],int[2],int[3]]")
        chkQuery("{ var x: set<integer>; x = [1, 2, 3]; return x; }",
                "ct_err:stmt_assign_type:set<integer>:list<integer>")
        chkQuery("{ var x: set<integer>; x = set(['Hello', 'World']); return x; }",
                "ct_err:stmt_assign_type:set<integer>:set<text>")
        chkQuery("{ var x: set<list<text>>; x = set([['Hello', 'World']]); return x; }",
                "set<list<text>>[list<text>[text[Hello],text[World]]]")
        chkQuery("{ var x: set<integer>; x = 123; return x; }", "ct_err:stmt_assign_type:set<integer>:integer")
    }

    @Test fun testMap() {
        chkQuery("{ var x: map<text,integer>; x = ['Bob':123]; return x; }", "map<text,integer>[text[Bob]=int[123]]")
        chkQuery("{ var x: map<text,integer>; x = [1, 2, 3]; return x; }",
                "ct_err:stmt_assign_type:map<text,integer>:list<integer>")
        chkQuery("{ var x: map<text,integer>; x = set(['Hello', 'World']); return x; }",
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

    private fun exec(code: String) = tst.chkOp(code, "")
    private fun chkData(vararg expected: String) = tst.chkData(*expected)
    private fun chkQuery(code: String, expected: String) = tst.chkQuery(code, expected)
}
