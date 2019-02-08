package net.postchain.rell.parser

import net.postchain.rell.model.*

sealed class S_UpdateTarget {
    abstract fun compile(ctx: C_ExprContext): Pair<C_DbExprContext, R_UpdateTarget>
}

class S_UpdateTarget_Simple(
        val cardinality: S_AtCardinality,
        val from: List<S_AtExprFrom>,
        val where: S_AtExprWhere
): S_UpdateTarget() {
    override fun compile(ctx: C_ExprContext): Pair<C_DbExprContext, R_UpdateTarget> {
        val rFrom = S_AtExpr.compileFrom(ctx, from)
        val cls = rFrom[0]
        val extraClasses = rFrom.subList(1, rFrom.size)
        val dbCtx = C_DbExprContext(ctx.blkCtx, rFrom)
        val dbWhere = where.compile(dbCtx)
        val rTarget = R_UpdateTarget_Simple(cls, extraClasses, cardinality.rCardinality, dbWhere)
        return Pair(dbCtx, rTarget)
    }
}

class S_UpdateTarget_Expr(val expr: S_Expr): S_UpdateTarget() {
    override fun compile(ctx: C_ExprContext): Pair<C_DbExprContext, R_UpdateTarget> {
        val target = compileTarget(ctx)
        val dbCtx = C_DbExprContext(ctx.blkCtx, listOf(target.cls()))
        return Pair(dbCtx, target)
    }

    private fun compileTarget(ctx: C_ExprContext): R_UpdateTarget {
        val cExpr = expr.compile(ctx)
        val rExpr = cExpr.toRExpr()
        val type = rExpr.type

        if (type is R_ClassType) {
            return compileTargetClass(rExpr, type.rClass)
        } else if (type is R_NullableType && type.valueType is R_ClassType) {
            return compileTargetClass(rExpr, type.valueType.rClass)
        } else if (type is R_ObjectType) {
            return compileTargetObject(type.rObject)
        } else if (type is R_SetType && type.elementType is R_ClassType) {
            return compileTargetCollection(rExpr, type, type.elementType, true)
        } else if (type is R_ListType && type.elementType is R_ClassType) {
            return compileTargetCollection(rExpr, type, type.elementType, false)
        } else {
            throw C_Error(expr.startPos, "stmt_update_expr_type:${type.toStrictString()}",
                    "Invalid expression type: ${type.toStrictString()}; must be a class or a collection of a class")
        }
    }

    private fun compileTargetClass(rExpr: R_Expr, rClass: R_Class): R_UpdateTarget {
        val cls = R_AtClass(rClass, rClass.name, 0)
        val whereLeft = Db_ClassExpr(cls)
        val whereRight = Db_ParameterExpr(R_ClassType(rClass), 0)
        val where = Db_BinaryExpr(R_BooleanType, Db_BinaryOp_Eq, whereLeft, whereRight)
        return R_UpdateTarget_Expr_One(cls, where, rExpr)
    }

    private fun compileTargetObject(rObject: R_Object): R_UpdateTarget {
        val rClass = rObject.rClass
        val cls = R_AtClass(rClass, rClass.name, 0)
        return R_UpdateTarget_Object(cls, rObject)
    }

    private fun compileTargetCollection(rExpr: R_Expr, type: R_Type, clsType: R_ClassType, set: Boolean): R_UpdateTarget {
        val rClass = clsType.rClass
        val cls = R_AtClass(rClass, rClass.name, 0)
        val whereLeft = Db_ClassExpr(cls)
        val whereRight = Db_ArrayParameterExpr(type, clsType, 0)
        val where = Db_BinaryExpr(R_BooleanType, Db_BinaryOp_In, whereLeft, whereRight)
        val setType = R_SetType(clsType)
        return R_UpdateTarget_Expr_Many(cls, where, rExpr, set, setType)
    }
}

class S_UpdateWhat(val pos: S_Pos, val name: S_Name?, val op: S_AssignOpCode?, val expr: S_Expr)

class S_UpdateStatement(
        val pos: S_Pos,
        val target: S_UpdateTarget,
        val what: List<S_UpdateWhat>): S_Statement()
{
    override fun compile(ctx: C_ExprContext): R_Statement {
        ctx.blkCtx.entCtx.checkDbUpdateAllowed(pos)

        val (dbCtx, rTarget) = target.compile(ctx)

        val rClass = rTarget.cls().rClass
        if (!rClass.flags.canUpdate) {
            throw C_Errors.errCannotUpdate(pos, rClass.name)
        }

        val dbWhat = compileWhat(rClass, dbCtx)

        return R_UpdateStatement(rTarget, dbWhat)
    }

    private fun compileWhat(cls: R_Class, dbCtx: C_DbExprContext): List<R_UpdateStatementWhat> {
        val dbWhat = what.map { compileWhatExpr(cls, dbCtx, it) }
        val types = dbWhat.map { it.type }
        val whatPairs = what.map { S_NameExprPair(it.name, it.expr) }
        val attrs = C_AttributeResolver.resolveUpdate(cls, whatPairs, types)
        val updAttrs = attrs.withIndex().map { (idx, attr) ->
            val w = what[idx]
            val op = if (w.op == null) S_AssignOp_Eq else w.op.op
            op.compileDbUpdate(w.pos, attr, dbWhat[idx])
        }
        return updAttrs
    }

    private fun compileWhatExpr(cls: R_Class, ctx: C_DbExprContext, pair: S_UpdateWhat): Db_Expr {
        val impName = pair.expr.asName()
        if (impName != null && pair.name == null) {
            val clsAttr = cls.attributes[impName.str]
            val localVar = ctx.blkCtx.lookupLocalVar(impName.str)
            if (clsAttr != null && localVar != null) {
                val rExpr = localVar.toVarExpr()
                return C_Utils.toDbExpr(impName.pos, rExpr)
            }
        }
        return pair.expr.compile(ctx).toDbExpr()
    }
}

class S_DeleteStatement(val pos: S_Pos, val target: S_UpdateTarget): S_Statement() {
    override fun compile(ctx: C_ExprContext): R_Statement {
        ctx.blkCtx.entCtx.checkDbUpdateAllowed(pos)

        val (_, rTarget) = target.compile(ctx)

        val rClass = rTarget.cls().rClass
        if (rClass.flags.isObject) {
            throw C_Error(pos, "stmt_delete_obj:${rClass.name}", "Cannot delete object '${rClass.name}' (not a class)")
        } else if (!rClass.flags.canDelete) {
            throw C_Errors.errCannotDelete(pos, rClass.name)
        }

        return R_DeleteStatement(rTarget)
    }
}
