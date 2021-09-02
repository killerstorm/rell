package net.postchain.rell.lib

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.immListOf
import java.util.*

object C_Lib_OpContext {
    const val NAMESPACE_NAME = "op_context"

    private val GTX_OPERATION_STRUCT = C_Utils.createSysStruct(
            "gtx_operation",
            C_SysAttribute("name", R_TextType),
            C_SysAttribute("args", R_ListType(R_GtvType))
    )

    private val GTX_TRANSACTION_BODY_STRUCT = C_Utils.createSysStruct(
            "gtx_transaction_body",
            C_SysAttribute("blockchain_rid", R_ByteArrayType),
            C_SysAttribute("operations", R_ListType(GTX_OPERATION_STRUCT.type)),
            C_SysAttribute("signers", R_ListType(R_GtvType))
    )

    private val GTX_TRANSACTION_STRUCT = C_Utils.createSysStruct(
            "gtx_transaction",
            C_SysAttribute("body", GTX_TRANSACTION_BODY_STRUCT.type),
            C_SysAttribute("signatures", R_ListType(R_GtvType))
    )

    val GLOBAL_STRUCTS = immListOf(GTX_OPERATION_STRUCT, GTX_TRANSACTION_BODY_STRUCT, GTX_TRANSACTION_STRUCT)

    private val GET_SIGNERS_RETURN_TYPE: R_Type = R_ListType(R_ByteArrayType)
    private val GET_ALL_OPERATIONS_RETURN_TYPE: R_Type = R_ListType(GTX_OPERATION_STRUCT.type)

    private val NAMESPACE_FNS = C_GlobalFuncBuilder(NAMESPACE_NAME)
            .add("get_signers", GET_SIGNERS_RETURN_TYPE, listOf(), wrapFn(GetSigners))
            .add("is_signer", R_BooleanType, listOf(R_ByteArrayType), wrapFn(IsSigner))
            .add("get_all_operations", GET_ALL_OPERATIONS_RETURN_TYPE, listOf(), wrapFn(GetAllOperations))
            .add("emit_event", R_UnitType, listOf(R_TextType, R_GtvType), wrapFn(EmitEvent))
            .build()

    val NAMESPACE = C_LibUtils.makeNs(
            NAMESPACE_FNS,
            "last_block_time" to BaseNsValue(R_IntegerType, LastBlockTime),
            "block_height" to BaseNsValue(R_IntegerType, BlockHeight),
            "transaction" to Value_Transaction,
            "op_index" to BaseNsValue(R_IntegerType, OpIndex)
    )

    val FN_IS_SIGNER: C_SysFunction = wrapFn(IsSigner)

    private val TRANSACTION_FN = "$NAMESPACE_NAME.transaction"

    fun transactionRExpr(ctx: C_NamespaceValueContext, pos: S_Pos): R_Expr {
        val type = ctx.modCtx.sysDefs.transactionEntity.type
        return C_Utils.createSysCallRExpr(type, Transaction(type), listOf(), pos, TRANSACTION_FN)
    }

    private fun transactionExpr(ctx: C_NamespaceValueContext, pos: S_Pos): V_Expr {
        val type = ctx.modCtx.sysDefs.transactionEntity.type
        return C_Utils.createSysGlobalPropExpr(ctx.exprCtx, type, Transaction(type), pos, TRANSACTION_FN, pure = false)
    }

    private fun checkCtx(ctx: C_NamespaceValueContext, name: List<S_Name>) {
        checkCtx(ctx.exprCtx, name[0].pos)
    }

    private fun checkCtx(ctx: C_ExprContext, pos: S_Pos) {
        val dt = ctx.defCtx.definitionType
        if (dt != C_DefinitionType.OPERATION && dt != C_DefinitionType.FUNCTION && dt != C_DefinitionType.ENTITY) {
            ctx.msgCtx.error(pos, "op_ctx_noop", "Can access '$NAMESPACE_NAME' only in an operation, function or entity")
        }
    }

    private fun wrapFn(rFn: R_SysFunction): C_SysFunction {
        val cFn = C_SysFunction.direct(rFn)
        return C_SysFunction.validating(cFn) { ctx, pos ->
            checkCtx(ctx, pos)
        }
    }

    private class BaseNsValue(val resType: R_Type, val rFn: R_SysFunction): C_NamespaceValue_VExpr() {
        override fun toExpr0(ctx: C_NamespaceValueContext, name: List<S_Name>): V_Expr {
            checkCtx(ctx, name)
            return C_Utils.createSysGlobalPropExpr(ctx.exprCtx, resType, rFn, name, pure = false)
        }
    }

    private object Value_Transaction: C_NamespaceValue_VExpr() {
        override fun toExpr0(ctx: C_NamespaceValueContext, name: List<S_Name>): V_Expr {
            checkCtx(ctx, name)
            return transactionExpr(ctx, name[0].pos)
        }
    }

    private abstract class BaseFn(private val name: String): R_SysFunction() {
        abstract fun call(opCtx: Rt_OpContext): Rt_Value

        final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            checkEquals(args.size, 0)
            val opCtx = getOpContext(ctx, name)
            return call(opCtx)
        }
    }

    private object LastBlockTime: BaseFn("last_block_time") {
        override fun call(opCtx: Rt_OpContext) = Rt_IntValue(opCtx.lastBlockTime)
    }

    private class Transaction(private val type: R_EntityType): BaseFn("transaction") {
        override fun call(opCtx: Rt_OpContext) = Rt_EntityValue(type, opCtx.transactionIid)
    }

    private object BlockHeight: BaseFn("block_height") {
        override fun call(opCtx: Rt_OpContext) = Rt_IntValue(opCtx.blockHeight)
    }

    private object OpIndex: BaseFn("op_index") {
        override fun call(opCtx: Rt_OpContext) = Rt_IntValue(opCtx.opIndex.toLong())
    }

    private object GetSigners: R_SysFunctionEx_0() {
        override fun call(ctx: Rt_CallContext): Rt_Value {
            val opCtx = getOpContext(ctx, "get_signers")
            val elements = opCtx.signers.map { Rt_ByteArrayValue(it) as Rt_Value }.toMutableList()
            return Rt_ListValue(GET_SIGNERS_RETURN_TYPE, elements)
        }
    }

    private object IsSigner: R_SysFunction() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            checkEquals(args.size, 1)
            val a = args[0].asByteArray()
            val opCtx = ctx.exeCtx.opCtx
            val r = if (opCtx == null) false else opCtx.signers.any { Arrays.equals(it, a) }
            return Rt_BooleanValue(r)
        }
    }

    private object GetAllOperations: R_SysFunctionEx_0() {
        private val ARGS_LIST_TYPE = R_ListType(R_GtvType)

        override fun call(ctx: Rt_CallContext): Rt_Value {
            val opCtx = getOpContext(ctx, "get_all_operations")
            val elements = opCtx.allOperations.map {
                val name = Rt_TextValue(it.opName)
                val args = Rt_ListValue(ARGS_LIST_TYPE, it.args.map { Rt_GtvValue(it) }.toMutableList())
                Rt_StructValue(GTX_OPERATION_STRUCT.type, mutableListOf(name, args)) as Rt_Value
            }.toMutableList()
            return Rt_ListValue(GET_ALL_OPERATIONS_RETURN_TYPE, elements)
        }
    }

    private object EmitEvent: R_SysFunctionEx_2() {
        override fun call(ctx: Rt_CallContext, arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
            val opCtx = getOpContext(ctx, "emit_event")
            val type = arg1.asString()
            val data = arg2.asGtv()
            opCtx.txCtx.emitEvent(type, data)
            return Rt_UnitValue
        }
    }

    private fun getOpContext(ctx: Rt_CallContext, fnName: String): Rt_OpContext {
        val opCtx = ctx.exeCtx.opCtx
        return opCtx ?: throw Rt_Error("fn:op_context.$fnName:noop", "Operation context not available")
    }
}
