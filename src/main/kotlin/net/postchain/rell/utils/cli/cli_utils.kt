/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.utils.cli

import mu.KLogging
import net.postchain.StorageBuilder
import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.config.app.AppConfig
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvNull
import net.postchain.rell.compiler.base.core.C_CompilationResult
import net.postchain.rell.compiler.base.core.C_Compiler
import net.postchain.rell.compiler.base.core.C_CompilerModuleSelection
import net.postchain.rell.compiler.base.core.C_CompilerOptions
import net.postchain.rell.compiler.base.utils.C_CommonError
import net.postchain.rell.compiler.base.utils.C_MessageType
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.lib.test.Rt_BlockRunnerStrategy
import net.postchain.rell.lib.test.Rt_DynamicBlockRunnerStrategy
import net.postchain.rell.lib.test.UnitTestBlockRunner
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_LangVersion
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.module.RellVersions
import net.postchain.rell.runtime.*
import net.postchain.rell.runtime.utils.Rt_SqlManager
import net.postchain.rell.sql.*
import net.postchain.rell.tools.RellJavaLoggingInit
import net.postchain.rell.utils.CommonUtils
import net.postchain.rell.utils.immMapOf
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap
import picocli.CommandLine
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.sql.DriverManager
import java.util.*
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
            cliEnv.error(errMsg(e.msg))
            throw RellCliExitException(1)
        }
    }

    fun handleCompilationResult(cliEnv: RellCliEnv, res: C_CompilationResult, quiet: Boolean): R_App {
        val warnCnt = res.warnings.size
        val errCnt = res.errors.size

        val haveImportantMessages = res.app == null || res.messages.any { !it.type.ignorable }

        if (haveImportantMessages || (!quiet && res.messages.isNotEmpty())) {
            // Print all messages only if not quiet or if compilation failed, so warnings are not suppressed by the
            // quiet flag if there is an error.
            for (message in res.messages) {
                cliEnv.error(message.toString())
            }
            if (errCnt > 0 || warnCnt > 0) {
                cliEnv.error("Errors: $errCnt Warnings: $warnCnt")
            }
        }

        val app = res.app
        if (app == null) {
            if (errCnt == 0) {
                cliEnv.error(errMsg("Compilation failed"))
            }
            throw RellCliExitException(1)
        } else if (errCnt > 0) {
            throw RellCliExitException(1)
        }

        return app
    }

    fun errMsg(msg: String) = "${C_MessageType.ERROR.text}: $msg"

    fun getTarget(sourceDir: String?, module: String): RellCliTarget {
        val sourcePath = checkDir(sourceDir ?: ".").absoluteFile
        val cSourceDir = C_SourceDir.diskDir(sourcePath)
        val moduleName = checkModule(module)
        return RellCliTarget(sourcePath, cSourceDir, listOf(moduleName))
    }

    fun <T: RellCliArgs> runCli(args: Array<String>, argsObj: T) {
        CommonUtils.failIfUnitTest() // Make sure unit test check works

        parseCliArgs(args, argsObj)
        try {
            argsObj.execute()
        } catch (e: RellCliExitException) {
            exitProcess(e.code)
        } catch (e: RellCliException) {
            System.err.println("ERROR: ${e.message}")
            exitProcess(2)
        } catch (e: Throwable) {
            e.printStackTrace()
            exitProcess(3)
        }
    }

    fun <T> runWithSqlManager(
        dbUrl: String?,
        dbProperties: String?,
        sqlLog: Boolean,
        sqlErrorLog: Boolean,
        code: (SqlManager) -> T,
    ): T {
        return if (dbUrl != null) {
            val schema = SqlUtils.extractDatabaseSchema(dbUrl)
            val jdbcProperties = Properties()
            jdbcProperties.setProperty("binaryTransfer", "false")
            DriverManager.getConnection(dbUrl, jdbcProperties).use { con ->
                con.autoCommit = true
                val sqlMgr = ConnectionSqlManager(con, sqlLog)
                runWithSqlManager(schema, sqlMgr, sqlErrorLog, code)
            }
        } else if (dbProperties != null) {
            val appCfg = AppConfig.fromPropertiesFile(dbProperties)
            val storage = StorageBuilder.buildStorage(appCfg)
            val sqlMgr = PostchainStorageSqlManager(storage, sqlLog)
            runWithSqlManager(appCfg.databaseSchema, sqlMgr, sqlErrorLog, code)
        } else {
            code(NoConnSqlManager)
        }
    }

    private fun <T> runWithSqlManager(
        schema: String?,
        sqlMgr: SqlManager,
        logSqlErrors: Boolean,
        code: (SqlManager) -> T,
    ): T {
        val sqlMgr2 = Rt_SqlManager(sqlMgr, logSqlErrors)
        if (schema != null) {
            sqlMgr2.transaction { sqlExec ->
                sqlExec.connection { con ->
                    SqlUtils.prepareSchema(con, schema)
                }
            }
        }
        return code(sqlMgr2)
    }

    fun createGlobalContext(
            compilerOptions: C_CompilerOptions,
            typeCheck: Boolean,
            outPrinter: Rt_Printer = Rt_OutPrinter,
            logPrinter: Rt_Printer = Rt_LogPrinter(),
    ): Rt_GlobalContext {
        return Rt_GlobalContext(
                compilerOptions = compilerOptions,
                outPrinter = outPrinter,
                logPrinter = logPrinter,
                typeCheck = typeCheck,
        )
    }

    fun createChainContext(moduleArgs: Map<R_ModuleName, Rt_Value> = immMapOf()): Rt_ChainContext {
        val bcRid = BlockchainRid(ByteArray(32))
        return Rt_ChainContext(GtvNull, moduleArgs, bcRid)
    }

    fun createSqlContext(app: R_App): Rt_SqlContext {
        val mapping = Rt_ChainSqlMapping(0)
        return Rt_RegularSqlContext.createNoExternalChains(app, mapping)
    }

    fun createBlockRunnerStrategy(
        sourceDir: C_SourceDir,
        app: R_App,
        moduleArgs: Map<R_ModuleName, Gtv>,
    ): Rt_BlockRunnerStrategy {
        val keyPair = UnitTestBlockRunner.getTestKeyPair()
        val blockRunnerModules = getMainModules(app)
        val compileConfig = RellCliCompileConfig.Builder()
            .cliEnv(NullRellCliEnv)
            .moduleArgs0(moduleArgs)
            .build()
        return Rt_DynamicBlockRunnerStrategy(sourceDir, keyPair, blockRunnerModules, compileConfig)
    }

    fun getMainModules(app: R_App): List<R_ModuleName> {
        return app.modules.filter { !it.test && !it.abstract && !it.external }.map { it.name }
    }

    private fun <T> parseCliArgs(args: Array<String>, argsObj: T) {
        val cl = CommandLine(argsObj)
        try {
            cl.parse(*args)
        } catch (e: CommandLine.PicocliException) {
            cl.usageHelpWidth = 1000
            cl.usage(System.err)
            exitProcess(1)
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
            throw RellCliBasicException(msg)
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
        return res ?: throw RellCliBasicException("Invalid module name: '$s'")
    }

    fun checkVersion(s: String?): R_LangVersion {
        s ?: return RellVersions.VERSION
        val ver = try {
            R_LangVersion.of(s)
        } catch (e: IllegalArgumentException) {
            throw RellCliBasicException("Invalid source version: '$s'")
        }
        if (ver !in RellVersions.SUPPORTED_VERSIONS) {
            throw RellCliBasicException("Source version not supported: $ver")
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
                throw RellCliBasicException(s)
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

abstract class RellCliException(msg: String): RuntimeException(msg)
class RellCliBasicException(msg: String): RellCliException(msg)
class RellCliExitException(val code: Int, msg: String = "exit $code"): RellCliException(msg)

class RellModuleSources(modules: List<String>, files: Map<String, String>) {
    val modules = modules.toImmList()
    val files = files.toImmMap()
}

class RellCliTarget(val sourcePath: File, val sourceDir: C_SourceDir, val modules: List<R_ModuleName>)

abstract class RellCliEnv {
    abstract fun print(msg: String)
    abstract fun error(msg: String)
}

object NullRellCliEnv: RellCliEnv() {
    override fun print(msg: String) {
    }

    override fun error(msg: String) {
    }
}

object MainRellCliEnv: RellCliEnv() {
    override fun print(msg: String) {
        println(msg)
    }

    override fun error(msg: String) {
        System.err.println(msg)
    }
}

class Rt_CliEnvPrinter(private val cliEnv: RellCliEnv): Rt_Printer {
    override fun print(str: String) {
        cliEnv.print(str)
    }
}

abstract class RellCliArgs {
    abstract fun execute()
}

abstract class RellBaseCliArgs: RellCliArgs() {
    @CommandLine.Option(names = ["-d", "--source-dir"], paramLabel = "SOURCE_DIR",
            description = ["Rell source code directory (default: current directory)"])
    var sourceDir: String? = null
}
