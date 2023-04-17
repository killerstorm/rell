/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.test

import net.postchain.base.BaseBlockchainContext
import net.postchain.base.BaseEContext
import net.postchain.base.configuration.BlockchainConfigurationData
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.common.hexStringToByteArray
import net.postchain.core.*
import net.postchain.crypto.*
import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.GTXBlockchainConfigurationFactory
import net.postchain.gtx.GtxBuilder
import net.postchain.rell.RellConfigGen
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.model.*
import net.postchain.rell.module.RellPostchainModuleApp
import net.postchain.rell.module.RellPostchainModuleEnvironment
import net.postchain.rell.runtime.*
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.tools.RunPostchainApp
import net.postchain.rell.utils.BytesKeyPair
import net.postchain.rell.utils.PostchainUtils
import net.postchain.rell.utils.cli.RellCliCompileConfig
import net.postchain.rell.utils.cli.RellCliException
import net.postchain.rell.utils.cli.RellCliInternalApi
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList
import java.sql.Connection

object UnitTestBlockRunner {
    private val TEST_KEYPAIR: BytesKeyPair = let {
        val privKey = "42".repeat(32).hexStringToByteArray()
        val pubKey = secp256k1_derivePubKey(privKey)
        BytesKeyPair(privKey, pubKey)
    }

    fun runBlock(ctx: Rt_CallContext, block: Rt_TestBlockValue) {
        val strategy = ctx.appCtx.blockRunnerStrategy
        val gtvConfig = strategy.getGtvConfig()
        val bcData = GtvObjectMapper.fromGtv(gtvConfig, BlockchainConfigurationData::class)

        val bcCtx = getBlockchainContext(ctx)
        val bcConfigFactory: BlockchainConfigurationFactory = GTXBlockchainConfigurationFactory()
        val sigMaker = makeSigMaker(strategy)

        val blockRunnerCfg = ctx.appCtx.blockRunnerConfig
        val txContextFactory = Rt_UnitTestTxContextFactory()
        val precompiledApp = strategy.getPrecompiledApp()
        val pcEnv = blockRunnerCfg.makePostchainModuleEnvironment(ctx.globalCtx, txContextFactory, precompiledApp)

        try {
            RellPostchainModuleEnvironment.set(pcEnv) {
                ctx.exeCtx.sqlExec.connection { con ->
                    val eCtx = createEContext(con, bcCtx)
                    val bcConfig = bcConfigFactory.makeBlockchainConfiguration(bcData, bcCtx, sigMaker, eCtx)
                    withSavepoint(con) {
                        processBlock(bcConfig, eCtx, block)
                    }
                }
            }
        } finally {
            val events = txContextFactory.getEvents()
            ctx.exeCtx.emittedEvents = events
        }
    }

    private fun makeSigMaker(strategy: Rt_BlockRunnerStrategy): SigMaker {
        val keyPair = strategy.getKeyPair()
        val pubKey = PubKey(keyPair.pub.toByteArray())
        val privKey = PrivKey(keyPair.priv.toByteArray())
        return PostchainUtils.cryptoSystem.buildSigMaker(KeyPair(pubKey, privKey))
    }

    private fun withSavepoint(con: Connection, code: () -> Unit) {
        if (con.autoCommit) {
            con.autoCommit = false
            try {
                withSavepoint0(con, code)
            } finally {
                con.autoCommit = true
            }
        } else {
            withSavepoint0(con, code)
        }
    }

    private fun withSavepoint0(con: Connection, code: () -> Unit) {
        val savepoint = con.setSavepoint("withSavepoint_${System.nanoTime()}")
        try {
            code()
            con.releaseSavepoint(savepoint)
        } catch (e: Throwable) {
            con.rollback(savepoint)
            throw e
        }
    }

    private fun processBlock(bcConfig: BlockchainConfiguration, eCtx: EContext, block: Rt_TestBlockValue) {
        val txFactory = bcConfig.getTransactionFactory()

        val blockBuilder = bcConfig.makeBlockBuilder(eCtx)

        blockBuilder.begin(null)

        for (tx in block.txs()) {
            val txBytes = prepareTxBytes(bcConfig, tx)
            val psTx = txFactory.decodeTransaction(txBytes)
            blockBuilder.appendTransaction(psTx)
        }

        blockBuilder.finalizeBlock()
        val bwb = blockBuilder.getBlockWitnessBuilder()!!
        val bw = bwb.getWitness()
        blockBuilder.commit(bw)
    }

    private fun prepareTxBytes(bcConfig: BlockchainConfiguration, tx: RawTestTxValue): ByteArray {
        val signers = tx.signers.map { it.pub.toByteArray() }
        val dataBuilder = GtxBuilder(bcConfig.blockchainRid, signers, PostchainUtils.cryptoSystem)

        for (op in tx.ops) {
            dataBuilder.addOperation(op.name.str(), *op.args.toTypedArray())
        }
        val sigBuilder = dataBuilder.finish()

        for (keyPair in tx.signers) {
            val pubKey = keyPair.pub.toByteArray()
            val privKey = keyPair.priv.toByteArray()
            val sigMaker = PostchainUtils.cryptoSystem.buildSigMaker(pubKey, privKey)
            sigBuilder.sign(sigMaker)
        }

        return sigBuilder.buildGtx().encode()
    }

    private fun getBlockchainContext(ctx: Rt_CallContext): BlockchainContext {
        val bcRid = ctx.chainCtx.blockchainRid
        val chainId = ctx.sqlCtx.mainChainMapping().chainId
        val nodeId = 0
        val nodeRid = "13".repeat(32).hexStringToByteArray()
        return BaseBlockchainContext(chainId, bcRid, nodeId, nodeRid)
    }

    private fun createEContext(con: Connection, bcCtx: BlockchainContext): EContext {
        val dbAccess: DatabaseAccess = PostgreSQLDatabaseAccess()
        return BaseEContext(con, bcCtx.chainID, dbAccess)
    }

    fun getTestKeyPair(): BytesKeyPair {
        return TEST_KEYPAIR
    }
}

abstract class Rt_BlockRunnerStrategy {
    abstract fun getKeyPair(): BytesKeyPair
    abstract fun getGtvConfig(): Gtv
    abstract fun getPrecompiledApp(): RellPostchainModuleApp?
}

class Rt_StaticBlockRunnerStrategy(
    private val gtvConfig: Gtv,
    private val keyPair: BytesKeyPair
): Rt_BlockRunnerStrategy() {
    override fun getGtvConfig() = gtvConfig
    override fun getKeyPair() = keyPair
    override fun getPrecompiledApp() = null
}

object Rt_UnsupportedBlockRunnerStrategy: Rt_BlockRunnerStrategy() {
    private const val errMsg = "Block execution not supported"
    override fun getGtvConfig() = throw Rt_Utils.errNotSupported(errMsg)
    override fun getKeyPair() = throw Rt_Utils.errNotSupported(errMsg)
    override fun getPrecompiledApp() = throw Rt_Utils.errNotSupported(errMsg)
}

class Rt_DynamicBlockRunnerStrategy(
        private val sourceDir: C_SourceDir,
        private val keyPair: BytesKeyPair,
        modules: List<R_ModuleName>?,
        private val compileConfig: RellCliCompileConfig,
): Rt_BlockRunnerStrategy() {
    private val modules = modules?.toImmList()

    private val lazyConfig: Pair<Gtv, RellPostchainModuleApp> by lazy {
        try {
            createConfig()
        } catch (e: RellCliException) {
            var msg = "Gtv config generation failed"
            e.message?.let { msg = "msg: $it" }
            throw Rt_Exception.common("block_runner", msg)
        }
    }

    override fun getKeyPair() = keyPair

    override fun getGtvConfig(): Gtv {
        return lazyConfig.first
    }

    override fun getPrecompiledApp(): RellPostchainModuleApp {
        return lazyConfig.second
    }

    private fun createConfig(): Pair<Gtv, RellPostchainModuleApp> {
        val pubKey0 = keyPair.pub.toByteArray()
        val template = RunPostchainApp.genBlockchainConfigTemplateNoRell(pubKey0)
        val (rellNode, modApp) = RellCliInternalApi.compileGtvEx(compileConfig, sourceDir, modules)
        val resNode = RellConfigGen.makeConfig(template, rellNode)
        return resNode to modApp
    }
}

class Rt_BlockRunnerConfig(
    private val wrapCtErrors: Boolean = DEFENV.wrapCtErrors,
    private val wrapRtErrors: Boolean = DEFENV.wrapRtErrors,
    private val forceTypeCheck: Boolean = DEFENV.forceTypeCheck,
    private val sqlLog: Boolean = DEFENV.sqlLog,
    private val dbInitLogLevel: Int = DEFENV.dbInitLogLevel,
) {
    fun makePostchainModuleEnvironment(
        globalCtx: Rt_GlobalContext,
        txContextFactory: Rt_PostchainTxContextFactory,
        precompiledApp: RellPostchainModuleApp?,
    ): RellPostchainModuleEnvironment {
        return RellPostchainModuleEnvironment(
            outPrinter = globalCtx.outPrinter,
            logPrinter = globalCtx.logPrinter,
            combinedPrinter = Rt_LogPrinter(),
            copyOutputToPrinter = false,
            wrapCtErrors = wrapCtErrors,
            wrapRtErrors = wrapRtErrors,
            forceTypeCheck = forceTypeCheck,
            dbInitEnabled = false, // Database must be initialized once, at start, when running unit tests.
            dbInitLogLevel = dbInitLogLevel,
            sqlLog = sqlLog,
            fallbackModules = immListOf(),
            precompiledApp = precompiledApp,
            txContextFactory = txContextFactory,
        )
    }

    companion object {
        private val DEFENV = RellPostchainModuleEnvironment.DEFAULT

        val EVENT_TYPE: R_Type = Rt_UnitTestTxContextFactory.EVENT_TUPLE_TYPE
    }
}

private class Rt_UnitTestTxContextFactory: Rt_PostchainTxContextFactory() {
    private val events = mutableListOf<Rt_Value>()

    override fun createTxContext(eContext: TxEContext): Rt_TxContext {
        return Rt_UnitTestTxContext()
    }

    fun getEvents() = events.toImmList()

    private inner class Rt_UnitTestTxContext: Rt_TxContext() {
        override fun emitEvent(type: String, data: Gtv) {
            val rtType = Rt_TextValue(type)
            val rtData = Rt_GtvValue(data)
            val v = Rt_TupleValue(EVENT_TUPLE_TYPE, immListOf(rtType, rtData))
            events.add(v)
        }
    }

    companion object {
        val EVENT_TUPLE_TYPE = R_TupleType.create(R_TextType, R_GtvType)
    }
}
