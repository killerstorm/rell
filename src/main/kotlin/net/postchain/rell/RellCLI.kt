/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell

import net.postchain.StorageBuilder
import net.postchain.base.BlockchainRid
import net.postchain.config.app.AppConfig
import net.postchain.gtv.GtvNull
import net.postchain.rell.model.*
import net.postchain.rell.module.GtvToRtContext
import net.postchain.rell.repl.ReplShell
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.*
import net.postchain.rell.utils.*
import picocli.CommandLine
import java.sql.DriverManager
import kotlin.system.exitProcess

@Suppress("unused")
private val INIT = run {
    RellCliLogUtils.initLogging()
}

private val SQL_MAPPER = Rt_ChainSqlMapping(0)

fun main(args: Array<String>) {
    RellCliUtils.runCli(args, RellCliArgs()) {
        main0(it)
    }
}

private fun main0(args: RellCliArgs) {
    if (args.version) {
        val ver = Rt_RellVersion.getInstance()?.buildDescriptor ?: "Version unknown"
        println(ver)
        exitProcess(0)
    }

    if (args.dbUrl != null && args.dbProperties != null) {
        throw RellCliErr("Both database URL and properties specified")
    }

    val dbSpecified = args.dbUrl != null || args.dbProperties != null

    if (args.resetdb && !dbSpecified) {
        throw RellCliErr("Database connection URL not specified")
    }

    if (args.resetdb && args.module == null && args.batch) {
        resetDatabase(args)
        return
    }

    val (entryModule, entryRoutine) = parseEntryPoint(args)

    if (args.batch || (entryModule != null && entryRoutine != null)) {
        runApp(args, dbSpecified, entryModule, entryRoutine)
    } else {
        runRepl(args, entryModule, dbSpecified)
    }
}

private fun runApp(args: RellCliArgs, dbSpecified: Boolean, entryModule: R_ModuleName?, entryRoutine: R_QualifiedName?) {
    val app = RellCliUtils.compileApp(args.sourceDir, entryModule, args.quiet)
    val launcher = getAppLauncher(app, args, entryModule, entryRoutine)

    runWithSqlManager(args, true) { sqlMgr ->
        val sqlCtx = Rt_SqlContext.createNoExternalChains(app, SQL_MAPPER)
        if (dbSpecified) {
            initDatabase(args, app, sqlMgr, sqlCtx)
        }
        launcher?.launch(sqlMgr, sqlCtx)
    }
}

private fun runRepl(args: RellCliArgs, moduleName: R_ModuleName?, useSql: Boolean) {
    runWithSqlManager(args, false) { sqlMgr ->
        if (args.resetdb) {
            sqlMgr.transaction { sqlExec ->
                SqlUtils.dropAll(sqlExec, true)
            }
        }

        val globalCtx = createGlobalCtx(args, null)
        val sourceDir = RellCliUtils.createSourceDir(args.sourceDir)
        ReplShell.start(sourceDir, moduleName, globalCtx, sqlMgr, useSql)
    }
}

private fun resetDatabase(args: RellCliArgs) {
    runWithSqlManager(args, true) { sqlMgr ->
        sqlMgr.transaction { sqlExec ->
            SqlUtils.dropAll(sqlExec, true)
        }
    }
    println("Database cleared")
}

private fun initDatabase(args: RellCliArgs, app: R_App, sqlMgr: SqlManager, sqlCtx: Rt_SqlContext) {
    sqlMgr.transaction { sqlExec ->
        if (args.resetdb) {
            SqlUtils.dropAll(sqlExec, true)
        }

        val appCtx = createAppContext(args, app, sqlCtx, null)
        val exeCtx = Rt_ExecutionContext(appCtx, sqlExec)

        val initLogging = SqlInitLogging.ofLevel(if (args.sqlInitLog) SqlInitLogging.LOG_ALL else SqlInitLogging.LOG_NONE)
        SqlInit.init(exeCtx, true, initLogging)
    }
}

private fun parseEntryPoint(args: RellCliArgs): Pair<R_ModuleName?, R_QualifiedName?> {
    val m = args.module
    val e = args.entry

    if (m == null) {
        return Pair(null, null)
    }

    val moduleName = R_ModuleName.ofOpt(m)
    if (moduleName == null) throw RellCliErr("Invalid module name: '$m'")

    var routineName: R_QualifiedName? = null
    if (e != null) {
        routineName = R_QualifiedName.ofOpt(e)
        if (routineName == null || routineName.isEmpty()) throw RellCliErr("Invalid entry point name: '$e'")
    }

    return Pair(moduleName, routineName)
}

private fun getAppLauncher(
        app: R_App,
        args: RellCliArgs,
        entryModule: R_ModuleName?,
        entryRoutine: R_QualifiedName?
): RellAppLauncher? {
    if (entryModule == null || entryRoutine == null) return null
    val entryPoint = findEntryPoint(app, entryModule, entryRoutine)
    return RellAppLauncher(app, args, entryPoint)
}

private fun runWithSqlManager(args: RellCliArgs, logSqlErrors: Boolean, code: (SqlManager) -> Unit) {
    val dbUrl = args.dbUrl
    val dbProperties = args.dbProperties

    if (dbUrl != null) {
        val schema = SqlUtils.extractDatabaseSchema(dbUrl)
        DriverManager.getConnection(dbUrl).use { con ->
            con.autoCommit = true
            val sqlMgr = ConnectionSqlManager(con, args.sqlLog)
            runWithSqlManager(schema, sqlMgr, logSqlErrors, code)
        }
    } else if (dbProperties != null) {
        val appCfg = AppConfig.fromPropertiesFile(dbProperties)
        val storage = StorageBuilder.buildStorage(appCfg, 0)
        val sqlMgr = PostchainStorageSqlManager(storage, args.sqlLog)
        runWithSqlManager(appCfg.databaseSchema, sqlMgr, logSqlErrors, code)
    } else {
        code(NoConnSqlManager)
    }
}

private fun runWithSqlManager(
        schema: String?,
        sqlMgr: SqlManager,
        logSqlErrors: Boolean,
        code: (SqlManager) -> Unit
) {
    val sqlMgr2 = Rt_SqlManager(sqlMgr, logSqlErrors)
    if (schema != null) {
        sqlMgr2.transaction { sqlExec ->
            sqlExec.connection { con ->
                SqlUtils.prepareSchema(con, schema)
            }
        }
    }
    code(sqlMgr2)
}

private fun findEntryPoint(app: R_App, moduleName: R_ModuleName, routineName: R_QualifiedName): RellEntryPoint {
    val module = app.modules.find { it.name == moduleName }
    if (module == null) {
        throw RellCliErr("Module not found: '$moduleName'")
    }

    val name = routineName.str()
    val mountName = R_MountName(routineName.parts)
    val eps = mutableListOf<RellEntryPoint>()

    val op = module.operations[name] ?: app.operations[mountName]
    if (op != null) {
        val time = System.currentTimeMillis() / 1000
        eps.add(RellEntryPoint_Operation(op, Rt_OpContext(time, -1, -1, listOf())))
    }

    val q = module.queries[name] ?: app.queries[mountName]
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

private fun createGlobalCtx(args: RellCliArgs, opCtx: Rt_OpContext?): Rt_GlobalContext {
    val bcRid = BlockchainRid(ByteArray(32))
    val chainCtx = Rt_ChainContext(GtvNull, mapOf(), bcRid)

    return Rt_GlobalContext(
            opCtx = opCtx,
            chainCtx = chainCtx,
            outPrinter = Rt_OutPrinter,
            logPrinter = Rt_LogPrinter(),
            typeCheck = args.typeCheck
    )
}

private fun createAppContext(
        args: RellCliArgs,
        app: R_App,
        sqlCtx: Rt_SqlContext,
        opCtx: Rt_OpContext?
): Rt_AppContext {
    val globalCtx = createGlobalCtx(args, opCtx)
    return Rt_AppContext(globalCtx, sqlCtx, app, null)
}

private fun parseArgs(entryPoint: RellEntryPoint, gtvCtx: GtvToRtContext, args: List<String>, json: Boolean): List<Rt_Value> {
    val params = entryPoint.routine().params()
    if (args.size != params.size) {
        System.err.println("Wrong number of arguments: ${args.size} instead of ${params.size}")
        exitProcess(1)
    }
    return args.withIndex().map { (idx, arg) -> parseArg(gtvCtx, params[idx], arg, json) }
}

private fun parseArg(gtvCtx: GtvToRtContext, param: R_Param, arg: String, json: Boolean): Rt_Value {
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

private class RellAppLauncher(
        private val app: R_App,
        private val args: RellCliArgs,
        private val entryPoint: RellEntryPoint
) {
    fun launch(sqlMgr: SqlManager, sqlCtx: Rt_SqlContext) {
        val appCtx = createAppContext(args, app, sqlCtx, entryPoint.opContext())

        val rtRes = sqlMgr.execute(entryPoint.transaction) { sqlExec ->
            val exeCtx = Rt_ExecutionContext(appCtx, sqlExec)

            val gtvCtx = GtvToRtContext(true)
            val rtArgs = parseArgs(entryPoint, gtvCtx, args.args ?: listOf(), args.json || args.jsonArgs)
            gtvCtx.finish(exeCtx)

            callEntryPoint(exeCtx, rtArgs)
        }

        if (rtRes != null && rtRes != Rt_UnitValue) {
            val strRes = resultToString(rtRes, args.jsonResult || args.json)
            println(strRes)
        }
    }

    private fun callEntryPoint(exeCtx: Rt_ExecutionContext, rtArgs: List<Rt_Value>): Rt_Value? {
        val res = try {
            entryPoint.call(exeCtx, rtArgs)
        } catch (e: Rt_StackTraceError) {
            val msg = Rt_Utils.appendStackTrace("ERROR ${e.message}", e.stack)
            System.err.println(msg)
            exitProcess(1)
        }
        return res
    }
}

private sealed class RellEntryPoint {
    abstract val kind: String
    abstract val transaction: Boolean
    abstract fun routine(): R_Routine
    abstract fun opContext(): Rt_OpContext?
    abstract fun call(exeCtx: Rt_ExecutionContext, args: List<Rt_Value>): Rt_Value?
}

private class RellEntryPoint_Function(private val f: R_Function): RellEntryPoint() {
    override val kind = "function"
    override val transaction = false
    override fun routine() = f
    override fun opContext() = null

    override fun call(exeCtx: Rt_ExecutionContext, args: List<Rt_Value>): Rt_Value? {
        return f.callTop(exeCtx, args, true)
    }
}

private class RellEntryPoint_Operation(private val o: R_Operation, private val opCtx: Rt_OpContext): RellEntryPoint() {
    override val kind = "operation"
    override val transaction = true
    override fun routine() = o
    override fun opContext() = opCtx

    override fun call(exeCtx: Rt_ExecutionContext, args: List<Rt_Value>): Rt_Value? {
        o.call(exeCtx, args)
        return null
    }
}

private class RellEntryPoint_Query(private val q: R_Query): RellEntryPoint() {
    override val kind = "query"
    override val transaction = false
    override fun routine() = q
    override fun opContext() = null

    override fun call(exeCtx: Rt_ExecutionContext, args: List<Rt_Value>): Rt_Value? {
        return q.call(exeCtx, args)
    }
}

@CommandLine.Command(name = "rell", description = ["Executes a rell program"])
private class RellCliArgs: RellBaseCliArgs() {
    @CommandLine.Option(names = ["--db-url"], paramLabel = "DB_URL",
            description = ["Database JDBC URL, e. g. jdbc:postgresql://localhost/relltestdb?user=relltestuser&password=1234"])
    var dbUrl: String? = null

    @CommandLine.Option(names = ["-p", "--db-properties"], paramLabel = "DB_PROPERTIES",
            description = ["Database connection properties file (same format as node-config.properties)"])
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

    @CommandLine.Option(names = ["--json-args"], description = ["Accept Rell program arguments in JSON format"])
    var jsonArgs = false

    @CommandLine.Option(names = ["--json-result"], description = ["Print Rell program result in JSON format"])
    var jsonResult = false

    @CommandLine.Option(names = ["--json"], description = ["Equivalent to --json-args --json-result"])
    var json = false

    @CommandLine.Option(names = ["--batch"], description = ["Run in non-interactive mode (do not start shell)"])
    var batch = false

    @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "MODULE", description = ["Module name"])
    var module: String? = null

    @CommandLine.Parameters(index = "1", arity = "0..1", paramLabel = "ENTRY", description = ["Entry point (operation/query/function name)"])
    var entry: String? = null

    @CommandLine.Parameters(index = "2..*", paramLabel = "ARGS", description = ["Call arguments"])
    var args: List<String>? = null
}
