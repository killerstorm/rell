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
class RellPostAppConfig(val node: RellPostAppNode, val chains: List<RellPostAppChain>, val wipeDb: Boolean)

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
        val configs: Map<Long, Gtv>,
        val modules: Set<R_ModuleName>
)

class Rcfg_Run(val nodeConfig: Rcfg_NodeConfig, val chains: List<Rcfg_Chain>, val wipeDb: Boolean)

class Rcfg_NodeConfig(val src: String?, val text: String?, val addSigners: Boolean)

class Rcfg_Chain(val name: String, val iid: Long, val configs: List<Rcfg_ChainConfig>)

class Rcfg_Dependency(val chain: Rcfg_Chain?, val brid: Bytes32?)

class Rcfg_ChainConfig(
        val height: Long,
        val app: Rcfg_App?,
        val gtvs: List<Rcfg_ChainConfigGtv>,
        val addDependencies: Boolean,
        val dependencies: Map<String, Rcfg_Dependency>
)

class Rcfg_App(
        val module: R_ModuleName,
        val args: Map<R_ModuleName, Map<String, Gtv>>,
        val addDefaults: Boolean
)

class Rcfg_ChainConfigGtv(val path: List<String>, val src: String?, val gtv: Gtv?)

class RellRunConfigParams(val sourceDir: C_SourceDir, val configDir: GeneralDir, val sourceVersion: R_LangVersion)

object RellRunConfigGenerator {
    fun generateCli(sourceDir: File, runConfFile: File, sourceVersion: R_LangVersion): RellPostAppCliConfig {
        val cSourceDir = C_DiskSourceDir(sourceDir)

        val configDir = runConfFile.absoluteFile.parentFile
        val generalConfigDir = DiskGeneralDir(configDir)
        val params = RellRunConfigParams(cSourceDir, generalConfigDir, sourceVersion)

        val runConfText = runConfFile.readText()
        val config = generate(params, runConfFile.path, runConfText)

        return RellPostAppCliConfig(cSourceDir, configDir, config)
    }

    fun generate(params: RellRunConfigParams, confPath: String, confText: String): RellPostAppConfig {
        val rcfg = readConfig(params.configDir, confPath, confText)

        val nodeConfig = RunConfigNodeConfigGen.generateNodeConfig(rcfg.nodeConfig, params.configDir)

        val replaceSigners = if (rcfg.nodeConfig.addSigners) nodeConfig.signers else null
        val chainConfigs = RunConfigChainConfigGen.generateChainsConfigs(params, rcfg, replaceSigners)

        return RellPostAppConfig(nodeConfig, chainConfigs, wipeDb = rcfg.wipeDb)
    }

    private fun readConfig(configDir: GeneralDir, confPath: String, confText: String): Rcfg_Run {
        val xml = RellXmlParser.parse(confPath, confText)
        val fullXml = RellXmlIncluder.includeFiles(xml, configDir)
        val runConfig = RunConfigParser.parseConfig(fullXml)
        return runConfig
    }

    fun buildFiles(appConfig: RellPostAppConfig): Map<String, DirFile> {
        val dirBuilder = DirBuilder()
        dirBuilder.put(appConfig.node.dstFiles)

        for (chain in appConfig.chains) {
            val chainPath = "blockchains/${chain.iid}"
            dirBuilder.put("$chainPath/brid.txt", chain.brid.toHex())

            for ((height, gtv) in chain.configs) {
                val xml = PostchainUtils.gtvToXml(gtv)
                dirBuilder.put("$chainPath/$height.xml", xml)

                val bytes = Bytes.of(GtvEncoder.encodeGtv(gtv))
                dirBuilder.put("$chainPath/$height.gtv", bytes)
            }
        }

        return dirBuilder.toFileMap()
    }
}
