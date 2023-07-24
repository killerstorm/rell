/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.misc

import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.grammar.parser
import com.github.h0tk3y.betterParse.lexer.Tokenizer
import com.github.h0tk3y.betterParse.parser.Parser
import net.postchain.rell.base.utils.FixedDefaultTokenizer
import org.junit.Test
import kotlin.test.assertEquals

class BetterParseExperimentalTest {
    @Test fun test() {
        assertEquals("name[foo]", TestGrammar.parseToEnd("foo;"))
        assertEquals("select[bar]", TestGrammar.parseToEnd("bar@;"))
    }

    @Test fun testTupleAmbiguity() {
        assertEquals("name[foo]", TupleGrammar.parseToEnd("foo"))
        assertEquals("num[123]", TupleGrammar.parseToEnd("123"))
        assertEquals("num[123]", TupleGrammar.parseToEnd("(123)"))
        assertEquals("tuple[(null,num[123])]", TupleGrammar.parseToEnd("(123,)"))
        assertEquals("name[foo]", TupleGrammar.parseToEnd("(foo)"))
        assertEquals("tuple[(null,name[foo])]", TupleGrammar.parseToEnd("(foo,)"))
        assertEquals("binary[name[foo],[(EQ,num[123])]]", TupleGrammar.parseToEnd("foo=123"))
        assertEquals("binary[name[foo],[(EQ,num[123])]]", TupleGrammar.parseToEnd("(foo=123)"))
        assertEquals("tuple[(foo,num[123])]", TupleGrammar.parseToEnd("(foo=123,)"))
        assertEquals("tuple[(foo,num[123]),(bar,num[456])]", TupleGrammar.parseToEnd("(foo=123,bar=456)"))
        assertEquals("tuple[(foo,num[123]),(bar,num[456])]", TupleGrammar.parseToEnd("(foo=123,bar=456,)"))
        assertEquals("tuple[(foo,num[123]),(bar,num[456]),(baz,num[789])]", TupleGrammar.parseToEnd("(foo=123,bar=456,baz=789)"))
        assertEquals("tuple[(foo,num[123]),(bar,num[456]),(baz,num[789])]", TupleGrammar.parseToEnd("(foo=123,bar=456,baz=789,)"))
    }

    @Test fun testExternalGrammarRules() {
        assertEquals("() : unit", FunGrammar.parseToEnd("():unit"))
        assertEquals("(list<integer>, map<text,decimal>) : set<text>",
            FunGrammar.parseToEnd("(list<integer>,map<text,decimal>):set<text>"))
    }

    private object TestGrammar: Grammar<String>() {
        override val tokenizer: Tokenizer by lazy { FixedDefaultTokenizer(tokens) }

        private val ID by token("[A-ZA-z][A-Za-z0-9]*")
        private val AT by token("@")
        private val SEMI by token(";")

        private val nameExpr by ( ID ) map { "name[${it.text}]" }
        private val selectExpr by ( ID * -AT ) map { id -> "select[${id.text}]" }
        private val expr by ( selectExpr or nameExpr )

        private val stmt by ( expr * -SEMI )

        override val rootParser by stmt
    }

    private object TupleGrammar: Grammar<String>() {
        override val tokenizer: Tokenizer by lazy { FixedDefaultTokenizer(tokens) }

        private val ID by token("[A-ZA-z][A-Za-z0-9]*")
        private val NUM by token("[0-9]+")
        private val LPAR by token("\\(")
        private val RPAR by token("\\)")
        private val COMMA by token(",")
        private val COLON by token(":")
        private val EQ by token("=")
        private val NE by token("!=")

        private val _expr by parser(this::expr)

        private val nameExpr by (ID) map { "name[${it.text}]" }
        private val numExpr by (NUM) map { "num[${it.text}]" }

        private val parExpr by ( -LPAR * _expr * -RPAR) map { it }

        private val tupField by ( optional(ID * -EQ) * _expr) map { ( name, expr ) -> Pair(name?.text, expr) }

        private val tupTail by ( separatedTerms(tupField, COMMA, true) * -optional(COMMA) )

        private val tupExpr by ( -LPAR * tupField * -COMMA * optional(tupTail) * -RPAR) map {
            (field, tail) ->
            if (tail == null && field.first == null) {
                field.second
            } else {
                val fields = (listOf(field) + (tail ?: listOf())).joinToString(",") { (a, b) -> "($a,$b)" }
                "tuple[$fields]"
            }
        }

        private val operandExpr by ( nameExpr or numExpr or parExpr or tupExpr )

        private val binaryOperator = ( EQ or NE )

        private val binaryExprOperand by ( binaryOperator * operandExpr ) map { ( op, expr ) -> "(${op.type.name},$expr)" }

        private val binaryExpr by ( operandExpr * zeroOrMore(binaryExprOperand) ) map { ( head, tail ) ->
            if (tail.isEmpty()) head else "binary[$head,$tail]"
        }

        private val expr: Parser<String> by binaryExpr

        override val rootParser by expr
    }

    private object TypeGrammar: Grammar<String>() {
        override val tokenizer: Tokenizer by lazy { FixedDefaultTokenizer(tokens) }

        private val ID by token("[A-ZA-z][A-Za-z0-9]*")
        private val LT by token("<")
        private val GT by token(">")
        val COMMA by token(",")

        private val nameType: Parser<String> by ID * optional(-LT * separatedTerms(parser(this::type), COMMA) * -GT) map {
            (name, args) -> if (args == null) name.text else "${name.text}<${args.joinToString(",")}>"
        }

        val type: Parser<String> by nameType

        override val rootParser by type
    }

    private object FunGrammar: Grammar<String>() {
        override val tokenizer: Tokenizer by lazy { FixedDefaultTokenizer(TypeGrammar.tokens + tokens) }

        private val LPAREN by token("\\(")
        private val RPAREN by token("\\)")
        private val COLON by token(":")

        private val params by separatedTerms(TypeGrammar.type, TypeGrammar.COMMA, true) map {
            it.joinToString(", ", "(", ")")
        }

        val funHeader: Parser<String> by -LPAREN * params * -RPAREN * -COLON * TypeGrammar.type map {
            (params, res) -> "$params : $res"
        }

        override val rootParser by funHeader
    }
}
