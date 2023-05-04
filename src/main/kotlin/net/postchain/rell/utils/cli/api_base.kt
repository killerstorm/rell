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
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_LangVersion
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.*

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

class RellCliCompilationResult(
    val cRes: C_CompilationResult,
    val moduleArgs: Map<R_ModuleName, Rt_Value>,
)

object RellCliInternalBaseApi {
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

        val rApp = cRes.app
        val moduleArgs = if (rApp != null && cRes.errors.isEmpty()) {
            processModuleArgs(rApp, config.moduleArgs, config.moduleArgsMissingError)
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
    ): Pair<Gtv, RellGtxModuleApp> {
        val options = makeCompilerOptions(config)
        val (apiRes, rApp) = compileApp(config, options, sourceDir, modules, immListOf())

        val mainModules = modules ?: RellApiBaseUtils.getMainModules(rApp)

        val gtv = catchCommonError {
            compileGtv0(config, sourceDir, mainModules, apiRes.cRes.files)
        }

        return gtv to RellGtxModuleApp(rApp, options)
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
            RellGtxConfigConstants.RELL_SOURCES_KEY to GtvFactory.gtv(sources.mapValues { (_, v) -> GtvFactory.gtv(v) }),
            RellGtxConfigConstants.RELL_VERSION_KEY to GtvFactory.gtv(config.version.str()),
        )

        val moduleArgs = config.moduleArgs
        if (moduleArgs.isNotEmpty()) {
            val argsGtv = GtvFactory.gtv(moduleArgs.mapKeys { (k, _) -> k.str() })
            map["moduleArgs"] = argsGtv
        }

        return GtvFactory.gtv(map.toImmMap())
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
                        PostchainGtvUtils.moduleArgsGtvToRt(expected, actual)
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
        val rApp = RellApiBaseUtils.handleCompilationResult(cliEnv, res.cRes, config.quiet)
        return res to rApp
    }

    fun <T> catchCommonError(code: () -> T): T {
        try {
            return code()
        } catch (e: C_CommonError) {
            throw RellCliBasicException(e.msg)
        }
    }
}
