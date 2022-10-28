/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.expr

import net.postchain.rell.compiler.ast.S_CallArgument
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_Name
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.namespace.C_NamespaceMemberTag
import net.postchain.rell.compiler.base.utils.C_Error
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.R_FunctionType
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_Type
import net.postchain.rell.tools.api.IdeSymbolInfo

class C_ExprHint(val typeHint: C_TypeHint, val callable: Boolean = false) {
    fun memberTags(): List<C_NamespaceMemberTag>  {
        return if (callable) C_NamespaceMemberTag.CALLABLE.list else C_NamespaceMemberTag.CALLABLE.notList
    }

    companion object {
        val DEFAULT = C_ExprHint(C_TypeHint.NONE)
        val DEFAULT_CALLABLE = C_ExprHint(C_TypeHint.NONE, callable = true)

        fun ofType(type: R_Type?) = C_ExprHint(C_TypeHint.ofType(type))
    }
}

class C_ExprMember(val expr: C_Expr, val ideInfo: IdeSymbolInfo)

abstract class C_Expr {
    abstract fun startPos(): S_Pos

    abstract fun value(): V_Expr
    open fun isCallable() = false

    open fun implicitMatchName(): R_Name? = null

    abstract fun member(ctx: C_ExprContext, memberName: C_Name, exprHint: C_ExprHint): C_ExprMember

    open fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        val vExpr = value() // May fail with "not a value" - that's OK.
        val vResExpr = vExpr.call(ctx, pos, args, resTypeHint)
        return C_ValueExpr(vResExpr)
    }
}

class C_ValueExpr(
        private val vExpr: V_Expr,
        private val implicitAttrMatchName: R_Name? = null,
): C_Expr() {
    override fun startPos() = vExpr.pos
    override fun value() = vExpr
    override fun isCallable() = vExpr.type is R_FunctionType
    override fun implicitMatchName() = implicitAttrMatchName

    override fun member(ctx: C_ExprContext, memberName: C_Name, exprHint: C_ExprHint): C_ExprMember {
        return vExpr.member(ctx, memberName, false, exprHint)
    }
}

abstract class C_NoValueExpr: C_Expr() {
    protected abstract fun errKindName(): Pair<String, String>

    final override fun value(): V_Expr {
        throw errNoValue()
    }

    override fun member(ctx: C_ExprContext, memberName: C_Name, exprHint: C_ExprHint): C_ExprMember {
        ctx.msgCtx.error(errNoValue())
        return C_ExprUtils.errorMember(ctx, memberName.pos)
    }

    private fun errNoValue(): C_Error {
        val pos = startPos()
        val (kind, name) = errKindName()
        return C_Error.stop(pos, "expr_novalue:$kind:[$name]", "Expression cannot be used as a value: $kind '$name'")
    }
}
