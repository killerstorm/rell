/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.test

import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.base.data.SQLDatabaseAccess
import net.postchain.gtv.Gtv
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.lib.test.C_Lib_Test
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.repl.PostchainReplInterpreterProjExt
import net.postchain.rell.repl.ReplInterpreterProjExt
import net.postchain.rell.sql.PostchainSqlInitProjExt
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlInitProjExt
import net.postchain.rell.utils.*
import net.postchain.rell.utils.cli.RellApiCompile

object PostchainRellTestProjExt: RellTestProjExt() {
    private val sqlInitProjExt: SqlInitProjExt = PostchainSqlInitProjExt

    override fun getSqlInitProjExt() = sqlInitProjExt

    override fun getReplInterpreterProjExt(): ReplInterpreterProjExt {
        val runnerConfig = createBlockRunnerConfig()
        return PostchainReplInterpreterProjExt(sqlInitProjExt, runnerConfig)
    }

    override fun initSysAppTables(sqlExec: SqlExecutor) {
        val sqlAccess: SQLDatabaseAccess = PostgreSQLDatabaseAccess()
        sqlExec.connection { con ->
            sqlAccess.initializeApp(con, PostchainBaseUtils.DATABASE_VERSION)
        }
    }

    override fun createUnitTestBlockRunner(
        sourceDir: C_SourceDir,
        app: R_App,
        moduleArgs: Map<R_ModuleName, Gtv>,
    ): Rt_UnitTestBlockRunner {
        val keyPair = C_Lib_Test.BLOCK_RUNNER_KEYPAIR
        val blkRunConfig = createBlockRunnerConfig()

        val modules = app.modules.filter { !it.test && !it.abstract && !it.external }.map { it.name }
        val compileConfig = RellApiCompile.Config.Builder().moduleArgs0(moduleArgs).build()
        val blkRunStrategy = Rt_DynamicBlockRunnerStrategy(sourceDir, keyPair, modules, compileConfig)

        return Rt_PostchainUnitTestBlockRunner(keyPair, blkRunConfig, blkRunStrategy)
    }

    private fun createBlockRunnerConfig(): Rt_BlockRunnerConfig {
        return Rt_BlockRunnerConfig(
            wrapCtErrors = false,
            wrapRtErrors = false,
            forceTypeCheck = true,
        )
    }
}
