/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.repl

import net.postchain.rell.CommonUtils
import net.postchain.rell.compiler.*
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.SqlInit
import net.postchain.rell.sql.SqlInitLogging
import net.postchain.rell.sql.SqlManager
import net.postchain.rell.toImmList
import net.postchain.rell.toImmMap

class C_ReplCodeState(val frameProto: C_CallFrameProto, val blockCodeProto: C_BlockCodeProto) {
    companion object { val EMPTY = C_ReplCodeState(C_CallFrameProto.EMPTY, C_BlockCodeProto.EMPTY) }
}

class Rt_ReplCodeState(val frameState: Rt_CallFrameState) {
    companion object { val EMPTY = Rt_ReplCodeState(Rt_CallFrameState.EMPTY) }
}

class ReplCodeState(val cState: C_ReplCodeState, val rtState: Rt_ReplCodeState) {
    companion object { val EMPTY = ReplCodeState(C_ReplCodeState.EMPTY, Rt_ReplCodeState.EMPTY) }
}

class ReplCode(
        private val rCode: R_ReplCode,
        private val newCtState: C_ReplCodeState,
        private val oldRtState: Rt_ReplCodeState
) {
    fun execute(exeCtx: Rt_ExecutionContext): ReplCodeState {
        val newRtState = rCode.execute(exeCtx, oldRtState)
        return ReplCodeState(newCtState, newRtState)
    }

    companion object {
        val ERROR = ReplCode(R_ReplCode(R_CallFrame.ERROR, listOf()), C_ReplCodeState.EMPTY, Rt_ReplCodeState.EMPTY)
    }
}

class C_ReplCommandContext(val frameCtx: C_FrameContext, val codeState: ReplCodeState) {
    val fnCtx = frameCtx.fnCtx
    val defCtx = fnCtx.defCtx
    val mntCtx = defCtx.mntCtx
    val nsCtx = mntCtx.nsCtx
    val executor = nsCtx.executor

    init {
        check(mntCtx.nsCtx === frameCtx.fnCtx.nsCtx)
    }

    private val commandLate = C_LateInit(C_CompilerPass.EXPRESSIONS, ReplCode.ERROR)
    val commandGetter = commandLate.getter

    fun setCommand(code: ReplCode) = commandLate.set(code)
}

class R_ReplCode(private val frame: R_CallFrame, stmts: List<R_Statement>) {
    private val stmts = stmts.toImmList()

    fun execute(exeCtx: Rt_ExecutionContext, oldState: Rt_ReplCodeState): Rt_ReplCodeState {
        val rtDefCtx = Rt_DefinitionContext(exeCtx, false, R_DefinitionPos("", "<console>"))
        val rtFrame = frame.createRtFrame(rtDefCtx, null, oldState.frameState)

        R_BlockStatement.executeStatements(rtFrame, stmts)

        val newFrameState = rtFrame.dumpState()
        return Rt_ReplCodeState(newFrameState)
    }
}

class ReplInterpreter private constructor(
        private val sourceDir: C_SourceDir,
        private val module: R_ModuleName?,
        private val rtGlobalCtx: Rt_GlobalContext,
        private val sqlMgr: SqlManager,
        private val outChannel: ReplOutputChannel,
        private val useSql: Boolean
) {
    private val commands = ControlCommands()
    private val cGlobalCtx = C_GlobalContext(C_CompilerOptions.DEFAULT)

    private var defsState = C_ReplDefsState.EMPTY
    private var codeState = ReplCodeState.EMPTY
    private var lastUpdateSqlDefs = R_AppSqlDefs.EMPTY

    private var mustQuit = false
    private var sqlUpdateAuto = false

    fun mustQuit() = mustQuit

    fun getHelpCommand() = commands.helpCmd
    fun getQuitCommand() = commands.quitCmd

    fun execute(command: String) {
        val trim = command.trim()
        if (trim.startsWith("\\")) {
            val ctrl = commands.map[trim]
            if (ctrl != null) {
                ctrl.action()
            } else {
                outChannel.printCompilerError("repl:invalid_command:$trim", "Invalid command: '$trim'")
            }
        } else {
            executeCode(command, false)
        }
    }

    private fun executeCode(code: String, forceSqlUpdate: Boolean): Boolean {
        val success = compile(code)
        if (success == null) {
            return false
        }

        executeCatch {
            val rtAppCtx = createRtAppContext(rtGlobalCtx, success.app)
            sqlUpdate(rtAppCtx, forceSqlUpdate)

            sqlMgr.access { sqlExec ->
                val exeCtx = Rt_ExecutionContext(rtAppCtx, sqlExec)
                codeState = success.code.execute(exeCtx)
                defsState = success.defsState
            }
        }

        return true
    }

    private fun compile(code: String): C_ReplSuccess? {
        val cRes = try {
            C_ReplCompiler.compile(sourceDir, module, code, cGlobalCtx, defsState, codeState)
        } catch (e: C_CommonError) {
            outChannel.printCompilerError(e.code, e.msg)
            return null
        }

        for (message in cRes.messages) {
            outChannel.printCompilerMessage(message)
        }

        return cRes.success
    }

    private fun executeCatch(code: () -> Unit) {
        try {
            code()
        } catch (e: Rt_StackTraceError) {
            outChannel.printRuntimeError(e, e.stack)
        } catch (e: Rt_BaseError) {
            outChannel.printRuntimeError(e, null)
        } catch (e: Throwable) {
            outChannel.printPlatformRuntimeError(e)
        }
    }

    private fun createRtAppContext(globalCtx: Rt_GlobalContext, app: R_App): Rt_AppContext {
        val sqlCtx = Rt_SqlContext.createNoExternalChains(app, Rt_ChainSqlMapping(0))
        return Rt_AppContext(globalCtx, sqlCtx, app, outChannel)
    }

    private fun sqlUpdate(appCtx: Rt_AppContext, force: Boolean) {
        if (!sqlUpdateAuto && !force) {
            return
        }

        if (useSql && !appCtx.sqlCtx.appDefs.same(lastUpdateSqlDefs)) {
            val logging = if (force) SQL_INIT_LOGGING_FORCE else SQL_INIT_LOGGING_AUTO
            sqlMgr.transaction { sqlExec ->
                val exeCtx = Rt_ExecutionContext(appCtx, sqlExec)
                SqlInit.init(exeCtx, logging)
            }
            lastUpdateSqlDefs = appCtx.sqlCtx.appDefs
        }
    }

    private inner class ControlCommands {
        private fun dbUpdate() {
            executeCode("", true)
        }

        private fun dbAuto() {
            val v = !sqlUpdateAuto
            sqlUpdateAuto = v
            val s = if (v) "on" else "off"
            outChannel.printControl("db-auto:$v", "SQL auto-update is $s")
            if (v) {
                dbUpdate()
            }
        }

        private fun help() {
            val table = mutableListOf<List<String>>()
            val aliases = mutableMapOf<Ctrl, String>()

            for (cmd in map.keys.sorted()) {
                val ctrl = map.getValue(cmd)
                val alias = aliases[ctrl]
                var help = if (alias == null) ctrl.help else "Same as '$alias'."
                aliases.putIfAbsent(ctrl, cmd)
                table.add(listOf(cmd, help))
            }

            val tableList = CommonUtils.tableToStrings(table)
            val str = "List of all control commands:\n" + tableList.joinToString("\n")
            rtGlobalCtx.outPrinter.print(str)
        }

        private fun exit() {
            mustQuit = true
        }

        private val dbUpdate = Ctrl("Update SQL tables to match defined entities and objects.") { dbUpdate() }
        private val dbAuto = Ctrl("Automatically update SQL tables when new entities or objects are defined.") { dbAuto() }
        private val help = Ctrl("Display this help.") { help() }
        private val exit = Ctrl("Exit.") { exit() }

        private fun formatCtrl(msg: String, format: ReplValueFormat) = Ctrl("Output values as $msg") {
            outChannel.setValueFormat(format)
        }

        private val rawHelpCmd = "?"
        private val rawQuitCmd = "q"

        val helpCmd = fullCmd(rawHelpCmd)
        val quitCmd = fullCmd(rawQuitCmd)

        private val rawMap = mapOf(
                rawHelpCmd to help,
                "exit" to exit,
                rawQuitCmd to exit,
                "db-update" to dbUpdate,
                "db-auto" to dbAuto,
                "oj" to formatCtrl("JSON (Gtv)", ReplValueFormat.GTV_JSON),
                "ox" to formatCtrl("XML (Gtv)", ReplValueFormat.GTV_XML),
                "ol" to formatCtrl("one collection item per line", ReplValueFormat.ONE_ITEM_PER_LINE),
                "od" to formatCtrl("default text representation (result of to_text())", ReplValueFormat.DEFAULT),
                "os" to formatCtrl("\"strict\" text representation", ReplValueFormat.STRICT)
        )

        val map = rawMap.mapKeys { (k, _) -> fullCmd(k) }.toImmMap()

        private fun fullCmd(rawCmd: String) = "\\$rawCmd"
    }

    private class Ctrl(val help: String, val action: () -> Unit)

    companion object {
        private val SQL_INIT_LOGGING_AUTO = SqlInitLogging(step = true, stepEmptyDb = true)

        private val SQL_INIT_LOGGING_FORCE = SqlInitLogging(
                header = true,
                step = true,
                stepEmptyDb = true,
                metaNoCode = true
        )

        fun create(
                sourceDir: C_SourceDir,
                module: R_ModuleName?,
                rtGlobalCtx: Rt_GlobalContext,
                sqlMgr: SqlManager,
                outChannel: ReplOutputChannel,
                useSql: Boolean
        ): ReplInterpreter? {
            val interpreter = ReplInterpreter(sourceDir, module, rtGlobalCtx, sqlMgr, outChannel, useSql)
            val init = interpreter.executeCode("", false) // Make sure the module can be found and has no errors.
            return if (init) interpreter else null
        }
    }
}