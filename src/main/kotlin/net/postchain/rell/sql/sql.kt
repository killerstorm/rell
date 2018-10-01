package net.postchain.rell.sql

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

object SqlConnector {
    fun <T> connect(block: (SqlExecutor) -> T): T {
        DriverManager.getConnection("jdbc:h2:mem:test;MODE=PostgreSQL").use { con ->
            val executor = SqlExecutor(con)
            return block(executor)
        }
    }
}

class SqlExecutor(private val con: Connection) {
    fun execute(sql: String) {
        con.createStatement().use { stmt ->
            stmt.execute(sql)
        }
    }

    fun executeQuery(sql: String, preparator: (PreparedStatement) -> Unit, consumer: (ResultSet) -> Unit) {
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

private inline fun <T:AutoCloseable, R> T.use(block: (T) -> R): R {
    try {
        return block(this);
    } finally {
        close()
    }
}
