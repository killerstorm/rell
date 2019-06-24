package net.postchain.rell.module

import mu.KLogging
import net.postchain.core.*
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvDictionary
import net.postchain.gtx.ExtOpData
import net.postchain.gtx.GTXModule
import net.postchain.gtx.GTXModuleFactory
import net.postchain.gtx.GTXOperation
import net.postchain.rell.CommonUtils
import net.postchain.rell.model.R_ExternalParam
import net.postchain.rell.model.R_Module
import net.postchain.rell.model.R_Operation
import net.postchain.rell.model.R_Query
import net.postchain.rell.parser.*
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.DefaultSqlExecutor
import net.postchain.rell.sql.SqlInit
import org.apache.commons.lang3.time.FastDateFormat

val RELL_LANG_VERSION = "0.9"
val RELL_VERSION = "0.9.0"

val CONFIG_RELL_FILES = "files_v$RELL_LANG_VERSION"
val CONFIG_RELL_SOURCES = "sources_v$RELL_LANG_VERSION"

private fun convertArgs(ctx: GtvToRtContext, params: List<R_ExternalParam>, args: List<Gtv>): List<Rt_Value> {
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
        } catch (e: Rt_BaseError) {
            val msg = processError(msgSupplier, e)
            throw if (wrapRtErrors) UserMistake(msg, e) else e
        } catch (e: C_Error){
            val msg = processError(msgSupplier, e)
            throw if (wrapCtErrors) UserMistake(msg, e) else e
        } catch (e: Exception) {
            val msg = processError(msgSupplier, e)
            throw ProgrammerMistake(msg, e)
        } catch (e: Throwable) {
            val msg = processError(msgSupplier, e)
            throw ProgrammerMistake(msg)
        }
    }

    private fun processError(msgSupplier: () -> String, e: Throwable): String {
        val subMsg = msgSupplier()
        val errMsg = e.message ?: e.toString()
        val fullMsg = "$subMsg: $errMsg"

        if (!ignore) {
            printer.print("ERROR $fullMsg")
        }
        ignore = false

        return fullMsg
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
    private lateinit var gtvToRtCtx: GtvToRtContext
    private lateinit var args: List<Rt_Value>

    override fun isCorrect(): Boolean {
        handleError {
            if (data.args.size != rOperation.params.size) {
                throw UserMistake("Wrong argument count: ${data.args.size} instead of ${rOperation.params.size}")
            }

            gtvToRtCtx = GtvToRtContext(GTV_OPERATION_PRETTY)
            args = convertArgs(gtvToRtCtx, rOperation.params, data.args.toList())
        }
        return true
    }

    override fun apply(ctx: TxEContext): Boolean {
        handleError {
            val opCtx = Rt_OpContext(ctx.timestamp, ctx.txIID, data.signers.toList())
            val heightProvider = Rt_TxChainHeightProvider(ctx)
            val modCtx = module.makeRtModuleContext(ctx, opCtx, heightProvider)
            gtvToRtCtx.finish(modCtx)
            rOperation.callTopNoTx(modCtx, args)
        }
        return true
    }

    private fun <T> handleError(code: () -> T): T {
        return errorHandler.handleError({ "Operation '${rOperation.name}' failed" }) {
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
        val factory: RellPostchainModuleFactory,
        private val rModule: R_Module,
        private val moduleName: String,
        private val chainCtx: Rt_ChainContext,
        private val chainDeps: Map<String, ByteArray>,
        private val stdoutPrinter: Rt_Printer,
        private val logPrinter: Rt_Printer,
        private val errorHandler: ErrorHandler,
        private val config: RellModuleConfig
) : GTXModule {
    override fun getOperations(): Set<String> {
        return rModule.operations.keys
    }

    override fun getQueries(): Set<String> {
        return rModule.queries.keys
    }

    override fun initializeDB(ctx: EContext) {
        errorHandler.handleError({ "Database initialization failed" }) {
            val heightProvider = Rt_ConstantChainHeightProvider(-1)
            val modCtx = makeRtModuleContext(ctx, null, heightProvider)
            SqlInit.init(modCtx, config.dbInitLogLevel)
        }
    }

    override fun makeTransactor(opData: ExtOpData): Transactor {
        return errorHandler.handleError({ "Operation '${opData.opName}' failed" }) {
            val op = rModule.operations[opData.opName]
            if (op == null) {
                throw UserMistake("Operation not found")
            }
            RellGTXOperation(this, op, errorHandler, opData)
        }
    }

    override fun query(ctx: EContext, name: String, args: Gtv): Gtv {
        return errorHandler.handleError({ "Query '$name' failed" }) {
            query0(ctx, name, args)
        }
    }

    private fun query0(ctx: EContext, name: String, args: Gtv): Gtv {
        val rQuery = rModule.queries[name]
        if (rQuery == null) {
            throw UserMistake("Query not found")
        }

        val heightProvider = Rt_ConstantChainHeightProvider(Long.MAX_VALUE)
        val modCtx = makeRtModuleContext(ctx, null, heightProvider)
        val rtArgs = translateQueryArgs(modCtx, rQuery, args)

        val rtResult = rQuery.callTopQuery(modCtx, rtArgs)
        val gtvResult = rQuery.type.rtToGtv(rtResult, GTV_QUERY_PRETTY)
        return gtvResult
    }

    private fun translateQueryArgs(modCtx: Rt_ModuleContext, rQuery: R_Query, gtvArgs: Gtv): List<Rt_Value> {
        gtvArgs is GtvDictionary

        val argMap = gtvArgs.asDict().filterKeys { it != "type" }
        val actArgNames = argMap.keys
        val expArgNames = rQuery.params.map { it.name }.toSet()
        if (actArgNames != expArgNames) {
            throw UserMistake("Wrong arguments: $actArgNames instead of $expArgNames")
        }

        val gtvToRtCtx = GtvToRtContext(GTV_QUERY_PRETTY)
        val args = rQuery.params.map { argMap.getValue(it.name) }
        val rtArgs = convertArgs(gtvToRtCtx, rQuery.params, args)
        gtvToRtCtx.finish(modCtx)

        return rtArgs
    }

    fun makeRtModuleContext(
            eCtx: EContext,
            opCtx: Rt_OpContext?,
            heightProvider: Rt_ChainHeightProvider
    ): Rt_ModuleContext {
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
        val sqlCtx = Rt_SqlContext.create(rModule, sqlMapping, chainDeps, sqlExec, heightProvider)
        return Rt_ModuleContext(globalCtx, rModule, sqlCtx)
    }
}

class RellPostchainModuleFactory(
        private val stdoutPrinter: Rt_Printer = Rt_StdoutPrinter,
        private val logPrinter: Rt_Printer = Rt_LogPrinter(),
        private val wrapCtErrors: Boolean = true,
        private val wrapRtErrors: Boolean = true,
        private val forceTypeCheck: Boolean = false
) : GTXModuleFactory {
    override fun makeModule(data: Gtv, blockchainRID: ByteArray): GTXModule {
        val gtxNode = data.asDict().getValue("gtx").asDict()
        val rellNode = gtxNode.getValue("rell").asDict()

        val moduleName = rellNode["moduleName"]?.asString() ?: ""

        val combinedPrinter = getCombinedPrinter(rellNode)
        val errorHandler = ErrorHandler(combinedPrinter, wrapCtErrors, wrapRtErrors)

        return errorHandler.handleError({ "Module initialization failed" }) {
            val copyOutput = rellNode["copyOutputToCombinedPrinter"]?.asBoolean() ?: true

            val (sourceCodes, mainFilePath) = getModuleCode(rellNode)
            val module = compileModule(sourceCodes, mainFilePath, errorHandler, copyOutput)

            val chainCtx = createChainContext(data, rellNode, module)
            val chainDeps = getGtxChainDependencies(data)

            val modLogPrinter = getModulePrinter(logPrinter, Rt_TimestampPrinter(combinedPrinter), copyOutput)
            val modStdoutPrinter = getModulePrinter(stdoutPrinter, combinedPrinter, copyOutput)

            val sqlLogging = rellNode["sqlLog"]?.asBoolean() ?: false
            val typeCheck = forceTypeCheck || (rellNode["typeCheck"]?.asBoolean() ?: false)
            val dbInitLogLevel = rellNode["dbInitLogLevel"]?.asInteger()?.toInt() ?: SqlInit.LOG_ALL

            val config = RellModuleConfig(
                    sqlLogging = sqlLogging,
                    typeCheck = typeCheck,
                    dbInitLogLevel = dbInitLogLevel
            )

            RellPostchainModule(
                    this,
                    module,
                    moduleName,
                    chainCtx,
                    chainDeps,
                    logPrinter = modLogPrinter,
                    stdoutPrinter = modStdoutPrinter,
                    errorHandler = errorHandler,
                    config = config
            )
        }
    }

    private fun getModulePrinter(basePrinter: Rt_Printer, combinedPrinter: Rt_Printer, copy: Boolean): Rt_Printer {
        return if (copy) Rt_MultiPrinter(basePrinter, combinedPrinter) else basePrinter
    }

    private fun getCombinedPrinter(rellNode: Map<String, Gtv>): Rt_Printer {
        val className = rellNode["combinedPrinterFactoryClass"]?.asString()
        className ?: return Rt_NopPrinter

        try {
            val cls = Class.forName(className)
            val factory = cls.newInstance() as Rt_PrinterFactory
            val printer = factory.newPrinter()
            return printer
        } catch (e: Throwable) {
            logger.error(e) { "Combined printer creation failed" }
            return Rt_NopPrinter
        }
    }

    private fun compileModule(
            sourceCodes: Map<C_SourcePath, String>,
            mainFilePath: C_SourcePath,
            errorHandler: ErrorHandler,
            copyOutput: Boolean
    ): R_Module {
        val sourceDir = C_VirtualSourceDir(sourceCodes)
        val cResult = C_Compiler.compile(sourceDir, mainFilePath)
        val module = processCompilationResult(cResult, errorHandler, copyOutput)
        return module
    }

    private fun processCompilationResult(cResult: C_CompilationResult, errorHandler: ErrorHandler, copyOutput: Boolean): R_Module {
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

        if (cResult.module == null) {
            val cError = cResult.error
            val err = if (cError != null && !wrapCtErrors) {
                cError
            } else {
                UserMistake(cError?.message ?: "Compilation error")
            }

            if (copyOutput) {
                errorHandler.printer.print("Compilation failed")
            }

            errorHandler.ignoreError()
            throw err
        }

        return cResult.module!!
    }

    private fun getModuleCode(rellNode: Map<String, Gtv>): Pair<Map<C_SourcePath, String>, C_SourcePath> {
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

        val sourcePathsToCodes = sourceCodes.mapKeys { (k, _) -> parseSourcePath(k) }

        val mainFilePath = parseSourcePath(rellNode.getValue("mainFile").asString())
        if (mainFilePath !in sourcePathsToCodes) {
            throw UserMistake("File '$mainFilePath' not found in sources")
        }

        return Pair(sourcePathsToCodes, mainFilePath)
    }

    private fun parseSourcePath(s: String): C_SourcePath {
        val path = C_SourcePath.parseOpt(s)
        return path ?: throw UserMistake("Invalid file path: '$s'")
    }


    private fun createChainContext(rawConfig: Gtv, rellNode: Map<String, Gtv>, rModule: R_Module): Rt_ChainContext {
        val argsRec = rModule.moduleArgsRecord
        val gtvArgs = rellNode["moduleArgs"]

        val args = if (argsRec == null || gtvArgs == null) Rt_NullValue else {
            val convCtx = GtvToRtContext(true)
            argsRec.gtvToRt(convCtx, gtvArgs)
        }

        return Rt_ChainContext(rawConfig, args)
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
