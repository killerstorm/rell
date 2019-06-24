package net.postchain.rell.tools

import mu.KotlinLogging
import net.postchain.common.toHex
import net.postchain.config.app.AppConfig
import net.postchain.config.node.NodeConfigurationProviderFactory
import net.postchain.devtools.TestLauncher
import net.postchain.gtv.gtvml.GtvMLEncoder
import net.postchain.rell.RellCliUtils
import net.postchain.rell.RellConfigGen
import picocli.CommandLine
import java.io.File

private val log = run {
    RellCliUtils.initLogging()
    KotlinLogging.logger("PostchainTest")
}

fun main(args: Array<String>) {
    RellCliUtils.runCli(args, RunPostchainTestArgs()) {
        main0(it)
    }
}

private fun main0(args: RunPostchainTestArgs) {
    RellCliUtils.checkFile(args.rellFile)
    RellCliUtils.checkFile(args.testFile)
    RellCliUtils.checkFile(args.nodeConfigFile)
    if (args.sourceDir != null) RellCliUtils.checkDir(args.sourceDir!!)

    val bcRid = RellCliUtils.parseHex(args.blockchainRid, 32, "blockchain RID")

    log.info("STARTING POSTCHAIN TEST")
    log.info("    rell file: ${File(args.rellFile).absolutePath}")
    log.info("    test file: ${File(args.testFile).absolutePath}")
    log.info("    node config file: ${File(args.nodeConfigFile).absolutePath}")
    log.info("    source directory: ${if (args.sourceDir == null) "" else File(args.sourceDir).absolutePath}")
    log.info("    blockchain RID: ${args.blockchainRid}")
    log.info("")

    val (sourceDir, sourcePath) = RellCliUtils.getSourceDirAndPath(args.sourceDir, args.rellFile)
    RellCliUtils.compileModule(sourceDir, sourcePath, true)

    val nodeAppConf = AppConfig.fromPropertiesFile(args.nodeConfigFile)
    val nodeConfPro = NodeConfigurationProviderFactory.createProvider(nodeAppConf)
    val nodeConf = nodeConfPro.getConfiguration()
    val template = RunPostchainApp.genBlockchainConfigTemplate(nodeConf.pubKeyByteArray)
    val bcConf = RellConfigGen.makeConfig(sourceDir, sourcePath, template)

    val tests = File(args.testFile).readText()

    val bcConfFile = File.createTempFile("test-blockchain-configuration", ".xml")

    val res = try {
        bcConfFile.writeText(GtvMLEncoder.encodeXMLGtv(bcConf))

        val tl = TestLauncher()
        tl.runXMLGTXTests(
                tests,
                bcRid.toHex(),
                args.nodeConfigFile,
                bcConfFile.absolutePath
        )
    } finally {
        bcConfFile.delete()
    }

    processResult(res)
}

private fun processResult(res: TestLauncher.TestOutput) {
    log.info("")

    val msgPassed = "TEST RESULT: " + if (res.passed) "PASSED" else "FAILED"
    if (res.passed) {
        log.info(msgPassed)
    } else {
        log.error(msgPassed)
    }

    if (res.malformedXML) {
        log.error("Malformed XML")
    }

    if (res.initializationError != null) {
        log.error("Initialization error", res.initializationError)
    }

    for (fail in res.transactionFailures) {
        log.error("Transaction failed: blockHeight = ${fail.blockHeight} txIdx = ${fail.txIdx}, error: $${fail.exception}")
    }
}

@CommandLine.Command(name = "RunPostchainTest", description = ["Runs a Rell Postchain test"])
private class RunPostchainTestArgs {
    @CommandLine.Option(names = ["--node-config"], paramLabel =  "NODE_CONFIG_FILE", required = true,
            description =  ["Node configuration (.properties)"])
    var nodeConfigFile: String = ""

    @CommandLine.Option(names = ["--blockchain-rid"], paramLabel =  "BLOCKCHAIN_RID", required = true,
            description =  ["Blockchain RID (hex, 32 bytes)"])
    var blockchainRid: String = ""

    @CommandLine.Option(names = ["--source-dir"], paramLabel =  "SOURCE_DIR",
            description =  ["Source directory used to resolve absolute include paths (default: the directory of the Rell file)"])
    var sourceDir: String? = null

    @CommandLine.Parameters(index = "0", paramLabel = "RELL_FILE", description = ["Rell main file"])
    var rellFile: String = ""

    @CommandLine.Parameters(index = "1", paramLabel = "TEST_FILE", description = ["Test file (XML)"])
    var testFile: String = ""
}
