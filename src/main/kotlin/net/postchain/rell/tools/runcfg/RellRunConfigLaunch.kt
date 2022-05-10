/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.tools.runcfg

import mu.KotlinLogging
import net.postchain.StorageBuilder
import net.postchain.api.rest.infra.RestApiConfig
import net.postchain.config.app.AppConfig
import net.postchain.common.BlockchainRid
import net.postchain.core.UserMistake
import net.postchain.devtools.PostchainTestNode
import net.postchain.gtv.Gtv
import net.postchain.rell.compiler.base.core.C_CompilerModuleSelection
import net.postchain.rell.compiler.base.core.C_CompilerOptions
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_LangVersion
import net.postchain.rell.runtime.Rt_ChainSqlMapping
import net.postchain.rell.runtime.Rt_RegularSqlContext
import net.postchain.rell.runtime.Rt_StaticBlockRunnerStrategy
import net.postchain.rell.sql.PostchainStorageSqlManager
import net.postchain.rell.utils.*
import org.apache.commons.configuration2.PropertiesConfiguration
import picocli.CommandLine
import java.io.File
import java.io.StringReader
import java.util.*
import kotlin.system.exitProcess

private val log = run {
    RellCliLogUtils.initLogging()
    KotlinLogging.logger("PostchainApp")
}

fun main(args: Array<String>) {
    RellCliUtils.runCli(args, RellRunConfigLaunchCliArgs()) {
        main0(it)
    }
}

private fun main0(args: RellRunConfigLaunchCliArgs) {
    val runConfigFile = RellCliUtils.checkFile(args.runConfigFile)
    val sourceDir = RellCliUtils.checkDir(args.sourceDir ?: ".").absoluteFile
    val sourceVer = RellCliUtils.checkVersion(args.sourceVersion)
    val commonArgs = CommonArgs(runConfigFile, sourceDir, sourceVer)

    if (args.test) {
        runTests(commonArgs)
    } else {
        runApp(commonArgs)
    }
}

private fun runApp(args: CommonArgs) {
    log.info("STARTING POSTCHAIN APP")
    log.info("    source directory: ${args.sourceDir.absolutePath}")
    log.info("    run config file: ${args.runConfigFile.absolutePath}")
    log.info("")

    RellCliUtils.printVersionInfo()

    val rellAppConf = generateRunConfig(args, false)

    // Make sure that all sources compile before trying to start a node.
    for (chain in rellAppConf.config.chains) {
        val modules = chain.modules.toList()
        val modSel = C_CompilerModuleSelection(modules)
        RellCliUtils.compileApp(rellAppConf.sourceDir, modSel, true, C_CompilerOptions.DEFAULT)
    }

    val appConfig = startPostchainNode(rellAppConf)

    log.info("")
    log.info("POSTCHAIN APP STARTED")
    log.info("    REST API port: ${RestApiConfig.fromAppConfig(appConfig).port}")
    log.info("")
}

private fun startPostchainNode(rellAppConf: RellPostAppCliConfig): AppConfig {
    val nodeAppConf = getNodeConfig(rellAppConf, rellAppConf.config.node)

    val node = PostchainTestNode(nodeAppConf, rellAppConf.config.wipeDb)

    val chainsSorted = rellAppConf.config.chains.sortedBy { it.iid }

    for (chain in chainsSorted) {
        val genesisConfig = chain.configs.getValue(0).gtvConfig
        val brid = node.addBlockchain(chain.iid, genesisConfig)
        log.info { "Chain '${chain.name}' ID = ${chain.iid} RID = ${brid.toHex()}" }

        check(Arrays.equals(brid.data, chain.brid.toByteArray())) {
            "Chain '${chain.name}' (${chain.iid}): calculated BRID = ${chain.brid.toHex()}, postchain BRID = ${brid.toHex()}"
        }

        for ((height, config) in chain.configs) {
            if (height != 0L) {
                node.addConfiguration(chain.iid, height, config.gtvConfig)
            }
        }
    }

    for (chain in chainsSorted) {
        try {
            node.startBlockchain(chain.iid)
        } catch (e: UserMistake) {
            throw UserMistake("Failed to start chain '${chain.name}' (IID = ${chain.iid})", e)
        } catch (e: Throwable) {
            throw RuntimeException("Failed to start chain '${chain.name}' (IID = ${chain.iid})", e)
        }
    }

    return nodeAppConf
}

private fun runTests(args: CommonArgs) {
    val compilerOptions = C_CompilerOptions.forLangVersion(args.sourceVer)

    val rellAppConf = generateRunConfig(args, true)
    val testNodeConfig = rellAppConf.config.testNode
    testNodeConfig ?: throw RellCliErr("Test database configuration not specified in run.xml")

    val nodeAppConf = getNodeConfig(rellAppConf, testNodeConfig)
    val keyPair = BytesKeyPair(nodeAppConf.privKeyByteArray, nodeAppConf.pubKeyByteArray)

    class TestChain(val chain: RellPostAppChain, val rApp: R_App, val gtvConfig: Gtv)

    val sortedChains = rellAppConf.config.chains.sortedBy { it.iid }
    val tChains = sortedChains.mapNotNull { chain ->
        val (_, config) = chain.configs.maxByOrNull { it.key }!!
        if (config.appModule == null) null else {
            val modules = listOf(config.appModule)
            val testModules = (modules.toSet() + config.testModules.toSet()).toList()
            val modSel = C_CompilerModuleSelection(modules, testModules)
            val rApp = RellCliUtils.compileApp(rellAppConf.sourceDir, modSel, true, compilerOptions)
            TestChain(chain, rApp, config.gtvConfig)
        }
    }

    val testRes = TestRunnerResults()

    StorageBuilder.buildStorage(nodeAppConf).use { storage ->
        val sqlMgr = PostchainStorageSqlManager(storage, false)

        for (tChain in tChains) {
            val sqlCtx = Rt_RegularSqlContext.createNoExternalChains(tChain.rApp, Rt_ChainSqlMapping(tChain.chain.iid))
            val blockRunnerStrategy = Rt_StaticBlockRunnerStrategy(tChain.gtvConfig, keyPair)

            val globalCtx = RellCliUtils.createGlobalContext(true, compilerOptions, true)

            val chainRid = BlockchainRid(tChain.chain.brid.toByteArray())
            val chainCtx = PostchainUtils.createChainContext(tChain.gtvConfig, tChain.rApp, chainRid)

            val testCtx = TestRunnerContext(sqlCtx, sqlMgr, globalCtx, chainCtx, blockRunnerStrategy, tChain.rApp)
            val fns = TestRunner.getTestFunctions(tChain.rApp)

            val tc = TestRunnerChain(tChain.chain.name, tChain.chain.iid)
            val cases = fns.map { TestRunnerCase(tc, it) }

            TestRunner.runTests(testCtx, cases, testRes)
        }
    }

    val ok = testRes.print()
    if (!ok) {
        exitProcess(1)
    }
}

private fun generateRunConfig(args: CommonArgs, test: Boolean): RellPostAppCliConfig {
    return RellRunConfigGenerator.generateCli(args.sourceDir, args.runConfigFile, args.sourceVer, unitTest = test)
}

private fun getNodeConfig(rellAppConf: RellPostAppCliConfig, rellAppNode: RellPostAppNode): AppConfig {
    if (rellAppNode.srcPropsPath != null) {
        val file = File(rellAppNode.srcPropsPath)
        val fullFile = if (file.isAbsolute) file else File(rellAppConf.configDir, rellAppNode.srcPropsPath)
        return AppConfig.fromPropertiesFile(fullFile.absolutePath)
    }

    val text = rellAppNode.srcPropsText!!
    val conf = PropertiesConfiguration()
    conf.layout.load(conf, StringReader(text))
    return AppConfig(conf)
}

private class CommonArgs(
        val runConfigFile: File,
        val sourceDir: File,
        val sourceVer: R_LangVersion
)

@CommandLine.Command(name = "RellRunConfigLaunch", description = ["Launch a run.xml config"])
private class RellRunConfigLaunchCliArgs: RellRunConfigCliArgs() {
    @CommandLine.Option(names = ["--test"], description = ["Run unit tests"])
    var test: Boolean = false
}
