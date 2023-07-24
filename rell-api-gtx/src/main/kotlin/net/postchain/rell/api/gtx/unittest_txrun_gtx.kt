/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.api.gtx

import net.postchain.base.BaseBlockchainContext
import net.postchain.base.BaseEContext
import net.postchain.base.configuration.BlockchainConfigurationData
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.core.*
import net.postchain.crypto.KeyPair
import net.postchain.crypto.PrivKey
import net.postchain.crypto.PubKey
import net.postchain.crypto.SigMaker
import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.GTXBlockchainConfigurationFactory
import net.postchain.gtx.GtxBuilder
import net.postchain.rell.api.base.RellApiBaseInternal
import net.postchain.rell.api.base.RellApiCompile
import net.postchain.rell.api.base.RellCliException
import net.postchain.rell.api.base.RellConfigGen
import net.postchain.rell.base.compiler.base.utils.C_SourceDir
import net.postchain.rell.base.lib.test.Lib_Test_Events
import net.postchain.rell.base.lib.test.RawTestTxValue
import net.postchain.rell.base.lib.test.Rt_TestBlockValue
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.utils.*
import net.postchain.rell.gtx.Rt_PostchainTxContext
import net.postchain.rell.gtx.Rt_PostchainTxContextFactory
import net.postchain.rell.module.RellPostchainModuleEnvironment
import java.sql.Connection

class Rt_PostchainUnitTestBlockRunner(
    private val keyPair: BytesKeyPair,
    private val runnerConfig: Rt_BlockRunnerConfig,
    private val runnerStrategy: Rt_BlockRunnerStrategy,
): Rt_UnitTestBlockRunner() {
    override fun runBlock(ctx: Rt_CallContext, block: Rt_TestBlockValue) {
        val gtvConfig = runnerStrategy.getGtvConfig()
        val bcData = GtvObjectMapper.fromGtv(gtvConfig, BlockchainConfigurationData::class)

        val bcCtx = getBlockchainContext(ctx)
        val bcConfigFactory: BlockchainConfigurationFactory = GTXBlockchainConfigurationFactory()
        val sigMaker = makeSigMaker()

        val txContextFactory = Rt_UnitTestPostchainTxContextFactory()
        val precompiledApp = runnerStrategy.getPrecompiledApp()
        val pcEnv = runnerConfig.makePostchainModuleEnvironment(ctx.globalCtx, txContextFactory, precompiledApp)

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

    private fun makeSigMaker(): SigMaker {
        val pubKey = PubKey(keyPair.pub.toByteArray())
        val privKey = PrivKey(keyPair.priv.toByteArray())
        return PostchainGtvUtils.cryptoSystem.buildSigMaker(KeyPair(pubKey, privKey))
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
        val dataBuilder = GtxBuilder(bcConfig.blockchainRid, signers, PostchainGtvUtils.cryptoSystem)

        for (op in tx.ops) {
            dataBuilder.addOperation(op.name.str(), *op.args.toTypedArray())
        }
        val sigBuilder = dataBuilder.finish()

        for (keyPair in tx.signers) {
            val pubKey = keyPair.pub.toByteArray()
            val privKey = keyPair.priv.toByteArray()
            val sigMaker = PostchainGtvUtils.cryptoSystem.buildSigMaker(pubKey, privKey)
            sigBuilder.sign(sigMaker)
        }

        return sigBuilder.buildGtx().encode()
    }

    private fun getBlockchainContext(ctx: Rt_CallContext): BlockchainContext {
        val bcRid = BlockchainRid(ctx.chainCtx.blockchainRid.toByteArray())
        val chainId = ctx.sqlCtx.mainChainMapping().chainId
        val nodeId = 0
        val nodeRid = "13".repeat(32).hexStringToByteArray()
        return BaseBlockchainContext(chainId, bcRid, nodeId, nodeRid)
    }

    private fun createEContext(con: Connection, bcCtx: BlockchainContext): EContext {
        val dbAccess: DatabaseAccess = PostgreSQLDatabaseAccess()
        return BaseEContext(con, bcCtx.chainID, dbAccess)
    }
}

abstract class Rt_BlockRunnerStrategy {
    abstract fun getGtvConfig(): Gtv
    abstract fun getPrecompiledApp(): RellGtxModuleApp?
}

class Rt_StaticBlockRunnerStrategy(private val gtvConfig: Gtv): Rt_BlockRunnerStrategy() {
    override fun getGtvConfig() = gtvConfig
    override fun getPrecompiledApp() = null
}

class Rt_DynamicBlockRunnerStrategy(
    private val sourceDir: C_SourceDir,
    private val keyPair: BytesKeyPair,
    modules: List<R_ModuleName>?,
    private val compileConfig: RellApiCompile.Config,
): Rt_BlockRunnerStrategy() {
    private val modules = modules?.toImmList()

    private val lazyConfig: Pair<Gtv, RellGtxModuleApp> by lazy {
        try {
            createConfig()
        } catch (e: RellCliException) {
            var msg = "Gtv config generation failed"
            e.message?.let { msg = "msg: $it" }
            throw Rt_Exception.common("block_runner", msg)
        }
    }

    override fun getGtvConfig(): Gtv {
        return lazyConfig.first
    }

    override fun getPrecompiledApp(): RellGtxModuleApp {
        return lazyConfig.second
    }

    private fun createConfig(): Pair<Gtv, RellGtxModuleApp> {
        val pubKey0 = keyPair.pub.toByteArray()
        val template = RellApiGtxUtils.genBlockchainConfigTemplateNoRell(pubKey0)
        val (rellNode, modApp) = RellApiBaseInternal.compileGtvEx(compileConfig, sourceDir, modules)
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
        precompiledApp: RellGtxModuleApp?,
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
    }
}

private class Rt_UnitTestPostchainTxContextFactory: Rt_PostchainTxContextFactory() {
    private val events = mutableListOf<Rt_Value>()

    override fun createTxContext(eContext: TxEContext): Rt_PostchainTxContext {
        return Rt_UnitTestPostchainTxContext()
    }

    fun getEvents() = events.toImmList()

    private inner class Rt_UnitTestPostchainTxContext: Rt_PostchainTxContext() {
        override fun emitEvent(type: String, data: Gtv) {
            val rtType = Rt_TextValue(type)
            val rtData = Rt_GtvValue(data)
            val v = Rt_TupleValue(Lib_Test_Events.EVENT_TUPLE_TYPE, immListOf(rtType, rtData))
            events.add(v)
        }
    }
}
