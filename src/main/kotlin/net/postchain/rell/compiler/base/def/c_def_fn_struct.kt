/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.def

import net.postchain.rell.compiler.ast.S_CallArgument
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_CompilerPass
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.expr.C_AttributeResolver
import net.postchain.rell.compiler.base.expr.C_CallArgument
import net.postchain.rell.compiler.base.expr.C_CreateContext
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.utils.C_CodeMsg
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_StructExpr
import net.postchain.rell.model.R_Struct
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.utils.LazyPosString

class C_StructGlobalFunction(private val struct: R_Struct, ideInfo: IdeSymbolInfo): C_GlobalFunction(ideInfo) {
    override fun compileCall(ctx: C_ExprContext, name: LazyPosString, args: List<S_CallArgument>, resTypeHint: C_TypeHint): V_Expr {
        return compileCall(ctx, struct, name.pos, args)
    }

    companion object {
        fun compileCall(ctx: C_ExprContext, struct: R_Struct, fnPos: S_Pos, args: List<S_CallArgument>): V_Expr {
            val createCtx = C_CreateContext(ctx, struct.initFrameGetter, fnPos.toFilePos())

            val callArgs = C_CallArgument.compileAttributes(ctx, args, struct.attributes)
            val attrArgs = C_CallArgument.toAttrArguments(ctx, callArgs, C_CodeMsg("struct", "struct expression"))

            val attrs = C_AttributeResolver.resolveCreate(createCtx, struct.attributes, attrArgs, fnPos)

            val dbModRes = ctx.getDbModificationRestriction()
            if (dbModRes != null) {
                ctx.executor.onPass(C_CompilerPass.VALIDATION) {
                    val dbModAttr = attrs.implicitAttrs.firstOrNull { it.attr.isExprDbModification }
                    if (dbModAttr != null) {
                        val code = "${dbModRes.code}:attr:${dbModAttr.attr.name}"
                        val msg = "${dbModRes.msg} (default value of attribute '${dbModAttr.attr.name}')"
                        ctx.msgCtx.error(fnPos, code, msg)
                    }
                }
            }

            return V_StructExpr(ctx, fnPos, struct, attrs.explicitAttrs, attrs.implicitAttrs)
        }
    }
}
