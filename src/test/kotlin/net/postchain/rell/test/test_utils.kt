/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.test

import com.google.common.collect.HashMultimap
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory
import net.postchain.gtv.GtvType
import net.postchain.rell.compiler.base.core.C_CompilationResult
import net.postchain.rell.compiler.base.core.C_CompilerModuleSelection
import net.postchain.rell.compiler.base.core.C_CompilerOptions
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_EntityDefinition
import net.postchain.rell.model.R_Param
import net.postchain.rell.model.R_StackPos
import net.postchain.rell.module.GtvToRtContext
import net.postchain.rell.module.RellVersions
import net.postchain.rell.runtime.Rt_ChainSqlMapping
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.sql.SqlConstants
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlManager
import net.postchain.rell.sql.SqlUtils
import net.postchain.rell.tools.api.IdeCodeSnippet
import net.postchain.rell.tools.api.IdeSnippetMessage
import net.postchain.rell.utils.*
import org.apache.commons.configuration2.PropertiesConfiguration
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder
import org.apache.commons.configuration2.builder.fluent.Parameters
import org.apache.commons.configuration2.io.ClasspathLocationStrategy
import org.postgresql.util.PGobject
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

fun String.unwrap(): String = this.replace(Regex("\\n\\s*"), "")

class T_App(val rApp: R_App, val messages: List<C_Message>)

object SqlTestUtils {
    fun createSqlConnection(schema: String? = null): Connection {
        val prop = readDbProperties()

        var url = prop.url
        if (schema != null) {
            url += (if ("?" in url) "&" else "?") + "currentSchema=$schema"
        }

        val jdbcProperties = Properties()
        jdbcProperties.setProperty("user", prop.user)
        jdbcProperties.setProperty("password", prop.password)
        jdbcProperties.setProperty("binaryTransfer", "false")
        val con = DriverManager.getConnection(url, jdbcProperties)
        var resource: AutoCloseable? = con
        try {
            freeDiskSpace(con)
            resource = null
        } finally {
            resource?.close()
        }

        return con
    }

    private fun readDbProperties(): DbConnProps {
        val localFileName = "local-config.properties"
        val localRes = javaClass.getResource("/" + localFileName)
        val configFileName = if (localRes != null) localFileName else "config.properties"
        val config = readProperties(configFileName)

        val url = System.getenv("POSTCHAIN_DB_URL") ?: config.getString("database.url")
        val user = System.getenv("POSTGRES_USER") ?: config.getString("database.username")
        val password = System.getenv("POSTGRES_PASSWORD") ?: config.getString("database.password")
        return DbConnProps(url, user, password)
    }

    private fun readProperties(fileName: String): PropertiesConfiguration {
        val propertiesFile = File(fileName)
        val params = Parameters()
                .fileBased()
                .setLocationStrategy(ClasspathLocationStrategy())
                .setFile(propertiesFile)

        val builder = FileBasedConfigurationBuilder(PropertiesConfiguration::class.java).configure(params)
        return builder.configuration
    }

    private data class DbConnProps(val url: String, val user: String, val password: String)

    private var freeDiskSpace = true

    // When running all tests multiple times in a row, sometimes Postgres starts failing with "no space left on device"
    // error. Executing VACUUM shall fix this.
    // https://www.postgresql.org/docs/current/sql-vacuum.html
    // https://dba.stackexchange.com/questions/37028/vacuum-returning-disk-space-to-operating-system
    private fun freeDiskSpace(con: Connection) {
        if (!freeDiskSpace) return
        freeDiskSpace = false
        con.createStatement().use { stmt ->
            stmt.execute("VACUUM FULL;")
        }
    }

    fun resetRowid(sqlExec: SqlExecutor, chainMapping: Rt_ChainSqlMapping) {
        val table = chainMapping.rowidTable
        sqlExec.execute("""UPDATE "$table" SET last_value = 0;""")
    }

    fun clearTables(sqlExec: SqlExecutor) {
        val tables = SqlUtils.getExistingTables(sqlExec)
        val sql = tables.joinToString("\n") { "TRUNCATE \"$it\" CASCADE;" }
        sqlExec.execute(sql)
    }

    fun mkins(table: String, columns: String, values: String): String {
        val quotedColumns = columns.split(",").joinToString { "\"$it\"" }
        return "INSERT INTO \"$table\"(\"${SqlConstants.ROWID_COLUMN}\",$quotedColumns) VALUES ($values);"
    }

    fun dumpDatabaseEntity(sqlExec: SqlExecutor, chainMapping: Rt_ChainSqlMapping, app: R_App): List<String> {
        val list = mutableListOf<String>()

        for (entity in app.sqlDefs.entities) {
            if (entity.sqlMapping.autoCreateTable()) {
                dumpEntity(sqlExec, chainMapping, entity, list)
            }
        }

        for (obj in app.sqlDefs.objects) {
            dumpEntity(sqlExec, chainMapping, obj.rEntity, list)
        }

        return list.toList()
    }

    private fun dumpEntity(sqlExec: SqlExecutor, chainMapping: Rt_ChainSqlMapping, entity: R_EntityDefinition, list: MutableList<String>) {
        val table = entity.sqlMapping.table(chainMapping)
        val cols = listOf(entity.sqlMapping.rowidColumn()) + entity.attributes.values.map { it.sqlMapping }
        val sql = getTableDumpSql(table, cols, entity.sqlMapping.rowidColumn())
        val rows = dumpSql(sqlExec, sql).map { "${entity.moduleLevelName}($it)" }
        list += rows
    }

    fun dumpSql(sqlExec: SqlExecutor, sql: String): List<String> {
        val list = mutableListOf<String>()
        sqlExec.executeQuery(sql, {}) { rs -> list.add(dumpSqlRecord(rs)) }
        return list
    }

    private fun getTableDumpSql(table: String, columns: List<String>, sortColumn: String?): String {
        val buf = StringBuilder()
        buf.append("SELECT")
        columns.joinTo(buf, ", ") { "\"$it\"" }

        buf.append(" FROM \"${table}\"")
        if (sortColumn != null) {
            buf.append(" ORDER BY \"$sortColumn\"")
        }

        return buf.toString()
    }

    private fun dumpSqlRecord(rs: ResultSet): String {
        val values = mutableListOf<String>()

        for (idx in 1 .. rs.metaData.columnCount) {
            val value = rs.getObject(idx)
            val str = if (value is String) {
                value
            } else if (value is ByteArray) {
                "0x" + CommonUtils.bytesToHex(rs.getBytes(idx))
            } else if (value is PGobject) {
                value.value
            } else if (value is Int || value is Long) {
                "" + value
            } else if (value is Boolean) {
                "" + value
            } else if (value is BigDecimal) {
                "" + value
            } else if (value == null) {
                "NULL"
            } else {
                throw IllegalStateException(value.javaClass.canonicalName)
            }
            values.add("" + str)
        }

        return values.joinToString(",")
    }

    fun dumpDatabaseTables(sqlMgr: SqlManager): Map<String, List<String>> {
        val res = sqlMgr.access { sqlExec ->
            sqlExec.connection { con ->
                dumpDatabaseTables(con, sqlExec)
            }
        }
        return res
    }

    private fun dumpDatabaseTables(con: Connection, sqlExec: SqlExecutor): Map<String, List<String>> {
        val res = mutableMapOf<String, List<String>>()

        val struct = dumpTablesStructure(con)
        for ((table, attrs) in struct) {
            val columns = attrs.keys.toMutableList()
            val rowid = columns.remove(SqlConstants.ROWID_COLUMN)
            if (rowid) columns.add(0, SqlConstants.ROWID_COLUMN)
            val sql = getTableDumpSql(table, columns, if (rowid) SqlConstants.ROWID_COLUMN else null)
            val rows = dumpSql(sqlExec, sql)
            res[table] = rows
        }

        return res
    }

    fun dumpTablesStructure(con: Connection, all: Boolean = false): Map<String, Map<String, String>> {
        val map = HashMultimap.create<String, Pair<String, String>>()
        val namePattern = if (all) null else "c%.%"
        con.metaData.getColumns(null, con.schema, namePattern, null).use { rs ->
            while (rs.next()) {
                val table = rs.getString(3)
                val column = rs.getString(4)
                val type = rs.getString(6)
                if (all || table.matches(Regex("c\\d+\\..+"))) {
                    map.put(table, Pair(column, type))
                }
            }
        }

        val res = mutableMapOf<String, Map<String, String>>()
        for (table in map.keySet().sorted()) {
            res[table] = map[table].sortedBy { it.first }.toMap()
        }

        return res
    }
}

object GtvTestUtils {
    fun decodeGtvStr(s: String) = PostchainUtils.jsonToGtv(s)
    fun encodeGtvStr(gtv: Gtv) = PostchainUtils.gtvToJson(gtv)

    fun decodeGtvQueryArgs(params: List<R_Param>, args: List<String>): List<Rt_Value> {
        return decodeGtvArgs(params, args, true)
    }

    fun decodeGtvOpArgs(params: List<R_Param>, args: List<Gtv>): List<Rt_Value> {
        checkEquals(args.size, params.size)
        val ctx = GtvToRtContext.make(false)
        return args.mapIndexed { i, gtv ->
            params[i].type.gtvToRt(ctx, gtv)
        }
    }

    fun decodeGtvOpArgsStr(params: List<R_Param>, args: List<String>): List<Rt_Value> {
        return decodeGtvArgs(params, args, false)
    }

    private fun decodeGtvArgs(params: List<R_Param>, args: List<String>, pretty: Boolean): List<Rt_Value> {
        checkEquals(args.size, params.size)
        val ctx = GtvToRtContext.make(pretty)
        return args.mapIndexed { i, arg ->
            val gtv = decodeGtvStr(arg)
            params[i].type.gtvToRt(ctx, gtv)
        }
    }

    fun gtvToStr(gtv: Gtv): String {
        val s = encodeGtvStr(gtv)
        return s.replace('"', '\'').replace("\\u003c", "<").replace("\\u003e", ">").replace("\\u003d", "=")
    }

    fun strToGtv(s: String): Gtv {
        val s2 = s.replace('\'', '"')
        return decodeGtvStr(s2)
    }

    fun merge(v1: Gtv, v2: Gtv): Gtv {
        checkEquals(v2.type, v1.type)
        return when (v1.type) {
            GtvType.ARRAY -> GtvFactory.gtv(v1.asArray().toList() + v2.asArray().toList())
            GtvType.DICT -> {
                val m1 = v1.asDict()
                val m2 = v2.asDict()
                val m = (m1.keys + m2.keys).toSet().map {
                    val x1 = m1[it]
                    val x2 = m2[it]
                    val x = if (x1 == null) x2 else if (x2 == null) x1 else merge(x1, x2)
                    it to x!!
                }.toMap()
                GtvFactory.gtv(m)
            }
            else -> v2
        }
    }
}

object TestSnippetsRecorder {
    private val ENABLED = false
    private val SOURCES_FILE: String = System.getProperty("user.home") + "/testsources-${RellVersions.VERSION_STR}.zip"

    private val sync = Any()
    private val snippets = mutableListOf<IdeCodeSnippet>()
    private var shutdownHookInstalled = false

    fun record(
            sourceDir: C_SourceDir,
            modules: C_CompilerModuleSelection,
            options: C_CompilerOptions,
            res: C_CompilationResult
    ) {
        if (!ENABLED) return

        val files = sourceDirToMap(sourceDir)
        val messages = res.messages.map { IdeSnippetMessage(it.pos.str(), it.type, it.code, it.text) }
        val parsing = makeParsing(files)

        val snippet = IdeCodeSnippet(files, modules, options, messages, parsing)
        addSnippet(snippet)
    }

    private fun sourceDirToMap(sourceDir: C_SourceDir): Map<String, String> {
        val map = mutableMapOf<C_SourcePath, String>()
        sourceDirToMap0(sourceDir, C_SourcePath.EMPTY, map)
        return map.mapKeys { (k, _) -> k.str() }.toImmMap()
    }

    private fun sourceDirToMap0(sourceDir: C_SourceDir, path: C_SourcePath, map: MutableMap<C_SourcePath, String>) {
        for (file in sourceDir.files(path)) {
            val subPath = path.add(file)
            check(subPath !in map) { "File already in the map: $subPath" }
            val text = sourceDir.file(subPath)!!.readText()
            map[subPath] = text
        }

        for (dir in sourceDir.dirs(path)) {
            val subPath = path.add(dir)
            sourceDirToMap0(sourceDir, subPath, map)
        }
    }

    private fun makeParsing(files: Map<String, String>): Map<String, List<IdeSnippetMessage>> {
        val res = mutableMapOf<String, List<IdeSnippetMessage>>()

        for ((file, code) in files) {
            val sourcePath = C_SourcePath.parse(file)
            val idePath = IdeSourcePathFilePath(sourcePath)
            val messages = try {
                C_Parser.parse(sourcePath, idePath, code)
                listOf()
            } catch (e: C_Error) {
                listOf(IdeSnippetMessage(e.pos.str(), C_MessageType.ERROR, e.code, e.errMsg))
            }
            res[file] = messages
        }

        return res.toImmMap()
    }

    private fun addSnippet(snippet: IdeCodeSnippet) {
        synchronized (sync) {
            snippets.add(snippet)
            if (!shutdownHookInstalled) {
                val thread = Thread(TestSnippetsRecorder::saveSources)
                thread.name = "SaveSources"
                thread.isDaemon = false
                Runtime.getRuntime().addShutdownHook(thread)
                shutdownHookInstalled = true
            }
        }
    }

    private fun saveSources() {
        synchronized (sync) {
            try {
                saveSourcesZipFile(File(SOURCES_FILE))
            } catch (e: Throwable) {
                System.err.println("Snippets saving failed")
                e.printStackTrace()
            }
        }
    }

    private fun saveSourcesZipFile(f: File) {
        FileOutputStream(f).use { fout ->
            ZipOutputStream(fout).use { zout ->
                var i = 0
                for (snippet in snippets) {
                    val str = snippet.serialize()
                    IdeCodeSnippet.deserialize(str) // Verification
                    zout.putNextEntry(ZipEntry(String.format("%04d.json", i)))
                    zout.write(str.toByteArray())
                    i++
                }
            }
        }
        printNotice(snippets.size, f)
    }

    private fun printNotice(count: Int, f: File) {
        println("Test snippets ($count) written to file: $f")
    }
}

object TestRellCliEnv: RellCliEnv() {
    override fun print(msg: String, err: Boolean) {
        println(msg)
    }

    override fun exit(status: Int): Nothing {
        throw TestRellCliEnvExitException()
    }
}

class TestRellCliEnvExitException: java.lang.RuntimeException()

class RellTestEval {
    private var wrapping = false
    private var lastErrorStack = listOf<R_StackPos>()

    fun eval(code: () -> String): String {
        val oldWrapping = wrapping
        wrapping = true
        return try {
            code()
        } catch (e: EvalException) {
            e.payload
        } finally {
            wrapping = oldWrapping
        }
    }

    fun errorStack() = lastErrorStack

    fun <T> wrapCt(code: () -> T): T {
        if (wrapping) {
            val p = RellTestUtils.catchCtErr0(false, code)
            return result(p)
        } else {
            return code()
        }
    }

    fun <T> wrapRt(code: () -> T): T {
        if (wrapping) {
            val p = RellTestUtils.catchRtErr0(code)
            lastErrorStack = p.first?.stack ?: listOf()
            return result(Pair(p.first?.res, p.second))
        } else {
            return code()
        }
    }

    fun <T> wrapAll(code: () -> T): T {
        return wrapRt {
            wrapCt(code)
        }
    }

    private fun <T> result(p: Pair<String?, T?>): T {
        if (p.first != null) throw EvalException(p.first!!)
        return p.second!!
    }

    private class EvalException(val payload: String): RuntimeException()
}
