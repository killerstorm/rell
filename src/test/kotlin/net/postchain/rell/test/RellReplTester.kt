/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.test

import net.postchain.rell.compiler.C_MapSourceDir
import net.postchain.rell.compiler.C_Message
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.model.R_StackPos
import net.postchain.rell.repl.ReplInterpreter
import net.postchain.rell.repl.ReplOutputChannel
import net.postchain.rell.repl.ReplValueFormat
import net.postchain.rell.repl.ReplValueFormatter
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.SqlManager
import java.util.*
import kotlin.test.assertEquals

class RellReplTester(
        files: Map<String, String>,
        globalCtx: Rt_GlobalContext,
        private val sqlMgr: SqlManager,
        private val module: R_ModuleName?,
        private val useSql: Boolean
) {
    var outPlainValues = false

    private val outChannel = TestReplOutputChannel()
    private val sourceDir = C_MapSourceDir.of(files)

    private val replGlobalCtx = Rt_GlobalContext(
            outChannel.outPrinter,
            outChannel.logPrinter,
            globalCtx.opCtx,
            globalCtx.chainCtx,
            logSqlErrors = globalCtx.logSqlErrors,
            sqlUpdatePortionSize = globalCtx.sqlUpdatePortionSize,
            typeCheck = globalCtx.typeCheck
    )

    private var repl: ReplInterpreter? = null

    fun chk(code: String, vararg expected: String) {
        if (repl == null) {
            repl = ReplInterpreter.create(sourceDir, module, replGlobalCtx, sqlMgr, outChannel, useSql)
        }
        repl?.execute(code)
        outChannel.chk(*expected)
    }

    private inner class TestReplOutputChannel: ReplOutputChannel {
        val outPrinter = Rt_ReplTestPrinter("OUT")
        val logPrinter = Rt_ReplTestPrinter("LOG")

        private val output: Queue<String> = ArrayDeque()
        private var format = ReplValueFormat.DEFAULT

        override fun printCompilerError(code: String, msg: String) {
            output.add("CTE:$code")
        }

        override fun printCompilerMessage(message: C_Message) {
            val s = RellTestUtils.errToString(message.pos, message.code, false, true)
            output.add("CTE:$s")
        }

        override fun printRuntimeError(e: Rt_BaseError, stack: List<R_StackPos>?) {
            val e2 = if (e is Rt_StackTraceError) e.realCause else e
            val s = when (e2) {
                is Rt_Error -> e2.code
                is Rt_GtvError -> "gtv:${e2.code}"
                is Rt_RequireError -> "req:${e2.userMsg}"
                else -> throw RuntimeException("Unexpected exception", e2)
            }
            output.add("RTE:$s")
        }

        override fun printPlatformRuntimeError(e: Throwable) {
            throw e
        }

        override fun setValueFormat(format: ReplValueFormat) {
            this.format = format
        }

        override fun printValue(value: Rt_Value) {
            if (outPlainValues) {
                val s = ReplValueFormatter.format(value, format)
                output.add(s ?: "null")
            } else {
                val s = value.toStrictString()
                output.add("RES:$s")
            }
        }

        override fun printControl(code: String, msg: String) {
            output.add("CMD:$code")
        }

        fun chk(vararg expected: String) {
            val expStr = expected.toList().toString()
            val actStr = output.toList().toString()
            assertEquals(expStr, actStr)
            output.clear()
        }

        private inner class Rt_ReplTestPrinter(private val type: String): Rt_Printer {
            override fun print(str: String) {
                output.add("$type:$str")
            }
        }
    }
}
