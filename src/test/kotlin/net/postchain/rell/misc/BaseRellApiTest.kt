/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.misc

import net.postchain.rell.compiler.base.core.C_CompilationResult
import net.postchain.rell.compiler.base.core.C_CompilerOptions
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.test.RellTestUtils
import net.postchain.rell.test.TestSnippetsRecorder
import net.postchain.rell.utils.cli.RellCliCompilationResult
import net.postchain.rell.utils.cli.RellCliCompileConfig
import net.postchain.rell.utils.cli.RellCliInternalBaseApi
import net.postchain.rell.utils.immListOf

abstract class BaseRellApiTest {
    protected val generalSourceDir = C_SourceDir.mapDirOf(
        "a.rell" to "module;",
        "b1/module.rell" to "module;",
        "b1/b2.rell" to "module;",
        "c.rell" to "module;",
        "d.rell" to "@test module; import c;",
        "e1/module.rell" to "module;",
        "e1/e2/module.rell" to "@test module;",
        "e1/e2/e3.rell" to "@test module;",
    )

    protected val defaultConfig = configBuilder().build()

    protected fun configBuilder() = RellCliCompileConfig.Builder()

    // Important to call this function instead of calling the API directly - to record test snippets.
    protected fun compileApp0(
        config: RellCliCompileConfig,
        options: C_CompilerOptions,
        sourceDir: C_SourceDir,
        appModules: List<R_ModuleName>?,
        testModules: List<R_ModuleName> = immListOf(),
    ): RellCliCompilationResult {
        val apiRes = RellCliInternalBaseApi.compileApp0(config, options, sourceDir, appModules, testModules)
        val modSel = RellCliInternalBaseApi.makeCompilerModuleSelection(config, appModules, testModules)
        TestSnippetsRecorder.record(sourceDir, modSel, options, apiRes.cRes)
        return apiRes
    }

    protected fun handleCompilationError(cRes: C_CompilationResult): String? {
        return when {
            cRes.errors.isNotEmpty() -> "CTE:${RellTestUtils.errsToString(cRes.errors, false)}"
            cRes.app == null -> "ERR:no_app"
            else -> null
        }
    }
}
