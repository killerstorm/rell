package net.postchain.rell.test

import net.postchain.base.BaseBlockEContext
import net.postchain.base.BaseEContext
import net.postchain.base.BaseTxEContext
import net.postchain.core.UserMistake
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory
import net.postchain.gtv.GtvString
import net.postchain.gtx.ExtOpData
import net.postchain.gtx.GTXModule
import net.postchain.gtx.GTXSchemaManager
import net.postchain.rell.CommonUtils
import net.postchain.rell.PostchainUtils
import net.postchain.rell.model.R_Module
import net.postchain.rell.module.RellPostchainModuleFactory
import net.postchain.rell.sql.SqlExecutor
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class RellGtxTester(
        tstCtx: RellTestContext,
        classDefs: List<String> = listOf(),
        inserts: List<String> = listOf(),
        gtv: Boolean = false
): RellBaseTester(tstCtx, classDefs, inserts, gtv)
{
    var wrapRtErrors = true
    var moduleArgs: String? = null
    val extraModuleConfig = mutableMapOf<String, String>()
    var blockchainRID = "DEADBEEF"
    var nodeId: Int = 3377

    init {
        chainId = 995511
    }

    override fun initSqlReset(conn: Connection, sqlExec: SqlExecutor, moduleCode: String, module: R_Module) {
        val gtxModule = createGtxModule(moduleCode)

        sqlExec.transaction {
            val dbAccess = PostchainUtils.createDatabaseAccess()
            val ctx = BaseEContext(conn, chainId, nodeId, dbAccess)
            GTXSchemaManager.initializeDB(ctx)
            gtxModule.initializeDB(ctx)
        }
    }

    fun chkQuery(code: String, expected: String) {
        chkQueryEx("query q() = $code;", "", expected)
    }

    fun chkQueryEx(code: String, args: String, expected: String) {
        val gtvArgs = strToArgs(args)
        val actual = callQueryEx(code, gtvArgs)
        assertEquals(expected, actual)
    }

    fun chkQueryEx(code: String, args: Map<String, Gtv>, expected: String) {
        val actual = callQueryEx(code, args)
        assertEquals(expected, actual)
    }

    private fun callQueryEx(code: String, args: Map<String, Gtv>): String {
        val moduleCode = moduleCode(code)
        return callQuery0(moduleCode, "q", args)
    }

    fun callQuery(name: String, args: Map<String, Gtv>): String {
        val moduleCode = defsCode()
        return callQuery0(moduleCode, name, args)
    }

    fun chkCallQuery(name: String, args: String, expected: String) {
        val gtvArgs = strToArgs(args)
        val actual = callQuery(name, gtvArgs)
        assertEquals(expected, actual)
    }

    private fun strToArgs(args: String): Map<String, Gtv> {
        val str = "{$args}"
        val gtv = GtvTestUtils.decodeGtvStr(str)
        return gtv.asDict()
    }

    private fun callQuery0(moduleCode: String, name: String, args: Map<String, Gtv>): String {
        return eval.eval {
            eval.wrapRt { init() }

            val queryMap = mutableMapOf<String, Gtv>("type" to GtvFactory.gtv("q"))
            queryMap.putAll(args)
            val queryGtv = GtvFactory.gtv(queryMap)

            val module = eval.wrapCt { createGtxModule(moduleCode) }

            val conn = getSqlConn()
            val dbAccess = PostchainUtils.createDatabaseAccess()
            val ctx = BaseEContext(conn, chainId, nodeId, dbAccess)

            val res = eval.wrapRt {
                module.query(ctx, name, queryGtv)
            }

            GtvTestUtils.gtvToStr(res)
        }
    }

    fun chkCallOperation(name: String, args: List<String>, expected: String = "OK") {
        val gtvArgs = args.map { GtvTestUtils.decodeGtvStr(it.replace('\'', '"')) }
        val moduleCode = defsCode()
        val actual = callOperation0(moduleCode, name, gtvArgs)
        assertEquals(expected, actual)
    }

    fun chkOpEx(code: String, args: List<Gtv>, expected: String = "OK") {
        val moduleCode = moduleCode(code)
        val actual = callOperation0(moduleCode, "o", args)
        assertEquals(expected, actual)
    }

    private fun callOperation0(moduleCode: String, name: String, args: List<Gtv>): String {
        return eval.eval {
            eval.wrapRt { init() }

            val module = eval.wrapCt { createGtxModule(moduleCode) }

            val conn = getSqlConn()
            val dbAccess = PostchainUtils.createDatabaseAccess()
            val ctx = BaseEContext(conn, chainId, nodeId, dbAccess)
            val blkCtx = BaseBlockEContext(ctx, 0, 0, mapOf())
            val txCtx = BaseTxEContext(blkCtx, 0)

            val bcRid = CommonUtils.hexToBytes(blockchainRID)
            val opData = ExtOpData(name, 0, bcRid, arrayOf(), args.toTypedArray())
            val tx = module.makeTransactor(opData)
            check(tx.isCorrect())

            val res = tx.apply(txCtx)
            check(res)
            "OK"
        }
    }

    fun chkUserMistake(code: String, msg: String) {
        val moduleCode = moduleCode(code)

        try {
            createGtxModule(moduleCode)
            fail("Did not throw UserMistake")
        } catch (e: UserMistake) {
            assertTrue(e.message!!.startsWith(msg), "" + e)
        }
    }

    private fun createGtxModule(moduleCode: String): GTXModule {
        val factory = RellPostchainModuleFactory(
                stdoutPrinter = stdoutPrinter,
                logPrinter = logPrinter,
                wrapCtErrors = false,
                wrapRtErrors = wrapRtErrors,
                forceTypeCheck = true
        )

        val moduleCfg = moduleConfig(moduleCode)
        val bcRidBytes = CommonUtils.hexToBytes(blockchainRID)
        val module = factory.makeModule(moduleCfg, bcRidBytes)
        return module
    }

    private fun moduleConfig(moduleCode: String): Gtv {
        val rellMap = mutableMapOf<String, Gtv>()
        rellMap["mainFile"] = GtvString(RellTestUtils.MAIN_FILE)

        val sourceCodes = files(moduleCode)
        rellMap["sources_v0.8"] = GtvFactory.gtv(sourceCodes.mapValues { (_, v) -> GtvString(v) })

        if (moduleArgs != null) {
            rellMap["moduleArgs"] = GtvTestUtils.decodeGtvStr(moduleArgs!!)
        }

        val cfgMap = mutableMapOf<String, Gtv>()
        cfgMap["gtx"] = GtvFactory.gtv(mapOf("rell" to GtvFactory.gtv(rellMap)))
        for ((key, value) in extraModuleConfig) {
            cfgMap[key] = GtvTestUtils.decodeGtvStr(value)
        }

        return GtvFactory.gtv(cfgMap)
    }
}
