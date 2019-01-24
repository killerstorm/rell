package net.postchain.rell.test

import net.postchain.gtx.GTXNull
import net.postchain.gtx.GTXValue
import net.postchain.rell.model.R_Class
import net.postchain.rell.model.R_ExternalParam
import net.postchain.rell.model.R_Module
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.DefaultSqlExecutor
import net.postchain.rell.sql.NoConnSqlExecutor
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlUtils
import java.io.Closeable
import java.sql.Connection
import java.util.*
import kotlin.test.assertEquals

open class RellBaseTester(
        useSql: Boolean = true,
        classDefs: List<String> = listOf(),
        inserts: List<String> = listOf(),
        protected val gtx: Boolean = false
) {
    private var inited = false
    private var destroyed = false
    private var sqlConn: Connection? = null
    private var sqlExec: SqlExecutor = NoConnSqlExecutor
    private var sqlExecResource: Closeable? = null
    private var lastInserts = listOf<String>()
    private var modelClasses: List<R_Class> = listOf()

    var errMsgPos = false

    var useSql: Boolean = useSql
        set(value) {
            checkNotInited()
            field = value
        }

    var defs: List<String> = classDefs
        set(value) {
            checkNotInited()
            field = value
        }

    var inserts: List<String> = inserts

    protected fun init() {
        if (!inited) {
            val code = defsCode()
            val module = RellTestUtils.parseModule(code, gtx)
            modelClasses = module.classes.values.toList()

            if (useSql) {
                initSql(code, module)
            }

            inited = true
        } else if (inserts != lastInserts) {
            if (!lastInserts.isEmpty()) {
                SqlTestUtils.clearTables(sqlExec)
            }
            initSqlInserts(sqlExec)
        }
    }

    protected fun checkNotInited() {
        check(!inited)
    }

    private fun initSql(moduleCode: String, module: R_Module) {
        val realSqlConn = SqlTestUtils.createSqlConnection()
        var closeable: Connection? = realSqlConn

        try {
            val realSqlExec = DefaultSqlExecutor(realSqlConn)
            initSqlReset(realSqlConn, realSqlExec, moduleCode, module)
            initSqlInserts(realSqlExec)
            sqlConn = realSqlConn
            sqlExec = realSqlExec
            sqlExecResource = realSqlExec
            closeable = null
        } finally {
            closeable?.close()
        }
    }

    protected open fun initSqlReset(conn: Connection, exec: SqlExecutor, moduleCode: String, module: R_Module) {
        SqlUtils.resetDatabase(exec, module, true)
    }

    private fun initSqlInserts(sqlExecLoc: SqlExecutor) {
        if (!inserts.isEmpty()) {
            val insertSql = inserts.joinToString("\n") { it }
            sqlExecLoc.transaction {
                sqlExecLoc.execute(insertSql)
            }
            lastInserts = inserts
        }
    }

    fun destroy() {
        if (!inited || destroyed) return
        destroyed = true
        sqlExecResource?.close()
    }

    protected fun getSqlConn(): Connection {
        init()
        return sqlConn!!
    }

    fun dumpDatabase(): List<String> {
        init()
        return SqlTestUtils.dumpDatabase(sqlExec, modelClasses)
    }

    protected fun defsCode(): String = defs.joinToString("\n")

    protected fun moduleCode(extraCode: String): String {
        val defsCode = defsCode()
        val modCode = (if (defsCode.isEmpty()) "" else defsCode + "\n") + extraCode
        return modCode
    }

    protected fun getSqlExec() = sqlExec

    fun chkCompile(code: String, expected: String) {
        val actual = compileModule(code)
        assertEquals(expected, actual)
    }

    fun compileModule(code: String): String {
        val moduleCode = moduleCode(code)
        return processModule(moduleCode) { "OK" }
    }

    fun compileModuleEx(code: String): R_Module {
        val moduleCode = moduleCode(code)
        var res: R_Module? = null
        processModule(moduleCode) {
            res = it
            "OK"
        }
        return res!!
    }

    fun processModule(code: String, processor: (R_Module) -> String): String {
        return RellTestUtils.processModule(code, errMsgPos, gtx, processor)
    }
}

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

    var strictToString = true
    var opContext: Rt_OpContext? = null

    fun chkQuery(bodyCode: String, expected: String) {
        val queryCode = "query q() $bodyCode"
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

    fun chkOp(bodyCode: String, expected: String) {
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

    fun execOp(code: String) {
        chkOp(code, "")
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
        return Rt_GlobalContext(stdoutPrinter, logPrinter, getSqlExec(), opContext, chainContext)
    }

    fun chkStdout(vararg expected: String) = stdoutPrinter.chk(*expected)
    fun chkLog(vararg expected: String) = logPrinter.chk(*expected)

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
