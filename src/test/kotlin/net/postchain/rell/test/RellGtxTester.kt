package net.postchain.rell.test

import net.postchain.core.EContext
import net.postchain.core.UserMistake
import net.postchain.gtx.GTXModule
import net.postchain.gtx.GTXSchemaManager
import net.postchain.gtx.GTXValue
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
        gtx: Boolean = false
): RellBaseTester(tstCtx, classDefs, inserts, gtx)
{
    var translateRtError = true
    var moduleArgs: String? = null
    var extraModuleConfig: String? = null
    var blockchainRID = "DEADBEEF"
    var nodeId: Int = 3377

    init {
        chainId = 995511
    }

    override fun initSqlReset(conn: Connection, sqlExec: SqlExecutor, moduleCode: String, module: R_Module) {
        val gtxModule = createGtxModule(moduleCode)

        sqlExec.transaction {
            val ctx = EContext(conn, chainId, nodeId)
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
            val argsGtx = GtxTestUtils.decodeGtxStr(argsStr.replace('\'', '"'))

            val moduleCode = moduleCode(code)
            val module = eval.wrapCt { createGtxModule(moduleCode) }

            val conn = getSqlConn()
            val ctx = EContext(conn, chainId, nodeId)

            val res = module.query(ctx, "q", argsGtx)
            GtxTestUtils.gtxToStr(res)
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
        val moduleLoader = { path: String -> moduleCode }
        val factory = RellPostchainModuleFactory(moduleLoader, translateRtError)
        val moduleCfg = moduleConfig()
        val bcRidBytes = blockchainRID.hexStringToByteArray()
        val module = factory.makeModule(moduleCfg, bcRidBytes)
        return module
    }

    private fun moduleConfig(): GTXValue {
        val args = if (moduleArgs == null) "" else ",'rellModuleArgs':$moduleArgs"
        val extra = if (extraModuleConfig == null) "" else ",$extraModuleConfig"
        val moduleConfigStr = "{'gtx':{'rellSrcModule':'foo'$args}$extra}"
        val str = moduleConfigStr.replace('\'', '"')
        val cfg = GtxTestUtils.decodeGtxStr(str)
        return cfg
    }
}
