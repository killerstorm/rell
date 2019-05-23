package net.postchain.rell.test

import com.google.common.io.Files
import net.postchain.gtv.Gtv
import net.postchain.rell.CommonUtils
import net.postchain.rell.model.R_Class
import net.postchain.rell.model.R_ExternalParam
import net.postchain.rell.model.R_Module
import net.postchain.rell.module.GtvToRtContext
import net.postchain.rell.module.RELL_VERSION
import net.postchain.rell.parser.C_Message
import net.postchain.rell.runtime.Rt_ChainSqlMapping
import net.postchain.rell.runtime.Rt_GtvValue
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.sql.ROWID_COLUMN
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlUtils
import org.apache.commons.configuration2.PropertiesConfiguration
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder
import org.apache.commons.configuration2.builder.fluent.Parameters
import org.apache.commons.configuration2.io.ClasspathLocationStrategy
import org.postgresql.util.PGobject
import java.io.File
import java.io.FileOutputStream
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RellTestModule(val rModule: R_Module, val messages: List<C_Message>)

object SqlTestUtils {
    fun createSqlConnection(): Connection {
        val prop = readDbProperties()
        return DriverManager.getConnection(prop.url, prop.user, prop.password)
    }

    private fun readDbProperties(): DbConnProps {
        val localFileName = "local-config.properties"
        val localRes = javaClass.getResource("/" + localFileName)
        val configFileName = if (localRes != null) localFileName else "config.properties"
        val config = readProperties(configFileName)

        val url = config.getString("database.url")
        val user = config.getString("database.username")
        val password = config.getString("database.password")
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

    fun resetRowid(sqlExec: SqlExecutor, chainMapping: Rt_ChainSqlMapping) {
        val table = chainMapping.rowidTable
        sqlExec.execute("""UPDATE "$table" SET last_value = 0;""")
    }

    fun clearTables(sqlExec: SqlExecutor) {
        val tables = SqlUtils.getExistingTables(sqlExec)
        val sql = tables.joinToString("\n") { "TRUNCATE \"$it\" CASCADE;" }
        sqlExec.transaction {
            sqlExec.execute(sql)
        }
    }

    fun mkins(table: String, columns: String, values: String): String {
        val quotedColumns = columns.split(",").joinToString { "\"$it\"" }
        return "INSERT INTO \"$table\"(\"$ROWID_COLUMN\",$quotedColumns) VALUES ($values);"
    }

    fun dumpDatabaseClasses(sqlExec: SqlExecutor, chainMapping: Rt_ChainSqlMapping, module: R_Module): List<String> {
        val list = mutableListOf<String>()

        for (cls in module.classes.values) {
            if (cls.sqlMapping.autoCreateTable()) {
                dumpClassTable(sqlExec, chainMapping, cls, list)
            }
        }

        for (obj in module.objects.values) {
            dumpClassTable(sqlExec, chainMapping, obj.rClass, list)
        }

        return list.toList()
    }

    private fun dumpClassTable(sqlExec: SqlExecutor, chainMapping: Rt_ChainSqlMapping, cls: R_Class, list: MutableList<String>) {
        val table = cls.sqlMapping.table(chainMapping)
        val cols = listOf(cls.sqlMapping.rowidColumn()) + cls.attributes.values.map { it.sqlMapping }
        val sql = getClassDumpSql(table, cols, cls.sqlMapping.rowidColumn())
        val rows = dumpSql(sqlExec, sql).map { "${cls.name}($it)" }
        list += rows
    }

    fun dumpSql(sqlExec: SqlExecutor, sql: String): List<String> {
        val list = mutableListOf<String>()
        sqlExec.executeQuery(sql, {}) { rs -> list.add(dumpSqlRecord(rs)) }
        return list
    }

    private fun getClassDumpSql(table: String, columns: List<String>, sortColumn: String?): String {
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
            } else {
                throw IllegalStateException(value.javaClass.canonicalName)
            }
            values.add("" + str)
        }

        return values.joinToString(",")
    }
}

object GtvTestUtils {
    fun decodeGtvStr(s: String) = Rt_GtvValue.jsonStringToGtv(s)
    fun encodeGtvStr(gtv: Gtv) = Rt_GtvValue.gtvToJsonString(gtv)

    fun decodeGtvQueryArgs(params: List<R_ExternalParam>, args: List<String>): List<Rt_Value> {
        return decodeGtvArgs(params, args, true)
    }

    fun decodeGtvOpArgs(params: List<R_ExternalParam>, args: List<Gtv>): List<Rt_Value> {
        check(params.size == args.size)
        val ctx = GtvToRtContext(false)
        return args.mapIndexed { i, gtv ->
            params[i].type.gtvToRt(ctx, gtv)
        }
    }

    fun decodeGtvOpArgsStr(params: List<R_ExternalParam>, args: List<String>): List<Rt_Value> {
        return decodeGtvArgs(params, args, false)
    }

    private fun decodeGtvArgs(params: List<R_ExternalParam>, args: List<String>, pretty: Boolean): List<Rt_Value> {
        check(params.size == args.size)
        val ctx = GtvToRtContext(pretty)
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
}

object TestSourcesRecorder {
    private val ENABLED = false
    private val SOURCES_FILE: String = System.getProperty("user.home") + "/testsources-$RELL_VERSION.rell"

    private val sync = Any()
    private val sources = mutableMapOf<String, String>()
    private var shutdownHookInstalled = false

    fun addSource(code: String, result: String) {
        if (!ENABLED) return

        synchronized (sync) {
            sources[code.trim()] = result
            if (!shutdownHookInstalled) {
                val thread = Thread(TestSourcesRecorder::saveSources)
                thread.name = "SaveSources"
                thread.isDaemon = false
                Runtime.getRuntime().addShutdownHook(thread)
                shutdownHookInstalled = true
            }
        }
    }

    private fun saveSources() {
        synchronized (sync) {
            saveSourcesSingleFile(File(SOURCES_FILE))
            saveSourcesZipFile(File(SOURCES_FILE + ".zip"))
        }
    }

    private fun saveSourcesSingleFile(f: File) {
        val buf = StringBuilder()
        for ((code, result) in sources) {
            if (result == "OK") {
                buf.append(code + "\n")
            }
        }
        Files.write(buf.toString(), f, Charsets.UTF_8)
        printNotice(sources.size, f)
    }

    private fun saveSourcesZipFile(f: File) {
        FileOutputStream(f).use { fout ->
            ZipOutputStream(fout).use { zout ->
                var i = 0
                for ((code, result) in sources) {
                    val str = codeToString(code, result)
                    zout.putNextEntry(ZipEntry(String.format("%04d.rell", i)))
                    zout.write(str.toByteArray())
                    i++
                }
            }
        }
        printNotice(sources.size, f)
    }

    private fun codeToString(code: String, result: String): String {
        return "$code\n--==[RESULT]==--\n$result"
    }

    private fun printNotice(count: Int, f: File) {
        println("Test sources ($count) written to file: $f")
    }
}

class RellTestEval() {
    private var wrapping = false

    fun eval(code: () -> String): String {
        val oldWrapping = wrapping
        wrapping = true
        return try {
            code()
        } catch (e: EvalException) {
            e.message!!
        } finally {
            wrapping = oldWrapping
        }
    }

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
            return result(p)
        } else {
            return code()
        }
    }

    fun <T> wrap(code: () -> T): T {
        check(wrapping)
        return wrapRt {
            wrapCt(code)
        }
    }

    private fun <T> result(p: Pair<String?, T?>): T {
        if (p.first != null) throw EvalException(p.first!!)
        return p.second!!
    }

    private class EvalException(msg: String): RuntimeException(msg)
}
