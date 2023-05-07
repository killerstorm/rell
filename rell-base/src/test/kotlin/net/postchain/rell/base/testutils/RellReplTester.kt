/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.testutils

import net.postchain.rell.base.compiler.base.utils.C_Message
import net.postchain.rell.base.compiler.base.utils.C_SourceDir
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.repl.*
import net.postchain.rell.base.runtime.Rt_Exception
import net.postchain.rell.base.runtime.Rt_GlobalContext
import net.postchain.rell.base.runtime.Rt_Printer
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.sql.SqlManager
import java.io.File
import java.util.*
import kotlin.test.assertEquals

class RellReplTester(
    private val tstProjExt: RellTestProjExt,
    private val globalCtx: Rt_GlobalContext,
    private val sourceDir: C_SourceDir,
    private val sqlMgr: SqlManager,
    private val module: R_ModuleName?,
) {
    var outPlainValues = false

    private val outChannelFactory: TestReplOutputChannelFactory by lazy {
        TestReplOutputChannelFactory(outPlainValues)
    }

    private val outChannel: ReplOutputChannel by lazy {
        outChannelFactory.createOutputChannel()
    }

    private var repl: ReplInterpreter? = null

    fun chk(code: String, vararg expected: String) {
        if (repl == null) {
            val replGlobalCtx = Rt_GlobalContext(
                globalCtx.compilerOptions,
                outChannelFactory.outPrinter,
                outChannelFactory.logPrinter,
                logSqlErrors = globalCtx.logSqlErrors,
                sqlUpdatePortionSize = globalCtx.sqlUpdatePortionSize,
                typeCheck = globalCtx.typeCheck,
            )

            val blockRunnerFactory = tstProjExt.getReplInterpreterProjExt()

            val cOpts = RellTestUtils.DEFAULT_COMPILER_OPTIONS
            repl = ReplInterpreter.create(
                cOpts,
                sourceDir,
                module,
                replGlobalCtx,
                sqlMgr,
                blockRunnerFactory,
                outChannel,
            )
        }

        repl?.execute(code)
        outChannelFactory.chk(*expected)
    }

    class TestReplInputChannelFactory(input: List<String>): ReplInputChannelFactory() {
        private val queue: Queue<String> = LinkedList(input)
        private var end = false

        constructor(vararg input: String): this(input.toList())

        override fun createInputChannel(historyFile: File?): ReplInputChannel {
            return TestReplInputChannel()
        }

        private inner class TestReplInputChannel: ReplInputChannel {
            override fun readLine(prompt: String): String? {
                val res = queue.poll()
                if (res == null) {
                    check(!end)
                    end = true
                }
                return res
            }
        }
    }

    class TestReplOutputChannelFactory(
        private val outPlainValues: Boolean = false,
    ): ReplOutputChannelFactory() {
        private val output: Queue<String> = ArrayDeque()

        val outPrinter: Rt_Printer = Rt_ReplTestPrinter("OUT")
        val logPrinter: Rt_Printer = Rt_ReplTestPrinter("LOG")

        override fun createOutputChannel(): ReplOutputChannel {
            return TestReplOutputChannel()
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

        private inner class TestReplOutputChannel: ReplOutputChannel {
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
        }
    }
}
