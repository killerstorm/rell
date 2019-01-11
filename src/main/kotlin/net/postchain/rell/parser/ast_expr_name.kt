package net.postchain.rell.parser

import net.postchain.rell.model.*

class S_NameExpr(val name: S_Name): S_Expr(name.pos) {
    override fun asName(): S_Name? = name

    override fun compile(ctx: C_ExprContext): C_Expr {
        return ctx.resolveName(name)
    }

    override fun compileWhere(ctx: C_DbExprContext, idx: Int): C_Expr {
        val clsAttrs = ctx.findAttributesByName(name.str)
        val localVar = ctx.blkCtx.lookupLocalVar(name.str)

        if (clsAttrs.isEmpty() || localVar == null) {
            return compile(ctx)
        }

        // There is a class attribute and a local variable with such name -> implicit where condition.

        val argType = localVar.type
        val clsAttrsByType = clsAttrs.filter { S_BinaryOp_EqNe.checkTypes(it.attr.type, argType) }

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
        if (!S_BinaryOp_EqNe.checkTypes(attrType, argType)) {
            throw C_Error(name.pos, "at_param_attr_type_missmatch:$name:$attrType:$argType",
                    "Parameter type does not match attribute type for '$name': $argType instead of $attrType")
        }

        val clsAttrExpr = Db_AttrExpr(Db_ClassExpr(clsAttr.cls), clsAttr.attr)

        val localAttrExpr = localVar.toVarExpr()
        val dbLocalAttrExpr = C_Utils.toDbExpr(startPos, localAttrExpr)

        val dbExpr = Db_BinaryExpr(R_BooleanType, Db_BinaryOp_Eq, clsAttrExpr, dbLocalAttrExpr)
        return C_DbExpr(startPos, dbExpr)
    }
}

class S_AttrExpr(pos: S_Pos, val name: S_Name): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        val expr = ctx.resolveAttr(name)
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

        val baseType = cBase.type()
        if (baseType !is R_NullableType) {
            throw errWrongType(baseType)
        }

        val cExpr = cBase.member(ctx, name, true)
        return cExpr
    }

    private fun errWrongType(type: R_Type): C_Error {
        return C_Error(name.pos, "expr_safemem_type:${type.toStrictString()}",
                "Wrong type for operator '?.': ${type.toStrictString()}")
    }
}
