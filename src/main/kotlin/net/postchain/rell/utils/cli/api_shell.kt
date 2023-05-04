/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.utils.cli

import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.module.RellPostchainModuleEnvironment
import net.postchain.rell.repl.*
import net.postchain.rell.runtime.Rt_LogPrinter
import net.postchain.rell.runtime.Rt_OutPrinter
import net.postchain.rell.runtime.Rt_Printer
import net.postchain.rell.sql.PostchainSqlInitProjExt
import net.postchain.rell.utils.CommonUtils
import net.postchain.rell.utils.Rt_BlockRunnerConfig
import java.io.File

class RellCliRunShellConfig(
    /** Compilation config. */
    val compileConfig: RellCliCompileConfig,
    /** Database URL. */
    val databaseUrl: String?,
    /** Enable SQL logging. */
    val sqlLog: Boolean,
    /** Enable SQL error logging. */
    val sqlErrorLog: Boolean,
    /** Printer used for Rell `print()` calls. */
    val outPrinter: Rt_Printer,
    /** Printer used for Rell `log()` calls. */
    val logPrinter: Rt_Printer,
    /** Shell commands history file, `null` means no history; default: `.rell_history` in the user's home directory. */
    val historyFile: File?,
    /** Input channel factory (used to read commands). */
    val inputChannelFactory: ReplInputChannelFactory,
    /** Output channel factory (used to print command execution results). */
    val outputChannelFactory: ReplOutputChannelFactory,
) {
    fun toBuilder() = Builder(this)

    companion object {
        val DEFAULT = RellCliRunShellConfig(
            compileConfig = RellCliCompileConfig.DEFAULT,
            databaseUrl = null,
            sqlLog = false,
            sqlErrorLog = false,
            outPrinter = Rt_OutPrinter,
            logPrinter = Rt_LogPrinter(),
            historyFile = RellCliInternalShellApi.getDefaultReplHistoryFile(),
            inputChannelFactory = ReplIo.DEFAULT_INPUT_FACTORY,
            outputChannelFactory = ReplIo.DEFAULT_OUTPUT_FACTORY,
        )
    }

    class Builder(proto: RellCliRunShellConfig = DEFAULT) {
        private var compileConfig = proto.compileConfig
        private var databaseUrl = proto.databaseUrl
        private var sqlLog = proto.sqlLog
        private var sqlErrorLog = proto.sqlErrorLog
        private var outPrinter = proto.outPrinter
        private var logPrinter = proto.logPrinter
        private var historyFile = proto.historyFile
        private var inputChannelFactory = proto.inputChannelFactory
        private var outputChannelFactory = proto.outputChannelFactory

        /** @see [RellCliRunShellConfig.compileConfig] */
        fun compileConfig(v: RellCliCompileConfig) = apply { compileConfig = v }

        /** @see [RellCliRunShellConfig.databaseUrl] */
        fun databaseUrl(v: String?) = apply { databaseUrl = v }

        /** @see [RellCliRunShellConfig.sqlLog] */
        fun sqlLog(v: Boolean) = apply { sqlLog = v }

        /** @see [RellCliRunShellConfig.sqlErrorLog] */
        fun sqlErrorLog(v: Boolean) = apply { sqlErrorLog = v }

        /** @see [RellCliRunShellConfig.outPrinter] */
        fun outPrinter(v: Rt_Printer) = apply { outPrinter = v }

        /** @see [RellCliRunShellConfig.logPrinter] */
        fun logPrinter(v: Rt_Printer) = apply { logPrinter = v }

        /** @see [RellCliRunShellConfig.historyFile] */
        fun historyFile(v: File?) = apply { historyFile = v }

        /** @see [RellCliRunShellConfig.inputChannelFactory] */
        fun inputChannelFactory(v: ReplInputChannelFactory) = apply { inputChannelFactory = v }

        /** @see [RellCliRunShellConfig.outputChannelFactory] */
        fun outputChannelFactory(v: ReplOutputChannelFactory) = apply { outputChannelFactory = v }

        fun build(): RellCliRunShellConfig {
            return RellCliRunShellConfig(
                compileConfig = compileConfig,
                databaseUrl = databaseUrl,
                sqlLog = sqlLog,
                sqlErrorLog = sqlErrorLog,
                outPrinter = outPrinter,
                logPrinter = logPrinter,
                historyFile = historyFile,
                inputChannelFactory = inputChannelFactory,
                outputChannelFactory = outputChannelFactory,
            )
        }
    }
}

object RellCliInternalShellApi {
    fun runShell(
        config: RellCliRunShellConfig,
        sourceDir: C_SourceDir,
        module: R_ModuleName?,
    ) {
        val options = RellCliInternalBaseApi.makeCompilerOptions(config.compileConfig)

        val globalCtx = RellApiBaseUtils.createGlobalContext(
            options,
            typeCheck = false,
            outPrinter = config.outPrinter,
            logPrinter = config.logPrinter,
        )

        val blockRunnerCfg = Rt_BlockRunnerConfig(
            forceTypeCheck = false,
            sqlLog = config.sqlLog,
            dbInitLogLevel = RellPostchainModuleEnvironment.DEFAULT_DB_INIT_LOG_LEVEL,
        )
        val projExt = PostchainReplInterpreterProjExt(PostchainSqlInitProjExt, blockRunnerCfg)

        RellApiGtxUtils.runWithSqlManager(
            dbUrl = config.databaseUrl,
            dbProperties = null,
            sqlLog = config.sqlLog,
            sqlErrorLog = config.sqlErrorLog,
        ) { sqlMgr ->
            ReplShell.start(
                sourceDir,
                module,
                globalCtx,
                sqlMgr,
                options,
                projExt,
                config.inputChannelFactory,
                config.outputChannelFactory,
                historyFile = config.historyFile,
            )
        }
    }

    fun getDefaultReplHistoryFile(): File? {
        val homeDir = CommonUtils.getHomeDir()
        return if (homeDir == null) null else File(homeDir, ".rell_history")
    }
}
