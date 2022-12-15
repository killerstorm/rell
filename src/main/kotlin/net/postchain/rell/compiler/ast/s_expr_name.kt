/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.base.core.C_BlockEntryResolution
import net.postchain.rell.compiler.base.core.C_GlobalNameRes
import net.postchain.rell.compiler.base.core.C_NameHandle
import net.postchain.rell.compiler.base.core.C_QualifiedNameHandle
import net.postchain.rell.compiler.base.expr.*
import net.postchain.rell.compiler.base.namespace.C_NamespaceMemberTag
import net.postchain.rell.compiler.base.utils.C_Error
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.vexpr.V_ConstantValueExpr
import net.postchain.rell.model.R_EnumType
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_NullableType
import net.postchain.rell.runtime.Rt_EnumValue
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.toImmList

class S_NameExpr(val qName: S_QualifiedName): S_Expr(qName.pos) {
    override fun asName() = qName

    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        val qNameHand = qName.compile(ctx)
        return compile0(ctx, hint, qNameHand)
    }

    private fun compile0(ctx: C_ExprContext, hint: C_ExprHint, qNameHand: C_QualifiedNameHandle): C_Expr {
        val hint0 = if (qNameHand.size == 1) hint else C_ExprHint.DEFAULT
        val res = resolveName(ctx, hint0, qNameHand.first)
        if (res == null) {
            qNameHand.setIdeInfo(IdeSymbolInfo.UNKNOWN)
            throw C_Error.stop(C_Errors.errUnknownName(qNameHand.first.name))
        }

        var expr: C_Expr = res
        for ((i, nameHand) in qNameHand.parts.withIndex().drop(1)) {
            val hintI = if (i == qNameHand.parts.indices.last) hint else C_ExprHint.DEFAULT
            val exprMem = expr.member(ctx, nameHand.name, hintI)
            nameHand.setIdeInfo(exprMem.ideInfo)
            expr = exprMem.expr
        }

        return expr
    }

    private fun resolveName(ctx: C_ExprContext, hint: C_ExprHint, nameHand: C_NameHandle): C_Expr? {
        val loc = resolveNameLocal(ctx, nameHand)
        val glob = resolveNameGlobal(ctx, nameHand, hint)

        val res = when {
            loc != null && glob == null -> loc
            loc == null && glob != null -> glob
            loc != null && glob != null -> when {
                !hint.callable -> loc
                loc.isCallable() -> loc
                glob.isCallable() -> glob
                else -> loc
            }
            else -> null
        }

        return res?.compile()
    }

    private fun resolveNameLocal(ctx: C_ExprContext, nameHand: C_NameHandle): NameRes? {
        val loc = ctx.blkCtx.lookupEntry(nameHand.rName)
        loc ?: return null
        val vExpr = loc.compile(ctx, nameHand.pos)
        val cExpr = C_ValueExpr(vExpr, implicitAttrMatchName = nameHand.rName)
        return NameRes_Local(nameHand, loc, cExpr)
    }

    private fun resolveNameGlobal(ctx: C_ExprContext, nameHand: C_NameHandle, hint: C_ExprHint): NameRes? {
        val qNameHand = C_QualifiedNameHandle(nameHand)
        val tag = if (hint.callable) C_NamespaceMemberTag.CALLABLE else C_NamespaceMemberTag.VALUE
        val nameRes = ctx.nsCtx.resolveName(qNameHand, tag.list)
        return if (nameRes.isValid()) NameRes_Global(ctx, nameHand.rName, nameRes) else null
    }

    override fun compileFromItem(ctx: C_ExprContext): C_AtFromItem {
        val qNameHand = qName.compile(ctx)

        val entity = ctx.nsCtx.getEntity(qNameHand, error = false, unknownInfo = false)
        if (entity != null) {
            return C_AtFromItem_Entity(qName.pos, qNameHand.last.name, entity)
        }

        val cExpr = ctx.msgCtx.consumeError {
            compile0(ctx, C_ExprHint.DEFAULT, qNameHand)
        } ?: C_ExprUtils.errorExpr(ctx, qName.pos)

        return exprToFromItem(ctx, cExpr)
    }

    override fun compileWhenEnum(ctx: C_ExprContext, type: R_EnumType): C_Expr {
        val qNameHand = qName.compile(ctx)

        val attr = if (qNameHand.size > 1) null else type.enum.attr(qNameHand.first.str)
        attr ?: return compile0(ctx, C_ExprHint.DEFAULT, qNameHand)

        qNameHand.setIdeInfo(IdeSymbolInfo(IdeSymbolKind.MEM_ENUM_VALUE))
        val value = Rt_EnumValue(type, attr)
        val vExpr = V_ConstantValueExpr(ctx, startPos, value)
        return C_ValueExpr(vExpr)
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
        private val rName: R_Name,
        private val nameRes: C_GlobalNameRes,
    ): NameRes() {
        override fun isCallable() = nameRes.isCallable()
        override fun compile(): C_Expr = nameRes.compile(ctx, implicitAttrMatchName = rName)
    }
}

class S_AttrExpr(pos: S_Pos, private val name: S_Name): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        return compileAttr(ctx, hint, name)
    }

    companion object {
        fun compileAttr(ctx: C_ExprContext, hint: C_ExprHint, name: S_Name): C_Expr {
            val nameHand = name.compile(ctx)
            val cName = nameHand.name

            val members = findMembers(ctx, hint, cName.rName)
            if (members.isEmpty()) {
                nameHand.setIdeInfo(IdeSymbolInfo.UNKNOWN)
                throw C_Errors.errUnknownAttr(cName)
            }

            if (members.size > 1) {
                val names = members.map { it.fullNameMsg() }
                val namesCode = names.joinToString(",") { it.code }
                val namesMsg = names.joinToString(", ") { it.msg }
                val code = "at_attr_name_ambig:$cName:[$namesCode]"
                val msg = "Multiple attributes with name '$cName': $namesMsg"
                ctx.msgCtx.error(cName.pos, code, msg)
            }

            val attr = members[0]
            val res = attr.compile(ctx, name.pos)
            nameHand.setIdeInfo(res.ideInfo)

            return res.expr
        }

        private fun findMembers(ctx: C_ExprContext, hint: C_ExprHint, name: R_Name): List<C_AtContextMember> {
            val members = ctx.blkCtx.lookupAtMembers(name)
            return members
                .filter { if (hint.callable) it.callable else it.valueType != null }
                .ifEmpty { members }
                .toImmList()
        }
    }
}

class S_MemberExpr(val base: S_Expr, val name: S_Name): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        val cBase = base.compileSafe(ctx)
        val nameHand = name.compile(ctx)
        val member = cBase.member(ctx, nameHand.name, hint)
        nameHand.setIdeInfo(member.ideInfo)
        return member.expr
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
        return C_ValueExpr(vExpr)
    }
}
