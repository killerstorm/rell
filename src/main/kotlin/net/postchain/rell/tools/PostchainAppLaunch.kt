/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.tools

import mu.KotlinLogging
import mu.withLoggingContext
import net.postchain.PostchainNode
import net.postchain.api.internal.BlockchainApi
import net.postchain.api.rest.infra.RestApiConfig
import net.postchain.base.gtv.GtvToBlockchainRidFactory
import net.postchain.base.withReadWriteConnection
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.config.app.AppConfig
import net.postchain.core.EContext
import net.postchain.crypto.secp256k1_derivePubKey
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.metrics.BLOCKCHAIN_RID_TAG
import net.postchain.metrics.CHAIN_IID_TAG
import net.postchain.metrics.NODE_PUBKEY_TAG
import net.postchain.rell.RellConfigGen
import net.postchain.rell.module.RellPostchainModuleEnvironment
import net.postchain.rell.module.RellVersions
import net.postchain.rell.runtime.Rt_LogPrinter
import net.postchain.rell.runtime.Rt_PrinterFactory
import net.postchain.rell.sql.SqlInitLogging
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.cli.MainRellCliEnv
import net.postchain.rell.utils.cli.RellBaseCliArgs
import net.postchain.rell.utils.cli.RellCliLogUtils
import net.postchain.rell.utils.cli.RellCliUtils
import picocli.CommandLine
import java.io.File
import java.util.logging.LogManager

private val log = run {
    RellCliLogUtils.initLogging()
    KotlinLogging.logger("PostchainApp")
}

fun main(args: Array<String>) {
    RellCliUtils.runCli(args, RunPostchainAppArgs())
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
    val template = RunPostchainApp.genBlockchainConfigTemplate(nodeAppConf.pubKeyByteArray)
    val bcConf = configGen.makeConfig(template)

    val node = PostchainNode(nodeAppConf, true)
    val chainId = 0L
    val brid = GtvToBlockchainRidFactory.calculateBlockchainRid(bcConf, node.postchainContext.cryptoSystem)

    val pcEnv = RellPostchainModuleEnvironment(
        combinedPrinter = Rt_LogPrinter(),
        copyOutputToPrinter = false,
        sqlLog = args.sqlLog,
        dbInitLogLevel = SqlInitLogging.LOG_STEP_COMPLEX,
    )

    withLoggingContext(
        NODE_PUBKEY_TAG to nodeAppConf.pubKey,
        CHAIN_IID_TAG to chainId.toString(),
        BLOCKCHAIN_RID_TAG to brid.toHex()
    ) {
        RellPostchainModuleEnvironment.set(pcEnv) {
            withReadWriteConnection(node.postchainContext.storage, chainId) { eContext: EContext ->
                BlockchainApi.initializeBlockchain(eContext, brid, override = true, bcConf)
            }

            node.startBlockchain(chainId)

            log.info("")
            log.info("POSTCHAIN APP STARTED")
            log.info("    REST API port:  ${RestApiConfig.fromAppConfig(nodeAppConf).port}")
            log.info("    blockchain RID: ${brid.toHex()}")
            log.info("")
        }
    }
}

object RunPostchainApp {
    fun genBlockchainConfigTemplate(pubKey: ByteArray): Gtv {
        val template0 = genBlockchainConfigTemplateNoRell(pubKey)
        val rell = gtv(
            "version" to gtv(RellVersions.VERSION_STR),
        )
        return RellConfigGen.makeConfig(template0, rell)
    }

    fun genBlockchainConfigTemplateNoRell(pubKey: ByteArray): Gtv {
        return gtv(
            "blockstrategy" to gtv("name" to gtv("net.postchain.base.BaseBlockBuildingStrategy")),
            "configurationfactory" to gtv("net.postchain.gtx.GTXBlockchainConfigurationFactory"),
            "signers" to gtv(listOf(gtv(pubKey))),
            "gtx" to gtv(
                "modules" to gtv(
                    gtv("net.postchain.rell.module.RellPostchainModuleFactory"),
                    gtv("net.postchain.gtx.StandardOpsGTXModule"),
                ),
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
class RunPostchainAppArgs: RellBaseCliArgs() {
    @CommandLine.Option(names = ["--node-config"], paramLabel = "NODE_CONFIG_FILE", required = true,
            description = ["Node configuration (.properties)"])
    var nodeConfigFile: String = ""

    @CommandLine.Parameters(index = "0", paramLabel = "MODULE", description = ["Module name"])
    var module: String = ""

    @CommandLine.Option(names = ["--sqllog"], description = ["Enable SQL logging"])
    var sqlLog = false

    override fun execute() {
        main0(this)
    }
}
