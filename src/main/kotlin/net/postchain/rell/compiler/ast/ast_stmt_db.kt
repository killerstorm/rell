/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.*
import net.postchain.rell.utils.toImmList

class C_UpdateTarget(val rTarget: R_UpdateTarget, val cFrom: C_AtFrom_Entities)

sealed class S_UpdateTarget {
    abstract fun compile(ctx: C_ExprContext, atExprId: R_AtExprId, subValues: MutableList<V_Expr>): C_UpdateTarget?
}

class S_UpdateTarget_Simple(
        val cardinality: R_AtCardinality,
        val from: List<S_AtExprFrom>,
        val where: S_AtExprWhere
): S_UpdateTarget() {
    override fun compile(ctx: C_ExprContext, atExprId: R_AtExprId, subValues: MutableList<V_Expr>): C_UpdateTarget {
        val cAtEntities = compileFromEntities(ctx, atExprId, from)
        val rAtEntities = cAtEntities.map { it.toRAtEntity() }
        val entity = rAtEntities[0]
        val extraEntities = rAtEntities.subList(1, rAtEntities.size)

        val cFrom = C_AtFrom_Entities(ctx, cAtEntities, null)
        val atCtx = cFrom.innerExprCtx()
        val dbWhere = where.compile(atCtx, subValues)?.toDbExpr()

        val rTarget: R_UpdateTarget = R_UpdateTarget_Simple(entity, extraEntities, cardinality, dbWhere)
        return C_UpdateTarget(rTarget, cFrom)
    }

    private fun compileFromEntities(ctx: C_ExprContext, atExprId: R_AtExprId, from: List<S_AtExprFrom>): List<C_AtEntity> {
        val cFrom = from.map { f -> compileFromEntity(ctx, atExprId, f) }
        return cFrom.map { ( _, entity ) -> entity }
    }

    private fun compileFromEntity(ctx: C_ExprContext, atExprId: R_AtExprId, from: S_AtExprFrom): Pair<S_Name, C_AtEntity> {
        val explicitAlias = from.alias
        val alias = explicitAlias ?: from.entityName[from.entityName.size - 1]
        val entity = ctx.nsCtx.getEntity(from.entityName)
        val atEntityId = ctx.appCtx.nextAtEntityId(atExprId)
        return Pair(alias, C_AtEntity(alias.pos, entity, alias.str, explicitAlias != null, atEntityId))
    }
}

class S_UpdateTarget_Expr(val expr: S_Expr): S_UpdateTarget() {
    override fun compile(ctx: C_ExprContext, atExprId: R_AtExprId, subValues: MutableList<V_Expr>): C_UpdateTarget? {
        val cExpr = expr.compile(ctx)
        val cValue = cExpr.value()
        subValues.add(cValue)
        return compileTarget(ctx, atExprId, cValue)
    }

    private fun compileTarget(ctx: C_ExprContext, atExprId: R_AtExprId, vExpr: V_Expr): C_UpdateTarget? {
        val rExpr = vExpr.toRExpr()
        val type = rExpr.type
        return if (type is R_EntityType) {
            compileTargetEntity(ctx, atExprId, rExpr, type.rEntity)
        } else if (type is R_NullableType && type.valueType is R_EntityType) {
            compileTargetEntity(ctx, atExprId, rExpr, type.valueType.rEntity)
        } else if (type is R_ObjectType) {
            compileTargetObject(ctx, atExprId, type.rObject)
        } else if (type is R_SetType && type.elementType is R_EntityType) {
            compileTargetCollection(ctx, atExprId, rExpr, type.elementType, true)
        } else if (type is R_ListType && type.elementType is R_EntityType) {
            compileTargetCollection(ctx, atExprId, rExpr, type.elementType, false)
        } else {
            if (type != R_CtErrorType) {
                ctx.msgCtx.error(expr.startPos, "stmt_update_expr_type:${type.toStrictString()}",
                        "Invalid expression type: ${type.toStrictString()}; must be an entity or a collection of an entity")
            }
            null
        }
    }

    private fun compileTargetEntity(ctx: C_ExprContext, atExprId: R_AtExprId, rExpr: R_Expr, rEntity: R_EntityDefinition): C_UpdateTarget {
        val rAtEntity = ctx.makeAtEntity(rEntity, atExprId)
        val whereLeft = Db_EntityExpr(rAtEntity)

        val cLambdaB = C_LambdaBlock.builder(ctx, rEntity.type)
        val cFrom = compileFrom(cLambdaB.innerExprCtx, rAtEntity)
        val cLambda = cLambdaB.build()

        val whereRight = cLambda.compileVarDbExpr(cFrom.innerExprCtx().blkCtx.blockUid)
        val where = C_Utils.makeDbBinaryExprEq(whereLeft, whereRight)
        val rTarget = R_UpdateTarget_Expr_One(rAtEntity, where, rExpr, cLambda.rLambda)

        return C_UpdateTarget(rTarget, cFrom)
    }

    private fun compileTargetObject(ctx: C_ExprContext, atExprId: R_AtExprId, rObject: R_ObjectDefinition): C_UpdateTarget {
        val rAtEntity = ctx.makeAtEntity(rObject.rEntity, atExprId)
        val rTarget = R_UpdateTarget_Object(rAtEntity)
        val cFrom = compileFrom(ctx, rAtEntity)
        return C_UpdateTarget(rTarget, cFrom)
    }

    private fun compileTargetCollection(
            ctx: C_ExprContext,
            atExprId: R_AtExprId,
            rExpr: R_Expr,
            entityType: R_EntityType,
            set: Boolean
    ): C_UpdateTarget {
        val rAtEntity = ctx.makeAtEntity(entityType.rEntity, atExprId)
        val listType: R_Type = R_ListType(entityType)

        val cLambdaB = C_LambdaBlock.builder(ctx, listType)
        val cFrom = compileFrom(cLambdaB.innerExprCtx, rAtEntity)
        val cLambda = cLambdaB.build()

        val whereLeft = Db_EntityExpr(rAtEntity)
        val whereRight = Db_CollectionInterpretedExpr(cLambda.compileVarRExpr(cFrom.innerExprCtx().blkCtx.blockUid))
        val where = Db_BinaryExpr(R_BooleanType, Db_BinaryOp_In, whereLeft, whereRight)
        val rTarget = R_UpdateTarget_Expr_Many(rAtEntity, where, rExpr, cLambda.rLambda, set, listType)
        return C_UpdateTarget(rTarget, cFrom)
    }

    private fun compileFrom(ctx: C_ExprContext, rAtEntity: R_DbAtEntity): C_AtFrom_Entities {
        val cEntity = C_AtEntity(expr.startPos, rAtEntity.rEntity, rAtEntity.rEntity.simpleName, false, rAtEntity.id)
        return C_AtFrom_Entities(ctx, listOf(cEntity), null)
    }
}

class S_UpdateWhat(val pos: S_Pos, val name: S_Name?, val op: S_AssignOpCode?, val expr: S_Expr)

class S_UpdateStatement(pos: S_Pos, val target: S_UpdateTarget, val what: List<S_UpdateWhat>): S_Statement(pos) {
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        ctx.checkDbUpdateAllowed(pos)

        val atExprId = ctx.appCtx.nextAtExprId()
        val subValues = mutableListOf<V_Expr>()
        val cTarget = target.compile(ctx.exprCtx, atExprId, subValues)

        if (cTarget == null) {
            what.forEach { it.expr.compileSafe(ctx.exprCtx) }
            return C_Statement.ERROR
        }

        val rEntity = cTarget.rTarget.entity().rEntity
        if (!rEntity.flags.canUpdate) {
            C_Errors.errCannotUpdate(ctx.msgCtx, pos, rEntity.simpleName)
        }

        val dbWhat = compileWhat(cTarget.cFrom.innerExprCtx(), rEntity, subValues)

        val rFromBlock = cTarget.cFrom.compileUpdate()
        val rStmt = R_UpdateStatement(cTarget.rTarget, rFromBlock, dbWhat)

        val resFacts = C_ExprVarFacts.forSubExpressions(subValues)
        return C_Statement(rStmt, false, resFacts.postFacts)
    }

    private fun compileWhat(
            ctx: C_ExprContext,
            entity: R_EntityDefinition,
            subValues: MutableList<V_Expr>
    ): List<R_UpdateStatementWhat> {
        val args = what.mapIndexed { i, w ->
            val vExpr = w.expr.compileSafe(ctx).value()
            C_Argument(i, w.name, w.expr, vExpr)
        }
        subValues.addAll(args.map { it.vExpr })

        val attrs = C_AttributeResolver.resolveUpdate(ctx.msgCtx, entity, args)

        val updAttrs = attrs.mapNotNull { (arg, attr) ->
            val w = what[arg.index]
            val op = if (w.op == null) S_AssignOp_Eq else w.op.op
            op.compileDbUpdate(ctx, w.pos, attr, arg.vExpr)
        }.toImmList()

        return updAttrs
    }
}

class S_DeleteStatement(pos: S_Pos, val target: S_UpdateTarget): S_Statement(pos) {
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        ctx.checkDbUpdateAllowed(pos)

        val atExprId = ctx.appCtx.nextAtExprId()
        val subValues = mutableListOf<V_Expr>()
        val cTarget = target.compile(ctx.exprCtx, atExprId, subValues)
        cTarget ?: return C_Statement.ERROR

        val rEntity = cTarget.rTarget.entity().rEntity

        val msgName = rEntity.simpleName
        if (rEntity.flags.isObject) {
            throw C_Error.more(pos, "stmt_delete_obj:$msgName", "Cannot delete object '$msgName' (not an entity)")
        } else if (!rEntity.flags.canDelete) {
            throw C_Errors.errCannotDelete(pos, msgName)
        }

        val rFromBlock = cTarget.cFrom.compileUpdate()
        val rStmt = R_DeleteStatement(cTarget.rTarget, rFromBlock)

        val resFacts = C_ExprVarFacts.forSubExpressions(subValues)
        return C_Statement(rStmt, false, resFacts.postFacts)
    }
}
