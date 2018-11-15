package net.postchain.rell.module

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import net.postchain.core.*
import net.postchain.gtx.*
import net.postchain.rell.model.*
import net.postchain.rell.parser.CtUtils

import net.postchain.rell.parser.S_Grammar
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.DefaultSqlExecutor
import net.postchain.rell.sql.ROWID_COLUMN
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.gensql
import org.apache.commons.collections4.MultiValuedMap
import org.apache.commons.collections4.multimap.HashSetValuedHashMap
import org.apache.commons.logging.LogFactory
import java.io.File

private object StdoutRtPrinter: RtPrinter() {
    override fun print(str: String) {
        println(str)
    }
}

private object LogRtPrinter: RtPrinter() {
    private val log = LogFactory.getLog(LogRtPrinter.javaClass)

    override fun print(str: String) {
        log.info(str)
    }
}

private fun makeRtModuleContext(rModule: RModule, eCtx: EContext, signers: List<ByteArray>): RtModuleContext {
    val exec = DefaultSqlExecutor(eCtx.conn)
    val globalCtx = RtGlobalContext(StdoutRtPrinter, LogRtPrinter, exec, signers)
    return RtModuleContext(globalCtx, rModule)
}

private class GtxToRtValueConverter {
    private val objectIds: MultiValuedMap<RClass, Long> = HashSetValuedHashMap()

    fun convert(type: RType, gv: GTXValue): RtValue {
        return when (type) {
            RBooleanType -> RtBooleanValue(gv.asInteger() != 0L)
            RTextType -> RtTextValue(gv.asString())
            RIntegerType -> RtIntValue(gv.asInteger())
            RByteArrayType -> RtByteArrayValue(gv.asByteArray())
            RJSONType -> RtJsonValue.parse(gv.asString())
            is RListType -> RtListValue(type, gv.asArray()
                    .map { convert(type.elementType, it) }.toMutableList())
            is RSetType -> makeRtSetValue(type, gv.asArray().map { convert(type.elementType, it) })
            is RMapType -> RtMapValue(type, gv.asDict()
                    .mapKeys { (k, v) -> convert(type.keyType, gtx(k)) }
                    .mapValues { (k, v) -> convert(type.valueType, v) }
                    .toMutableMap())
            is RTupleType -> RtTupleValue(type, type.fields
                    .mapIndexed { i, f -> convert(f.type, gv.asArray()[i]) })
            is RInstanceRefType -> {
                val rowid = gv.asInteger()
                objectIds.put(type.rClass, rowid)
                RtObjectValue(type, rowid)
            }
            else -> throw UserMistake("Cannot convert GTX value to ${type.toStrictString()}")
        }
    }

    fun finish(sqlExec: SqlExecutor) {
        for (rClass in objectIds.keySet()) {
            val rowids = objectIds.get(rClass)
            checkRowids(sqlExec, rClass, rowids)
        }
    }

    private fun checkRowids(sqlExec: SqlExecutor, rClass: RClass, rowids: Collection<Long>) {
        val existingIds = selectExistingIds(sqlExec, rClass, rowids)
        val missingIds = rowids.toSet() - existingIds
        if (!missingIds.isEmpty()) {
            val s = missingIds.toList().sorted()
            throw UserMistake("Missing objects of class '${rClass.name}': $s")
        }
    }

    private fun selectExistingIds(sqlExec: SqlExecutor, rClass: RClass, rowids: Collection<Long>): Set<Long> {
        val buf = StringBuilder()
        buf.append("SELECT \"").append(ROWID_COLUMN).append("\"")
        buf.append(" FROM \"").append(rClass.name).append("\"")
        buf.append(" WHERE \"").append(ROWID_COLUMN).append("\" IN (")
        rowids.joinTo(buf, ",")
        buf.append(")")
        val sql = buf.toString()

        val existingIds = mutableSetOf<Long>()
        sqlExec.executeQuery(sql, {}) { existingIds.add(it.getLong(1)) }
        return existingIds
    }
}

private fun makeRtSetValue(type: RType, elements: List<RtValue>): RtValue {
    val set = mutableSetOf<RtValue>()
    for (value in elements) {
        if (!set.add(value)) {
            throw UserMistake("Duplicate set element: ${value}")
        }
    }
    return RtSetValue(type, set)
}

private fun gtxValueFromRtValue(rt: RtValue): GTXValue {
    return when (rt) {
        is RtUnitValue -> GTXNull
        is RtNullValue -> GTXNull
        is RtBooleanValue -> IntegerGTXValue(if (rt.asBoolean()) 1L else 0L)
        is RtIntValue -> IntegerGTXValue(rt.asInteger())
        is RtTextValue -> StringGTXValue(rt.asString())
        is RtByteArrayValue -> ByteArrayGTXValue(rt.asByteArray())
        is RtObjectValue -> IntegerGTXValue(rt.asObjectId())
        is RtListValue -> ArrayGTXValue(rt.asList().map { gtxValueFromRtValue(it) }.toTypedArray())
        is RtSetValue -> ArrayGTXValue(rt.asSet().map { gtxValueFromRtValue(it) }.toTypedArray())
        is RtMapValue -> DictGTXValue(rt.asMap()
                .mapKeys { (k, v) -> k.asString() }
                .mapValues { (k, v) -> gtxValueFromRtValue(v) })
        is RtTupleValue -> ArrayGTXValue(rt.asTuple().map { gtxValueFromRtValue(it) }.toTypedArray())
        is RtJsonValue -> StringGTXValue(rt.asJsonString()) // TODO consider parsing JSON into GTX array/object
        else -> throw ProgrammerMistake("Cannot convert to GTX: ${rt.type().toStrictString()}")
    }
}

class RellGTXOperation(val rOperation: ROperation, val rModule: RModule, opData: ExtOpData):
        GTXOperation(opData)
{
    private lateinit var converter: GtxToRtValueConverter
    private lateinit var args: List<RtValue>

    override fun isCorrect(): Boolean {
        if (data.args.size != rOperation.params.size) {
            throw UserMistake(
                    "Wrong argument count for op '${rOperation.name}': ${data.args.size} instead of ${rOperation.params.size}")
        }

        converter = GtxToRtValueConverter()
        args = data.args.mapIndexed {
            index, arg ->
            converter.convert(rOperation.params[index].type, arg)
        }

        return true
    }

    override fun apply(ctx: TxEContext): Boolean {
        val modCtx = makeRtModuleContext(rModule, ctx, data.signers.toList())
        converter.finish(modCtx.globalCtx.sqlExec)

        rOperation.callTopNoTx(modCtx, args)

        return true
    }
}

class RellPostchainModule(val rModule: RModule, val moduleName: String): GTXModule {
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

        val modCtx = makeRtModuleContext(rModule, ctx, listOf())
        val rtArgs = translateQueryArgs(modCtx, rQuery, args)

        val rtResult = try {
            rQuery.callTopQuery(modCtx, rtArgs)
        } catch (e: Exception) {
            throw ProgrammerMistake("Query failed: ${e.message}", e)
        }

        val gtxResult = gtxValueFromRtValue(rtResult)
        return gtxResult
    }

    private fun translateQueryArgs(modCtx: RtModuleContext, rQuery: RQuery, gtxArgs: GTXValue): List<RtValue> {
        gtxArgs is DictGTXValue

        val argMap = gtxArgs.asDict().filterKeys { it.startsWith("q_") }.mapKeys { (k, v) -> k.substring(2) }
        val actArgNames = argMap.keys
        val expArgNames = rQuery.params.map { it.name }.toSet()
        if (actArgNames != expArgNames) {
            throw UserMistake("Wrong arguments for query '${rQuery.name}': $actArgNames instead of $expArgNames")
        }

        val converter = GtxToRtValueConverter()
        val rtArgs = rQuery.params.map { converter.convert(it.type, argMap.getValue(it.name)) }
        converter.finish(modCtx.globalCtx.sqlExec)

        return rtArgs
    }
}

class RellPostchainModuleFactory: GTXModuleFactory {
    override fun makeModule(data: GTXValue, blockchainRID: ByteArray): GTXModule {
        val rellSourceModule = data["gtx"]!!["rellSrcModule"]!!.asString()
        val sourceCode = File(rellSourceModule).readText()
        val ast = CtUtils.parse(sourceCode)
        val module = ast.compile()
        return RellPostchainModule(module, rellSourceModule)
    }
}
