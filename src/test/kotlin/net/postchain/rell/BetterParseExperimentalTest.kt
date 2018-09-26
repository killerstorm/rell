package net.postchain.rell

import com.github.h0tk3y.betterParse.combinators.map
import com.github.h0tk3y.betterParse.combinators.or
import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.parser.Parser
import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.parseToEnd
import org.junit.Test
import kotlin.test.assertEquals

class BetterParseExperimentalTest {
    @Test fun test() {
        assertEquals("name[foo]", TestGrammar.parseToEnd("foo;"))
        assertEquals("select[bar]", TestGrammar.parseToEnd("bar@;"))
    }

    object TestGrammar: Grammar<String>() {
        private val ID by token("[A-ZA-z][A-Za-z0-9]*")
        private val AT by token("@")
        private val SEMI by token(";")

        private val nameExpr by ( ID ) map { "name[${it.text}]" }
        private val selectExpr by ( ID * -AT ) map { id -> "select[${id.text}]" }
        private val expr by ( selectExpr or nameExpr )

        private val stmt by ( expr * -SEMI )

        override val rootParser by stmt
    }
}
