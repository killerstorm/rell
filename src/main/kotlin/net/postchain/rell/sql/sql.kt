package net.postchain.rell.sql

import java.io.Closeable
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicLong

val ROWID_COLUMN = "rowid"

abstract class SqlExecutor {
    abstract fun transaction(code: () -> Unit)
    abstract fun execute(sql: String)
    abstract fun execute(sql: String, preparator: (PreparedStatement) -> Unit)
    abstract fun executeQuery(sql: String, preparator: (PreparedStatement) -> Unit, consumer: (ResultSet) -> Unit)
}

class DefaultSqlExecutor(private val con: Connection, private val logging: Boolean): SqlExecutor(), Closeable {
    private val conId = idCounter.getAndIncrement()

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
    override fun transaction(code: () -> Unit) {
        code()
    }

    override fun execute(sql: String) = throw err()
    override fun execute(sql: String, preparator: (PreparedStatement) -> Unit) = throw err()
    override fun executeQuery(sql: String, preparator: (PreparedStatement) -> Unit, consumer: (ResultSet) -> Unit) = throw err()

    private fun err() = IllegalStateException("No database connection")
}

object SqlUtils {
    fun dropAll(sqlExec: SqlExecutor, sysTables: Boolean) {
        dropTables(sqlExec, sysTables)
        dropFunctions(sqlExec)
    }

    private fun dropTables(sqlExec: SqlExecutor, sysTables: Boolean) {
        val tables = getExistingTables(sqlExec)

        val delTables = if (sysTables) tables else {
            val sys = setOf("blocks", "transactions", "blockchains")
            tables.filter { it !in sys }
        }

        val sql = delTables.joinToString("\n") { "DROP TABLE \"$it\" CASCADE;" }
        sqlExec.execute(sql)
    }

    private fun dropFunctions(sqlExec: SqlExecutor) {
        val functions = getExistingFunctions(sqlExec)
        val sql = functions.joinToString("\n") { "DROP FUNCTION \"$it\"();" }
        sqlExec.execute(sql)
    }

    fun getExistingTables(sqlExec: SqlExecutor): List<String> {
        val sql = "SELECT table_name FROM information_schema.tables WHERE table_catalog = CURRENT_DATABASE() AND table_schema = 'public'"
        val list = mutableListOf<String>()
        sqlExec.executeQuery(sql, {}) { rs -> list.add(rs.getString(1))}
        return list.toList()
    }

    private fun getExistingFunctions(sqlExec: SqlExecutor): List<String> {
        val sql = "SELECT routine_name FROM information_schema.routines WHERE routine_catalog = CURRENT_DATABASE() AND routine_schema = 'public'"
        val list = mutableListOf<String>()
        sqlExec.executeQuery(sql, {}) { rs -> list.add(rs.getString(1))}
        return list.toList()
    }
}

private inline fun <T:AutoCloseable, R> T.use(block: (T) -> R): R {
    try {
        return block(this);
    } finally {
        close()
    }
}
