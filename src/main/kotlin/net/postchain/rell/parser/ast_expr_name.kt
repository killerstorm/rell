package net.postchain.rell.parser

import net.postchain.rell.model.Db_AttrExpr
import net.postchain.rell.model.R_NullableType
import net.postchain.rell.model.R_Type

class S_NameExpr(val name: S_Name): S_Expr(name.pos) {
    override fun asName(): S_Name? = name

    override fun compile(ctx: C_ExprContext): C_Expr {
        return ctx.nameCtx.resolveName(ctx, name)
    }

    override fun compileWhere(ctx: C_ExprContext, idx: Int): C_Expr {
        val clsAttrs = ctx.nameCtx.findAttributesByName(name.str)
        val localVar = ctx.blkCtx.lookupLocalVar(name.str)

        if (clsAttrs.isEmpty() || localVar == null) {
            return compile(ctx)
        }

        // There is a class attribute and a local variable with such name -> implicit where condition.

        val argType = localVar.type
        val clsAttrsByType = clsAttrs.filter { C_BinOp_EqNe.checkTypesDb(it.attr.type, argType) }

        if (clsAttrsByType.isEmpty()) {
            val typeStr = argType.toStrictString()
            throw C_Error(name.pos, "at_attr_type:$idx:${name.str}:$typeStr",
                    "No attribute '${name.str}' of type $typeStr")
        } else if (clsAttrsByType.size > 1) {
            throw C_Errors.errMutlipleAttrs(name.pos, clsAttrsByType, "at_attr_name_ambig:$idx:${name.str}",
                    "Multiple attributes match '${name.str}'")
        }

        val clsAttr = clsAttrsByType[0]
        val attrType = clsAttr.attr.type
        if (!C_BinOp_EqNe.checkTypesDb(attrType, argType)) {
            throw C_Error(name.pos, "at_param_attr_type_mismatch:$name:$attrType:$argType",
                    "Parameter type does not match attribute type for '$name': $argType instead of $attrType")
        }

        val clsExpr = clsAttr.cls.compileExpr()
        val clsAttrExpr = Db_AttrExpr(clsExpr, clsAttr.attr)

        val localAttrExpr = localVar.toVarExpr()
        val dbLocalAttrExpr = C_Utils.toDbExpr(startPos, localAttrExpr)

        val dbExpr = C_Utils.makeDbBinaryExprEq(clsAttrExpr, dbLocalAttrExpr)
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
