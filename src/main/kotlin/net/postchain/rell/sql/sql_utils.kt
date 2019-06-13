package net.postchain.rell.sql

import net.postchain.rell.model.R_Class
import net.postchain.rell.runtime.Rt_ChainSqlMapping
import net.postchain.rell.runtime.Rt_SqlContext
import java.sql.Connection

class SqlCol(val type: String)
class SqlTable(val cols: Map<String, SqlCol>)

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

    fun getExistingChainTables(con: Connection, mapping: Rt_ChainSqlMapping): Map<String, SqlTable> {
        val tables = mutableMapOf<String, MutableMap<String, SqlCol>>()

        val schema = con.schema
        con.metaData.getColumns(null, schema, mapping.tableSqlFilter, null).use { rs ->
            while (rs.next()) {
                val table = rs.getString(3)
                val column = rs.getString(4)
                val type = rs.getString(6)
                if (mapping.isChainTable(table)) {
                    val columns = tables.computeIfAbsent(table) { mutableMapOf() }
                    check(column !in columns) { "$table $column" }
                    columns[column] = SqlCol(type)
                }
            }
        }

        val res = mutableMapOf<String, SqlTable>()
        for (table in tables.keys.sorted()) {
            val colsMap = tables.getValue(table)
            val cols = colsMap.keys.sorted().map { Pair(it, colsMap.getValue(it) ) }.toMap()
            res[table] = SqlTable(cols)
        }

        return res
    }

    fun recordsExist(sqlExec: SqlExecutor, sqlCtx: Rt_SqlContext, cls: R_Class): Boolean {
        val table = cls.sqlMapping.table(sqlCtx)
        val sql = """SELECT "$ROWID_COLUMN" FROM "$table" LIMIT 1;"""
        var res: Boolean = false
        sqlExec.executeQuery(sql, {}) { res = true }
        return res
    }
}

private inline fun <T:AutoCloseable, R> T.use(block: (T) -> R): R {
    try {
        return block(this);
    } finally {
        close()
    }
}
