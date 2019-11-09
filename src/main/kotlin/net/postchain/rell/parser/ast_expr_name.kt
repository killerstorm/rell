package net.postchain.rell.parser

import net.postchain.rell.model.R_NullableType
import net.postchain.rell.model.R_Type

class S_NameExpr(val name: S_Name): S_Expr(name.pos) {
    override fun asName(): S_Name? = name

    override fun compile(ctx: C_ExprContext): C_Expr {
        return ctx.nameCtx.resolveName(ctx, name)
    }

    override fun compileWhere(ctx: C_ExprContext, idx: Int): C_Expr {
        val entityAttrs = ctx.nameCtx.findAttributesByName(name.str)
        val localVar = ctx.blkCtx.lookupLocalVar(name.str)

        if (localVar == null) {
            return compile(ctx)
        }

        val varType = localVar.type

        val entityAttrsByType = if (!entityAttrs.isEmpty()) {
            entityAttrs.filter { C_BinOp_EqNe.checkTypesDb(it.attrRef.type(), varType) }
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

        val entityAttr = entityAttrsByType[0]
        val attrType = entityAttr.attrRef.type()
        if (!C_BinOp_EqNe.checkTypesDb(attrType, varType)) {
            throw C_Error(name.pos, "at_param_attr_type_mismatch:$name:$attrType:$varType",
                    "Parameter type does not match attribute type for '$name': $varType instead of $attrType")
        }

        val entityAttrExpr = entityAttr.compile()

        val localAttrExpr = localVar.toVarExpr()
        val dbLocalAttrExpr = C_Utils.toDbExpr(startPos, localAttrExpr)

        val dbExpr = C_Utils.makeDbBinaryExprEq(entityAttrExpr, dbLocalAttrExpr)
        return C_DbValue.makeExpr(startPos, dbExpr)
    }
}

class S_AttrExpr(pos: S_Pos, val name: S_Name): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        val expr = ctx.nameCtx.resolveAttr(name)
        return expr
    }
}

class S_MemberExpr(val base: S_Expr, val name: S_Name): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        val cBase = base.compile(ctx)
        val cExpr = cBase.member(ctx, name, false)
        return cExpr
    }
}

class S_SafeMemberExpr(val base: S_Expr, val name: S_Name): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
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
        return C_Error(name.pos, "expr_safemem_type:${type.toStrictString()}",
                "Wrong type for operator '?.': ${type.toStrictString()}")
    }
}
