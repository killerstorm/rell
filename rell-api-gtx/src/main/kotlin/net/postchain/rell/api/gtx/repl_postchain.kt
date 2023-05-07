/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.api.gtx

import net.postchain.rell.api.base.NullRellCliEnv
import net.postchain.rell.api.base.RellApiCompile
import net.postchain.rell.base.compiler.base.utils.C_SourceDir
import net.postchain.rell.base.lib.test.C_Lib_Test
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.repl.ReplInterpreterProjExt
import net.postchain.rell.base.sql.SqlInitProjExt
import net.postchain.rell.base.utils.Rt_UnitTestBlockRunner

class PostchainReplInterpreterProjExt(
    private val sqlInitProjExt: SqlInitProjExt,
    private val runnerConfig: Rt_BlockRunnerConfig,
): ReplInterpreterProjExt() {
    override fun getSqlInitProjExt() = sqlInitProjExt

    override fun createBlockRunner(sourceDir: C_SourceDir, modules: List<R_ModuleName>): Rt_UnitTestBlockRunner {
        val keyPair = C_Lib_Test.BLOCK_RUNNER_KEYPAIR
        val compileConfig = RellApiCompile.Config.Builder().cliEnv(NullRellCliEnv).build()
        val runnerStrategy = Rt_DynamicBlockRunnerStrategy(sourceDir, keyPair, modules, compileConfig)
        return Rt_PostchainUnitTestBlockRunner(keyPair, runnerConfig, runnerStrategy)
    }
}
