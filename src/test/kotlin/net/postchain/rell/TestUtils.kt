package net.postchain.rell

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.parser.AlternativesFailure
import com.github.h0tk3y.betterParse.parser.ErrorResult
import com.github.h0tk3y.betterParse.parser.ParseException
import net.postchain.rell.model.RModule
import net.postchain.rell.model.RQuery
import net.postchain.rell.model.makeModule
import net.postchain.rell.parser.CtError
import net.postchain.rell.parser.S_Grammar
import net.postchain.rell.parser.S_ModuleDefinition
import net.postchain.rell.runtime.RtError
import net.postchain.rell.runtime.RtValue
import net.postchain.rell.sql.ROWID_COLUMN
import net.postchain.rell.sql.SqlConnector
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.genclass
import kotlin.test.assertNotNull

object TestUtils {
    fun invoke(code: String, inserts: Array<String>, args: Array<RtValue>): String {
        val ast = parse(code)

        val module = try {
            makeModule(ast)
        } catch (e: CtError) {
            e.printStackTrace()
            return "ct_err:" + e.code
        }

        val result = SqlConnector.connect { sqlExec ->
            for (classDef in module.classes) {
                val sql = genclass(classDef)
                sqlExec.execute(sql)
            }

            for (insert in inserts) {
                sqlExec.execute(insert)
            }

            execute(module, sqlExec, args)
        }

        return result
    }

    private fun execute(module: RModule, sqlExec: SqlExecutor, args: Array<RtValue>): String {
        val query = module.queries.find { it.name == "q" }
        assertNotNull(query, "Query not found")
        val res = try {
            query?.execute(sqlExec, args)
        } catch (e: RtError) {
            e.printStackTrace()
            return "rt_err:" + e.code
        }
        val str = if (res == null) "null" else res.toStrictString()
        return str
    }

    fun compileQuery(code: String): RQuery {
        val ast = parse(code)
        val module = makeModule(ast)
        val query = module.queries.find { it.name == "q" }!!
        return query
    }

    fun invokeQuery(query: RQuery, vararg args: RtValue): String {
        val res = try {
            SqlConnector.connect { sqlExec ->
                query.execute(sqlExec, args.map{it}.toTypedArray())
            }
        } catch (e: RtError) {
            e.printStackTrace()
            return "rt_err:" + e.code
        }
        return res.toStrictString()
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
