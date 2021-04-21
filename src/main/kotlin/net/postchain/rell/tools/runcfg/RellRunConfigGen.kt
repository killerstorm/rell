/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.tools.runcfg

import net.postchain.rell.utils.DirFile
import net.postchain.rell.utils.RellBaseCliArgs
import net.postchain.rell.utils.RellCliLogUtils
import net.postchain.rell.utils.RellCliUtils
import picocli.CommandLine
import java.io.File

fun main(args: Array<String>) {
    RellCliLogUtils.initLogging()
    RellCliUtils.runCli(args, RellRunConfigGenCliArgs()) {
        main0(it)
    }
}

private fun main0(args: RellRunConfigGenCliArgs) {
    val runConfigFile = RellCliUtils.checkFile(args.runConfigFile)
    val sourceDir = RellCliUtils.checkDir(args.sourceDir ?: ".")
    val sourceVersion = RellCliUtils.checkVersion(args.sourceVersion)

    val outputDir = if (args.outputDir == null) File(".") else {
        val f = File(args.outputDir!!)
        RellCliUtils.check(f.isDirectory || f.absoluteFile.parentFile.isDirectory) { "Bad output directory: $f" }
        f
    }

    val appConfig = RellRunConfigGenerator.generateCli(sourceDir, runConfigFile, sourceVersion, unitTest = false)
    val files = RellRunConfigGenerator.buildFiles(appConfig.config)

    if (args.dryRun) {
        printFiles(files)
    } else {
        createFiles(outputDir, files)
    }
}

private fun createFiles(outputDir: File, files: Map<String, DirFile>) {
    RellCliUtils.prepareDir(outputDir)
    for ((path, file) in files) {
        val javaFile = File(outputDir, path)
        val dir = javaFile.parentFile
        RellCliUtils.prepareDir(dir)
        file.write(javaFile)
    }
}

private fun printFiles(files: Map<String, DirFile>) {
    for ((path, file) in files) {
        val text = file.previewText()
        println(path)
        println(text)
        println()
    }
}

abstract class RellRunConfigCliArgs: RellBaseCliArgs() {
    @CommandLine.Option(names = ["-s", "--source-version"], paramLabel = "VERSION",
            description = ["Version of Rell the source code is compatible with, X.Y.Z"])
    var sourceVersion: String? = null

    @CommandLine.Parameters(index = "0", paramLabel = "RUN_CONFIG", description = ["Run config file"])
    var runConfigFile: String = ""
}

@CommandLine.Command(name = "RellRunConfigGen", description = ["Generate blockchain config from a run.xml config"])
private class RellRunConfigGenCliArgs: RellRunConfigCliArgs() {
    @CommandLine.Option(names = ["-o", "--output-dir"], paramLabel = "OUTPUT_DIR", description = ["Output directory"])
    var outputDir: String? = null

    @CommandLine.Option(names = ["--dry-run"], description = ["Do not create files"])
    var dryRun: Boolean = false
}
