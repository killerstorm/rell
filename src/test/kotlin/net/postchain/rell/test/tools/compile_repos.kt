package net.postchain.rell.test.tools

import net.postchain.rell.compiler.C_Compiler
import net.postchain.rell.compiler.C_DiskSourceDir
import net.postchain.rell.compiler.C_SourceDir
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.utils.checkEquals
import org.apache.commons.lang3.StringUtils
import java.io.File

fun main(args: Array<String>) {
    val reposDir = if (args.isNotEmpty()) File(args[0]) else File(System.getProperty("user.home"), "rell-repos")
    check(reposDir.isDirectory) { reposDir }
    ReposCompiler().go(reposDir)
}

private class ReposCompiler {
    private var totCount = 0
    private var errCount = 0

    fun go(reposDir: File) {
        for (repoDir in reposDir.listFiles()!!.sorted()) {
            check(repoDir.isDirectory) { repoDir }
            for (verDir in repoDir.listFiles()!!) {
                check(verDir.isDirectory) { verDir }
                val verName = verDir.name
                check(verName.matches(Regex("\\d{4}-\\d{2}-\\d{2}__[0-9a-f]{7}"))) { verDir }
                processVer(repoDir.name, verDir)
            }
        }

        println("---------------------------------------------------------------------------")
        println("Failed $errCount / $totCount")
    }

    private fun processVer(repoName: String, verDir: File) {
        val infoFile = File(verDir, "info.txt")
        check(infoFile.isFile) { infoFile }

        val lines = infoFile.readLines()
        for (line in lines) {
            val parts = StringUtils.splitPreserveAllTokens(line, ":")
            checkEquals(parts.size, 2)
            val srcPath = parts[0]
            val srcDir = File(verDir, srcPath)
            val module = parts[1].trim()
            check(srcDir.isDirectory) { srcDir }
            processSources(srcDir, repoName, verDir.name, srcPath, module)
        }
    }

    private fun processSources(srcDir: File, repoName: String, verName: String, srcPath: String, module: String) {
        val sourceDir: C_SourceDir = C_DiskSourceDir(srcDir)
        val modules = listOf(R_ModuleName.of(module))

        val res = C_Compiler.compile(sourceDir, modules)
        val err = res.app == null || res.errors.isNotEmpty()

        ++totCount
        if (err) {
            ++errCount
        }

        val resStr = if (err) "*** ERROR ***" else "ok"
        for (msg in res.errors) {
            println("    $msg")
        }
        println("$repoName - $verName - $srcPath - [$module] ==> $resStr")
    }
}
