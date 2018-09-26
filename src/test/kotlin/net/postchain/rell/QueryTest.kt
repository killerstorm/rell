package net.postchain.rell

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.parser.AlternativesFailure
import com.github.h0tk3y.betterParse.parser.ErrorResult
import com.github.h0tk3y.betterParse.parser.ParseException
import net.postchain.rell.model.makeModule
import net.postchain.rell.parser.S_Grammar
import net.postchain.rell.parser.S_ModuleDefinition
import org.apache.commons.lang3.builder.RecursiveToStringStyle
import org.apache.commons.lang3.builder.ToStringBuilder
import org.apache.commons.lang3.builder.ToStringStyle
import org.jooq.impl.ParserException
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
        check("query q(a: integer) = a;", "int[12345]")
        check("query q(a: text) = a;", "text[Hello]")
        check("query q(a: integer, b: text) = a;", "int[12345]")
        check("query q(a: integer, b: text) = a;", "text[Hello]")
    }

    @Test fun testReturnLiteral() {
        check("query q() { return 12345; }", "int[12345]")
        check("query q() { return \"Hello\"; }", "text[Hello]")
    }

    @Test fun testReturnVariableLiteral() {
        check("query q() { val x = 12345; return x; }", "int[12345]")
        check("query q() { val x = \"Hello\"; return x; }", "text[Hello]")
    }

    @Test fun testReturnSelectNoObjects() {
        check("query q() = user @ { name = \"Bob\" } ;", "list<user>[]")
    }

    @Test fun testReturnSelectOneObject() {
        check("query q() = user @ { name = \"Bob\" } ;", "list<user>[user[33]]")
    }

    @Test fun testReturnSelectManyObjects() {
        check("query q() = user @ { name = \"Bob\" } ;", "list<user>[user[33],user[77],user[111]]")
    }

    private fun check(code: String, expectedResult: String) {
        val ast = parse(code)
        val model = makeModule(ast)

        println(ToStringBuilder.reflectionToString(model, StrStyle()))
        //val actualResult = execute(code)
        //assertEquals(expectedResult, actualResult)
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

    private fun execute(code: String): String {
        return "123"
    }

    private class StrStyle : RecursiveToStringStyle {
        constructor() {
            setUseShortClassName(true)
            setUseIdentityHashCode(false)
        }
    }
}
