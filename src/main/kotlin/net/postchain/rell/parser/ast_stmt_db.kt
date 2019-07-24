package net.postchain.rell.parser

import net.postchain.rell.model.*

class C_UpdateTarget(val ctx: C_ExprContext, val rTarget: R_UpdateTarget)

sealed class S_UpdateTarget {
    abstract fun compile(ctx: C_ExprContext, subValues: MutableList<C_Value>): C_UpdateTarget
}

class S_UpdateTarget_Simple(
        val cardinality: S_AtCardinality,
        val from: List<S_AtExprFrom>,
        val where: S_AtExprWhere
): S_UpdateTarget() {
    override fun compile(ctx: C_ExprContext, subValues: MutableList<C_Value>): C_UpdateTarget {
        val cFrom = S_AtExpr.compileFrom(ctx, from)
        val cls = cFrom[0].compile()
        val extraClasses = cFrom.subList(1, cFrom.size).map { it.compile() }
        val dbCtx = ctx.update(nameCtx = C_DbNameContext(ctx.blkCtx, cFrom))
        val dbWhere = where.compile(dbCtx, subValues)
        val rTarget = R_UpdateTarget_Simple(cls, extraClasses, cardinality.rCardinality, dbWhere)
        return C_UpdateTarget(dbCtx, rTarget)
    }
}

class S_UpdateTarget_Expr(val expr: S_Expr): S_UpdateTarget() {
    override fun compile(ctx: C_ExprContext, subValues: MutableList<C_Value>): C_UpdateTarget {
        val cExpr = expr.compile(ctx)
        val cValue = cExpr.value()
        subValues.add(cValue)

        val rTarget = compileTarget(cValue)
        val rCls = rTarget.cls()
        val cCls = C_AtClass(rCls.rClass, rCls.rClass.name, rCls.index)
        val dbCtx = ctx.update(nameCtx = C_DbNameContext(ctx.blkCtx, listOf(cCls)))

        return C_UpdateTarget(dbCtx, rTarget)
    }

    private fun compileTarget(cValue: C_Value): R_UpdateTarget {
        val rExpr = cValue.toRExpr()
        val type = rExpr.type

        val rTarget = if (type is R_ClassType) {
            compileTargetClass(rExpr, type.rClass)
        } else if (type is R_NullableType && type.valueType is R_ClassType) {
            compileTargetClass(rExpr, type.valueType.rClass)
        } else if (type is R_ObjectType) {
            compileTargetObject(type.rObject)
        } else if (type is R_SetType && type.elementType is R_ClassType) {
            compileTargetCollection(rExpr, type, type.elementType, true)
        } else if (type is R_ListType && type.elementType is R_ClassType) {
            compileTargetCollection(rExpr, type, type.elementType, false)
        } else {
            throw C_Error(expr.startPos, "stmt_update_expr_type:${type.toStrictString()}",
                    "Invalid expression type: ${type.toStrictString()}; must be a class or a collection of a class")
        }

        return rTarget
    }

    private fun compileTargetClass(rExpr: R_Expr, rClass: R_Class): R_UpdateTarget {
        val cls = R_AtClass(rClass, 0)
        val whereLeft = Db_ClassExpr(cls)
        val whereRight = Db_ParameterExpr(R_ClassType(rClass), 0)
        val where = C_Utils.makeDbBinaryExprEq(whereLeft, whereRight)
        return R_UpdateTarget_Expr_One(cls, where, rExpr)
    }

    private fun compileTargetObject(rObject: R_Object): R_UpdateTarget {
        return R_UpdateTarget_Object(rObject)
    }

    private fun compileTargetCollection(rExpr: R_Expr, type: R_Type, clsType: R_ClassType, set: Boolean): R_UpdateTarget {
        val rClass = clsType.rClass
        val cls = R_AtClass(rClass, 0)
        val whereLeft = Db_ClassExpr(cls)
        val whereRight = Db_ArrayParameterExpr(type, clsType, 0)
        val where = Db_BinaryExpr(R_BooleanType, Db_BinaryOp_In, whereLeft, whereRight)
        val setType = R_SetType(clsType)
        return R_UpdateTarget_Expr_Many(cls, where, rExpr, set, setType)
    }
}

class S_UpdateWhat(val pos: S_Pos, val name: S_Name?, val op: S_AssignOpCode?, val expr: S_Expr)

class S_UpdateStatement(pos: S_Pos, val target: S_UpdateTarget, val what: List<S_UpdateWhat>): S_Statement(pos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        ctx.blkCtx.entCtx.checkDbUpdateAllowed(pos)

        val subValues = mutableListOf<C_Value>()
        val cTarget = target.compile(ctx, subValues)

        val rClass = cTarget.rTarget.cls().rClass
        if (!rClass.flags.canUpdate) {
            throw C_Errors.errCannotUpdate(pos, rClass.name)
        }

        val dbWhat = compileWhat(rClass, cTarget.ctx, subValues)
        val rStmt = R_UpdateStatement(cTarget.rTarget, dbWhat)

        val resFacts = C_ExprVarFacts.forSubExpressions(subValues)
        return C_Statement(rStmt, false, resFacts.postFacts)
    }

    private fun compileWhat(cls: R_Class, ctx: C_ExprContext, subValues: MutableList<C_Value>): List<R_UpdateStatementWhat> {
        val dbWhat = what.map { compileWhatExpr(cls, ctx, it) }
        subValues.addAll(dbWhat)

        val types = dbWhat.map { it.type() }
        val whatPairs = what.map { S_NameExprPair(it.name, it.expr) }
        val attrs = C_AttributeResolver.resolveUpdate(cls, whatPairs, types)

        val updAttrs = attrs.withIndex().map { (idx, attr) ->
            val w = what[idx]
            val op = if (w.op == null) S_AssignOp_Eq else w.op.op
            op.compileDbUpdate(w.pos, attr, dbWhat[idx])
        }

        return updAttrs
    }

    private fun compileWhatExpr(cls: R_Class, ctx: C_ExprContext, pair: S_UpdateWhat): C_Value {
        val impName = pair.expr.asName()
        if (impName != null && pair.name == null) {
            val clsAttr = cls.attributes[impName.str]
            val localVar = ctx.blkCtx.lookupLocalVar(impName.str)
            if (clsAttr != null && localVar != null) {
                val rExpr = localVar.toVarExpr()
                val dbExpr = C_Utils.toDbExpr(impName.pos, rExpr)
                return C_DbValue(pair.expr.startPos, dbExpr, C_ExprVarFacts.EMPTY)
            }
        }
        return pair.expr.compile(ctx).value()
    }
}

class S_DeleteStatement(pos: S_Pos, val target: S_UpdateTarget): S_Statement(pos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        ctx.blkCtx.entCtx.checkDbUpdateAllowed(pos)

        val subValues = mutableListOf<C_Value>()
        val cTarget = target.compile(ctx, subValues)

        val rClass = cTarget.rTarget.cls().rClass
        if (rClass.flags.isObject) {
            throw C_Error(pos, "stmt_delete_obj:${rClass.name}", "Cannot delete object '${rClass.name}' (not a class)")
        } else if (!rClass.flags.canDelete) {
            throw C_Errors.errCannotDelete(pos, rClass.name)
        }

        val rStmt = R_DeleteStatement(cTarget.rTarget)
        val resFacts = C_ExprVarFacts.forSubExpressions(subValues)
        return C_Statement(rStmt, false, resFacts.postFacts)
    }
}
