package net.postchain.rell.module

import mu.KLogging
import net.postchain.base.BlockchainRid
import net.postchain.base.data.DatabaseAccess
import net.postchain.core.*
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvDictionary
import net.postchain.gtx.ExtOpData
import net.postchain.gtx.GTXModule
import net.postchain.gtx.GTXModuleFactory
import net.postchain.gtx.GTXOperation
import net.postchain.rell.CommonUtils
import net.postchain.rell.LateInit
import net.postchain.rell.model.*
import net.postchain.rell.parser.*
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.DefaultSqlExecutor
import net.postchain.rell.sql.SqlInit
import net.postchain.rell.toImmMap
import net.postchain.rell.toImmSet
import org.apache.commons.lang3.time.FastDateFormat

const val RELL_LANG_VERSION = "0.10"
const val RELL_VERSION = "0.10.1"

const val RELL_VERSION_MODULE_SYSTEM = "0.10.0"

const val CONFIG_RELL_FILES = "files_v$RELL_LANG_VERSION"
const val CONFIG_RELL_SOURCES = "sources_v$RELL_LANG_VERSION"

private fun convertArgs(ctx: GtvToRtContext, params: List<R_Param>, args: List<Gtv>): List<Rt_Value> {
    return args.mapIndexed { index, arg ->
        val type = params[index].type
        type.gtvToRt(ctx, arg)
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
        } catch (e: Rt_StackTraceError) {
            val msg = processError(msgSupplier, e, e.stack)
            throw if (wrapRtErrors) UserMistake(msg) else e
        } catch (e: Rt_BaseError) {
            val msg = processError(msgSupplier, e)
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
        private val rOperation: R_Operation,
        private val errorHandler: ErrorHandler,
        opData: ExtOpData
) : GTXOperation(opData) {
    private val gtvToRtCtx = LateInit<GtvToRtContext>()
    private val args = LateInit<List<Rt_Value>>()

    override fun isCorrect(): Boolean {
        handleError {
            val params = rOperation.params()
            if (data.args.size != params.size) {
                throw UserMistake("Wrong argument count: ${data.args.size} instead of ${params.size}")
            }
            if (!gtvToRtCtx.isSet()) {
                gtvToRtCtx.set(GtvToRtContext(GTV_OPERATION_PRETTY))
            }
            if (!args.isSet()) {
                args.set(convertArgs(gtvToRtCtx.get(), params, data.args.toList()))
            }
        }
        return true
    }

    override fun apply(ctx: TxEContext): Boolean {
        handleError {
            val blockHeight = DatabaseAccess.of(ctx).getLastBlockHeight(ctx)
            val opCtx = Rt_OpContext(ctx.timestamp, ctx.txIID, blockHeight, data.signers.toList())
            val heightProvider = Rt_TxChainHeightProvider(ctx)
            val modCtx = module.createAppContext(ctx, opCtx, heightProvider)
            gtvToRtCtx.get().finish(modCtx)
            rOperation.callTopNoTx(modCtx, args.get())
        }
        return true
    }

    private fun <T> handleError(code: () -> T): T {
        return errorHandler.handleError({ "Operation '${rOperation.appLevelName}' failed" }) {
            code()
        }
    }

    private class Rt_TxChainHeightProvider(private val ctx: TxEContext): Rt_ChainHeightProvider {
        override fun getChainHeight(rid: ByteArrayKey, id: Long): Long? {
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
        val dbInitLogLevel: Int
)

private class RellPostchainModule(
        private val rApp: R_App,
        private val moduleName: String,
        private val chainCtx: Rt_ChainContext,
        private val chainDeps: Map<String, ByteArray>,
        private val stdoutPrinter: Rt_Printer,
        private val logPrinter: Rt_Printer,
        private val errorHandler: ErrorHandler,
        private val config: RellModuleConfig
) : GTXModule {
    private val operationNames = rApp.operations.keys.map { it.str() }.toImmSet()
    private val queryNames = rApp.queries.keys.map { it.str() }.toImmSet()

    override fun getOperations(): Set<String> {
        return operationNames
    }

    override fun getQueries(): Set<String> {
        return queryNames
    }

    override fun initializeDB(ctx: EContext) {
        errorHandler.handleError({ "Database initialization failed" }) {
            val heightProvider = Rt_ConstantChainHeightProvider(-1)
            val appCtx = createAppContext(ctx, null, heightProvider)
            SqlInit.init(appCtx, config.dbInitLogLevel)
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
        val appCtx = createAppContext(ctx, null, heightProvider)
        val rtArgs = translateQueryArgs(appCtx, rQuery, args)

        val rtResult = rQuery.callTopQuery(appCtx, rtArgs)

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

    private fun translateQueryArgs(appCtx: Rt_AppContext, rQuery: R_Query, gtvArgs: Gtv): List<Rt_Value> {
        gtvArgs is GtvDictionary
        val params = rQuery.params()

        val argMap = gtvArgs.asDict().filterKeys { it != "type" }
        val actArgNames = argMap.keys
        val expArgNames = params.map { it.name }.toSet()
        if (actArgNames != expArgNames) {
            throw UserMistake("Wrong arguments: $actArgNames instead of $expArgNames")
        }

        val gtvToRtCtx = GtvToRtContext(GTV_QUERY_PRETTY)
        val args = params.map { argMap.getValue(it.name) }
        val rtArgs = convertArgs(gtvToRtCtx, params, args)
        gtvToRtCtx.finish(appCtx)

        return rtArgs
    }

    fun createAppContext(
            eCtx: EContext,
            opCtx: Rt_OpContext?,
            heightProvider: Rt_ChainHeightProvider
    ): Rt_AppContext {
        val sqlExec = DefaultSqlExecutor(eCtx.conn, config.sqlLogging)
        val sqlMapping = Rt_ChainSqlMapping(eCtx.chainID)

        val globalCtx = Rt_GlobalContext(
                sqlExec = sqlExec,
                opCtx = opCtx,
                stdoutPrinter = stdoutPrinter,
                logPrinter = logPrinter,
                chainCtx = chainCtx,
                typeCheck = config.typeCheck
        )

        val chainDeps = chainDeps.mapValues { (_, rid) -> Rt_ChainDependency(rid) }
        val sqlCtx = Rt_SqlContext.create(rApp, sqlMapping, chainDeps, sqlExec, heightProvider)
        return Rt_AppContext(globalCtx, sqlCtx, rApp)
    }
}

class RellPostchainModuleFactory(
        private val stdoutPrinter: Rt_Printer = Rt_StdoutPrinter,
        private val logPrinter: Rt_Printer = Rt_LogPrinter("net.postchain.rell.Rell"), // Need net.postchain to be logged with Postchain settings.
        private val wrapCtErrors: Boolean = true,
        private val wrapRtErrors: Boolean = true,
        private val forceTypeCheck: Boolean = false
): GTXModuleFactory {
    override fun makeModule(config: Gtv, blockchainRID: BlockchainRid): GTXModule {
        val gtxNode = config.asDict().getValue("gtx").asDict()
        val rellNode = gtxNode.getValue("rell").asDict()

        val moduleName = rellNode["moduleName"]?.asString() ?: ""

        val (combinedPrinter, copyOutput) = getCombinedPrinter(rellNode)
        val errorHandler = ErrorHandler(combinedPrinter, wrapCtErrors, wrapRtErrors)

        return errorHandler.handleError({ "Module initialization failed" }) {
            val sourceCodes = getSourceCodes(rellNode)
            val modules = getModuleNames(rellNode)
            val app = compileApp(sourceCodes, modules, errorHandler, copyOutput)

            val chainCtx = createChainContext(config, rellNode, app, blockchainRID)
            val chainDeps = getGtxChainDependencies(config)

            val modLogPrinter = getModulePrinter(logPrinter, Rt_TimestampPrinter(combinedPrinter), copyOutput)
            val modStdoutPrinter = getModulePrinter(stdoutPrinter, combinedPrinter, copyOutput)

            val sqlLogging = rellNode["sqlLog"]?.asBoolean() ?: false
            val typeCheck = forceTypeCheck || (rellNode["typeCheck"]?.asBoolean() ?: false)
            val dbInitLogLevel = rellNode["dbInitLogLevel"]?.asInteger()?.toInt() ?: SqlInit.LOG_STEP_COMPLEX

            val moduleConfig = RellModuleConfig(
                    sqlLogging = sqlLogging,
                    typeCheck = typeCheck,
                    dbInitLogLevel = dbInitLogLevel
            )

            RellPostchainModule(
                    app,
                    moduleName,
                    chainCtx,
                    chainDeps,
                    logPrinter = modLogPrinter,
                    stdoutPrinter = modStdoutPrinter,
                    errorHandler = errorHandler,
                    config = moduleConfig
            )
        }
    }

    private fun getModulePrinter(basePrinter: Rt_Printer, combinedPrinter: Rt_Printer, copy: Boolean): Rt_Printer {
        return if (copy) Rt_MultiPrinter(basePrinter, combinedPrinter) else basePrinter
    }

    private fun getCombinedPrinter(rellNode: Map<String, Gtv>): Pair<Rt_Printer, Boolean> {
        val className = rellNode["combinedPrinterFactoryClass"]?.asString()
        val copyOutput = rellNode["copyOutputToCombinedPrinter"]?.asBoolean() ?: true
        className ?: return Pair(logPrinter, false)

        try {
            val cls = Class.forName(className)
            val factory = cls.newInstance() as Rt_PrinterFactory
            val printer = factory.newPrinter()
            return Pair(printer, copyOutput)
        } catch (e: Throwable) {
            logger.error(e) { "Combined printer creation failed" }
            return Pair(Rt_NopPrinter, false)
        }
    }

    private fun compileApp(
            sourceCodes: Map<C_SourcePath, C_SourceFile>,
            modules: List<R_ModuleName>,
            errorHandler: ErrorHandler,
            copyOutput: Boolean
    ): R_App {
        val sourceDir = C_MapSourceDir(sourceCodes)
        val cResult = C_Compiler.compile(sourceDir, modules)
        val app = processCompilationResult(cResult, errorHandler, copyOutput)
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

        val err = if (wrapCtErrors) {
            val error = if (errors.isEmpty()) null else errors[0]
            UserMistake(error?.text ?: "Compilation error")
        } else if (errors.isNotEmpty()) {
            val error = errors[0]
            C_Error(error.pos, error.code, error.text)
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

        return if (names.isNotEmpty()) names else listOf(R_ModuleName.EMPTY)
    }

    private fun getSourceCodes(rellNode: Map<String, Gtv>): Map<C_SourcePath, C_SourceFile> {
        val filesNode = rellNode[CONFIG_RELL_FILES]
        val sourcesNode = rellNode[CONFIG_RELL_SOURCES]

        if (filesNode == null && sourcesNode == null) {
            throw UserMistake("Neither files nor sources specified")
        } else if (filesNode != null && sourcesNode != null) {
            throw UserMistake("Both files and sources specified")
        }

        val sourceCodes = if (sourcesNode != null) {
            sourcesNode.asDict().mapValues { (_, v) -> v.asString() }
        } else {
            filesNode!!.asDict().mapValues { (_, v) -> CommonUtils.readFileContent(v.asString()) }
        }

        return sourceCodes
                .mapKeys { (k, _) -> parseSourcePath(k) }
                .mapValues { (k, v) -> C_TextSourceFile(k, v) }
                .toImmMap()
    }

    private fun parseSourcePath(s: String): C_SourcePath {
        val path = C_SourcePath.parseOpt(s)
        return path ?: throw UserMistake("Invalid file path: '$s'")
    }


    private fun createChainContext(
            rawConfig: Gtv,
            rellNode: Map<String, Gtv>,
            rApp: R_App,
            blockchainRid: BlockchainRid
    ): Rt_ChainContext {
        val gtvArgsDict = rellNode["moduleArgs"]?.asDict() ?: mapOf()

        val moduleArgs = mutableMapOf<R_ModuleName, Rt_Value>()

        for (rModule in rApp.modules) {
            val argsStruct = rModule.moduleArgs

            if (argsStruct != null) {
                val gtvArgs = gtvArgsDict[rModule.name.str()]
                if (gtvArgs == null) {
                    throw UserMistake("No moduleArgs in blockchain configuration for module '${rModule.name}', " +
                            "but type ${argsStruct.moduleLevelName} defined in the code")
                }

                val convCtx = GtvToRtContext(true)
                val rtArgs = argsStruct.type.gtvToRt(convCtx, gtvArgs)
                moduleArgs[rModule.name] = rtArgs
            }
        }

        return Rt_ChainContext(rawConfig, moduleArgs, blockchainRid)
    }

    private fun getGtxChainDependencies(data: Gtv): Map<String, ByteArray> {
        val gtvDeps = data["dependencies"]
        if (gtvDeps == null) return mapOf()

        val deps = mutableMapOf<String, ByteArray>()

        for (entry in gtvDeps.asArray()) {
            val entryArray = entry.asArray()
            check(entryArray.size == 2)
            val name = entryArray[0].asString()
            val rid = entryArray[1].asByteArray(true)
            check(name !in deps)
            deps[name] = rid
        }

        return deps.toMap()
    }

    companion object : KLogging()
}
