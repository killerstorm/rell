package net.postchain.rell.tools.runcfg

import net.postchain.rell.RellBaseCliArgs
import net.postchain.rell.RellCliUtils
import picocli.CommandLine
import java.io.File

fun main(args: Array<String>) {
    RellCliUtils.initLogging()
    RellCliUtils.runCli(args, RellRunConfigGenArgs()) {
        main0(it)
    }
}

private fun main0(args: RellRunConfigGenArgs) {
    val runConfigFile = RellCliUtils.checkFile(args.runConfigFile)
    val sourceDir = RellCliUtils.checkDir(args.sourceDir ?: ".")

    val outputDir = if (args.outputDir == null) File(".") else {
        val f = File(args.outputDir!!)
        RellCliUtils.check(f.isDirectory || f.absoluteFile.parentFile.isDirectory) { "Bad output directory: $f" }
        f
    }

    val appConfig = RellRunConfigGenerator.generateCli(sourceDir, runConfigFile)
    val files = RellRunConfigGenerator.buildFiles(appConfig.config)

    if (args.dryRun) {
        printFiles(files)
    } else {
        createFiles(outputDir, files)
    }
}

private fun createFiles(outputDir: File, files: Map<String, Any>) {
    RellCliUtils.prepareDir(outputDir)
    for ((path, text) in files) {
        val file = File(outputDir, path)
        val dir = file.parentFile
        RellCliUtils.prepareDir(dir)
        if (text is String)
            file.writeText(text)
        else if (text is ByteArray)
            file.writeBytes(text)
    }
}

private fun printFiles(files: Map<String, Any>) {
    for ((file, text) in files) {
        println(file)
        if (text is String) println(text)
        else {
            println("BINARY DATA OMITTED")
        }
        println()
    }
}

@CommandLine.Command(name = "RellRunConfigGen", description = ["Generate blockchain config from a run config"])
private class RellRunConfigGenArgs: RellBaseCliArgs() {
    @CommandLine.Option(names = ["-o", "--output-dir"], paramLabel =  "OUTPUT_DIR", description = ["Output directory"])
    var outputDir: String? = null

    @CommandLine.Option(names = ["--dry-run"], paramLabel = "DRY_RUN", description = ["Do not create files"])
    var dryRun: Boolean = false

    @CommandLine.Parameters(index = "0", paramLabel = "RUN_CONFIG", description = ["Run config file"])
    var runConfigFile: String = ""
}
