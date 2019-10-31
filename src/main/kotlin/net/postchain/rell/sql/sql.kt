package net.postchain.rell.sql

import java.io.Closeable
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicLong

object SqlConstants {
    const val ROWID_COLUMN = "rowid"
    const val ROWID_GEN = "rowid_gen"
    const val MAKE_ROWID = "make_rowid"

    const val BLOCKS_TABLE = "blocks"
    const val BLOCKCHAINS_TABLE = "blockchains"
    const val TRANSACTIONS_TABLE = "transactions"

    val SYSTEM_OBJECTS = setOf(
            ROWID_GEN,
            MAKE_ROWID,
            BLOCKS_TABLE,
            BLOCKCHAINS_TABLE,
            TRANSACTIONS_TABLE,
            "configurations",
            "meta",
            "peerinfos"
    )
}

abstract class SqlExecutor {
    abstract fun connection(): Connection
    abstract fun transaction(code: () -> Unit)
    abstract fun execute(sql: String)
    abstract fun execute(sql: String, preparator: (PreparedStatement) -> Unit)
    abstract fun executeQuery(sql: String, preparator: (PreparedStatement) -> Unit, consumer: (ResultSet) -> Unit)
}

class DefaultSqlExecutor(private val con: Connection, private val logging: Boolean): SqlExecutor(), Closeable {
    private val conId = idCounter.getAndIncrement()

    override fun connection(): Connection {
        return con
    }

    override fun transaction(code: () -> Unit) {
        val autoCommit = con.autoCommit
        try {
            con.autoCommit = false
            var rollback = true
            try {
                log("BEGIN TRANSACTION")
                code()
                log("COMMIT TRANSACTION")
                con.commit()
                rollback = false
            } finally {
                if (rollback) {
                    log("ROLLBACK TRANSACTION")
                    con.rollback()
                }
            }
        } finally {
            con.autoCommit = autoCommit
        }
    }

    override fun execute(sql: String) {
        execute0(sql) {
            con.createStatement().use { stmt ->
                stmt.execute(sql)
            }
        }
    }

    override fun execute(sql: String, preparator: (PreparedStatement) -> Unit) {
        execute0(sql) {
            con.prepareStatement(sql).use { stmt ->
                preparator(stmt)
                stmt.execute()
            }
        }
    }

    override fun executeQuery(sql: String, preparator: (PreparedStatement) -> Unit, consumer: (ResultSet) -> Unit) {
        execute0(sql) {
            con.prepareStatement(sql).use { stmt ->
                preparator(stmt)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        consumer(rs)
                    }
                }
            }
        }
    }

    private fun <T> execute0(sql: String, code: () -> T): T {
        log(sql)
        return code()
    }

    override fun close() {
        con.close()
    }

    private fun log(s: String) {
        if (logging) println("[$conId] $s")
    }

    companion object {
        private val idCounter = AtomicLong()

        fun connect(url: String): Connection {
            val con = DriverManager.getConnection(url)
            var c: AutoCloseable? = con
            try {
                con.autoCommit = true
                c = null
                return con
            } finally {
                c?.close()
            }
        }
    }
}

object NoConnSqlExecutor: SqlExecutor() {
    override fun connection() = throw err()

    override fun transaction(code: () -> Unit) {
        code()
    }

    override fun execute(sql: String) = throw err()
    override fun execute(sql: String, preparator: (PreparedStatement) -> Unit) = throw err()
    override fun executeQuery(sql: String, preparator: (PreparedStatement) -> Unit, consumer: (ResultSet) -> Unit) = throw err()

    private fun err() = IllegalStateException("No database connection")
}
