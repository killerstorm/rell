package net.postchain.rell

import net.postchain.gtv.GtvNull
import net.postchain.rell.model.R_ExternalParam
import net.postchain.rell.model.R_Module
import net.postchain.rell.model.R_Routine
import net.postchain.rell.module.RELL_VERSION
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.*
import picocli.CommandLine
import kotlin.system.exitProcess

private val SQL_MAPPER = Rt_ChainSqlMapping(0)

fun main(args: Array<String>) {
    RellCliUtils.initLogging()
    RellCliUtils.runCli(args, RellCliArgs()) {
        main0(it)
    }
}

private fun main0(args: RellCliArgs) {
    if (args.version) {
        System.out.println("Rell version $RELL_VERSION")
        exitProcess(0)
    }

    if (args.resetdb && args.dburl == null) {
        System.err.println("Database URL not specified")
        exitProcess(1)
    }

    if (args.resetdb && args.rellFile == null) {
        runWithSql(args.dburl, args.sqlLog) { sqlExec ->
            sqlExec.transaction {
                SqlUtils.dropAll(sqlExec, true)
            }
        }
        return
    }

    if (args.rellFile == null) {
        System.err.println("Rell file not specified")
        exitProcess(1)
    }

    val module = RellCliUtils.compileModule(args.rellFile!!, args.sourceDir, args.quiet)
    val routine = getRoutineCaller(args, module)

    runWithSql(args.dburl, args.sqlLog) { sqlExec ->
        val sqlCtx = Rt_SqlContext.createNoExternalChains(module, SQL_MAPPER)

        if (args.dburl != null) {
            sqlExec.transaction {
                if (args.resetdb) {
                    SqlUtils.dropAll(sqlExec, true)
                }

                val modCtx = createModuleCtx(args, sqlCtx, sqlExec, null)
                SqlInit.init(modCtx, SqlInit.LOG_NONE)
            }
        }

        routine(sqlExec, sqlCtx)
    }
}

private fun getRoutineCaller(args: RellCliArgs, module: R_Module): (SqlExecutor, Rt_SqlContext) -> Unit {
    val op = args.op
    if (op == null) return { _, _ -> }

    val entryPoint = findEntryPoint(module, op)
    val rtArgs = parseArgs(entryPoint, args.args ?: listOf())

    return { sqlExec, sqlCtx ->
        callEntryPoint(args, sqlExec, sqlCtx, entryPoint, rtArgs)
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

private fun findEntryPoint(module: R_Module, name: String): RellEntryPoint {
    val eps = mutableListOf<RellEntryPoint>()

    val op = module.operations[name]
    if (op != null) {
        val time = System.currentTimeMillis() / 1000
        eps.add(RellEntryPoint("operation", op, Rt_OpContext(time, -1, listOf())))
    }

    val q = module.queries[name]
    if (q != null) eps.add(RellEntryPoint("query", q, null))

    val f = module.functions[name]
    if (f != null) eps.add(RellEntryPoint("function", f, null))

    if (eps.isEmpty()) {
        throw RellCliErr("Found no operation, query or function with name '$name'")
    } else if (eps.size > 1) {
        throw RellCliErr("Found more than one definition with name '$name': ${eps.joinToString { it.kind }}")
    }

    val ep = eps[0]
    return ep
}

private fun callEntryPoint(
        cliArgs: RellCliArgs,
        sqlExec: SqlExecutor,
        sqlCtx: Rt_SqlContext,
        entryPoint: RellEntryPoint,
        args: List<Rt_Value>
) {
    val modCtx = createModuleCtx(cliArgs, sqlCtx, sqlExec, entryPoint.opCtx)
    entryPoint.routine.callTop(modCtx, args)
}

private fun createGlobalCtx(args: RellCliArgs, sqlExec: SqlExecutor, opCtx: Rt_OpContext?): Rt_GlobalContext {
    val chainCtx = Rt_ChainContext(GtvNull, Rt_NullValue)

    return Rt_GlobalContext(
            sqlExec = sqlExec,
            opCtx = opCtx,
            chainCtx = chainCtx,
            stdoutPrinter = Rt_StdoutPrinter,
            logPrinter = Rt_LogPrinter(),
            typeCheck = args.typeCheck
    )
}

private fun createModuleCtx(args: RellCliArgs, sqlCtx: Rt_SqlContext, sqlExec: SqlExecutor, opCtx: Rt_OpContext?): Rt_ModuleContext {
    val globalCtx = createGlobalCtx(args, sqlExec, opCtx)
    return Rt_ModuleContext(globalCtx, sqlCtx.module, sqlCtx)
}

private fun parseArgs(entryPoint: RellEntryPoint, args: List<String>): List<Rt_Value> {
    val params = entryPoint.routine.params
    if (args.size != params.size) {
        System.err.println("Wrong number of arguments: ${args.size} instead of ${params.size}")
        exitProcess(1)
    }
    return args.withIndex().map { (idx, arg) -> parseArg(params[idx], arg) }
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

private class RellEntryPoint(val kind: String, val routine: R_Routine, val opCtx: Rt_OpContext?)

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

    @CommandLine.Option(names = ["-q", "--quiet"], description = ["No useless messages"])
    var quiet = false

    @CommandLine.Option(names = ["-v", "--version"], description = ["Print version and quit"])
    var version = false

    @CommandLine.Option(names = ["--source-dir"], paramLabel =  "SOURCE_DIR",
            description =  ["Source directory used to resolve absolute include paths (default: the directory of the Rell file)"])
    var sourceDir: String? = null

    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "FILE", description = ["Rell source file"])
    var rellFile: String? = null

    @CommandLine.Parameters(index = "1", arity = "0..1", paramLabel = "OP", description = ["Operation or query name"])
    var op: String? = null

    @CommandLine.Parameters(index = "2..*", paramLabel = "ARGS", description = ["Call arguments"])
    var args: List<String>? = null
}
