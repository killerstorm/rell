package net.postchain.rell.test

import net.postchain.rell.model.R_Module
import net.postchain.rell.runtime.Rt_SqlExecutor
import net.postchain.rell.runtime.Rt_SqlMapper
import net.postchain.rell.sql.DefaultSqlExecutor
import net.postchain.rell.sql.NoConnSqlExecutor
import net.postchain.rell.sql.SqlExecutor
import java.io.Closeable
import java.sql.Connection
import kotlin.test.assertEquals

abstract class RellBaseTester(
        useSql: Boolean = true,
        classDefs: List<String> = listOf(),
        inserts: List<String> = listOf(),
        gtx: Boolean = false
): Closeable {
    private var inited = false
    private var destroyed = false
    private var sqlConn: Connection? = null
    private var sqlExec: SqlExecutor = NoConnSqlExecutor
    private var lastInserts = listOf<String>()
    private var moduleProto: R_Module? = null

    var errMsgPos = false
    var gtx = gtx
    var sqlLogging = false

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

    var chainId: Long = 0
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

    fun insert(table: String, columns: String, values: String): RellBaseTester {
        val ins = SqlTestUtils.mkins(table, columns, values)
        inserts = inserts + listOf(ins)
        return this
    }

    protected fun checkNotInited() {
        check(!inited)
    }

    private fun initSql(moduleCode: String, module: R_Module) {
        val realSqlConn = SqlTestUtils.createSqlConnection()
        var closeable: Connection? = realSqlConn

        try {
            realSqlConn.autoCommit = false
            val realSqlExec = Rt_SqlExecutor(DefaultSqlExecutor(realSqlConn, sqlLogging), true)
            initSqlReset(realSqlConn, realSqlExec, moduleCode, module)
            initSqlInserts(realSqlExec)
            sqlConn = realSqlConn
            sqlExec = realSqlExec
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

    override final fun close() {
        if (!inited || destroyed) return
        destroyed = true
        sqlConn?.close()
    }

    protected fun getSqlConn(): Connection {
        init()
        return sqlConn!!
    }

    fun dumpDatabase(): List<String> {
        init()
        val sqlMapper = createSqlMapper()
        return SqlTestUtils.dumpDatabaseClasses(sqlExec, sqlMapper, moduleProto!!)
    }

    fun resetRowid() {
        init()
        val sqlMapper = createSqlMapper()
        SqlTestUtils.resetRowid(sqlExec, sqlMapper)
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

    protected fun createSqlMapper(): Rt_SqlMapper {
        return Rt_SqlMapper(chainId)
    }
}
