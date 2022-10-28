/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.expr

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_AppContext
import net.postchain.rell.compiler.base.core.C_BlockEntry_AtEntity
import net.postchain.rell.compiler.base.utils.C_CodeMsg
import net.postchain.rell.compiler.base.utils.toCodeMsg
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.*
import net.postchain.rell.utils.chainToIterable
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.toImmList

class C_AtEntity(
        val declPos: S_Pos,
        val rEntity: R_EntityDefinition,
        val alias: R_Name,
        val explicitAlias: Boolean,
        atEntityId: R_AtEntityId
) {
    val atExprId = atEntityId.exprId

    private val rAtEntity = R_DbAtEntity(rEntity, atEntityId)

    fun toRAtEntity(): R_DbAtEntity {
        return rAtEntity
    }

    fun toVExpr(ctx: C_ExprContext, pos: S_Pos, ambiguous: Boolean): V_Expr {
        return V_AtEntityExpr(ctx, pos, this, ambiguous)
    }

    fun toRAtEntityValidated(ctx: C_ExprContext, pos: S_Pos, ambiguous: Boolean): R_DbAtEntity {
        if (!isValidAccess(ctx) && !ambiguous) {
            ctx.msgCtx.error(pos, "at:entity:outer:$alias",
                    "Cannot access entity '${rEntity.moduleLevelName}' as it belongs to an unrelated at-expression")
        }
        return rAtEntity
    }

    private fun isValidAccess(ctx: C_ExprContext): Boolean {
        return chainToIterable(ctx.atCtx) { it.parent }.any { it.atExprId == atExprId }
    }
}

class C_AtFrom_Entities(
        outerExprCtx: C_ExprContext,
        fromCtx: C_AtFromContext,
        entities: List<C_AtEntity>
): C_AtFrom(outerExprCtx, fromCtx) {
    val entities = entities.toImmList()

    private val msgCtx = outerExprCtx.msgCtx

    private val innerExprCtx = outerExprCtx.update(blkCtx = innerBlkCtx, atCtx = innerAtCtx)

    init {
        check(entities.isNotEmpty())

        val atExprIds = entities.map { it.atExprId }.toSet()
        checkEquals(setOf(atExprId), atExprIds)

        val ph = entities.any { !it.explicitAlias }
        for (entity in entities) {
            val entry = C_BlockEntry_AtEntity(entity)
            innerBlkCtx.addEntry(entity.declPos, entity.alias, entity.explicitAlias, entry)
            if (ph) {
                innerBlkCtx.addAtPlaceholder(entry)
            }
        }
    }

    override fun innerExprCtx() = innerExprCtx

    override fun makeDefaultWhat(): V_DbAtWhat {
        val fields = entities.map {
            val name = if (entities.size == 1) null else it.alias
            val vExpr = it.toVExpr(innerExprCtx, it.declPos, false)
            V_DbAtWhatField(outerExprCtx.appCtx, name, vExpr.type, vExpr, V_AtWhatFieldFlags.DEFAULT, null)
        }
        return V_DbAtWhat(fields)
    }

    override fun findMembers(name: R_Name): List<C_AtFromMember> {
        return entities.flatMap { atEntity ->
            val base = C_AtFromBase_Entity(atEntity)
            atEntity.rEntity.type.getValueMembers(name).map { C_AtFromMember(base, it) }
        }.toImmList()
    }

    override fun findImplicitAttributesByName(name: R_Name): List<C_AtFromImplicitAttr> {
        return findContextAttrs { rEntity ->
            rEntity.type.getAtImplicitAttrs(name)
        }
    }

    override fun findImplicitAttributesByType(type: R_Type): List<C_AtFromImplicitAttr> {
        return findContextAttrs { rEntity ->
            rEntity.type.getAtImplicitAttrs(type)
        }
    }

    private fun findContextAttrs(getter: (R_EntityDefinition) -> List<C_AtTypeImplicitAttr>): List<C_AtFromImplicitAttr> {
        return entities
            .flatMap { entity ->
                val base = C_AtFromBase_Entity(entity)
                val members = getter(entity.rEntity)
                members.map { C_AtFromImplicitAttr(base, it) }
            }.toImmList()
    }

    override fun compile(details: C_AtDetails): V_Expr {
        val vBase = compileBase(details)
        val extras = V_AtExprExtras(details.limit, details.offset)

        if (parentAtCtx?.dbAt != true) {
            return compileTop(details, vBase, extras)
        }

        val dependent = isOuterDependent(vBase)
        return if (dependent || details.cardinality.value == R_AtCardinality.ZERO_MANY) {
            compileNested(details, vBase, extras)
        } else {
            compileTop(details, vBase, extras)
        }
    }

    private fun isOuterDependent(vBase: V_AtExprBase): Boolean {
        val exprIds = vBase.referencedAtExprIds()
        return chainToIterable(parentAtCtx) { it.parent }.any { exprIds.contains(it.atExprId) }
    }

    private fun compileTop(details: C_AtDetails, vBase: V_AtExprBase, extras: V_AtExprExtras): V_Expr {
        val cBlock = innerBlkCtx.buildBlock()
        val rowDecoder = details.res.rowDecoder
        val internals = R_DbAtExprInternals(cBlock.rBlock, rowDecoder)

        return V_TopDbAtExpr(
                outerExprCtx,
                details.startPos,
                details.res.resultType,
                vBase,
                extras,
                details.cardinality.value,
                internals,
                details.exprFacts
        )
    }

    private fun compileNested(details: C_AtDetails, vBase: V_AtExprBase, extras: V_AtExprExtras): V_Expr {
        var resultType = details.res.resultType

        if (details.cardinality.value != R_AtCardinality.ZERO_MANY) {
            msgCtx.error(details.cardinality.pos, "at_expr:nested:cardinality:${details.cardinality.value}",
                    "Only '@*' can be used in a nested at-expression")
            // Fix result type to prevent exists() also firing a "wrong argument type" CTE.
            resultType = C_AtExprResult.calcResultType(details.res.recordType, R_AtCardinality.ZERO_MANY)
        }

        val cBlock = innerBlkCtx.buildBlock()

        return V_NestedDbAtExpr(
                outerExprCtx,
                details.startPos,
                resultType,
                vBase,
                extras,
                cBlock.rBlock,
                details.exprFacts
        )
    }

    private fun compileBase(details: C_AtDetails): V_AtExprBase {
        val rFrom = entities.map { it.toRAtEntity() }
        return V_AtExprBase(rFrom, details.base.what.materialFields, details.base.where)
    }

    fun compileUpdate(): R_FrameBlock {
        val cBlock = innerBlkCtx.buildBlock()
        return cBlock.rBlock
    }

    private inner class C_AtFromBase_Entity(private val atEntity: C_AtEntity): C_AtFromBase() {
        override fun nameMsg(): C_CodeMsg {
            return "${atEntity.alias}:${atEntity.rEntity.type.name}" toCodeMsg atEntity.alias.str
        }

        override fun compile(pos: S_Pos): V_Expr {
            return V_AtEntityExpr(innerExprCtx, pos, atEntity, false)
        }
    }
}

sealed class C_DbAtWhatValue {
    abstract fun toDbWhatTop(appCtx: C_AppContext, field: V_DbAtWhatField): Db_AtWhatValue
    abstract fun toDbWhatSub(): Db_AtWhatValue
}

class C_DbAtWhatValue_Simple(private val dbExpr: Db_Expr): C_DbAtWhatValue() {
    override fun toDbWhatTop(appCtx: C_AppContext, field: V_DbAtWhatField): Db_AtWhatValue {
        var resExpr = dbExpr
        if (field.summarization != null) {
            resExpr = field.summarization.compileDb(appCtx, resExpr)
        }
        return Db_AtWhatValue_DbExpr(resExpr, field.resultType)
    }

    override fun toDbWhatSub(): Db_AtWhatValue {
        return Db_AtWhatValue_DbExpr(dbExpr, dbExpr.type)
    }
}

class C_DbAtWhatValue_Complex(
        vExprs: List<V_Expr>,
        private val evaluator: Db_ComplexAtWhatEvaluator
): C_DbAtWhatValue() {
    private val vExprs = vExprs.toImmList()

    override fun toDbWhatTop(appCtx: C_AppContext, field: V_DbAtWhatField): Db_AtWhatValue {
        V_AtUtils.checkNoWhatModifiers(appCtx.msgCtx, field)
        return toDbWhatSub()
    }

    override fun toDbWhatSub(): Db_AtWhatValue {
        val items = mutableListOf<Pair<Boolean, Int>>()
        val dbExprs = mutableListOf<Db_AtWhatValue>()
        val rExprs = mutableListOf<R_Expr>()

        for (vExpr in vExprs) {
            if (vExpr.info.dependsOnDbAtEntity) {
                items.add(true to dbExprs.size)
                dbExprs.add(vExpr.toDbExprWhat().toDbWhatSub())
            } else {
                items.add(false to rExprs.size)
                rExprs.add(vExpr.toRExpr())
            }
        }

        return Db_AtWhatValue_Complex(dbExprs, rExprs, items, evaluator)
    }
}

class C_DbAtWhatValue_Other(private val dbWhatValue: Db_AtWhatValue): C_DbAtWhatValue() {
    override fun toDbWhatTop(appCtx: C_AppContext, field: V_DbAtWhatField): Db_AtWhatValue {
        V_AtUtils.checkNoWhatModifiers(appCtx.msgCtx, field)
        return dbWhatValue
    }

    override fun toDbWhatSub() = dbWhatValue
}
