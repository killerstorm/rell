/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
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
        val entity = cFrom[0].compile()
        val extraEntities = cFrom.subList(1, cFrom.size).map { it.compile() }
        val dbCtx = ctx.update(nameCtx = C_NameContext.createAt(ctx.nameCtx, cFrom))
        val dbWhere = where.compile(dbCtx, subValues)
        val rTarget = R_UpdateTarget_Simple(entity, extraEntities, cardinality.rCardinality, dbWhere)
        return C_UpdateTarget(dbCtx, rTarget)
    }
}

class S_UpdateTarget_Expr(val expr: S_Expr): S_UpdateTarget() {
    override fun compile(ctx: C_ExprContext, subValues: MutableList<C_Value>): C_UpdateTarget {
        val cExpr = expr.compile(ctx)
        val cValue = cExpr.value()
        subValues.add(cValue)

        val rTarget = compileTarget(cValue)
        val rEntity = rTarget.entity()
        val cEntity = C_AtEntity(rEntity.rEntity, rEntity.rEntity.simpleName, rEntity.index)
        val dbCtx = ctx.update(nameCtx = C_NameContext.createAt(ctx.nameCtx, listOf(cEntity)))

        return C_UpdateTarget(dbCtx, rTarget)
    }

    private fun compileTarget(cValue: C_Value): R_UpdateTarget {
        val rExpr = cValue.toRExpr()
        val type = rExpr.type

        val rTarget = if (type is R_EntityType) {
            compileTargetEntity(rExpr, type.rEntity)
        } else if (type is R_NullableType && type.valueType is R_EntityType) {
            compileTargetEntity(rExpr, type.valueType.rEntity)
        } else if (type is R_ObjectType) {
            compileTargetObject(type.rObject)
        } else if (type is R_SetType && type.elementType is R_EntityType) {
            compileTargetCollection(rExpr, type, type.elementType, true)
        } else if (type is R_ListType && type.elementType is R_EntityType) {
            compileTargetCollection(rExpr, type, type.elementType, false)
        } else {
            throw C_Error(expr.startPos, "stmt_update_expr_type:${type.toStrictString()}",
                    "Invalid expression type: ${type.toStrictString()}; must be an entity or a collection of an entity")
        }

        return rTarget
    }

    private fun compileTargetEntity(rExpr: R_Expr, rEntity: R_Entity): R_UpdateTarget {
        val entity = R_AtEntity(rEntity, 0)
        val whereLeft = Db_EntityExpr(entity)
        val whereRight = Db_ParameterExpr(rEntity.type, 0)
        val where = C_Utils.makeDbBinaryExprEq(whereLeft, whereRight)
        return R_UpdateTarget_Expr_One(entity, where, rExpr)
    }

    private fun compileTargetObject(rObject: R_Object): R_UpdateTarget {
        return R_UpdateTarget_Object(rObject)
    }

    private fun compileTargetCollection(rExpr: R_Expr, type: R_Type, entityType: R_EntityType, set: Boolean): R_UpdateTarget {
        val rEntity = entityType.rEntity
        val entity = R_AtEntity(rEntity, 0)
        val whereLeft = Db_EntityExpr(entity)
        val whereRight = Db_ArrayParameterExpr(type, entityType, 0)
        val where = Db_BinaryExpr(R_BooleanType, Db_BinaryOp_In, whereLeft, whereRight)
        val setType = R_SetType(entityType)
        return R_UpdateTarget_Expr_Many(entity, where, rExpr, set, setType)
    }
}

class S_UpdateWhat(val pos: S_Pos, val name: S_Name?, val op: S_AssignOpCode?, val expr: S_Expr)

class S_UpdateStatement(pos: S_Pos, val target: S_UpdateTarget, val what: List<S_UpdateWhat>): S_Statement(pos) {
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        ctx.defCtx.checkDbUpdateAllowed(pos)

        val subValues = mutableListOf<C_Value>()
        val cTarget = target.compile(ctx.exprCtx, subValues)

        val rEntity = cTarget.rTarget.entity().rEntity
        if (!rEntity.flags.canUpdate) {
            throw C_Errors.errCannotUpdate(pos, rEntity.simpleName)
        }

        val dbWhat = compileWhat(rEntity, cTarget.ctx, subValues)
        val rStmt = R_UpdateStatement(cTarget.rTarget, dbWhat)

        val resFacts = C_ExprVarFacts.forSubExpressions(subValues)
        return C_Statement(rStmt, false, resFacts.postFacts)
    }

    private fun compileWhat(entity: R_Entity, ctx: C_ExprContext, subValues: MutableList<C_Value>): List<R_UpdateStatementWhat> {
        val dbWhat = what.map { compileWhatExpr(entity, ctx, it) }
        subValues.addAll(dbWhat)

        val types = dbWhat.map { it.type() }
        val whatPairs = what.map { S_NameExprPair(it.name, it.expr) }
        val attrs = C_AttributeResolver.resolveUpdate(entity, whatPairs, types)

        val updAttrs = attrs.withIndex().map { (idx, attr) ->
            val w = what[idx]
            val op = if (w.op == null) S_AssignOp_Eq else w.op.op
            op.compileDbUpdate(w.pos, attr, dbWhat[idx])
        }

        return updAttrs
    }

    private fun compileWhatExpr(entity: R_Entity, ctx: C_ExprContext, pair: S_UpdateWhat): C_Value {
        val impName = pair.expr.asName()
        if (impName != null && pair.name == null) {
            val entityAttr = entity.attributes[impName.str]
            val localVar = ctx.nameCtx.resolveNameLocalValue(impName.str)
            if (entityAttr != null && localVar != null) {
                val rExpr = localVar.toVarExpr()
                val dbExpr = C_Utils.toDbExpr(impName.pos, rExpr)
                return C_DbValue(pair.expr.startPos, dbExpr, C_ExprVarFacts.EMPTY)
            }
        }
        return pair.expr.compile(ctx).value()
    }
}

class S_DeleteStatement(pos: S_Pos, val target: S_UpdateTarget): S_Statement(pos) {
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        ctx.defCtx.checkDbUpdateAllowed(pos)

        val subValues = mutableListOf<C_Value>()
        val cTarget = target.compile(ctx.exprCtx, subValues)

        val rEntity = cTarget.rTarget.entity().rEntity
        val msgName = rEntity.simpleName

        if (rEntity.flags.isObject) {
            throw C_Error(pos, "stmt_delete_obj:$msgName", "Cannot delete object '$msgName' (not an entity)")
        } else if (!rEntity.flags.canDelete) {
            throw C_Errors.errCannotDelete(pos, msgName)
        }

        val rStmt = R_DeleteStatement(cTarget.rTarget)
        val resFacts = C_ExprVarFacts.forSubExpressions(subValues)
        return C_Statement(rStmt, false, resFacts.postFacts)
    }
}
