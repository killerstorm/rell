/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.tools

import mu.KotlinLogging
import net.postchain.StorageBuilder
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.config.app.AppConfig
import net.postchain.config.node.NodeConfigurationProviderFactory
import net.postchain.crypto.secp256k1_derivePubKey
import net.postchain.devtools.PostchainTestNode
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.rell.RellConfigGen
import net.postchain.rell.module.RellVersions
import net.postchain.rell.runtime.Rt_LogPrinter
import net.postchain.rell.runtime.Rt_PrinterFactory
import net.postchain.rell.sql.SqlInitLogging
import net.postchain.rell.utils.*
import picocli.CommandLine
import java.io.File
import java.util.logging.LogManager

private val log = run {
    RellCliLogUtils.initLogging()
    KotlinLogging.logger("PostchainApp")
}

fun main(args: Array<String>) {
    RellCliUtils.runCli(args, RunPostchainAppArgs()) {
        main0(it)
    }
}

private fun main0(args: RunPostchainAppArgs) {
    val target = RellCliUtils.getTarget(args.sourceDir, args.module)
    RellCliUtils.checkFile(args.nodeConfigFile)

    log.info("STARTING POSTCHAIN APP")
    log.info("    source directory: ${target.sourcePath.absolutePath}")
    log.info("    module:           ${args.module}")
    log.info("    node config file: ${File(args.nodeConfigFile).absolutePath}")
    log.info("")

    RellCliUtils.printVersionInfo()

    val configGen = RellConfigGen.create(MainRellCliEnv, target)

    val nodeAppConf = AppConfig.fromPropertiesFile(args.nodeConfigFile)
    val storage = StorageBuilder.buildStorage(nodeAppConf, true)

    val nodeConfPro = NodeConfigurationProviderFactory.createProvider(nodeAppConf) { storage }

    val nodeConf = nodeConfPro.getConfiguration()
    val template = RunPostchainApp.genBlockchainConfigTemplate(nodeConf.pubKeyByteArray, args.sqlLog)
    val bcConf = configGen.makeConfig(template)

    val node = PostchainTestNode(nodeConfPro, storage)
    val brid = node.addBlockchain(0, bcConf)
    node.startBlockchain(0)

    log.info("")
    log.info("POSTCHAIN APP STARTED")
    log.info("    REST API port:  ${nodeConf.restApiPort}")
    log.info("    blockchain RID: ${brid.toHex()}")
    log.info("")
}

object RunPostchainApp {
    fun genBlockchainConfigTemplate(pubKey: ByteArray, sqlLog: Boolean, sqlInitLog: Int = SqlInitLogging.LOG_STEP_COMPLEX): Gtv {
        return gtv(
                "blockstrategy" to gtv("name" to gtv("net.postchain.base.BaseBlockBuildingStrategy")),
                "configurationfactory" to gtv("net.postchain.gtx.GTXBlockchainConfigurationFactory"),
                "signers" to gtv(listOf(gtv(pubKey))),
                "gtx" to gtv(
                        "modules" to gtv(
                                gtv("net.postchain.rell.module.RellPostchainModuleFactory"),
                                gtv("net.postchain.gtx.StandardOpsGTXModule")
                        ),
                        "rell" to gtv(
                                "version" to gtv(RellVersions.VERSION_STR),
                                "dbInitLogLevel" to gtv(sqlInitLog.toLong()),
                                "sqlLog" to gtv(sqlLog),
                                "combinedPrinterFactoryClass" to gtv(Rt_RellAppPrinterFactory::class.java.name),
                                "copyOutputToCombinedPrinter" to gtv(false)
                        )
                )
        )
    }

    fun calcPubKey(privKey: String): String {
        checkEquals(privKey.length, 64)
        val privKeyBytes = privKey.hexStringToByteArray()
        val pubKeyBytes = secp256k1_derivePubKey(privKeyBytes)
        return pubKeyBytes.toHex()

    }
}

class RellJavaLoggingInit {
    init {
        javaClass.getResourceAsStream("/rell_logging.properties")?.use { ins ->
            LogManager.getLogManager().readConfiguration(ins)
        }
    }
}

class Rt_RellAppPrinterFactory: Rt_PrinterFactory {
    override fun newPrinter() = Rt_LogPrinter()
}

@CommandLine.Command(name = "PostchainAppLaunch", description = ["Runs a Rell Postchain app"])
private class RunPostchainAppArgs: RellBaseCliArgs() {
    @CommandLine.Option(names = ["--node-config"], paramLabel = "NODE_CONFIG_FILE", required = true,
            description = ["Node configuration (.properties)"])
    var nodeConfigFile: String = ""

    @CommandLine.Parameters(index = "0", paramLabel = "MODULE", description = ["Module name"])
    var module: String = ""

    @CommandLine.Option(names = ["--sqllog"], description = ["Enable SQL logging"])
    var sqlLog = false
}
