/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.base.core.*
import net.postchain.rell.compiler.base.expr.*
import net.postchain.rell.compiler.base.utils.C_Error
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.compiler.vexpr.V_AtWhatFieldFlags
import net.postchain.rell.compiler.vexpr.V_DbAtWhat
import net.postchain.rell.compiler.vexpr.V_DbAtWhatField
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.*
import net.postchain.rell.runtime.Rt_DecimalValue
import net.postchain.rell.runtime.Rt_IntValue
import net.postchain.rell.utils.CommonUtils
import net.postchain.rell.utils.immSetOf
import net.postchain.rell.utils.toImmList

class S_AtExprFrom(val alias: S_Name?, val entityName: List<S_Name>)

sealed class S_AtExprWhat {
    abstract fun compile(ctx: C_ExprContext, from: C_AtFrom, subValues: MutableList<V_Expr>): V_DbAtWhat
}

class S_AtExprWhat_Default: S_AtExprWhat() {
    override fun compile(ctx: C_ExprContext, from: C_AtFrom, subValues: MutableList<V_Expr>): V_DbAtWhat {
        return from.makeDefaultWhat()
    }
}

class S_AtExprWhat_Simple(val path: List<S_Name>): S_AtExprWhat() {
    override fun compile(ctx: C_ExprContext, from: C_AtFrom, subValues: MutableList<V_Expr>): V_DbAtWhat {
        val vAttrExpr = ctx.resolveAttr(path[0])
        var expr: C_Expr = C_VExpr(vAttrExpr)
        for (step in path.subList(1, path.size)) {
            expr = expr.member(ctx, step, false)
        }

        val vExpr = expr.value()
        val fields = listOf(V_DbAtWhatField(ctx.appCtx, null, vExpr.type, vExpr, V_AtWhatFieldFlags.DEFAULT, null))
        return V_DbAtWhat(fields)
    }
}

class S_AtExprWhatComplexField(
        val attr: S_Name?,
        val expr: S_Expr,
        val annotations: List<S_Annotation>,
        val sort: S_PosValue<R_AtWhatSort>?
)

class S_AtExprWhat_Complex(val fields: List<S_AtExprWhatComplexField>): S_AtExprWhat() {
    override fun compile(ctx: C_ExprContext, from: C_AtFrom, subValues: MutableList<V_Expr>): V_DbAtWhat {
        val procFields = processFields(ctx)
        subValues.addAll(procFields.map { it.vExpr })

        val selFields = procFields.filter { !it.flags.omit }
        if (selFields.isEmpty()) {
            ctx.msgCtx.error(fields[0].expr.startPos, "at:no_fields", "All fields are excluded from the result")
        }

        val hasGroup = procFields.any { it.summarization?.isGroup() ?: false }

        val cFields = procFields.map { field ->
            val name = if (!field.flags.omit && (field.nameExplicit || selFields.size > 1)) field.name else null
            val resultType = field.summarization?.getResultType(hasGroup) ?: field.vExpr.type
            V_DbAtWhatField(ctx.appCtx, name, resultType, field.vExpr, field.flags, field.summarization)
        }

        return V_DbAtWhat(cFields)
    }

    private fun processFields(ctx: C_ExprContext): List<WhatField> {
        val procFields = fields.map { processField(ctx, it) }

        val (aggrFields, noAggrFields) = procFields.withIndex().partition {
            it.value.summarization != null || it.value.flags.aggregate != null
        }

        if (aggrFields.isNotEmpty()) {
            for ((i, field) in noAggrFields) {
                val code = "at:what:no_aggr:$i"
                val anns = C_AtSummarizationKind.values().joinToString(", ") { "@${it.annotation}" }
                val msg = "Either none or all what-expressions must be annotated with one of: $anns"
                ctx.msgCtx.error(field.vExpr.pos, code, msg)
            }
        }

        val res = processNameConflicts(ctx, procFields)
        return res
    }

    private fun processField(ctx: C_ExprContext, field: S_AtExprWhatComplexField): WhatField {
        val modTarget = C_ModifierTarget(C_ModifierTargetType.EXPRESSION, null)

        if (field.sort != null) {
            modTarget.sort?.set(field.sort)
            val ann = if (field.sort.value.asc) C_Modifier.SORT else C_Modifier.SORT_DESC
            ctx.msgCtx.warning(field.sort.pos, "at:what:sort:deprecated:$ann", "Deprecated sort syntax; use @$ann annotation instead")
        }

        val modifierCtx = C_ModifierContext(ctx.msgCtx, ctx.appCtx.valExec)
        for (annotation in field.annotations) {
            annotation.compile(modifierCtx, modTarget)
        }

        val omit = modTarget.omit?.get() ?: false
        val sort = modTarget.sort?.get()
        val summ = modTarget.summarization?.get()

        val flags = V_AtWhatFieldFlags(
                omit = omit,
                sort = sort,
                group = if (summ?.value == C_AtSummarizationKind.GROUP) summ.pos else null,
                aggregate = if (summ != null && summ.value != C_AtSummarizationKind.GROUP) summ.pos else null
        )

        val vExpr = field.expr.compileSafe(ctx).value()
        val cSummarization = compileSummarization(ctx, vExpr, summ?.value)

        var namePos: S_Pos = field.expr.startPos
        var name: String? = null
        var nameExplicit = false

        val attr = field.attr
        if (attr != null) {
            if (attr.str != "_") {
                namePos = attr.pos
                name = attr.str
                nameExplicit = true
            }
        } else if (!omit && (cSummarization == null || cSummarization.isGroup())) {
            name = vExpr.implicitAtWhatAttrName()
        }

        return WhatField(vExpr, namePos, name, nameExplicit, flags, cSummarization)
    }

    private fun compileSummarization(ctx: C_ExprContext, vExpr: V_Expr, ann: C_AtSummarizationKind?): C_AtSummarization? {
        ann ?: return null

        val type = vExpr.type
        val pos = C_AtSummarizationPos(vExpr.pos, ann)

        val cSummarization = when (ann) {
            C_AtSummarizationKind.GROUP -> C_AtSummarization_Group(pos, type)
            C_AtSummarizationKind.SUM -> compileSummarizationSum(pos, type)
            C_AtSummarizationKind.MIN -> compileSummarizationMinMax(pos, type, R_CmpOp_Le, Db_SysFn_Aggregation_Min)
            C_AtSummarizationKind.MAX -> compileSummarizationMinMax(pos, type, R_CmpOp_Ge, Db_SysFn_Aggregation_Max)
        }

        if (cSummarization == null) {
            C_AtSummarization.typeError(ctx.msgCtx, type, pos)
        }

        return cSummarization
    }

    private fun compileSummarizationSum(pos: C_AtSummarizationPos, type: R_Type): C_AtSummarization? {
        return when (type) {
            R_IntegerType -> C_AtSummarization_Aggregate_Sum(pos, type, R_BinaryOp_Add_Integer, Rt_IntValue(0))
            R_DecimalType -> C_AtSummarization_Aggregate_Sum(pos, type, R_BinaryOp_Add_Decimal, Rt_DecimalValue.ZERO)
            else -> null
        }
    }

    private fun compileSummarizationMinMax(
            pos: C_AtSummarizationPos,
            type: R_Type,
            cmpOp: R_CmpOp,
            dbFn: Db_SysFunction
    ): C_AtSummarization? {
        val rCmpType = R_CmpType.forAtMinMaxType(type)
        val rComparator = if (type is R_NullableType) null else type.comparator()
        return if (rCmpType == null && rComparator == null) null else {
            C_AtSummarization_Aggregate_MinMax(pos, type, cmpOp, rCmpType, rComparator, dbFn)
        }
    }

    private fun processNameConflicts(ctx: C_ExprContext, procFields: List<WhatField>): List<WhatField> {
        val res = mutableListOf<WhatField>()
        val names = mutableSetOf<String>()

        for (f in procFields) {
            var name = f.name
            if (name != null && !names.add(name)) {
                ctx.msgCtx.error(f.namePos, "at:dup_field_name:$name", "Duplicate field name: '$name'")
                name = null
            }
            res.add(f.updateName(name))
        }

        return res
    }

    private class WhatField(
            val vExpr: V_Expr,
            val namePos: S_Pos,
            val name: String?,
            val nameExplicit: Boolean,
            val flags: V_AtWhatFieldFlags,
            val summarization: C_AtSummarization?
    ) {
        fun updateName(newName: String?): WhatField {
            return WhatField(
                    vExpr = vExpr,
                    namePos = namePos,
                    name = newName,
                    nameExplicit = nameExplicit,
                    flags = flags,
                    summarization = summarization
            )
        }
    }
}

class S_AtExprWhere(val exprs: List<S_Expr>) {
    fun compile(ctx: C_ExprContext, atExprId: R_AtExprId, subValues: MutableList<V_Expr>): V_Expr? {
        val whereExprs = exprs.withIndex().map { (idx, expr) -> compileWhereExpr(ctx, atExprId, idx, expr, subValues) }
        return makeWhere(ctx, whereExprs)
    }

    private fun compileWhereExpr(
            ctx: C_ExprContext,
            atExprId: R_AtExprId,
            idx: Int,
            expr: S_Expr,
            subValues: MutableList<V_Expr>
    ): V_Expr {
        val cExpr = expr.compileSafe(ctx)
        val vExpr = cExpr.value()
        subValues.add(vExpr)

        val type = vExpr.type
        if (type.isError()) {
            return vExpr
        }

        val dependsOnThisAtExpr = vExpr.info.dependsOnAtExprs == immSetOf(atExprId)
        val attrName = vExpr.implicitAtWhereAttrName()

        return if (!dependsOnThisAtExpr && attrName != null) {
            compileWhereExprName(ctx, idx, vExpr, attrName, type)
        } else {
            compileWhereExprNoName(ctx, idx, vExpr, dependsOnThisAtExpr)
        }
    }

    private fun compileWhereExprNoName(ctx: C_ExprContext, idx: Int, vExpr: V_Expr, dependsOnThisAtExpr: Boolean): V_Expr {
        val type = vExpr.type
        if (type == R_BooleanType || type == R_CtErrorType) {
            return vExpr
        }

        if (dependsOnThisAtExpr) {
            throw C_Errors.errTypeMismatch(vExpr.pos, type, R_BooleanType, "at_where:type:$idx",
                    "Wrong type of ${whereExprMsg(idx)}")
        }

        val attrs = S_AtExpr.findWhereContextAttrsByType(ctx, type)
        if (attrs.isEmpty()) {
            ctx.msgCtx.error(vExpr.pos, "at_where_type:$idx:$type",
                    "No attribute matches type of ${whereExprMsg(idx)} ($type)")
            return C_Utils.errorVExpr(ctx, vExpr.pos)
        } else if (attrs.size > 1) {
            throw C_Errors.errMultipleAttrs(vExpr.pos, attrs, "at_attr_type_ambig:$idx:$type",
                    "Multiple attributes match type of ${whereExprMsg(idx)} ($type)")
        }

        val attr = attrs[0]
        val attrExpr = attr.compile(ctx, vExpr.pos)
        return C_Utils.makeVBinaryExprEq(ctx, vExpr.pos, attrExpr, vExpr)
    }

    private fun compileWhereExprName(ctx: C_ExprContext, idx: Int, vExpr: V_Expr, name: String, type: R_Type): V_Expr {
        val entityAttrs = ctx.findWhereAttributesByName(name)
        if (entityAttrs.isEmpty() && type == R_BooleanType) {
            val msg = "No context attribute matches name '$name', but the expression is accepted" +
                    ", because its type is ${R_BooleanType.name}" +
                    " (suggestion: write <expression> == true for clarity)"
            ctx.msgCtx.warning(vExpr.pos, "at:where:name_boolean_no_attr:$name", msg)
            return vExpr
        }

        val entityAttr = ctx.msgCtx.consumeError {
            matchWhereAttribute(ctx, idx, vExpr.pos, name, entityAttrs, type)
        }
        entityAttr ?: return C_Utils.errorVExpr(ctx, vExpr.pos)

        val entityAttrExpr = entityAttr.compile(ctx, vExpr.pos)
        return C_Utils.makeVBinaryExprEq(ctx, vExpr.pos, entityAttrExpr, vExpr)
    }

    private fun matchWhereAttribute(
            ctx: C_ExprContext,
            idx: Int,
            exprPos: S_Pos,
            name: String,
            entityAttrsByName: List<C_ExprContextAttr>,
            varType: R_Type
    ): C_ExprContextAttr {
        val entityAttrsByType = if (entityAttrsByName.isNotEmpty()) {
            entityAttrsByName.filter { it.type == varType }
        } else {
            S_AtExpr.findWhereContextAttrsByType(ctx, varType)
        }

        if (entityAttrsByType.isEmpty()) {
            throw C_Error.more(exprPos, "at_where:var_noattrs:$idx:$name:$varType",
                    "No attribute matches name '$name' or type $varType")
        } else if (entityAttrsByType.size > 1) {
            if (entityAttrsByName.isEmpty()) {
                throw C_Errors.errMultipleAttrs(
                        exprPos,
                        entityAttrsByType,
                        "at_where:var_manyattrs_type:$idx:$name:$varType",
                        "Multiple attributes match expression type $varType"
                )
            } else {
                throw C_Errors.errMultipleAttrs(
                        exprPos,
                        entityAttrsByType,
                        "at_where:var_manyattrs_nametype:$idx:$name:$varType",
                        "Multiple attributes match name '$name' and type $varType"
                )
            }
        }

        return entityAttrsByType[0]
    }

    private fun makeWhere(ctx: C_ExprContext, compiledExprs: List<V_Expr>): V_Expr? {
        return if (compiledExprs.isEmpty()) null else {
            CommonUtils.foldSimple(compiledExprs) { left, right ->
                C_BinOp_And.compile(ctx, left, right) ?: C_Utils.errorVExpr(ctx, left.pos)
            }
        }
    }

    private fun whereExprMsg(idx: Int): String {
        val idxMsg = if (exprs.size == 1) "" else " #${idx + 1}"
        return "where-expression$idxMsg"
    }
}

class S_AtExpr(
        val from: S_Expr,
        val cardinality: S_PosValue<R_AtCardinality>,
        val where: S_AtExprWhere,
        val what: S_AtExprWhat,
        val limit: S_Expr?,
        val offset: S_Expr?
): S_Expr(from.startPos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        return compileInternal(ctx, null)
    }

    override fun compileNestedAt(ctx: C_ExprContext, parentAtCtx: C_AtContext): C_Expr {
        return compileInternal(ctx, parentAtCtx)
    }

    private fun compileInternal(ctx: C_ExprContext, parentAtCtx: C_AtContext?): C_Expr {
        val subValues = mutableListOf<V_Expr>()

        val atExprId = ctx.appCtx.nextAtExprId()
        val fromCtx = C_AtFromContext(cardinality.pos, atExprId, parentAtCtx)
        val cFrom = from.compileFrom(ctx, fromCtx, subValues)

        val cDetails = compileDetails(ctx, atExprId, cFrom, subValues)
        val vExpr = cFrom.compile(cDetails)
        return C_VExpr(vExpr)
    }

    private fun compileDetails(
            ctx: C_ExprContext,
            atExprId: R_AtExprId,
            cFrom: C_AtFrom,
            subValues: MutableList<V_Expr>
    ): C_AtDetails {
        val innerCtx = cFrom.innerExprCtx()
        val vWhere = where.compile(innerCtx, atExprId, subValues)

        val cWhat = what.compile(innerCtx, cFrom, subValues)
        val cResult = compileAtResult(cWhat.allFields)

        val vLimit = compileLimitOffset(limit, "limit", ctx, subValues)
        val vOffset = compileLimitOffset(offset, "offset", ctx, subValues)

        val base = C_AtExprBase(cWhat, vWhere)
        val facts = C_ExprVarFacts.forSubExpressions(subValues)

        return C_AtDetails(startPos, cardinality, base, vLimit, vOffset, cResult, facts)
    }

    private fun compileAtResult(whatFields: List<V_DbAtWhatField>): C_AtExprResult {
        val selFieldsIndexes = whatFields.withIndex().filter { !it.value.flags.omit }.map { it.index }.toImmList()
        val selFields = selFieldsIndexes.map { whatFields[it] }

        val groupFieldsIndexes = whatFields.withIndex()
                .filter { it.value.summarization?.isGroup() ?: false }
                .map { it.index }
                .toImmList()

        val hasAggregateFields = whatFields.any { !(it.summarization?.isGroup() ?: true) }

        val rowDecoder: R_AtExprRowDecoder
        val recordType: R_Type

        if (selFields.size == 1 && selFields[0].name == null) {
            rowDecoder = R_AtExprRowDecoder_Simple
            recordType = selFields[0].resultType
        } else {
            val tupleFields = selFields.map { R_TupleField(it.name, it.resultType) }
            val type = R_TupleType(tupleFields)
            rowDecoder = R_AtExprRowDecoder_Tuple(type)
            recordType = type
        }

        val resultType = C_AtExprResult.calcResultType(recordType, cardinality.value)

        return C_AtExprResult(
                recordType,
                resultType,
                rowDecoder,
                selFieldsIndexes,
                groupFieldsIndexes,
                hasAggregateFields
        )
    }

    private fun compileLimitOffset(sExpr: S_Expr?, msg: String, ctx: C_ExprContext, subValues: MutableList<V_Expr>): V_Expr? {
        if (sExpr == null) {
            return null
        }

        val subCtx = ctx.update(atCtx = null)
        val vExpr = sExpr.compile(subCtx).value()
        subValues.add(vExpr)

        C_Types.match(R_IntegerType, vExpr.type, sExpr.startPos, "expr_at_${msg}_type", "Wrong $msg type")
        return vExpr
    }

    companion object {
        fun findWhereContextAttrsByType(ctx: C_ExprContext, type: R_Type): List<C_ExprContextAttr> {
            return if (type == R_BooleanType) {
                listOf()
            } else {
                ctx.findWhereAttributesByType(type)
            }
        }
    }
}
