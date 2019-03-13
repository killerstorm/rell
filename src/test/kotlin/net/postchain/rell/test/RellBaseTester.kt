package net.postchain.rell.test

import net.postchain.rell.model.R_Module
import net.postchain.rell.runtime.Rt_ChainSqlMapping
import net.postchain.rell.sql.SqlExecutor
import java.sql.Connection
import kotlin.test.assertEquals

abstract class RellBaseTester(
        private val tstCtx: RellTestContext,
        classDefs: List<String> = listOf(),
        inserts: List<String> = listOf(),
        gtx: Boolean = false
){
    private var inited = false
    private var lastInserts = listOf<String>()
    private var moduleProto: R_Module? = null

    var errMsgPos = false
    var gtx = gtx

    var defs: List<String> = classDefs
        set(value) {
            checkNotInited()
            field = value
        }

    var chainId: Long = 0
        set(value) {
            checkNotInited()
            field = value
        }

    var inserts: List<String> = inserts

    fun init() {
        tstCtx.init()

        if (!inited) {
            val code = defsCode()
            val module = RellTestUtils.parseModule(code, gtx)

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

    fun insert(table: String, columns: String, values: String) {
        val ins = SqlTestUtils.mkins(table, columns, values)
        inserts = inserts + listOf(ins)
    }

    fun insert(inserts: List<String>) {
        this.inserts += inserts
    }

    protected fun checkNotInited() {
        check(!inited)
    }

    private fun initSql(moduleCode: String, module: R_Module) {
        val sqlConn = tstCtx.sqlConn()
        val sqlExec = tstCtx.sqlExec()
        initSqlReset(sqlConn, sqlExec, moduleCode, module)
        initSqlInserts(sqlExec)
    }

    protected abstract fun initSqlReset(conn: Connection, exec: SqlExecutor, moduleCode: String, module: R_Module)

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
        return tstCtx.sqlConn()
    }

    fun dumpDatabase(): List<String> {
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

    protected fun createChainSqlMapping(): Rt_ChainSqlMapping {
        return Rt_ChainSqlMapping(chainId)
    }
}
