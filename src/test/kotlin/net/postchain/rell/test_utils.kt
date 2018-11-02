package net.postchain.rell

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.parser.AlternativesFailure
import com.github.h0tk3y.betterParse.parser.ErrorResult
import com.github.h0tk3y.betterParse.parser.ParseException
import net.postchain.rell.model.*
import net.postchain.rell.parser.CtError
import net.postchain.rell.parser.S_Grammar
import net.postchain.rell.parser.S_ModuleDefinition
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.*
import java.io.Closeable
import java.io.FileInputStream
import java.lang.IllegalStateException
import java.sql.ResultSet
import java.util.*
import kotlin.test.assertEquals

object RellTestUtils {
    fun processModule(code: String, processor: (RModule) -> String): String {
        val module = try {
            parseModule(code)
        } catch (e: CtError) {
            return "ct_err:" + e.code
        }
        return processor(module)
    }

    private fun catchRtErr(block: () -> String): String {
        try {
            return block()
        } catch (e: RtError) {
            return "rt_err:" + e.code
        } catch (e: RtRequireError) {
            return "req_err:" + e.message
        }
    }

    fun callQuery(globalCtx: RtGlobalContext, moduleCode: String, name: String, args: List<RtValue>, strict: Boolean = true): String {
        return processModule(moduleCode) { module ->
            callQuery(globalCtx, module, name, args, strict)
        }
    }

    fun callQuery(globalCtx: RtGlobalContext, module: RModule, name: String, args: List<RtValue>, strict: Boolean): String {
        val query = module.queries.find { it.name == name }
        if (query == null) throw IllegalStateException("Query not found: '$name'")
        val modCtx = RtModuleContext(globalCtx, module)
        return callQuery(modCtx, query, args, strict)
    }

    private fun callQuery(modCtx: RtModuleContext, query: RQuery, args: List<RtValue>, strict: Boolean): String {
        val res = catchRtErr {
            val v = query.callTopQuery(modCtx, args)
            if (v == null) "null" else if (strict) v.toStrictString() else v.toString()
        }
        return res
    }

    fun callOp(globalCtx: RtGlobalContext, moduleCode: String, name: String, args: List<RtValue>): String {
        return processModule(moduleCode) { module ->
            callOp(globalCtx, module, name, args)
        }
    }

    fun callOp(globalCtx: RtGlobalContext, module: RModule, name: String, args: List<RtValue>): String {
        val op = module.operations.find { it.name == name }
        if (op == null) throw IllegalStateException("Operation not found: '$name'")
        val modCtx = RtModuleContext(globalCtx, module)
        return catchRtErr {
            op.callTop(modCtx, args)
            ""
        }
    }

    fun parseModule(code: String): RModule {
        val ast = parse(code)
        return ast.compile()
    }

    private fun parse(code: String): S_ModuleDefinition {
        try {
            return S_Grammar.parseToEnd(code)
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
        val prop = Properties()
        FileInputStream("tests.properties").use(prop::load)
        val url = prop.getProperty("jdbcUrl")
        return DefaultSqlExecutor.connect(url!!)
    }

    fun setupDatabase(module: RModule, sqlExec: SqlExecutor) {
        sqlExec.transaction {
            dropTables(sqlExec)
            dropFunctions(sqlExec)
            val sql = gensql(module, false)
            sqlExec.execute(sql)
        }
    }

    private fun dropTables(sqlExec: SqlExecutor) {
        val tables = getExistingTables(sqlExec)
        val sql = tables.joinToString("\n") { "DROP TABLE \"$it\" CASCADE;" }
        sqlExec.execute(sql)
    }

    private fun dropFunctions(sqlExec: SqlExecutor) {
        val functions = getExistingFunctions(sqlExec)
        val sql = functions.joinToString("\n") { "DROP FUNCTION \"$it\";" }
        sqlExec.execute(sql)
    }

    fun clearTables(sqlExec: SqlExecutor) {
        val tables = getExistingTables(sqlExec)
        val sql = tables.joinToString("\n") { "TRUNCATE \"$it\" CASCADE;" }
        sqlExec.transaction {
            sqlExec.execute(sql)
        }
    }

    private fun getExistingTables(sqlExec: SqlExecutor): List<String> {
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
    private var sqlExec: SqlExecutor = NullSqlExecutor
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

    private fun init() {
        if (!inited) {
            val module = RellTestUtils.parseModule(classDefsCode())
            modelClasses = module.classes

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
            SqlTestUtils.setupDatabase(module, realSqlExec)
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

    fun chkQuery(bodyCode: String, expected: String) {
        val queryCode = "query q() $bodyCode"
        chkQueryEx(queryCode, listOf(), expected)
    }

    fun chkQueryEx(code: String, args: List<RtValue>, expected: String) {
        val actual = callQuery(code, args)
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
        return RellTestUtils.processModule(moduleCode) { "OK" }
    }

    fun callQuery(code: String, args: List<RtValue>): String {
        init()
        val moduleCode = moduleCode(code)
        val globalCtx = createGlobalCtx()
        return RellTestUtils.callQuery(globalCtx, moduleCode, "q", args, strictToString)
    }

    fun callOp(code: String, args: List<RtValue>): String {
        init()
        val moduleCode = moduleCode(code)
        val globalCtx = createGlobalCtx()
        return RellTestUtils.callOp(globalCtx, moduleCode, "o", args)
    }

    private fun createGlobalCtx() = RtGlobalContext(stdoutPrinter, logPrinter, sqlExec)

    fun chkStdout(vararg expected: String) = stdoutPrinter.chk(*expected)
    fun chkLog(vararg expected: String) = logPrinter.chk(*expected)

    private fun classDefsCode(): String = classDefs.joinToString("\n")
    private fun moduleCode(code: String): String = classDefsCode() + "\n" + code

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
