package net.postchain.rell

import net.postchain.gtx.GTXNull
import net.postchain.rell.model.*
import net.postchain.rell.parser.C_Error
import net.postchain.rell.parser.C_Utils
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.DefaultSqlExecutor
import net.postchain.rell.sql.NoConnSqlExecutor
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlUtils
import org.apache.commons.logging.LogFactory
import picocli.CommandLine
import java.io.File
import java.lang.UnsupportedOperationException
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val argsEx = parseArgs(args)

    if (argsEx.resetdb && argsEx.dburl == null) {
        System.err.println("Database URL not specified")
        exitProcess(1)
    }

    val module = compileModule(argsEx.rellFile)
    val routine = getRoutineCaller(argsEx, module)

    runWithSql(argsEx.dburl) { sqlExec ->
        if (argsEx.resetdb) {
            SqlUtils.resetDatabase(sqlExec, module, false)
            println("Database reset done")
        }
        routine(sqlExec)
    }
}

private fun getRoutineCaller(args: Args, module: R_Module): (SqlExecutor) -> Unit {
    val op = args.op
    if (op == null) return {}

    val (routine, opCtx) = findRoutine(module, op)
    val rtArgs = parseArgs(routine, args.args ?: listOf())

    return { sqlExec ->
        callRoutine(sqlExec, module, routine, opCtx, rtArgs)
    }
}

private fun runWithSql(dbUrl: String?, code: (SqlExecutor) -> Unit) {
    if (dbUrl != null) {
        DefaultSqlExecutor.connect(dbUrl).use(code)
    } else {
        code(NoConnSqlExecutor)
    }
}

private fun parseArgs(args: Array<String>): Args {
    val argsObj = Args()
    val cl = CommandLine(argsObj)
    try {
        cl.parse(*args)
    } catch (e: CommandLine.PicocliException) {
        cl.usageHelpWidth = 1000
        cl.usage(System.err)
        exitProcess(1)
    }
    return argsObj
}

@CommandLine.Command(name = "rell", description = ["Executes a rell program"])
private class Args {
    @CommandLine.Option(names = ["--dburl"], paramLabel =  "URL",
            description =  ["Database JDBC URL, e. g. jdbc:postgresql://localhost/relltestdb?user=relltestuser&password=1234"])
    var dburl: String? = null

    @CommandLine.Option(names = ["--resetdb"], description = ["Reset database (drop all and create tables from scratch)"])
    var resetdb = false

    @CommandLine.Parameters(index = "0", paramLabel = "FILE", description = ["Rell toExpr file"])
    var rellFile: String = ""

    @CommandLine.Parameters(index = "1", arity = "0..1", paramLabel = "OP", description = ["Operation or query name"])
    var op: String? = null

    @CommandLine.Parameters(index = "2..*", paramLabel = "ARGS", description = ["Call arguments"])
    var args: List<String>? = null
}

private fun compileModule(rellFile: String): R_Module {
    val sourceCode = File(rellFile).readText()

    val module = try {
        val ast = C_Utils.parse(sourceCode)
        ast.compile(true)
    } catch (e: C_Error) {
        System.err.println("ERROR ${e.pos} ${e.errMsg}")
        exitProcess(1)
    }
    return module
}

private fun findRoutine(module: R_Module, name: String): Pair<R_Routine, Rt_OpContext?> {
    val oper = module.operations[name]
    val query = module.queries[name]
    if (oper != null && query != null) {
        System.err.println("Found both operation and query with name '$name'")
        exitProcess(1)
    } else if (oper != null) {
        val time = System.currentTimeMillis() / 1000
        return Pair(oper, Rt_OpContext(time, -1, listOf()))
    } else if (query != null) {
        return Pair(query, null)
    } else {
        System.err.println("Found no operation or query with name '$name'")
        exitProcess(1)
    }
}

private fun callRoutine(sqlExec: SqlExecutor, module: R_Module, op: R_Routine, opCtx: Rt_OpContext?, args: List<Rt_Value>) {
    val chainCtx = Rt_ChainContext(GTXNull, Rt_NullValue)
    val globalCtx = Rt_GlobalContext(StdoutRtPrinter, LogRtPrinter, sqlExec, opCtx, chainCtx)
    val modCtx = Rt_ModuleContext(globalCtx, module)
    op.callTop(modCtx, args)
}

private fun parseArgs(routine: R_Routine, args: List<String>): List<Rt_Value> {
    if (args.size != routine.params.size) {
        System.err.println("Wrong number of arguments: ${args.size} instead of ${routine.params.size}")
        exitProcess(1)
    }
    return args.withIndex().map { (idx, arg) -> parseArg(routine.params[idx], arg) }
}

private fun parseArg(param: R_ExternalParam, arg: String): Rt_Value {
    val type = param.type
    try {
        return type.fromCli(arg)
    } catch (e: UnsupportedOperationException) {
        System.err.println("Parameter '${param.name}' has unsupported type: ${type.toStrictString()}")
        exitProcess(1)
    } catch (e: Exception) {
        System.err.println("Invalid value for type ${type.toStrictString()}: '$arg'")
        exitProcess(1)
    }
}

private object StdoutRtPrinter: Rt_Printer() {
    override fun print(str: String) {
        println(str)
    }
}

private object LogRtPrinter: Rt_Printer() {
    private val log = LogFactory.getLog(LogRtPrinter.javaClass)

    override fun print(str: String) {
        log.info(str)
    }
}
