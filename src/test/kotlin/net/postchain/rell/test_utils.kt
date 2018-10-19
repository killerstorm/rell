package net.postchain.rell

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.parser.AlternativesFailure
import com.github.h0tk3y.betterParse.parser.ErrorResult
import com.github.h0tk3y.betterParse.parser.ParseException
import net.postchain.rell.model.*
import net.postchain.rell.parser.CtError
import net.postchain.rell.parser.S_Grammar
import net.postchain.rell.parser.S_ModuleDefinition
import net.postchain.rell.runtime.RtError
import net.postchain.rell.runtime.RtJsonValue
import net.postchain.rell.runtime.RtRequireError
import net.postchain.rell.runtime.RtValue
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

    private fun executeQuery(sqlExec: SqlExecutor, query: RQuery, args: List<RtValue>): String {
        val res = try {
            query.execute(sqlExec, args)
        } catch (e: RtError) {
            return "rt_err:" + e.code
        } catch (e: RtRequireError) {
            return "req_err:" + e.message
        }
        return if (res == null) "null" else res.toStrictString()
    }

    fun callQuery(sqlExec: SqlExecutor, moduleCode: String, name: String, args: List<RtValue>): String {
        return processModule(moduleCode) { module ->
            callQuery(sqlExec, module, name, args)
        }
    }

    fun callQuery(sqlExec: SqlExecutor, module: RModule, name: String, args: List<RtValue>): String {
        val query = module.queries.find { it.name == name }
        if (query == null) throw IllegalStateException("Query not found: '$name'")
        return executeQuery(sqlExec, query, args)
    }

    fun callOp(sqlExec: SqlExecutor, moduleCode: String, name: String, args: List<RtValue>): String {
        return processModule(moduleCode) { module ->
            callOp(sqlExec, module, name, args)
        }
    }

    fun callOp(sqlExec: SqlExecutor, module: RModule, name: String, args: List<RtValue>): String {
        val op = module.operations.find { it.name == name }
        if (op == null) throw IllegalStateException("Operation not found: '$name'")
        op.execute(sqlExec, args)
        return ""
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
        dropTables(sqlExec)
        dropFunctions(sqlExec)
        val sql = gensql(module, false)
        sqlExec.execute(sql)
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
        sqlExec.execute(sql)
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
            sqlExecLoc.execute(insertSql)
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
        return RellTestUtils.callQuery(sqlExec, moduleCode, "q", args)
    }

    fun callOp(code: String, args: List<RtValue>): String {
        init()
        val moduleCode = moduleCode(code)
        return RellTestUtils.callOp(sqlExec, moduleCode, "o", args)
    }

    private fun classDefsCode(): String = classDefs.joinToString("\n")
    private fun moduleCode(code: String): String = classDefsCode() + "\n" + code
}
