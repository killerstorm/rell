/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.repl

import com.google.common.base.Throwables
import net.postchain.rell.compiler.base.core.C_CompilerOptions
import net.postchain.rell.compiler.base.utils.C_Message
import net.postchain.rell.compiler.base.utils.C_Parser
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.model.R_StackPos
import net.postchain.rell.runtime.*
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.sql.SqlManager
import net.postchain.rell.utils.CommonUtils
import org.apache.commons.lang3.StringUtils
import org.jline.reader.*
import org.jline.terminal.TerminalBuilder
import java.io.File

object ReplShell {
    fun start(
            sourceDir: C_SourceDir,
            moduleName: R_ModuleName?,
            globalCtx: Rt_GlobalContext,
            sqlMgr: SqlManager,
            useSql: Boolean,
            compilerOptions: C_CompilerOptions
    ) {
        val repl = ReplInterpreter.create(compilerOptions, sourceDir, moduleName, globalCtx, sqlMgr, CliReplOutputChannel, useSql)
        if (repl == null) {
            return
        }

        printIntro(repl, moduleName)

        val console = createConsole()

        while (!repl.mustQuit()) {
            val line = console.readLine(">>> ")
            if (line == null) {
                break
            } else if (!StringUtils.isBlank(line)) {
                repl.execute(line)
            }
        }
    }

    private fun printIntro(repl: ReplInterpreter, moduleName: R_ModuleName?) {
        val ver = getVersionInfo()
        println(ver)

        val quit = repl.getQuitCommand()
        val help = repl.getHelpCommand()
        println("Type '$quit' to quit or '$help' for help.")

        if (moduleName != null) {
            println("Current module: '$moduleName'")
        }
    }

    private fun getVersionInfo(): String {
        val v = Rt_RellVersion.getInstance()
        if (v == null) return "Version unknown"
        val ver = v.properties[Rt_RellVersionProperty.RELL_VERSION] ?: "[unknown version]"
        val time = v.properties[Rt_RellVersionProperty.RELL_BUILD_TIME] ?: "unknown time"
        return "Rell $ver ($time)"
    }

    private fun createConsole(): ReplConsole {
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
            val homeFile = File(homeDir, ".rell_history")
            readerBuilder
                    .variable(LineReader.HISTORY_FILE, homeFile)
                    .variable(LineReader.HISTORY_SIZE, 1000)
                    .variable(LineReader.HISTORY_FILE_SIZE, 100 * 1024)
                    .variable(LineReader.SECONDARY_PROMPT_PATTERN, "... ")
        }

        val reader = readerBuilder.build()
        return JlineReplConsole(reader)
    }
}

private object CliReplOutputChannel: ReplOutputChannel {
    private var valueFormat = ReplValueFormat.ONE_ITEM_PER_LINE

    override fun printCompilerError(code: String, msg: String) {
        println(msg)
    }

    override fun printCompilerMessage(message: C_Message) {
        println(message)
    }

    override fun printRuntimeError(e: Rt_BaseError, stack: List<R_StackPos>?) {
        val msg = "Run-time error: ${e.message}"
        val fullMsg = if (stack == null) msg else Rt_Utils.appendStackTrace(msg, stack)
        println(fullMsg.trim())
    }

    override fun printPlatformRuntimeError(e: Throwable) {
        val s = Throwables.getStackTraceAsString(e).trim()
        println("Run-time error: $s")
    }

    override fun setValueFormat(format: ReplValueFormat) {
        valueFormat = format
    }

    override fun printValue(value: Rt_Value) {
        val s = ReplValueFormatter.format(value, valueFormat)
        if (s != null) {
            println(s)
        }
    }

    override fun printControl(code: String, msg: String) {
        println(msg)
    }
}

private sealed class ReplConsole {
    abstract fun readLine(prompt: String): String?
}

private class JlineReplConsole(private val reader: LineReader): ReplConsole() {
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
