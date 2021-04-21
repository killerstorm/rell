package net.postchain.rell.lib

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*

object C_Lib_OpContext {
    val NAME = "op_context"

    private val NAMESPACE_FNS = C_GlobalFuncBuilder()
            .add("get_signers", R_ListType(R_ByteArrayType), listOf(), R_Lib_OpContext.GetSigners)
            .add("is_signer", R_BooleanType, listOf(R_ByteArrayType), R_SysFn_Crypto.IsSigner)
            .build()

    val NAMESPACE = C_LibUtils.makeNs(
            NAMESPACE_FNS,
            "last_block_time" to Value_LastBlockTime,
            "block_height" to Value_BlockHeight,
            "transaction" to Value_Transaction
    )

    private val TRANSACTION_FN = "$NAME.transaction"

    fun transactionExpr(ctx: C_NamespaceValueContext, pos: S_Pos): R_Expr {
        val type = ctx.modCtx.sysDefs.transactionEntity.type
        return C_Utils.createSysCallExpr(type, R_Lib_OpContext.Transaction(type), listOf(), pos, TRANSACTION_FN)
    }

    private fun checkCtx(ctx: C_NamespaceValueContext, name: List<S_Name>) {
        val dt = ctx.defCtx.definitionType
        if (dt != C_DefinitionType.OPERATION && dt != C_DefinitionType.FUNCTION && dt != C_DefinitionType.ENTITY) {
            throw C_Error.stop(name[0].pos, "op_ctx_noop", "Can access '$NAME' only in an operation, function or entity")
        }
    }

    private object Value_LastBlockTime: C_NamespaceValue_RExpr() {
        override fun toExpr0(ctx: C_NamespaceValueContext, name: List<S_Name>): R_Expr {
            checkCtx(ctx, name)
            return C_Utils.createSysCallExpr(R_IntegerType, R_Lib_OpContext.LastBlockTime, listOf(), name)
        }
    }

    private object Value_BlockHeight: C_NamespaceValue_RExpr() {
        override fun toExpr0(ctx: C_NamespaceValueContext, name: List<S_Name>): R_Expr {
            checkCtx(ctx, name)
            return C_Utils.createSysCallExpr(R_IntegerType, R_Lib_OpContext.BlockHeight, listOf(), name)
        }
    }

    private object Value_Transaction: C_NamespaceValue_RExpr() {
        override fun toExpr0(ctx: C_NamespaceValueContext, name: List<S_Name>): R_Expr {
            checkCtx(ctx, name)
            return transactionExpr(ctx, name[0].pos)
        }
    }
}

private object R_Lib_OpContext {
    abstract class BaseFn(private val name: String): R_SysFunction() {
        abstract fun call(opCtx: Rt_OpContext): Rt_Value

        final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            check(args.size == 0)
            val opCtx = getOpContext(ctx, name)
            return call(opCtx)
        }
    }

    object LastBlockTime: BaseFn("last_block_time") {
        override fun call(opCtx: Rt_OpContext) = Rt_IntValue(opCtx.lastBlockTime)
    }

    class Transaction(private val type: R_EntityType): BaseFn("transaction") {
        override fun call(opCtx: Rt_OpContext) = Rt_EntityValue(type, opCtx.transactionIid)
    }

    object BlockHeight: BaseFn("block_height") {
        override fun call(opCtx: Rt_OpContext) = Rt_IntValue(opCtx.blockHeight)
    }

    object GetSigners: R_SysFunctionEx_0() {
        private val LIST_TYPE = R_ListType(R_ByteArrayType)

        override fun call(ctx: Rt_CallContext): Rt_Value {
            val opCtx = getOpContext(ctx, "get_signers")
            val elements = opCtx.signers.map { Rt_ByteArrayValue(it) as Rt_Value }.toMutableList()
            return Rt_ListValue(LIST_TYPE, elements)
        }
    }

    private fun getOpContext(ctx: Rt_CallContext, fnName: String): Rt_OpContext {
        val opCtx = ctx.globalCtx.opCtx
        return opCtx ?: throw Rt_Error("fn:op_context.$fnName:noop", "Operation context not available")
    }
}
