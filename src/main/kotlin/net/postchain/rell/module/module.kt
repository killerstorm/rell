package net.postchain.rell.module

import net.postchain.core.*
import net.postchain.gtx.*
import net.postchain.rell.model.*
import net.postchain.rell.parser.C_Utils

import net.postchain.rell.runtime.*
import net.postchain.rell.sql.DefaultSqlExecutor
import net.postchain.rell.sql.gensql
import org.apache.commons.logging.LogFactory
import java.io.File

private object StdoutRtPrinter: Rt_Printer() {
    override fun print(str: String) {
        println(str)
    }
}

private object LogRtPrinter: Rt_Printer() {
    private val log = LogFactory.getLog(LogRtPrinter.javaClass)

    override fun print(str: String) {
        log.info(str)
    }
}

private fun makeRtModuleContext(rModule: R_Module, eCtx: EContext, opCtx: Rt_OpContext?): Rt_ModuleContext {
    val exec = DefaultSqlExecutor(eCtx.conn)
    val globalCtx = Rt_GlobalContext(StdoutRtPrinter, LogRtPrinter, exec, opCtx)
    return Rt_ModuleContext(globalCtx, rModule)
}

private fun <T> catchRtErr(code: () -> T): T {
    try {
        return code()
    } catch (e: Rt_BaseError) {
        throw UserMistake(e.message ?: "")
    }
}

private fun convertArgs(ctx: GtxToRtContext, params: List<R_ExternalParam>, args: List<GTXValue>, human: Boolean): List<Rt_Value> {
    return args.mapIndexed {
        index, arg ->
        val type = params[index].type
        type.gtxToRt(ctx, arg, human)
    }
}

class RellGTXOperation(val rOperation: R_Operation, val rModule: R_Module, opData: ExtOpData): GTXOperation(opData) {
    private lateinit var gtxToRtCtx: GtxToRtContext
    private lateinit var args: List<Rt_Value>

    override fun isCorrect(): Boolean {
        if (data.args.size != rOperation.params.size) {
            throw UserMistake(
                    "Wrong argument count for op '${rOperation.name}': ${data.args.size} instead of ${rOperation.params.size}")
        }

        catchRtErr {
            gtxToRtCtx = GtxToRtContext()
            args = convertArgs(gtxToRtCtx, rOperation.params, data.args.toList(), GTX_OPERATION_HUMAN)
        }

        return true
    }

    override fun apply(ctx: TxEContext): Boolean {
        val opCtx = Rt_OpContext(ctx.timestamp, data.signers.toList())
        val modCtx = makeRtModuleContext(rModule, ctx, opCtx)

        catchRtErr {
            gtxToRtCtx.finish(modCtx.globalCtx.sqlExec)
        }

        try {
            rOperation.callTopNoTx(modCtx, args)
        } catch (e: Exception) {
            throw UserMistake("Query failed: ${e.message}", e)
        }

        return true
    }
}

class RellPostchainModule(val rModule: R_Module, val moduleName: String): GTXModule {
    override fun getOperations(): Set<String> {
        return rModule.operations.keys
    }

    override fun getQueries(): Set<String> {
        return rModule.queries.keys
    }

    override fun initializeDB(ctx: EContext) {
        if (GTXSchemaManager.getModuleVersion(ctx, moduleName) == null) {
            val sql = gensql(rModule, false)
            ctx.conn.createStatement().use {
                it.execute(sql)
            }
            GTXSchemaManager.setModuleVersion(ctx, moduleName, 0)
        }
    }

    override fun makeTransactor(opData: ExtOpData): Transactor {
        if (opData.opName in rModule.operations) {
            return RellGTXOperation(rModule.operations[opData.opName]!!, rModule, opData)
        } else {
            throw UserMistake("Operation not found: '${opData.opName}'")
        }
    }

    override fun query(ctx: EContext, name: String, args: GTXValue): GTXValue {
        val rQuery = rModule.queries[name] ?: throw UserMistake("Query not found: '$name'")

        val modCtx = makeRtModuleContext(rModule, ctx, null)
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

        val rtArgs = catchRtErr {
            val gtxToRtCtx = GtxToRtContext()
            val args = rQuery.params.map { argMap.getValue(it.name) }
            val res = convertArgs(gtxToRtCtx, rQuery.params, args, GTX_QUERY_HUMAN)
            gtxToRtCtx.finish(modCtx.globalCtx.sqlExec)
            res
        }

        return rtArgs
    }
}

class RellPostchainModuleFactory: GTXModuleFactory {
    override fun makeModule(data: GTXValue, blockchainRID: ByteArray): GTXModule {
        val rellSourceModule = data["gtx"]!!["rellSrcModule"]!!.asString()
        val sourceCode = File(rellSourceModule).readText()
        val ast = C_Utils.parse(sourceCode)
        val module = ast.compile(true)
        return RellPostchainModule(module, rellSourceModule)
    }
}
