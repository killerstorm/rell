package net.postchain.rell.test

import net.postchain.rell.model.R_App
import net.postchain.rell.parser.*
import net.postchain.rell.runtime.Rt_ChainSqlMapping
import net.postchain.rell.runtime.Rt_Printer
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.toImmMap
import org.jooq.tools.jdbc.MockConnection
import java.sql.Connection
import java.util.*
import kotlin.test.assertEquals

abstract class RellBaseTester(
        val tstCtx: RellTestContext,
        entityDefs: List<String> = listOf(),
        inserts: List<String> = listOf(),
        gtv: Boolean = false
){
    private var inited = false
    private var lastInserts = listOf<String>()
    private var appProto: R_App? = null

    protected val messages = mutableListOf<C_Message>()

    var errMsgPos = false
    var gtv = gtv
    var deprecatedError = false

    var defs: List<String> = entityDefs
        set(value) {
            checkNotInited()
            field = value
        }

    private val defs0 = mutableListOf<String>()

    var chainId: Long = 0
        set(value) {
            checkNotInited()
            field = value
        }

    var inserts: List<String> = inserts

    private val files = mutableMapOf<String, String>()

    private val stdoutPrinter0 = Rt_TesterPrinter()
    private val logPrinter0 = Rt_TesterPrinter()

    val stdoutPrinter: Rt_Printer = stdoutPrinter0
    val logPrinter: Rt_Printer = logPrinter0

    fun init() {
        tstCtx.init()

        if (!inited) {
            val code = defsCode()
            val app = initCompile(code)

            if (tstCtx.useSql) {
                initSql(code, app)
            }

            appProto = app
            inited = true
        } else if (inserts != lastInserts) {
            val sqlExec = tstCtx.sqlExec()
            if (!lastInserts.isEmpty()) {
                SqlTestUtils.clearTables(sqlExec)
            }
            initSqlInserts(sqlExec)
        }
    }

    private fun initCompile(code: String): R_App {
        val sourceDir = createSourceDir(code)
        val options = compilerOptions()
        val cRes = RellTestUtils.compileApp(sourceDir, options)

        if (cRes.errors.isNotEmpty()) {
            val err = cRes.errors[0]
            throw C_Error(err.pos, err.code, err.text)
        }

        return cRes.app!!
    }

    protected val eval = RellTestEval()

    private val expectedData = mutableListOf<String>()

    protected fun compilerOptions() = C_CompilerOptions(gtv, deprecatedError)

    fun def(def: String) {
        checkNotInited()
        defs0.add(def)
        defs = defs0
    }

    fun insert(table: String, columns: String, values: String) {
        val ins = SqlTestUtils.mkins(table, columns, values)
        inserts = inserts + listOf(ins)
    }

    fun insert(inserts: List<String>) {
        this.inserts += inserts
    }

    fun file(path: String, text: String) {
        checkNotInited()
        check(path !in files)
        check(path != RellTestUtils.MAIN_FILE)
        files[path] = text
    }

    fun files() = files.toImmMap()

    protected fun files(code: String): Map<String, String> {
        return files() + mapOf(RellTestUtils.MAIN_FILE to code)
    }

    protected fun checkNotInited() {
        check(!inited)
    }

    private fun initSql(moduleCode: String, app: R_App) {
        val sqlExec = tstCtx.sqlExec()
        initSqlReset(sqlExec, moduleCode, app)
        initSqlInserts(sqlExec)
    }

    protected abstract fun initSqlReset(exec: SqlExecutor, moduleCode: String, app: R_App)

    private fun createSourceDir(code: String): C_SourceDir {
        val files = files(code)
        return C_MapSourceDir.of(files)
    }

    private fun initSqlInserts(sqlExecLoc: SqlExecutor) {
        if (!inserts.isEmpty()) {
            val insertSql = inserts.joinToString("\n") { it }
            sqlExecLoc.transaction {
                sqlExecLoc.execute(insertSql)
            }
        }
        lastInserts = inserts
    }

    protected fun getSqlConn(): Connection {
        init()
        return if (tstCtx.useSql) tstCtx.sqlConn() else MockConnection { TODO() }
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
        val actual = dumpDatabaseEntities()
        assertEquals(expectedData, actual)
    }

    fun chkDataNew(vararg expected: String) {
        chkDataNew(expected.toList())
    }

    fun chkDataRaw(vararg expected: String) {
        chkDataRaw(expected.toList())
    }

    fun chkDataRaw(expected: List<String>) {
        val actual = dumpDatabaseTables()
        assertEquals(expected, actual)
    }

    fun chkDataSql(sql: String, vararg expected: String) {
        init()
        val actual = SqlTestUtils.dumpSql(getSqlExec(), sql)
        assertEquals(expected.toList(), actual)
    }

    private fun dumpDatabaseEntities(): List<String> {
        init()
        val sqlMapping = createChainSqlMapping()
        return SqlTestUtils.dumpDatabaseEntity(tstCtx.sqlExec(), sqlMapping, appProto!!)
    }

    fun dumpDatabaseTables(): List<String> {
        init()
        val map = SqlTestUtils.dumpDatabaseTables(tstCtx.sqlConn(), tstCtx.sqlExec())
        return map.keys.sorted()
                .filter { !it.matches(Regex("c\\d+\\.(rowid_gen|sys\\.attributes|sys\\.classes)")) }
                .flatMap {
                    map.getValue(it).map { row -> "$it($row)" }
                }
    }

    fun resetRowid() {
        init()
        val sqlMapping = createChainSqlMapping()
        SqlTestUtils.resetRowid(tstCtx.sqlExec(), sqlMapping)
    }

    protected fun defsCode(): String = defs.joinToString("\n")

    protected fun moduleCode(extraCode: String): String {
        val defsCode = defsCode()
        val modCode = (if (defsCode.isEmpty()) "" else defsCode + "\n") + extraCode
        return modCode
    }

    protected fun getSqlExec() = tstCtx.sqlExec()

    fun chkCompile(code: String, expected: String) {
        val actual = compileModule(code)
        checkResult(expected, actual)
    }

    fun chkStdout(vararg expected: String) = stdoutPrinter0.chk(*expected)
    fun chkLog(vararg expected: String) = logPrinter0.chk(*expected)

    fun compileModule(code: String): String {
        val moduleCode = moduleCode(code)
        return processApp(moduleCode) { "OK" }
    }

    fun compileAppEx(code: String): R_App {
        val moduleCode = moduleCode(code)
        var res: R_App? = null
        processApp(moduleCode) {
            res = it
            "OK"
        }
        return res!!
    }

    fun processApp(code: String, processor: (R_App) -> String): String {
        messages.clear()
        val sourceDir = createSourceDir(code)
        return RellTestUtils.processApp(sourceDir, errMsgPos, compilerOptions(), messages) {
            processor(it.rApp)
        }
    }

    protected fun createChainSqlMapping(): Rt_ChainSqlMapping {
        return Rt_ChainSqlMapping(chainId)
    }

    private class Rt_TesterPrinter: Rt_Printer {
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

    companion object {
        fun checkResult(expected: String, actual: String) {
            val expected2 = if (!expected.startsWith("ct_err:")) expected else {
                expected.replace(Regex("\n *"), "")
            }
            assertEquals(expected2, actual)
        }
    }
}
