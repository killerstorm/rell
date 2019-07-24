package net.postchain.rell.tools.runcfg

import net.postchain.gtv.Gtv
import net.postchain.rell.*
import net.postchain.rell.parser.C_DiskSourceDir
import net.postchain.rell.parser.C_SourceDir
import net.postchain.rell.parser.C_SourcePath
import java.io.File

class RellPostAppCliConfig(val sourceDir: C_SourceDir, val configDir: File, val config: RellPostAppConfig)
class RellPostAppConfig(val node: RellPostAppNode, val chains: List<RellPostAppChain>, val wipeDb: Boolean)

class RellPostAppNode(
        val srcPropsPath: String?,
        val srcPropsText: String?,
        val dstFiles: Map<String, String>,
        val signers: Set<Bytes33>
)

class RellPostAppChain(
        val name: String,
        val iid: Long,
        val brid: Bytes32,
        val configs: Map<Long, Gtv>,
        val sourcePaths: Set<C_SourcePath>
)

class Rcfg_Run(val nodeConfig: Rcfg_NodeConfig, val chains: List<Rcfg_Chain>, val wipeDb: Boolean)

class Rcfg_NodeConfig(val src: String?, val text: String?, val addSigners: Boolean)

class Rcfg_Chain(
        val name: String,
        val iid: Long,
        val brid: Bytes32,
        val configs: List<Rcfg_ChainConfig>,
        val dependencies: Map<String, Bytes32>
)

class Rcfg_ChainConfig(
        val height: Long,
        val module: Rcfg_Module?,
        val gtvs: List<Rcfg_ChainConfigGtv>,
        val addDependencies: Boolean
)

class Rcfg_Module(val src: String, val args: Map<String, Gtv>?, val addDefaults: Boolean)

class Rcfg_ChainConfigGtv(val path: List<String>, val src: String?, val gtv: Gtv?)

object RellRunConfigGenerator {
    fun generateCli(sourceDir: File, runConfFile: File): RellPostAppCliConfig {
        val cSourceDir = C_DiskSourceDir(sourceDir)

        val configDir = runConfFile.absoluteFile.parentFile
        val generalConfigDir = DiskGeneralDir(configDir)

        val runConfText = runConfFile.readText()
        val config = generate(cSourceDir, generalConfigDir, runConfFile.path, runConfText)

        return RellPostAppCliConfig(cSourceDir, configDir, config)
    }

    fun generate(sourceDir: C_SourceDir, configDir: GeneralDir, confPath: String, confText: String): RellPostAppConfig {
        val rcfg = readConfig(configDir, confPath, confText)

        val nodeConfig = RunConfigNodeConfigGen.generateNodeConfig(rcfg.nodeConfig, configDir)
        val chainConfigs = RunConfigChainConfigGen.generateChainsConfigs(sourceDir, configDir, rcfg, nodeConfig.signers)

        return RellPostAppConfig(nodeConfig, chainConfigs, wipeDb = rcfg.wipeDb)
    }

    private fun readConfig(configDir: GeneralDir, confPath: String, confText: String): Rcfg_Run {
        val xml = RellXmlParser.parse(confPath, confText)
        val fullXml = RellXmlIncluder.includeFiles(xml, configDir)
        val runConfig = RunConfigParser.parseConfig(fullXml)
        return runConfig
    }

    fun buildFiles(appConfig: RellPostAppConfig): Map<String, String> {
        val dirBuilder = DirBuilder()
        dirBuilder.put(appConfig.node.dstFiles)

        for (chain in appConfig.chains) {
            val chainPath = "blockchains/${chain.iid}"
            dirBuilder.put("$chainPath/brid.txt", chain.brid.toHex())

            for ((height, gtv) in chain.configs) {
                val configPath = "$chainPath/$height.xml"
                val xml = PostchainUtils.gtvToXml(gtv)
                dirBuilder.put(configPath, xml)
            }
        }

        return dirBuilder.toFileMap()
    }
}
