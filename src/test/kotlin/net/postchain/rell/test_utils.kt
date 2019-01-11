package net.postchain.rell

import com.google.common.io.Files
import net.postchain.gtx.GTXValue
import net.postchain.rell.model.*
import net.postchain.rell.module.GtxToRtContext
import net.postchain.rell.parser.C_Error
import net.postchain.rell.parser.C_Utils
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
    val ENCODER_PLAIN = { t: R_Type, v: Rt_Value -> v.toString() }
    val ENCODER_STRICT = { t: R_Type, v: Rt_Value -> v.toStrictString() }
    val ENCODER_GTX = { t: R_Type, v: Rt_Value -> encodeGtxStr(t.rtToGtx(v, true)) }

    fun processModule(code: String, errPos: Boolean = false, gtx: Boolean = false, processor: (R_Module) -> String): String {
        val module = try {
            parseModule(code, gtx)
        } catch (e: C_Error) {
            val p = if (errPos) "" + e.pos else ""
            return "ct_err$p:" + e.code
        }
        return processor(module)
    }

    private fun catchRtErr(block: () -> String): String {
        val p = catchRtErr0(block)
        return p.first ?: p.second!!
    }

    private fun <T> catchRtErr0(block: () -> T): Pair<String?, T?> {
        try {
            val res = block()
            return Pair(null, res)
        } catch (e: Rt_Error) {
            return Pair("rt_err:" + e.code, null)
        } catch (e: Rt_RequireError) {
            return Pair("req_err:" + if (e.userMsg != null) "[${e.userMsg}]" else "null", null)
        } catch (e: Rt_GtxValueError) {
            return Pair("gtx_err:" + e.code, null)
        }
    }

    fun callFn(globalCtx: Rt_GlobalContext, module: R_Module, name: String, args: List<Rt_Value>, strict: Boolean): String {
        val fn = module.functions[name]
        if (fn == null) throw IllegalStateException("Function not found: '$name'")
        val modCtx = Rt_ModuleContext(globalCtx, module)
        val res = catchRtErr {
            val v = fn.callTopFunction(modCtx, args)
            if (strict) v.toStrictString() else v.toString()
        }
        return res
    }

    fun callQuery(globalCtx: Rt_GlobalContext, module: R_Module, name: String, args: List<Rt_Value>, encoder: (R_Type, Rt_Value) -> String): String {
        val decoder = { params: List<R_ExternalParam>, args: List<Rt_Value> -> args }
        return callQueryGeneric(globalCtx, module, name, args, decoder, encoder)
    }

    fun <T> callQueryGeneric(
            globalCtx: Rt_GlobalContext,
            module: R_Module,
            name: String,
            args: List<T>,
            decoder: (List<R_ExternalParam>, List<T>) -> List<Rt_Value>,
            encoder: (R_Type, Rt_Value) -> String
    ): String
    {
        val query = module.queries[name]
        if (query == null) throw IllegalStateException("Query not found: '$name'")

        val (rtErr, rtArgs) = catchRtErr0 { decoder(query.params, args) }
        if (rtErr != null) {
            return rtErr
        }

        val modCtx = Rt_ModuleContext(globalCtx, module)
        return callQuery0(modCtx, query, rtArgs!!, encoder)
    }

    private fun callQuery0(modCtx: Rt_ModuleContext, query: R_Query, args: List<Rt_Value>, encoder: (R_Type, Rt_Value) -> String): String {
        val res = catchRtErr {
            val v = query.callTopQuery(modCtx, args)
            encoder(query.type, v)
        }
        return res
    }

    fun callOp(globalCtx: Rt_GlobalContext, module: R_Module, name: String, args: List<Rt_Value>): String {
        val decoder = { params: List<R_ExternalParam>, args: List<Rt_Value> -> args }
        return callOpGeneric(globalCtx, module, name, args, decoder)
    }

    fun <T> callOpGeneric(
            globalCtx: Rt_GlobalContext,
            module: R_Module,
            name: String,
            args: List<T>,
            decoder: (List<R_ExternalParam>, List<T>) -> List<Rt_Value>
    ): String
    {
        val op = module.operations[name]
        if (op == null) throw IllegalStateException("Operation not found: '$name'")

        val (rtErr, rtArgs) = catchRtErr0 { decoder(op.params, args) }
        if (rtErr != null) {
            return rtErr
        }

        val modCtx = Rt_ModuleContext(globalCtx, module)
        return catchRtErr {
            op.callTop(modCtx, rtArgs!!)
            ""
        }
    }

    fun parseModule(code: String, gtx: Boolean): R_Module {
        val ast = parse(code)
        val m = ast.compile(gtx)
        TestSourcesRecorder.addSource(code)
        return m
    }

    private fun parse(code: String): S_ModuleDefinition {
        try {
            return C_Utils.parse(code)
        } catch (e: C_Error) {
            println("PARSING FAILED:")
            println(code)
            throw e
        }
    }

    fun decodeGtxStr(s: String) = Rt_GtxValue.jsonStringToGtxValue(s)
    fun encodeGtxStr(gtx: GTXValue) = Rt_GtxValue.gtxValueToJsonString(gtx)

    fun decodeGtxQueryArgs(params: List<R_ExternalParam>, args: List<String>): List<Rt_Value> {
        return decodeGtxArgs(params, args, true)
    }

    fun decodeGtxOpArgs(params: List<R_ExternalParam>, args: List<String>): List<Rt_Value> {
        return decodeGtxArgs(params, args, false)
    }

    private fun decodeGtxArgs(params: List<R_ExternalParam>, args: List<String>, human: Boolean): List<Rt_Value> {
        check(params.size == args.size)
        val ctx = GtxToRtContext()
        return args.mapIndexed { i, arg ->
            val gtx = decodeGtxStr(arg)
            params[i].type.gtxToRt(ctx, gtx, human)
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

    fun dumpDatabase(sqlExec: SqlExecutor, modelClasses: List<R_Class>): List<String> {
        val list = mutableListOf<String>()

        for (cls in modelClasses) {
            dumpTable(sqlExec, cls, list)
        }

        return list.toList()
    }

    private fun dumpTable(sqlExec: SqlExecutor, cls: R_Class, list: MutableList<String>) {
        val sql = getDumpSql(cls)
        sqlExec.executeQuery(sql, {}) { rs -> list.add(dumpRecord(cls, rs)) }
    }

    private fun getDumpSql(cls: R_Class): String {
        val buf = StringBuilder()
        buf.append("SELECT \"$ROWID_COLUMN\"")
        for (attr in cls.attributes.values) {
            buf.append(", \"${attr.name}\"")
        }
        buf.append(" FROM \"${cls.name}\" ORDER BY \"$ROWID_COLUMN\"")
        return buf.toString()
    }

    private fun dumpRecord(cls: R_Class, rs: ResultSet): String {
        val buf = StringBuilder()
        buf.append("${cls.name}(${rs.getLong(1)}")
        for ((listIndex, attr) in cls.attributes.values.withIndex()) {
            val idx = listIndex + 2
            val type = attr.type
            val value = if (type == R_TextType) {
                rs.getString(idx)
            } else if (type == R_ByteArrayType) {
                "0x" + rs.getBytes(idx).toHex()
            } else if (type == R_JSONType) {
                "" + rs.getString(idx)
            } else if (type == R_IntegerType || type is R_ClassType) {
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

object TestSourcesRecorder {
    private val ENABLED = false
    private val SOURCES_FILE: String = System.getProperty("user.home") + "/testsources.rell"

    private val sync = Any()
    private val sources = mutableSetOf<String>()
    private var shutdownHookInstalled = false

    fun addSource(code: String) {
        if (!ENABLED) return

        synchronized (sync) {
            sources.add(code.trim())
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
        for (code in sources) {
            buf.append(code + "\n")
        }
        Files.write(buf.toString(), f, Charsets.UTF_8)
        printNotice(sources.size, f)
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
        printNotice(sources.size, f)
    }

    private fun printNotice(count: Int, f: File) {
        println("Test sources ($count) written to file: $f")
    }
}

class RellSqlTester(
        useSql: Boolean = true,
        classDefs: List<String> = listOf(),
        inserts: List<String> = listOf(),
        gtx: Boolean = false
)
{
    private var inited = false
    private var destroyed = false
    private var sqlExec: SqlExecutor = NoConnSqlExecutor
    private var sqlExecResource: Closeable? = null
    private var modelClasses: List<R_Class> = listOf()
    private var lastInserts = listOf<String>()
    private val expectedData = mutableListOf<String>()
    private val stdoutPrinter = TesterRtPrinter()
    private val logPrinter = TesterRtPrinter()
    private val gtx = gtx

    var useSql: Boolean = useSql
    set(value) {
        check(!inited)
        field = value
    }

    var defs: List<String> = classDefs
    set(value) {
        check(!inited)
        field = value
    }

    var gtxResult: Boolean = gtx
        set(value) {
            check(!inited)
            field = value
        }

    var inserts: List<String> = inserts

    var strictToString = true
    var errMsgPos = false
    var opContext: Rt_OpContext? = null

    private fun init() {
        if (!inited) {
            val module = RellTestUtils.parseModule(defsCode(), gtx)
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

    private fun initSql(module: R_Module) {
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

    fun chkQueryEx(code: String, args: List<Rt_Value>, expected: String) {
        val actual = callQuery(code, args)
        assertEquals(expected, actual)
    }

    fun chkQueryGtx(code: String, expected: String) {
        val queryCode = "query q() $code"
        val actual = callQuery0(queryCode, listOf(), RellTestUtils::decodeGtxQueryArgs)
        assertEquals(expected, actual)
    }

    fun chkQueryGtxEx(code: String, args: List<String>, expected: String) {
        val actual = callQuery0(code, args, RellTestUtils::decodeGtxQueryArgs)
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

    fun chkOpGtxEx(code: String, args: List<String>, expected: String) {
        val actual = callOp0(code, args, RellTestUtils::decodeGtxOpArgs)
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

    fun callQuery(code: String, args: List<Rt_Value>): String {
        return callQuery0(code, args) { _, v -> v }
    }

    private fun <T> callQuery0(code: String, args: List<T>, decoder: (List<R_ExternalParam>, List<T>) -> List<Rt_Value>): String {
        init()
        val moduleCode = moduleCode(code)
        val globalCtx = createGlobalCtx()

        val encoder = if (gtxResult) RellTestUtils.ENCODER_GTX
        else if (strictToString) RellTestUtils.ENCODER_STRICT
        else RellTestUtils.ENCODER_PLAIN

        return processModule(moduleCode) { module ->
            RellTestUtils.callQueryGeneric(globalCtx, module, "q", args, decoder, encoder)
        }
    }

    fun callOp(code: String, args: List<Rt_Value>): String {
        return callOp0(code, args) { _, v -> v }
    }

    fun callOpGtx(code: String, args: List<String>): String {
        return callOp0(code, args, RellTestUtils::decodeGtxOpArgs)
    }

    private fun <T> callOp0(code: String, args: List<T>, decoder: (List<R_ExternalParam>, List<T>) -> List<Rt_Value>): String {
        init()
        val moduleCode = moduleCode(code)
        val globalCtx = createGlobalCtx()
        return processModule(moduleCode) { module ->
            RellTestUtils.callOpGeneric(globalCtx, module, "o", args, decoder)
        }
    }

    fun processModule(code: String, processor: (R_Module) -> String): String {
        return RellTestUtils.processModule(code, errMsgPos, gtx, processor)
    }

    private fun createGlobalCtx() = Rt_GlobalContext(stdoutPrinter, logPrinter, sqlExec, opContext)

    fun chkStdout(vararg expected: String) = stdoutPrinter.chk(*expected)
    fun chkLog(vararg expected: String) = logPrinter.chk(*expected)

    private fun defsCode(): String = defs.joinToString("\n")

    private fun moduleCode(code: String): String {
        val defsCode = defsCode()
        val modCode = (if (defsCode.isEmpty()) "" else defsCode + "\n") + code
        return modCode
    }

    private class TesterRtPrinter: Rt_Printer() {
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
