/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.repl

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.utils.C_Parser
import net.postchain.rell.utils.CommonUtils
import org.apache.commons.lang3.StringUtils
import org.jline.reader.*
import org.jline.terminal.TerminalBuilder
import java.io.File

abstract class ReplInputChannelFactory {
    abstract fun createInputChannel(history: Boolean): ReplInputChannel

    companion object {
        val DEFAULT: ReplInputChannelFactory = JlineReplInputChannelFactory
    }
}

interface ReplInputChannel {
    fun readLine(prompt: String): String?
}

private object JlineReplInputChannelFactory: ReplInputChannelFactory() {
    override fun createInputChannel(history: Boolean): ReplInputChannel {
        val terminal = TerminalBuilder.builder()
            .dumb(true) // Suppress "dump terminal" warning, but use normal terminal if possible.
            .build()

        terminal.echo(false)

        val readerBuilder = LineReaderBuilder.builder()
            .terminal(terminal)
            .parser(RellJLineParser())
            .variable(LineReader.BLINK_MATCHING_PAREN, 0)
            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)

        val homeDir = CommonUtils.getHomeDir()
        if (homeDir != null) {
            readerBuilder.variable(LineReader.SECONDARY_PROMPT_PATTERN, "... ")
            if (history) {
                val historyFile = File(homeDir, ".rell_history")
                readerBuilder.variable(LineReader.HISTORY_FILE, historyFile)
                    .variable(LineReader.HISTORY_SIZE, 1000)
                    .variable(LineReader.HISTORY_FILE_SIZE, 100 * 1024)
            }
        }

        val reader = readerBuilder.build()
        return JlineReplInputChannel(reader)
    }
}

private class JlineReplInputChannel(private val reader: LineReader): ReplInputChannel {
    override fun readLine(prompt: String): String? {
        return try {
            reader.readLine(prompt)
        } catch (e: EndOfFileException) {
            null
        } catch (e: UserInterruptException) {
            return ""
        }
    }
}

// Using a custom parser, because:
// 1. To provide "secondary prompt" or "continuation" (when a valid, but incomplete input was typed).
// 2. Default parser removes "\" and does some other processing of the input.
private class RellJLineParser: Parser {
    override fun isEscapeChar(ch: Char) = false

    override fun parse(line: String, cursor: Int, context: Parser.ParseContext): ParsedLine {
        if (context == Parser.ParseContext.ACCEPT_LINE || context == Parser.ParseContext.SECONDARY_PROMPT) {
            val eof = eofPos(line)
            if (eof != null) {
                throw EOFError(eof.line(), eof.column(), "EOF")
            }
        }
        return RellJLineParsedLine(line, cursor)
    }

    private fun eofPos(line: String): S_Pos? {
        if (StringUtils.isBlank(line)) {
            return null
        }

        try {
            val err = C_Parser.checkEofErrorRepl(line)
            return err?.pos
        } catch (e: Throwable) {
            return null
        }
    }
}

private class RellJLineParsedLine(private val line: String, private val cursor: Int): CompletingParsedLine {
    override fun word() = line
    override fun wordCursor() = cursor
    override fun wordIndex() = 0
    override fun words() = listOf(line)
    override fun line() = line
    override fun cursor() = cursor
    override fun escape(candidate: CharSequence, complete: Boolean) = candidate
    override fun rawWordCursor() = cursor
    override fun rawWordLength() = line.length
}
