package net.postchain.rell.runtime

import net.postchain.base.BaseBlockchainConfigurationData
import net.postchain.base.BaseBlockchainContext
import net.postchain.base.BaseEContext
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.base.secp256k1_derivePubKey
import net.postchain.common.hexStringToByteArray
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.BlockchainConfigurationFactory
import net.postchain.core.BlockchainContext
import net.postchain.core.EContext
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvDictionary
import net.postchain.gtv.GtvEncoder
import net.postchain.gtx.GTXBlockchainConfigurationFactory
import net.postchain.rell.RellConfigGen
import net.postchain.rell.module.RellPostchainModuleEnvironment
import net.postchain.rell.tools.RunPostchainApp
import net.postchain.rell.utils.PostchainUtils
import java.sql.Connection

object PostchainBlockRunner {
    fun runBlock(ctx: Rt_CallContext, block: Rt_GtxBlockValue) {
        val (privKey, pubKey) = getPrivPubKeys()
        val gtvConfig = getGtvConfig(ctx, pubKey)
        val bcCtx = getBlockchainContext(ctx)

        RellPostchainModuleEnvironment.set(ctx.globalCtx.pcModuleEnv) {
            val sigMaker = PostchainUtils.cryptoSystem.buildSigMaker(pubKey, privKey)
            val bcData = BaseBlockchainConfigurationData(gtvConfig as GtvDictionary, bcCtx, sigMaker)
            val bcConfigFactory: BlockchainConfigurationFactory = GTXBlockchainConfigurationFactory()
            val bcConfig = bcConfigFactory.makeBlockchainConfiguration(bcData)

            ctx.exeCtx.sqlExec.connection { con ->
                val eCtx = createEContext(con, bcCtx)
                processBlock(bcConfig, eCtx, block)
            }
        }
    }

    private fun processBlock(bcConfig: BlockchainConfiguration, eCtx: EContext, block: Rt_GtxBlockValue) {
        val txFactory = bcConfig.getTransactionFactory()

        bcConfig.initializeDB(eCtx)
        val blockBuilder = bcConfig.makeBlockBuilder(eCtx)

        blockBuilder.begin(null)

        for (tx in block.txs) {
            val txGtv = tx.type().rtToGtv(tx, false)
            val txBytes = GtvEncoder.encodeGtv(txGtv)
            val psTx = txFactory.decodeTransaction(txBytes)
            blockBuilder.appendTransaction(psTx)
        }

        blockBuilder.finalizeBlock()
        val bwb = blockBuilder.getBlockWitnessBuilder()!!
        val bw = bwb.getWitness()
        blockBuilder.commit(bw)
    }

    private fun getBlockchainContext(ctx: Rt_CallContext): BlockchainContext {
        val bcRid = ctx.globalCtx.chainCtx.blockchainRid
        val chainId = ctx.appCtx.sqlCtx.mainChainMapping.chainId
        val nodeId = 0
        val nodeRid = "13".repeat(32).hexStringToByteArray()
        return BaseBlockchainContext(bcRid, nodeId, chainId, nodeRid)
    }

    private fun getGtvConfig(ctx: Rt_CallContext, pubKey: ByteArray): Gtv {
        val configGen = RellConfigGen.create(ctx.appCtx.sourceDir, ctx.appCtx.modules.toList())
        val configTemplate = RunPostchainApp.genBlockchainConfigTemplate(pubKey, false)
        return configGen.makeConfig(configTemplate)
    }

    private fun createEContext(con: Connection, bcCtx: BlockchainContext): EContext {
        val dbAccess: DatabaseAccess = PostgreSQLDatabaseAccess()
        return BaseEContext(con, bcCtx.chainID, bcCtx.nodeID, dbAccess)
    }

    private fun getPrivPubKeys(): Pair<ByteArray, ByteArray> {
        val privKey = "42".repeat(8).hexStringToByteArray()
        val pubKey = secp256k1_derivePubKey(privKey)
        return Pair(privKey, pubKey)
    }
}
