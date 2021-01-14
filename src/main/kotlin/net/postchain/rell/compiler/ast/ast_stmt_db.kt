/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.vexpr.V_DbExpr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.*
import net.postchain.rell.utils.toImmList

class C_UpdateTarget(val ctx: C_ExprContext, val rTarget: R_UpdateTarget)

sealed class S_UpdateTarget {
    abstract fun compile(ctx: C_ExprContext, subValues: MutableList<V_Expr>): C_UpdateTarget?
}

class S_UpdateTarget_Simple(
        val cardinality: R_AtCardinality,
        val from: List<S_AtExprFrom>,
        val where: S_AtExprWhere
): S_UpdateTarget() {
    override fun compile(ctx: C_ExprContext, subValues: MutableList<V_Expr>): C_UpdateTarget {
        val entities = compileFromEntities(ctx, from)
        val entity = entities[0].compile()
        val extraEntities = entities.subList(1, entities.size).map { it.compile() }

        val cFrom: C_AtFrom = C_AtFrom_Entities(ctx, entities)
        val atCtx = cFrom.innerExprCtx()
        val dbWhere = where.compile(atCtx, subValues)?.toDbExpr(ctx.msgCtx)

        val rTarget = R_UpdateTarget_Simple(entity, extraEntities, cardinality, dbWhere)
        return C_UpdateTarget(atCtx, rTarget)
    }

    private fun compileFromEntities(ctx: C_ExprContext, from: List<S_AtExprFrom>): List<C_AtEntity> {
        val cFrom = from.mapIndexed { i, f -> compileFromEntity(ctx, i, f) }

        val names = mutableSetOf<String>()
        for ((alias, entity) in cFrom) {
            if (!names.add(entity.alias)) {
                ctx.msgCtx.error(alias.pos, "at_dup_alias:${entity.alias}", "Duplicate entity alias: ${entity.alias}")
            }
        }

        return cFrom.map { ( _, entity ) -> entity }
    }

    private fun compileFromEntity(ctx: C_ExprContext, idx: Int, from: S_AtExprFrom): Pair<S_Name, C_AtEntity> {
        if (from.alias != null) {
            val name = from.alias
            val localVar = ctx.nameCtx.resolveNameLocalValue(name.str)
            if (localVar != null) {
                ctx.msgCtx.error(name.pos, "expr_at_conflict_alias:${name.str}", "Name conflict: '${name.str}'")
            }
        }

        val explicitAlias = from.alias
        val alias = explicitAlias ?: from.entityName[from.entityName.size - 1]

        val entity = ctx.nsCtx.getEntity(from.entityName)
        return Pair(alias, C_AtEntity(alias.pos, entity, alias.str, explicitAlias != null, idx))
    }
}

class S_UpdateTarget_Expr(val expr: S_Expr): S_UpdateTarget() {
    override fun compile(ctx: C_ExprContext, subValues: MutableList<V_Expr>): C_UpdateTarget? {
        val cExpr = expr.compile(ctx)
        val cValue = cExpr.value()
        subValues.add(cValue)

        val rTarget = compileTarget(ctx, cValue)
        rTarget ?: return null

        val rEntity = rTarget.entity()
        val cEntity = C_AtEntity(expr.startPos, rEntity.rEntity, rEntity.rEntity.simpleName, false, rEntity.index)

        val cFrom: C_AtFrom = C_AtFrom_Entities(ctx, listOf(cEntity))
        val dbCtx = cFrom.innerExprCtx()

        return C_UpdateTarget(dbCtx, rTarget)
    }

    private fun compileTarget(ctx: C_ExprContext, vExpr: V_Expr): R_UpdateTarget? {
        val rExpr = vExpr.toRExpr()
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
            if (type != R_CtErrorType) {
                ctx.msgCtx.error(expr.startPos, "stmt_update_expr_type:${type.toStrictString()}",
                        "Invalid expression type: ${type.toStrictString()}; must be an entity or a collection of an entity")
            }
            null
        }

        return rTarget
    }

    private fun compileTargetEntity(rExpr: R_Expr, rEntity: R_EntityDefinition): R_UpdateTarget {
        val entity = R_DbAtEntity(rEntity, 0)
        val whereLeft = Db_EntityExpr(entity)
        val whereRight = Db_ParameterExpr(rEntity.type, 0)
        val where = C_Utils.makeDbBinaryExprEq(whereLeft, whereRight)
        return R_UpdateTarget_Expr_One(entity, where, rExpr)
    }

    private fun compileTargetObject(rObject: R_ObjectDefinition): R_UpdateTarget {
        return R_UpdateTarget_Object(rObject)
    }

    private fun compileTargetCollection(rExpr: R_Expr, type: R_Type, entityType: R_EntityType, set: Boolean): R_UpdateTarget {
        val rEntity = entityType.rEntity
        val entity = R_DbAtEntity(rEntity, 0)
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
        ctx.checkDbUpdateAllowed(pos)

        val subValues = mutableListOf<V_Expr>()
        val cTarget = target.compile(ctx.exprCtx, subValues)

        if (cTarget == null) {
            what.forEach { it.expr.compileSafe(ctx.exprCtx) }
            return C_Statement.ERROR
        }

        val rEntity = cTarget.rTarget.entity().rEntity
        if (!rEntity.flags.canUpdate) {
            C_Errors.errCannotUpdate(ctx.msgCtx, pos, rEntity.simpleName)
        }

        val dbWhat = compileWhat(cTarget.ctx, rEntity, subValues)
        val rStmt = R_UpdateStatement(cTarget.rTarget, dbWhat)

        val resFacts = C_ExprVarFacts.forSubExpressions(subValues)
        return C_Statement(rStmt, false, resFacts.postFacts)
    }

    private fun compileWhat(
            ctx: C_ExprContext,
            entity: R_EntityDefinition,
            subValues: MutableList<V_Expr>
    ): List<R_UpdateStatementWhat> {
        val args = what.mapIndexed { i, w -> C_Argument(i, w.name, w.expr, compileWhatExpr(entity, ctx, w)) }
        subValues.addAll(args.map { it.vExpr })

        val attrs = C_AttributeResolver.resolveUpdate(ctx.msgCtx, entity, args)

        val updAttrs = attrs.mapNotNull { (arg, attr) ->
            val w = what[arg.index]
            val op = if (w.op == null) S_AssignOp_Eq else w.op.op
            op.compileDbUpdate(ctx, w.pos, attr, arg.vExpr)
        }.toImmList()

        return updAttrs
    }

    private fun compileWhatExpr(entity: R_EntityDefinition, ctx: C_ExprContext, pair: S_UpdateWhat): V_Expr {
        val impName = pair.expr.asName()
        if (impName != null && pair.name == null) {
            val entityAttr = entity.attributes[impName.str]
            val localVar = ctx.nameCtx.resolveNameLocalValue(impName.str)
            if (entityAttr != null && localVar != null) {
                val rExpr = localVar.toExpr()
                val dbExpr = C_Utils.toDbExpr(impName.pos, rExpr)
                return V_DbExpr.create(pair.expr.startPos, dbExpr, C_ExprVarFacts.EMPTY)
            }
        }
        return pair.expr.compileSafe(ctx).value()
    }
}

class S_DeleteStatement(pos: S_Pos, val target: S_UpdateTarget): S_Statement(pos) {
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        ctx.checkDbUpdateAllowed(pos)

        val subValues = mutableListOf<V_Expr>()
        val cTarget = target.compile(ctx.exprCtx, subValues)
        cTarget ?: return C_Statement.ERROR

        val rEntity = cTarget.rTarget.entity().rEntity
        val msgName = rEntity.simpleName

        if (rEntity.flags.isObject) {
            throw C_Error.more(pos, "stmt_delete_obj:$msgName", "Cannot delete object '$msgName' (not an entity)")
        } else if (!rEntity.flags.canDelete) {
            throw C_Errors.errCannotDelete(pos, msgName)
        }

        val rStmt = R_DeleteStatement(cTarget.rTarget)
        val resFacts = C_ExprVarFacts.forSubExpressions(subValues)
        return C_Statement(rStmt, false, resFacts.postFacts)
    }
}
