/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.expr

import net.postchain.rell.base.compiler.ast.S_CallArgument
import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.base.core.C_Name
import net.postchain.rell.base.compiler.base.core.C_TypeHint
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceMemberTag
import net.postchain.rell.base.compiler.base.utils.C_Error
import net.postchain.rell.base.compiler.vexpr.V_Expr
import net.postchain.rell.base.compiler.vexpr.V_TypeValueMember
import net.postchain.rell.base.compiler.vexpr.V_ValueMemberExpr
import net.postchain.rell.base.model.R_FunctionType
import net.postchain.rell.base.model.R_Type
import net.postchain.rell.base.utils.ide.IdeSymbolInfo

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

    open fun member(ctx: C_ExprContext, memberName: C_Name, exprHint: C_ExprHint): C_ExprMember {
        val vExpr = value()
        return vExpr.member(ctx, memberName, false, exprHint)
    }

    open fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        val vExpr = value() // May fail with "not a value" - that's OK.
        val vResExpr = vExpr.call(ctx, pos, args, resTypeHint)
        return C_ValueExpr(vResExpr)
    }
}

class C_ValueExpr(private val vExpr: V_Expr): C_Expr() {
    override fun startPos() = vExpr.pos
    override fun value() = vExpr
    override fun isCallable() = vExpr.type is R_FunctionType
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
        return errNoValue(pos, kind, name)
    }

    companion object {
        fun errNoValue(pos: S_Pos, kind: String, name: String): C_Error {
            return C_Error.stop(pos, "expr_novalue:$kind:[$name]", "Expression cannot be used as a value: $kind '$name'")
        }
    }
}

class C_ValueMemberExpr(
    private val exprCtx: C_ExprContext,
    memberLink: C_MemberLink,
    private val member: C_TypeValueMember,
): C_Expr() {
    private val vBase = memberLink.base
    private val selfType = memberLink.selfType
    private val memberPos = memberLink.linkPos
    private val memberName = memberLink.linkName
    private val safe = memberLink.safe

    override fun startPos() = vBase.pos
    override fun isCallable() = member.isCallable()

    override fun value(): V_Expr {
        val vMember = member.value(exprCtx, memberPos, memberName)
        return makeMemberExpr(vMember)
    }

    override fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): C_Expr {
        val vMember = member.call(exprCtx, selfType, memberPos, args, resTypeHint)
        vMember ?: return super.call(ctx, pos, args, resTypeHint)
        val vExpr = makeMemberExpr(vMember)
        return C_ValueExpr(vExpr)
    }

    private fun makeMemberExpr(vMember: V_TypeValueMember): V_Expr {
        return V_ValueMemberExpr(exprCtx, vBase, vMember, memberPos, safe)
    }
}
