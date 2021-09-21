/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.test

import net.postchain.base.BlockchainRid
import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.base.data.SQLDatabaseAccess
import net.postchain.rell.runtime.utils.Rt_SqlManager
import net.postchain.rell.sql.*
import net.postchain.rell.utils.PostchainUtils
import java.io.Closeable
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

class RellTestContext(useSql: Boolean = true): Closeable {
    class BlockBuilder(private val chainId: Long) {
        private val list = mutableListOf<String>()

        fun list() = list.toList()

        fun block(iid: Long, height: Long, rid: String?, header: String?, timestamp: Long?): BlockBuilder {
            val ridStr = if (rid == null) "NULL" else """E'\\x$rid'"""
            val headerStr = if (header == null) "NULL" else """E'\\x$header'"""
            val timestampStr = if (timestamp == null) "NULL" else """$timestamp"""
            val s = """INSERT INTO "c$chainId.blocks"(block_iid,block_height,block_rid,block_header_data,block_witness,timestamp)
                VALUES($iid,$height,$ridStr,$headerStr,NULL,$timestampStr);"""
            list.add(s)
            return this
        }

        fun tx(iid: Long, block: Long, rid: String, data: String, hash: String): BlockBuilder {
            val s = """INSERT INTO "c$chainId.transactions"(tx_iid,tx_rid,tx_data,tx_hash,block_iid)
                VALUES($iid,E'\\x$rid',E'\\x$data',E'\\x$hash',$block);"""
            list.add(s)
            return this
        }
    }

    private var inited = false
    private var destroyed = false
    private var sqlResource: AutoCloseable? = null
    private var sqlMgr: SqlManager? = null
    private val sqlStats = TestSqlStats()

    var sqlLogging = false

    var useSql: Boolean = useSql
        set(value) {
            checkNotInited()
            field = value
        }

    private val blockchains = mutableMapOf<Long, BlockchainRid>()
    private val inserts = mutableListOf<String>()

    fun init() {
        if (inited) return
        if (useSql) {
            initSql()
        }
        inited = true
    }

    private fun initSql() {
        val conn = SqlTestUtils.createSqlConnection()
        var closeable: Connection? = conn

        try {
            conn.autoCommit = true

            val sqlMgr = Rt_SqlManager(TestSqlManager(ConnectionSqlManager(conn, sqlLogging), sqlStats), true)

            sqlMgr.transaction { sqlExec ->
                SqlUtils.dropAll(sqlExec, true)

                initSqlCreateSysTables(sqlExec)
                initSqlInsertBlockchains(sqlExec)

                if (!inserts.isEmpty()) {
                    val insertsSql = inserts.joinToString("\n")
                    sqlExec.execute(insertsSql)
                }
            }

            this.sqlMgr = sqlMgr
            sqlResource = conn
            closeable = null
        } finally {
            closeable?.close()
        }
    }

    private fun initSqlCreateSysTables(sqlExec: SqlExecutor) {
        val sqlAccess: SQLDatabaseAccess = PostgreSQLDatabaseAccess()
        sqlExec.connection { con ->
            sqlAccess.initializeApp(con, PostchainUtils.DATABASE_VERSION)
        }
    }

    private fun initSqlInsertBlockchains(sqlExecLoc: SqlExecutor) {
        if (blockchains.isEmpty()) return

        val inserts = blockchains.entries.map { ( chainId, rid ) ->
            val ridStr = rid.toHex()
            """INSERT INTO blockchains(chain_iid, blockchain_rid) VALUES ($chainId, E'\\x$ridStr');"""
        }

        val insertSql = inserts.joinToString("\n") { it }
        sqlExecLoc.execute(insertSql)
    }

    private fun checkNotInited() {
        check(!inited)
    }

    fun blockchain(chainId: Long, rid: String) {
        checkNotInited()
        val bcRid = RellTestUtils.strToBlockchainRid(rid)
        check(chainId !in blockchains)
        blockchains[chainId] = bcRid
    }

    fun insert(table: String, columns: String, values: String) {
        checkNotInited()
        val ins = SqlTestUtils.mkins(table, columns, values)
        inserts += listOf(ins)
    }

    fun insert(inserts: List<String>) {
        checkNotInited()
        this.inserts += inserts
    }

    override fun close() {
        if (!inited || destroyed) return
        sqlResource?.close()
        sqlResource = null
        destroyed = true
    }

    fun sqlMgr(): SqlManager {
        init()
        return if (useSql) sqlMgr!! else NoConnSqlManager
    }

    fun resetSqlCounter() {
        sqlStats.count = 0
    }

    fun sqlCounter() = sqlStats.count

    private class TestSqlStats {
        var count = 0
    }

    private class TestSqlManager(private val mgr: SqlManager, private val stats: TestSqlStats): SqlManager() {
        override val hasConnection = mgr.hasConnection

        override fun <T> execute0(tx: Boolean, code: (SqlExecutor) -> T): T {
            return mgr.execute(tx) { sqlExec ->
                code(TestSqlExecutor(sqlExec))
            }
        }

        private inner class TestSqlExecutor(private val exec: SqlExecutor): SqlExecutor() {
            override fun <T> connection(code: (Connection) -> T): T = exec.connection(code)

            override fun execute(sql: String) {
                stats.count++
                exec.execute(sql)
            }

            override fun execute(sql: String, preparator: (PreparedStatement) -> Unit) {
                stats.count++
                exec.execute(sql, preparator)
            }

            override fun executeQuery(sql: String, preparator: (PreparedStatement) -> Unit, consumer: (ResultSet) -> Unit) {
                stats.count++
                exec.executeQuery(sql, preparator, consumer)
            }
        }
    }
}
