/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.sql

import com.google.common.collect.HashMultimap
import net.postchain.rell.base.model.R_EntityDefinition
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.utils.checkEquals
import java.sql.Connection

class SqlCol(val type: String)
class SqlIndex(val name: String, val unique: Boolean, val cols: List<String>)
class SqlTable(val cols: Map<String, SqlCol>, val indexes: List<SqlIndex>)

object SqlUtils {
    fun dropAll(sqlExec: SqlExecutor, sysTables: Boolean) {
        dropTables(sqlExec, sysTables)
        dropFunctions(sqlExec)
    }

    private fun dropTables(sqlExec: SqlExecutor, sysTables: Boolean) {
        val tables = getExistingTables(sqlExec)
        val delTables = tables
            .filter { sysTables || it !in SqlConstants.SYSTEM_APP_TABLES }
            .filter { !it.startsWith("pg_") }
        val sql = delTables.joinToString("\n") { "DROP TABLE IF EXISTS \"$it\" CASCADE;" }
        sqlExec.execute(sql)
    }

    private fun dropFunctions(sqlExec: SqlExecutor) {
        val functions = getExistingFunctions(sqlExec)
        val delFunctions = functions
            .filter { !it.startsWith("pg_") }
        val sql = delFunctions.joinToString("\n") { "DROP FUNCTION \"$it\";" }
        sqlExec.execute(sql)
    }

    fun getExistingTables(sqlExec: SqlExecutor): List<String> {
        val sql = "SELECT table_name FROM information_schema.tables WHERE table_catalog = CURRENT_DATABASE() AND table_schema = CURRENT_SCHEMA();"
        val list = mutableListOf<String>()
        sqlExec.executeQuery(sql, {}) { rs -> list.add(rs.getString(1))}
        return list.toList()
    }

    fun getExistingFunctions(sqlExec: SqlExecutor): List<String> {
        val sql = "SELECT routine_name FROM information_schema.routines WHERE routine_catalog = CURRENT_DATABASE() AND routine_schema = CURRENT_SCHEMA();"
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
            val indexes = getTableIndexes(con, schema, table)
            res[table] = SqlTable(cols, indexes)
        }

        return res
    }

    private fun getTableIndexes(con: Connection, schema: String, table: String): List<SqlIndex> {
        class IndexRec(val unique: Boolean, val ordinal: Int, val column: String)
        val map = HashMultimap.create<String, IndexRec>()

        con.metaData.getIndexInfo(null, schema, table, false, false).use { rs ->
            while (rs.next()) {
                val indexTable = rs.getString(3)
                check(indexTable == table) { "Wrong table: $indexTable != $table" }
                val unique = !rs.getBoolean(4)
                val name = rs.getString(6)
                val ordinal = rs.getInt(8)
                val column = rs.getString(9)
                map.put(name, IndexRec(unique, ordinal, column))
            }
        }

        val res = mutableListOf<SqlIndex>()

        for (name in map.keySet().sorted()) {
            val recs = map.get(name)
            val sortedRecs = recs.toList().sortedBy { it.ordinal }
            val n = sortedRecs.size

            val ordinals = sortedRecs.map { it.ordinal }
            val expOrdinals = (1 .. n).toList()
            check(ordinals == expOrdinals) { "Table $table, index $name: ordinals = $ordinals" }

            val cols = sortedRecs.map { it.column }
            check(cols.toSet().size == cols.size) { "Table $table, index $name: duplicate column(s): $cols" }

            val uniques = sortedRecs.map { it.unique }.toSet()
            check(uniques.size == 1) { "Table $table, index $name: conflicting unique flag" }
            val unique = uniques.iterator().next()

            res.add(SqlIndex(name, unique, cols))
        }

        return res
    }

    fun recordsExist(sqlExec: SqlExecutor, sqlCtx: Rt_SqlContext, entity: R_EntityDefinition): Boolean {
        val table = entity.sqlMapping.table(sqlCtx)
        val sql = """SELECT "${SqlConstants.ROWID_COLUMN}" FROM "$table" LIMIT 1;"""
        var res: Boolean = false
        sqlExec.executeQuery(sql, {}) { res = true }
        return res
    }

    fun printConnectionInfo(sqlExec: SqlExecutor) {
        var database: String? = null
        var schema: String? = null
        sqlExec.executeQuery("SELECT CURRENT_DATABASE(), CURRENT_SCHEMA();", {}) { rs ->
            checkEquals(database, null)
            checkEquals(schema, null)
            database = rs.getString(1)
            schema = rs.getString(2)
        }
        println("Database: [$database], schema: [$schema]")
    }

    fun initDatabase(
        appCtx: Rt_AppContext,
        sqlCtx: Rt_SqlContext,
        sqlMgr: SqlManager,
        adapter: SqlInitProjExt,
        dropTables: Boolean,
        sqlInitLog: Boolean,
    ) {
        sqlMgr.transaction { sqlExec ->
            if (dropTables) {
                dropAll(sqlExec, true)
            }

            val exeCtx = Rt_ExecutionContext(appCtx, Rt_NullOpContext, sqlCtx, sqlExec)
            val initLogging = SqlInitLogging.ofLevel(if (sqlInitLog) SqlInitLogging.LOG_ALL else SqlInitLogging.LOG_NONE)
            SqlInit.init(exeCtx, adapter, initLogging)
        }
    }
}
