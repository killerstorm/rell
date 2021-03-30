/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.tools.runcfg

import mu.KotlinLogging
import net.postchain.StorageBuilder
import net.postchain.config.app.AppConfig
import net.postchain.config.node.NodeConfig
import net.postchain.config.node.NodeConfigurationProviderFactory
import net.postchain.core.NODE_ID_TODO
import net.postchain.core.UserMistake
import net.postchain.devtools.PostchainTestNode
import net.postchain.rell.compiler.C_CompilerOptions
import net.postchain.rell.utils.RellCliLogUtils
import net.postchain.rell.utils.RellCliUtils
import org.apache.commons.configuration2.PropertiesConfiguration
import picocli.CommandLine
import java.io.File
import java.io.StringReader
import java.util.*

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

    log.info("STARTING POSTCHAIN APP")
    log.info("    source directory: ${sourceDir.absolutePath}")
    log.info("    run config file: ${runConfigFile.absolutePath}")
    log.info("")

    RellCliUtils.printVersionInfo()

    val rellAppConf = RellRunConfigGenerator.generateCli(sourceDir, runConfigFile, sourceVer)

    // Make sure that all sources compile before trying to start a node.
    for (chain in rellAppConf.config.chains) {
        val modules = chain.modules.toList()
        RellCliUtils.compileApp(rellAppConf.sourceDir, modules, true, C_CompilerOptions.DEFAULT)
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

    // Wiping DB
    StorageBuilder.buildStorage(nodeAppConf, NODE_ID_TODO, rellAppConf.config.wipeDb).close()

    val node = PostchainTestNode(nodeConfPro)

    val chainsSorted = rellAppConf.config.chains.sortedBy { it.iid }

    for (chain in chainsSorted) {
        val genesisConfig = chain.configs.getValue(0)
        val brid = node.addBlockchain(chain.iid, genesisConfig)
        log.info { "Chain '${chain.name}' ID = ${chain.iid} RID = ${brid.toHex()}" }

        check(Arrays.equals(brid.data, chain.brid.toByteArray())) {
            "Chain '${chain.name}' (${chain.iid}): calculated BRID = ${chain.brid.toHex()}, postchain BRID = ${brid.toHex()}"
        }

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

@CommandLine.Command(name = "RellRunConfigLaunch", description = ["Launch a run.xml config"])
private class RellRunConfigLaunchCliArgs: RellRunConfigCliArgs()
