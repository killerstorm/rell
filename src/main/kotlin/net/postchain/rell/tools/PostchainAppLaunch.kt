package net.postchain.rell.tools

import mu.KotlinLogging
import net.postchain.base.secp256k1_derivePubKey
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.config.app.AppConfig
import net.postchain.config.node.NodeConfigurationProviderFactory
import net.postchain.devtools.PostchainTestNode
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.rell.RellBaseCliArgs
import net.postchain.rell.RellCliUtils
import net.postchain.rell.RellConfigGen
import net.postchain.rell.runtime.Rt_LogPrinter
import net.postchain.rell.runtime.Rt_PrinterFactory
import net.postchain.rell.sql.SqlInit
import picocli.CommandLine
import java.io.File
import java.util.logging.LogManager

private val log = run {
    RellCliUtils.initLogging()
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

    val bcRid = RellCliUtils.parseHex(args.blockchainRid, 32, "blockchain RID")

    log.info("STARTING POSTCHAIN APP")
    log.info("    source directory: ${target.sourcePath.absolutePath}")
    log.info("    module:           ${args.module}")
    log.info("    node config file: ${File(args.nodeConfigFile).absolutePath}")
    log.info("    blockchain RID:   ${args.blockchainRid}")
    log.info("")

    val configGen = RellConfigGen.create(target)

    val nodeAppConf = AppConfig.fromPropertiesFile(args.nodeConfigFile)
    val nodeConfPro = NodeConfigurationProviderFactory.createProvider(nodeAppConf)

    val nodeConf = nodeConfPro.getConfiguration()
    val template = RunPostchainApp.genBlockchainConfigTemplate(nodeConf.pubKeyByteArray)
    val bcConf = configGen.makeConfig(template)

    val node = PostchainTestNode(nodeConfPro, true)
    node.addBlockchain(0, bcRid, bcConf)
    node.startBlockchain(0)

    log.info("")
    log.info("POSTCHAIN APP STARTED")
    log.info("    REST API port: ${nodeConf.restApiPort}")
    log.info("")
}

object RunPostchainApp {
    fun genBlockchainConfigTemplate(pubKey: ByteArray): Gtv {
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
                                "dbInitLogLevel" to gtv(SqlInit.LOG_STEP_COMPLEX.toLong()),
                                "combinedPrinterFactoryClass" to gtv(Rt_RellAppPrinterFactory::class.java.name),
                                "copyOutputToCombinedPrinter" to gtv(false)
                        )
                )
        )
    }

    fun calcPubKey(privKey: String): String {
        check(privKey.length == 64)
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
    override fun newPrinter() = Rt_LogPrinter("Rell")
}

@CommandLine.Command(name = "PostchainAppLaunch", description = ["Runs a Rell Postchain app"])
private class RunPostchainAppArgs: RellBaseCliArgs() {
    @CommandLine.Option(names = ["--node-config"], paramLabel =  "NODE_CONFIG_FILE", required = true,
            description =  ["Node configuration (.properties)"])
    var nodeConfigFile: String = ""

    @CommandLine.Option(names = ["--blockchain-rid"], paramLabel =  "BLOCKCHAIN_RID", required = true,
            description =  ["Blockchain RID (hex, 32 bytes)"])
    var blockchainRid: String = ""

    @CommandLine.Parameters(index = "0", paramLabel = "MODULE", description = ["Module name"])
    var module: String = ""
}
