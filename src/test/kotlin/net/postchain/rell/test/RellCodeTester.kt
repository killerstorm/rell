/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.test

import com.google.common.collect.Sets
import net.postchain.base.BaseEContext
import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.base.data.SQLDatabaseAccess
import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.common.types.WrappedByteArray
import net.postchain.core.EContext
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvNull
import net.postchain.rell.compiler.base.utils.C_MessageType
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.lib.test.Rt_DynamicBlockRunnerStrategy
import net.postchain.rell.lib.test.UnitTestBlockRunner
import net.postchain.rell.model.*
import net.postchain.rell.module.GtvToRtContext
import net.postchain.rell.module.RellPostchainModuleEnvironment
import net.postchain.rell.runtime.*
import net.postchain.rell.runtime.utils.toGtv
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlInit
import net.postchain.rell.sql.SqlInitLogging
import net.postchain.rell.sql.SqlUtils
import net.postchain.rell.utils.*
import org.junit.Assert
import kotlin.test.assertEquals

class RellCodeTester(
        tstCtx: RellTestContext,
        entityDefs: List<String> = listOf(),
        inserts: List<String> = listOf(),
        gtv: Boolean = false
): RellBaseTester(tstCtx, entityDefs, inserts, gtv) {
    var gtvResult: Boolean = gtv
        set(value) {
            checkNotInited()
            field = value
        }

    var dropTables = true
    var createTables = true
    var wrapInit = false
    var strictToString = true
    var opContext: Rt_OpContext? = null
    var sqlUpdatePortionSize = 1000
    var replModule: String? = null
    var chainRid: String? = null

    private val chainDependencies = mutableMapOf<String, TestChainDependency>()
    private val postInitOps = mutableListOf<String>()

    private var rtErrStack = listOf<R_StackPos>()

    override fun initSqlReset(sqlExec: SqlExecutor, moduleCode: String, app: R_App) {
        val globalCtx = createInitGlobalCtx()
        val exeCtx = createExeCtx(globalCtx, sqlExec, app)

        if (dropTables) {
            SqlUtils.dropAll(sqlExec, false)
        }

        if (createTables) {
            initSqlCreateSysTables(sqlExec)
            SqlInit.init(exeCtx, false, SqlInitLogging())
        }
    }

    private fun initSqlCreateSysTables(sqlExec: SqlExecutor) {
        val sqlAccess: SQLDatabaseAccess = PostgreSQLDatabaseAccess()
        sqlExec.connection { con ->
            val eCtx: EContext = BaseEContext(con, chainId, sqlAccess)
            val bcRid = BlockchainRid(blockchainRid.hexStringToByteArray())
            sqlAccess.initializeBlockchain(eCtx, bcRid)
        }
    }

    fun createInitGlobalCtx(): Rt_GlobalContext {
        val compilerOptions = compilerOptions()
        return Rt_GlobalContext(
                compilerOptions,
                Rt_FailingPrinter,
                Rt_FailingPrinter,
                logSqlErrors = true,
                typeCheck = true,
                pcModuleEnv = RellPostchainModuleEnvironment.DEFAULT
        )
    }

    fun chainDependency(name: String, rid: String, height: Long) {
        check(name !in chainDependencies)
        val bcRid = RellTestUtils.strToBlockchainRid(rid)
        chainDependencies[name] = TestChainDependency(bcRid, height)
    }

    fun chainDependencies() = chainDependencies.mapValues { (_, v) -> Pair(v.rid, v.height) }.toImmMap()

    fun postInitOp(code: String) {
        checkNotInited()
        postInitOps.add(code)
    }

    override fun postInit() {
        for (code in postInitOps) {
            chkOp(code, "OK")
        }
    }

    override fun chkEx(code: String, expected: String) {
        val queryCode = "query q() $code"
        chkFull(queryCode, "q", listOf(), expected)
    }

    fun chkEx(code: String, args: List<Any>, expected: String) {
        val args2 = args.map { v ->
            when (v) {
                is Boolean -> "boolean" to Rt_BooleanValue(v)
                is Long -> "integer" to Rt_IntValue(v)
                else -> throw IllegalArgumentException(v.javaClass.name)
            }
        }

        val params = args2.mapIndexed { i, v ->
            val argName = ('a' + i).toString()
            "$argName: ${v.first}"
        }.joinToString(", ")

        chkFull("query q($params) $code", args2.map { it.second }, expected)
    }

    fun chkFull(code: String, expected: String) = chkFull(code, listOf(), expected)
    fun chkFull(code: String, args: List<Rt_Value>, expected: String) = chkFull(code, "q", args, expected)

    fun chkFull(code: String, name: String, args: List<Rt_Value>, expected: String) {
        val actual = callQuery(code, name, args)
        checkResult(expected, actual)
    }

    fun chkQueryGtv(code: String, expected: String) {
        val queryCode = "query q() $code"
        val actual = callQuery0(queryCode, "q", listOf(), GtvTestUtils::decodeGtvQueryArgs)
        checkResult(expected, actual)
    }

    fun chkQueryGtvEx(code: String, args: List<String>, expected: String) {
        val actual = callQuery0(code, "q", args, GtvTestUtils::decodeGtvQueryArgs)
        checkResult(expected, actual)
    }

    fun chkQueryType(bodyCode: String, expected: String) {
        val queryCode = "query q() $bodyCode"
        val moduleCode = moduleCode(queryCode)
        val actual = processApp(moduleCode) { module ->
            module.queries.getValue(R_MountName.of("q")).type().strCode()
        }
        checkResult(expected, actual)
    }

    fun chkOp(bodyCode: String, expected: String = "OK") {
        val opCode = "operation o() { $bodyCode }"
        chkOpEx(opCode, "o", expected)
    }

    fun chkOpEx(opCode: String, name: String, expected: String) {
        val actual = callOp(opCode, name, listOf())
        checkResult(expected, actual)
    }

    fun chkOpGtvEx(code: String, args: List<String>, expected: String) {
        val actual = callOp0(code, "o", args, GtvTestUtils::decodeGtvOpArgsStr)
        checkResult(expected, actual)
    }

    fun chkFn(code: String, expected: String) = chkFnFull("function f() $code", expected)

    fun chkFnFull(code: String, expected: String) {
        val actual = callFn(code)
        checkResult(expected, actual)
    }

    private fun callFn(code: String): String {
        init()
        val moduleCode = moduleCode(code)
        return processWithExeCtx(moduleCode, false) { modCtx ->
            RellTestUtils.callFn(modCtx, "f", listOf(), strictToString)
        }
    }

    fun callQuery(code: String, name: String, args: List<Rt_Value>): String {
        return callQuery0(code, name, args) { _, v -> v }
    }

    private fun <T> callQuery0(
            code: String,
            name: String,
            args: List<T>,
            decoder: (List<R_Param>, List<T>) -> List<Rt_Value>
    ): String {
        val evalRes = eval.eval {
            if (wrapInit) {
                eval.wrapAll { init() }
            } else {
                init()
            }

            val moduleCode = moduleCode(code)

            val encoder = when {
                gtvResult -> RellTestUtils.ENCODER_GTV
                strictToString -> RellTestUtils.ENCODER_STRICT
                else -> RellTestUtils.ENCODER_PLAIN
            }

            processWithExeCtx(moduleCode, false) { appCtx ->
                RellTestUtils.callQueryGeneric(eval, appCtx, name, args, decoder, encoder)
            }
        }

        rtErrStack = eval.errorStack()
        return evalRes
    }

    private fun callOp(code: String, name: String, args: List<Rt_Value>): String {
        return callOp0(code, name, args) { _, v -> v }
    }

    fun callOpGtv(code: String, args: List<Gtv>): String {
        return callOp0(code, "o", args, GtvTestUtils::decodeGtvOpArgs)
    }

    fun callOpGtvStr(code: String, args: List<String>): String {
        return callOp0(code, "o", args, GtvTestUtils::decodeGtvOpArgsStr)
    }

    private fun <T> callOp0(code: String, name: String, args: List<T>, decoder: (List<R_Param>, List<T>) -> List<Rt_Value>): String {
        init()
        val moduleCode = moduleCode(code)
        return processWithAppSqlCtx(moduleCode) { appCtx, sqlCtx ->
            RellTestUtils.callOpGeneric(appCtx, opContext, sqlCtx, tstCtx.sqlMgr(), name, args, decoder)
        }
    }

    fun chkWarn(vararg msgs: String) {
        val actual = messages.filter { it.type == C_MessageType.WARNING }.map { it.code }.toSet()
        val expected = msgs.toSet()
        if (actual != expected) {
            val diffAct = Sets.difference(actual, expected)
            val diffExp = Sets.difference(expected, actual)
            val list = mutableListOf<String>()
            if (!diffExp.isEmpty()) list.add("missing: $diffExp")
            if (!diffAct.isEmpty()) list.add("extra: $diffAct")
            val msg = list.joinToString()
            Assert.fail(msg)
        }
    }

    fun chkStack(vararg expected: String) {
        val actual = rtErrStack.map { it.toString() }
        assertEquals(expected.toList(), actual)
    }

    fun chkInit(expected: String) {
        val actual = RellTestUtils.catchRtErr {
            init()
            "OK"
        }
        assertEquals(expected, actual)
    }

    fun resetSqlCtr() {
        tstCtx.resetSqlCounter()
    }

    fun chkSql(expected: Int) {
        assertEquals(expected, tstCtx.sqlCounter())
    }

    fun createRepl(): RellReplTester {
        val files = files()
        val moduleNameStr = replModule
        val module = if (moduleNameStr == null) null else R_ModuleName.of(moduleNameStr)

        val sourceDir = C_SourceDir.mapDirOf(files)
        val globalCtx = createGlobalCtx()

        val sqlMgr = tstCtx.sqlMgr()
        return RellReplTester(globalCtx, sourceDir, sqlMgr, module, tstCtx.useSql)
    }

    private fun processWithExeCtx(code: String, tx: Boolean, processor: (Rt_ExecutionContext) -> String): String {
        val globalCtx = createGlobalCtx()
        return processApp(code) { app ->
            RellTestUtils.catchRtErr {
                tstCtx.sqlMgr().execute(tx) { sqlExec ->
                    val exeCtx = createExeCtx(globalCtx, sqlExec, app)
                    processor(exeCtx)
                }
            }
        }
    }

    private fun processWithAppSqlCtx(code: String, processor: (Rt_AppContext, Rt_SqlContext) -> String): String {
        val globalCtx = createGlobalCtx()
        val res = processApp(code) { app ->
            RellTestUtils.catchRtErr {
                val (appCtx, sqlCtx) = tstCtx.sqlMgr().access { sqlExec ->
                    val appCtx0 = createAppCtx(globalCtx, app, C_SourceDir.EMPTY, false)
                    val sqlCtx0 = createSqlCtx(app, sqlExec)
                    Pair(appCtx0, sqlCtx0)
                }
                processor(appCtx, sqlCtx)
            }
        }
        return res
    }

    fun chkTests(testModule: String, expected: String) {
        testModules(testModule) // Side effect, not good.
        val actual = runTests()
        assertEquals(expected, actual)
    }

    private fun runTests(): String {
        val res = processWithTestRunnerCtx { testRunnerCtx ->
            val testFns = TestRunner.getTestFunctions(testRunnerCtx.app, TestMatcher.ANY)
                    .map { TestRunnerCase(TestRunnerChain("foo", 123), it) }
                    .toImmList()

            val testRes = TestRunnerResults()
            TestRunner.runTests(testRunnerCtx, testFns, testRes)

            val resList = testRes.getResults()
            val resMap = resList
                    .map {
                        val err = it.res.error
                        it.case.fn.moduleLevelName to (if(err == null) "OK" else RellTestUtils.rtErrToResult(err).res)
                    }.toMap().toImmMap()

            resMap.entries.joinToString(",")
        }

        return res
    }

    private fun processWithTestRunnerCtx(processor: (TestRunnerContext) -> String): String {
        val globalCtx = createGlobalCtx()
        val moduleCode = moduleCode("")

        return processApp(moduleCode) { app ->
            val sqlCtx = tstCtx.sqlMgr().access { sqlExec ->
                createSqlCtx(app, sqlExec)
            }

            val chainCtx = createChainContext(app)
            val sourceDir = createSourceDir(moduleCode)
            val blkRunStrategy = createBlockRunnerStrategy(app, sourceDir)

            val testRunnerCtx = TestRunnerContext(
                    sqlCtx = sqlCtx,
                    sqlMgr = tstCtx.sqlMgr(),
                    globalCtx = globalCtx,
                    chainCtx = chainCtx,
                    blockRunnerStrategy = blkRunStrategy,
                    app = app
            )

            processor(testRunnerCtx)
        }
    }

    fun createGlobalCtx(): Rt_GlobalContext {
        val compilerOptions = compilerOptions()

        val pcModuleEnv = RellPostchainModuleEnvironment(
                outPrinter = outPrinter,
                logPrinter = logPrinter,
                wrapCtErrors = false,
                wrapRtErrors = false,
                forceTypeCheck = true
        )

        return Rt_GlobalContext(
                compilerOptions,
                outPrinter,
                logPrinter,
                pcModuleEnv = pcModuleEnv,
                logSqlErrors = true,
                sqlUpdatePortionSize = sqlUpdatePortionSize,
                typeCheck = true
        )
    }

    private fun createChainContext(app: R_App, gtvConfig: Gtv = GtvNull): Rt_ChainContext {
        val bcRidHex = chainRid ?: "00".repeat(32)
        val bcRid = BlockchainRid(bcRidHex.hexStringToByteArray())
        val modArgsVals = getModuleArgsValues(app)
        return Rt_ChainContext(gtvConfig, modArgsVals, bcRid)
    }

    private fun getModuleArgsValues(app: R_App): Map<R_ModuleName, Rt_Value> {
        val modArgs = getModuleArgs()
        return modArgs.map {
            val modName = R_ModuleName.of(it.key)
            val module = app.moduleMap.getValue(modName)
            val struct = module.moduleArgs!!
            val gtv = GtvTestUtils.strToGtv(it.value)
            val value = struct.type.gtvToRt(GtvToRtContext.make(pretty = true), gtv)
            modName to value
        }.toMap().toImmMap()
    }

    fun createAppCtx(
            globalCtx: Rt_GlobalContext,
            app: R_App,
            sourceDir: C_SourceDir,
            test: Boolean
    ): Rt_AppContext {
        val chainCtx = createChainContext(app, GtvNull)
        val blkRunStrategy = createBlockRunnerStrategy(app, sourceDir)

        return Rt_AppContext(
                globalCtx,
                chainCtx,
                app,
                repl = false,
                test = test,
                replOut = null,
                blockRunnerStrategy = blkRunStrategy
        )
    }

    private fun createBlockRunnerStrategy(app: R_App, sourceDir: C_SourceDir): Rt_BlockRunnerStrategy {
        val modules = app.modules.filter { !it.test && !it.abstract && !it.external }.map { it.name }
        val keyPair = UnitTestBlockRunner.getTestKeyPair()
        val modArgs = getModuleArgs()
        val s = Rt_DynamicBlockRunnerStrategy(sourceDir, modules, keyPair)
        return Rt_TestBlockRunnerStrategy(s, modArgs)
    }

    fun createExeCtx(
            globalCtx: Rt_GlobalContext,
            sqlExec: SqlExecutor,
            app: R_App,
            sourceDir: C_SourceDir = C_SourceDir.EMPTY,
            test: Boolean = false
    ): Rt_ExecutionContext {
        val appCtx = createAppCtx(globalCtx, app, sourceDir, test)
        val sqlCtx = createSqlCtx(app, sqlExec)
        return Rt_ExecutionContext(appCtx, opContext, sqlCtx, sqlExec)
    }

    private fun createSqlCtx(app: R_App, sqlExec: SqlExecutor): Rt_SqlContext {
        val sqlMapping = createChainSqlMapping()
        val rtDeps = chainDependencies.mapValues { (_, v) -> Rt_ChainDependency(v.rid.data) }

        val heightMap = chainDependencies.mapKeys { (_, v) -> WrappedByteArray(v.rid.data) }.mapValues { (_, v) -> v.height }
        val heightProvider = TestChainHeightProvider(heightMap)

        return eval.wrapRt {
            Rt_RegularSqlContext.create(app, sqlMapping, rtDeps, sqlExec, heightProvider)
        }
    }

    private class TestChainDependency(val rid: BlockchainRid, val height: Long)

    private class TestChainHeightProvider(private val map: Map<WrappedByteArray, Long>): Rt_ChainHeightProvider {
        override fun getChainHeight(rid: WrappedByteArray, id: Long): Long? {
            val height = map[rid]
            return height
        }
    }

    private class Rt_TestBlockRunnerStrategy(
            private val s: Rt_BlockRunnerStrategy,
            modArgs: Map<String, String>
    ): Rt_BlockRunnerStrategy() {
        private val modArgs = modArgs.toImmMap()

        override fun createGtvConfig(): Gtv {
            val gtv = s.createGtvConfig()
            val gtv2 = mapOf(
                    "gtx" to mapOf(
                            "rell" to mapOf(
                                    "moduleArgs" to RellGtxTester.moduleArgsToGtv(modArgs)
                            ).toGtv()
                    ).toGtv()
            ).toGtv()
            return GtvTestUtils.merge(gtv, gtv2)
        }

        override fun getKeyPair() = s.getKeyPair()
    }
}
