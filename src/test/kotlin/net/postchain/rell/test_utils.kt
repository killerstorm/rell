package net.postchain.rell

import com.github.h0tk3y.betterParse.parser.AlternativesFailure
import com.github.h0tk3y.betterParse.parser.ErrorResult
import com.github.h0tk3y.betterParse.parser.ParseException
import com.google.common.io.Files
import net.postchain.rell.model.*
import net.postchain.rell.parser.CtError
import net.postchain.rell.parser.CtUtils
import net.postchain.rell.parser.S_ModuleDefinition
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.*
import org.apache.commons.configuration2.PropertiesConfiguration
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder
import org.apache.commons.configuration2.builder.fluent.Parameters
import org.apache.commons.configuration2.io.ClasspathLocationStrategy
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.lang.IllegalStateException
import java.sql.ResultSet
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals

object RellTestUtils {
    private val sources = mutableSetOf<String>()
    private val sourcesFile: String? = null//System.getProperty("user.home") + "/testsources.rell"

    fun processModule(code: String, errPos: Boolean = false, processor: (RModule) -> String): String {
        val module = try {
            parseModule(code)
        } catch (e: CtError) {
            val p = if (errPos) "" + e.pos else ""
            return "ct_err$p:" + e.code
        }
        return processor(module)
    }

    private fun catchRtErr(block: () -> String): String {
        try {
            return block()
        } catch (e: RtError) {
            return "rt_err:" + e.code
        } catch (e: RtRequireError) {
            return "req_err:" + if (e.userMsg != null) "[${e.userMsg}]" else "null"
        }
    }

    fun callFn(globalCtx: RtGlobalContext, module: RModule, name: String, args: List<RtValue>, strict: Boolean): String {
        val fn = module.functions[name]
        if (fn == null) throw IllegalStateException("Function not found: '$name'")
        val modCtx = RtModuleContext(globalCtx, module)
        val res = catchRtErr {
            val v = fn.callTopFunction(modCtx, args)
            if (strict) v.toStrictString() else v.toString()
        }
        return res
    }

    fun callQuery(globalCtx: RtGlobalContext, module: RModule, name: String, args: List<RtValue>, strict: Boolean): String {
        val query = module.queries[name]
        if (query == null) throw IllegalStateException("Query not found: '$name'")
        val modCtx = RtModuleContext(globalCtx, module)
        return callQuery(modCtx, query, args, strict)
    }

    private fun callQuery(modCtx: RtModuleContext, query: RQuery, args: List<RtValue>, strict: Boolean): String {
        val res = catchRtErr {
            val v = query.callTopQuery(modCtx, args)
            if (strict) v.toStrictString() else v.toString()
        }
        return res
    }

    fun callOp(globalCtx: RtGlobalContext, module: RModule, name: String, args: List<RtValue>): String {
        val op = module.operations[name]
        if (op == null) throw IllegalStateException("Operation not found: '$name'")
        val modCtx = RtModuleContext(globalCtx, module)
        return catchRtErr {
            op.callTop(modCtx, args)
            ""
        }
    }

    fun parseModule(code: String): RModule {
        val ast = parse(code)
        val m = ast.compile()
        if (sourcesFile != null) {
            sources.add(code)
        }
        return m
    }

    fun saveSources() {
        if (sourcesFile == null) return
        saveSourcesSingleFile(File(sourcesFile))
        saveSourcesZipFile(File(sourcesFile + ".zip"))
    }

    private fun saveSourcesSingleFile(f: File) {
        val buf = StringBuilder()
        for (code in sources) {
            buf.append(code + "\n")
        }
        Files.write(buf.toString(), f, Charsets.UTF_8)
    }

    private fun saveSourcesZipFile(f: File) {
        FileOutputStream(f).use { fout ->
            ZipOutputStream(fout).use { zout ->
                var i = 0
                for (code in sources) {
                    zout.putNextEntry(ZipEntry(String.format("%04d.rell", i)))
                    zout.write(code.toByteArray())
                    i++
                }
            }
        }
    }

    private fun parse(code: String): S_ModuleDefinition {
        try {
            return CtUtils.parse(code)
        } catch (e: ParseException) {
            println("PARSER FAILURE:")
            println(code)
            printError(e.errorResult, "")
            throw Exception("Parse failed")
        }
    }

    private fun printError(err: ErrorResult, indent: String) {
        if (err is AlternativesFailure) {
            println(indent + "Alternatives:")
            for (x in err.errors) {
                printError(x, indent + "    ")
            }
        } else {
            println(indent + err)
        }
    }
}

object SqlTestUtils {
    fun createSqlExecutor(): DefaultSqlExecutor {
        val prop = readDbProperties()
        return DefaultSqlExecutor.connect(prop.url, prop.user, prop.password)
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

    fun dumpDatabase(sqlExec: SqlExecutor, modelClasses: List<RClass>): List<String> {
        val list = mutableListOf<String>()

        for (cls in modelClasses) {
            dumpTable(sqlExec, cls, list)
        }

        return list.toList()
    }

    private fun dumpTable(sqlExec: SqlExecutor, cls: RClass, list: MutableList<String>) {
        val sql = getDumpSql(cls)
        sqlExec.executeQuery(sql, {}) { rs -> list.add(dumpRecord(cls, rs)) }
    }

    private fun getDumpSql(cls: RClass): String {
        val buf = StringBuilder()
        buf.append("SELECT \"$ROWID_COLUMN\"")
        for (attr in cls.attributes.values) {
            buf.append(", \"${attr.name}\"")
        }
        buf.append(" FROM \"${cls.name}\" ORDER BY \"$ROWID_COLUMN\"")
        return buf.toString()
    }

    private fun dumpRecord(cls: RClass, rs: ResultSet): String {
        val buf = StringBuilder()
        buf.append("${cls.name}(${rs.getLong(1)}")
        for ((listIndex, attr) in cls.attributes.values.withIndex()) {
            val idx = listIndex + 2
            val type = attr.type
            val value = if (type == RTextType) {
                rs.getString(idx)
            } else if (type == RByteArrayType) {
                "0x" + rs.getBytes(idx).toHex()
            } else if (type == RJSONType) {
                "" + rs.getString(idx)
            } else if (type == RIntegerType || type is RInstanceRefType) {
                "" + rs.getLong(idx)
            } else {
                throw IllegalStateException(type.toStrictString())
            }
            buf.append(",$value")
        }
        buf.append(")")
        return buf.toString()
    }
}

class RellSqlTester(
        useSql: Boolean = true,
        classDefs: List<String> = listOf(),
        inserts: List<String> = listOf()
)
{
    private var inited = false
    private var destroyed = false
    private var sqlExec: SqlExecutor = NoConnSqlExecutor
    private var sqlExecResource: Closeable? = null
    private var modelClasses: List<RClass> = listOf()
    private var lastInserts = listOf<String>()
    private val expectedData = mutableListOf<String>()
    private val stdoutPrinter = TesterRtPrinter()
    private val logPrinter = TesterRtPrinter()

    var useSql: Boolean = useSql
    set(value) {
        check(!inited)
        field = value
    }

    var classDefs: List<String> = classDefs
    set(value) {
        check(!inited)
        field = value
    }

    var inserts: List<String> = inserts

    var strictToString = true
    var errMsgPos = false
    var signers = listOf<ByteArray>()

    private fun init() {
        if (!inited) {
            val module = RellTestUtils.parseModule(classDefsCode())
            modelClasses = module.classes.values.toList()

            if (useSql) {
                initSql(module)
            }

            inited = true
        } else if (inserts != lastInserts) {
            if (!lastInserts.isEmpty()) {
                SqlTestUtils.clearTables(sqlExec)
            }
            initSqlInserts(sqlExec)
        }
    }

    private fun initSql(module: RModule) {
        val realSqlExec = SqlTestUtils.createSqlExecutor()
        var closeable: Closeable? = realSqlExec

        try {
            SqlUtils.resetDatabase(module, realSqlExec)
            initSqlInserts(realSqlExec)
            sqlExec = realSqlExec
            sqlExecResource = realSqlExec
            closeable = null
        } finally {
            closeable?.close()
        }
    }

    private fun initSqlInserts(sqlExecLoc: SqlExecutor) {
        if (!inserts.isEmpty()) {
            val insertSql = inserts.joinToString("\n") { it }
            sqlExecLoc.transaction {
                sqlExecLoc.execute(insertSql)
            }
            lastInserts = inserts
        }
    }

    fun destroy() {
        if (!inited || destroyed) return
        destroyed = true
        sqlExecResource?.close()
    }

    fun dumpDatabase(): List<String> {
        init()
        return SqlTestUtils.dumpDatabase(sqlExec, modelClasses)
    }

    fun chkCompile(code: String, expected: String) {
        val actual = compileModule(code)
        assertEquals(expected, actual)
    }

    fun chkQuery(bodyCode: String, expected: String) {
        val queryCode = "query q() $bodyCode"
        chkQueryEx(queryCode, listOf(), expected)
    }

    fun chkQueryEx(code: String, args: List<RtValue>, expected: String) {
        val actual = callQuery(code, args)
        assertEquals(expected, actual)
    }

    fun chkQueryType(bodyCode: String, expected: String) {
        val queryCode = "query q() $bodyCode"
        val moduleCode = moduleCode(queryCode)
        val actual = processModule(moduleCode) { module ->
            module.queries.getValue("q").type.toStrictString()
        }
        assertEquals(expected, actual)
    }

    fun chkOp(bodyCode: String, expected: String) {
        val opCode = "operation o() { $bodyCode }"
        chkOpEx(opCode, expected)
    }

    fun chkOpEx(opCode: String, expected: String) {
        val actual = callOp(opCode, listOf())
        assertEquals(expected, actual)
    }

    fun execOp(code: String) {
        chkOp(code, "")
    }

    fun chkFnEx(code: String, expected: String) {
        val actual = callFn(code)
        assertEquals(expected, actual)
    }

    fun chkData(expected: List<String>) {
        expectedData.clear()
        chkDataNew(expected)
    }

    fun chkData(vararg expected: String) {
        chkData(expected.toList())
    }

    fun chkDataNew(expected: List<String>) {
        expectedData.addAll(expected)
        val actual = dumpDatabase()
        assertEquals(expectedData, actual)
    }

    fun chkDataNew(vararg expected: String) {
        chkDataNew(expected.toList())
    }

    fun compileModule(code: String): String {
        val moduleCode = moduleCode(code)
        return processModule(moduleCode) { "OK" }
    }

    private fun callFn(code: String): String {
        init()
        val moduleCode = moduleCode(code)
        val globalCtx = createGlobalCtx()
        return processModule(moduleCode) { module ->
            RellTestUtils.callFn(globalCtx, module, "f", listOf(), strictToString)
        }
    }

    fun callQuery(code: String, args: List<RtValue>): String {
        init()
        val moduleCode = moduleCode(code)
        val globalCtx = createGlobalCtx()
        return processModule(moduleCode) { module ->
            RellTestUtils.callQuery(globalCtx, module, "q", args, strictToString)
        }
    }

    fun callOp(code: String, args: List<RtValue>): String {
        init()
        val moduleCode = moduleCode(code)
        val globalCtx = createGlobalCtx()
        return processModule(moduleCode) { module ->
            RellTestUtils.callOp(globalCtx, module, "o", args)
        }
    }

    private fun processModule(code: String, processor: (RModule) -> String): String {
        return RellTestUtils.processModule(code, errMsgPos, processor)
    }

    private fun createGlobalCtx() = RtGlobalContext(stdoutPrinter, logPrinter, sqlExec, signers)

    fun chkStdout(vararg expected: String) = stdoutPrinter.chk(*expected)
    fun chkLog(vararg expected: String) = logPrinter.chk(*expected)

    private fun classDefsCode(): String = classDefs.joinToString("\n")
    private fun moduleCode(code: String): String {
        val clsCode = classDefsCode()
        val modCode = (if (clsCode.isEmpty()) "" else clsCode + "\n") + code
        return modCode
    }

    private class TesterRtPrinter: RtPrinter() {
        private val queue = LinkedList<String>()

        override fun print(str: String) {
            queue.add(str)
        }

        fun chk(vararg expected: String) {
            val expectedList = expected.toList()
            val actualList = queue.toList()
            assertEquals(expectedList, actualList)
            queue.clear()
        }
    }
}
