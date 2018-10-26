package net.postchain.rell

import com.github.h0tk3y.betterParse.grammar.parseToEnd
import net.postchain.rell.model.*
import net.postchain.rell.parser.S_Grammar
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.DefaultSqlExecutor
import org.apache.commons.logging.LogFactory
import java.io.File
import java.lang.NumberFormatException
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("Usage: JDBC_URL RELL_FILE OPERATION_NAME ARG*")
        System.err.println("    JDBC_URL         E.g. jdbc:postgresql://localhost/relltestdb?user=relltestuser&password=1234")
        System.err.println("    RELL_FILE        Rell source file")
        System.err.println("    OPERATION_NAME   Operation or query name")
        System.err.println("    ARG              Arguments")
        exitProcess(1)
    }

    val jdbcUrl = args[0]
    val rellFile = args[1]
    val opName = args[2]
    val opArgs = args.slice(3 .. args.size - 1).toList()

    runRell(jdbcUrl, rellFile, opName, opArgs)
}

private fun runRell(jdbcUrl: String, rellFile: String, opName: String, opArgs: List<String>) {
    val sourceCode = File(rellFile).readText()

    val ast = S_Grammar.parseToEnd(sourceCode)
    val module = ast.compile()

    val oper = module.operations.find { it.name == opName }
    val query = module.queries.find { it.name == opName }
    if (oper != null && query != null) {
        System.err.println("Found both operation and query with name '$opName'")
        exitProcess(1)
    } else if (oper != null) {
        runOperation(jdbcUrl, opArgs, module, oper)
    } else if (query != null) {
        runOperation(jdbcUrl, opArgs, module, query)
    } else {
        System.err.println("Found no operation or query with name '$opName'")
        exitProcess(1)
    }
}

private fun runOperation(jdbcUrl: String, opArgs: List<String>, module: RModule, op: RRoutine) {
    val args = parseArgs(op.params, opArgs)
    DefaultSqlExecutor.connect(jdbcUrl).use { sqlExec ->
        val globalCtx = RtGlobalContext(StdoutRtPrinter, LogRtPrinter, sqlExec)
        val modCtx = RtModuleContext(globalCtx, module)
        op.callTop(modCtx, args)
    }
}

private fun parseArgs(params: List<RExternalParam>, args: List<String>): List<RtValue> {
    if (params.size != args.size) {
        System.err.println("Wrong number of arguments: ${args.size} instead of ${params.size}")
        exitProcess(1)
    }
    return args.withIndex().map { (idx, arg) -> parseArg(params[idx], arg) }
}

private fun parseArg(param: RExternalParam, arg: String): RtValue {
    val type = param.type
    if (type == RIntegerType) {
        val v = try { arg.toLong() } catch (e: NumberFormatException) {
            System.err.println("Bad value for parameter '${param.name}': '$arg'")
            exitProcess(1)
        }
        return RtIntValue(v)
    } else if (type == RTextType) {
        return RtTextValue(arg)
    } else {
        System.err.println("Parameter '${param.name}' has unsupported type: ${type.toStrictString()}")
        exitProcess(1)
    }
}

private object StdoutRtPrinter: RtPrinter() {
    override fun print(str: String) {
        println(str)
    }
}

private object LogRtPrinter: RtPrinter() {
    private val log = LogFactory.getLog(LogRtPrinter.javaClass)

    override fun print(str: String) {
        log.info(str)
    }
}
