package net.postchain.rell.test

import net.postchain.core.EContext
import net.postchain.core.UserMistake
import net.postchain.gtx.GTXModule
import net.postchain.gtx.GTXSchemaManager
import net.postchain.gtx.GTXValue
import net.postchain.rell.hexStringToByteArray
import net.postchain.rell.model.R_Module
import net.postchain.rell.module.RellPostchainModuleFactory
import net.postchain.rell.parser.C_Error
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlUtils
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class RellGtxTester(
        useSql: Boolean = true,
        classDefs: List<String> = listOf(),
        inserts: List<String> = listOf(),
        gtx: Boolean = false
): RellBaseTester(useSql, classDefs, inserts, gtx)
{
    var moduleArgs: String? = null
    var blockchainRID = "DEADBEEF"
    var chainID: Long = 995511
    var nodeID: Int = 3377

    override fun initSqlReset(conn: Connection, sqlExec: SqlExecutor, moduleCode: String, module: R_Module) {
        val module = createGtxModule(moduleCode)

        sqlExec.transaction {
            SqlUtils.dropAll(sqlExec)
            val ctx = EContext(conn, chainID, nodeID)
            GTXSchemaManager.initializeDB(ctx)
            module.initializeDB(ctx)
        }
    }

    fun chkQuery(code: String, expected: String) {
        chkQueryEx("query q() = $code;", "", expected)
    }

    fun chkQueryEx(code: String, args: String, expected: String) {
        init()

        val argsExtra = if (args.isEmpty()) "" else ",$args"
        val argsStr = "{'type':'q'$argsExtra}"
        val argsGtx = GtxTestUtils.decodeGtxStr(argsStr.replace('\'', '"'))

        val moduleCode = moduleCode(code)

        val actual = processGtxModule(moduleCode) { module ->
            val conn = getSqlConn()
            val ctx = EContext(conn, chainID, nodeID)

            val res = module.query(ctx, "q", argsGtx)
            GtxTestUtils.gtxToStr(res)
        }

        assertEquals(expected, actual)
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

    private fun processGtxModule(moduleCode: String, processor: (GTXModule) -> String): String {
        val module = try {
            createGtxModule(moduleCode)
        } catch (e: C_Error) {
            return "ct_err:" + e.code
        }
        return processor(module)
    }

    private fun createGtxModule(moduleCode: String): GTXModule {
        val moduleLoader = { path: String -> moduleCode }
        val factory = RellPostchainModuleFactory(moduleLoader)
        val moduleCfg = moduleConfig()
        val bcRidBytes = blockchainRID.hexStringToByteArray()
        val module = factory.makeModule(moduleCfg, bcRidBytes)
        return module
    }

    private fun moduleConfig(): GTXValue {
        val args = if (moduleArgs == null) "" else ",'rellModuleArgs':$moduleArgs"
        val moduleConfigStr = "{'gtx':{'rellSrcModule':'foo'$args}}"
        val str = moduleConfigStr.replace('\'', '"')
        val cfg = GtxTestUtils.decodeGtxStr(str)
        return cfg
    }
}
