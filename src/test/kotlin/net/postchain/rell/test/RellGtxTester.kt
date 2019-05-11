package net.postchain.rell.test

import net.postchain.base.BaseEContext
import net.postchain.base.data.SQLDatabaseAccess
import net.postchain.core.UserMistake
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvDictionary
import net.postchain.gtv.GtvString
import net.postchain.gtx.GTXModule
import net.postchain.gtx.GTXSchemaManager
import net.postchain.rell.hexStringToByteArray
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
            val ctx = BaseEContext(conn, chainId, nodeId, SQLDatabaseAccess())
            GTXSchemaManager.initializeDB(ctx)
            gtxModule.initializeDB(ctx)
        }
    }

    fun chkQuery(code: String, expected: String) {
        chkQueryEx("query q() = $code;", "", expected)
    }

    fun chkQueryEx(code: String, args: String, expected: String) {
        val actual = callQueryEx(code, args)
        assertEquals(expected, actual)
    }

    private fun callQueryEx(code: String, args: String): String {
        return eval.eval {
            eval.wrapRt { init() }

            val argsExtra = if (args.isEmpty()) "" else ",$args"
            val argsStr = "{'type':'q'$argsExtra}"
            val argsGtv = GtvTestUtils.decodeGtvStr(argsStr.replace('\'', '"'))

            val moduleCode = moduleCode(code)
            val module = eval.wrapCt { createGtxModule(moduleCode) }

            val conn = getSqlConn()
            val ctx = BaseEContext(conn, chainId, nodeId, SQLDatabaseAccess())

            val res = module.query(ctx, "q", argsGtv)
            GtvTestUtils.gtvToStr(res)
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
        val factory = RellPostchainModuleFactory(false, wrapRtErrors)
        val moduleCfg = moduleConfig(moduleCode)
        val bcRidBytes = blockchainRID.hexStringToByteArray()
        val module = factory.makeModule(moduleCfg, bcRidBytes)
        return module
    }

    private fun moduleConfig(moduleCode: String): Gtv {
        val rellMap = mutableMapOf<String, Gtv>()
        rellMap["mainFile"] = GtvString(RellTestUtils.MAIN_FILE)

        val sourceCodes = files(moduleCode)
        rellMap["sources_v0.8"] = GtvDictionary(sourceCodes.mapValues { (_, v) -> GtvString(v) })

        if (moduleArgs != null) {
            rellMap["moduleArgs"] = GtvTestUtils.decodeGtvStr(moduleArgs!!)
        }

        val cfgMap = mutableMapOf<String, Gtv>()
        cfgMap["gtx"] = GtvDictionary(mapOf("rell" to GtvDictionary(rellMap)))
        for ((key, value) in extraModuleConfig) {
            cfgMap[key] = GtvTestUtils.decodeGtvStr(value)
        }

        return GtvDictionary(cfgMap)
    }
}
