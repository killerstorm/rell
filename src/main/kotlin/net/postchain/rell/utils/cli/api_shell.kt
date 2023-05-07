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

object RellApiRunShell {
    /**
     * Start a REPL shell.
     *
     * @param config Configuration.
     * @param sourceDir Source directory.
     * @param module Current module: REPL commands will be executed in scope of that module; `null` means none.
     */
    fun runShell(
        config: Config,
        sourceDir: File,
        module: String?,
    ) {
        val cSourceDir = C_SourceDir.diskDir(sourceDir)
        val rModule = module?.let { R_ModuleName.of(it) }
        RellApiShellInternal.runShell(config, cSourceDir, rModule)
    }

    class Config(
        /** Compilation config. */
        val compileConfig: RellApiCompile.Config,
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
            val DEFAULT = Config(
                compileConfig = RellApiCompile.Config.DEFAULT,
                databaseUrl = null,
                sqlLog = false,
                sqlErrorLog = false,
                outPrinter = Rt_OutPrinter,
                logPrinter = Rt_LogPrinter(),
                historyFile = RellApiShellInternal.getDefaultReplHistoryFile(),
                inputChannelFactory = ReplIo.DEFAULT_INPUT_FACTORY,
                outputChannelFactory = ReplIo.DEFAULT_OUTPUT_FACTORY,
            )
        }

        class Builder(proto: Config = DEFAULT) {
            private var compileConfig = proto.compileConfig
            private var databaseUrl = proto.databaseUrl
            private var sqlLog = proto.sqlLog
            private var sqlErrorLog = proto.sqlErrorLog
            private var outPrinter = proto.outPrinter
            private var logPrinter = proto.logPrinter
            private var historyFile = proto.historyFile
            private var inputChannelFactory = proto.inputChannelFactory
            private var outputChannelFactory = proto.outputChannelFactory

            /** @see [Config.compileConfig] */
            fun compileConfig(v: RellApiCompile.Config) = apply { compileConfig = v }

            /** @see [Config.databaseUrl] */
            fun databaseUrl(v: String?) = apply { databaseUrl = v }

            /** @see [Config.sqlLog] */
            fun sqlLog(v: Boolean) = apply { sqlLog = v }

            /** @see [Config.sqlErrorLog] */
            fun sqlErrorLog(v: Boolean) = apply { sqlErrorLog = v }

            /** @see [Config.outPrinter] */
            fun outPrinter(v: Rt_Printer) = apply { outPrinter = v }

            /** @see [Config.logPrinter] */
            fun logPrinter(v: Rt_Printer) = apply { logPrinter = v }

            /** @see [Config.historyFile] */
            fun historyFile(v: File?) = apply { historyFile = v }

            /** @see [Config.inputChannelFactory] */
            fun inputChannelFactory(v: ReplInputChannelFactory) = apply { inputChannelFactory = v }

            /** @see [Config.outputChannelFactory] */
            fun outputChannelFactory(v: ReplOutputChannelFactory) = apply { outputChannelFactory = v }

            fun build(): Config {
                return Config(
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
}

object RellApiShellInternal {
    fun runShell(
        config: RellApiRunShell.Config,
        sourceDir: C_SourceDir,
        module: R_ModuleName?,
    ) {
        val options = RellApiBaseInternal.makeCompilerOptions(config.compileConfig)

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
