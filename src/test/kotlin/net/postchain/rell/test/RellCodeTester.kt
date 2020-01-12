package net.postchain.rell.test

import com.google.common.collect.Sets
import net.postchain.base.BlockchainRid
import net.postchain.core.ByteArrayKey
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvNull
import net.postchain.rell.CommonUtils
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_MountName
import net.postchain.rell.model.R_Param
import net.postchain.rell.model.R_StackPos
import net.postchain.rell.compiler.C_MessageType
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlInit
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

    private val chainDependencies = mutableMapOf<String, TestChainDependency>()

    private var rtErrStack = listOf<R_StackPos>()

    override fun initSqlReset(exec: SqlExecutor, moduleCode: String, app: R_App) {
        val globalCtx = createInitGlobalCtx(exec)
        val appCtx = createAppCtx(globalCtx, app)

        exec.transaction {
            if (dropTables) {
                SqlUtils.dropAll(exec, false)
            }

            if (createTables) {
                SqlInit.init(appCtx, SqlInit.LOG_NONE)
            }
        }
    }

    fun createInitGlobalCtx(sqlExec: SqlExecutor): Rt_GlobalContext {
        val chainCtx = createChainContext()
        return Rt_GlobalContext(
                Rt_FailingPrinter,
                Rt_FailingPrinter,
                sqlExec,
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
        val globalCtx = createGlobalCtx()
        return processAppCtx(globalCtx, moduleCode) { modCtx ->
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
            val globalCtx = createGlobalCtx()

            val encoder = if (gtvResult) RellTestUtils.ENCODER_GTV
            else if (strictToString) RellTestUtils.ENCODER_STRICT
            else RellTestUtils.ENCODER_PLAIN

            processAppCtx(globalCtx, moduleCode) { appCtx ->
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
        val globalCtx = createGlobalCtx()
        return processAppCtx(globalCtx, moduleCode) { modCtx ->
            RellTestUtils.callOpGeneric(modCtx, name, args, decoder)
        }
    }

    private fun createGlobalCtx(): Rt_GlobalContext {
        val chainContext = createChainContext()
        return Rt_GlobalContext(
                stdoutPrinter,
                logPrinter,
                getSqlExec(),
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

    fun createAppCtx(globalCtx: Rt_GlobalContext, app: R_App): Rt_AppContext {
        val sqlCtx = createSqlCtx(app, globalCtx.sqlExec)
        return Rt_AppContext(globalCtx, sqlCtx, app)
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

    private fun processAppCtx(globalCtx: Rt_GlobalContext, code: String, processor: (Rt_AppContext) -> String): String {
        return processApp(code) { app ->
            RellTestUtils.catchRtErr({ createAppCtx(globalCtx, app) }) {
                appCtx -> processor(appCtx)
            }
        }
    }

    private class TestChainDependency(val rid: ByteArray, val height: Long)

    private class TestChainHeightProvider(private val map: Map<ByteArrayKey, Long>): Rt_ChainHeightProvider {
        override fun getChainHeight(rid: ByteArrayKey, id: Long): Long? {
            val height = map[rid]
            return height
        }
    }
}
