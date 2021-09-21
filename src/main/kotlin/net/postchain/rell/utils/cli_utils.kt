/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.utils

import mu.KLogging
import net.postchain.base.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.gtv.GtvNull
import net.postchain.rell.compiler.base.core.C_CompilationResult
import net.postchain.rell.compiler.base.core.C_Compiler
import net.postchain.rell.compiler.base.core.C_CompilerModuleSelection
import net.postchain.rell.compiler.base.core.C_CompilerOptions
import net.postchain.rell.compiler.base.utils.C_CommonError
import net.postchain.rell.compiler.base.utils.C_MessageType
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_LangVersion
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.module.RellPostchainModuleEnvironment
import net.postchain.rell.module.RellVersions
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.SqlInitLogging
import net.postchain.rell.tools.RellJavaLoggingInit
import picocli.CommandLine
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

object RellCliUtils: KLogging() {
    fun createSourceDir(sourceDirPath: String?): C_SourceDir {
        val file = if (sourceDirPath == null) File(".") else File(sourceDirPath)
        return C_SourceDir.diskDir(file.absoluteFile)
    }

    fun compileApp(
            sourceDir: String?,
            moduleName: R_ModuleName?,
            quiet: Boolean = false,
            compilerOptions: C_CompilerOptions
    ): R_App {
        val cSourceDir = createSourceDir(sourceDir)
        val modSel = C_CompilerModuleSelection(listOfNotNull(moduleName))
        val res = compileApp(cSourceDir, modSel, quiet, compilerOptions)
        return res
    }

    fun compileApp(
            sourceDir: C_SourceDir,
            modSel: C_CompilerModuleSelection,
            quiet: Boolean,
            compilerOptions: C_CompilerOptions
    ): R_App {
        val res = compile(MainRellCliEnv, sourceDir, modSel, quiet, compilerOptions)
        return res.app!!
    }

    fun compile(
            cliEnv: RellCliEnv,
            sourceDir: C_SourceDir,
            modSel: C_CompilerModuleSelection,
            quiet: Boolean,
            compilerOptions: C_CompilerOptions
    ): C_CompilationResult {
        val res = compile0(compilerOptions, cliEnv, sourceDir, modSel)
        handleCompilationResult(cliEnv, res, quiet)
        return res
    }

    private fun compile0(
            compilerOptions: C_CompilerOptions,
            cliEnv: RellCliEnv,
            sourceDir: C_SourceDir,
            modSel: C_CompilerModuleSelection
    ): C_CompilationResult {
        try {
            val res = C_Compiler.compile(sourceDir, modSel, compilerOptions)
            return res
        } catch (e: C_CommonError) {
            cliEnv.print(errMsg(e.msg), true)
            cliEnv.exit(1)
        }
    }

    private fun handleCompilationResult(cliEnv: RellCliEnv, res: C_CompilationResult, quiet: Boolean) {
        val warnCnt = res.warnings.size
        val errCnt = res.errors.size

        val haveImportantMessages = res.app == null || res.messages.any { !it.type.ignorable }

        if (haveImportantMessages || (!quiet && !res.messages.isEmpty())) {
            // Print all messages only if not quiet or if compilation failed, so warnings are not suppressed by the
            // quiet flag if there is an error.
            for (message in res.messages) {
                cliEnv.print(message.toString(), true)
            }
            if (errCnt > 0 || warnCnt > 0) {
                cliEnv.print("Errors: $errCnt Warnings: $warnCnt", true)
            }
        }

        val app = res.app
        if (app == null) {
            if (errCnt == 0) {
                cliEnv.print(errMsg("Compilation failed"), true)
            }
            cliEnv.exit(1)
        } else if (errCnt > 0) {
            cliEnv.exit(1)
        }
    }

    fun errMsg(msg: String) = "${C_MessageType.ERROR.text}: $msg"

    fun getTarget(sourceDir: String?, module: String): RellCliTarget {
        val sourcePath = checkDir(sourceDir ?: ".").absoluteFile
        val cSourceDir = C_SourceDir.diskDir(sourcePath)
        val moduleName = checkModule(module)
        return RellCliTarget(sourcePath, cSourceDir, listOf(moduleName))
    }

    fun <T> runCli(args: Array<String>, argsObj: T, body: (T) -> Unit) {
        CommonUtils.failIfUnitTest() // Make sure unit test check works

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

    fun createGlobalContext(
            typeCheck: Boolean,
            compilerOptions: C_CompilerOptions,
            runXmlTest: Boolean
    ): Rt_GlobalContext {
        // There was a request to suppress SqlInit logging for unit tests (when run from Eclipse).
        val dbInitLogLevel = when {
            runXmlTest -> SqlInitLogging.LOG_NONE
            else -> RellPostchainModuleEnvironment.DEFAULT_DB_INIT_LOG_LEVEL
        }

        val pcModuleEnv = RellPostchainModuleEnvironment(
                outPrinter = Rt_OutPrinter,
                logPrinter = Rt_LogPrinter(),
                forceTypeCheck = typeCheck,
                dbInitLogLevel = dbInitLogLevel
        )

        return Rt_GlobalContext(
                compilerOptions = compilerOptions,
                outPrinter = Rt_OutPrinter,
                logPrinter = Rt_LogPrinter(),
                typeCheck = typeCheck,
                pcModuleEnv = pcModuleEnv
        )
    }

    fun createChainContext(): Rt_ChainContext {
        val bcRid = BlockchainRid(ByteArray(32))
        return Rt_ChainContext(GtvNull, immMapOf(), bcRid)
    }

    private fun <T> parseCliArgs(args: Array<String>, argsObj: T): T {
        val cl = CommandLine(argsObj)
        try {
            cl.parse(*args)
        } catch (e: CommandLine.PicocliException) {
            cl.usageHelpWidth = 1000
            cl.usage(System.err)
            exitProcess(1)
        }
        return argsObj
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

    fun checkModule(s: String): R_ModuleName {
        val res = R_ModuleName.ofOpt(s)
        return res ?: throw RellCliErr("Invalid module name: '$s'")
    }

    fun checkVersion(s: String?): R_LangVersion {
        s ?: return RellVersions.VERSION
        val ver = try {
            R_LangVersion.of(s)
        } catch (e: IllegalArgumentException) {
            throw RellCliErr("Invalid source version: '$s'")
        }
        if (ver !in RellVersions.SUPPORTED_VERSIONS) {
            throw RellCliErr("Source version not supported: $ver")
        }
        return ver
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

    fun printVersionInfo() {
        val ver = Rt_RellVersion.getInstance()?.buildDescriptor ?: "Rell version unknown"
        logger.info(ver)
    }
}

object RellCliLogUtils {
    fun initLogging() {
        System.setProperty("java.util.logging.config.class", RellJavaLoggingInit::class.java.name)

        val log4jKey = "log4j.configurationFile"
        if (System.getProperty(log4jKey) == null) {
            System.setProperty(log4jKey, "log4j2-rell-console.xml")
        }

        initLoggingStdOutErr()
        disableIllegalAccessWarning()
    }

    private fun initLoggingStdOutErr() {
        fun suppressMessage(s: String): Boolean {
            if (s == "WARNING: sun.reflect.Reflection.getCallerClass is not supported. This will impact performance.") {
                // Log4j: org.apache.logging.log4j.util.StackLocator (log4j-api-2.11.2)
                return true
            }

            return s.startsWith("SLF4J: ")
                    || s.endsWith(" org.apache.commons.beanutils.FluentPropertyBeanIntrospector introspect")
                    || s.startsWith("INFO: Error when creating PropertyDescriptor for public final void org.apache.commons.configuration2.AbstractConfiguration.")
        }

        fun filterStream(outs: OutputStream): PrintStream {
            return object : PrintStream(outs, true) {
                override fun println(s: String?) {
                    if (s != null && suppressMessage(s)) return
                    super.println(s)
                }
            }
        }

        System.setOut(filterStream(System.out))
        System.setErr(filterStream(System.err))
    }

    /**
     * Suppress famous Java 9+ warning about illegal reflective access
     * ("WARNING: An illegal reflective access operation has occurred").
     */
    private fun disableIllegalAccessWarning() {
        try {
            val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null) as sun.misc.Unsafe

            val loggerClass = Class.forName("jdk.internal.module.IllegalAccessLogger")
            val loggerField = loggerClass.getDeclaredField("logger")
            unsafe.putObjectVolatile(loggerClass, unsafe.staticFieldOffset(loggerField), null)
        } catch (e: Throwable) {
            // ignore
        }
    }
}

class RellCliErr(msg: String): RuntimeException(msg)

class RellModuleSources(modules: List<String>, files: Map<String, String>) {
    val modules = modules.toImmList()
    val files = files.toImmMap()
}

class RellCliTarget(val sourcePath: File, val sourceDir: C_SourceDir, val modules: List<R_ModuleName>)

abstract class RellCliEnv {
    abstract fun print(msg: String, err: Boolean = false)
    abstract fun exit(status: Int): Nothing
}

object MainRellCliEnv: RellCliEnv() {
    override fun print(msg: String, err: Boolean) {
        val out = if (err) System.err else System.out
        out.println(msg)
    }

    override fun exit(status: Int): Nothing {
        exitProcess(status)
    }
}

abstract class RellBaseCliArgs {
    @CommandLine.Option(names = ["-d", "--source-dir"], paramLabel = "SOURCE_DIR",
            description = ["Rell source code directory (default: current directory)"])
    var sourceDir: String? = null
}
