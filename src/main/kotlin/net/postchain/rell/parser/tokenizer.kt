package net.postchain.rell.parser

import com.github.h0tk3y.betterParse.lexer.*
import com.github.h0tk3y.betterParse.utils.cached
import net.postchain.rell.hexStringToByteArray
import java.io.InputStream
import java.lang.IllegalArgumentException
import java.lang.UnsupportedOperationException
import java.util.*
import kotlin.coroutines.experimental.buildSequence

class RellTokenizer(override val tokens: List<Token>) : Tokenizer {
    val tkIdentifier: Token
    val tkInteger: Token
    val tkString: Token
    val tkByteArray: Token

    val tkKeywords: Map<String, Token>
    val tkDelims: List<Token>

    init {
        require(tokens.isNotEmpty()) { "The tokens list should not be empty" }

        val generals = mutableMapOf<String, Token>()
        val keywords = mutableMapOf<String, Token>()
        val delims = mutableMapOf<String, Token>()

        for (token in tokens) {
            val p = token.pattern
            if (isGeneralToken(p)) {
                require(p !in generals) { "Duplicate token: '$p'" }
                generals[p] = token
            } else if (p.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) {
                require(p !in keywords) { "Duplicate keyword: '$p'" }
                keywords[p] = token
            } else if (!p.isEmpty() && p.firstOrNull { !isDelim(it) } == null) {
                require(p !in delims) { "Duplicate token: '$p'" }
                delims[p] = token
            } else {
                throw IllegalArgumentException("Invalid token: '$p'")
            }
        }

        tkIdentifier = generals.getValue(IDENTIFIER)
        tkInteger = generals.getValue(INTEGER)
        tkString = generals.getValue(STRING)
        tkByteArray = generals.getValue(BYTEARRAY)

        tkKeywords = keywords.toMap()
        tkDelims = delims.values.toList().sortedBy { -it.pattern.length }
    }

    override fun tokenize(input: InputStream) = throw UnsupportedOperationException()

    override fun tokenize(input: Readable) = throw UnsupportedOperationException()

    override fun tokenize(input: Scanner) = throw UnsupportedOperationException()

    override fun tokenize(input: String) = buildSequence<TokenMatch> {
        val seq = CharSeq(input)

        while (true) {
            val token = scanToken(seq)
            if (token == null) {
                break
            }
            yield(token)
        }
    }.constrainOnce().cached()

    private fun scanToken(seq: CharSeq): TokenMatch? {
        scanBlank(seq)

        val k = seq.cur()
        if (k == null) {
            return null
        }

        seq.keepPos()

        if (isDigit(k) || (k == '0' && seq.afterCur() == 'x')) {
            scanWhileTrue(seq) { isDigit(it) || (it >= 'A' && it <= 'Z') || (it >= 'a' && it <= 'z') }
            val s = seq.text(0, 0)
            val tk = seq.tokenMatch(tkInteger, s)
            decodeInteger(tk) // Fail early - will throw an exception is the number is wrong
            return tk
        } else if (k == 'x' && (seq.afterCur() == '\'' || seq.afterCur() == '"')) {
            val s = scanByteArrayLiteral(seq)
            val tk = seq.tokenMatch(tkByteArray, s)
            decodeByteArray(tk) // Fail early - will throw an exception is the number is wrong
            return tk
        } else if (Character.isJavaIdentifierStart(k)) {
            scanWhileTrue(seq, Character::isJavaIdentifierPart)
            val s = seq.text(0, 0)
            val tk = tkKeywords.getOrDefault(s, tkIdentifier)
            return seq.tokenMatch(tk, s)
        } else if (isDelim(k)) {
            scanWhileTrue(seq, ::isDelim)
            val s = seq.text(0, 0)
            return scanDelimiter(seq, s)
        } else if (k == '\'' || k == '"') {
            val s = scanStringLiteral(seq)
            return seq.tokenMatch(tkString, s)
        } else {
            throw seq.err("lex_token", "Syntax error")
        }
    }

    private fun scanBlank(seq: CharSeq) {
        while (true) {
            val k = seq.cur()
            if (k == null) {
                break
            } else if (Character.isWhitespace(k)) {
                seq.next()
            } else if (k == '/') {
                val k2 = seq.afterCur()
                if (k2 == '/') {
                    seq.next()
                    seq.next()
                    scanSingleLineComment(seq)
                } else if (k2 == '*') {
                    seq.next()
                    seq.next()
                    scanMultiLineComment(seq)
                } else {
                    break
                }
            } else {
                break
            }
        }
    }

    private fun scanSingleLineComment(seq: CharSeq) {
        scanWhileTrue(seq, { it != '\n' })
        seq.next()
    }

    private fun scanMultiLineComment(seq: CharSeq) {
        while (true) {
            val k = seq.cur()
            seq.next()
            if (k == null) {
                throw seq.err("lex_comment_eof", "Unclosed multiline comment")
            } else if (k == '*' && seq.cur() == '/') {
                seq.next()
                break
            }
        }
    }

    private fun scanStringLiteral(seq: CharSeq): String {
        val q = seq.cur()
        seq.next()

        val buf = StringBuilder()

        while (true) {
            val k = seq.cur()
            if (k == q) {
                seq.next()
                break
            } else if (k == null || k == '\n') {
                throw seq.err("lex_string_unclosed", "Unclosed string literal")
            } else if (k == '\\') {
                val c = scanEscapeSeq(seq)
                buf.append(c)
            } else {
                buf.append(k)
                seq.next()
            }
        }

        return buf.toString()
    }

    private fun scanEscapeSeq(seq: CharSeq): Char {
        val x = seq.afterCur()
        if (x == 'u') {
            return scanUnicodeEscapeSeq(seq)
        } else {
            val c = when (x) {
                'b' -> '\b'
                't' -> '\t'
                'r' -> '\r'
                'n' -> '\n'
                '"' -> '"'
                '\'' -> '\''
                '\\' -> '\\'
                else -> throw seq.err("lex_string_esc", "Invalid escape sequence")
            }
            seq.next()
            seq.next()
            return c
        }
    }

    private fun scanUnicodeEscapeSeq(seq: CharSeq): Char {
        val pos = seq.textPos()
        seq.next()
        seq.next()

        var code = 0

        for (i in 0..3) {
            var c = seq.cur()
            val k = if (c == null) -1 else Character.digit(c, 16)
            if (k < 0) {
                throw seq.err(pos, "lex_string_esc_unicode", "Invalid UNICODE escape sequence")
            }
            code = code * 16 + k
            seq.next()
        }

        return code.toChar()
    }

    private fun scanByteArrayLiteral(seq: CharSeq): String {
        seq.next()
        val q = seq.cur()
        seq.next()

        val buf = StringBuilder()

        while (true) {
            val c = seq.cur()
            if (c == q) {
                seq.next()
                break
            } else if (c == null || c == '\n') {
                throw seq.err("lex_bytearray_unclosed", "Unclosed byte array literal")
            }
            seq.next()
            buf.append(c)
        }

        return buf.toString()
    }

    private fun scanDelimiter(seq: CharSeq, s: String): TokenMatch {
        for (tk in tkDelims) {
            if (s.startsWith(tk.pattern)) {
                seq.back(s.length - tk.pattern.length)
                return seq.tokenMatch(tk, s)
            }
        }
        throw seq.err("lex_delim:$s", "Syntax error")
    }

    private fun scanWhileTrue(seq: CharSeq, predicate: (Char) -> Boolean) {
        seq.next()
        while (true) {
            val k = seq.cur()
            if (k == null || !predicate(k)) break
            seq.next()
        }
    }

    private fun isGeneralToken(s: String) = s.matches(Regex("<[A-Z0-9_]+>"))

    private fun isDigit(c: Char) = c >= '0' && c <= '9'
    private fun isDelim(c: Char) = "~!@#$%^&*()-=+[]{}|;:,.<>/?".contains(c)

    companion object {
        val IDENTIFIER = "<IDENTIFIER>"
        val INTEGER = "<INTEGER>"
        val STRING = "<STRING>"
        val BYTEARRAY = "<BYTEARRAY>"

        fun decodeInteger(t: TokenMatch): Long {
            val s = t.text
            return try {
                if (s.startsWith("0x")) {
                    java.lang.Long.parseLong(s.substring(2), 16)
                } else {
                    java.lang.Long.parseLong(s)
                }
            } catch (e: NumberFormatException) {
                throw C_Error(S_Pos(t), "lex_int:$s", "Invalid integer literal: '$s'")
            }
        }

        fun decodeString(t: TokenMatch): String {
            return t.text
        }

        fun decodeByteArray(t: TokenMatch): ByteArray {
            val s = t.text
            try {
                return s.hexStringToByteArray()
            } catch (e: IllegalArgumentException) {
                val maxlen = 64
                val p = if (s.length <= maxlen) s else (s.substring(0, maxlen) + "...")
                throw C_Error(S_Pos(t), "parser_bad_hex:$p", "Invalid byte array literal: '$p'")
            }
        }
    }
}

private class CharSeq(private val str: String) {
    private var len = str.length
    private var pos = 0
    private var row = 1
    private var col = 1
    private var cur: Char? = null

    private var startPos = 0
    private var startRow = 1
    private var startCol = 1

    init {
        update()
    }

    private fun update() {
        cur = if (pos >= len) null else str[pos]
    }

    fun cur() = cur
    fun afterCur() = if (pos >= len - 1) null else str[pos + 1]
    fun pos() = pos

    fun keepPos() {
        startPos = pos
        startRow = row
        startCol = col
    }

    fun textPos() = S_Pos(row, col)
    fun text(startSkip: Int, endSkip: Int) = str.substring(startPos + startSkip, pos - endSkip)

    fun tokenMatch(type: Token, text: String) = TokenMatch(type, text, startPos, startRow, startCol)

    fun err(code: String, msg: String) = err(textPos(), code, msg)
    fun err(pos: S_Pos, code: String, msg: String) = C_Error(pos, code, msg)

    fun next() {
        if (pos < len) {
            val k = str[pos]
            if (k == '\n') {
                ++row
                col = 1
            } else {
                ++col
            }
            pos++
            update()
        }
    }

    fun back(n: Int) {
        // Assuming we never go back to a previous line, only within the same line.
        require(n <= pos)
        require(n < col)
        pos -= n
        col -= n
        update()
    }
}
