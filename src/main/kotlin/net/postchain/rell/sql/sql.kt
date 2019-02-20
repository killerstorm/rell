package net.postchain.rell.sql

import net.postchain.rell.model.R_Module
import net.postchain.rell.runtime.Rt_SqlMapper
import java.io.Closeable
import java.sql.*

val ROWID_COLUMN = "rowid"

abstract class SqlExecutor {
    abstract fun transaction(code: () -> Unit)
    abstract fun execute(sql: String)
    abstract fun execute(sql: String, preparator: (PreparedStatement) -> Unit)
    abstract fun executeQuery(sql: String, preparator: (PreparedStatement) -> Unit, consumer: (ResultSet) -> Unit)
}

class DefaultSqlExecutor(private val con: Connection, private val logging: Boolean): SqlExecutor(), Closeable {
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
        if (logging) {
            println(sql)
        }
        return code()
    }

    override fun close() {
        con.close()
    }

    companion object {
        fun connect(url: String): DefaultSqlExecutor {
            return connect0() { DriverManager.getConnection(url) }
        }

        private fun connect0(factory: () -> Connection): DefaultSqlExecutor {
            val con = factory()
            var c: AutoCloseable? = con
            try {
                con.autoCommit = false
                val exec = DefaultSqlExecutor(con, false)
                c = null
                return exec
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
    fun resetDatabase(
            sqlExec: SqlExecutor,
            module: R_Module,
            sqlMapper: Rt_SqlMapper,
            dropTables: Boolean,
            createBlockTables: Boolean
    ) {
        sqlExec.transaction {
            if (dropTables) {
                dropAll(sqlExec)
            }
            val sql = genSql(module, sqlMapper, createBlockTables, false)
            sqlExec.execute(sql)
        }
    }

    fun dropAll(sqlExec: SqlExecutor) {
        dropTables(sqlExec)
        dropFunctions(sqlExec)
    }

    private fun dropTables(sqlExec: SqlExecutor) {
        val tables = getExistingTables(sqlExec)
        val sql = tables.joinToString("\n") { "DROP TABLE \"$it\" CASCADE;" }
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
