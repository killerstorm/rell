package net.postchain.rell

import net.postchain.config.SimpleDatabaseConnector
import net.postchain.config.app.AppConfig
import net.postchain.gtv.GtvNull
import net.postchain.rell.model.*
import net.postchain.rell.module.GtvToRtContext
import net.postchain.rell.module.RELL_VERSION
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.*
import picocli.CommandLine
import kotlin.system.exitProcess

private val INIT = run {
    RellCliUtils.initLogging()
}

private val SQL_MAPPER = Rt_ChainSqlMapping(0)

fun main(args: Array<String>) {
    RellCliUtils.runCli(args, RellCliArgs()) {
        main0(it)
    }
}

private fun main0(args: RellCliArgs) {
    if (args.version) {
        System.out.println("Rell version $RELL_VERSION")
        exitProcess(0)
    }

    if (args.dbUrl != null && args.dbProperties != null) {
        throw RellCliErr("Both database URL and properties specified")
    }

    val dbSpecified = args.dbUrl != null || args.dbProperties != null

    if (args.resetdb && !dbSpecified) {
        throw RellCliErr("Database connection URL not specified")
    }

    if (args.resetdb && args.rellFile == null) {
        runWithSql(args) { sqlExec ->
            sqlExec.transaction {
                SqlUtils.dropAll(sqlExec, true)
            }
        }
        println("Database cleared")
        return
    }

    if (args.rellFile == null) {
        throw RellCliErr("Rell file not specified")
    }

    val module = RellCliUtils.compileModule(args.rellFile!!, args.sourceDir, args.quiet)
    val routine = getRoutineCaller(args, module)

    runWithSql(args) { sqlExec ->
        val sqlCtx = Rt_SqlContext.createNoExternalChains(module, SQL_MAPPER)

        if (dbSpecified) {
            sqlExec.transaction {
                if (args.resetdb) {
                    SqlUtils.dropAll(sqlExec, true)
                }

                val modCtx = createModuleCtx(args, sqlCtx, sqlExec, null)
                val logLevel = if (args.sqlInitLog) SqlInit.LOG_ALL else SqlInit.LOG_NONE
                SqlInit.init(modCtx, logLevel)
            }
        }

        routine(sqlExec, sqlCtx)
    }
}

private fun getRoutineCaller(args: RellCliArgs, module: R_Module): (SqlExecutor, Rt_SqlContext) -> Unit {
    val op = args.op
    if (op == null) return { _, _ -> }

    val entryPoint = findEntryPoint(module, op)

    return { sqlExec, sqlCtx ->
        val modCtx = createModuleCtx(args, sqlCtx, sqlExec, entryPoint.opContext())

        val gtvCtx = GtvToRtContext(true)
        val rtArgs = parseArgs(entryPoint, gtvCtx, args.args ?: listOf(), args.json || args.jsonArgs)
        gtvCtx.finish(modCtx)

        val rtRes = entryPoint.call(modCtx, rtArgs)
        if (rtRes != null && rtRes != Rt_UnitValue) {
            val strRes = resultToString(rtRes, args.jsonResult || args.json)
            println(strRes)
        }
    }
}

private fun runWithSql(args: RellCliArgs, code: (SqlExecutor) -> Unit) {
    val dbUrl = args.dbUrl
    val dbProperties = args.dbProperties

    if (dbUrl != null) {
        val schema = SqlUtils.extractDatabaseSchema(dbUrl)
        DefaultSqlExecutor.connect(dbUrl).use { con ->
            if (schema != null) {
                SqlUtils.prepareSchema(con, schema)
            }
            val exec = Rt_SqlExecutor(DefaultSqlExecutor(con, args.sqlLog), true)
            code(exec)
        }
    } else if (dbProperties != null) {
        val appCfg = AppConfig.fromPropertiesFile(dbProperties)
        val connector = SimpleDatabaseConnector(appCfg)
        val con = connector.openWriteConnection()
        var commit = false
        try {
            SqlUtils.prepareSchema(con, appCfg.databaseSchema)
            val exec = Rt_SqlExecutor(DefaultSqlExecutor(con, args.sqlLog), true)
            code(exec)
            commit = true
        } finally {
            connector.closeWriteConnection(con, commit)
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
        eps.add(RellEntryPoint_Operation(op, Rt_OpContext(time, -1, -1, listOf())))
    }

    val q = module.queries[name]
    if (q != null) eps.add(RellEntryPoint_Query(q))

    val f = module.functions[name]
    if (f != null) eps.add(RellEntryPoint_Function(f))

    if (eps.isEmpty()) {
        throw RellCliErr("Found no operation, query or function with name '$name'")
    } else if (eps.size > 1) {
        throw RellCliErr("Found more than one definition with name '$name': ${eps.joinToString { it.kind }}")
    }

    val ep = eps[0]
    return ep
}

private fun createGlobalCtx(args: RellCliArgs, sqlExec: SqlExecutor, opCtx: Rt_OpContext?): Rt_GlobalContext {
    val bcRid = ByteArray(32)
    val chainCtx = Rt_ChainContext(GtvNull, Rt_NullValue, bcRid)

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

private fun parseArgs(entryPoint: RellEntryPoint, gtvCtx: GtvToRtContext, args: List<String>, json: Boolean): List<Rt_Value> {
    val params = entryPoint.routine().params
    if (args.size != params.size) {
        System.err.println("Wrong number of arguments: ${args.size} instead of ${params.size}")
        exitProcess(1)
    }
    return args.withIndex().map { (idx, arg) -> parseArg(gtvCtx, params[idx], arg, json) }
}

private fun parseArg(gtvCtx: GtvToRtContext, param: R_ExternalParam, arg: String, json: Boolean): Rt_Value {
    val type = param.type

    if (json) {
        if (!type.completeFlags().gtv.fromGtv) {
            throw RellCliErr("Parameter '${param.name}' of type ${type.toStrictString()} cannot be converted from Gtv")
        }
        val gtv = PostchainUtils.jsonToGtv(arg)
        return type.gtvToRt(gtvCtx, gtv)
    }

    try {
        return type.fromCli(arg)
    } catch (e: UnsupportedOperationException) {
        throw RellCliErr("Parameter '${param.name}' has unsupported type: ${type.toStrictString()}")
    } catch (e: Exception) {
        throw RellCliErr("Invalid value for type ${type.toStrictString()}: '$arg'")
    }
}

private fun resultToString(res: Rt_Value, json: Boolean): String {
    return if (json) {
        val type = res.type()
        if (!type.completeFlags().gtv.toGtv) {
            throw RellCliErr("Result of type '${type.toStrictString()}' cannot be converted to Gtv")
        }
        val gtv = type.rtToGtv(res, true)
        PostchainUtils.gtvToJson(gtv)
    } else {
        res.toString()
    }
}

private sealed class RellEntryPoint {
    abstract val kind: String
    abstract fun routine(): R_Routine
    abstract fun opContext(): Rt_OpContext?
    abstract fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value?
}

private class RellEntryPoint_Function(private val f: R_Function): RellEntryPoint() {
    override val kind = "function"
    override fun routine() = f
    override fun opContext() = null

    override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value? {
        return f.callTopFunction(modCtx, args, true)
    }
}

private class RellEntryPoint_Operation(private val o: R_Operation, private val opCtx: Rt_OpContext): RellEntryPoint() {
    override val kind = "operation"
    override fun routine() = o
    override fun opContext() = opCtx

    override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value? {
        o.callTop(modCtx, args)
        return null
    }
}

private class RellEntryPoint_Query(private val q: R_Query): RellEntryPoint() {
    override val kind = "query"
    override fun routine() = q
    override fun opContext() = null

    override fun call(modCtx: Rt_ModuleContext, args: List<Rt_Value>): Rt_Value? {
        return q.callTopQuery(modCtx, args)
    }
}

@CommandLine.Command(name = "rell", description = ["Executes a rell program"])
private class RellCliArgs {
    @CommandLine.Option(names = ["--db-url"], paramLabel =  "DB_URL",
            description =  ["Database JDBC URL, e. g. jdbc:postgresql://localhost/relltestdb?user=relltestuser&password=1234"])
    var dbUrl: String? = null

    @CommandLine.Option(names = ["--db-properties"], paramLabel =  "DB_PROPERTIES",
            description =  ["Database connection properties file (same format as node-config.properties)"])
    var dbProperties: String? = null

    @CommandLine.Option(names = ["--resetdb"], description = ["Reset database (drop everything)"])
    var resetdb = false

    @CommandLine.Option(names = ["--sqllog"], description = ["Enable SQL logging"])
    var sqlLog = false

    @CommandLine.Option(names = ["--sqlinitlog"], description = ["Enable SQL tables structure update logging"])
    var sqlInitLog = false

    @CommandLine.Option(names = ["--typecheck"], description = ["Run-time type checking (debug)"])
    var typeCheck = false

    @CommandLine.Option(names = ["-q", "--quiet"], description = ["No useless messages"])
    var quiet = false

    @CommandLine.Option(names = ["-v", "--version"], description = ["Print version and quit"])
    var version = false

    @CommandLine.Option(names = ["--source-dir"], paramLabel =  "SOURCE_DIR",
            description =  ["Source directory used to resolve absolute include paths (default: the directory of the Rell file)"])
    var sourceDir: String? = null

    @CommandLine.Option(names = ["--json-args"], description = ["Accept Rell program arguments in JSON format"])
    var jsonArgs = false

    @CommandLine.Option(names = ["--json-result"], description = ["Print Rell program result in JSON format"])
    var jsonResult = false

    @CommandLine.Option(names = ["--json"], description = ["Equivalent to --json-args --json-result"])
    var json = false

    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "FILE", description = ["Rell source file"])
    var rellFile: String? = null

    @CommandLine.Parameters(index = "1", arity = "0..1", paramLabel = "OP", description = ["Operation or query name"])
    var op: String? = null

    @CommandLine.Parameters(index = "2..*", paramLabel = "ARGS", description = ["Call arguments"])
    var args: List<String>? = null
}
