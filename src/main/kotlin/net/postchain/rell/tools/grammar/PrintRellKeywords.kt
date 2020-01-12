package net.postchain.rell.tools.grammar

import net.postchain.rell.compiler.parser.S_Grammar

fun main() {
    val tokenizer = S_Grammar.tokenizer
    for (t in tokenizer.tkKeywords.values.sortedBy { it.token.pattern }) {
        println(t.token.pattern)
    }
}
