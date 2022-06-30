/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.compiler.base.core.C_QualifiedName
import net.postchain.rell.compiler.base.expr.C_ExprUtils
import net.postchain.rell.compiler.base.namespace.*
import net.postchain.rell.compiler.base.utils.C_Constants
import net.postchain.rell.compiler.base.utils.C_Error
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.checkEquals

object C_Lib_ChainContext {
    fun bind(nsBuilder: C_SysNsProtoBuilder) {
        val b = C_SysNsProtoBuilder()
        b.addProperty("raw_config", makeProperty(R_GtvType, ChainCtxFns.RawConfig))
        b.addProperty("blockchain_rid", makeProperty(R_ByteArrayType, ChainCtxFns.BlockchainRid))
        b.addProperty("args", C_NsValue_ChainContext_Args)
        nsBuilder.addNamespace("chain_context", b.build().toNamespace())
    }
}

private object C_NsValue_ChainContext_Args: C_NamespaceValue_VExpr(IdeSymbolInfo(IdeSymbolKind.DEF_CONSTANT)) {
    override fun toExpr0(ctx: C_NamespaceValueContext, name: C_QualifiedName): V_Expr {
        val struct = ctx.modCtx.getModuleArgsStruct()
        if (struct == null) {
            val nameStr = name.str()
            throw C_Error.stop(name.pos, "expr_chainctx_args_norec",
                "To use '$nameStr', define a struct '${C_Constants.MODULE_ARGS_STRUCT}'")
        }

        val moduleName = ctx.modCtx.moduleName
        val rFn = ChainCtxFns.Args(moduleName)

        return C_ExprUtils.createSysGlobalPropExpr(ctx.exprCtx, struct.type, rFn, name, pure = true)
    }
}

private object ChainCtxFns {
    object RawConfig: R_SysFunction {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            checkEquals(args.size, 0)
            return Rt_GtvValue(ctx.chainCtx.rawConfig)
        }
    }

    object BlockchainRid: R_SysFunction {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            checkEquals(args.size, 0)
            val bcRid = ctx.chainCtx.blockchainRid
            return Rt_ByteArrayValue(bcRid.data)
        }
    }

    class Args(private val moduleName: R_ModuleName): R_SysFunction {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            checkEquals(args.size, 0)
            val res = ctx.chainCtx.moduleArgs[moduleName]
            return res ?: throw Rt_Error("chain_context.args:no_module_args:$moduleName", "No module args for module '$moduleName'")
        }
    }
}

private fun makeProperty(type: R_Type, fn: R_SysFunction, pure: Boolean = false): C_NamespaceValue {
    val ideInfo = IdeSymbolInfo(IdeSymbolKind.DEF_CONSTANT)
    return C_NamespaceValue_SysFunction(ideInfo, type, fn, pure = pure)
}
