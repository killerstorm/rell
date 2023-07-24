/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.utils

import com.github.h0tk3y.betterParse.lexer.Token
import com.github.h0tk3y.betterParse.lexer.TokenMatch
import com.github.h0tk3y.betterParse.lexer.Tokenizer
import com.github.h0tk3y.betterParse.lexer.noneMatched
import java.io.InputStream
import java.util.*

// Copy of the com.github.h0tk3y.betterParse.lexer.DefaultTokenizer class (a bit reduced):
// the old library class did not work after upgrading to Kotlin 1.4.30 (was failing with NoClassDefFoundError).
class FixedDefaultTokenizer(override val tokens: List<Token>) : Tokenizer {
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
