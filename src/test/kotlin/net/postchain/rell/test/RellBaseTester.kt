package net.postchain.rell.test

import net.postchain.gtx.GTXNull
import net.postchain.rell.model.R_Module
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.DefaultSqlExecutor
import net.postchain.rell.sql.NoConnSqlExecutor
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlUtils
import java.io.Closeable
import java.sql.Connection
import kotlin.test.assertEquals

abstract class RellBaseTester(
        useSql: Boolean = true,
        classDefs: List<String> = listOf(),
        inserts: List<String> = listOf(),
        gtx: Boolean = false
) {
    private var inited = false
    private var destroyed = false
    private var sqlConn: Connection? = null
    private var sqlExec: SqlExecutor = NoConnSqlExecutor
    private var sqlExecResource: Closeable? = null
    private var lastInserts = listOf<String>()
    private var moduleProto: R_Module? = null

    var errMsgPos = false
    var gtx = gtx

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

            if (useSql) {
                initSql(code, module)
            }

            moduleProto = module
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

    protected abstract fun initSqlReset(conn: Connection, exec: SqlExecutor, moduleCode: String, module: R_Module)

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
        return SqlTestUtils.dumpDatabase(sqlExec, moduleProto!!)
    }

    fun resetRowid() {
        init()
        SqlTestUtils.resetRowid(sqlExec)
    }

    protected fun defsCode(): String = defs.joinToString("\n")

    protected fun moduleCode(extraCode: String): String {
        val defsCode = defsCode()
        val modCode = (if (defsCode.isEmpty()) "" else defsCode + "\n") + extraCode
        return modCode
    }

    protected fun getSqlExec() = sqlExec
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
}
