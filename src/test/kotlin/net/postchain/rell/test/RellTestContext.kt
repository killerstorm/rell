/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.test

import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.base.data.SQLDatabaseAccess
import net.postchain.rell.CommonUtils
import net.postchain.rell.PostchainUtils
import net.postchain.rell.runtime.Rt_SqlManager
import net.postchain.rell.sql.*
import java.io.Closeable
import java.sql.Connection

class RellTestContext(useSql: Boolean = true): Closeable {
    class BlockBuilder(private val chainId: Long) {
        private val list = mutableListOf<String>()

        fun list() = list.toList()

        fun block(iid: Long, height: Long, rid: String, header: String, timestamp: Long): BlockBuilder {
            val s = """INSERT INTO "c$chainId.blocks"(block_iid,block_height,block_rid,block_header_data,block_witness,timestamp)
                VALUES($iid,$height,E'\\x$rid',E'\\x$header',NULL,$timestamp);""".trimIndent()
            list.add(s)
            return this
        }

        fun tx(iid: Long, block: Long, rid: String, data: String, hash: String): BlockBuilder {
            val s = """INSERT INTO "c$chainId.transactions"(tx_iid,tx_rid,tx_data,tx_hash,block_iid)
                VALUES($iid,E'\\x$rid',E'\\x$data',E'\\x$hash',$block);""".trimIndent()
            list.add(s)
            return this
        }
    }

    private var inited = false
    private var destroyed = false
    private var sqlResource: AutoCloseable? = null
    private var sqlMgr: SqlManager? = null

    var sqlLogging = false

    var useSql: Boolean = useSql
        set(value) {
            checkNotInited()
            field = value
        }

    private val blockchains = mutableMapOf<Long, ByteArray>()
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

            val sqlMgr = Rt_SqlManager(ConnectionSqlManager(conn, sqlLogging), true)

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
            val ridStr = CommonUtils.bytesToHex(rid)
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
        val ridArray = CommonUtils.hexToBytes(rid)
        check(chainId !in blockchains)
        blockchains[chainId] = ridArray
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
}
