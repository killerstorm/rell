package net.postchain.rell

import org.junit.After
import org.junit.Test

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
        chkQuery("= json();", "ct_err:expr_call_argcnt:json:1:0")
        chkQuery("= json(1234);", "ct_err:expr_call_argtype:json:0:text:integer")
        chkQuery("= json(json('{}'));", "ct_err:expr_call_argtype:json:0:text:json")
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

    private fun exec(code: String) = tst.chkOp(code, "")
    private fun chkData(vararg expected: String) = tst.chkData(*expected)
    private fun chkQuery(code: String, expected: String) = tst.chkQuery(code, expected)
}
