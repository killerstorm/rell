package net.postchain.rell.tools.runcfg

import mu.KotlinLogging
import net.postchain.config.app.AppConfig
import net.postchain.config.node.NodeConfig
import net.postchain.config.node.NodeConfigurationProviderFactory
import net.postchain.core.UserMistake
import net.postchain.devtools.PostchainTestNode
import net.postchain.rell.RellCliUtils
import org.apache.commons.configuration2.PropertiesConfiguration
import picocli.CommandLine
import java.io.File
import java.io.StringReader

private val log = run {
    RellCliUtils.initLogging()
    KotlinLogging.logger("PostchainApp")
}

fun main(args: Array<String>) {
    RellCliUtils.runCli(args, RellRunConfigLaunchArgs()) {
        main0(it)
    }
}

private fun main0(args: RellRunConfigLaunchArgs) {
    val runConfigFile = RellCliUtils.checkFile(args.runConfigFile)
    val sourceDir = RellCliUtils.checkDir(args.sourceDir)

    log.info("STARTING POSTCHAIN APP")
    log.info("    run config file: ${runConfigFile.absolutePath}")
    log.info("    source directory: ${sourceDir.absolutePath}")
    log.info("")

    val rellAppConf = RellRunConfigGenerator.generateCli(sourceDir, runConfigFile)

    // Make sure that all sources compile before trying to start a node.
    for (chain in rellAppConf.config.chains) {
        for (sourcePath in chain.sourcePaths) {
            RellCliUtils.compileModule(rellAppConf.sourceDir, sourcePath, true)
        }
    }

    val nodeConf = startPostchainNode(rellAppConf)

    log.info("")
    log.info("POSTCHAIN APP STARTED")
    log.info("    REST API port: ${nodeConf.restApiPort}")
    log.info("")
}

private fun startPostchainNode(rellAppConf: RellPostAppCliConfig): NodeConfig {
    val nodeAppConf = getNodeConfig(rellAppConf.configDir, rellAppConf.config.node)
    val nodeConfPro = NodeConfigurationProviderFactory.createProvider(nodeAppConf)
    val nodeConf = nodeConfPro.getConfiguration()

    val node = PostchainTestNode(nodeConfPro, rellAppConf.config.wipeDb)

    val chainsSorted = rellAppConf.config.chains.sortedBy { it.iid }

    for (chain in chainsSorted) {
        val genesisConfig = chain.configs.getValue(0)
        node.addBlockchain(chain.iid, chain.brid.toByteArray(), genesisConfig)

        for ((height, config) in chain.configs) {
            if (height != 0L) {
                node.addConfiguration(chain.iid, height, config)
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

    return nodeConf
}

private fun getNodeConfig(configDir: File, node: RellPostAppNode): AppConfig {
    if (node.srcPropsPath != null) {
        val file = File(node.srcPropsPath)
        val fullFile = if (file.isAbsolute) file else File(configDir, node.srcPropsPath)
        return AppConfig.fromPropertiesFile(fullFile.absolutePath)
    }

    val text = node.srcPropsText!!

    val conf = PropertiesConfiguration()
    conf.layout.load(conf, StringReader(text))
    return AppConfig(conf)
}

@CommandLine.Command(name = "RellRunConfigLaunch", description = ["Launch a run config"])
private class RellRunConfigLaunchArgs {
    @CommandLine.Option(names = ["--source-dir"], paramLabel =  "SOURCE_DIR", description = ["Rell source directory"], required = true)
    var sourceDir: String = ""

    @CommandLine.Parameters(index = "0", paramLabel = "RUN_CONFIG", description = ["Run config file"])
    var runConfigFile: String = ""
}