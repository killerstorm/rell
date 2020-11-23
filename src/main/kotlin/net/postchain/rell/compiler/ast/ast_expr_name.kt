/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.model.R_BooleanType
import net.postchain.rell.model.R_NullableType
import net.postchain.rell.model.R_Type
import net.postchain.rell.utils.toImmList

class S_NameExpr(val name: S_Name): S_Expr(name.pos) {
    override fun asName(): S_Name? = name

    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        return ctx.nameCtx.resolveName(ctx, name)
    }

    override fun compileWhere(ctx: C_ExprContext, idx: Int): C_Expr {
        val localVar = ctx.nameCtx.resolveNameLocalValue(name.str)
        if (localVar == null) {
            return compile(ctx)
        }

        val res = C_NameResolution_Local(name, ctx, localVar)
        val vExpr = res.toExpr().value()
        val varType = vExpr.type()

        val entityAttrs = ctx.nameCtx.findAttributesByName(name)
        if (entityAttrs.isEmpty() && varType == R_BooleanType) {
            return compile(ctx)
        }

        val entityAttr = matchAttribute(ctx, idx, entityAttrs, varType)
        val attrType = entityAttr.type
        if (!C_BinOp_EqNe.checkTypesDb(attrType, varType)) {
            throw C_Error(name.pos, "at_param_attr_type_mismatch:$name:$attrType:$varType",
                    "Parameter type does not match attribute type for '$name': $varType instead of $attrType")
        }

        val entityAttrExpr = entityAttr.compile(startPos)
        val vResExpr = C_Utils.makeVBinaryExprEq(startPos, entityAttrExpr, vExpr)
        return C_VExpr(vResExpr)
    }

    private fun matchAttribute(
            ctx: C_ExprContext,
            idx: Int,
            entityAttrs: List<C_ExprContextAttr>,
            varType: R_Type
    ): C_ExprContextAttr {
        val entityAttrsByType = if (!entityAttrs.isEmpty()) {
            entityAttrs.filter { C_BinOp_EqNe.checkTypesDb(it.type, varType) }
        } else {
            S_AtExpr.findWhereContextAttrsByType(ctx, varType)
        }

        if (entityAttrsByType.isEmpty()) {
            throw C_Error(name.pos, "at_where:var_noattrs:$idx:${name.str}:$varType",
                    "No attribute matches variable '${name.str}' by name or type ($varType)")
        } else if (entityAttrsByType.size > 1) {
            if (entityAttrs.isEmpty()) {
                throw C_Errors.errMultipleAttrs(
                        name.pos,
                        entityAttrsByType,
                        "at_where:var_manyattrs_name:$idx:${name.str}:$varType",
                        "Multiple attributes match variable '${name.str}' by type ($varType)"
                )
            } else {
                throw C_Errors.errMultipleAttrs(
                        name.pos,
                        entityAttrsByType,
                        "at_where:var_manyattrs_nametype:$idx:${name.str}:$varType",
                        "Multiple attributes match variable '${name.str}' by name and type ($varType)"
                )
            }
        }

        return entityAttrsByType[0]
    }

    override fun compileFromItem(ctx: C_ExprContext): C_AtFromItem {
        val entity = ctx.nsCtx.getEntityOpt(listOf(name))
        if (entity != null) {
            return C_AtFromItem_Entity(name.pos, name.str, entity)
        }
        return super.compileFromItem(ctx)
    }
}

class S_AttrExpr(pos: S_Pos, val name: S_Name): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val vExpr = ctx.nameCtx.resolveAttr(name)
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
                return C_AtFromItem_Entity(names[0].pos, names.last().str, entity)
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
            throw errWrongType(baseType)
        }

        val smartType = baseValue.type()
        if (smartType !is R_NullableType) {
            return baseValue.member(ctx, name, false)
        } else {
            return baseValueNullable.member(ctx, name, true)
        }
    }

    private fun errWrongType(type: R_Type): C_Error {
        return C_Error(name.pos, "expr_safemem_type:[${type.toStrictString()}]",
                "Wrong type for operator '?.': ${type.toStrictString()}")
    }
}

class S_DollarExpr(pos: S_Pos): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val vExpr = ctx.nameCtx.resolvePlaceholder(startPos)
        return C_VExpr(vExpr)
    }
}
