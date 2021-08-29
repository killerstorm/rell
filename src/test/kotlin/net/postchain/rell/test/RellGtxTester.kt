/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.test

import net.postchain.base.*
import net.postchain.common.hexStringToByteArray
import net.postchain.core.*
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory
import net.postchain.gtv.GtvString
import net.postchain.gtv.GtvType
import net.postchain.gtx.ExtOpData
import net.postchain.gtx.GTXModule
import net.postchain.gtx.GTXSchemaManager
import net.postchain.rell.model.R_App
import net.postchain.rell.module.RellPostchainModuleEnvironment
import net.postchain.rell.module.RellPostchainModuleFactory
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.utils.PostchainUtils
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class RellGtxTester(
        tstCtx: RellTestContext,
        entityDefs: List<String> = listOf(),
        inserts: List<String> = listOf(),
        gtv: Boolean = false,
        chainId: Long = 995511
): RellBaseTester(tstCtx, entityDefs, inserts, gtv) {
    var wrapRtErrors = true
    val extraModuleConfig = mutableMapOf<String, String>()
    var nodeId: Int = 3377
    var modules: List<String>? = listOf("")
    var configTemplate: String = getDefaultConfigTemplate()

    init {
        super.chainId = chainId
    }

    override fun initSqlReset(sqlExec: SqlExecutor, moduleCode: String, app: R_App) {
        val bRid = BlockchainRid(blockchainRid.hexStringToByteArray())

        val gtxModule = createGtxModule(moduleCode)

        val dbAccess = PostchainUtils.createDatabaseAccess()
        sqlExec.connection { con ->
            val ctx = BaseEContext(con, chainId, nodeId, dbAccess)
            dbAccess.initializeBlockchain(ctx, bRid)
            GTXSchemaManager.initializeDB(ctx)
            gtxModule.initializeDB(ctx)
        }
    }

    override fun chkEx(code: String, expected: String) {
        chkQueryEx("query q() $code", "", expected)
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

            withEContext(false) { ctx ->
                val res = eval.wrapRt {
                    module.query(ctx, name, queryGtv)
                }
                GtvTestUtils.gtvToStr(res)
            }
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

            val dummyEventSink = object : TxEventSink {
                override fun processEmittedEvent(ctxt: TxEContext, type: String, data: Gtv) {
                    TODO("Not yet implemented")
                }
            }

            val res = withEContext(true) { ctx ->
                val blkCtx = BaseBlockEContext(ctx, 0, 0, System.currentTimeMillis(), mapOf(), dummyEventSink)
                val bcRid = PostchainUtils.hexToRid(blockchainRid)
                val opData = ExtOpData(name, 0, args.toTypedArray(), bcRid, arrayOf(), arrayOf())
                val transactor = module.makeTransactor(opData)
                check(transactor.isCorrect())

                val tx = TransactorTransaction(transactor)
                val txCtx = BaseTxEContext(blkCtx, 0, tx)

                transactor.apply(txCtx)
            }

            check(res)
            "OK"
        }
    }

    private fun <T> withEContext(tx: Boolean, code: (EContext) -> T): T {
        init()
        val res = tstCtx.sqlMgr().execute(tx) { sqlExec ->
            sqlExec.connection { con ->
                val dbAccess = PostchainUtils.createDatabaseAccess()
                val ctx = BaseEContext(con, chainId, nodeId, dbAccess)
                code(ctx)
            }
        }
        return res
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
        val env = RellPostchainModuleEnvironment(
                outPrinter = outPrinter,
                logPrinter = logPrinter,
                wrapCtErrors = false,
                wrapRtErrors = wrapRtErrors,
                forceTypeCheck = true
        )
        val factory = RellPostchainModuleFactory(env)

        val moduleCfg = getModuleConfig(moduleCode)
        val bcRid = PostchainUtils.hexToRid(blockchainRid)
        val module = factory.makeModule(moduleCfg, bcRid)
        return module
    }

    fun getModuleConfig(moduleCode: String): Gtv {
        val sourceCodes = files(moduleCode)
        val modArgs = getModuleArgs()
        val parts = ModuleConfigParts(modules, sourceCodes, modArgs, extraModuleConfig)
        val templateGtv = GtvTestUtils.strToGtv(configTemplate)
        return getModuleConfig(parts, templateGtv)
    }

    private fun getModuleConfig(parts: ModuleConfigParts, template: Gtv): Gtv {
        val v = makeModuleConfigNode(parts, template, true)
        return v ?: GtvFactory.gtv(mapOf())
    }

    private fun makeModuleConfigNode(parts: ModuleConfigParts, tpl: Gtv, root: Boolean): Gtv? {
        return when (tpl.type) {
            GtvType.NULL, GtvType.BYTEARRAY, GtvType.INTEGER -> tpl
            GtvType.ARRAY -> GtvFactory.gtv(tpl.asArray().mapNotNull { makeModuleConfigNode(parts, it, false) })
            GtvType.DICT -> {
                val map = tpl.asDict().entries
                        .map { it.key to makeModuleConfigNode(parts, it.value, false) }
                        .filter { it.second != null }
                        .map { it.first to it.second!! }
                        .toMap().toMutableMap()
                if (root) {
                    map.putAll(parts.extraModuleConfig.mapValues { GtvTestUtils.decodeGtvStr(it.value) })
                }
                GtvFactory.gtv(map)
            }
            GtvType.STRING -> {
                val s = tpl.asString()
                when (s) {
                    "{MODULES}" -> {
                        if (parts.modules == null) null else GtvFactory.gtv(parts.modules.map { GtvFactory.gtv(it) })
                    }
                    "{SOURCES}" -> GtvFactory.gtv(parts.sourceCodes.mapValues { (_, v) -> GtvString(v) })
                    "{MODULE_ARGS}" -> moduleArgsToGtv(parts.moduleArgs)
                    else -> tpl
                }
            }
        }
    }

    private fun getDefaultConfigTemplate(): String {
        val v = RellTestUtils.RELL_VER
        return "{'gtx':{'rell':{'modules':'{MODULES}','sources':'{SOURCES}','version':'$v','moduleArgs':'{MODULE_ARGS}'}}}"
    }

    private class TransactorTransaction(val transactor: Transactor): Transaction {
        override fun apply(ctx: TxEContext): Boolean = transactor.apply(ctx)
        override fun isCorrect(): Boolean = transactor.isCorrect()
        override fun isSpecial(): Boolean = transactor.isSpecial()

        // TODO To properly support following methods, test transactions execution shall be done in a different way.
        override fun getHash(): ByteArray = TODO()
        override fun getRID(): ByteArray = TODO()
        override fun getRawData(): ByteArray = TODO()
    }

    private class ModuleConfigParts(
            val modules: List<String>?,
            val sourceCodes: Map<String, String>,
            val moduleArgs: Map<String, String>,
            val extraModuleConfig: Map<String, String>
    )

    companion object {
        fun moduleArgsToGtv(moduleArgs: Map<String, String>): Gtv {
            return GtvFactory.gtv(moduleArgs.mapValues { (_, v) -> GtvTestUtils.decodeGtvStr(v) })
        }
    }
}
