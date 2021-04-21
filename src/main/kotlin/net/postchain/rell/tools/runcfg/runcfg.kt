/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.tools.runcfg

import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvEncoder
import net.postchain.rell.compiler.C_DiskSourceDir
import net.postchain.rell.compiler.C_SourceDir
import net.postchain.rell.model.R_LangVersion
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.utils.*
import java.io.File

class RellPostAppCliConfig(val sourceDir: C_SourceDir, val configDir: File, val config: RellPostAppConfig)

class RellPostAppConfig(
        val node: RellPostAppNode,
        val testNode: RellPostAppNode?,
        val chains: List<RellPostAppChain>,
        val wipeDb: Boolean
)

class RellPostAppNode(
        val srcPropsPath: String?,
        val srcPropsText: String?,
        val dstFiles: Map<String, DirFile>,
        val signers: Set<Bytes33>
)

class RellPostAppChain(
        val name: String,
        val iid: Long,
        val brid: Bytes32,
        val configs: Map<Long, RellPostAppChainConfig>,
        val modules: Set<R_ModuleName>
)

class RellPostAppChainConfig(
        val appModule: R_ModuleName?,
        val testModules: List<R_ModuleName>,
        val gtvConfig: Gtv
)

class Rcfg_Run(
        val nodeConfig: Rcfg_NodeConfig?,
        val testNodeConfig: Rcfg_NodeConfig?,
        val chains: List<Rcfg_Chain>,
        val tests: List<Rcfg_TestModule>,
        val wipeDb: Boolean
)

class Rcfg_NodeConfig(val src: String?, val text: String?, val addSigners: Boolean)

class Rcfg_Chain(val name: String, val iid: Long, val configs: List<Rcfg_ChainConfig>, val tests: List<Rcfg_TestModule>)

class Rcfg_Dependency(val chain: Rcfg_Chain?, val brid: Bytes32?)

class Rcfg_ChainConfig(
        val height: Long,
        val app: Rcfg_App?,
        val gtvs: List<Rcfg_ChainConfigGtv>,
        val addDependencies: Boolean,
        val dependencies: Map<String, Rcfg_Dependency>
)

class Rcfg_TestModule(val module: R_ModuleName)

class Rcfg_App(
        val module: R_ModuleName,
        val args: Map<R_ModuleName, Map<String, Gtv>>,
        val addDefaults: Boolean
)

class Rcfg_ChainConfigGtv(val path: List<String>, val src: String?, val gtv: Gtv?)

class RellRunConfigParams(
        val sourceDir: C_SourceDir,
        val configDir: GeneralDir,
        val sourceVersion: R_LangVersion,
        val unitTest: Boolean
)

object RellRunConfigGenerator {
    fun generateCli(
            sourceDir: File,
            runConfFile: File,
            sourceVersion: R_LangVersion,
            unitTest: Boolean
    ): RellPostAppCliConfig {
        val cSourceDir = C_DiskSourceDir(sourceDir)

        val configDir = runConfFile.absoluteFile.parentFile
        val generalConfigDir = DiskGeneralDir(configDir)
        val params = RellRunConfigParams(cSourceDir, generalConfigDir, sourceVersion, unitTest)

        val runConfText = runConfFile.readText()
        val config = generate(MainRellCliEnv, params, runConfFile.path, runConfText)

        return RellPostAppCliConfig(cSourceDir, configDir, config)
    }

    fun generate(
            cliEnv: RellCliEnv,
            params: RellRunConfigParams,
            confPath: String,
            confText: String
    ): RellPostAppConfig {
        val parserOpts = RunConfigParserOptions(unitTest = params.unitTest)
        val rcfg = readConfig(params.configDir, confPath, confText, parserOpts)

        val rawNodeConfig = if (params.unitTest) rcfg.testNodeConfig else rcfg.nodeConfig
        check(rawNodeConfig != null) { "Node config not defined!" }

        val nodeConfig = RunConfigNodeConfigGen.generateNodeConfig(rawNodeConfig, params.configDir)

        val testNodeConfig = if (rcfg.testNodeConfig == null) null else {
            RunConfigNodeConfigGen.generateNodeConfig(rcfg.testNodeConfig, params.configDir)
        }

        val replaceSigners = if (rawNodeConfig.addSigners) nodeConfig.signers else null
        val chainConfigs = RunConfigChainConfigGen.generateChainsConfigs(cliEnv, params, rcfg, replaceSigners)

        return RellPostAppConfig(nodeConfig, testNodeConfig, chainConfigs, wipeDb = rcfg.wipeDb)
    }

    private fun readConfig(
            configDir: GeneralDir,
            confPath: String,
            confText: String,
            parserOpts: RunConfigParserOptions
    ): Rcfg_Run {
        val xml = RellXmlParser.parse(confPath, confText)
        val fullXml = RellXmlIncluder.includeFiles(xml, configDir)
        val runConfig = RunConfigParser.parseConfig(fullXml, parserOpts)
        return runConfig
    }

    fun buildFiles(appConfig: RellPostAppConfig): Map<String, DirFile> {
        val dirBuilder = DirBuilder()
        dirBuilder.put(appConfig.node.dstFiles)

        for (chain in appConfig.chains) {
            val chainPath = "blockchains/${chain.iid}"
            dirBuilder.put("$chainPath/brid.txt", chain.brid.toHex())

            for ((height, config) in chain.configs) {
                val xml = PostchainUtils.gtvToXml(config.gtvConfig)
                dirBuilder.put("$chainPath/$height.xml", xml)

                val bytes = Bytes.of(GtvEncoder.encodeGtv(config.gtvConfig))
                dirBuilder.put("$chainPath/$height.gtv", bytes)
            }
        }

        return dirBuilder.toFileMap()
    }
}
