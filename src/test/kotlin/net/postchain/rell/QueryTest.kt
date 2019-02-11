package net.postchain.rell

import net.postchain.rell.runtime.*
import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.SqlTestUtils
import org.junit.Test

class QueryTest: BaseRellTest() {
    @Test fun testResultIntegerLiteral() {
        chkEx("= 12345;", "int[12345]")
    }

    @Test fun testResultStringLiteral() {
        chkEx("= 'Hello';", "text[Hello]")
    }

    @Test fun testResultParameter() {
        chkQueryEx("query q(a: integer) = a;", listOf(Rt_IntValue(12345)), "int[12345]")
        chkQueryEx("query q(a: text) = a;", listOf(Rt_TextValue("Hello")), "text[Hello]")
        chkQueryEx("query q(a: integer, b: text) = a;", listOf(Rt_IntValue(12345), Rt_TextValue("Hello")), "int[12345]")
        chkQueryEx("query q(a: integer, b: text) = b;", listOf(Rt_IntValue(12345), Rt_TextValue("Hello")), "text[Hello]")
    }

    @Test fun testReturnLiteral() {
        chkEx("{ return 12345; }", "int[12345]")
        chkEx("{ return \"Hello\"; }", "text[Hello]")
    }

    @Test fun testReturnValLiteral() {
        chkEx("{ val x = 12345; return x; }", "int[12345]")
        chkEx("{ val x = \"Hello\"; return x; }", "text[Hello]")
    }

    @Test fun testReturnSelectAllNoObjects() {
        tst.defs = listOf("class user { name: text; }")
        tst.inserts = listOf(mkins("c0_user", "name", "11, 'Alice'"))
        chkEx("= user @* { .name == 'Bob' } ;", "list<user>[]")
    }

    @Test fun testReturnSelectAllOneObject() {
        tst.defs = listOf("class user { name: text; }")
        tst.inserts = listOf(
                mkins("c0_user", "name", "11,'Alice'"),
                mkins("c0_user", "name", "33,'Bob'")
        )
        chkEx("= user @* { .name == \"Bob\" } ;", "list<user>[user[33]]")
    }

    @Test fun testReturnSelectAllManyObjects() {
        tst.defs = listOf("class user { name: text; }")
        tst.inserts = listOf(
                mkins("c0_user", "name", "11,'Alice'"),
                mkins("c0_user", "name", "33,'Bob'"),
                mkins("c0_user", "name", "55,'James'"),
                mkins("c0_user", "name", "77,'Bob'"),
                mkins("c0_user", "name", "99,'Victor'"),
                mkins("c0_user", "name", "111,'Bob'")
        )
        chkEx("= user @* { .name == 'Bob' } ;", "list<user>[user[33],user[77],user[111]]")
    }

    @Test fun testReturnErr() {
        chkEx("{ return; }", "ct_err:stmt_return_query_novalue")

        chkQueryEx("query q(): integer = 123;", "int[123]")
        chkQueryEx("query q(): integer = 'Hello';", "ct_err:entity_rettype:integer:text")
        chkQueryEx("query q(): text = 123;", "ct_err:entity_rettype:text:integer")

        chkQueryEx("query q(): integer { return 123; }", "int[123]")
        chkQueryEx("query q(): integer { return 'Hello'; }", "ct_err:entity_rettype:integer:text")
        chkQueryEx("query q(): text { return 123; }", "ct_err:entity_rettype:text:integer")

        chkEx("{ if (1 > 0) return 123; else return 456; }", "int[123]")
        chkEx("{ if (1 > 0) return 123; else return 'Hello'; }", "ct_err:entity_rettype:integer:text")
        chkEx("{ if (1 > 0) return 'Hello'; else return 123; }", "ct_err:entity_rettype:text:integer")
    }

    @Test fun testNoReturn() {
        chkEx("{ return 123; }", "int[123]")
        chkEx("{}", "ct_err:query_noreturn:q")
        chkEx("{ if (1 > 0) return 123; }", "ct_err:query_noreturn:q")
        chkEx("{ if (1 > 0) return 123; return 456; }", "int[123]")
        chkEx("{ if (1 > 0) {} else return 456; }", "ct_err:query_noreturn:q")
        chkEx("{ if (1 > 0) return 123; else return 456; }", "int[123]")
        chkEx("{ if (1 > 0) { return 123; } else { return 456; } }", "int[123]")
        chkEx("{ if (1 > 0) { if (2 > 3) return 100; else return 200; } else { return 456; } }", "int[200]")
        chkEx("{ while (1 < 0) return 123; }", "ct_err:query_noreturn:q")
    }

    @Test fun testWrongNumberOfArguments() {
        val code = "query q(x: integer, y: text) = x;"
        chkQueryEx(code, listOf(Rt_IntValue(12345), Rt_TextValue("abc")), "int[12345]")
        chkQueryEx(code, listOf(), "rt_err:fn_wrong_arg_count:q:2:0")
        chkQueryEx(code, listOf(Rt_IntValue(12345)), "rt_err:fn_wrong_arg_count:q:2:1")
        chkQueryEx(code, listOf(Rt_IntValue(12345), Rt_TextValue("abc"), Rt_BooleanValue(true)), "rt_err:fn_wrong_arg_count:q:2:3")
    }

    @Test fun testWrongArgumentType() {
        val code = "query q(x: integer) = x;"
        chkQueryEx(code, listOf(Rt_IntValue(12345)), "int[12345]")
        chkQueryEx(code, listOf(Rt_TextValue("Hello")), "rt_err:fn_wrong_arg_type:q:integer:text")
        chkQueryEx(code, listOf(Rt_BooleanValue(true)), "rt_err:fn_wrong_arg_type:q:integer:boolean")
    }

    @Test fun testCreateUpdateDelete() {
        tst.defs = listOf("class user { mutable name: text; }")
        chkEx("{ create user('Bob'); return 0; }", "ct_err:no_db_update")
        chkEx("{ update user @ {} ( name = 'Bob'); return 0; }", "ct_err:no_db_update")
        chkEx("{ delete user @ { .name == 'Bob' }; return 0; }", "ct_err:no_db_update")
        chkEx("{ if (2 < 3) create user('Bob'); return 0; }", "ct_err:no_db_update")
        chkEx("{ if (2 < 3) update user @ {} ( .name == 'Bob'); return 0; }", "ct_err:no_db_update")
        chkEx("{ if (2 < 3) delete user @ { .name == 'Bob' }; return 0; }", "ct_err:no_db_update")
    }

    @Test fun testReturnTypeExplicit() {
        tst.useSql = false
        tst.chkQueryType(": integer { return null; }", "ct_err:entity_rettype:integer:null")
        tst.chkQueryType(": integer? { return null; }", "integer?")
        tst.chkQueryType(": integer? { return 123; }", "integer?")
        tst.chkQueryType(": integer { return 123; }", "integer")
        tst.chkQueryType(": integer { if (integer('0') == 0) return 123; else return null; }",
                "ct_err:entity_rettype:integer:null")
        tst.chkQueryType(": integer? { if (integer('0') == 0) return null; else return 123; }", "integer?")
        tst.chkQueryType(": integer? { if (integer('0') == 0) return 123; else return null; }", "integer?")
    }

    @Test fun testReturnTypeImplicit() {
        tst.useSql = false

        tst.chkQueryType("{ return null; }", "null")
        tst.chkQueryType("{ if (integer('0') == 0) return 123; else return 456; }", "integer")
        tst.chkQueryType("{ if (integer('0') == 0) return 123; else return null; }", "integer?")
        tst.chkQueryType("{ if (integer('0') == 0) return null; else return 123; }", "integer?")

        chkEx("{ if (a == 0) return null; else return 123; }", 0, "null")
        chkEx("{ if (a == 0) return null; else return 123; }", 1, "int[123]")
        chkEx("{ if (a == 0) return 123; else return null; }", 0, "int[123]")
        chkEx("{ if (a == 0) return 123; else return null; }", 1, "null")
        chkEx("{ return null; }", "null")

        chkEx("= unit();", "ct_err:query_exprtype_unit")
        chkEx("= print('Hello');", "ct_err:query_exprtype_unit")
        chkEx("{ return unit(); }", "ct_err:stmt_return_unit")
        chkEx("{ return print('Hello'); }", "ct_err:stmt_return_unit")

        chkEx("{ if (1 > 0) return 123; else return 'Hello'; }", "ct_err:entity_rettype:integer:text")
    }

    private val mkins = SqlTestUtils::mkins
}
