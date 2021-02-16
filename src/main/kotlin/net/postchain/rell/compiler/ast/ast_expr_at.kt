/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_DecimalValue
import net.postchain.rell.runtime.Rt_IntValue
import net.postchain.rell.utils.CommonUtils
import net.postchain.rell.utils.toImmList

class S_AtExprFrom(val alias: S_Name?, val entityName: List<S_Name>)

sealed class S_AtExprWhat {
    abstract fun compile(ctx: C_ExprContext, from: C_AtFrom, subValues: MutableList<V_Expr>): C_AtWhat
}

class S_AtExprWhat_Default: S_AtExprWhat() {
    override fun compile(ctx: C_ExprContext, from: C_AtFrom, subValues: MutableList<V_Expr>): C_AtWhat {
        return from.makeDefaultWhat()
    }
}

class S_AtExprWhat_Simple(val path: List<S_Name>): S_AtExprWhat() {
    override fun compile(ctx: C_ExprContext, from: C_AtFrom, subValues: MutableList<V_Expr>): C_AtWhat {
        val vAttrExpr = ctx.nameCtx.resolveAttr(path[0])
        var expr: C_Expr = C_VExpr(vAttrExpr)
        for (step in path.subList(1, path.size)) {
            expr = expr.member(ctx, step, false)
        }

        val vExpr = expr.value()
        val fields = listOf(C_AtWhatField(null, vExpr.type(), vExpr, C_AtWhatFieldFlags.DEFAULT, null))
        return C_AtWhat(fields)
    }
}

class S_AtExprWhatComplexField(
        val attr: S_Name?,
        val expr: S_Expr,
        val annotations: List<S_Annotation>,
        val sort: S_PosValue<R_AtWhatSort>?
)

class S_AtExprWhat_Complex(val fields: List<S_AtExprWhatComplexField>): S_AtExprWhat() {
    override fun compile(ctx: C_ExprContext, from: C_AtFrom, subValues: MutableList<V_Expr>): C_AtWhat {
        val procFields = processFields(ctx)
        subValues.addAll(procFields.map { it.vExpr })

        val selFields = procFields.filter { !it.flags.omit }
        if (selFields.isEmpty()) {
            ctx.msgCtx.error(fields[0].expr.startPos, "at:no_fields", "All fields are excluded from the result")
        }

        val hasGroup = procFields.any { it.summarization?.isGroup() ?: false }

        val cFields = procFields.map { field ->
            val name = if (!field.flags.omit && (field.nameExplicit || selFields.size > 1)) field.name else null
            val resultType = field.summarization?.getResultType(hasGroup) ?: field.vExpr.type()
            C_AtWhatField(name, resultType, field.vExpr, field.flags, field.summarization)
        }

        return C_AtWhat(cFields)
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

        val modifierCtx = C_ModifierContext(ctx.msgCtx, R_MountName.EMPTY)
        for (annotation in field.annotations) {
            annotation.compile(modifierCtx, modTarget)
        }

        val omit = modTarget.omit?.get() ?: false
        val sort = modTarget.sort?.get()
        val summ = modTarget.summarization?.get()

        val flags = C_AtWhatFieldFlags(
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
            name = vExpr.implicitWhatName()
        }

        return WhatField(vExpr, namePos, name, nameExplicit, flags, cSummarization)
    }

    private fun compileSummarization(ctx: C_ExprContext, vExpr: V_Expr, ann: C_AtSummarizationKind?): C_AtSummarization? {
        ann ?: return null

        val type = vExpr.type()
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
            val flags: C_AtWhatFieldFlags,
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
    fun compile(ctx: C_ExprContext, subValues: MutableList<V_Expr>): V_Expr? {
        val whereExprs = exprs.withIndex().map { (idx, expr) -> compileWhereExpr(ctx, idx, expr, subValues) }
        return makeWhere(ctx, whereExprs)
    }

    private fun compileWhereExpr(ctx: C_ExprContext, idx: Int, expr: S_Expr, subValues: MutableList<V_Expr>): V_Expr {
        val cExpr = expr.compileWhere(ctx, idx)
        val vExpr = cExpr.value()
        subValues.add(vExpr)

        val type = vExpr.type()
        if (type == R_BooleanType || type == R_CtErrorType) {
            return vExpr
        }

        if (vExpr.dependsOnAtVariable()) {
            throw C_Errors.errTypeMismatch(vExpr.pos, type, R_BooleanType, "at_where:type:$idx",
                    "Wrong type of where-expression #${idx+1}")
        }

        val attrs = S_AtExpr.findWhereContextAttrsByType(ctx, type)
        if (attrs.isEmpty()) {
            ctx.msgCtx.error(expr.startPos, "at_where_type:$idx:$type",
                    "No attribute matches type of where-expression #${idx + 1}: $type")
            return C_Utils.errorVExpr(ctx, expr.startPos)
        } else if (attrs.size > 1) {
            throw C_Errors.errMultipleAttrs(expr.startPos, attrs, "at_attr_type_ambig:$idx:$type",
                    "Multiple attributes match type of where-expression #${idx+1} ($type)")
        }

        val attr = attrs[0]
        val attrExpr = attr.compile(expr.startPos)
        return C_Utils.makeVBinaryExprEq(ctx, expr.startPos, attrExpr, vExpr)
    }

    private fun makeWhere(ctx: C_ExprContext, compiledExprs: List<V_Expr>): V_Expr? {
        for (value in compiledExprs) {
            check(value.type() in listOf(R_BooleanType, R_CtErrorType))
        }

        if (compiledExprs.isEmpty()) {
            return null
        }

        return CommonUtils.foldSimple(compiledExprs) { left, right ->
            C_BinOp_And.compile(ctx, left, right) ?: C_Utils.errorVExpr(ctx, left.pos)
        }
    }
}

class S_AtExpr(
        val from: S_Expr,
        val atPos: S_Pos,
        val cardinality: R_AtCardinality,
        val where: S_AtExprWhere,
        val what: S_AtExprWhat,
        val limit: S_Expr?,
        val offset: S_Expr?
): S_Expr(from.startPos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val (cFrom, cDetails) = compileInternal(ctx, null)
        val vExpr = cFrom.compile(cDetails)
        return C_VExpr(vExpr)
    }

    override fun compileNestedAt(ctx: C_ExprContext, parentAtCtx: C_DbAtContext): C_MaybeNestedAt {
        val (cFrom, cDetails) = compileInternal(ctx, parentAtCtx)
        return cFrom.compileNested(cDetails)
    }

    private fun compileInternal(ctx: C_ExprContext, parentAtCtx: C_DbAtContext?): Pair<C_AtFrom, C_AtDetails> {
        val subValues = mutableListOf<V_Expr>()

        val atExprId = ctx.appCtx.nextAtExprId()
        val fromCtx = C_AtFromContext(atPos, atExprId, parentAtCtx)
        val cFrom = from.compileFrom(ctx, fromCtx, subValues)

        val cDetails = compileDetails(ctx, cFrom, subValues)
        return Pair(cFrom, cDetails)
    }

    private fun compileDetails(ctx: C_ExprContext, cFrom: C_AtFrom, subValues: MutableList<V_Expr>): C_AtDetails {
        val innerCtx = cFrom.innerExprCtx()
        val vWhere = where.compile(innerCtx, subValues)

        val cWhat = what.compile(innerCtx, cFrom, subValues)
        val cResult = compileAtResult(cWhat.allFields)

        val vLimit = compileLimitOffset(limit, "limit", ctx, subValues)
        val vOffset = compileLimitOffset(offset, "offset", ctx, subValues)

        val base = C_AtExprBase(cWhat, vWhere)
        val facts = C_ExprVarFacts.forSubExpressions(subValues)

        return C_AtDetails(startPos, atPos, cardinality, base, vLimit, vOffset, cResult, facts)
    }

    private fun compileAtResult(whatFields: List<C_AtWhatField>): C_AtExprResult {
        val selFieldsIndexes = whatFields.withIndex().filter { !it.value.flags.omit }.map { it.index }.toImmList()
        val selFields = selFieldsIndexes.map { whatFields[it] }

        val groupFieldsIndexes = whatFields.withIndex()
                .filter { it.value.summarization?.isGroup() ?: false }
                .map { it.index }
                .toImmList()

        val hasAggregateFields = whatFields.any { !(it.summarization?.isGroup() ?: true) }

        var rowDecoder: R_AtExprRowDecoder
        var recordType: R_Type

        if (selFields.size == 1 && selFields[0].name == null) {
            rowDecoder = R_AtExprRowDecoder_Simple
            recordType = selFields[0].resultType
        } else {
            val tupleFields = selFields.map { R_TupleField(it.name, it.resultType) }
            val type = R_TupleType(tupleFields)
            rowDecoder = R_AtExprRowDecoder_Tuple(type)
            recordType = type
        }

        val resultType = C_AtExprResult.calcResultType(recordType, cardinality)
        return C_AtExprResult(recordType, resultType, rowDecoder, selFieldsIndexes, groupFieldsIndexes, hasAggregateFields)
    }

    private fun compileLimitOffset(sExpr: S_Expr?, msg: String, ctx: C_ExprContext, subValues: MutableList<V_Expr>): V_Expr? {
        if (sExpr == null) {
            return null
        }

        val subCtx = ctx.update(dbAtCtx = null)
        val vExpr = sExpr.compile(subCtx).value()
        subValues.add(vExpr)

        val type = vExpr.type()
        C_Types.match(R_IntegerType, type, sExpr.startPos, "expr_at_${msg}_type", "Wrong $msg type")

        return vExpr
    }

    companion object {
        fun findWhereContextAttrsByType(ctx: C_ExprContext, type: R_Type): List<C_ExprContextAttr> {
            return if (type == R_BooleanType) {
                listOf()
            } else {
                ctx.nameCtx.findAttributesByType(type)
            }
        }
    }
}
