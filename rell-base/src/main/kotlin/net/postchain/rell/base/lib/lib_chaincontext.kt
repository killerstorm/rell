/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib

import net.postchain.rell.base.compiler.base.core.C_QualifiedName
import net.postchain.rell.base.compiler.base.expr.C_ExprUtils
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceProperty
import net.postchain.rell.base.compiler.base.namespace.C_NamespacePropertyContext
import net.postchain.rell.base.compiler.base.utils.C_Constants
import net.postchain.rell.base.compiler.base.utils.C_Error
import net.postchain.rell.base.compiler.vexpr.V_Expr
import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.model.R_SysFunctionEx_N
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.utils.checkEquals
import net.postchain.rell.base.utils.ide.IdeSymbolInfo
import net.postchain.rell.base.utils.ide.IdeSymbolKind

object Lib_ChainContext {
    val NAMESPACE = Ld_NamespaceDsl.make {
        namespace("chain_context") {
            property("raw_config", type = "gtv", pure = false, ideKind = IdeSymbolKind.DEF_CONSTANT) {
                bodyContext { ctx ->
                    Rt_GtvValue(ctx.chainCtx.rawConfig)
                }
            }

            property("blockchain_rid", type = "byte_array", pure = false, ideKind = IdeSymbolKind.DEF_CONSTANT) {
                bodyContext { ctx ->
                    val bcRid = ctx.chainCtx.blockchainRid
                    Rt_ByteArrayValue(bcRid.toByteArray())
                }
            }

            property("args", C_NsProperty_ChainContext_Args)
        }
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
        val rFn = FnArgs(moduleName)

        return C_ExprUtils.createSysGlobalPropExpr(ctx.exprCtx, struct.structDef.type, rFn, name, pure = true)
    }

    private class FnArgs(private val moduleName: R_ModuleName): R_SysFunctionEx_N() {
        override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
            checkEquals(args.size, 0)
            val res = ctx.chainCtx.moduleArgs[moduleName]
            return res ?: throw Rt_Exception.common(
                "chain_context.args:no_module_args:$moduleName",
                "No module args for module '$moduleName'",
            )
        }
    }
}
