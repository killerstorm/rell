/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.test

import net.postchain.base.BaseBlockchainContext
import net.postchain.base.BaseEContext
import net.postchain.base.configuration.BlockchainConfigurationData
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.crypto.secp256k1_derivePubKey
import net.postchain.common.hexStringToByteArray
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.BlockchainConfigurationFactory
import net.postchain.core.BlockchainContext
import net.postchain.core.EContext
import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.GTXBlockchainConfigurationFactory
import net.postchain.gtx.GtxBuilder
import net.postchain.rell.RellConfigGen
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.module.RellPostchainModuleEnvironment
import net.postchain.rell.runtime.Rt_BlockRunnerStrategy
import net.postchain.rell.runtime.Rt_CallContext
import net.postchain.rell.runtime.Rt_Error
import net.postchain.rell.sql.SqlInitLogging
import net.postchain.rell.tools.RunPostchainApp
import net.postchain.rell.utils.*
import java.sql.Connection

object UnitTestBlockRunner {
    private val TEST_KEY_PAIR: BytesKeyPair by lazy {
        val privKey = "42".repeat(32).hexStringToByteArray()
        val pubKey = secp256k1_derivePubKey(privKey)
        BytesKeyPair(privKey, pubKey)
    }

    fun runBlock(ctx: Rt_CallContext, block: Rt_TestBlockValue) {
        val strategy = ctx.appCtx.blockRunnerStrategy
        val keyPair = strategy.getKeyPair()
        val gtvConfig = strategy.createGtvConfig()

        val bcCtx = getBlockchainContext(ctx)

        RellPostchainModuleEnvironment.set(ctx.globalCtx.pcModuleEnv) {
            val pubKey = keyPair.pub.toByteArray()
            val privKey = keyPair.priv.toByteArray()
            val sigMaker = PostchainUtils.cryptoSystem.buildSigMaker(pubKey, privKey)

            val bcData = GtvObjectMapper.fromGtv(gtvConfig, BlockchainConfigurationData::class, mapOf("partialContext" to bcCtx, "sigmaker" to sigMaker))
            val bcConfigFactory: BlockchainConfigurationFactory = GTXBlockchainConfigurationFactory()
            ctx.exeCtx.sqlExec.connection { con ->
                val eCtx = createEContext(con, bcCtx)
                val bcConfig = bcConfigFactory.makeBlockchainConfiguration(bcData, eCtx)
                withSavepoint(con) {
                    processBlock(bcConfig, eCtx, block)
                }
            }
        }
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
        return BaseBlockchainContext(bcRid, nodeId, chainId, nodeRid)
    }

    fun makeGtvConfig(cliEnv: RellCliEnv, sourceDir: C_SourceDir, modules: List<R_ModuleName>, pubKey: Bytes33): Gtv {
        val configGen = RellConfigGen.create(cliEnv, sourceDir, modules)
        val pubKey0 = pubKey.toByteArray()
        val configTemplate = RunPostchainApp.genBlockchainConfigTemplate(pubKey0, false, SqlInitLogging.LOG_NONE)
        return configGen.makeConfig(configTemplate)
    }

    private fun createEContext(con: Connection, bcCtx: BlockchainContext): EContext {
        val dbAccess: DatabaseAccess = PostgreSQLDatabaseAccess()
        return BaseEContext(con, bcCtx.chainID, dbAccess)
    }

    fun getTestKeyPair(): BytesKeyPair {
        return TEST_KEY_PAIR
    }
}

class Rt_DynamicBlockRunnerStrategy(
        private val sourceDir: C_SourceDir,
        modules: List<R_ModuleName>,
        private val keyPair: BytesKeyPair
): Rt_BlockRunnerStrategy() {
    private val modules = modules.toImmList()

    override fun createGtvConfig(): Gtv {
        return UnitTestBlockRunner.makeGtvConfig(BlockRunnerRellCliEnv, sourceDir, modules, keyPair.pub)
    }

    override fun getKeyPair() = keyPair

    private object BlockRunnerRellCliEnv: RellCliEnv() {
        override fun print(msg: String, err: Boolean) {
            // TODO output the message somewhere
        }

        override fun exit(status: Int): Nothing {
            throw Rt_Error("block_runner", "Gtv config generation failed")
        }
    }
}
