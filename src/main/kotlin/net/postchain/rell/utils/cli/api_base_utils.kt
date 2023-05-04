/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.utils.cli

import net.postchain.gtv.GtvNull
import net.postchain.rell.compiler.base.core.C_CompilationResult
import net.postchain.rell.compiler.base.core.C_CompilerOptions
import net.postchain.rell.compiler.base.utils.C_MessageType
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.immMapOf
import java.io.File

object RellApiBaseUtils {
    fun createSourceDir(sourceDirPath: String?): C_SourceDir {
        val file = if (sourceDirPath == null) File(".") else File(sourceDirPath)
        return C_SourceDir.diskDir(file.absoluteFile)
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
            throw RellCliExitException(1, "Compilation failed")
        } else if (errCnt > 0) {
            throw RellCliExitException(1, "Compilation failed")
        }

        return app
    }

    fun errMsg(msg: String) = "${C_MessageType.ERROR.text}: $msg"

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
        return Rt_ChainContext(GtvNull, moduleArgs, Rt_ChainContext.ZERO_BLOCKCHAIN_RID)
    }

    fun createSqlContext(app: R_App): Rt_SqlContext {
        val mapping = Rt_ChainSqlMapping(0)
        return Rt_RegularSqlContext.createNoExternalChains(app, mapping)
    }

    fun getMainModules(app: R_App): List<R_ModuleName> {
        return app.modules.filter { !it.test && !it.abstract && !it.external }.map { it.name }
    }
}

abstract class RellCliException(msg: String): RuntimeException(msg)
class RellCliBasicException(msg: String): RellCliException(msg)
class RellCliExitException(val code: Int, msg: String = "exit $code"): RellCliException(msg)

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
