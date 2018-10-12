package net.postchain.rell

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.parser.AlternativesFailure
import com.github.h0tk3y.betterParse.parser.ErrorResult
import com.github.h0tk3y.betterParse.parser.ParseException
import net.postchain.rell.model.RClass
import net.postchain.rell.model.RModule
import net.postchain.rell.model.RQuery
import net.postchain.rell.model.RTextType
import net.postchain.rell.parser.CtError
import net.postchain.rell.parser.S_Grammar
import net.postchain.rell.parser.S_ModuleDefinition
import net.postchain.rell.runtime.RtError
import net.postchain.rell.runtime.RtValue
import net.postchain.rell.sql.*
import java.io.FileInputStream
import java.lang.IllegalStateException
import java.sql.ResultSet
import java.util.*
import kotlin.test.assertNotNull

object TestUtils {
    fun createSqlExecutor(): DefaultSqlExecutor {
        val prop = Properties()
        FileInputStream("tests.properties").use(prop::load)
        val url = prop.getProperty("jdbcUrl")
        return DefaultSqlExecutor.connect(url!!)
    }

    fun invokeWithSql(code: String, inserts: Array<String>, args: Array<RtValue>): String {
        val res = processModule(code) { module ->
            createSqlExecutor().use { sqlExec ->
                setupDatabase(module, sqlExec)

                val insertSql = inserts.joinToString("\n") { it }
                sqlExec.execute(insertSql)

                execute(module, sqlExec, args)
            }
        }
        return res
    }

    fun invokeWithSql(ctx: SqlTestCtx, code: String, inserts: Array<String>, args: Array<RtValue>): String {
        clearTables(ctx.sqlExec)

        val res = processModule(code) { module ->
            val insertSql = inserts.joinToString("\n") { it }
            ctx.sqlExec.execute(insertSql)

            execute(module, ctx.sqlExec, args)
        }

        return res
    }

    fun invoke(code: String, args: Array<RtValue>): String {
        val res = processModule(code) { module ->
            execute(module, NullSqlExecutor, args)
        }
        return res
    }

    fun processModule(code: String, processor: (RModule) -> String): String {
        val module = try {
            parseModule(code)
        } catch (e: CtError) {
            return "ct_err:" + e.code
        }
        return processor(module)
    }

    fun parseModule(code: String): RModule {
        val ast = parse(code)
        return ast.compile()
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

    private fun clearTables(sqlExec: SqlExecutor) {
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

    fun execute(module: RModule, sqlExec: SqlExecutor, args: Array<RtValue>): String {
        val query = module.queries.find { it.name == "q" }
        assertNotNull(query, "Query not found")
        val res = try {
            query?.execute(sqlExec, args)
        } catch (e: RtError) {
            return "rt_err:" + e.code
        }
        val str = if (res == null) "null" else res.toStrictString()
        return str
    }

    fun compileQuery(code: String): RQuery {
        val ast = parse(code)
        val module = ast.compile()
        val query = module.queries.find { it.name == "q" }!!
        return query
    }

    fun invokeQuery(query: RQuery, vararg args: RtValue): String {
        val res = try {
            createSqlExecutor().use { sqlExec ->
                query.execute(sqlExec, args.map{it}.toTypedArray())
            }
        } catch (e: RtError) {
            return "rt_err:" + e.code
        }
        return res.toStrictString()
    }

    fun invokeOperation(module: RModule, name: String, sqlExec: SqlExecutor): String {
        val op = module.operations.find { it.name == name }
        if (op == null) throw IllegalStateException("Operation not found: '$name'")
        op.execute(sqlExec, arrayOf())
        return ""
    }

    private fun parse(code: String): S_ModuleDefinition {
        try {
            return S_Grammar.parseToEnd(code)
        } catch (e: ParseException) {
            println("PARSER FAILURE:")
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

    fun mkins(table: String, columns: String, values: String): String {
        val quotedColumns = columns.split(",").joinToString { "\"$it\"" }
        return "INSERT INTO \"$table\"(\"$ROWID_COLUMN\",$quotedColumns) VALUES ($values);"
    }
}

class SqlTestCtx(val classDefs: String) {
    val sqlExec = TestUtils.createSqlExecutor()
    private val modelClasses: List<RClass>

    init {
        val module = TestUtils.parseModule(classDefs)
        modelClasses = module.classes
        TestUtils.setupDatabase(module, sqlExec)
    }

    fun executeOperation(code: String): String {
        val fullCode = "$classDefs\noperation o() {\n$code\n}"

        val res = TestUtils.processModule(fullCode) { module ->
            TestUtils.invokeOperation(module, "o", sqlExec)
        }

        return res
    }

    fun dumpDatabase(): List<String> {
        val list = mutableListOf<String>()

        for (cls in modelClasses) {
            dumpTable(cls, list)
        }

        return list.toList()
    }

    fun executeSql(sql: String) {
        sqlExec.execute(sql)
    }

    private fun dumpTable(cls: RClass, list: MutableList<String>) {
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
        for ((idx, attr) in cls.attributes.values.withIndex()) {
            val value = if (attr.type == RTextType) {
                rs.getString(idx + 2)
            } else {
                "" + rs.getLong(idx + 2)
            }
            buf.append(",$value")
        }
        buf.append(")")
        return buf.toString()
    }

    fun destroy() {
        sqlExec.close()
    }
}
