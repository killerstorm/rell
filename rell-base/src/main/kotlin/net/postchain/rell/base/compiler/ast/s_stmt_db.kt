/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.ast

import net.postchain.rell.base.compiler.base.core.C_IdeSymbolInfo
import net.postchain.rell.base.compiler.base.core.C_LambdaBlock
import net.postchain.rell.base.compiler.base.core.C_Name
import net.postchain.rell.base.compiler.base.core.C_Statement
import net.postchain.rell.base.compiler.base.expr.*
import net.postchain.rell.base.compiler.base.utils.C_Error
import net.postchain.rell.base.compiler.base.utils.C_Errors
import net.postchain.rell.base.compiler.vexpr.V_Expr
import net.postchain.rell.base.model.*
import net.postchain.rell.base.model.expr.*
import net.postchain.rell.base.model.stmt.*
import net.postchain.rell.base.utils.ide.IdeSymbolKind
import net.postchain.rell.base.utils.immListOf
import net.postchain.rell.base.utils.toImmList

class C_UpdateTarget(val rTarget: R_UpdateTarget, val cFrom: C_AtFrom_Entities)

sealed class S_UpdateTarget {
    abstract fun compile(
            ctx: C_ExprContext,
            stmtPos: S_Pos,
            atExprId: R_AtExprId,
            subValues: MutableList<V_Expr>
    ): C_UpdateTarget?
}

class S_UpdateTarget_Simple(
        private val cardinality: R_AtCardinality,
        private val from: List<S_AtExprFrom>,
        private val where: S_AtExprWhere
): S_UpdateTarget() {
    override fun compile(
            ctx: C_ExprContext,
            stmtPos: S_Pos,
            atExprId: R_AtExprId,
            subValues: MutableList<V_Expr>
    ): C_UpdateTarget? {
        val cAtEntities = compileFromEntities(ctx, atExprId, from)
        cAtEntities ?: return null

        val rAtEntities = cAtEntities.map { it.toRAtEntity() }
        val entity = rAtEntities[0]
        val extraEntities = rAtEntities.subList(1, rAtEntities.size)

        val fromCtx = C_AtFromContext(stmtPos, atExprId, null)
        val cFrom = C_AtFrom_Entities(ctx, fromCtx, cAtEntities)

        val atCtx = cFrom.innerExprCtx()
        val dbWhere = where.compile(atCtx, cFrom.atExprId, subValues)?.toDbExpr()

        val rTarget: R_UpdateTarget = R_UpdateTarget_Simple(entity, extraEntities, cardinality, dbWhere)
        return C_UpdateTarget(rTarget, cFrom)
    }

    private fun compileFromEntities(ctx: C_ExprContext, atExprId: R_AtExprId, from: List<S_AtExprFrom>): List<C_AtEntity>? {
        val cFrom0 = from.map { f -> compileFromEntity(ctx, atExprId, f) }
        val cFrom = cFrom0.filterNotNull()
        return if (cFrom.size != cFrom0.size) null else cFrom
    }

    private fun compileFromEntity(ctx: C_ExprContext, atExprId: R_AtExprId, from: S_AtExprFrom): C_AtEntity? {
        val explicitAliasHand = from.alias?.compile(ctx)
        val entityNameHand = from.entityName.compile(ctx.symCtx)

        val entity = ctx.nsCtx.getEntity(entityNameHand)
        if (entity == null) {
            explicitAliasHand?.setIdeInfo(C_IdeSymbolInfo.get(IdeSymbolKind.LOC_AT_ALIAS))
            return null
        }

        val alias = explicitAliasHand?.name ?: entityNameHand.last.name
        val atEntityId = ctx.appCtx.nextAtEntityId(atExprId)
        return S_AtExpr.makeDbAtEntity(entity, alias, explicitAliasHand, atEntityId, ctx.docFactory)
    }
}

class S_UpdateTarget_Expr(private val expr: S_Expr): S_UpdateTarget() {
    override fun compile(
            ctx: C_ExprContext,
            stmtPos: S_Pos,
            atExprId: R_AtExprId,
            subValues: MutableList<V_Expr>
    ): C_UpdateTarget? {
        val cExpr = expr.compile(ctx)
        val cValue = cExpr.value()
        subValues.add(cValue)

        val vExpr = cExpr.value()
        val rExpr = vExpr.toRExpr()

        val targetCtx = C_TargetContext(ctx, stmtPos, atExprId, rExpr)
        return compileTarget(targetCtx)
    }

    private fun compileTarget(targetCtx: C_TargetContext): C_UpdateTarget? {
        val type = targetCtx.rExpr.type
        return if (type is R_EntityType) {
            compileTargetEntity(targetCtx, type.rEntity)
        } else if (type is R_NullableType && type.valueType is R_EntityType) {
            compileTargetEntity(targetCtx, type.valueType.rEntity)
        } else if (type is R_ObjectType) {
            compileTargetObject(targetCtx, type.rObject)
        } else if (type is R_SetType && type.elementType is R_EntityType) {
            compileTargetCollection(targetCtx, type.elementType, true)
        } else if (type is R_ListType && type.elementType is R_EntityType) {
            compileTargetCollection(targetCtx, type.elementType, false)
        } else {
            if (type.isNotError()) {
                targetCtx.exprCtx.msgCtx.error(expr.startPos, "stmt_update_expr_type:${type.strCode()}",
                        "Invalid expression type: ${type.strCode()}; must be an entity or a collection of an entity")
            }
            null
        }
    }

    private fun compileTargetEntity(tCtx: C_TargetContext, rEntity: R_EntityDefinition): C_UpdateTarget {
        val rAtEntity = tCtx.exprCtx.makeAtEntity(rEntity, tCtx.atExprId)
        val whereLeft = Db_EntityExpr(rAtEntity)

        val cLambdaB = C_LambdaBlock.builder(tCtx.exprCtx, rEntity.type)
        val cFrom = compileFrom(cLambdaB.innerExprCtx, tCtx.stmtPos, rAtEntity)
        val cLambda = cLambdaB.build()

        val whereRight = cLambda.compileVarDbExpr(cFrom.innerExprCtx().blkCtx.blockUid)
        val where = C_ExprUtils.makeDbBinaryExprEq(whereLeft, whereRight)
        val rTarget = R_UpdateTarget_Expr_One(rAtEntity, immListOf(), where, tCtx.rExpr, cLambda.rLambda)

        return C_UpdateTarget(rTarget, cFrom)
    }

    private fun compileTargetObject(tCtx: C_TargetContext, rObject: R_ObjectDefinition): C_UpdateTarget {
        val rAtEntity = tCtx.exprCtx.makeAtEntity(rObject.rEntity, tCtx.atExprId)
        val rTarget = R_UpdateTarget_Object(rAtEntity)
        val cFrom = compileFrom(tCtx.exprCtx, tCtx.stmtPos, rAtEntity)
        return C_UpdateTarget(rTarget, cFrom)
    }

    private fun compileTargetCollection(tCtx: C_TargetContext, entityType: R_EntityType, set: Boolean): C_UpdateTarget {
        val rAtEntity = tCtx.exprCtx.makeAtEntity(entityType.rEntity, tCtx.atExprId)
        val listType: R_Type = R_ListType(entityType)

        val cLambdaB = C_LambdaBlock.builder(tCtx.exprCtx, listType)
        val cFrom = compileFrom(cLambdaB.innerExprCtx, tCtx.stmtPos, rAtEntity)
        val cLambda = cLambdaB.build()

        val whereLeft = Db_EntityExpr(rAtEntity)
        val whereRight = Db_CollectionInterpretedExpr(cLambda.compileVarRExpr(cFrom.innerExprCtx().blkCtx.blockUid))
        val where = Db_BinaryExpr(R_BooleanType, Db_BinaryOp_In, whereLeft, whereRight)
        val rTarget = R_UpdateTarget_Expr_Many(rAtEntity, where, tCtx.rExpr, cLambda.rLambda, set, listType)
        return C_UpdateTarget(rTarget, cFrom)
    }

    private fun compileFrom(ctx: C_ExprContext, stmtPos: S_Pos, rAtEntity: R_DbAtEntity): C_AtFrom_Entities {
        val cAlias = C_Name.make(expr.startPos, rAtEntity.rEntity.rName)
        val cAtEntity = S_AtExpr.makeDbAtEntity(rAtEntity.rEntity, cAlias, null, rAtEntity.id, ctx.docFactory)
        val fromCtx = C_AtFromContext(stmtPos, cAtEntity.atExprId, null)
        return C_AtFrom_Entities(ctx, fromCtx, immListOf(cAtEntity))
    }

    private class C_TargetContext(
            val exprCtx: C_ExprContext,
            val stmtPos: S_Pos,
            val atExprId: R_AtExprId,
            val rExpr: R_Expr
    )
}

class S_UpdateWhat(val pos: S_Pos, val name: S_Name?, val op: S_AssignOpCode?, val expr: S_Expr) {
    companion object {
        fun makeRWhat(entity: R_DbAtEntity, attr: R_Attribute, expr: Db_Expr, op: Db_BinaryOp?): R_UpdateStatementWhat {
            val resExpr = if (op == null) expr else {
                val tableExpr = Db_EntityExpr(entity)
                val attrExpr = Db_AttrExpr(tableExpr, attr)
                Db_BinaryExpr(attr.type, op, attrExpr, expr) //TODO determine the result type in a cleaner way
            }
            return R_UpdateStatementWhat(attr, resExpr)
        }
    }
}

class S_UpdateStatement(pos: S_Pos, val target: S_UpdateTarget, val what: List<S_UpdateWhat>): S_Statement(pos) {
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        ctx.checkDbUpdateAllowed(pos)

        val atExprId = ctx.appCtx.nextAtExprId()
        val subValues = mutableListOf<V_Expr>()
        val cTarget = target.compile(ctx.exprCtx, pos, atExprId, subValues)

        if (cTarget == null) {
            what.forEach { it.expr.compileSafe(ctx.exprCtx) }
            return C_Statement.ERROR
        }

        val rEntity = cTarget.rTarget.entity().rEntity
        if (!rEntity.flags.canUpdate) {
            C_Errors.errCannotUpdate(ctx.msgCtx, pos, rEntity.simpleName)
        }

        val rWhat = compileWhat(cTarget.cFrom.innerExprCtx(), cTarget.rTarget, rEntity, subValues)

        val rFromBlock = cTarget.cFrom.compileUpdate()
        val rStmt = R_UpdateStatement(cTarget.rTarget, rFromBlock, rWhat)

        val resFacts = C_ExprVarFacts.forSubExpressions(subValues)
        return C_Statement(rStmt, false, resFacts.postFacts)
    }

    private fun compileWhat(
            ctx: C_ExprContext,
            target: R_UpdateTarget,
            entity: R_EntityDefinition,
            subValues: MutableList<V_Expr>
    ): List<R_UpdateStatementWhat> {
        val ideInfoProvider = C_CallArgumentIdeInfoProvider_Attribute(entity.attributes)

        val args = what.mapIndexed { i, w ->
            val nameHand = w.name?.compile(ctx)
            if (nameHand != null) {
                val ideInfo = ideInfoProvider.getIdeInfo(nameHand.rName)
                nameHand.setIdeInfo(ideInfo)
            }

            val cExpr = w.expr.compileSafe(ctx)
            val vExpr = cExpr.value()
            C_AttrArgument(i, nameHand?.name, vExpr)
        }

        subValues.addAll(args.map { it.vExpr })

        val attrs = C_AttributeResolver.resolveUpdate(ctx, entity, args)
        val atEntity = target.entity()

        val updAttrs = attrs.mapNotNull { (arg, attr) ->
            val w = what[arg.index]
            val op = if (w.op == null) S_AssignOp_Eq else w.op.op
            op.compileDbUpdate(ctx, w.pos, atEntity, attr, arg.vExpr)
        }.toImmList()

        return updAttrs
    }
}

class S_DeleteStatement(pos: S_Pos, val target: S_UpdateTarget): S_Statement(pos) {
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        ctx.checkDbUpdateAllowed(pos)

        val atExprId = ctx.appCtx.nextAtExprId()
        val subValues = mutableListOf<V_Expr>()
        val cTarget = target.compile(ctx.exprCtx, pos, atExprId, subValues)
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
