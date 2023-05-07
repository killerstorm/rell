/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.testutils.tools

import net.postchain.rell.base.compiler.base.core.C_Compiler
import net.postchain.rell.base.compiler.base.core.C_CompilerModuleSelection
import net.postchain.rell.base.compiler.base.core.C_CompilerOptions
import net.postchain.rell.base.compiler.base.utils.C_SourceDir
import net.postchain.rell.base.model.R_LangVersion
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.utils.RellVersions
import net.postchain.rell.base.utils.checkEquals
import net.postchain.rell.base.utils.toImmMultimapKey
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
        val infoFile: File,
        val gitVer: String,
        val rellVer: R_LangVersion?,
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
                val (gitVer, rellVer) = parseVerDirName(verDir)
                verDirs.add(VerDir(repoDir.name, verDir, File(verDir, "info.txt"), gitVer, rellVer))
            }
        }

        val noInfo = verDirs.filter { !it.infoFile.isFile }
        if (noInfo.isNotEmpty()) {
            println("No info.txt: ${noInfo.size}")
            for (verDir in noInfo.sortedBy { it.dir }) println(verDir.dir)
            exitProcess(1)
        }

        val repoMap = verDirs.toImmMultimapKey { it.repo }
        for (repo in repoMap.keySet().sorted()) {
            val verMap = repoMap[repo].toImmMultimapKey { it.gitVer }
            for (gitVer in verMap.keySet().sorted()) {
                val vers = verMap[gitVer].sortedBy { it.rellVer ?: R_LangVersion.of("0.0.0") }.reversed()
                val verDir = vers.firstOrNull { it.rellVer == null || RellVersions.VERSION >= it.rellVer }
                if (verDir != null) {
                    processVer(verDir)
                }
            }
        }

        println("---------------------------------------------------------------------------")
        println("Failed $errCount / $totCount")
    }

    private fun parseVerDirName(verDir: File): Pair<String, R_LangVersion?> {
        val verName = verDir.name
        check(verName.matches(Regex("\\d{4}-\\d{2}-\\d{2}__[0-9a-f]{7}(__[0-9]+[.][0-9]+[.][0-9]+)?"))) { verDir }
        val parts = verName.split("__")
        check(parts.size == 2 || parts.size == 3) { parts }
        val gitVer = parts[0] + "__" + parts[1]
        val rellVer = if (parts.size == 2) null else R_LangVersion.of(parts[2])
        return gitVer to rellVer
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
        println("$repoName - $verName - ${runInfo.srcPath} - [${runInfo.module}] ==> $resStr")

        for (msg in res.errors) {
            println("    $msg")
        }
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
