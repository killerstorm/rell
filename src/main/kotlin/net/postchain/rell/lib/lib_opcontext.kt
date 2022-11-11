/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_DefinitionPath
import net.postchain.rell.compiler.base.core.C_DefinitionType
import net.postchain.rell.compiler.base.core.C_QualifiedName
import net.postchain.rell.compiler.base.def.C_SysAttribute
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.expr.C_ExprUtils
import net.postchain.rell.compiler.base.namespace.C_Deprecated
import net.postchain.rell.compiler.base.namespace.C_NamespaceProperty
import net.postchain.rell.compiler.base.namespace.C_NamespacePropertyContext
import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.R_Expr
import net.postchain.rell.runtime.*
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.LazyString
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.immListOf

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

private val GET_SIGNERS_RETURN_TYPE: R_Type = R_ListType(R_ByteArrayType)
private val GET_ALL_OPERATIONS_RETURN_TYPE: R_Type = R_ListType(GTX_OPERATION_STRUCT.type)

object C_Lib_OpContext {
    const val NAMESPACE_NAME = "op_context"

    private val NAMESPACE_QNAME = R_QualifiedName.of(NAMESPACE_NAME)

    val GLOBAL_STRUCTS = immListOf(GTX_OPERATION_STRUCT, GTX_TRANSACTION_BODY_STRUCT, GTX_TRANSACTION_STRUCT)

    private val DEF_PATH = C_DefinitionPath.ROOT.subPath(NAMESPACE_NAME)

    private val NAMESPACE_FNS = C_GlobalFuncBuilder(DEF_PATH)
            .add("get_signers", GET_SIGNERS_RETURN_TYPE, listOf(), wrapFn(OpCtxFns.GetSigners))
            .add("is_signer", R_BooleanType, listOf(R_ByteArrayType), wrapFn(OpCtxFns.IsSigner))
            .add("get_all_operations", GET_ALL_OPERATIONS_RETURN_TYPE, listOf(), wrapFn(OpCtxFns.GetAllOperations))
            .add("emit_event", R_UnitType, listOf(R_TextType, R_GtvType), wrapFn(OpCtxFns.EmitEvent))
            .build()

    val NAMESPACE = C_LibUtils.makeNs(
            DEF_PATH,
            NAMESPACE_FNS,
            "exists" to Property_Exists,
            "last_block_time" to BaseNsProperty(R_IntegerType, OpCtxFns.LastBlockTime),
            "block_height" to BaseNsProperty(R_IntegerType, OpCtxFns.BlockHeight),
            "transaction" to Property_Transaction,
            "op_index" to BaseNsProperty(R_IntegerType, OpCtxFns.OpIndex)
    )

    val FN_IS_SIGNER: C_SysFunction = wrapFn(OpCtxFns.IsSigner)

    private val TRANSACTION_FN_QNAME = NAMESPACE_QNAME.child("transaction")
    private val TRANSACTION_FN = TRANSACTION_FN_QNAME.str()
    private val TRANSACTION_FN_LAZY = LazyString.of(TRANSACTION_FN)

    fun bind(nsBuilder: C_SysNsProtoBuilder, testLib: Boolean) {
        val fb = C_GlobalFuncBuilder()
        // When turning deprecated warning into error, keep backwards-compatibility (the function is used in existing code).
        fb.add("is_signer", R_BooleanType, listOf(R_ByteArrayType), C_Lib_OpContext.FN_IS_SIGNER, depWarn("op_context.is_signer"))
        C_LibUtils.bindFunctions(nsBuilder, fb.build())

        for (struct in GLOBAL_STRUCTS) {
            nsBuilder.addStruct(struct.name, struct, IdeSymbolInfo(IdeSymbolKind.DEF_STRUCT))
        }

        if (!testLib) {
            nsBuilder.addNamespace(NAMESPACE_NAME, NAMESPACE)
        }
    }

    fun transactionRExpr(ctx: C_NamespacePropertyContext, pos: S_Pos): R_Expr {
        val type = ctx.modCtx.sysDefs.transactionEntity.type
        return C_ExprUtils.createSysCallRExpr(type, OpCtxFns.Transaction(type), listOf(), pos, TRANSACTION_FN_LAZY)
    }

    private fun transactionExpr(ctx: C_NamespacePropertyContext, pos: S_Pos): V_Expr {
        val type = ctx.modCtx.sysDefs.transactionEntity.type
        return C_ExprUtils.createSysGlobalPropExpr(ctx.exprCtx, type, OpCtxFns.Transaction(type), pos, TRANSACTION_FN, pure = false)
    }

    private fun checkCtx(ctx: C_NamespacePropertyContext, name: C_QualifiedName) {
        checkCtx(ctx.exprCtx, name.pos)
    }

    private fun checkCtx(ctx: C_ExprContext, pos: S_Pos) {
        val dt = ctx.defCtx.definitionType
        if (dt != C_DefinitionType.OPERATION && dt != C_DefinitionType.FUNCTION && dt != C_DefinitionType.ENTITY) {
            ctx.msgCtx.error(pos, "op_ctx_noop", "Can access '$NAMESPACE_NAME' only in an operation, function or entity")
        }
    }

    private fun wrapFn(rFn: R_SysFunction): C_SysFunction {
        return C_SysFunction.validating(rFn) { ctx ->
            checkCtx(ctx.exprCtx, ctx.callPos)
        }
    }

    private class BaseNsProperty(val resType: R_Type, val rFn: R_SysFunction): C_NamespaceProperty(IdeSymbolInfo.MEM_SYS_PROPERTY) {
        override fun toExpr(ctx: C_NamespacePropertyContext, name: C_QualifiedName): V_Expr {
            checkCtx(ctx, name)
            return C_ExprUtils.createSysGlobalPropExpr(ctx.exprCtx, resType, rFn, name, pure = false)
        }
    }

    private object Property_Transaction: C_NamespaceProperty(IdeSymbolInfo.MEM_SYS_PROPERTY) {
        override fun toExpr(ctx: C_NamespacePropertyContext, name: C_QualifiedName): V_Expr {
            checkCtx(ctx, name)
            return transactionExpr(ctx, name.pos)
        }
    }

    private object Property_Exists: C_NamespaceProperty(IdeSymbolInfo.MEM_SYS_PROPERTY) {
        override fun toExpr(ctx: C_NamespacePropertyContext, name: C_QualifiedName): V_Expr {
            val nameMsg = name.last.str
            return C_ExprUtils.createSysGlobalPropExpr(ctx.exprCtx, R_BooleanType, OpCtxFns.Exists, name.pos, nameMsg, pure = false)
        }
    }
}

private object OpCtxFns {
    abstract class BaseFn(private val name: String): R_SysFunction {
        abstract fun call(opCtx: Rt_OpContext): Rt_Value

        final override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            checkEquals(args.size, 0)
            val opCtx = getOpContext(ctx, name)
            return call(opCtx)
        }
    }

    object Exists: R_SysFunctionEx_0() {
        override fun call(ctx: Rt_CallContext): Rt_Value {
            val opCtx = ctx.exeCtx.opCtx
            return Rt_BooleanValue(opCtx != null)
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

    object OpIndex: BaseFn("op_index") {
        override fun call(opCtx: Rt_OpContext) = Rt_IntValue(opCtx.opIndex.toLong())
    }

    object GetSigners: R_SysFunctionEx_0() {
        override fun call(ctx: Rt_CallContext): Rt_Value {
            val opCtx = getOpContext(ctx, "get_signers")
            val elements = opCtx.signers.map { Rt_ByteArrayValue(it) as Rt_Value }.toMutableList()
            return Rt_ListValue(GET_SIGNERS_RETURN_TYPE, elements)
        }
    }

    object IsSigner: R_SysFunction {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            checkEquals(args.size, 1)
            val a = args[0].asByteArray()
            val opCtx = ctx.exeCtx.opCtx
            val r = if (opCtx == null) false else opCtx.signers.any { it.contentEquals(a) }
            return Rt_BooleanValue(r)
        }
    }

    object GetAllOperations: R_SysFunctionEx_0() {
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

    object EmitEvent: R_SysFunctionEx_2() {
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
        return opCtx ?: throw Rt_Exception.common("fn:op_context.$fnName:noop", "Operation context not available")
    }
}

private fun depWarn(newName: String) = C_Deprecated(useInstead = newName, error = false)
