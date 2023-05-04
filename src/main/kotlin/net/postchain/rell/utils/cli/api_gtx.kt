/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.utils.cli

import net.postchain.rell.compiler.base.core.C_CompilerOptions
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.lib.test.C_Lib_Test
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.runtime.Rt_LogPrinter
import net.postchain.rell.runtime.Rt_OutPrinter
import net.postchain.rell.runtime.Rt_Printer
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.sql.PostchainSqlInitProjExt
import net.postchain.rell.sql.SqlInitLogging
import net.postchain.rell.utils.*

class RellCliRunTestsConfig(
    /** Compilation config. */
    val compileConfig: RellCliCompileConfig,
    /** CLI environment used to print tests execution progress (test cases) and results. */
    val cliEnv: RellCliEnv,
    /** Stop tests after the first error. */
    val stopOnError: Boolean,
    /** Database URL. */
    val databaseUrl: String?,
    /** Enable SQL logging. */
    val sqlLog: Boolean,
    /** Enable SQL error logging. */
    val sqlErrorLog: Boolean,
    /** List of glob patterns to filter test cases: when not `null`, only tests matching one of the patterns will be executed. */
    val testPatterns: List<String>?,
    /** Printer used for Rell `print()` calls. */
    val outPrinter: Rt_Printer,
    /** Printer used for Rell `log()` calls. */
    val logPrinter: Rt_Printer,
    /** Print test case names and results during the execution. */
    val printTestCases: Boolean,
    /** Add dependencies of test modules to the active modules available during block execution (default: `true`). */
    val addTestDependenciesToBlockRunModules: Boolean,
    /** Test case start callback. */
    val onTestCaseStart: (UnitTestCase) -> Unit,
    /** Test case finished callback. */
    val onTestCaseFinished: (UnitTestCaseResult) -> Unit,
) {
    fun toBuilder() = Builder(this)

    companion object {
        val DEFAULT = RellCliRunTestsConfig(
            compileConfig = RellCliCompileConfig.DEFAULT,
            cliEnv = MainRellCliEnv,
            stopOnError = false,
            databaseUrl = null,
            sqlLog = false,
            sqlErrorLog = false,
            testPatterns = null,
            outPrinter = Rt_OutPrinter,
            logPrinter = Rt_LogPrinter(),
            printTestCases = true,
            addTestDependenciesToBlockRunModules = true,
            onTestCaseStart = {},
            onTestCaseFinished = {},
        )
    }

    class Builder(proto: RellCliRunTestsConfig = DEFAULT) {
        private var compileConfig = proto.compileConfig
        private var cliEnv = proto.cliEnv
        private var stopOnError = proto.stopOnError
        private var databaseUrl = proto.databaseUrl
        private var sqlLog = proto.sqlLog
        private var sqlErrorLog = proto.sqlErrorLog
        private var testPatterns = proto.testPatterns
        private var outPrinter = proto.outPrinter
        private var logPrinter = proto.logPrinter
        private var printTestCases = proto.printTestCases
        private var addTestDependenciesToBlockRunModules = proto.addTestDependenciesToBlockRunModules
        private var onTestCaseStart = proto.onTestCaseStart
        private var onTestCaseFinished = proto.onTestCaseFinished

        /** @see [RellCliRunTestsConfig.compileConfig] */
        fun compileConfig(v: RellCliCompileConfig) = apply { compileConfig = v }

        /** @see [RellCliRunTestsConfig.cliEnv] */
        fun cliEnv(v: RellCliEnv) = apply { cliEnv = v }

        /** @see [RellCliRunTestsConfig.stopOnError] */
        fun stopOnError(v: Boolean) = apply { stopOnError = v }

        /** @see [RellCliRunTestsConfig.databaseUrl] */
        fun databaseUrl(v: String?) = apply { databaseUrl = v }

        /** @see [RellCliRunTestsConfig.sqlLog] */
        fun sqlLog(v: Boolean) = apply { sqlLog = v }

        /** @see [RellCliRunTestsConfig.sqlErrorLog] */
        fun sqlErrorLog(v: Boolean) = apply { sqlErrorLog = v }

        /** @see [RellCliRunTestsConfig.testPatterns] */
        fun testPatterns(v: List<String>?) = apply { testPatterns = v?.toImmList() }

        /** @see [RellCliRunTestsConfig.outPrinter] */
        fun outPrinter(v: Rt_Printer) = apply { outPrinter = v }

        /** @see [RellCliRunTestsConfig.logPrinter] */
        fun logPrinter(v: Rt_Printer) = apply { logPrinter = v }

        /** @see [RellCliRunTestsConfig.printTestCases] */
        fun printTestCases(v: Boolean) = apply { printTestCases = v }

        /** @see [RellCliRunTestsConfig.addTestDependenciesToBlockRunModules]  */
        fun addTestDependenciesToBlockRunModules(v: Boolean) = apply { addTestDependenciesToBlockRunModules = v }

        /** @see [RellCliRunTestsConfig.onTestCaseStart] */
        fun onTestCaseStart(v: (UnitTestCase) -> Unit) = apply { onTestCaseStart = v }

        /** @see [RellCliRunTestsConfig.onTestCaseFinished] */
        fun onTestCaseFinished(v: (UnitTestCaseResult) -> Unit) = apply { onTestCaseFinished = v }

        fun build(): RellCliRunTestsConfig {
            return RellCliRunTestsConfig(
                compileConfig = compileConfig,
                cliEnv = cliEnv,
                stopOnError = stopOnError,
                databaseUrl = databaseUrl,
                sqlLog = sqlLog,
                sqlErrorLog = sqlErrorLog,
                testPatterns = testPatterns?.toImmList(),
                outPrinter = outPrinter,
                logPrinter = logPrinter,
                printTestCases = printTestCases,
                addTestDependenciesToBlockRunModules = addTestDependenciesToBlockRunModules,
                onTestCaseStart = onTestCaseStart,
                onTestCaseFinished = onTestCaseFinished,
            )
        }
    }
}

object RellCliInternalGtxApi {
    fun runTests(
        config: RellCliRunTestsConfig,
        options: C_CompilerOptions,
        sourceDir: C_SourceDir,
        app: R_App,
        appModules: List<R_ModuleName>?,
        moduleArgsRt: Map<R_ModuleName, Rt_Value>,
    ): UnitTestRunnerResults {
        val globalCtx = RellApiBaseUtils.createGlobalContext(
            options,
            typeCheck = false,
            outPrinter = config.outPrinter,
            logPrinter = config.logPrinter,
        )

        val blockRunner = createBlockRunner(config, sourceDir, app, appModules)

        val sqlCtx = RellApiBaseUtils.createSqlContext(app)
        val chainCtx = RellApiBaseUtils.createChainContext(moduleArgs = moduleArgsRt)

        val testMatcher = if (config.testPatterns == null) UnitTestMatcher.ANY else UnitTestMatcher.make(config.testPatterns)
        val testFns = UnitTestRunner.getTestFunctions(app, testMatcher)
        val testCases = testFns.map { UnitTestCase(null, it) }

        return RellApiGtxUtils.runWithSqlManager(
            dbUrl = config.databaseUrl,
            dbProperties = null,
            sqlLog = config.sqlLog,
            sqlErrorLog = config.sqlErrorLog,
        ) { sqlMgr ->
            val testCtx = UnitTestRunnerContext(
                app = app,
                printer = Rt_CliEnvPrinter(config.cliEnv),
                sqlCtx = sqlCtx,
                sqlMgr = sqlMgr,
                sqlInitProjExt = PostchainSqlInitProjExt,
                globalCtx = globalCtx,
                chainCtx = chainCtx,
                blockRunner = blockRunner,
                printTestCases = config.printTestCases,
                stopOnError = config.stopOnError,
                onTestCaseStart = config.onTestCaseStart,
                onTestCaseFinished = config.onTestCaseFinished,
            )

            val testRes = UnitTestRunnerResults()
            UnitTestRunner.runTests(testCtx, testCases, testRes)
            testRes
        }
    }

    private fun createBlockRunner(
        config: RellCliRunTestsConfig,
        sourceDir: C_SourceDir,
        app: R_App,
        appModules: List<R_ModuleName>?,
    ): Rt_UnitTestBlockRunner {
        val keyPair = C_Lib_Test.BLOCK_RUNNER_KEYPAIR

        val blockRunnerCfg = Rt_BlockRunnerConfig(
            forceTypeCheck = false,
            sqlLog = config.sqlLog,
            dbInitLogLevel = SqlInitLogging.LOG_NONE,
        )

        val mainModules = when {
            appModules == null -> null
            config.addTestDependenciesToBlockRunModules -> (appModules + RellApiBaseUtils.getMainModules(app)).toSet().toImmList()
            else -> appModules
        }

        val compileConfig = config.compileConfig
        val gtvCompileConfig = RellCliCompileConfig.Builder()
            .cliEnv(compileConfig.cliEnv)
            .version(compileConfig.version)
            .moduleArgs0(compileConfig.moduleArgs)
            .quiet(true)
            .build()

        val blockRunnerStrategy = Rt_DynamicBlockRunnerStrategy(sourceDir, keyPair, mainModules, gtvCompileConfig)
        return Rt_PostchainUnitTestBlockRunner(keyPair, blockRunnerCfg, blockRunnerStrategy)
    }
}
