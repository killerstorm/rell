/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.compiler.base.core.C_QualifiedName
import net.postchain.rell.compiler.base.expr.C_ExprUtils
import net.postchain.rell.compiler.base.namespace.C_NamespaceProperty
import net.postchain.rell.compiler.base.namespace.C_NamespacePropertyContext
import net.postchain.rell.compiler.base.namespace.C_NamespaceProperty_SysFunction
import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_Constants
import net.postchain.rell.compiler.base.utils.C_Error
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.utils.checkEquals

object C_Lib_ChainContext {
    fun bind(nsBuilder: C_SysNsProtoBuilder) {
        val nsName = "chain_context"
        val b = C_SysNsProtoBuilder(nsBuilder.basePath.subPath(nsName))

        b.addProperty("raw_config", makeProperty(R_GtvType, ChainCtxFns.RawConfig))
        b.addProperty("blockchain_rid", makeProperty(R_ByteArrayType, ChainCtxFns.BlockchainRid))
        b.addProperty("args", C_NsProperty_ChainContext_Args)

        nsBuilder.addNamespace(nsName, b.build().toNamespace())
    }
}

private object C_NsProperty_ChainContext_Args: C_NamespaceProperty(IdeSymbolInfo.DEF_CONSTANT) {
    override fun toExpr(ctx: C_NamespacePropertyContext, name: C_QualifiedName): V_Expr {
        val struct = ctx.modCtx.getModuleArgsStruct()
        if (struct == null) {
            val nameStr = name.str()
            throw C_Error.stop(name.pos, "expr_chainctx_args_norec",
                "To use '$nameStr', define a struct '${C_Constants.MODULE_ARGS_STRUCT}'")
        }

        val ideInfo = struct.ideInfo
        if (ideInfo.link != null) {
            ctx.exprCtx.symCtx.setLink(name.last.pos, ideInfo.link)
        }

        val moduleName = ctx.modCtx.moduleName
        val rFn = ChainCtxFns.Args(moduleName)

        return C_ExprUtils.createSysGlobalPropExpr(ctx.exprCtx, struct.structDef.type, rFn, name, pure = true)
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
            return Rt_ByteArrayValue(bcRid.toByteArray())
        }
    }

    class Args(private val moduleName: R_ModuleName): R_SysFunction {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            checkEquals(args.size, 0)
            val res = ctx.chainCtx.moduleArgs[moduleName]
            return res ?: throw Rt_Exception.common("chain_context.args:no_module_args:$moduleName", "No module args for module '$moduleName'")
        }
    }
}

private fun makeProperty(type: R_Type, fn: R_SysFunction, pure: Boolean = false): C_NamespaceProperty {
    val ideInfo = IdeSymbolInfo.DEF_CONSTANT
    return C_NamespaceProperty_SysFunction(ideInfo, type, fn, pure = pure)
}
