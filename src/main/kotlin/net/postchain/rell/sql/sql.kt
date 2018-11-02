package net.postchain.rell.sql

import java.io.Closeable
import java.lang.UnsupportedOperationException
import java.sql.*

val ROWID_COLUMN = "rowid"
val MAKE_ROWID_FUNCTION = "make_rowid"

sealed class SqlExecutor {
    abstract fun transaction(code: () -> Unit)
    abstract fun execute(sql: String)
    abstract fun execute(sql: String, preparator: (PreparedStatement) -> Unit)
    abstract fun executeQuery(sql: String, preparator: (PreparedStatement) -> Unit, consumer: (ResultSet) -> Unit)
}

class DefaultSqlExecutor(private val con: Connection): SqlExecutor(), Closeable {
    init {
        con.autoCommit = false
    }

    override fun transaction(code: () -> Unit) {
        var rollback = true
        try {
            code()
            con.commit()
            rollback = false
        } finally {
            if (rollback) {
                con.rollback()
            }
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
        try {
            return code()
        } catch (e: SQLException) {
            System.err.println("SQL FAILED: $sql")
            throw e
        }
    }

    override fun close() {
        con.close()
    }

    companion object {
        fun connect(url: String): DefaultSqlExecutor {
            val con = DriverManager.getConnection(url)
            var c: AutoCloseable? = con
            try {
                val exec = DefaultSqlExecutor(con)
                c = null
                return exec
            } finally {
                c?.close()
            }
        }
    }
}

object NullSqlExecutor: SqlExecutor() {
    override fun transaction(code: () -> Unit) {
        code()
    }

    override fun execute(sql: String) {
        throw UnsupportedOperationException()
    }

    override fun execute(sql: String, preparator: (PreparedStatement) -> Unit) {
        throw UnsupportedOperationException()
    }

    override fun executeQuery(sql: String, preparator: (PreparedStatement) -> Unit, consumer: (ResultSet) -> Unit) {
        throw UnsupportedOperationException()
    }
}

private inline fun <T:AutoCloseable, R> T.use(block: (T) -> R): R {
    try {
        return block(this);
    } finally {
        close()
    }
}
