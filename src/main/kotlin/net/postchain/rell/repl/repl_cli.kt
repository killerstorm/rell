/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.repl

import net.postchain.rell.compiler.base.core.C_CompilerOptions
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.lib.test.Rt_BlockRunnerConfig
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.runtime.Rt_GlobalContext
import net.postchain.rell.runtime.Rt_RellVersion
import net.postchain.rell.runtime.Rt_RellVersionProperty
import net.postchain.rell.sql.SqlManager
import org.apache.commons.lang3.StringUtils

object ReplShell {
    fun start(
        sourceDir: C_SourceDir,
        module: R_ModuleName?,
        globalCtx: Rt_GlobalContext,
        sqlMgr: SqlManager,
        compilerOptions: C_CompilerOptions,
        testBlockRunnerCfg: Rt_BlockRunnerConfig,
        inChannelFactory: ReplInputChannelFactory,
        outChannelFactory: ReplOutputChannelFactory,
        historyEnabled: Boolean = true,
    ) {
        val outChannel = outChannelFactory.createOutputChannel()

        val repl = ReplInterpreter.create(
            compilerOptions,
            sourceDir,
            module,
            globalCtx,
            testBlockRunnerCfg,
            sqlMgr,
            outChannel,
        )

        if (repl == null) {
            return
        }

        printIntro(repl, module)

        val inChannel = inChannelFactory.createInputChannel(historyEnabled)

        while (!repl.mustQuit()) {
            val line = inChannel.readLine(">>> ")
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
        return "Rell $ver"
    }
}
