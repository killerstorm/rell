package net.postchain.rell.test

import net.postchain.rell.model.R_Module
import net.postchain.rell.parser.C_CompilerOptions
import net.postchain.rell.parser.C_MapSourceDir
import net.postchain.rell.parser.C_Message
import net.postchain.rell.parser.C_SourceDir
import net.postchain.rell.runtime.Rt_ChainSqlMapping
import net.postchain.rell.runtime.Rt_Printer
import net.postchain.rell.sql.SqlExecutor
import org.jooq.tools.jdbc.MockConnection
import java.sql.Connection
import java.util.*
import kotlin.test.assertEquals

abstract class RellBaseTester(
        private val tstCtx: RellTestContext,
        classDefs: List<String> = listOf(),
        inserts: List<String> = listOf(),
        gtv: Boolean = false
){
    private var inited = false
    private var lastInserts = listOf<String>()
    private var moduleProto: R_Module? = null

    protected val messages = mutableListOf<C_Message>()

    var errMsgPos = false
    var gtv = gtv
    var deprecatedError = false

    var defs: List<String> = classDefs
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

            val includeDir = createIncludeDir(code)
            val options = compilerOptions()
            val module = RellTestUtils.parseModule(includeDir, options).rModule

            if (tstCtx.useSql) {
                initSql(code, module)
            }

            moduleProto = module
            inited = true
        } else if (inserts != lastInserts) {
            val sqlExec = tstCtx.sqlExec()
            if (!lastInserts.isEmpty()) {
                SqlTestUtils.clearTables(sqlExec)
            }
            initSqlInserts(sqlExec)
        }
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

    protected fun files(code: String): Map<String, String> {
        return files.toMap() + mapOf(RellTestUtils.MAIN_FILE to code)
    }

    protected fun checkNotInited() {
        check(!inited)
    }

    private fun initSql(moduleCode: String, module: R_Module) {
        val sqlExec = tstCtx.sqlExec()
        initSqlReset(sqlExec, moduleCode, module)
        initSqlInserts(sqlExec)
    }

    protected abstract fun initSqlReset(exec: SqlExecutor, moduleCode: String, module: R_Module)

    private fun createIncludeDir(code: String): C_SourceDir {
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

    private fun dumpDatabase(): List<String> {
        init()
        val sqlMapping = createChainSqlMapping()
        return SqlTestUtils.dumpDatabaseClasses(tstCtx.sqlExec(), sqlMapping, moduleProto!!)
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
    protected fun getModuleProto() = moduleProto!!

    fun chkCompile(code: String, expected: String) {
        val actual = compileModule(code)
        assertEquals(expected, actual)
    }

    fun chkStdout(vararg expected: String) = stdoutPrinter0.chk(*expected)
    fun chkLog(vararg expected: String) = logPrinter0.chk(*expected)

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
        messages.clear()
        val includeDir = createIncludeDir(code)
        return RellTestUtils.processModule(includeDir, errMsgPos, compilerOptions()) {
            messages.addAll(it.messages)
            processor(it.rModule)
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
}
