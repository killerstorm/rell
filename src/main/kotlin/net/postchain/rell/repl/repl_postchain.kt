/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.repl

import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.lib.test.C_Lib_Test
import net.postchain.rell.lib.test.Rt_BlockRunnerConfig
import net.postchain.rell.lib.test.Rt_DynamicBlockRunnerStrategy
import net.postchain.rell.lib.test.Rt_PostchainUnitTestBlockRunner
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.sql.SqlInitProjExt
import net.postchain.rell.utils.Rt_UnitTestBlockRunner
import net.postchain.rell.utils.cli.NullRellCliEnv
import net.postchain.rell.utils.cli.RellCliCompileConfig

class PostchainReplInterpreterProjExt(
    private val sqlInitProjExt: SqlInitProjExt,
    private val runnerConfig: Rt_BlockRunnerConfig,
): ReplInterpreterProjExt() {
    override fun getSqlInitProjExt() = sqlInitProjExt

    override fun createBlockRunner(sourceDir: C_SourceDir, modules: List<R_ModuleName>): Rt_UnitTestBlockRunner {
        val keyPair = C_Lib_Test.BLOCK_RUNNER_KEYPAIR
        val compileConfig = RellCliCompileConfig.Builder().cliEnv(NullRellCliEnv).build()
        val runnerStrategy = Rt_DynamicBlockRunnerStrategy(sourceDir, keyPair, modules, compileConfig)
        return Rt_PostchainUnitTestBlockRunner(keyPair, runnerConfig, runnerStrategy)
    }
}
