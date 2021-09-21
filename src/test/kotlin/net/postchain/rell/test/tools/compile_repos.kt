/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.test.tools

import net.postchain.rell.compiler.base.core.C_Compiler
import net.postchain.rell.compiler.base.core.C_CompilerModuleSelection
import net.postchain.rell.compiler.base.core.C_CompilerOptions
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.model.R_LangVersion
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.utils.checkEquals
import org.apache.commons.lang3.StringUtils
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val reposDir = if (args.isNotEmpty()) File(args[0]) else File(System.getProperty("user.home"), "rell-repos")
    check(reposDir.isDirectory) { reposDir }
    ReposCompiler().go(reposDir)
}

private class VerDir(
        val repo: String,
        val dir: File,
        val infoFile: File
)

private class RunInfo(
        val srcPath: String,
        val srcDir: File,
        val module: String,
        val test: Boolean,
        val rellVer: R_LangVersion?
)

private class ReposCompiler {
    private var totCount = 0
    private var errCount = 0

    fun go(reposDir: File) {
        val verDirs = mutableListOf<VerDir>()

        for (repoDir in reposDir.listFiles()!!.sorted()) {
            check(repoDir.isDirectory) { repoDir }
            for (subFile in repoDir.listFiles()!!) {
                if (!subFile.isDirectory) continue
                val verDir = subFile
                check(verDir.isDirectory) { verDir }
                val verName = verDir.name
                check(verName.matches(Regex("\\d{4}-\\d{2}-\\d{2}__[0-9a-f]{7}"))) { verDir }
                verDirs.add(VerDir(repoDir.name, verDir, File(verDir, "info.txt")))
            }
        }

        val noInfo = verDirs.filter { !it.infoFile.isFile }
        if (noInfo.isNotEmpty()) {
            println("No info.txt: ${noInfo.size}")
            for (verDir in noInfo) println(verDir.dir)
            exitProcess(1)
        }

        for (verDir in verDirs) {
            processVer(verDir)
        }

        println("---------------------------------------------------------------------------")
        println("Failed $errCount / $totCount")
    }

    private fun processVer(verDir: VerDir) {
        val infoFile = verDir.infoFile
        check(infoFile.isFile) { infoFile }

        val lines = infoFile.readLines()
        for (line in lines) {
            val runInfo = parseRunInfo(verDir, line)
            processSources(verDir.repo, verDir.dir.name, runInfo)
        }
    }

    private fun processSources(repoName: String, verName: String, runInfo: RunInfo) {
        val sourceDir = C_SourceDir.diskDir(runInfo.srcDir)

        val modules = listOf(R_ModuleName.of(runInfo.module))
        val modSel = if (runInfo.test) {
            C_CompilerModuleSelection(listOf(), modules)
        } else {
            C_CompilerModuleSelection(modules, modules)
        }

        val opts = if (runInfo.rellVer == null) {
            C_CompilerOptions.DEFAULT
        } else {
            C_CompilerOptions.forLangVersion(runInfo.rellVer)
        }

        val res = C_Compiler.compile(sourceDir, modSel, opts)

        val err = res.app == null || res.errors.isNotEmpty()

        ++totCount
        if (err) {
            ++errCount
        }

        val resStr = if (err) "*** ERROR ***" else "ok"
        for (msg in res.errors) {
            println("    $msg")
        }
        println("$repoName - $verName - ${runInfo.srcPath} - [${runInfo.module}] ==> $resStr")
    }
}

private fun parseRunInfo(verDir: VerDir, line: String): RunInfo {
    val parts = StringUtils.splitPreserveAllTokens(line, ":")
    check(parts.size >= 2 && parts.size <= 4) { parts.toList() }

    val srcPath = parts[0]
    val srcDir = File(verDir.dir, srcPath)
    check(srcDir.isDirectory) { srcDir }

    val module = parts[1].trim()

    val test = if (parts.size < 3) false else {
        val s = parts[2]
        if (s == "") false else {
            checkEquals(s, "test")
            true
        }
    }

    val rellVer = if (parts.size < 4) null else R_LangVersion.of(parts[3])

    return RunInfo(srcPath, srcDir, module, test, rellVer)
}
