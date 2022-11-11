/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.test

import net.postchain.rell.compiler.base.utils.C_Message
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.module.RellPostchainModuleEnvironment
import net.postchain.rell.repl.ReplInterpreter
import net.postchain.rell.repl.ReplOutputChannel
import net.postchain.rell.repl.ReplValueFormat
import net.postchain.rell.repl.ReplValueFormatter
import net.postchain.rell.runtime.Rt_Exception
import net.postchain.rell.runtime.Rt_GlobalContext
import net.postchain.rell.runtime.Rt_Printer
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.sql.SqlManager
import java.util.*
import kotlin.test.assertEquals

class RellReplTester(
        globalCtx: Rt_GlobalContext,
        private val sourceDir: C_SourceDir,
        private val sqlMgr: SqlManager,
        private val module: R_ModuleName?,
        private val useSql: Boolean
) {
    var outPlainValues = false

    private val outChannel = TestReplOutputChannel()

    private val pcModuleEnv = RellPostchainModuleEnvironment(
            outPrinter = outChannel.outPrinter,
            logPrinter = outChannel.logPrinter,
            wrapCtErrors = false,
            wrapRtErrors = false,
            forceTypeCheck = true
    )

    private val replGlobalCtx = Rt_GlobalContext(
            globalCtx.compilerOptions,
            outChannel.outPrinter,
            outChannel.logPrinter,
            pcModuleEnv = pcModuleEnv,
            logSqlErrors = globalCtx.logSqlErrors,
            sqlUpdatePortionSize = globalCtx.sqlUpdatePortionSize,
            typeCheck = globalCtx.typeCheck
    )

    private var repl: ReplInterpreter? = null

    fun chk(code: String, vararg expected: String) {
        if (repl == null) {
            val cOpts = RellTestUtils.DEFAULT_COMPILER_OPTIONS
            repl = ReplInterpreter.create(cOpts, sourceDir, module, replGlobalCtx, sqlMgr, outChannel, useSql)
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

        override fun printRuntimeError(e: Rt_Exception) {
            val code = e.err.code()
            output.add(code)
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
                val s = value.strCode()
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
