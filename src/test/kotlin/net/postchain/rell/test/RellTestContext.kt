package net.postchain.rell.test

import net.postchain.rell.CommonUtils
import net.postchain.rell.runtime.Rt_SqlExecutor
import net.postchain.rell.sql.*
import java.io.Closeable
import java.sql.Connection

class RellTestContext(useSql: Boolean = true): Closeable {
    class BlockBuilder {
        private val list = mutableListOf<String>()

        fun list() = list.toList()

        fun block(iid: Long, chainId: Long, height: Long, rid: String, header: String, timestamp: Long): BlockBuilder {
            val s = """INSERT INTO "blocks"(block_iid,block_height,block_rid,chain_id,block_header_data,block_witness,timestamp)
                VALUES($iid,$height,E'\\x$rid',$chainId,E'\\x$header',NULL,$timestamp);""".trimIndent()
            list.add(s)
            return this
        }

        fun tx(iid: Long, chainId: Long, block: Long, rid: String, data: String, hash: String): BlockBuilder {
            val s = """INSERT INTO "transactions"(tx_iid,chain_id,tx_rid,tx_data,tx_hash,block_iid)
                VALUES($iid,$chainId,E'\\x$rid',E'\\x$data',E'\\x$hash',$block);""".trimIndent()
            list.add(s)
            return this
        }
    }

    private var inited = false
    private var destroyed = false
    private var sqlConn: Connection? = null
    private var sqlExec: SqlExecutor = NoConnSqlExecutor

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
            val realSqlExec = Rt_SqlExecutor(DefaultSqlExecutor(conn, sqlLogging), true)

            realSqlExec.transaction {
                SqlUtils.dropAll(realSqlExec, true)

                val sql = SqlGen.genSqlCreateSysTables()
                realSqlExec.execute(sql)

                initSqlInsertBlockchains(realSqlExec)

                if (!inserts.isEmpty()) {
                    val insertsSql = inserts.joinToString("\n")
                    realSqlExec.execute(insertsSql)
                }
            }

            sqlConn = conn
            sqlExec = realSqlExec
            closeable = null
        } finally {
            closeable?.close()
        }
    }

    private fun initSqlInsertBlockchains(sqlExecLoc: SqlExecutor) {
        if (blockchains.isEmpty()) return

        val inserts = blockchains.entries.map { ( chainId, rid ) ->
            val ridStr = CommonUtils.bytesToHex(rid)
            """INSERT INTO blockchains(chain_id, blockchain_rid) VALUES ($chainId, E'\\x$ridStr');"""
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
        sqlConn?.close()
        sqlConn = null
        destroyed = true
    }

    fun sqlConn(): Connection {
        init()
        return sqlConn!!
    }

    fun sqlExec(): SqlExecutor {
        init()
        return sqlExec
    }
}
