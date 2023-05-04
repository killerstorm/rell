/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.utils.cli

import net.postchain.gtv.Gtv
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.lib.test.*
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.repl.*
import net.postchain.rell.utils.*
import java.io.File

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

        val options = RellCliInternalBaseApi.makeCompilerOptions(config)
        val (_, rApp) = RellCliInternalBaseApi.compileApp(config, options, cSourceDir, rAppModules, rTestModules)
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
        return RellCliInternalBaseApi.compileGtv(config, cSourceDir, immListOf(rMainModule))
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
    ): UnitTestRunnerResults {
        val cSourceDir = C_SourceDir.diskDir(sourceDir)
        val rAppModules = appModules?.map { R_ModuleName.of(it) }?.toImmList()
        val rTestModules = testModules.map { R_ModuleName.of(it) }.toImmList()

        val compileConfig = config.compileConfig
        val options = RellCliInternalBaseApi.makeCompilerOptions(compileConfig)
        val (cRes, app) = RellCliInternalBaseApi.compileApp(compileConfig, options, cSourceDir, rAppModules, rTestModules)
        return RellCliInternalGtxApi.runTests(config, options, cSourceDir, app, rAppModules, cRes.moduleArgs)
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
        RellCliInternalShellApi.runShell(config, cSourceDir, rModule)
    }
}
