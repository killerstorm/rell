/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.utils.cli

import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory
import net.postchain.rell.RellConfigGen
import net.postchain.rell.compiler.base.core.C_CompilationResult
import net.postchain.rell.compiler.base.core.C_Compiler
import net.postchain.rell.compiler.base.core.C_CompilerModuleSelection
import net.postchain.rell.compiler.base.core.C_CompilerOptions
import net.postchain.rell.compiler.base.utils.C_CommonError
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.compiler.base.utils.C_SourcePath
import net.postchain.rell.lib.test.Rt_BlockRunnerConfig
import net.postchain.rell.lib.test.Rt_BlockRunnerStrategy
import net.postchain.rell.lib.test.Rt_DynamicBlockRunnerStrategy
import net.postchain.rell.lib.test.UnitTestBlockRunner
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_LangVersion
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.module.ConfigConstants
import net.postchain.rell.module.RellPostchainModuleApp
import net.postchain.rell.module.RellPostchainModuleEnvironment
import net.postchain.rell.module.RellVersions
import net.postchain.rell.repl.ReplInputChannelFactory
import net.postchain.rell.repl.ReplOutputChannelFactory
import net.postchain.rell.repl.ReplShell
import net.postchain.rell.runtime.Rt_LogPrinter
import net.postchain.rell.runtime.Rt_OutPrinter
import net.postchain.rell.runtime.Rt_Printer
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.sql.SqlInitLogging
import net.postchain.rell.utils.*
import java.io.File

class RellCliCompileConfig(
    /** CLI environment used to print compilation messages and errors. */
    val cliEnv: RellCliEnv,
    /** Language version for backward compatibility (may affect some aspects of compilation). */
    val version: R_LangVersion,
    /** Module arguments. */
    val moduleArgs: Map<R_ModuleName, Gtv>,
    /** Submodules of all test modules are compiled in addition to the explicitly specified test modules, when `true`. */
    val includeTestSubModules: Boolean,
    /** Missing module arguments for a module (which defines `module_args`) causes compilation error, when `true`. */
    val moduleArgsMissingError: Boolean,
    /** Mount name conflicts cause compilation error, when `true`. */
    val mountConflictError: Boolean,
    /** Specifying a non-test module in the list of test modules causes an error, when `true`. */
    val appModuleInTestsError: Boolean,
    /** Do not print non-error compilation messages (warnings) if compilation succeeds, when `true`. */
    val quiet: Boolean,
) {
    fun toBuilder() = Builder(this)

    companion object {
        val DEFAULT = RellCliCompileConfig(
            cliEnv = MainRellCliEnv,
            version = RellVersions.VERSION,
            moduleArgs = immMapOf(),
            includeTestSubModules = true,
            moduleArgsMissingError = true,
            mountConflictError = true,
            appModuleInTestsError = true,
            quiet = false,
        )
    }

    class Builder(proto: RellCliCompileConfig = DEFAULT) {
        private var cliEnv = proto.cliEnv
        private var version = proto.version
        private var moduleArgs = proto.moduleArgs
        private var includeTestSubModules = proto.includeTestSubModules
        private var moduleArgsMissingError = proto.moduleArgsMissingError
        private var mountConflictError = proto.mountConflictError
        private var appModuleInTestsError = proto.appModuleInTestsError
        private var quiet = proto.quiet

        /** @see [RellCliCompileConfig.cliEnv] */
        fun cliEnv(v: RellCliEnv) = apply { cliEnv = v }

        /** @see [RellCliCompileConfig.version] */
        fun version(v: String) = apply { version = R_LangVersion.of(v) }

        /** @see [RellCliCompileConfig.version] */
        fun version(v: R_LangVersion) = apply { version = v }

        /** @see [RellCliCompileConfig.moduleArgs] */
        fun moduleArgs(v: Map<String, Map<String, Gtv>>) = moduleArgs0(
            v.map { R_ModuleName.of(it.key) to GtvFactory.gtv(it.value) }.toImmMap()
        )

        /** @see [RellCliCompileConfig.moduleArgs] */
        fun moduleArgs(vararg v: Pair<String, Map<String, Gtv>>) = moduleArgs(v.toMap())

        /** @see [RellCliCompileConfig.moduleArgs] */
        fun moduleArgs0(v: Map<R_ModuleName, Gtv>) = apply { moduleArgs = v.toImmMap() }

        /** @see [RellCliCompileConfig.includeTestSubModules] */
        fun includeTestSubModules(v: Boolean) = apply { includeTestSubModules = v }

        /** @see [RellCliCompileConfig.moduleArgsMissingError] */
        fun moduleArgsMissingError(v: Boolean) = apply { moduleArgsMissingError = v }

        /** @see [RellCliCompileConfig.mountConflictError] */
        fun mountConflictError(v: Boolean) = apply { mountConflictError = v }

        /** @see [RellCliCompileConfig.appModuleInTestsError] */
        fun appModuleInTestsError(v: Boolean) = apply { appModuleInTestsError = v }

        /** @see [RellCliCompileConfig.quiet] */
        fun quiet(v: Boolean) = apply { quiet = v }

        fun build(): RellCliCompileConfig {
            return RellCliCompileConfig(
                cliEnv = cliEnv,
                version = version,
                moduleArgs = moduleArgs,
                includeTestSubModules = includeTestSubModules,
                moduleArgsMissingError = moduleArgsMissingError,
                mountConflictError = mountConflictError,
                appModuleInTestsError = appModuleInTestsError,
                quiet = quiet,
            )
        }
    }
}

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
    val onTestCaseStart: (TestCase) -> Unit,
    /** Test case finished callback. */
    val onTestCaseFinished: (TestCaseResult) -> Unit,
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
        fun onTestCaseStart(v: (TestCase) -> Unit) = apply { onTestCaseStart = v }

        /** @see [RellCliRunTestsConfig.onTestCaseFinished] */
        fun onTestCaseFinished(v: (TestCaseResult) -> Unit) = apply { onTestCaseFinished = v }

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
            historyFile = RellCliInternalApi.getDefaultReplHistoryFile(),
            inputChannelFactory = ReplInputChannelFactory.DEFAULT,
            outputChannelFactory = ReplOutputChannelFactory.DEFAULT,
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

@Suppress("unused")
object RellCliApi {
    /**
     * Compile an app.
     *
     * Use-case 1: compile an app same way as **`multirun`** or **`multigen`**. Specify a single app module and no
     * test modules (a test module may add other app modules to the active set).
     *
     * Use-case 2: compile all app modules. Specify `null` as the list of app modules.
     *
     * Use-case 3: compile all app modules and all test modules. Pass `null` for the list of app modules and the *root*
     * module (`""`) in the list of test modules; [config.testSubModules][RellCliCompileConfig.includeTestSubModules]
     * must be `true`.
     *
     * @param config Compile config.
     * @param sourceDir Source directory.
     * @param appModules List of app (non-test) modules. Empty means none, `null` means all.
     * @param testModules List of test modules. Empty means none.
     * Can contain also app modules, if [config.testSubModules][RellCliCompileConfig.includeTestSubModules] is `true`.
     */
    fun compileApp(
        config: RellCliCompileConfig,
        sourceDir: File,
        appModules: List<String>?,
        testModules: List<String> = immListOf(),
    ): R_App {
        val cSourceDir = C_SourceDir.diskDir(sourceDir)
        val rAppModules = appModules?.map { R_ModuleName.of(it) }?.toImmList()
        val rTestModules = testModules.map { R_ModuleName.of(it) }.toImmList()

        val options = RellCliInternalApi.makeCompilerOptions(config)
        val (_, rApp) = RellCliInternalApi.compileApp(config, options, cSourceDir, rAppModules, rTestModules)
        return rApp
    }

    /**
     * Compiles an app, returns a `Gtv`. The returned value is the `Gtv` node to be put at the path `gtx.rell` in a
     * blockchain configuration.
     */
    fun compileGtv(
        config: RellCliCompileConfig,
        sourceDir: File,
        mainModule: String,
    ): Gtv {
        val cSourceDir = C_SourceDir.diskDir(sourceDir)
        val rMainModule = R_ModuleName.of(mainModule)
        return RellCliInternalApi.compileGtv(config, cSourceDir, immListOf(rMainModule))
    }

    /**
     * Run tests.
     *
     * Use-case 1: run tests same way as **`multirun`** does. Set [appModules] to the app's main module, add the
     * main module to [testModules], set [config.testSubModules][RellCliCompileConfig.includeTestSubModules] to `true`.
     *
     * Use-case 2: run all tests. Add the *root* module (`""`) to [testModules],
     * set [config.testSubModules][RellCliCompileConfig.includeTestSubModules] to `true`.
     *
     * @param config Tests run config.
     * @param sourceDir Source directory.
     * @param appModules List of app modules. Empty means none, `null` means all. Defines active modules for blocks
     * execution (tests can execute only operations defined in active modules).
     * @param testModules List of test modules to run. Empty means none. Can contain also app modules, if
     * [config.testSubModules][RellCliCompileConfig.includeTestSubModules] is `true`.
     */
    fun runTests(
        config: RellCliRunTestsConfig,
        sourceDir: File,
        appModules: List<String>?,
        testModules: List<String>,
    ): TestRunnerResults {
        val cSourceDir = C_SourceDir.diskDir(sourceDir)
        val rAppModules = appModules?.map { R_ModuleName.of(it) }?.toImmList()
        val rTestModules = testModules.map { R_ModuleName.of(it) }.toImmList()

        val compileConfig = config.compileConfig
        val options = RellCliInternalApi.makeCompilerOptions(compileConfig)
        val (cRes, app) = RellCliInternalApi.compileApp(compileConfig, options, cSourceDir, rAppModules, rTestModules)
        return RellCliInternalApi.runTests(config, options, cSourceDir, app, rAppModules, cRes.moduleArgs)
    }

    /**
     * Start a REPL shell.
     *
     * @param config Run config.
     * @param sourceDir Source directory.
     * @param module Current module: REPL commands will be executed in scope of that module; `null` means none.
     */
    fun runShell(
        config: RellCliRunShellConfig,
        sourceDir: File,
        module: String?,
    ) {
        val cSourceDir = C_SourceDir.diskDir(sourceDir)
        val rModule = module?.let { R_ModuleName.of(it) }
        RellCliInternalApi.runShell(config, cSourceDir, rModule)
    }
}

internal class RellCliCompilationResult(
    val cRes: C_CompilationResult,
    val moduleArgs: Map<R_ModuleName, Rt_Value>,
)

internal object RellCliInternalApi {
    fun compileApp(
        config: RellCliCompileConfig,
        options: C_CompilerOptions,
        sourceDir: C_SourceDir,
        appModules: List<R_ModuleName>?,
        testModules: List<R_ModuleName>,
    ): Pair<RellCliCompilationResult, R_App> {
        return wrapCompilation(config) {
            compileApp0(config, options, sourceDir, appModules, testModules)
        }
    }

    fun compileApp0(
        config: RellCliCompileConfig,
        options: C_CompilerOptions,
        sourceDir: C_SourceDir,
        appModules: List<R_ModuleName>?,
        testModules: List<R_ModuleName>,
    ): RellCliCompilationResult {
        val modSel = makeCompilerModuleSelection(config, appModules, testModules)
        val cRes = C_Compiler.compile(sourceDir, modSel, options)

        val moduleArgs = if (cRes.app != null && cRes.errors.isEmpty()) {
            processModuleArgs(cRes.app, config.moduleArgs, config.moduleArgsMissingError)
        } else {
            immMapOf()
        }

        return RellCliCompilationResult(cRes, moduleArgs)
    }

    fun compileGtv(
        config: RellCliCompileConfig,
        sourceDir: C_SourceDir,
        modules: List<R_ModuleName>?,
    ): Gtv {
        val (gtv, _) = compileGtvEx(config, sourceDir, modules)
        return gtv
    }

    fun compileGtvEx(
        config: RellCliCompileConfig,
        sourceDir: C_SourceDir,
        modules: List<R_ModuleName>?,
    ): Pair<Gtv, RellPostchainModuleApp> {
        val options = makeCompilerOptions(config)
        val (apiRes, rApp) = compileApp(config, options, sourceDir, modules, immListOf())

        val mainModules = modules ?: RellCliUtils.getMainModules(rApp)

        val gtv = catchCommonError {
            compileGtv0(config, sourceDir, mainModules, apiRes.cRes.files)
        }

        return gtv to RellPostchainModuleApp(rApp, options)
    }

    fun compileGtv0(
        config: RellCliCompileConfig,
        sourceDir: C_SourceDir,
        modules: List<R_ModuleName>,
        files: List<C_SourcePath>,
    ): Gtv {
        val sources = RellConfigGen.getModuleFiles(sourceDir, files)

        val map = mutableMapOf(
            "modules" to GtvFactory.gtv(modules.map { GtvFactory.gtv(it.str()) }),
            ConfigConstants.RELL_SOURCES_KEY to GtvFactory.gtv(sources.mapValues { (_, v) -> GtvFactory.gtv(v) }),
            ConfigConstants.RELL_VERSION_KEY to GtvFactory.gtv(config.version.str()),
        )

        val moduleArgs = config.moduleArgs
        if (moduleArgs.isNotEmpty()) {
            val argsGtv = GtvFactory.gtv(moduleArgs.mapKeys { (k, _) -> k.str() })
            map["moduleArgs"] = argsGtv
        }

        return GtvFactory.gtv(map.toImmMap())
    }

    fun runTests(
        config: RellCliRunTestsConfig,
        options: C_CompilerOptions,
        sourceDir: C_SourceDir,
        app: R_App,
        appModules: List<R_ModuleName>?,
        moduleArgsRt: Map<R_ModuleName, Rt_Value>,
    ): TestRunnerResults {
        val globalCtx = RellCliUtils.createGlobalContext(
            options,
            typeCheck = false,
            outPrinter = config.outPrinter,
            logPrinter = config.logPrinter,
        )

        val blockRunnerCfg = Rt_BlockRunnerConfig(
            forceTypeCheck = false,
            sqlLog = config.sqlLog,
            dbInitLogLevel = SqlInitLogging.LOG_NONE,
        )

        val sqlCtx = RellCliUtils.createSqlContext(app)
        val chainCtx = RellCliUtils.createChainContext(moduleArgs = moduleArgsRt)
        val blockRunnerStrategy = createBlockRunnerStrategy(config, sourceDir, app, appModules)

        val testMatcher = if (config.testPatterns == null) TestMatcher.ANY else TestMatcher.make(config.testPatterns)
        val testFns = TestRunner.getTestFunctions(app, testMatcher)
        val testCases = testFns.map { TestCase(null, it) }

        return RellCliUtils.runWithSqlManager(
            dbUrl = config.databaseUrl,
            dbProperties = null,
            sqlLog = config.sqlLog,
            sqlErrorLog = config.sqlErrorLog,
        ) { sqlMgr ->
            val testCtx = TestRunnerContext(
                app = app,
                cliEnv = config.cliEnv,
                sqlCtx = sqlCtx,
                sqlMgr = sqlMgr,
                globalCtx = globalCtx,
                chainCtx = chainCtx,
                blockRunnerConfig = blockRunnerCfg,
                blockRunnerStrategy = blockRunnerStrategy,
                printTestCases = config.printTestCases,
                stopOnError = config.stopOnError,
                onTestCaseStart = config.onTestCaseStart,
                onTestCaseFinished = config.onTestCaseFinished,
            )

            val testRes = TestRunnerResults()
            TestRunner.runTests(testCtx, testCases, testRes)
            testRes
        }
    }

    private fun createBlockRunnerStrategy(
        config: RellCliRunTestsConfig,
        sourceDir: C_SourceDir,
        app: R_App,
        appModules: List<R_ModuleName>?,
    ): Rt_BlockRunnerStrategy {
        val mainModules = when {
            appModules == null -> null
            config.addTestDependenciesToBlockRunModules -> (appModules + RellCliUtils.getMainModules(app)).toSet().toImmList()
            else -> appModules
        }

        val keyPair = UnitTestBlockRunner.getTestKeyPair()

        val compileConfig = config.compileConfig
        val gtvCompileConfig = RellCliCompileConfig.Builder()
            .cliEnv(compileConfig.cliEnv)
            .version(compileConfig.version)
            .moduleArgs0(compileConfig.moduleArgs)
            .quiet(true)
            .build()

        return Rt_DynamicBlockRunnerStrategy(sourceDir, keyPair, mainModules, gtvCompileConfig)
    }

    fun runShell(
        config: RellCliRunShellConfig,
        sourceDir: C_SourceDir,
        module: R_ModuleName?,
    ) {
        val options = makeCompilerOptions(config.compileConfig)

        val globalCtx = RellCliUtils.createGlobalContext(
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

        RellCliUtils.runWithSqlManager(
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
                blockRunnerCfg,
                config.inputChannelFactory,
                config.outputChannelFactory,
                historyFile = config.historyFile,
            )
        }
    }

    fun makeCompilerOptions(config: RellCliCompileConfig): C_CompilerOptions {
        return C_CompilerOptions.DEFAULT.toBuilder()
            .compatibility(config.version)
            .mountConflictError(config.mountConflictError)
            .appModuleInTestsError(config.appModuleInTestsError)
            .build()
    }

    fun makeCompilerModuleSelection(
        config: RellCliCompileConfig,
        appModules: List<R_ModuleName>?,
        testModules: List<R_ModuleName>,
    ): C_CompilerModuleSelection {
        return C_CompilerModuleSelection(appModules, testModules, testSubModules = config.includeTestSubModules)
    }

    private fun processModuleArgs(
        app: R_App,
        actualArgs: Map<R_ModuleName, Gtv>,
        missingError: Boolean,
    ): Map<R_ModuleName, Rt_Value> {
        val expectedArgs = app.moduleMap
            .filterValues { it.moduleArgs != null }
            .mapValues { it.value.moduleArgs!! }
            .toImmMap()

        val missingModules = expectedArgs.keys.filter { it !in actualArgs }.sorted().toImmList()
        if (missingModules.isNotEmpty() && missingError) {
            val modulesCode = missingModules.joinToString(",") { it.str() }
            val modulesMsg = missingModules.joinToString(", ") { it.displayStr() }
            throw C_CommonError("module_args_missing:$modulesCode", "Missing module_args for module(s): $modulesMsg")
        }

        return expectedArgs.keys.sorted()
            .mapNotNull { module ->
                val expected = expectedArgs.getValue(module)
                val actual = actualArgs[module]
                if (actual == null) null else {
                    val value = try {
                        PostchainUtils.moduleArgsGtvToRt(expected, actual)
                    } catch (e: Throwable) {
                        throw C_CommonError("module_args_bad:$module", "Bad module_args for module '${module.str()}': ${e.message}")
                    }
                    module to value
                }
            }
            .toImmMap()
    }

    fun wrapCompilation(
        config: RellCliCompileConfig,
        code: () -> RellCliCompilationResult,
    ): Pair<RellCliCompilationResult, R_App> {
        val cliEnv = config.cliEnv
        val res = catchCommonError {
            code()
        }
        val rApp = RellCliUtils.handleCompilationResult(cliEnv, res.cRes, config.quiet)
        return res to rApp
    }

    fun <T> catchCommonError(code: () -> T): T {
        try {
            return code()
        } catch (e: C_CommonError) {
            throw RellCliBasicException(e.msg)
        }
    }

    fun getDefaultReplHistoryFile(): File? {
        val homeDir = CommonUtils.getHomeDir()
        return if (homeDir == null) null else File(homeDir, ".rell_history")
    }
}
