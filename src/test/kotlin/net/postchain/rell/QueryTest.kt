package net.postchain.rell

import net.postchain.rell.runtime.*
import org.junit.Test
import kotlin.test.assertEquals

class QueryTest {
    @Test fun testResultIntegerLiteral() {
        check("query q() = 12345;", "int[12345]")
    }

    @Test fun testResultStringLiteral() {
        check("query q() = \"Hello\";", "text[Hello]")
    }

    @Test fun testResultParameter() {
        check("query q(a: integer) = a;", arrayOf(), arrayOf(RtIntValue(12345)), "int[12345]")
        check("query q(a: text) = a;", arrayOf(), arrayOf(RtTextValue("Hello")), "text[Hello]")
        check("query q(a: integer, b: text) = a;", arrayOf(), arrayOf(RtIntValue(12345), RtTextValue("Hello")), "int[12345]")
        check("query q(a: integer, b: text) = b;", arrayOf(), arrayOf(RtIntValue(12345), RtTextValue("Hello")), "text[Hello]")
    }

    @Test fun testReturnLiteral() {
        check("query q() { return 12345; }", "int[12345]")
        check("query q() { return \"Hello\"; }", "text[Hello]")
    }

    @Test fun testReturnValLiteral() {
        check("query q() { val x = 12345; return x; }", "int[12345]")
        check("query q() { val x = \"Hello\"; return x; }", "text[Hello]")
    }

    @Test fun testReturnSelectAllNoObjects() {
        val inserts = arrayOf(mkins("user", "name", "11, 'Alice'"))
        check("class user { name: text; } query q() = all user @ { name = \"Bob\" } ;", inserts, "list<user>[]")
    }

    @Test fun testReturnSelectAllOneObject() {
        val inserts = arrayOf(
                mkins("user", "name", "11,'Alice'"),
                mkins("user", "name", "33,'Bob'")
        )
        check("class user { name: text; } query q() = all user @ { name = \"Bob\" } ;", inserts, "list<user>[user[33]]")
    }

    @Test fun testReturnSelectAllManyObjects() {
        val inserts = arrayOf(
                mkins("user", "name", "11,'Alice'"),
                mkins("user", "name", "33,'Bob'"),
                mkins("user", "name", "55,'James'"),
                mkins("user", "name", "77,'Bob'"),
                mkins("user", "name", "99,'Victor'"),
                mkins("user", "name", "111,'Bob'")
        )
        check("class user { name: text; } query q() = all user @ { name = \"Bob\" } ;", inserts, "list<user>[user[33],user[77],user[111]]")
    }

    @Test fun testWrongNumberOfArguments() {
        val query = TestUtils.compileQuery("""query q(x: integer, y: text) = x;""")
        assertEquals("int[12345]", TestUtils.invokeQuery(query, RtIntValue(12345), RtTextValue("abc")))
        assertEquals("rt_err:fn_wrong_arg_count:q:2:0", TestUtils.invokeQuery(query))
        assertEquals("rt_err:fn_wrong_arg_count:q:2:1", TestUtils.invokeQuery(query, RtIntValue(12345)))
        assertEquals("rt_err:fn_wrong_arg_count:q:2:3",
                TestUtils.invokeQuery(query, RtIntValue(12345), RtTextValue("abc"), RtBooleanValue(true)))
    }

    @Test fun testWrongArgumentType() {
        val query = TestUtils.compileQuery("""query q(x: integer) = x;""")
        assertEquals("int[12345]", TestUtils.invokeQuery(query, RtIntValue(12345)))
        assertEquals("rt_err:fn_wrong_arg_type:q:integer:text", TestUtils.invokeQuery(query, RtTextValue("Hello")))
        assertEquals("rt_err:fn_wrong_arg_type:q:integer:boolean", TestUtils.invokeQuery(query, RtBooleanValue(true)))
    }

    private fun check(code: String, expectedResult: String) {
        check(code, arrayOf(), arrayOf(), expectedResult)
    }

    private fun check(code: String, inserts: Array<String>, expectedResult: String) {
        check(code, inserts, arrayOf(), expectedResult)
    }

    private fun check(code: String, inserts: Array<String>, args: Array<RtValue>, expectedResult: String) {
        val actualResult = TestUtils.invokeWithSql(code, inserts, args)
        assertEquals(expectedResult, actualResult)
    }

    private val mkins = TestUtils::mkins
}
