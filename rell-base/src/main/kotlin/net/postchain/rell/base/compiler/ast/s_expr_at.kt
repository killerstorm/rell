/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.ast

import net.postchain.rell.base.compiler.base.core.*
import net.postchain.rell.base.compiler.base.expr.*
import net.postchain.rell.base.compiler.base.modifier.*
import net.postchain.rell.base.compiler.base.utils.C_Constants
import net.postchain.rell.base.compiler.base.utils.C_Error
import net.postchain.rell.base.compiler.base.utils.C_Errors
import net.postchain.rell.base.compiler.base.utils.toCodeMsg
import net.postchain.rell.base.compiler.vexpr.V_AtWhatFieldFlags
import net.postchain.rell.base.compiler.vexpr.V_DbAtWhat
import net.postchain.rell.base.compiler.vexpr.V_DbAtWhatField
import net.postchain.rell.base.compiler.vexpr.V_Expr
import net.postchain.rell.base.model.*
import net.postchain.rell.base.model.expr.*
import net.postchain.rell.base.runtime.Rt_BigIntegerValue
import net.postchain.rell.base.runtime.Rt_DecimalValue
import net.postchain.rell.base.runtime.Rt_IntValue
import net.postchain.rell.base.utils.CommonUtils
import net.postchain.rell.base.utils.doc.DocDeclaration_AtVariable
import net.postchain.rell.base.utils.doc.DocSymbol
import net.postchain.rell.base.utils.doc.DocSymbolKind
import net.postchain.rell.base.utils.doc.DocSymbolName
import net.postchain.rell.base.utils.ide.IdeLocalSymbolLink
import net.postchain.rell.base.utils.ide.IdeSymbolId
import net.postchain.rell.base.utils.ide.IdeSymbolKind
import net.postchain.rell.base.utils.immSetOf
import net.postchain.rell.base.utils.toImmList

class S_AtExprFrom(val alias: S_Name?, val entityName: S_QualifiedName)

sealed class S_AtExprWhat {
    abstract fun compile(ctx: C_ExprContext, hint: C_ExprHint, from: C_AtFrom, subValues: MutableList<V_Expr>): V_DbAtWhat
}

class S_AtExprWhat_Default: S_AtExprWhat() {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint, from: C_AtFrom, subValues: MutableList<V_Expr>): V_DbAtWhat {
        return from.makeDefaultWhat()
    }
}

class S_AtExprWhat_Simple(val path: List<S_Name>): S_AtExprWhat() {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint, from: C_AtFrom, subValues: MutableList<V_Expr>): V_DbAtWhat {
        var expr = S_AttrExpr.compileAttr(ctx, C_ExprHint.DEFAULT, path[0])

        for (i in 1 until path.size) {
            val step = path[i]
            val stepHand = step.compile(ctx)
            expr = expr.member(ctx, stepHand, C_ExprHint.DEFAULT)
        }

        val vExpr = expr.value()
        val field = V_DbAtWhatField(ctx.appCtx, null, vExpr.type, vExpr, V_AtWhatFieldFlags.DEFAULT, null)
        val fields = listOf(field)
        return V_DbAtWhat(fields)
    }
}

class S_AtExprWhatComplexField(
        val attr: S_Name?,
        val expr: S_Expr,
        val modifiers: S_Modifiers
)

class S_AtExprWhat_Complex(val fields: List<S_AtExprWhatComplexField>): S_AtExprWhat() {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint, from: C_AtFrom, subValues: MutableList<V_Expr>): V_DbAtWhat {
        val lazyTupleIdeId = lazy { ctx.defCtx.tupleIdeId() }

        val procFields = processFields(ctx)
        subValues.addAll(procFields.map { it.vExpr })

        val selFields = procFields.filter { !it.flags.omit }
        if (selFields.isEmpty()) {
            ctx.msgCtx.error(fields[0].expr.startPos, "at:no_fields", "All fields are excluded from the result")
        }

        val hasGroup = procFields.any { it.summarization?.isGroup() ?: false }

        val cFields = procFields.map { field ->
            val resultType = field.summarization?.getResultType(hasGroup) ?: field.vExpr.type
            val rIdeName = compileFieldName(ctx, lazyTupleIdeId, field, resultType, selFields.size > 1)
            V_DbAtWhatField(ctx.appCtx, rIdeName, resultType, field.vExpr, field.flags, field.summarization)
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

    private fun processField(
        ctx: C_ExprContext,
        field: S_AtExprWhatComplexField,
    ): WhatField {
        val mods = C_ModifierValues(C_ModifierTargetType.EXPRESSION, null)
        val modOmit = mods.field(C_ModifierFields.OMIT)
        val modSort = mods.field(C_ModifierFields.SORT)
        val modSumm = mods.field(C_ModifierFields.SUMMARIZATION)

        val modifierCtx = C_ModifierContext(ctx.msgCtx, ctx.symCtx)
        field.modifiers.compile(modifierCtx, mods)

        val omit = modOmit.hasValue()
        val sort = modSort.posValue()
        val summ = modSumm.posValue()

        val flags = V_AtWhatFieldFlags(
                omit = omit,
                sort = sort,
                group = if (summ?.value == C_AtSummarizationKind.GROUP) summ.pos else null,
                aggregate = if (summ != null && summ.value != C_AtSummarizationKind.GROUP) summ.pos else null
        )

        val vExpr = field.expr.compileSafe(ctx).value()
        val cSummarization = compileSummarization(ctx, vExpr, summ?.value)

        var explicitNameHand: C_NameHandle? = null
        var effectiveName: C_Name? = null

        val attr = field.attr

        if (attr != null) {
            val attrNameHand = attr.compile(ctx)
            val cAttr = attrNameHand.name
            if (cAttr.str != "_") {
                explicitNameHand = attrNameHand
                effectiveName = cAttr
            } else {
                attrNameHand.setIdeInfo(C_IdeSymbolInfo.UNKNOWN)
            }
        } else if (!omit && (cSummarization == null || cSummarization.isGroup())) {
            val impName = vExpr.implicitAtWhatAttrName()
            if (impName != null) {
                effectiveName = impName
            }
        }

        return WhatField(vExpr, explicitNameHand, effectiveName, flags, cSummarization)
    }

    private fun compileFieldName(
        ctx: C_ExprContext,
        lazyTupleIdeId: Lazy<IdeSymbolId>,
        field: WhatField,
        resultType: R_Type,
        manyFields: Boolean,
    ): R_IdeName? {
        field.effectiveName ?: return null

        val ideDef = S_TupleType.makeFieldIdeDef(lazyTupleIdeId.value, field.effectiveName, resultType)
        val ideDefId = ideDef.defInfo.defId

        if (field.explicitNameHand != null) {
            var defIdeInfo = ideDef.defInfo
            if (field.flags.omit) defIdeInfo = defIdeInfo.update(defId = null)
            field.explicitNameHand.setIdeInfo(defIdeInfo)
        } else if (ideDefId != null) {
            ctx.symCtx.setDefId(field.effectiveName.pos, ideDefId)
        }

        val hasActualName = !field.flags.omit && (field.explicitNameHand != null || manyFields)
        return if (hasActualName) R_IdeName(field.effectiveName.rName, ideDef.refInfo) else null
    }

    private fun compileSummarization(ctx: C_ExprContext, vExpr: V_Expr, ann: C_AtSummarizationKind?): C_AtSummarization? {
        ann ?: return null

        val type = vExpr.type
        val pos = C_AtSummarizationPos(vExpr.pos, ann)

        val cSummarization = when (ann) {
            C_AtSummarizationKind.GROUP -> C_AtSummarization_Group(pos, type)
            C_AtSummarizationKind.SUM -> compileSummarizationSum(pos, type)
            C_AtSummarizationKind.MIN -> compileSummarizationMinMax(pos, type, R_CmpOp_Le, Db_SysFn_Aggregation.Min)
            C_AtSummarizationKind.MAX -> compileSummarizationMinMax(pos, type, R_CmpOp_Ge, Db_SysFn_Aggregation.Max)
        }

        if (cSummarization == null) {
            C_AtSummarization.typeError(ctx.msgCtx, type, pos)
        }

        return cSummarization
    }

    private fun compileSummarizationSum(pos: C_AtSummarizationPos, type: R_Type): C_AtSummarization? {
        return when (type) {
            R_IntegerType -> C_AtSummarization_Aggregate_Sum(pos, type, R_BinaryOp_Add_Integer, Rt_IntValue.ZERO)
            R_BigIntegerType -> C_AtSummarization_Aggregate_Sum(pos, type, R_BinaryOp_Add_BigInteger, Rt_BigIntegerValue.ZERO)
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
        val names = mutableSetOf<R_Name>()

        for (f in procFields) {
            val name = f.effectiveName
            var field = f
            if (name != null && !names.add(name.rName)) {
                ctx.msgCtx.error(f.namePos, "at:dup_field_name:$name", "Duplicate field name: '$name'")
                f.explicitNameHand?.setIdeInfo(C_IdeSymbolInfo.UNKNOWN)
                field = f.updateName(null)
            }
            res.add(field)
        }

        return res
    }

    private class WhatField(
        val vExpr: V_Expr,
        val explicitNameHand: C_NameHandle?,
        val effectiveName: C_Name?,
        val flags: V_AtWhatFieldFlags,
        val summarization: C_AtSummarization?,
    ) {
        val namePos: S_Pos = explicitNameHand?.pos ?: vExpr.pos

        fun updateName(newName: C_Name?): WhatField {
            return WhatField(
                vExpr = vExpr,
                effectiveName = newName,
                explicitNameHand = explicitNameHand,
                flags = flags,
                summarization = summarization,
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
            ctx.msgCtx.error(vExpr.pos, "at_where_type:$idx:${type.strCode()}",
                    "No attribute matches type of ${whereExprMsg(idx)} (${type.str()})")
            return C_ExprUtils.errorVExpr(ctx, vExpr.pos)
        } else if (attrs.size > 1) {
            throw C_Errors.errMultipleAttrs(vExpr.pos, attrs, "at_attr_type_ambig:$idx:${type.strCode()}",
                    "Multiple attributes match type of ${whereExprMsg(idx)} (${type.str()})")
        }

        val attr = attrs[0]
        val attrExpr = attr.compile(ctx, vExpr.pos)
        return C_ExprUtils.makeVBinaryExprEq(ctx, vExpr.pos, attrExpr, vExpr)
    }

    private fun compileWhereExprName(ctx: C_ExprContext, idx: Int, vExpr: V_Expr, name: R_Name, type: R_Type): V_Expr {
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
        entityAttr ?: return C_ExprUtils.errorVExpr(ctx, vExpr.pos)

        val entityAttrExpr = entityAttr.compile(ctx, vExpr.pos)
        return C_ExprUtils.makeVBinaryExprEq(ctx, vExpr.pos, entityAttrExpr, vExpr)
    }

    private fun matchWhereAttribute(
        ctx: C_ExprContext,
        idx: Int,
        exprPos: S_Pos,
        name: R_Name,
        entityAttrsByName: List<C_AtFromImplicitAttr>,
        varType: R_Type
    ): C_AtFromImplicitAttr {
        val entityAttrsByType = if (entityAttrsByName.isNotEmpty()) {
            entityAttrsByName.filter { it.type == varType }
        } else {
            S_AtExpr.findWhereContextAttrsByType(ctx, varType)
        }

        if (entityAttrsByType.isEmpty()) {
            throw C_Error.more(exprPos, "at_where:var_noattrs:$idx:$name:${varType.strCode()}",
                    "No attribute matches name '$name' or type ${varType.str()}")
        } else if (entityAttrsByType.size > 1) {
            if (entityAttrsByName.isEmpty()) {
                throw C_Errors.errMultipleAttrs(
                        exprPos,
                        entityAttrsByType,
                        "at_where:var_manyattrs_type:$idx:$name:${varType.strCode()}",
                        "Multiple attributes match expression type ${varType.str()}"
                )
            } else {
                throw C_Errors.errMultipleAttrs(
                        exprPos,
                        entityAttrsByType,
                        "at_where:var_manyattrs_nametype:$idx:$name:${varType.strCode()}",
                        "Multiple attributes match name '$name' and type ${varType.str()}"
                )
            }
        }

        return entityAttrsByType[0]
    }

    private fun makeWhere(ctx: C_ExprContext, compiledExprs: List<V_Expr>): V_Expr? {
        return if (compiledExprs.isEmpty()) null else {
            CommonUtils.foldSimple(compiledExprs) { left, right ->
                C_BinOp_And.compile(ctx, left, right) ?: C_ExprUtils.errorVExpr(ctx, left.pos)
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
    val offset: S_Expr?,
): S_Expr(from.startPos) {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        return compileInternal(ctx, hint, null)
    }

    override fun compileNestedAt(ctx: C_ExprContext, parentAtCtx: C_AtContext): C_Expr {
        return compileInternal(ctx, C_ExprHint.DEFAULT, parentAtCtx)
    }

    private fun compileInternal(ctx: C_ExprContext, hint: C_ExprHint, parentAtCtx: C_AtContext?): C_Expr {
        val subValues = mutableListOf<V_Expr>()

        val atExprId = ctx.appCtx.nextAtExprId()
        val fromCtx = C_AtFromContext(cardinality.pos, atExprId, parentAtCtx)
        val cFrom = from.compileFrom(ctx, fromCtx, subValues)

        val cDetails = compileDetails(ctx, hint, atExprId, cFrom, subValues)
        val vExpr = cFrom.compile(cDetails)
        return C_ValueExpr(vExpr)
    }

    private fun compileDetails(
            ctx: C_ExprContext,
            hint: C_ExprHint,
            atExprId: R_AtExprId,
            cFrom: C_AtFrom,
            subValues: MutableList<V_Expr>
    ): C_AtDetails {
        val innerCtx = cFrom.innerExprCtx()
        val vWhere = where.compile(innerCtx, atExprId, subValues)

        val cWhat = what.compile(innerCtx, hint, cFrom, subValues)
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
        } else if (selFields.isNotEmpty()) {
            val tupleFields = selFields.map { R_TupleField(it.name, it.resultType) }
            val type = R_TupleType(tupleFields)
            rowDecoder = R_AtExprRowDecoder_Tuple(type)
            recordType = type
        } else {
            //TODO shall be handled where the "at:no_fields" error is reported
            rowDecoder = R_AtExprRowDecoder_Simple
            recordType = R_CtErrorType
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

        C_Types.match(R_IntegerType, vExpr.type, sExpr.startPos) { "expr_at_${msg}_type" toCodeMsg "Wrong $msg type" }
        return vExpr
    }

    companion object {
        fun findWhereContextAttrsByType(ctx: C_ExprContext, type: R_Type): List<C_AtFromImplicitAttr> {
            return if (type == R_BooleanType) {
                listOf()
            } else {
                ctx.findWhereAttributesByType(type)
            }
        }

        fun makeDbAtEntity(
            entity: R_EntityDefinition,
            alias: C_Name,
            explicitAliasHand: C_NameHandle?,
            atEntityId: R_AtEntityId,
        ): C_AtEntity {
            val ideDef = makeDbAtIdeDef(alias.str, alias.pos, entity)
            val ideDefPh = makeDbAtIdeDef(C_Constants.AT_PLACEHOLDER, alias.pos, entity)
            explicitAliasHand?.setIdeInfo(ideDef.defInfo)
            return C_AtEntity(
                alias.pos,
                entity,
                alias.rName,
                explicitAliasHand != null,
                atEntityId,
                ideDef.refInfo,
                ideDefPh.refInfo,
            )
        }

        private fun makeDbAtIdeDef(name: String, pos: S_Pos, rEntity: R_EntityDefinition): C_IdeSymbolDef {
            val docSymbol = DocSymbol(
                DocSymbolKind.AT_VAR_DB,
                DocSymbolName.local(name),
                null,
                DocDeclaration_AtVariable(name, rEntity.type.mType),
                null
            )
            return C_IdeSymbolDef.make(IdeSymbolKind.LOC_AT_ALIAS, link = IdeLocalSymbolLink(pos), doc = docSymbol)
        }

        fun makeColAtIdeInfo(explicitAlias: R_Name?, itemType: R_Type): C_IdeSymbolInfo {
            val docName = explicitAlias?.str ?: C_Constants.AT_PLACEHOLDER
            val docSymbol = DocSymbol(
                DocSymbolKind.AT_VAR_COL,
                DocSymbolName.local(docName),
                null,
                DocDeclaration_AtVariable(docName, itemType.mType),
                null
            )
            return C_IdeSymbolInfo.direct(IdeSymbolKind.LOC_AT_ALIAS, doc = docSymbol)
        }
    }
}
