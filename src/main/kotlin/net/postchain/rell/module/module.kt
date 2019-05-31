package net.postchain.rell.module

import mu.KLogging
import net.postchain.core.*
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvDictionary
import net.postchain.gtx.*
import net.postchain.rell.CommonUtils
import net.postchain.rell.model.R_ExternalParam
import net.postchain.rell.model.R_Module
import net.postchain.rell.model.R_Operation
import net.postchain.rell.model.R_Query
import net.postchain.rell.parser.*
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.DefaultSqlExecutor
import net.postchain.rell.sql.genSqlForChain

val RELL_VERSION = "v0.8"
val CONFIG_RELL_FILES = "files_$RELL_VERSION"
val CONFIG_RELL_SOURCES = "sources_$RELL_VERSION"

private object StdoutRtPrinter : Rt_Printer() {
    override fun print(str: String) {
        println(str)
    }
}

private object LogRtPrinter : Rt_Printer() {
    private val logger = KLogging().logger("Rell")

    override fun print(str: String) {
        logger.info(str)
    }
}

private fun convertArgs(ctx: GtvToRtContext, params: List<R_ExternalParam>, args: List<Gtv>): List<Rt_Value> {
    return args.mapIndexed { index, arg ->
        val type = params[index].type
        type.gtvToRt(ctx, arg)
    }
}

class RellGTXOperation(val module: RellPostchainModule, val rOperation: R_Operation, opData: ExtOpData) : GTXOperation(opData) {
    private lateinit var gtvToRtCtx: GtvToRtContext
    private lateinit var args: List<Rt_Value>

    override fun isCorrect(): Boolean {
        if (data.args.size != rOperation.params.size) {
            throw UserMistake(
                    "Wrong argument count for op '${rOperation.name}': ${data.args.size} instead of ${rOperation.params.size}")
        }

        module.factory.catchRtErr {
            gtvToRtCtx = GtvToRtContext(GTV_OPERATION_PRETTY)
            args = convertArgs(gtvToRtCtx, rOperation.params, data.args.toList())
        }

        return true
    }

    override fun apply(ctx: TxEContext): Boolean {
        val opCtx = Rt_OpContext(ctx.timestamp, ctx.txIID, data.signers.toList())
        val heightProvider = Rt_TxChainHeightProvider(ctx)
        val modCtx = module.makeRtModuleContext(ctx, opCtx, heightProvider)

        module.factory.catchRtErr {
            gtvToRtCtx.finish(modCtx)
        }

        module.factory.wrapMistake("Operation failed") {
            rOperation.callTopNoTx(modCtx, args)
        }

        return true
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

class RellPostchainFlags(val sqlLogging: Boolean, val typeCheck: Boolean)

class RellPostchainModule(
        val factory: RellPostchainModuleFactory,
        private val rModule: R_Module,
        private val moduleName: String,
        private val chainCtx: Rt_ChainContext,
        private val chainDeps: Map<String, ByteArray>,
        private val flags: RellPostchainFlags
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
            val heightProvider = Rt_ConstantChainHeightProvider(-1)
            val modCtx = makeRtModuleContext(ctx, null, heightProvider)

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

    override fun query(ctx: EContext, name: String, args: Gtv): Gtv {
        val rQuery = rModule.queries[name] ?: throw UserMistake("Query not found: '$name'")

        val heightProvider = Rt_ConstantChainHeightProvider(Long.MAX_VALUE)
        val modCtx = makeRtModuleContext(ctx, null, heightProvider)
        val rtArgs = translateQueryArgs(modCtx, rQuery, args)

        val rtResult = factory.wrapMistake("Query failed") {
            rQuery.callTopQuery(modCtx, rtArgs)
        }

        val gtvResult = rQuery.type.rtToGtv(rtResult, GTV_QUERY_PRETTY)
        return gtvResult
    }

    private fun translateQueryArgs(modCtx: Rt_ModuleContext, rQuery: R_Query, gtvArgs: Gtv): List<Rt_Value> {
        gtvArgs is GtvDictionary

        val argMap = gtvArgs.asDict().filterKeys { it != "type" }
        val actArgNames = argMap.keys
        val expArgNames = rQuery.params.map { it.name }.toSet()
        if (actArgNames != expArgNames) {
            throw UserMistake("Wrong arguments for query '${rQuery.name}': $actArgNames instead of $expArgNames")
        }

        val rtArgs = factory.catchRtErr {
            val gtvToRtCtx = GtvToRtContext(GTV_QUERY_PRETTY)
            val args = rQuery.params.map { argMap.getValue(it.name) }
            val res = convertArgs(gtvToRtCtx, rQuery.params, args)
            gtvToRtCtx.finish(modCtx)
            res
        }

        return rtArgs
    }

    fun makeRtModuleContext(
            eCtx: EContext,
            opCtx: Rt_OpContext?,
            heightProvider: Rt_ChainHeightProvider
    ): Rt_ModuleContext {
        val sqlExec = DefaultSqlExecutor(eCtx.conn, flags.sqlLogging)
        val sqlMapping = Rt_ChainSqlMapping(eCtx.chainID)

        val globalCtx = Rt_GlobalContext(
                sqlExec = sqlExec,
                opCtx = opCtx,
                stdoutPrinter = factory.stdoutPrinter,
                logPrinter = factory.logPrinter,
                chainCtx = chainCtx,
                typeCheck = flags.typeCheck
        )

        val chainDeps = chainDeps.mapValues { (_, rid) -> Rt_ChainDependency(rid) }
        val sqlCtx = Rt_SqlContext.create(rModule, sqlMapping, chainDeps, sqlExec, heightProvider)
        return Rt_ModuleContext(globalCtx, rModule, sqlCtx)
    }
}

class RellPostchainModuleFactory(
        val stdoutPrinter: Rt_Printer = StdoutRtPrinter,
        val logPrinter: Rt_Printer = LogRtPrinter,
        private val wrapCtErrors: Boolean = true,
        private val wrapRtErrors: Boolean = true,
        private val forceTypeCheck: Boolean = false
) : GTXModuleFactory {
    override fun makeModule(data: Gtv, blockchainRID: ByteArray): GTXModule {
        val gtxNode = data.asDict().getValue("gtx").asDict()
        val rellNode = gtxNode.getValue("rell").asDict()

        val moduleNameNode = rellNode["moduleName"]
        val moduleName = if (moduleNameNode == null) "" else moduleNameNode.asString()

        val (sourceCodes, mainFilePath) = getModuleCode(rellNode)
        val sourceDir = C_VirtualSourceDir(sourceCodes)

        val cResult = C_Compiler.compile(sourceDir, mainFilePath)
        val module = processCompilationResult(cResult)
        val chainCtx = createChainContext(data, rellNode, module)

        val chainDeps = catchRtErr {
            getGtxChainDependencies(data)
        }

        val sqlLogging = rellNode["sqlLog"]?.asBoolean() ?: false
        val typeCheck = forceTypeCheck || (rellNode["typeCheck"]?.asBoolean() ?: false)
        val flags = RellPostchainFlags(sqlLogging = sqlLogging, typeCheck = typeCheck)

        return RellPostchainModule(this, module, moduleName, chainCtx, chainDeps, flags)
    }

    private fun processCompilationResult(cResult: C_CompilationResult): R_Module {
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
        }

        val cError = cResult.error
        if (cError != null && !wrapCtErrors) {
            throw cError
        }

        if (cError != null || cResult.module == null) {
            throw UserMistake(cError?.message ?: "Compilation error")
        }

        return cResult.module
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
            catchRtErr {
                argsRec.gtvToRt(convCtx, gtvArgs)
            }
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

    fun <T> catchRtErr(code: () -> T): T {
        try {
            return code()
        } catch (e: Rt_BaseError) {
            throw if (wrapRtErrors) UserMistake(e.message ?: "") else e
        }
    }

    fun <T> wrapMistake(msg: String, code: () -> T): T {
        try {
            return code()
        } catch (e: Rt_BaseError) {
            throw if (wrapRtErrors) UserMistake("$msg: ${e.message}" ?: "") else e
        } catch (e: Exception) {
            throw ProgrammerMistake("$msg: ${e.message}", e)
        }
    }

    companion object : KLogging()
}
