package net.postchain.rell.test

import net.postchain.gtx.GTXNull
import net.postchain.gtx.GTXValue
import net.postchain.rell.model.R_ExternalParam
import net.postchain.rell.model.R_Module
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlUtils
import java.sql.Connection
import java.util.*
import kotlin.test.assertEquals

class RellCodeTester(
        useSql: Boolean = true,
        classDefs: List<String> = listOf(),
        inserts: List<String> = listOf(),
        gtx: Boolean = false
): RellBaseTester(useSql, classDefs, inserts, gtx)
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
    var createSystemTables = true
    var autoInitObjects = true
    var strictToString = true
    var opContext: Rt_OpContext? = null
    var sqlUpdatePortionSize = 1000

    override fun initSqlReset(conn: Connection, exec: SqlExecutor, moduleCode: String, module: R_Module) {
        val sqlMapper = createSqlMapper()
        SqlUtils.resetDatabase(exec, module, sqlMapper, dropTables, createSystemTables)
        if (autoInitObjects) {
            initSqlObjects(exec, module)
        }
    }

    private fun initSqlObjects(sqlExec: SqlExecutor, module: R_Module) {
        val sqlMapper = createSqlMapper()
        val chainCtx = Rt_ChainContext(GTXNull, Rt_NullValue)
        val globalCtx = Rt_GlobalContext(Rt_FailingPrinter, Rt_FailingPrinter, sqlExec, sqlMapper, null, chainCtx, logSqlErrors = true)
        val modCtx = Rt_ModuleContext(globalCtx, module)
        sqlExec.transaction {
            modCtx.insertObjectRecords()
        }
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

    private fun callFn(code: String): String {
        init()
        val moduleCode = moduleCode(code)
        val globalCtx = createGlobalCtx()
        return processModule(moduleCode) { module ->
            RellTestUtils.callFn(globalCtx, module, "f", listOf(), strictToString)
        }
    }

    fun callQuery(code: String, args: List<Rt_Value>): String {
        return callQuery0(code, args) { _, v -> v }
    }

    private fun <T> callQuery0(code: String, args: List<T>, decoder: (List<R_ExternalParam>, List<T>) -> List<Rt_Value>): String {
        init()
        val moduleCode = moduleCode(code)
        val globalCtx = createGlobalCtx()

        val encoder = if (gtxResult) RellTestUtils.ENCODER_GTX
        else if (strictToString) RellTestUtils.ENCODER_STRICT
        else RellTestUtils.ENCODER_PLAIN

        return processModule(moduleCode) { module ->
            RellTestUtils.callQueryGeneric(globalCtx, module, "q", args, decoder, encoder)
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
        return processModule(moduleCode) { module ->
            RellTestUtils.callOpGeneric(globalCtx, module, "o", args, decoder)
        }
    }

    private fun createGlobalCtx(): Rt_GlobalContext {
        val chainContext = Rt_ChainContext(GTXNull, Rt_NullValue)
        return Rt_GlobalContext(
                stdoutPrinter,
                logPrinter,
                getSqlExec(),
                createSqlMapper(),
                opContext,
                chainContext,
                logSqlErrors = true,
                sqlUpdatePortionSize = sqlUpdatePortionSize
        )
    }

    fun chkStdout(vararg expected: String) = stdoutPrinter.chk(*expected)
    fun chkLog(vararg expected: String) = logPrinter.chk(*expected)

    fun chkInitObjects(expected: String) {
        init()
        val sqlExec = getSqlExec()
        val moduleProto = getModuleProto()
        val actual = RellTestUtils.catchRtErr {
            initSqlObjects(sqlExec, moduleProto)
            "OK"
        }
        assertEquals(expected, actual)
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
