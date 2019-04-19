package net.postchain.rell.module

import net.postchain.core.EContext
import net.postchain.core.Transactor
import net.postchain.core.TxEContext
import net.postchain.core.UserMistake
import net.postchain.gtx.*
import net.postchain.rell.CommonUtils
import net.postchain.rell.model.R_ExternalParam
import net.postchain.rell.model.R_Module
import net.postchain.rell.model.R_Operation
import net.postchain.rell.model.R_Query
import net.postchain.rell.parser.C_IncludeResolver
import net.postchain.rell.parser.C_Parser
import net.postchain.rell.parser.C_VirtualIncludeDir
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.DefaultSqlExecutor
import net.postchain.rell.sql.genSqlForChain
import org.apache.commons.logging.LogFactory

val RELL_VERSION = "v0.8"
val CONFIG_RELL_FILES = "files_$RELL_VERSION"
val CONFIG_RELL_SOURCES = "sources_$RELL_VERSION"

private object StdoutRtPrinter : Rt_Printer() {
    override fun print(str: String) {
        println(str)
    }
}

private object LogRtPrinter : Rt_Printer() {
    private val log = LogFactory.getLog(LogRtPrinter.javaClass)

    override fun print(str: String) {
        log.info(str)
    }
}

private fun convertArgs(ctx: GtxToRtContext, params: List<R_ExternalParam>, args: List<GTXValue>, human: Boolean): List<Rt_Value> {
    return args.mapIndexed { index, arg ->
        val type = params[index].type
        type.gtxToRt(ctx, arg, human)
    }
}

class RellGTXOperation(val module: RellPostchainModule, val rOperation: R_Operation, opData: ExtOpData) : GTXOperation(opData) {
    private lateinit var gtxToRtCtx: GtxToRtContext
    private lateinit var args: List<Rt_Value>

    override fun isCorrect(): Boolean {
        if (data.args.size != rOperation.params.size) {
            throw UserMistake(
                    "Wrong argument count for op '${rOperation.name}': ${data.args.size} instead of ${rOperation.params.size}")
        }

        module.factory.catchRtErr {
            gtxToRtCtx = GtxToRtContext()
            args = convertArgs(gtxToRtCtx, rOperation.params, data.args.toList(), GTX_OPERATION_HUMAN)
        }

        return true
    }

    override fun apply(ctx: TxEContext): Boolean {
        val opCtx = Rt_OpContext(ctx.timestamp, ctx.txIID, data.signers.toList())
        val modCtx = module.makeRtModuleContext(ctx, opCtx)

        module.factory.catchRtErr {
            gtxToRtCtx.finish(modCtx)
        }

        try {
            rOperation.callTopNoTx(modCtx, args)
        } catch (e: Exception) {
            throw UserMistake("Query failed: ${e.message}", e)
        }

        return true
    }
}

class RellPostchainModule(
        val factory: RellPostchainModuleFactory,
        private val rModule: R_Module,
        private val moduleName: String,
        private val chainCtx: Rt_ChainContext,
        private val chainDeps: Map<String, ByteArray>,
        private val sqlLogging: Boolean
) : GTXModule {
    override fun getOperations(): Set<String> {
        return rModule.operations.keys
    }

    override fun getQueries(): Set<String> {
        return rModule.queries.keys
    }

    override fun initializeDB(ctx: EContext) {
        if (GTXSchemaManager.getModuleVersion(ctx, moduleName) == null) {
            initDb(ctx)
            GTXSchemaManager.setModuleVersion(ctx, moduleName, 0)
        }
    }

    private fun initDb(ctx: EContext) {
        factory.catchRtErr {
            val modCtx = makeRtModuleContext(ctx, null)

            val sql = genSqlForChain(modCtx.sqlCtx)
            ctx.conn.createStatement().use {
                it.execute(sql)
            }

            modCtx.insertObjectRecords()
        }
    }

    override fun makeTransactor(opData: ExtOpData): Transactor {
        if (opData.opName in rModule.operations) {
            return RellGTXOperation(this, rModule.operations[opData.opName]!!, opData)
        } else {
            throw UserMistake("Operation not found: '${opData.opName}'")
        }
    }

    override fun query(ctx: EContext, name: String, args: GTXValue): GTXValue {
        val rQuery = rModule.queries[name] ?: throw UserMistake("Query not found: '$name'")

        val modCtx = makeRtModuleContext(ctx, null)
        val rtArgs = translateQueryArgs(modCtx, rQuery, args)

        val rtResult = try {
            rQuery.callTopQuery(modCtx, rtArgs)
        } catch (e: Exception) {
            throw UserMistake("Query failed: ${e.message}", e)
        }

        val gtxResult = rQuery.type.rtToGtx(rtResult, GTX_QUERY_HUMAN)
        return gtxResult
    }

    private fun translateQueryArgs(modCtx: Rt_ModuleContext, rQuery: R_Query, gtxArgs: GTXValue): List<Rt_Value> {
        gtxArgs is DictGTXValue

        val argMap = gtxArgs.asDict().filterKeys { it != "type" }
        val actArgNames = argMap.keys
        val expArgNames = rQuery.params.map { it.name }.toSet()
        if (actArgNames != expArgNames) {
            throw UserMistake("Wrong arguments for query '${rQuery.name}': $actArgNames instead of $expArgNames")
        }

        val rtArgs = factory.catchRtErr {
            val gtxToRtCtx = GtxToRtContext()
            val args = rQuery.params.map { argMap.getValue(it.name) }
            val res = convertArgs(gtxToRtCtx, rQuery.params, args, GTX_QUERY_HUMAN)
            gtxToRtCtx.finish(modCtx)
            res
        }

        return rtArgs
    }

    fun makeRtModuleContext(eCtx: EContext, opCtx: Rt_OpContext?): Rt_ModuleContext {
        val sqlExec = DefaultSqlExecutor(eCtx.conn, sqlLogging)
        val sqlMapping = Rt_ChainSqlMapping(eCtx.chainID)
        val globalCtx = Rt_GlobalContext(StdoutRtPrinter, LogRtPrinter, sqlExec, opCtx, chainCtx)

        val chainDeps = chainDeps.mapValues { (_, rid) -> Rt_ChainDependency(rid, Long.MAX_VALUE) }
        val sqlCtx = Rt_SqlContext.create(rModule, sqlMapping, chainDeps, sqlExec)

        return Rt_ModuleContext(globalCtx, rModule, sqlCtx)
    }
}

class RellPostchainModuleFactory(private val translateRtError: Boolean = true) : GTXModuleFactory {
    override fun makeModule(data: GTXValue, blockchainRID: ByteArray): GTXModule {
        val gtxNode = data.asDict().getValue("gtx").asDict()
        val rellNode = gtxNode.getValue("rell").asDict()

        val moduleNameNode = rellNode["moduleName"]
        val moduleName = if (moduleNameNode == null) "" else moduleNameNode.asString()

        val (sourceCodes, mainFileName) = getModuleCode(rellNode)

        val mainFileText = sourceCodes.getValue(mainFileName)
        val ast = C_Parser.parse(mainFileName, mainFileText)

        val includeResolver = C_IncludeResolver(C_VirtualIncludeDir(sourceCodes))
        val module = ast.compile(mainFileName, includeResolver, true)

        val chainCtx = createChainContext(data, rellNode, module.rModule)

        val chainDeps = catchRtErr {
            getGtxChainDependencies(data)
        }

        val sqlLogging = (rellNode["sqlLog"]?.asInteger() ?: 0L) != 0L

        return RellPostchainModule(this, module.rModule, moduleName, chainCtx, chainDeps, sqlLogging)
    }

    private fun getModuleCode(rellNode: Map<String, GTXValue>): Pair<Map<String, String>, String> {
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

        val mainFileName = rellNode.getValue("mainFile").asString()

        if (mainFileName !in sourceCodes) {
            throw UserMistake("File '$mainFileName' not found in sources")
        }

        return Pair(sourceCodes, mainFileName)
    }

    private fun createChainContext(rawConfig: GTXValue, rellNode: Map<String, GTXValue>, rModule: R_Module): Rt_ChainContext {
        val argsRec = rModule.moduleArgsRecord
        val gtxArgs = rellNode["moduleArgs"]

        val args = if (argsRec == null || gtxArgs == null) Rt_NullValue else {
            val convCtx = GtxToRtContext()
            catchRtErr {
                argsRec.gtxToRt(convCtx, gtxArgs, true)
            }
        }

        return Rt_ChainContext(rawConfig, args)
    }

    private fun getGtxChainDependencies(data: GTXValue): Map<String, ByteArray> {
        val gtxDeps = data["dependencies"]
        if (gtxDeps == null) return mapOf()

        val deps = mutableMapOf<String, ByteArray>()

        for ((name, ridGtx) in gtxDeps.asDict()) {
            val rid = ridGtx.asByteArray(true)
            check(name !in deps)
            deps[name] = rid
        }

        return deps.toMap()
    }

    fun <T> catchRtErr(code: () -> T): T {
        try {
            return code()
        } catch (e: Rt_BaseError) {
            throw if (translateRtError) UserMistake(e.message ?: "") else e
        }
    }
}
