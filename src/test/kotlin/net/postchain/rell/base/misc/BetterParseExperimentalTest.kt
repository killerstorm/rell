/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.misc

import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.grammar.parser
import com.github.h0tk3y.betterParse.lexer.Token
import com.github.h0tk3y.betterParse.lexer.TokenMatch
import com.github.h0tk3y.betterParse.lexer.Tokenizer
import com.github.h0tk3y.betterParse.lexer.noneMatched
import com.github.h0tk3y.betterParse.parser.Parser
import org.junit.Test
import java.io.InputStream
import java.util.*
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

    // Copy of the com.github.h0tk3y.betterParse.lexer.DefaultTokenizer class (a bit reduced):
    // the old library class did not work after upgrading to Kotlin 1.4.30 (was failing with NoClassDefFoundError).
    private class FixedDefaultTokenizer(override val tokens: List<Token>) : Tokenizer {
        private val patterns = tokens.map { it to (it.regex?.toPattern() ?: it.pattern.toPattern()) }

        override fun tokenize(input: String) = tokenize(Scanner(input))
        override fun tokenize(input: InputStream) = tokenize(Scanner(input))
        override fun tokenize(input: Readable) = tokenize(Scanner(input))

        override fun tokenize(input: Scanner): Sequence<TokenMatch> {
            input.useDelimiter("")
            var pos = 0
            val res = mutableListOf<TokenMatch>()

            while (input.hasNext()) {
                val matchedToken = patterns.firstOrNull { (_, pattern) ->
                    try {
                        input.skip(pattern)
                        true
                    } catch (_: NoSuchElementException) {
                        false
                    }
                }

                if (matchedToken == null) {
                    res.add(TokenMatch(noneMatched, input.next(), pos, 1, pos + 1))
                    break
                }

                val match = input.match().group()
                pos += match.length
                val result = TokenMatch(matchedToken.first, match, pos, 1, pos + 1)
                res.add(result)
            }

            return res.asSequence()
        }
    }
}
