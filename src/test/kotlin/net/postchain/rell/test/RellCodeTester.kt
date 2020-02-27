/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.test

import com.google.common.collect.Sets
import net.postchain.base.BlockchainRid
import net.postchain.core.ByteArrayKey
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvNull
import net.postchain.rell.CommonUtils
import net.postchain.rell.compiler.C_MessageType
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlInit
import net.postchain.rell.sql.SqlInitLogging
import net.postchain.rell.sql.SqlUtils
import net.postchain.rell.toImmMap
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

    private val chainDependencies = mutableMapOf<String, TestChainDependency>()

    private var rtErrStack = listOf<R_StackPos>()

    override fun initSqlReset(sqlExec: SqlExecutor, moduleCode: String, app: R_App) {
        val globalCtx = createInitGlobalCtx()
        val exeCtx = createExeCtx(globalCtx, sqlExec, app)

        if (dropTables) {
            SqlUtils.dropAll(sqlExec, false)
        }

        if (createTables) {
            SqlInit.init(exeCtx, SqlInitLogging())
        }
    }

    fun createInitGlobalCtx(): Rt_GlobalContext {
        val chainCtx = createChainContext()
        return Rt_GlobalContext(
                Rt_FailingPrinter,
                Rt_FailingPrinter,
                null,
                chainCtx,
                logSqlErrors = true,
                typeCheck = true
        )
    }

    fun chainDependency(name: String, rid: String, height: Long) {
        check(name !in chainDependencies)
        val ridArray = CommonUtils.hexToBytes(rid)
        chainDependencies[name] = TestChainDependency(ridArray, height)
    }

    fun chainDependencies() = chainDependencies.mapValues { (_, v) -> Pair(v.rid, v.height) }.toImmMap()

    fun chkQuery(bodyCode: String, expected: String) {
        val queryCode = "query q() = $bodyCode;"
        chkQueryEx(queryCode, "q", listOf(), expected)
    }

    fun chkQueryEx(code: String, name: String, args: List<Rt_Value>, expected: String) {
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
            module.queries.getValue(R_MountName.of("q")).type().toStrictString()
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

    fun chkFnEx(code: String, expected: String) {
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

            val encoder = if (gtvResult) RellTestUtils.ENCODER_GTV
            else if (strictToString) RellTestUtils.ENCODER_STRICT
            else RellTestUtils.ENCODER_PLAIN

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
        return processWithAppCtx(moduleCode) { appCtx ->
            RellTestUtils.callOpGeneric(appCtx, tstCtx.sqlMgr(), name, args, decoder)
        }
    }

    fun createGlobalCtx(): Rt_GlobalContext {
        val chainContext = createChainContext()
        return Rt_GlobalContext(
                outPrinter,
                logPrinter,
                opContext,
                chainContext,
                logSqlErrors = true,
                sqlUpdatePortionSize = sqlUpdatePortionSize,
                typeCheck = true
        )
    }

    private fun createChainContext(): Rt_ChainContext {
        val bcRid = BlockchainRid(ByteArray(32))
        return Rt_ChainContext(GtvNull, mapOf(), bcRid)
    }

    fun createAppCtx(globalCtx: Rt_GlobalContext, sqlExec: SqlExecutor, app: R_App): Rt_AppContext {
        val sqlCtx = createSqlCtx(app, sqlExec)
        return Rt_AppContext(globalCtx, sqlCtx, app, null)
    }

    fun createExeCtx(globalCtx: Rt_GlobalContext, sqlExec: SqlExecutor, app: R_App): Rt_ExecutionContext {
        val appCtx = createAppCtx(globalCtx, sqlExec, app)
        return Rt_ExecutionContext(appCtx, sqlExec)
    }

    private fun createSqlCtx(app: R_App, sqlExec: SqlExecutor): Rt_SqlContext {
        val sqlMapping = createChainSqlMapping()
        val rtDeps = chainDependencies.mapValues { (_, v) -> Rt_ChainDependency(v.rid) }

        val heightMap = chainDependencies.mapKeys { (_, v) -> ByteArrayKey(v.rid) }.mapValues { (_, v) -> v.height }
        val heightProvider = TestChainHeightProvider(heightMap)

        return eval.wrapRt { Rt_SqlContext.create(app, sqlMapping, rtDeps, sqlExec, heightProvider) }
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

    fun createRepl(): RellReplTester {
        val files = files()
        val globalCtx = createGlobalCtx()
        val moduleNameStr = replModule
        val module = if (moduleNameStr == null) null else R_ModuleName.of(moduleNameStr)
        val sqlMgr = tstCtx.sqlMgr()
        return RellReplTester(files, globalCtx, sqlMgr, module, tstCtx.useSql)
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

    private fun processWithAppCtx(code: String, processor: (Rt_AppContext) -> String): String {
        val globalCtx = createGlobalCtx()
        val res = processApp(code) { app ->
            RellTestUtils.catchRtErr {
                val appCtx = tstCtx.sqlMgr().access { sqlExec ->
                    createAppCtx(globalCtx, sqlExec, app)
                }
                processor(appCtx)
            }
        }
        return res
    }

    private class TestChainDependency(val rid: ByteArray, val height: Long)

    private class TestChainHeightProvider(private val map: Map<ByteArrayKey, Long>): Rt_ChainHeightProvider {
        override fun getChainHeight(rid: ByteArrayKey, id: Long): Long? {
            val height = map[rid]
            return height
        }
    }
}
