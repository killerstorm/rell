/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.def.C_GlobalFunction
import net.postchain.rell.compiler.base.expr.*
import net.postchain.rell.compiler.base.namespace.C_NamespaceValue
import net.postchain.rell.compiler.base.namespace.C_NamespaceValueContext
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.vexpr.V_ConstantValueExpr
import net.postchain.rell.model.R_EnumType
import net.postchain.rell.model.R_NullableType
import net.postchain.rell.runtime.Rt_EnumValue
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind

class S_NameExpr(val name: S_Name): S_Expr(name.pos) {
    override fun asName() = name

    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        val nameHand = name.compile(ctx)
        return compile0(ctx, hint, nameHand)
    }

    private fun compile0(ctx: C_ExprContext, hint: C_ExprHint, nameHand: C_NameHandle): C_Expr {
        val res = resolveName(ctx, hint, nameHand)
        if (res != null) {
            return res
        }
        nameHand.setIdeInfo(IdeSymbolInfo.UNKNOWN)
        throw C_Errors.errUnknownName(nameHand.name)
    }

    private fun resolveName(ctx: C_ExprContext, hint: C_ExprHint, nameHand: C_NameHandle): C_Expr? {
        val valueRes = resolveNameValue(ctx, nameHand)
        val fnRes = resolveNameFunction(ctx, nameHand)

        val valueCallable = valueRes?.isCallable() ?: false
        val res = C_ExprUtils.valueFunctionExpr0(valueRes, fnRes, valueCallable, hint)

        return res?.compile()
    }

    private fun resolveNameValue(ctx: C_ExprContext, nameHand: C_NameHandle): NameRes? {
        val loc = ctx.blkCtx.lookupEntry(nameHand.rName)
        if (loc != null) {
            val vExpr = loc.compile(ctx, nameHand.pos)
            val cExpr = C_VExpr(vExpr, implicitAttrMatchName = nameHand.rName)
            return NameRes_Local(nameHand, loc, cExpr)
        }

        val qNameHand = C_QualifiedNameHandle(nameHand)
        val glob = ctx.nsCtx.getValueOpt(qNameHand)
        if (glob != null) {
            val qName = qNameHand.qName
            return NameRes_Global(ctx, qName, glob)
        }

        return null
    }

    private fun resolveNameFunction(ctx: C_ExprContext, nameHand: C_NameHandle): NameRes? {
        val qNameHand = C_QualifiedNameHandle(nameHand)
        val defRes = ctx.nsCtx.getFunctionOpt(qNameHand)
        defRes ?: return null
        return NameRes_Function(defRes, nameHand.name)
    }

    override fun compileFromItem(ctx: C_ExprContext): C_AtFromItem {
        val qNameHand = S_QualifiedName(name).compile(ctx)
        qNameHand.allowRedefinition()

        val entityRes = ctx.nsCtx.getEntityOpt(qNameHand)
        if (entityRes != null) {
            val entity = entityRes.getDef()
            return C_AtFromItem_Entity(name.pos, entityRes.qName.last, entity)
        }

        return super.compileFromItem(ctx)
    }

    override fun compileWhenEnum(ctx: C_ExprContext, type: R_EnumType): C_Expr {
        val nameHand = name.compile(ctx)

        val attr = type.enum.attr(nameHand.str)
        attr ?: return compile0(ctx, C_ExprHint.DEFAULT, nameHand)

        nameHand.setIdeInfo(IdeSymbolInfo(IdeSymbolKind.MEM_ENUM_ATTR))
        val value = Rt_EnumValue(type, attr)
        val vExpr = V_ConstantValueExpr(ctx, startPos, value)
        return C_VExpr(vExpr)
    }

    private abstract class NameRes {
        abstract fun isCallable(): Boolean
        abstract fun compile(): C_Expr
    }

    private class NameRes_Local(
            private val nameHand: C_NameHandle,
            private val loc: C_BlockEntryResolution,
            private val expr: C_Expr
    ): NameRes() {
        override fun isCallable() = expr.isCallable()

        override fun compile(): C_Expr {
            val ideInfo = loc.ideSymbolInfo()
            nameHand.setIdeInfo(ideInfo)
            return expr
        }
    }

    private class NameRes_Global(
            private val ctx: C_ExprContext,
            private val qName: C_QualifiedName,
            private val defRes: C_DefResolution<C_NamespaceValue>
    ): NameRes() {
        // Actually not right to always return "false", but at the moment of writing there are no callable global
        // values (constants don't support functional type and functions are resolved separately).
        override fun isCallable() = false

        override fun compile(): C_Expr {
            val def = defRes.getDef()
            val memCtx = C_NamespaceValueContext(ctx)
            val expr = def.toExpr(memCtx, qName)
            return expr
        }
    }

    private class NameRes_Function(
            private val defRes: C_DefResolution<C_GlobalFunction>,
            private val cName: C_Name
    ): NameRes() {
        override fun isCallable() = true

        override fun compile(): C_Expr {
            val fn = defRes.getDef()
            val expr: C_Expr = C_FunctionExpr(cName, fn)
            return expr
        }
    }
}

class S_AttrExpr(pos: S_Pos, private val name: S_Name): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        val vExpr = ctx.resolveAttr(name)
        return C_VExpr(vExpr)
    }
}

class S_MemberExpr(val base: S_Expr, val name: S_Name): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        val cBase = base.compileSafe(ctx)
        val cExpr = cBase.member(ctx, name, false, hint)
        return cExpr
    }

    override fun compileFromItem(ctx: C_ExprContext): C_AtFromItem {
        val qName = toQualifiedName()

        if (qName != null) {
            val qNameHand = qName.compile(ctx)
            qNameHand.allowRedefinition()

            val entityRes = ctx.nsCtx.getEntityOpt(qNameHand)
            if (entityRes != null) {
                val entity = entityRes.getDef()
                return C_AtFromItem_Entity(qName.pos, qNameHand.last.name, entity)
            }
        }

        return super.compileFromItem(ctx)
    }

    private fun toQualifiedName(): S_QualifiedName? {
        var cur: S_Expr = this
        val names = mutableListOf<S_Name>()

        while (true) {
            if (cur is S_NameExpr) {
                names.add(cur.name)
                return S_QualifiedName(names.reversed())
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
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        val cBase = base.compile(ctx)

        val baseValueOriginal = cBase.value()
        val baseValueNullable = baseValueOriginal.asNullable()

        val baseType = baseValueNullable.type
        if (baseType !is R_NullableType && baseType.isNotError()) {
            val typeStr = baseType.strCode()
            ctx.msgCtx.error(name.pos, "expr_safemem_type:[$typeStr]", "Wrong type for operator '?.': $typeStr")
        }

        val safe = baseValueOriginal.type is R_NullableType
        val actualBaseValue = if (safe) baseValueNullable else baseValueOriginal

        val nameHand = name.compile(ctx)
        val member = actualBaseValue.member(ctx, nameHand.name, safe, hint)
        nameHand.setIdeInfo(member.ideInfo)

        return member.expr
    }
}

class S_DollarExpr(pos: S_Pos): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        val ph = ctx.blkCtx.lookupAtPlaceholder()
        ph ?: throw C_Errors.errAtPlaceholderNotDefined(startPos)

        val ideInfo = ph.ideSymbolInfo()
        ctx.symCtx.addSymbol(startPos, ideInfo)

        val vExpr = ph.compile(ctx, startPos)
        return C_VExpr(vExpr)
    }
}
