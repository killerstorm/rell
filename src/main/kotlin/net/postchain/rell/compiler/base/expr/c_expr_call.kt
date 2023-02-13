/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.expr

import net.postchain.rell.compiler.ast.S_CallArgument
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_Name
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.fn.C_FunctionCallTargetInfo
import net.postchain.rell.compiler.base.utils.C_CodeMsg
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.R_Attribute
import net.postchain.rell.model.R_Name
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap

interface C_CallTypeHints {
    fun getTypeHint(index: Int?, name: R_Name?): C_TypeHint
}

object C_CallTypeHints_None: C_CallTypeHints {
    override fun getTypeHint(index: Int?, name: R_Name?) = C_TypeHint.NONE
}

sealed class C_CallArgumentValue(val pos: S_Pos)
class C_CallArgumentValue_Expr(pos: S_Pos, val vExpr: V_Expr): C_CallArgumentValue(pos)
class C_CallArgumentValue_Wildcard(pos: S_Pos): C_CallArgumentValue(pos)

class C_CallArgument(val index: Int, val name: C_Name?, val value: C_CallArgumentValue) {
    companion object {
        fun compileAttributes(
                ctx: C_ExprContext,
                args: List<S_CallArgument>,
                attrs: Map<R_Name, R_Attribute>
        ): List<C_CallArgument> {
            val typeHints = C_AttrsTypeHints(attrs)
            val ideInfoProvider = C_CallArgumentIdeInfoProvider_Attribute(attrs)
            return compileArguments(ctx, args, typeHints, ideInfoProvider)
        }

        fun compileArguments(
                ctx: C_ExprContext,
                args: List<S_CallArgument>,
                typeHints: C_CallTypeHints,
                ideInfoProvider: C_CallArgumentIdeInfoProvider
        ): List<C_CallArgument> {
            val res = mutableListOf<C_CallArgument>()
            var positional = true

            for ((index, arg) in args.withIndex()) {
                if (arg.name != null) positional = false
                val cArg = arg.compile(ctx, index, positional, typeHints, ideInfoProvider)
                res.add(cArg)
            }

            return res.toImmList()
        }

        fun toAttrArguments(ctx: C_ExprContext, callArgs: List<C_CallArgument>, place: C_CodeMsg): List<C_AttrArgument> {
            var wildcardErr = false
            return callArgs.mapNotNull {
                when (it.value) {
                    is C_CallArgumentValue_Expr -> {
                        C_AttrArgument(it.index, it.name, it.value.vExpr)
                    }
                    is C_CallArgumentValue_Wildcard -> {
                        if (!wildcardErr) {
                            wildcardErr = true
                            val code = "expr:call:wildcard:${place.code}"
                            val msg = "Wildcards not allowed in ${place.msg}"
                            ctx.msgCtx.error(it.value.pos, code, msg)
                        }
                        if (it.name == null) null else {
                            val vExpr = C_ExprUtils.errorVExpr(ctx, it.value.pos)
                            C_AttrArgument(it.index, it.name, vExpr)
                        }
                    }
                }
            }
        }
    }

    private class C_AttrsTypeHints(private val attrs: Map<R_Name, R_Attribute>): C_CallTypeHints {
        override fun getTypeHint(index: Int?, name: R_Name?): C_TypeHint {
            val attr = if (name != null) {
                attrs[name]
            } else if (attrs.size == 1) {
                attrs.values.iterator().next()
            } else {
                null
            }
            return C_TypeHint.ofType(attr?.type)
        }
    }
}

sealed class C_CallArgumentIdeInfoProvider {
    abstract fun getIdeInfo(name: R_Name): IdeSymbolInfo
}

object C_CallArgumentIdeInfoProvider_Unknown: C_CallArgumentIdeInfoProvider() {
    override fun getIdeInfo(name: R_Name) = IdeSymbolInfo.UNKNOWN
}

class C_CallArgumentIdeInfoProvider_Argument(
        private val targetInfo: C_FunctionCallTargetInfo
): C_CallArgumentIdeInfoProvider() {
    override fun getIdeInfo(name: R_Name): IdeSymbolInfo {
        val param = targetInfo.hasParameter(name)
        return if (param) IdeSymbolInfo.EXPR_CALL_ARG else IdeSymbolInfo.UNKNOWN
    }
}

class C_CallArgumentIdeInfoProvider_Attribute(
        attributes: Map<R_Name, R_Attribute>
): C_CallArgumentIdeInfoProvider() {
    private val attributes = attributes.toImmMap()

    override fun getIdeInfo(name: R_Name): IdeSymbolInfo {
        val attr = attributes[name]
        return attr?.ideInfo ?: IdeSymbolInfo.UNKNOWN
    }
}
