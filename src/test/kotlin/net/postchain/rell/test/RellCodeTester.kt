package net.postchain.rell.test

import net.postchain.gtx.GTXNull
import net.postchain.gtx.GTXValue
import net.postchain.rell.hexStringToByteArray
import net.postchain.rell.model.R_ExternalParam
import net.postchain.rell.model.R_Module
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlUtils
import net.postchain.rell.sql.genSqlForChain
import java.sql.Connection
import java.util.*
import kotlin.test.assertEquals

class RellCodeTester(
        tstCtx: RellTestContext,
        classDefs: List<String> = listOf(),
        inserts: List<String> = listOf(),
        gtx: Boolean = false
): RellBaseTester(tstCtx, classDefs, inserts, gtx)
{
    private val expectedData = mutableListOf<String>()
    private val stdoutPrinter = TesterRtPrinter()
    private val logPrinter = TesterRtPrinter()

    var gtxResult: Boolean = gtx
        set(value) {
            checkNotInited()
            field = value
        }

    var dropTables = true
    var createTables = true
    var autoInitObjects = true
    var strictToString = true
    var opContext: Rt_OpContext? = null
    var sqlUpdatePortionSize = 1000

    private val chainDependencies = mutableMapOf<String, Rt_ChainDependency>()

    override fun initSqlReset(conn: Connection, sqlExec: SqlExecutor, moduleCode: String, module: R_Module) {
        val sqlCtx = createSqlCtx(module, sqlExec)

        sqlExec.transaction {
            if (dropTables) {
                SqlUtils.dropAll(sqlExec, false)
            }

            if (createTables) {
                val sql = genSqlForChain(sqlCtx)
                sqlExec.execute(sql)
            }

            if (autoInitObjects) {
                initSqlObjects(sqlExec, sqlCtx)
            }
        }
    }

    private fun initSqlObjects(sqlExec: SqlExecutor, sqlCtx: Rt_SqlContext) {
        val chainCtx = Rt_ChainContext(GTXNull, Rt_NullValue)
        val globalCtx = Rt_GlobalContext(Rt_FailingPrinter, Rt_FailingPrinter, sqlExec, null, chainCtx, logSqlErrors = true)
        val modCtx = Rt_ModuleContext(globalCtx, sqlCtx.module, sqlCtx)
        modCtx.insertObjectRecords()
    }

    fun chainDependency(name: String, rid: String, height: Long) {
        check(name !in chainDependencies)
        val ridArray = rid.hexStringToByteArray()
        chainDependencies[name] = Rt_ChainDependency(ridArray, height)
    }

    fun chkQuery(bodyCode: String, expected: String) {
        val queryCode = "query q() = $bodyCode;"
        chkQueryEx(queryCode, listOf(), expected)
    }

    fun chkQueryEx(code: String, args: List<Rt_Value>, expected: String) {
        val actual = callQuery(code, args)
        assertEquals(expected, actual)
    }

    fun chkQueryGtx(code: String, expected: String) {
        val queryCode = "query q() $code"
        val actual = callQuery0(queryCode, listOf(), GtxTestUtils::decodeGtxQueryArgs)
        assertEquals(expected, actual)
    }

    fun chkQueryGtxEx(code: String, args: List<String>, expected: String) {
        val actual = callQuery0(code, args, GtxTestUtils::decodeGtxQueryArgs)
        assertEquals(expected, actual)
    }

    fun chkQueryType(bodyCode: String, expected: String) {
        val queryCode = "query q() $bodyCode"
        val moduleCode = moduleCode(queryCode)
        val actual = processModule(moduleCode) { module ->
            module.queries.getValue("q").type.toStrictString()
        }
        assertEquals(expected, actual)
    }

    fun chkOp(bodyCode: String, expected: String = "OK") {
        val opCode = "operation o() { $bodyCode }"
        chkOpEx(opCode, expected)
    }

    fun chkOpEx(opCode: String, expected: String) {
        val actual = callOp(opCode, listOf())
        assertEquals(expected, actual)
    }

    fun chkOpGtxEx(code: String, args: List<String>, expected: String) {
        val actual = callOp0(code, args, GtxTestUtils::decodeGtxOpArgsStr)
        assertEquals(expected, actual)
    }

    fun chkFnEx(code: String, expected: String) {
        val actual = callFn(code)
        assertEquals(expected, actual)
    }

    fun chkData(expected: List<String>) {
        expectedData.clear()
        chkDataNew(expected)
    }

    fun chkData(vararg expected: String) {
        chkData(expected.toList())
    }

    fun chkDataNew(expected: List<String>) {
        expectedData.addAll(expected)
        val actual = dumpDatabase()
        assertEquals(expectedData, actual)
    }

    fun chkDataNew(vararg expected: String) {
        chkDataNew(expected.toList())
    }

    fun chkDataSql(sql: String, vararg expected: String) {
        init()
        val actual = SqlTestUtils.dumpSql(getSqlExec(), sql)
        assertEquals(expected.toList(), actual)
    }

    private fun callFn(code: String): String {
        init()
        val moduleCode = moduleCode(code)
        val globalCtx = createGlobalCtx()
        return processModuleCtx(globalCtx, moduleCode) { modCtx ->
            RellTestUtils.callFn(modCtx, "f", listOf(), strictToString)
        }
    }

    fun callQuery(code: String, args: List<Rt_Value>): String {
        return callQuery0(code, args) { _, v -> v }
    }

    private fun <T> callQuery0(code: String, args: List<T>, decoder: (List<R_ExternalParam>, List<T>) -> List<Rt_Value>): String {
        return eval.eval {
            init()
            val moduleCode = moduleCode(code)
            val globalCtx = createGlobalCtx()

            val encoder = if (gtxResult) RellTestUtils.ENCODER_GTX
            else if (strictToString) RellTestUtils.ENCODER_STRICT
            else RellTestUtils.ENCODER_PLAIN

            processModuleCtx(globalCtx, moduleCode) { modCtx ->
                RellTestUtils.callQueryGeneric(modCtx, "q", args, decoder, encoder)
            }
        }
    }

    fun callOp(code: String, args: List<Rt_Value>): String {
        return callOp0(code, args) { _, v -> v }
    }

    fun callOpGtx(code: String, args: List<GTXValue>): String {
        return callOp0(code, args, GtxTestUtils::decodeGtxOpArgs)
    }

    fun callOpGtxStr(code: String, args: List<String>): String {
        return callOp0(code, args, GtxTestUtils::decodeGtxOpArgsStr)
    }

    private fun <T> callOp0(code: String, args: List<T>, decoder: (List<R_ExternalParam>, List<T>) -> List<Rt_Value>): String {
        init()
        val moduleCode = moduleCode(code)
        val globalCtx = createGlobalCtx()
        return processModuleCtx(globalCtx, moduleCode) { modCtx ->
            RellTestUtils.callOpGeneric(modCtx, "o", args, decoder)
        }
    }

    private fun createGlobalCtx(): Rt_GlobalContext {
        val chainContext = Rt_ChainContext(GTXNull, Rt_NullValue)
        return Rt_GlobalContext(
                stdoutPrinter,
                logPrinter,
                getSqlExec(),
                opContext,
                chainContext,
                logSqlErrors = true,
                sqlUpdatePortionSize = sqlUpdatePortionSize
        )
    }

    private fun createModuleCtx(globalCtx: Rt_GlobalContext, module: R_Module): Rt_ModuleContext {
        val sqlCtx = createSqlCtx(module, globalCtx.sqlExec)
        return Rt_ModuleContext(globalCtx, module, sqlCtx)
    }

    private fun createSqlCtx(module: R_Module, sqlExec: SqlExecutor): Rt_SqlContext {
        val sqlMapping = createChainSqlMapping()
        return eval.wrapRt { Rt_SqlContext.create(module, sqlMapping, chainDependencies, sqlExec) }
    }

    fun chkStdout(vararg expected: String) = stdoutPrinter.chk(*expected)
    fun chkLog(vararg expected: String) = logPrinter.chk(*expected)

    fun chkInitObjects(expected: String) {
        init()
        val sqlExec = getSqlExec()
        val moduleProto = getModuleProto()
        val sqlCtx = createSqlCtx(moduleProto, sqlExec)
        val actual = RellTestUtils.catchRtErr {
            sqlExec.transaction {
                initSqlObjects(sqlExec, sqlCtx)
            }
            "OK"
        }
        assertEquals(expected, actual)
    }

    private fun processModuleCtx(globalCtx: Rt_GlobalContext, code: String, processor: (Rt_ModuleContext) -> String): String {
        return RellTestUtils.processModule(code, errMsgPos, gtx) { module ->
            RellTestUtils.catchRtErr({ createModuleCtx(globalCtx, module) }) {
                modCtx -> processor(modCtx) }
        }
    }

    private class TesterRtPrinter: Rt_Printer() {
        private val queue = LinkedList<String>()

        override fun print(str: String) {
            queue.add(str)
        }

        fun chk(vararg expected: String) {
            val expectedList = expected.toList()
            val actualList = queue.toList()
            assertEquals(expectedList, actualList)
            queue.clear()
        }
    }
}
