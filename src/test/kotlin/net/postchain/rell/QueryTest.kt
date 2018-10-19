package net.postchain.rell

import net.postchain.rell.runtime.*
import net.postchain.rell.sql.NullSqlExecutor
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals

class QueryTest {
    private val tst = RellSqlTester()

    @After fun after() = tst.destroy()

    @Test fun testResultIntegerLiteral() {
        check("query q() = 12345;", "int[12345]")
    }

    @Test fun testResultStringLiteral() {
        check("query q() = \"Hello\";", "text[Hello]")
    }

    @Test fun testResultParameter() {
        check("query q(a: integer) = a;", listOf(RtIntValue(12345)), "int[12345]")
        check("query q(a: text) = a;", listOf(RtTextValue("Hello")), "text[Hello]")
        check("query q(a: integer, b: text) = a;", listOf(RtIntValue(12345), RtTextValue("Hello")), "int[12345]")
        check("query q(a: integer, b: text) = b;", listOf(RtIntValue(12345), RtTextValue("Hello")), "text[Hello]")
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
        tst.classDefs = listOf("class user { name: text; }")
        tst.inserts = listOf(mkins("user", "name", "11, 'Alice'"))
        check("query q() = all user @ { name = \"Bob\" } ;", "list<user>[]")
    }

    @Test fun testReturnSelectAllOneObject() {
        tst.classDefs = listOf("class user { name: text; }")
        tst.inserts = listOf(
                mkins("user", "name", "11,'Alice'"),
                mkins("user", "name", "33,'Bob'")
        )
        check("query q() = all user @ { name = \"Bob\" } ;", "list<user>[user[33]]")
    }

    @Test fun testReturnSelectAllManyObjects() {
        tst.classDefs = listOf("class user { name: text; }")
        tst.inserts = listOf(
                mkins("user", "name", "11,'Alice'"),
                mkins("user", "name", "33,'Bob'"),
                mkins("user", "name", "55,'James'"),
                mkins("user", "name", "77,'Bob'"),
                mkins("user", "name", "99,'Victor'"),
                mkins("user", "name", "111,'Bob'")
        )
        check("query q() = all user @ { name = \"Bob\" } ;", "list<user>[user[33],user[77],user[111]]")
    }

    @Test fun testWrongNumberOfArguments() {
        val code = "query q(x: integer, y: text) = x;"
        check(code, listOf(RtIntValue(12345), RtTextValue("abc")), "int[12345]")
        check(code, listOf(), "rt_err:fn_wrong_arg_count:q:2:0")
        check(code, listOf(RtIntValue(12345)), "rt_err:fn_wrong_arg_count:q:2:1")
        check(code, listOf(RtIntValue(12345), RtTextValue("abc"), RtBooleanValue(true)), "rt_err:fn_wrong_arg_count:q:2:3")
    }

    @Test fun testWrongArgumentType() {
        val code = "query q(x: integer) = x;"
        check(code, listOf(RtIntValue(12345)), "int[12345]")
        check(code, listOf(RtTextValue("Hello")), "rt_err:fn_wrong_arg_type:q:integer:text")
        check(code, listOf(RtBooleanValue(true)), "rt_err:fn_wrong_arg_type:q:integer:boolean")
    }

    @Test fun testCreateUpdateDelete() {
        val defs = "class user { mutable name: text; } query q()"
        check("$defs { create user('Bob'); return 0; }", "ct_err:no_db_update")
        check("$defs { update user @ {} ( name = 'Bob'); return 0; }", "ct_err:no_db_update")
        check("$defs { delete user @ { name = 'Bob' }; return 0; }", "ct_err:no_db_update")
        check("$defs { if (2 < 3) create user('Bob'); return 0; }", "ct_err:no_db_update")
        check("$defs { if (2 < 3) update user @ {} ( name = 'Bob'); return 0; }", "ct_err:no_db_update")
        check("$defs { if (2 < 3) delete user @ { name = 'Bob' }; return 0; }", "ct_err:no_db_update")
    }

    private fun check(code: String, expectedResult: String) = check(code, listOf(), expectedResult)
    private fun check(code: String, args: List<RtValue>, expected: String) = tst.chkQueryEx(code, args, expected)

    private val mkins = SqlTestUtils::mkins
}
