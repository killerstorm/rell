package net.postchain.rell

import mu.KLogging
import net.postchain.common.hexStringToByteArray
import net.postchain.rell.compiler.*
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.runtime.Rt_RellVersion
import net.postchain.rell.tools.RellJavaLoggingInit
import picocli.CommandLine
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

object RellCliUtils: KLogging() {
    fun createSourceDir(sourceDirPath: String?): C_SourceDir {
        val file = if (sourceDirPath == null) File(".") else File(sourceDirPath)
        return C_DiskSourceDir(file.absoluteFile)
    }

    fun compileApp(sourceDir: String?, moduleName: R_ModuleName?, quiet: Boolean = false): R_App {
        val cSourceDir = createSourceDir(sourceDir)
        val modules = listOf(moduleName).filterNotNull()
        val res = compileApp(cSourceDir, modules, quiet)
        return res
    }

    fun compileApp(sourceDir: C_SourceDir, modules: List<R_ModuleName>, quiet: Boolean): R_App {
        val res = compile(sourceDir, modules, quiet)
        return res.app!!
    }

    fun compile(sourceDir: C_SourceDir, modules: List<R_ModuleName>, quiet: Boolean): C_CompilationResult {
        val res = compile0(sourceDir, modules)

        val warnCnt = res.warnings.size
        val errCnt = res.errors.size

        System.out.flush()

        val haveImportantMessages = res.app == null || res.messages.any { !it.type.ignorable }

        if (haveImportantMessages || (!quiet && !res.messages.isEmpty())) {
            // Print all messages only if not quiet or if compilation failed, so warnings are not suppressed by the
            // quiet flag if there is an error.
            for (message in res.messages) {
                System.err.println(message)
            }
            System.err.println("Errors: $errCnt Warnings: $warnCnt")
        }

        val app = res.app
        if (app == null) {
            if (errCnt == 0) System.err.println(errMsg("compilation failed"))
            exitProcess(1)
        } else if (errCnt > 0) {
            exitProcess(1)
        }

        return res
    }

    private fun compile0(sourceDir: C_SourceDir, modules: List<R_ModuleName>): C_CompilationResult {
        try {
            val res = C_Compiler.compile(sourceDir, modules)
            return res
        } catch (e: C_CommonError) {
            System.err.println(errMsg(e.msg))
            exitProcess(1)
        }
    }

    private fun errMsg(msg: String) = "${C_MessageType.ERROR.text}: $msg"

    fun getTarget(sourceDir: String?, module: String): RellCliTarget {
        val sourcePath = checkDir(sourceDir ?: ".").absoluteFile
        val cSourceDir = C_DiskSourceDir(sourcePath)
        val moduleName = checkModule(module)
        return RellCliTarget(sourcePath, cSourceDir, listOf(moduleName))
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

abstract class RellBaseCliArgs {
    @CommandLine.Option(names = ["-d", "--source-dir"], paramLabel = "SOURCE_DIR",
            description = ["Rell source code directory (default: current directory)"])
    var sourceDir: String? = null
}
