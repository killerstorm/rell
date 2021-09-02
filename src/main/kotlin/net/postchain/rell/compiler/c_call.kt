package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_CallArgument
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.R_Attribute
import net.postchain.rell.utils.toImmList

interface C_CallTypeHints {
    fun getTypeHint(index: Int?, name: String?): C_TypeHint
}

object C_CallTypeHints_None: C_CallTypeHints {
    override fun getTypeHint(index: Int?, name: String?) = C_TypeHint.NONE
}

sealed class C_CallArgumentValue(val pos: S_Pos)
class C_CallArgumentValue_Expr(pos: S_Pos, val vExpr: V_Expr, val implicitName: String?): C_CallArgumentValue(pos)
class C_CallArgumentValue_Wildcard(pos: S_Pos): C_CallArgumentValue(pos)

class C_CallArgument(val index: Int, val name: S_Name?, val value: C_CallArgumentValue) {
    companion object {
        fun compileAttributes(
                ctx: C_ExprContext,
                args: List<S_CallArgument>,
                attrs: Map<String, R_Attribute>
        ): List<C_CallArgument> {
            return compileArguments(ctx, args, C_AttrsTypeHints(attrs))
        }

        fun compileArguments(
                ctx: C_ExprContext,
                args: List<S_CallArgument>,
                typeHints: C_CallTypeHints
        ): List<C_CallArgument> {
            val res = mutableListOf<C_CallArgument>()
            var positional = true

            for ((index, arg) in args.withIndex()) {
                if (arg.name != null) positional = false
                val cArg = compileArg(ctx, arg, index, positional, typeHints)
                res.add(cArg)
            }

            return res.toImmList()
        }

        private fun compileArg(
                ctx: C_ExprContext,
                arg: S_CallArgument,
                index: Int,
                positional: Boolean,
                typeHints: C_CallTypeHints
        ): C_CallArgument {
            val hintIndex = if (positional) index else null
            val typeHint = typeHints.getTypeHint(hintIndex, arg.name?.str)
            val argValue = arg.value.compile(ctx, typeHint)
            return C_CallArgument(index, arg.name, argValue)
        }

        fun toAttrArguments(ctx: C_ExprContext, callArgs: List<C_CallArgument>, place: C_CodeMsg): List<C_AttrArgument> {
            var wildcardErr = false
            return callArgs.mapNotNull {
                when (it.value) {
                    is C_CallArgumentValue_Expr -> {
                        val exprName = it.value.implicitName
                        C_AttrArgument(it.index, it.name, it.value.vExpr, exprName)
                    }
                    is C_CallArgumentValue_Wildcard -> {
                        if (!wildcardErr) {
                            wildcardErr = true
                            val code = "expr:call:wildcard:${place.code}"
                            val msg = "Wildcards not allowed in ${place.msg}"
                            ctx.msgCtx.error(it.value.pos, code, msg)
                        }
                        if (it.name == null) null else {
                            val vExpr = C_Utils.errorVExpr(ctx, it.value.pos)
                            C_AttrArgument(it.index, it.name, vExpr, null)
                        }
                    }
                }
            }
        }
    }

    private class C_AttrsTypeHints(private val attrs: Map<String, R_Attribute>): C_CallTypeHints {
        override fun getTypeHint(index: Int?, name: String?): C_TypeHint {
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
