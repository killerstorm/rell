package net.postchain.rell

import net.postchain.gtv.GtvNull
import net.postchain.rell.model.R_ExternalParam
import net.postchain.rell.model.R_Module
import net.postchain.rell.model.R_Routine
import net.postchain.rell.module.RELL_VERSION
import net.postchain.rell.parser.*
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.*
import picocli.CommandLine
import java.io.File
import kotlin.system.exitProcess

private val SQL_MAPPER = Rt_ChainSqlMapping(0)

fun main(args: Array<String>) {
    val argsEx = parseCliArgs(args)

    if (argsEx.version) {
        System.out.println("Rell version $RELL_VERSION")
        exitProcess(0)
    }

    if (argsEx.resetdb && argsEx.dburl == null) {
        System.err.println("Database URL not specified")
        exitProcess(1)
    }

    if (argsEx.resetdb && argsEx.rellFile == null) {
        runWithSql(argsEx.dburl, argsEx.sqlLog) { sqlExec ->
            sqlExec.transaction {
                SqlUtils.dropAll(sqlExec, true)
            }
        }
        return
    }

    if (argsEx.rellFile == null) {
        System.err.println("Rell file not specified")
        exitProcess(1)
    }

    val module = RellCliUtils.compileModule(argsEx.rellFile!!)
    val routine = getRoutineCaller(argsEx, module)

    runWithSql(argsEx.dburl, argsEx.sqlLog) { sqlExec ->
        val sqlCtx = Rt_SqlContext.createNoExternalChains(module, SQL_MAPPER)

        if (argsEx.dburl != null) {
            sqlExec.transaction {
                if (argsEx.resetdb) {
                    SqlUtils.dropAll(sqlExec, true)
                }

                val modCtx = createModuleCtx(argsEx, sqlCtx, sqlExec, null)
                SqlInit.init(modCtx, false)
            }
        }

        routine(sqlExec, sqlCtx)
    }
}

private fun getRoutineCaller(args: RellCliArgs, module: R_Module): (SqlExecutor, Rt_SqlContext) -> Unit {
    val op = args.op
    if (op == null) return { _, _ -> }

    val (routine, opCtx) = findRoutine(module, op)
    val rtArgs = parseArgs(routine, args.args ?: listOf())

    return { sqlExec, sqlCtx ->
        callRoutine(args, sqlExec, sqlCtx, routine, opCtx, rtArgs)
    }
}

private fun runWithSql(dbUrl: String?, logging: Boolean, code: (SqlExecutor) -> Unit) {
    if (dbUrl != null) {
        DefaultSqlExecutor.connect(dbUrl).use { con ->
            val exec = Rt_SqlExecutor(DefaultSqlExecutor(con, logging), true)
            code(exec)
        }
    } else {
        code(NoConnSqlExecutor)
    }
}

private fun parseCliArgs(args: Array<String>): RellCliArgs {
    val argsObj = RellCliArgs()
    val cl = CommandLine(argsObj)
    try {
        if (args.size == 0) throw CommandLine.PicocliException("no args")
        cl.parse(*args)
    } catch (e: CommandLine.PicocliException) {
        cl.usageHelpWidth = 1000
        cl.usage(System.err)
        exitProcess(1)
    }
    return argsObj
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

private fun callRoutine(
        cliArgs: RellCliArgs,
        sqlExec: SqlExecutor,
        sqlCtx: Rt_SqlContext,
        op: R_Routine,
        opCtx: Rt_OpContext?,
        args: List<Rt_Value>
) {
    val modCtx = createModuleCtx(cliArgs, sqlCtx, sqlExec, opCtx)
    op.callTop(modCtx, args)
}

private fun createGlobalCtx(args: RellCliArgs, sqlExec: SqlExecutor, opCtx: Rt_OpContext?): Rt_GlobalContext {
    val chainCtx = Rt_ChainContext(GtvNull, Rt_NullValue)

    return Rt_GlobalContext(
            sqlExec = sqlExec,
            opCtx = opCtx,
            chainCtx = chainCtx,
            stdoutPrinter = Rt_StdoutPrinter,
            logPrinter = Rt_LogPrinter,
            typeCheck = args.typeCheck
    )
}

private fun createModuleCtx(args: RellCliArgs, sqlCtx: Rt_SqlContext, sqlExec: SqlExecutor, opCtx: Rt_OpContext?): Rt_ModuleContext {
    val globalCtx = createGlobalCtx(args, sqlExec, opCtx)
    return Rt_ModuleContext(globalCtx, sqlCtx.module, sqlCtx)
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

object RellCliUtils {
    fun compileModule(rellPath: String): R_Module {
        val sourceFile = File(rellPath)
        val res = compile(sourceFile)

        val warnCnt = res.messages.filter { it.type == C_MessageType.WARNING }.size
        val errCnt = res.messages.filter { it.type == C_MessageType.ERROR }.size

        for (message in res.messages) {
            System.err.println(message)
        }

        if (warnCnt > 0 || errCnt > 0) {
            System.err.println("Errors: $errCnt Warnings: $warnCnt")
        }

        val module = res.module
        if (module == null) {
            if (errCnt == 0) System.err.println(errMsg("compilation failed"))
            exitProcess(1)
        } else if (errCnt > 0) {
            exitProcess(1)
        }

        return module
    }

    private fun compile(file: File): C_CompilationResult {
        try {
            val sourcePath = C_SourcePath.parse(file.name)
            val sourceDir = C_DiskSourceDir(file.absoluteFile.parentFile)
            val res = C_Compiler.compile(sourceDir, sourcePath)
            return res
        } catch (e: C_CommonError) {
            System.err.println(errMsg(e.msg))
            exitProcess(1)
        }
    }

    private fun errMsg(msg: String) = "${C_MessageType.ERROR.text}: $msg"
}

@CommandLine.Command(name = "rell", description = ["Executes a rell program"])
private class RellCliArgs {
    @CommandLine.Option(names = ["--dburl"], paramLabel =  "URL",
            description =  ["Database JDBC URL, e. g. jdbc:postgresql://localhost/relltestdb?user=relltestuser&password=1234"])
    var dburl: String? = null

    @CommandLine.Option(names = ["--resetdb"], description = ["Reset database (drop everything)"])
    var resetdb = false

    @CommandLine.Option(names = ["--sqllog"], description = ["Enable SQL logging"])
    var sqlLog = false

    @CommandLine.Option(names = ["--typecheck"], description = ["Run-time type checking (debug)"])
    var typeCheck = false

    @CommandLine.Option(names = ["-v", "--version"], description = ["Print version and quit"])
    var version = false

    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "FILE", description = ["Rell source file"])
    var rellFile: String? = null

    @CommandLine.Parameters(index = "1", arity = "0..1", paramLabel = "OP", description = ["Operation or query name"])
    var op: String? = null

    @CommandLine.Parameters(index = "2..*", paramLabel = "ARGS", description = ["Call arguments"])
    var args: List<String>? = null
}
