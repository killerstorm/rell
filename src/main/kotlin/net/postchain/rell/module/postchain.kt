/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.module

import mu.KLogging
import net.postchain.base.BaseBlockBuilderExtension
import net.postchain.base.data.DatabaseAccess
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.common.types.WrappedByteArray
import net.postchain.core.EContext
import net.postchain.core.Transactor
import net.postchain.core.TxEContext
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvDictionary
import net.postchain.gtx.GTXModule
import net.postchain.gtx.GTXModuleFactory
import net.postchain.gtx.GTXOperation
import net.postchain.gtx.NON_STRICT_QUERY_ARGUMENT
import net.postchain.gtx.data.ExtOpData
import net.postchain.gtx.special.GTXSpecialTxExtension
import net.postchain.rell.compiler.base.core.C_CompilationResult
import net.postchain.rell.compiler.base.core.C_Compiler
import net.postchain.rell.compiler.base.core.C_CompilerOptions
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.runtime.utils.Rt_SqlExecutor
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.sql.ConnectionSqlExecutor
import net.postchain.rell.sql.NullSqlInitProjExt
import net.postchain.rell.sql.SqlInit
import net.postchain.rell.sql.SqlInitLogging
import net.postchain.rell.utils.*
import org.apache.commons.lang3.time.FastDateFormat

private fun convertArgs(ctx: GtvToRtContext, params: List<R_Param>, args: List<Gtv>): List<Rt_Value> {
    return args.mapIndexed { index, arg ->
        val param = params[index]
        val type = param.type
        val subCtx = ctx.updateSymbol(GtvToRtSymbol_Param(param), true)
        type.gtvToRt(subCtx, arg)
    }
}

private class ErrorHandler(val printer: Rt_Printer, private val wrapCtErrors: Boolean, private val wrapRtErrors: Boolean) {
    private var ignore = false

    fun ignoreError() {
        ignore = true
    }

    fun <T> handleError(msgSupplier: () -> String, code: () -> T): T {
        try {
            val res = code()
            return res
        } catch (e: UserMistake) {
            val msg = processError(msgSupplier, e)
            throw UserMistake(msg, e)
        } catch (e: ProgrammerMistake) {
            val msg = processError(msgSupplier, e)
            throw ProgrammerMistake(msg, e)
        } catch (e: Rt_Exception) {
            val msg = processError(msgSupplier, e, e.info.stack)
            throw if (wrapRtErrors) UserMistake(msg) else e
        } catch (e: C_Error){
            val msg = processError(msgSupplier, e)
            throw if (wrapCtErrors) UserMistake(msg) else e
        } catch (e: Exception) {
            val msg = processError(msgSupplier, e)
            throw ProgrammerMistake(msg, e)
        } catch (e: Throwable) {
            val msg = processError(msgSupplier, e)
            throw ProgrammerMistake(msg)
        }
    }

    private fun processError(msgSupplier: () -> String, e: Throwable, stack: List<R_StackPos> = listOf()): String {
        val subMsg = msgSupplier()
        val errMsg = e.message ?: e.toString()
        val headMsg = "$subMsg: $errMsg"

        if (!ignore) {
            val fullMsg = Rt_Utils.appendStackTrace(headMsg, stack)
            printer.print("ERROR $fullMsg")
        }
        ignore = false

        val resMsg = if (stack.isEmpty()) headMsg else "[${stack[0]}] $headMsg"
        return resMsg
    }
}

private class Rt_MultiPrinter(private val printers: Collection<Rt_Printer>): Rt_Printer {
    companion object: KLogging()

    constructor(vararg printers: Rt_Printer): this(printers.toList())

    override fun print(str: String) {
        for (printer in printers) {
            try {
                printer.print(str)
            } catch (e: Throwable) {
                logger.error("$e")
            }
        }
    }
}

class Rt_TimestampPrinter(private val printer: Rt_Printer): Rt_Printer {
    companion object: KLogging() {
        private val DATE_FMT = FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss,SSS")
    }

    override fun print(str: String) {
        val time = System.currentTimeMillis()
        val timeStr = DATE_FMT.format(time)
        val str2 = "$timeStr $str"
        printer.print(str2)
    }
}

private class RellGTXOperation(
        private val module: RellPostchainModule,
        private val rOperation: R_OperationDefinition,
        private val errorHandler: ErrorHandler,
        opData: ExtOpData
): GTXOperation(opData) {
    private val gtvToRtCtx = LateInit<GtvToRtContext>()
    private val args = LateInit<List<Rt_Value>>()

    override fun checkCorrectness() {
        handleError {
            val params = rOperation.params()
            if (data.args.size != params.size) {
                throw Rt_Exception.common("operation:[${rOperation.appLevelName}]:arg_count:${data.args.size}:${params.size}",
                                    "Wrong argument count: ${data.args.size} instead of ${params.size}")
            }
            if (!gtvToRtCtx.isSet()) {
                gtvToRtCtx.set(GtvToRtContext.make(GTV_OPERATION_PRETTY))
            }
            if (!args.isSet()) {
                args.set(convertArgs(gtvToRtCtx.get(), params, data.args.toList()))
            }
        }
    }

    override fun apply(ctx: TxEContext): Boolean {
        handleError {
            val blockHeight = DatabaseAccess.of(ctx).getLastBlockHeight(ctx)
            val txCtx = module.env.txContextFactory.createTxContext(ctx)

            val opCtx = Rt_PostchainOpContext(
                    txCtx = txCtx,
                    lastBlockTime = ctx.timestamp,
                    transactionIid = ctx.txIID,
                    blockHeight = blockHeight,
                    opIndex = data.opIndex,
                    signers = data.signers.map { it.toBytes() }.toImmList(),
                    allOperations = data.operations.toImmList(),
            )

            val heightProvider = Rt_TxChainHeightProvider(ctx)
            val exeCtx = module.createExecutionContext(ctx, opCtx, heightProvider)
            gtvToRtCtx.get().finish(exeCtx)
            rOperation.call(exeCtx, args.get())
        }
        return true
    }

    private fun <T> handleError(code: () -> T): T {
        return errorHandler.handleError({ "Operation '${rOperation.appLevelName}' failed" }) {
            code()
        }
    }

    private class Rt_TxChainHeightProvider(private val ctx: TxEContext): Rt_ChainHeightProvider {
        override fun getChainHeight(rid: WrappedByteArray, id: Long): Long? {
            return try {
                ctx.getChainDependencyHeight(id)
            } catch (e: Exception) {
                null
            }
        }
    }
}

private class RellModuleConfig(
        val sqlLogging: Boolean,
        val typeCheck: Boolean,
        val dbInitLogLevel: Int,
        val compilerOptions: C_CompilerOptions
)

private class RellPostchainModule(
        val env: RellPostchainModuleEnvironment,
        private val rApp: R_App,
        chainCtx: Rt_ChainContext,
        private val chainDeps: Map<String, ByteArray>,
        outPrinter: Rt_Printer,
        logPrinter: Rt_Printer,
        private val errorHandler: ErrorHandler,
        val config: RellModuleConfig,
): GTXModule {
    private val operationNames = rApp.operations.keys.map { it.str() }.toImmSet()
    private val queryNames = rApp.queries.keys.map { it.str() }.toImmSet()

    private val globalCtx = Rt_GlobalContext(
            compilerOptions = config.compilerOptions,
            outPrinter = outPrinter,
            logPrinter = logPrinter,
            typeCheck = config.typeCheck,
    )

    private val appCtx = Rt_AppContext(
            globalCtx,
            chainCtx,
            rApp,
            repl = false,
            test = false,
            replOut = null,
    )

    override fun getOperations(): Set<String> {
        return operationNames
    }

    override fun getQueries(): Set<String> {
        return queryNames
    }

    override fun initializeDB(ctx: EContext) {
        if (!env.dbInitEnabled) {
            return
        }

        errorHandler.handleError({ "Database initialization failed" }) {
            val heightProvider = Rt_ConstantChainHeightProvider(-1)
            val exeCtx = createExecutionContext(ctx, Rt_NullOpContext, heightProvider)
            val initLogging = SqlInitLogging.ofLevel(config.dbInitLogLevel)

            // Using the null ProjExt, because Postchain must do the initialization itself.
            SqlInit.init(exeCtx, NullSqlInitProjExt, initLogging)
        }
    }

    override fun makeTransactor(opData: ExtOpData): Transactor {
        return errorHandler.handleError({ "Operation '${opData.opName}' failed" }) {
            val rOperation = getRoutine("Operation", rApp.operations, opData.opName)
            RellGTXOperation(this, rOperation, errorHandler, opData)
        }
    }

    override fun query(ctxt: EContext, name: String, args: Gtv): Gtv {
        return errorHandler.handleError({ "Query '$name' failed" }) {
            query0(ctxt, name, args)
        }
    }

    private fun query0(ctx: EContext, name: String, args: Gtv): Gtv {
        val rQuery = getRoutine("Query", rApp.queries, name)

        val heightProvider = Rt_ConstantChainHeightProvider(Long.MAX_VALUE)

        val exeCtx = createExecutionContext(ctx, Rt_NullOpContext, heightProvider)
        val rtArgs = translateQueryArgs(exeCtx, rQuery, args)
        val rtResult = rQuery.call(exeCtx, rtArgs)

        val type = rQuery.type()
        val gtvResult = type.rtToGtv(rtResult, GTV_QUERY_PRETTY)
        return gtvResult
    }

    private fun <T> getRoutine(kind: String, map: Map<R_MountName, T>, name: String): T {
        val mountName = R_MountName.ofOpt(name)
        mountName ?: throw UserMistake("$kind mount name is invalid: '$name")

        val r = map[mountName]
        return r ?: throw UserMistake("$kind not found: '$name'")
    }

    fun createExecutionContext(
            eCtx: EContext,
            opCtx: Rt_OpContext,
            heightProvider: Rt_ChainHeightProvider
    ): Rt_ExecutionContext {
        val sqlMapping = Rt_ChainSqlMapping(eCtx.chainID)

        val chainDeps = chainDeps.mapValues { (_, rid) -> Rt_ChainDependency(rid) }

        val sqlExec = Rt_SqlExecutor(ConnectionSqlExecutor(eCtx.conn, config.sqlLogging), globalCtx.logSqlErrors)
        val sqlCtx = Rt_RegularSqlContext.create(rApp, sqlMapping, chainDeps, sqlExec, heightProvider)

        return Rt_ExecutionContext(appCtx, opCtx, sqlCtx, sqlExec)
    }

    private fun translateQueryArgs(exeCtx: Rt_ExecutionContext, rQuery: R_QueryDefinition, gtvArgs: Gtv): List<Rt_Value> {
        gtvArgs is GtvDictionary
        val params = rQuery.params()

        val argMap = gtvArgs.asDict()
        val actArgNames = argMap.keys.filterNot { it == NON_STRICT_QUERY_ARGUMENT }.toSet() // TODO RELL-121 enable non-strict parsing
        val expArgNames = params.map { it.name.str }.toSet()
        if (actArgNames != expArgNames) {
            val exp = expArgNames.joinToString(",")
            val act = actArgNames.joinToString(",")
            val code = "query:wrong_arg_names:${rQuery.appLevelName}:$exp:$act"
            throw Rt_Exception.common(code, "Wrong arguments: $actArgNames instead of $expArgNames")
        }

        val gtvToRtCtx = GtvToRtContext.make(GTV_QUERY_PRETTY)
        val args = params.map { argMap.getValue(it.name.str) }
        val rtArgs = convertArgs(gtvToRtCtx, params, args)
        gtvToRtCtx.finish(exeCtx)

        return rtArgs
    }

    override fun getSpecialTxExtensions(): List<GTXSpecialTxExtension> = immListOf()
    override fun makeBlockBuilderExtensions(): List<BaseBlockBuilderExtension> = immListOf()
}

class RellPostchainModuleEnvironment(
    val outPrinter: Rt_Printer = Rt_OutPrinter,
    val logPrinter: Rt_Printer = Rt_LogPrinter(),
    val combinedPrinter: Rt_Printer? = null,
    val copyOutputToPrinter: Boolean = false,
    val wrapCtErrors: Boolean = true,
    val wrapRtErrors: Boolean = true,
    val forceTypeCheck: Boolean = false,
    val dbInitEnabled: Boolean = true,
    val dbInitLogLevel: Int = DEFAULT_DB_INIT_LOG_LEVEL,
    val hiddenLib: Boolean = false,
    val sqlLog: Boolean = false,
    val fallbackModules: List<R_ModuleName> = immListOf(R_ModuleName.EMPTY),
    val precompiledApp: RellGtxModuleApp? = null,
    val txContextFactory: Rt_PostchainTxContextFactory = Rt_DefaultPostchainTxContextFactory,
) {
    companion object {
        val DEFAULT = RellPostchainModuleEnvironment()

        const val DEFAULT_DB_INIT_LOG_LEVEL = SqlInitLogging.LOG_STEP_COMPLEX

        private val THREAD_LOCAL = ThreadLocalContext(DEFAULT)

        fun get() = THREAD_LOCAL.get()

        fun set(env: RellPostchainModuleEnvironment, code: () -> Unit) {
            THREAD_LOCAL.set(env, code)
        }
    }
}

class RellPostchainModuleFactory(env: RellPostchainModuleEnvironment? = null): GTXModuleFactory {
    private val env = env ?: RellPostchainModuleEnvironment.get()

    override fun makeModule(config: Gtv, blockchainRID: BlockchainRid): GTXModule {
        val gtxNode = config.asDict().getValue("gtx").asDict()
        val rellNode = gtxNode.getValue("rell").asDict()

        @Suppress("UNUSED_VARIABLE") // Legacy...
        val moduleName = rellNode["moduleName"]?.asString() ?: ""

        val combinedPrinter = env.combinedPrinter ?: env.logPrinter
        val copyOutput = env.copyOutputToPrinter
        val errorHandler = ErrorHandler(combinedPrinter, env.wrapCtErrors, env.wrapRtErrors)

        return errorHandler.handleError({ "Module initialization failed" }) {
            val modApp = getApp(rellNode, errorHandler, copyOutput)
            val bcRid = Bytes32(blockchainRID.data)
            val chainCtx = PostchainBaseUtils.createChainContext(config, modApp.app, bcRid)
            val chainDeps = getGtxChainDependencies(config)

            val modLogPrinter = getModulePrinter(env.logPrinter, Rt_TimestampPrinter(combinedPrinter), copyOutput)
            val modOutPrinter = getModulePrinter(env.outPrinter, combinedPrinter, copyOutput)

            val typeCheck = env.forceTypeCheck || (rellNode["typeCheck"]?.asBoolean() ?: false)
            val dbInitLogLevel = rellNode["dbInitLogLevel"]?.asInteger()?.toInt() ?: env.dbInitLogLevel

            val moduleConfig = RellModuleConfig(
                    sqlLogging = env.sqlLog,
                    typeCheck = typeCheck,
                    dbInitLogLevel = dbInitLogLevel,
                    compilerOptions = modApp.compilerOptions,
            )

            RellPostchainModule(
                    env,
                    modApp.app,
                    chainCtx,
                    chainDeps,
                    logPrinter = modLogPrinter,
                    outPrinter = modOutPrinter,
                    errorHandler = errorHandler,
                    config = moduleConfig
            )
        }
    }

    private fun getApp(
        rellNode: Map<String, Gtv>,
        errorHandler: ErrorHandler,
        copyOutput: Boolean,
    ): RellGtxModuleApp {
        if (env.precompiledApp != null) {
            return env.precompiledApp
        }

        val sourceCfg = SourceCodeConfig(rellNode)
        val sourceDir = sourceCfg.dir
        val modules = getModuleNames(rellNode)
        val compilerOptions = getCompilerOptions(sourceCfg.version)
        val app = compileApp(sourceDir, modules, compilerOptions, errorHandler, copyOutput)
        return RellGtxModuleApp(app, compilerOptions)
    }

    private fun getCompilerOptions(langVersion: R_LangVersion): C_CompilerOptions {
        val opts = C_CompilerOptions.forLangVersion(langVersion)
        return C_CompilerOptions.builder(opts).hiddenLib(env.hiddenLib).build()
    }

    private fun getModulePrinter(basePrinter: Rt_Printer, combinedPrinter: Rt_Printer, copy: Boolean): Rt_Printer {
        return if (copy) Rt_MultiPrinter(basePrinter, combinedPrinter) else basePrinter
    }

    private fun compileApp(
            sourceDir: C_SourceDir,
            modules: List<R_ModuleName>,
            compilerOptions: C_CompilerOptions,
            errorHandler: ErrorHandler,
            copyOutput: Boolean
    ): R_App {
        val cResult = C_Compiler.compile(sourceDir, modules, compilerOptions)
        val app = processCompilationResult(cResult, errorHandler, copyOutput)

        for (moduleName in modules) {
            val module = app.moduleMap[moduleName]
            if (module != null && module.test) {
                throw UserMistake("Test module specified as a main module: '$moduleName'")
            }
        }

        return app
    }

    private fun processCompilationResult(
            cResult: C_CompilationResult,
            errorHandler: ErrorHandler,
            copyOutput: Boolean
    ): R_App {
        for (message in cResult.messages) {
            val str = message.toString()

            val type = message.type
            if (type == C_MessageType.WARNING) {
                logger.warn(str)
            } else if (type == C_MessageType.ERROR) {
                logger.error(str)
            } else {
                logger.info(str)
            }

            if (copyOutput) {
                errorHandler.printer.print(str)
            }
        }

        val errors = cResult.errors

        val rApp = cResult.app
        if (rApp != null && rApp.valid && errors.isEmpty()) {
            return rApp
        }

        if (copyOutput) {
            errorHandler.printer.print("Compilation failed")
        }

        errorHandler.ignoreError()

        val err = if (env.wrapCtErrors) {
            val error = if (errors.isEmpty()) null else errors[0]
            UserMistake(error?.text ?: "Compilation error")
        } else if (errors.isNotEmpty()) {
            val error = errors[0]
            C_Error.other(error.pos, error.code, error.text)
        } else {
            IllegalStateException("Compilation error")
        }

        throw err
    }

    private fun getModuleNames(rellNode: Map<String, Gtv>): List<R_ModuleName> {
        val modulesNode = rellNode["modules"]

        val names = (modulesNode?.asArray() ?: arrayOf()).map {
            val s = it.asString()
            R_ModuleName.ofOpt(s) ?: throw UserMistake("Invalid module name: '$s'")
        }

        return names.ifEmpty { env.fallbackModules }
    }

    private fun getGtxChainDependencies(data: Gtv): Map<String, ByteArray> {
        val gtvDeps = data["dependencies"]
        if (gtvDeps == null) return mapOf()

        val deps = mutableMapOf<String, ByteArray>()

        for (entry in gtvDeps.asArray()) {
            val entryArray = entry.asArray()
            checkEquals(entryArray.size, 2)
            val name = entryArray[0].asString()
            val rid = entryArray[1].asByteArray(true)
            check(name !in deps)
            deps[name] = rid
        }

        return deps.toMap()
    }

    companion object : KLogging()
}

private class SourceCodeConfig(rellNode: Map<String, Gtv>) {
    val dir: C_SourceDir
    val version: R_LangVersion

    init {
        val ver = getSourceVersion(rellNode)
        val textSources = getSourceCodes(rellNode, ver, false)
        val fileSources = getSourceCodes(rellNode, ver, true)
        val allSources = textSources + fileSources

        if (allSources.isEmpty()) {
            throw UserMistake("Source code not specified in the configuration")
        } else if (allSources.size > 1) {
            val s = allSources.map { it.key }.sorted().joinToString()
            throw UserMistake("Multiple source code nodes specified in the configuration: $s")
        }

        val source = allSources.first()
        if (source.legacy && ver != null) {
            val verKey = RellGtxConfigConstants.RELL_VERSION_KEY
            throw UserMistake("Keys '${source.key}' and '$verKey' cannot be specified together")
        }

        val sourcesNode = rellNode.getValue(source.key)

        val fileMap = sourcesNode.asDict()
                .mapValues { (_, v) ->
                    val s = v.asString()
                    if (source.files) CommonUtils.readFileText(s) else s
                }
                .mapKeys { (k, _) -> parseSourcePath(k) }
                .mapValues { (k, v) -> C_TextSourceFile(k, v) }
                .toImmMap()

        dir = C_SourceDir.mapDir(fileMap)
        version = source.version
    }

    private fun getSourceVersion(rellNode: Map<String, Gtv>): R_LangVersion? {
        val verStr = rellNode[RellGtxConfigConstants.RELL_VERSION_KEY]?.asString()
        verStr ?: return null

        val ver = try {
            R_LangVersion.of(verStr)
        } catch (e: IllegalArgumentException) {
            throw UserMistake("Invalid language version: $verStr")
        }

        if (ver !in RellVersions.SUPPORTED_VERSIONS) {
            throw UserMistake("Unsupported language version: $ver")
        }

        return ver
    }

    private fun getSourceCodes(rellNode: Map<String, Gtv>, ver: R_LangVersion?, files: Boolean): List<SourceCode> {
        val res = mutableListOf<SourceCode>()

        val key = if (files) RellGtxConfigConstants.RELL_FILES_KEY else RellGtxConfigConstants.RELL_SOURCES_KEY

        if (key in rellNode) {
            val verKey = RellGtxConfigConstants.RELL_VERSION_KEY
            ver ?: throw UserMistake("Configuration key '$key' is specified, but '$verKey' is missing")
            res.add(SourceCode(key, ver, files, false))
        }

        val regex = Regex("${key}_v(\\d.*)")
        val legacy = rellNode.keys
                .mapNotNull { regex.matchEntire(it) }
                .map {
                    val k = it.groupValues[0]
                    val legacyVer = processLegacySourceKeyVersion(k, it.groupValues[1], key)
                    SourceCode(k, legacyVer, files, true)
                }
        res.addAll(legacy)

        return res.toImmList()
    }

    private fun processLegacySourceKeyVersion(key: String, s: String, keyPrefix: String): R_LangVersion {
        return when (s) {
            "0.10" -> R_LangVersion.of("0.10.4")
            else -> {
                val verKey = RellGtxConfigConstants.RELL_VERSION_KEY
                throw UserMistake("Invalid source code key: $key; use '$keyPrefix' and '$verKey' instead")
            }
        }
    }

    private fun parseSourcePath(s: String): C_SourcePath {
        val path = C_SourcePath.parseOpt(s)
        return path ?: throw UserMistake("Invalid file path: '$s'")
    }

    private class SourceCode(val key: String, val version: R_LangVersion, val files: Boolean, val legacy: Boolean)
}
