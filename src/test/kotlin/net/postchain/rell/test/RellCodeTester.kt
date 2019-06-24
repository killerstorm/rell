package net.postchain.rell.test

import com.google.common.collect.Sets
import net.postchain.core.ByteArrayKey
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvNull
import net.postchain.rell.CommonUtils
import net.postchain.rell.model.R_ExternalParam
import net.postchain.rell.model.R_Module
import net.postchain.rell.parser.C_MessageType
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlInit
import net.postchain.rell.sql.SqlUtils
import org.junit.Assert
import kotlin.test.assertEquals

class RellCodeTester(
        tstCtx: RellTestContext,
        classDefs: List<String> = listOf(),
        inserts: List<String> = listOf(),
        gtv: Boolean = false
): RellBaseTester(tstCtx, classDefs, inserts, gtv)
{
    var gtvResult: Boolean = gtv
        set(value) {
            checkNotInited()
            field = value
        }

    var dropTables = true
    var createTables = true
    var strictToString = true
    var opContext: Rt_OpContext? = null
    var sqlUpdatePortionSize = 1000

    private val chainDependencies = mutableMapOf<String, TestChainDependency>()

    override fun initSqlReset(sqlExec: SqlExecutor, moduleCode: String, module: R_Module) {
        val globalCtx = createInitGlobalCtx(sqlExec)
        val modCtx = createModuleCtx(globalCtx, module)

        sqlExec.transaction {
            if (dropTables) {
                SqlUtils.dropAll(sqlExec, false)
            }

            if (createTables) {
                SqlInit.init(modCtx, SqlInit.LOG_ALL)
            }
        }
    }

    fun createInitGlobalCtx(sqlExec: SqlExecutor): Rt_GlobalContext {
        val chainCtx = Rt_ChainContext(GtvNull, Rt_NullValue)
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

    private fun initSqlObjects(sqlExec: SqlExecutor, sqlCtx: Rt_SqlContext) {
        val globalCtx = createInitGlobalCtx(sqlExec)
        val modCtx = Rt_ModuleContext(globalCtx, sqlCtx.module, sqlCtx)

        for (rObject in sqlCtx.module.objects.values) {
            modCtx.insertObjectRecord(rObject)
        }
    }

    fun chainDependency(name: String, rid: String, height: Long) {
        check(name !in chainDependencies)
        val ridArray = CommonUtils.hexToBytes(rid)
        chainDependencies[name] = TestChainDependency(ridArray, height)
    }

    fun chkQuery(bodyCode: String, expected: String) {
        val queryCode = "query q() = $bodyCode;"
        chkQueryEx(queryCode, listOf(), expected)
    }

    fun chkQueryEx(code: String, args: List<Rt_Value>, expected: String) {
        val actual = callQuery(code, args)
        assertEquals(expected, actual)
    }

    fun chkQueryGtv(code: String, expected: String) {
        val queryCode = "query q() $code"
        val actual = callQuery0(queryCode, listOf(), GtvTestUtils::decodeGtvQueryArgs)
        assertEquals(expected, actual)
    }

    fun chkQueryGtvEx(code: String, args: List<String>, expected: String) {
        val actual = callQuery0(code, args, GtvTestUtils::decodeGtvQueryArgs)
        assertEquals(expected, actual)
    }

    fun chkQueryType(bodyCode: String, expected: String) {
        val queryCode = "query q() $bodyCode"
        val moduleCode = moduleCode(queryCode)
        val actual = processModule(moduleCode) { module ->
            module.queries.getValue("q").type.toStrictString()
        }
        assertEquals(expected, actual)
    }

    fun chkOp(bodyCode: String, expected: String = "OK") {
        val opCode = "operation o() { $bodyCode }"
        chkOpEx(opCode, expected)
    }

    fun chkOpEx(opCode: String, expected: String) {
        val actual = callOp(opCode, listOf())
        assertEquals(expected, actual)
    }

    fun chkOpGtvEx(code: String, args: List<String>, expected: String) {
        val actual = callOp0(code, args, GtvTestUtils::decodeGtvOpArgsStr)
        assertEquals(expected, actual)
    }

    fun chkFnEx(code: String, expected: String) {
        val actual = callFn(code)
        assertEquals(expected, actual)
    }

    private fun callFn(code: String): String {
        init()
        val moduleCode = moduleCode(code)
        val globalCtx = createGlobalCtx()
        return processModuleCtx(globalCtx, moduleCode) { modCtx ->
            RellTestUtils.callFn(modCtx, "f", listOf(), strictToString)
        }
    }

    fun callQuery(code: String, args: List<Rt_Value>): String {
        return callQuery0(code, args) { _, v -> v }
    }

    private fun <T> callQuery0(code: String, args: List<T>, decoder: (List<R_ExternalParam>, List<T>) -> List<Rt_Value>): String {
        return eval.eval {
            init()
            val moduleCode = moduleCode(code)
            val globalCtx = createGlobalCtx()

            val encoder = if (gtvResult) RellTestUtils.ENCODER_GTV
            else if (strictToString) RellTestUtils.ENCODER_STRICT
            else RellTestUtils.ENCODER_PLAIN

            processModuleCtx(globalCtx, moduleCode) { modCtx ->
                RellTestUtils.callQueryGeneric(modCtx, "q", args, decoder, encoder)
            }
        }
    }

    fun callOp(code: String, args: List<Rt_Value>): String {
        return callOp0(code, args) { _, v -> v }
    }

    fun callOpGtv(code: String, args: List<Gtv>): String {
        return callOp0(code, args, GtvTestUtils::decodeGtvOpArgs)
    }

    fun callOpGtvStr(code: String, args: List<String>): String {
        return callOp0(code, args, GtvTestUtils::decodeGtvOpArgsStr)
    }

    private fun <T> callOp0(code: String, args: List<T>, decoder: (List<R_ExternalParam>, List<T>) -> List<Rt_Value>): String {
        init()
        val moduleCode = moduleCode(code)
        val globalCtx = createGlobalCtx()
        return processModuleCtx(globalCtx, moduleCode) { modCtx ->
            RellTestUtils.callOpGeneric(modCtx, "o", args, decoder)
        }
    }

    private fun createGlobalCtx(): Rt_GlobalContext {
        val chainContext = Rt_ChainContext(GtvNull, Rt_NullValue)
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

    fun createModuleCtx(globalCtx: Rt_GlobalContext, module: R_Module): Rt_ModuleContext {
        val sqlCtx = createSqlCtx(module, globalCtx.sqlExec)
        return Rt_ModuleContext(globalCtx, module, sqlCtx)
    }

    private fun createSqlCtx(module: R_Module, sqlExec: SqlExecutor): Rt_SqlContext {
        val sqlMapping = createChainSqlMapping()
        val rtDeps = chainDependencies.mapValues { (k, v) -> Rt_ChainDependency(v.rid) }

        val heightMap = chainDependencies.mapKeys { (k, v) -> ByteArrayKey(v.rid) }.mapValues { (k, v) -> v.height }
        val heightProvider = TestChainHeightProvider(heightMap)

        return eval.wrapRt { Rt_SqlContext.create(module, sqlMapping, rtDeps, sqlExec, heightProvider) }
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

    fun chkInit(expected: String) {
        val actual = RellTestUtils.catchRtErr {
            init()
            "OK"
        }
        assertEquals(expected, actual)
    }

    private fun processModuleCtx(globalCtx: Rt_GlobalContext, code: String, processor: (Rt_ModuleContext) -> String): String {
        return processModule(code) { module ->
            RellTestUtils.catchRtErr({ createModuleCtx(globalCtx, module) }) {
                modCtx -> processor(modCtx)
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
