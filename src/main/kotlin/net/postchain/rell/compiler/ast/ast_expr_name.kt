/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.model.R_NullableType
import net.postchain.rell.utils.toImmList

class S_NameExpr(val name: S_Name): S_Expr(name.pos) {
    override fun asName() = name

    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val valueExpr = resolveNameValue(ctx, name)
        val fn = ctx.nsCtx.getFunctionOpt(listOf(name))
        val fnExpr = if (fn == null) null else C_FunctionExpr(name, fn)
        val expr = C_ValueFunctionExpr.create(name, valueExpr, fnExpr)
        return expr ?: throw C_Errors.errUnknownName(name)
    }

    private fun resolveNameValue(ctx: C_ExprContext, name: S_Name): C_Expr? {
        val loc = ctx.blkCtx.lookupEntry(name.str)
        val glob = ctx.nsCtx.getValueOpt(name)
        return if (loc != null) {
            val vExpr = loc.compile(ctx, name.pos)
            C_VExpr(vExpr)
        } else if (glob != null) {
            C_NamespaceValueExpr(ctx.nsValueCtx, listOf(name), glob)
        } else {
            null
        }
    }

    override fun compileFromItem(ctx: C_ExprContext): C_AtFromItem {
        val entity = ctx.nsCtx.getEntityOpt(listOf(name))
        if (entity != null) {
            return C_AtFromItem_Entity(name.pos, name, entity)
        }
        return super.compileFromItem(ctx)
    }
}

class S_AttrExpr(pos: S_Pos, val name: S_Name): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val vExpr = ctx.resolveAttr(name)
        return C_VExpr(vExpr)
    }
}

class S_MemberExpr(val base: S_Expr, val name: S_Name): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val cBase = base.compile(ctx)
        val cExpr = cBase.member(ctx, name, false)
        return cExpr
    }

    override fun compileFromItem(ctx: C_ExprContext): C_AtFromItem {
        val names = toNames()
        if (names != null) {
            val entity = ctx.nsCtx.getEntityOpt(names)
            if (entity != null) {
                return C_AtFromItem_Entity(names[0].pos, names.last(), entity)
            }
        }

        return super.compileFromItem(ctx)
    }

    private fun toNames(): List<S_Name>? {
        var cur: S_Expr = this
        val names = mutableListOf<S_Name>()

        while (true) {
            if (cur is S_NameExpr) {
                names.add(cur.name)
                return names.reversed().toImmList()
            } else if (cur is S_MemberExpr) {
                names.add(cur.name)
                cur = cur.base
            } else {
                return null
            }
        }
    }
}

class S_SafeMemberExpr(val base: S_Expr, val name: S_Name): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val cBase = base.compile(ctx)

        val baseValue = cBase.value()
        val baseValueNullable = baseValue.asNullable()

        val baseType = baseValueNullable.type()
        if (baseType !is R_NullableType) {
            val typeStr = baseType.toStrictString()
            ctx.msgCtx.error(name.pos, "expr_safemem_type:[$typeStr]", "Wrong type for operator '?.': $typeStr")
        }

        val smartType = baseValue.type()
        if (smartType !is R_NullableType) {
            return baseValue.member(ctx, name, false)
        } else {
            return baseValueNullable.member(ctx, name, true)
        }
    }
}

class S_DollarExpr(pos: S_Pos): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val ph = ctx.blkCtx.lookupAtPlaceholder()
        ph ?: throw C_Errors.errAtPlaceholderNotDefined(startPos)
        val vExpr = ph.compile(ctx, startPos)
        return C_VExpr(vExpr)
    }
}
