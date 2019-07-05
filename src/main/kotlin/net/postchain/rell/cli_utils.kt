package net.postchain.rell

import com.google.common.collect.ImmutableMap
import net.postchain.common.hexStringToByteArray
import net.postchain.rell.model.R_Module
import net.postchain.rell.parser.*
import net.postchain.rell.tools.RellJavaLoggingInit
import picocli.CommandLine
import java.io.File
import kotlin.system.exitProcess

object RellCliUtils {
    fun compileModule(rellPath: String, sourceDir: String? = null, quiet: Boolean = false): R_Module {
        val (cSourceDir, cSourcePath) = getSourceDirAndPath(sourceDir, rellPath)
        val res = compileModule(cSourceDir, cSourcePath, quiet)
        return res
    }

    fun compileModule(sourceDir: C_SourceDir, sourcePath: C_SourcePath, quiet: Boolean): R_Module {
        val res = compile(sourceDir, sourcePath)

        val warnCnt = res.messages.filter { it.type == C_MessageType.WARNING }.size
        val errCnt = res.messages.filter { it.type == C_MessageType.ERROR }.size

        System.out.flush()

        val haveImportantMessages = res.module == null || res.messages.any { !it.type.ignorable }

        if (haveImportantMessages || (!quiet && !res.messages.isEmpty())) {
            // Print all messages only if not quiet or if compilation failed, so warnings are not suppressed by the
            // quiet flag if there is an error.
            for (message in res.messages) {
                System.err.println(message)
            }
            System.err.println("Errors: $errCnt Warnings: $warnCnt")
        }

        val module = res.module
        if (module == null) {
            if (errCnt == 0) System.err.println(errMsg("compilation failed"))
            exitProcess(1)
        } else if (errCnt > 0) {
            exitProcess(1)
        }

        return module
    }

    private fun compile(sourceDir: C_SourceDir, sourcePath: C_SourcePath): C_CompilationResult {
        try {
            val res = C_Compiler.compile(sourceDir, sourcePath)
            return res
        } catch (e: C_CommonError) {
            System.err.println(errMsg(e.msg))
            exitProcess(1)
        }
    }

    private fun errMsg(msg: String) = "${C_MessageType.ERROR.text}: $msg"

    fun getSourceDirAndPath(sourceDir: String?, rellFile: String): Pair<C_SourceDir, C_SourcePath> {
        val (dir, path) = getSourceDirAndPath0(sourceDir, rellFile)
        val resDir = C_DiskSourceDir(dir)
        val resPath = C_SourcePath.parse(path)
        return Pair(resDir, resPath)
    }

    private fun getSourceDirAndPath0(sourceDir: String?, rellFile: String): Pair<File, String> {
        if (sourceDir == null) {
            val file = File(rellFile)
            val dir = file.absoluteFile.parentFile
            val path = file.name
            return Pair(dir, path)
        }

        val dir = File(sourceDir).absoluteFile
        val file = File(rellFile)
        val absFile = file.absoluteFile

        val relFile = relativeFile(dir, absFile)
        if (relFile == null) {
            throw RellCliErr("File $file is not in the directory $dir")
        }

        return Pair(dir, relFile.path)
    }

    private fun relativeFile(dir: File, file: File): File? {
        val dirPath = filePathToList(dir)
        val filePath = filePathToList(file)

        if (filePath.size <= dirPath.size) return null
        if (filePath.subList(0, dirPath.size) != dirPath) return null

        var res = File(filePath[dirPath.size])
        for (s in filePath.subList(dirPath.size + 1, filePath.size)) {
            res = File(res, s)
        }

        return res
    }

    private fun filePathToList(file: File): List<String> {
        val res = mutableListOf<String>()
        var f: File? = file
        while (f != null) {
            res.add(f.name)
            f = f.parentFile
        }
        res.reverse()
        return res
    }

    fun <T> runCli(args: Array<String>, argsObj: T, body: (T) -> Unit) {
        val argsEx = parseCliArgs(args, argsObj)
        try {
            body(argsEx)
        } catch (e: RellCliErr) {
            System.err.println("ERROR: ${e.message}")
            exitProcess(2)
        } catch (e: Throwable) {
            e.printStackTrace()
            exitProcess(3)
        }
    }

    private fun <T> parseCliArgs(args: Array<String>, argsObj: T): T {
        val cl = CommandLine(argsObj)
        try {
            if (args.size == 0) throw CommandLine.PicocliException("no args")
            cl.parse(*args)
        } catch (e: CommandLine.PicocliException) {
            cl.usageHelpWidth = 1000
            cl.usage(System.err)
            exitProcess(1)
        }
        return argsObj
    }

    fun initLogging() {
        System.setProperty("java.util.logging.config.class", RellJavaLoggingInit::class.java.name)

        val log4jKey = "log4j.configurationFile"
        if (System.getProperty(log4jKey) == null) {
            System.setProperty(log4jKey, "log4j2-rell-console.xml")
        }
    }

    fun parseHex(s: String, sizeBytes: Int, msg: String): ByteArray {
        val bytes = calc({ s.hexStringToByteArray() }, Exception::class.java) { "Invalid $msg: '$s'" }
        check(bytes.size == sizeBytes) { "Invalid $msg size: ${bytes.size}" }
        return bytes
    }

    fun prepareDir(dir: File) {
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create directory: $dir" }
    }

    fun check(b: Boolean, msgCode: () -> String) {
        if (!b) {
            val msg = msgCode()
            throw RellCliErr(msg)
        }
    }

    fun checkDir(path: String): File {
        val file = File(path)
        check(file.isDirectory) { "Directory not found: $path" }
        return file
    }

    fun checkFile(path: String): File {
        val file = File(path)
        check(file.isFile) { "File not found: $path" }
        return file
    }

    fun <T> calc(calc: () -> T, errType: Class<out Throwable>, msg: () -> String): T {
        try {
            val res = calc()
            return res
        } catch (e: Throwable) {
            if (errType.isInstance(e)) {
                val s = msg()
                throw RellCliErr(s)
            }
            throw e
        }
    }
}

class RellCliErr(msg: String): RuntimeException(msg)

class RellModuleSources(val mainFile: String, files: Map<String, String>) {
    val files: Map<String, String> = ImmutableMap.copyOf(files)
}
