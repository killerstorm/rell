package net.postchain.rell.parser

import com.github.h0tk3y.betterParse.lexer.TokenMatch
import com.github.h0tk3y.betterParse.lexer.Tokenizer
import com.github.h0tk3y.betterParse.utils.cached
import net.postchain.rell.hexStringToByteArray
import java.io.InputStream
import java.util.*
import kotlin.coroutines.experimental.buildSequence

class RellTokenizer(private val tokensEx: List<RellToken>) : Tokenizer {
    val tkIdentifier: RellToken
    val tkInteger: RellToken
    val tkString: RellToken
    val tkByteArray: RellToken

    val tkKeywords: Map<String, RellToken>
    val tkDelims: List<RellToken>

    override val tokens = tokensEx.map { it.token }

    init {
        require(tokens.isNotEmpty()) { "The tokens list should not be empty" }

        val generals = mutableMapOf<String, RellToken>()
        val keywords = mutableMapOf<String, RellToken>()
        val delims = mutableMapOf<String, RellToken>()

        for (token in tokensEx) {
            val p = token.token.pattern
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
        tkDelims = delims.values.toList().sortedBy { -it.token.pattern.length }
    }

    override fun tokenize(input: InputStream) = throw UnsupportedOperationException()

    override fun tokenize(input: Readable) = throw UnsupportedOperationException()

    override fun tokenize(input: Scanner) = throw UnsupportedOperationException()

    override fun tokenize(input: String) = tokenize(input) {}

    fun tokenize(input: String, callback: (TokenMatch) -> Unit) = buildSequence<TokenMatch> {
        val seq = CharSeq(input)
        while (true) {
            val token = scanToken(seq)
            token ?: break
            callback(token)
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
            val pos = seq.startPos()
            val tk = seq.tokenMatch(tkInteger, s)
            decodeInteger(pos, tk.text) // Fail early - will throw an exception is the number is wrong
            return tk
        } else if (k == 'x' && (seq.afterCur() == '\'' || seq.afterCur() == '"')) {
            val s = scanByteArrayLiteral(seq)
            val pos = seq.startPos()
            val tk = seq.tokenMatch(tkByteArray, s)
            decodeByteArray(pos, tk.text) // Fail early - will throw an exception is the number is wrong
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
            throw seq.err("lex:token", "Syntax error")
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
                throw seq.err("lex:comment_eof", "Unclosed multiline comment")
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
                throw seq.err("lex:string_unclosed", "Unclosed string literal")
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
                else -> throw seq.err("lex:string_esc", "Invalid escape sequence")
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
                throw seq.err(pos, "lex:string_esc_unicode", "Invalid UNICODE escape sequence")
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
                throw seq.err("lex:bytearray_unclosed", "Unclosed byte array literal")
            }
            seq.next()
            buf.append(c)
        }

        return buf.toString()
    }

    private fun scanDelimiter(seq: CharSeq, s: String): TokenMatch {
        for (tkEx in tkDelims) {
            val tk = tkEx.token
            if (s.startsWith(tk.pattern)) {
                seq.back(s.length - tk.pattern.length)
                return seq.tokenMatch(tkEx, tk.pattern)
            }
        }
        throw seq.err("lex:delim:$s", "Syntax error")
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

        fun decodeInteger(pos: S_Pos, s: String): Long {
            return try {
                if (s.startsWith("0x")) {
                    java.lang.Long.parseLong(s.substring(2), 16)
                } else {
                    java.lang.Long.parseLong(s)
                }
            } catch (e: NumberFormatException) {
                throw C_Error(pos, "lex:int:$s", "Invalid integer literal: '$s'")
            }
        }

        fun decodeString(pos: S_Pos, s: String): String {
            return s
        }

        fun decodeByteArray(pos: S_Pos, s: String): ByteArray {
            try {
                return s.hexStringToByteArray()
            } catch (e: IllegalArgumentException) {
                val maxlen = 64
                val p = if (s.length <= maxlen) s else (s.substring(0, maxlen) + "...")
                throw C_Error(pos, "lex:bad_hex:$p", "Invalid byte array literal: '$p'")
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
    fun startPos() = S_BasicPos(C_Parser.currentFile(), startRow, startCol)
    fun pos() = pos

    fun keepPos() {
        startPos = pos
        startRow = row
        startCol = col
    }

    fun textPos() = S_BasicPos(C_Parser.currentFile(), row, col)
    fun text(startSkip: Int, endSkip: Int) = str.substring(startPos + startSkip, pos - endSkip)

    fun tokenMatch(token: RellToken, text: String) = TokenMatch(token.token, text, startPos, startRow, startCol)

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
