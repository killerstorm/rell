package net.postchain.rell.tools

import mu.KotlinLogging
import net.postchain.config.app.AppConfig
import net.postchain.config.node.NodeConfigurationProviderFactory
import net.postchain.core.UserMistake
import net.postchain.devtools.TestLauncher
import net.postchain.gtv.gtvml.GtvMLEncoder
import net.postchain.rell.PostchainUtils
import net.postchain.rell.RellBaseCliArgs
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
    val target = RellCliUtils.getTarget(args.sourceDir, args.module)
    RellCliUtils.checkFile(args.testFile)
    RellCliUtils.checkFile(args.nodeConfigFile)

    log.info("STARTING POSTCHAIN TEST")
    log.info("    source directory: ${target.sourcePath.absolutePath}")
    log.info("    module:           ${args.module}")
    log.info("    test file:        ${File(args.testFile).absolutePath}")
    log.info("    node config file: ${File(args.nodeConfigFile).absolutePath}")
    log.info("")

    val configGen = RellConfigGen.create(target)

    val nodeAppConf = AppConfig.fromPropertiesFile(args.nodeConfigFile)
    val nodeConfPro = NodeConfigurationProviderFactory.createProvider(nodeAppConf)
    val nodeConf = nodeConfPro.getConfiguration()
    val template = RunPostchainApp.genBlockchainConfigTemplate(nodeConf.pubKeyByteArray)
    val bcConf = configGen.makeConfig(template)

    val tests = File(args.testFile).readText()

    val bcConfFile = File.createTempFile("test-blockchain-configuration", ".xml")

    val bcRid = PostchainUtils.calcBlockchainRid(bcConf)

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
        val ex = fail.exception
        val errorMsg = if (ex is UserMistake) ex.message else "$ex"
        log.error("")
        log.error("Transaction failed:")
        log.error("    blockHeight: ${fail.blockHeight}")
        log.error("    txIdx: ${fail.txIdx}")
        log.error("    error: $errorMsg")
    }
}

@CommandLine.Command(name = "PostchainTestLaunch", description = ["Runs a Rell Postchain test"])
private class RunPostchainTestArgs: RellBaseCliArgs() {
    @CommandLine.Option(names = ["--node-config"], paramLabel =  "NODE_CONFIG_FILE", required = true,
            description =  ["Node configuration (.properties)"])
    var nodeConfigFile: String = ""

    @CommandLine.Parameters(index = "0", paramLabel = "MODULE", description = ["Module name"])
    var module: String = ""

    @CommandLine.Parameters(index = "1", paramLabel = "TEST_FILE", description = ["Test file (XML)"])
    var testFile: String = ""
}
