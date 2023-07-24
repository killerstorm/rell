/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.gtx

import net.postchain.core.TxEContext
import net.postchain.gtv.Gtv
import net.postchain.gtx.data.OpData
import net.postchain.rell.base.lib.Lib_OpContext
import net.postchain.rell.base.runtime.Rt_OpContext
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.utils.Bytes
import net.postchain.rell.base.utils.toImmList

abstract class Rt_PostchainTxContext {
    abstract fun emitEvent(type: String, data: Gtv)
}

abstract class Rt_PostchainTxContextFactory {
    abstract fun createTxContext(eContext: TxEContext): Rt_PostchainTxContext
}

object Rt_DefaultPostchainTxContextFactory: Rt_PostchainTxContextFactory() {
    override fun createTxContext(eContext: TxEContext): Rt_PostchainTxContext {
        return Rt_DefaultPostchainPostchainTxContext(eContext)
    }

    private class Rt_DefaultPostchainPostchainTxContext(private val txCtx: TxEContext): Rt_PostchainTxContext() {
        override fun emitEvent(type: String, data: Gtv) {
            txCtx.emitEvent(type, data)
        }
    }
}

class Rt_PostchainOpContext(
    private val txCtx: Rt_PostchainTxContext,
    private val lastBlockTime: Long,
    private val transactionIid: Long,
    private val blockHeight: Long,
    private val opIndex: Int,
    signers: List<Bytes>,
    allOperations: List<OpData>
): Rt_OpContext() {
    private val signers = signers.toImmList()
    private val allOperations = allOperations.toImmList()

    override fun exists() = true
    override fun lastBlockTime() = lastBlockTime
    override fun transactionIid() = transactionIid
    override fun blockHeight() = blockHeight
    override fun opIndex() = opIndex
    override fun isSigner(pubKey: Bytes) = pubKey in signers
    override fun signers() = signers

    override fun allOperations(): List<Rt_Value> {
        return allOperations.map { op ->
            Lib_OpContext.gtxTransactionStructValue(op.opName, op.args.asList())
        }
    }

    override fun emitEvent(type: String, data: Gtv) {
        txCtx.emitEvent(type, data)
    }
}
