/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_PosValue
import net.postchain.rell.compiler.base.core.C_AppContext
import net.postchain.rell.compiler.base.core.C_MessageContext
import net.postchain.rell.compiler.base.expr.*
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.model.R_AtExprId
import net.postchain.rell.model.R_FrameBlock
import net.postchain.rell.model.R_Name
import net.postchain.rell.model.R_Type
import net.postchain.rell.model.expr.*
import net.postchain.rell.model.stmt.R_ForIterator
import net.postchain.rell.utils.*

class V_AtEntityExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val cAtEntity: C_AtEntity,
        private val ambiguous: Boolean
): V_Expr(exprCtx, pos) {
    override fun exprInfo0() = V_ExprInfo(
            cAtEntity.rEntity.type,
            immListOf(),
            dependsOnDbAtEntity = true,
            dependsOnAtExprs = immSetOf(cAtEntity.atExprId)
    )

    override fun isAtExprItem() = true
    override fun implicitTargetAttrName() = cAtEntity.alias

    override fun toRExpr0() = throw C_Errors.errExprDbNotAllowed(pos)

    override fun toDbExpr0(): Db_Expr {
        val rAtEntity = cAtEntity.toRAtEntityValidated(exprCtx, pos, ambiguous)
        return Db_EntityExpr(rAtEntity)
    }
}

class V_AtWhatFieldFlags(
        val omit: Boolean,
        val sort: S_PosValue<R_AtWhatSort>?,
        val group: S_Pos?,
        val aggregate: S_Pos?
) {
    fun compile() = R_AtWhatFieldFlags(omit = omit, sort = sort?.value, group = group != null, aggregate = aggregate != null)

    companion object {
        val DEFAULT = V_AtWhatFieldFlags(omit = false, sort = null, group = null, aggregate = null)
    }
}

class V_DbAtWhatField(
        private val appCtx: C_AppContext,
        val name: R_Name?,
        val resultType: R_Type,
        val expr: V_Expr,
        val flags: V_AtWhatFieldFlags,
        val summarization: C_AtSummarization?
) {
    fun isIgnored() = flags.omit && flags.sort == null && summarization == null

    fun toDbField(nested: Boolean): Db_AtWhatField {
        val cWhatValue = if (expr.info.dependsOnDbAtEntity || V_AtUtils.hasWhatModifiers(flags)) {
            if (nested) {
                // Nested "at" doesn't support complex what (e. g. exists, in - complex what doesn't make sense).
                val dbExpr = expr.toDbExpr()
                C_DbAtWhatValue_Simple(dbExpr)
            } else {
                expr.toDbExprWhat()
            }
        } else {
            val rExpr = expr.toRExpr()
            val dbWhatValue = Db_AtWhatValue_RExpr(rExpr)
            C_DbAtWhatValue_Other(dbWhatValue)
        }

        val dbWhatValue = cWhatValue.toDbWhatTop(appCtx, this)
        val rFlags = flags.compile()
        return Db_AtWhatField(rFlags, dbWhatValue)
    }
}

class V_DbAtWhat(allFields: List<V_DbAtWhatField>) {
    val allFields = allFields.toImmList()
    val materialFields = allFields.filter { !it.isIgnored() }
}

class V_AtExprBase(
        from: List<R_DbAtEntity>,
        what: List<V_DbAtWhatField>,
        private val where: V_Expr?
) {
    private val from = from.toImmList()
    private val what = what.toImmList()

    private val innerExprs = (what.map { it.expr } + listOfNotNull(where)).toImmList()
    private val refAtExprIds = innerExprs.flatMap { it.info.dependsOnAtExprs }.toImmSet()

    fun innerExprs(): List<V_Expr> = innerExprs
    fun referencedAtExprIds(): Set<R_AtExprId> = refAtExprIds

    fun toDbBase(nested: Boolean): Db_AtExprBase {
        val dbWhat = what.map { it.toDbField(nested) }
        val dbWhere = where?.toDbExpr()
        return Db_AtExprBase(from, dbWhat, dbWhere)
    }
}

class V_TopDbAtExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val resultType: R_Type,
        private val base: V_AtExprBase,
        private val extras: V_AtExprExtras,
        private val cardinality: R_AtCardinality,
        private val internals: R_DbAtExprInternals,
        private val resVarFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    override fun exprInfo0() = V_ExprInfo(resultType, base.innerExprs() + extras.innerExprs())
    override fun varFacts0() = resVarFacts

    override fun globalConstantRestriction() = V_GlobalConstantRestriction("at_expr", null)

    override fun toRExpr0(): R_Expr {
        val dbBase = base.toDbBase(false)
        val rExtras = extras.toRExtras()
        return R_DbAtExpr(resultType, dbBase, cardinality, rExtras, internals)
    }
}

class V_NestedDbAtExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val resultType: R_Type,
        private val base: V_AtExprBase,
        private val extras: V_AtExprExtras,
        private val rBlock: R_FrameBlock,
        private val resVarFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    override fun exprInfo0() = V_ExprInfo.simple(
            resultType,
            base.innerExprs() + extras.innerExprs(),
            dependsOnDbAtEntity = true
    )

    override fun varFacts0() = resVarFacts

    override fun globalConstantRestriction() = V_GlobalConstantRestriction("at_expr", null)

    override fun toRExpr0() = throw C_Errors.errExprDbNotAllowed(pos)

    override fun toDbExpr0(): Db_Expr {
        val dbBase = base.toDbBase(true)
        val rExtras = extras.toRExtras()
        return Db_NestedAtExpr(resultType, dbBase, rExtras, rBlock)
    }
}

class V_AtExprExtras(private val limit: V_Expr?, private val offset: V_Expr?) {
    fun innerExprs(): List<V_Expr> = listOfNotNull(limit, offset)

    fun toRExtras(): R_AtExprExtras {
        val rLimit = limit?.toRExpr()
        val rOffset = offset?.toRExpr()
        return R_AtExprExtras(rLimit, rOffset)
    }
}

class V_ColAtFrom(private val rIterator: R_ForIterator, private val expr: V_Expr) {
    fun innerExprs(): List<V_Expr> = immListOf(expr)

    fun toRFrom(): R_ColAtFrom {
        val rExpr = expr.toRExpr()
        return R_ColAtFrom(rIterator, rExpr)
    }
}

class V_ColAtWhatField(val expr: V_Expr, val flags: R_AtWhatFieldFlags, val summarization: R_ColAtFieldSummarization) {
    fun toRField(): R_ColAtWhatField {
        val rExpr = expr.toRExpr()
        return R_ColAtWhatField(rExpr, flags, summarization)
    }
}

class V_ColAtWhat(
        fields: List<V_ColAtWhatField>,
        val extras: R_ColAtWhatExtras
) {
    val fields = fields.toImmList()

    init {
        checkEquals(extras.fieldCount, fields.size)
    }

    fun innerExprs(): List<V_Expr> = fields.map { it.expr }

    fun toRWhat(): R_ColAtWhat {
        val rFields = fields.map { it.toRField() }
        return R_ColAtWhat(rFields, extras)
    }
}

class V_ColAtExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val result: C_AtExprResult,
        private val from: V_ColAtFrom,
        private val what: V_ColAtWhat,
        private val where: V_Expr?,
        private val cardinality: R_AtCardinality,
        private val extras: V_AtExprExtras,
        private val block: R_FrameBlock,
        private val param: R_ColAtParam,
        private val resVarFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    override fun exprInfo0(): V_ExprInfo {
        val subExprs = from.innerExprs() + what.innerExprs() + listOfNotNull(where) + extras.innerExprs()
        return V_ExprInfo(result.resultType, subExprs)
    }

    override fun varFacts0() = resVarFacts

    override fun toRExpr0(): R_Expr {
        val rFrom = from.toRFrom()
        val rWhat = what.toRWhat()
        val rWhere = where?.toRExpr() ?: R_ConstantValueExpr.makeBool(true)
        val rExtras = extras.toRExtras()
        val summarization = compileSummarization(result, rWhat)

        return R_ColAtExpr(
                type = result.resultType,
                block = block,
                param = param,
                from = rFrom,
                what = rWhat,
                where = rWhere,
                summarization = summarization,
                cardinality = cardinality,
                extras = rExtras
        )
    }

    private fun compileSummarization(cResult: C_AtExprResult, rWhat: R_ColAtWhat): R_ColAtSummarization {
        return if (cResult.groupFields.isEmpty() && !cResult.hasAggregateFields) {
            R_ColAtSummarization_None(rWhat.fields.size)
        } else if (cResult.groupFields.isEmpty()) {
            R_ColAtSummarization_All(rWhat)
        } else {
            R_ColAtSummarization_Group(rWhat)
        }
    }
}

object V_AtUtils {
    fun hasWhatModifiers(flags: V_AtWhatFieldFlags): Boolean {
        return flags.sort != null || flags.group != null || flags.aggregate != null
    }

    fun checkNoWhatModifiers(msgCtx: C_MessageContext, field: V_DbAtWhatField) {
        val flags = field.flags
        checkWhatFlag(msgCtx, flags.sort?.pos, "sort", "sort")
        checkWhatFlag(msgCtx, flags.group, "group", "group")
        checkWhatFlag(msgCtx, flags.aggregate, "aggregate", "aggregate")
    }

    private fun checkWhatFlag(msgCtx: C_MessageContext, flagPos: S_Pos?, code: String, msg: String) {
        if (flagPos != null) {
            msgCtx.error(flagPos, "expr:at:$code", "Cannot $msg this expression")
        }
    }
}
