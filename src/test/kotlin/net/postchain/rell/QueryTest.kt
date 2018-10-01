package net.postchain.rell

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.parser.AlternativesFailure
import com.github.h0tk3y.betterParse.parser.ErrorResult
import com.github.h0tk3y.betterParse.parser.ParseException
import net.postchain.rell.model.RModule
import net.postchain.rell.model.RQuery
import net.postchain.rell.model.makeModule
import net.postchain.rell.parser.S_Grammar
import net.postchain.rell.parser.S_ModuleDefinition
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.SqlConnector
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.genclass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class QueryTest {
    @Test fun testResultIntegerLiteral() {
        check("query q() = 12345;", "int[12345]")
    }

    @Test fun testResultStringLiteral() {
        check("query q() = \"Hello\";", "text[Hello]")
    }

    @Test fun testResultParameter() {
        check("query q(a: integer) = a;", null, arrayOf(RtIntValue(12345)), "int[12345]")
        check("query q(a: text) = a;", null, arrayOf(RtTextValue("Hello")), "text[Hello]")
        check("query q(a: integer, b: text) = a;", null, arrayOf(RtIntValue(12345), RtTextValue("Hello")), "int[12345]")
        check("query q(a: integer, b: text) = b;", null, arrayOf(RtIntValue(12345), RtTextValue("Hello")), "text[Hello]")
    }

    @Test fun testReturnLiteral() {
        check("query q() { return 12345; }", "int[12345]")
        check("query q() { return \"Hello\"; }", "text[Hello]")
    }

    @Test fun testReturnValLiteral() {
        check("query q() { val x = 12345; return x; }", "int[12345]")
        check("query q() { val x = \"Hello\"; return x; }", "text[Hello]")
    }

    @Test fun testReturnSelectNoObjects() {
        val inserts = """INSERT INTO "user"("rowid", "name") VALUES (11, 'Alice');"""
        check("class user { name: text; } query q() = user @ { name = \"Bob\" } ;", inserts, "list<user>[]")
    }

    @Test fun testReturnSelectOneObject() {
        val inserts = """
            INSERT INTO "user"("rowid", "name") VALUES (11, 'Alice');
            INSERT INTO "user"("rowid", "name") VALUES (33, 'Bob');
        """
        check("class user { name: text; } query q() = user @ { name = \"Bob\" } ;", inserts, "list<user>[user[33]]")
    }

    @Test fun testReturnSelectManyObjects() {
        val inserts = """
            INSERT INTO "user"("rowid", "name") VALUES (11, 'Alice');
            INSERT INTO "user"("rowid", "name") VALUES (33, 'Bob');
            INSERT INTO "user"("rowid", "name") VALUES (55, 'James');
            INSERT INTO "user"("rowid", "name") VALUES (77, 'Bob');
            INSERT INTO "user"("rowid", "name") VALUES (99, 'Victor');
            INSERT INTO "user"("rowid", "name") VALUES (111, 'Bob');
        """
        check("class user { name: text; } query q() = user @ { name = \"Bob\" } ;", inserts, "list<user>[user[33],user[77],user[111]]")
    }

    @Test fun testWrongNumberOfArguments() {
        val query = compileQuery("""query q(x: integer, y: text) = x;""")

        assertEquals("int[12345]", invokeQuery(query, RtIntValue(12345), RtTextValue("abc")))

        assertFailsWith(RtErrWrongNumberOfArguments::class, { invokeQuery(query) })
        assertFailsWith(RtErrWrongNumberOfArguments::class, { invokeQuery(query, RtIntValue(12345)) })
        assertFailsWith(RtErrWrongNumberOfArguments::class,
                { invokeQuery(query, RtIntValue(12345), RtTextValue("abc"), RtBooleanValue(true)) })
    }

    @Test fun testWrongArgumentType() {
        val query = compileQuery("""query q(x: integer) = x;""")

        assertEquals("int[12345]", invokeQuery(query, RtIntValue(12345)))

        assertFailsWith(RtErrWrongArgumentType::class, { invokeQuery(query, RtTextValue("Hello")) })
        assertFailsWith(RtErrWrongArgumentType::class, { invokeQuery(query, RtBooleanValue(true)) })
    }

    private fun check(code: String, expectedResult: String) {
        check(code, null, arrayOf(), expectedResult)
    }

    private fun check(code: String, inserts: String, expectedResult: String) {
        check(code, inserts, arrayOf(), expectedResult)
    }

    private fun check(code: String, inserts: String?, args: Array<RtValue>, expectedResult: String) {
        val ast = parse(code)
        val module = makeModule(ast)

        SqlConnector.connect { sqlExec ->
            for (classDef in module.classes) {
                val sql = genclass(classDef)
                sqlExec.execute(sql)
            }

            if (inserts != null) {
                sqlExec.execute(inserts)
            }

            val actualResult = execute(module, sqlExec, args)
            assertEquals(expectedResult, actualResult)
        }
    }

    private fun parse(code: String): S_ModuleDefinition {
        try {
            return S_Grammar.parseToEnd(code)
        } catch (e: ParseException) {
            println("PARSER FAILURE:")
            printError(e.errorResult, "")
            throw Exception("Parse failed")
        }
    }

    private fun printError(err: ErrorResult, indent: String) {
        if (err is AlternativesFailure) {
            println(indent + "Alternatives:")
            val alt = err as AlternativesFailure
            for (x in alt.errors) {
                printError(x, indent + "    ")
            }
        } else {
            println(indent + err)
        }
    }

    private fun execute(module: RModule, sqlExec: SqlExecutor, args: Array<RtValue>): String {
        val query = module.queries.find { it.name == "q" }
        assertNotNull(query, "Query not found")
        val res = query?.execute(sqlExec, args)
        val str = if (res == null) "null" else res.toStrictString()
        return str
    }

    private fun compileQuery(code: String): RQuery {
        val ast = parse(code)
        val module = makeModule(ast)
        val query = module.queries.find { it.name == "q" }!!
        return query
    }

    private fun invokeQuery(query: RQuery, vararg args: RtValue): String {
        val res = SqlConnector.connect { sqlExec ->
            query.execute(sqlExec, args.map{it}.toTypedArray())
        }
        return res.toStrictString()
    }
}
