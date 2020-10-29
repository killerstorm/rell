/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.vexpr.V_AtPlaceholderExpr
import net.postchain.rell.compiler.vexpr.V_DbExpr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.compiler.vexpr.V_RExpr
import net.postchain.rell.model.*
import net.postchain.rell.utils.CommonUtils
import net.postchain.rell.utils.toImmList

class C_AtEntity(val pos: S_Pos, val rEntity: R_Entity, val alias: String, val index: Int) {
    private val rAtEntity = R_DbAtEntity(rEntity, index)

    fun compile() = rAtEntity
    fun compileExpr() = Db_EntityExpr(rAtEntity)
}

sealed class C_AtFrom() {
    abstract fun innerExprCtx(): C_ExprContext
    abstract fun makeDefaultWhat(): C_AtWhat
    abstract fun resolvePlaceholder(pos: S_Pos): V_Expr
    abstract fun findDefinitionByAlias(alias: S_Name): C_NameResolution?
    abstract fun findAttributesByName(name: String): List<C_ExprContextAttr>
    abstract fun findAttributesByType(type: R_Type): List<C_ExprContextAttr>
    abstract fun compile(startPos: S_Pos, resType: R_Type, cardinality: R_AtCardinality, details: C_AtDetails): V_Expr
}

class C_AtFrom_Entities(exprCtx: C_ExprContext, entities: List<C_AtEntity>): C_AtFrom() {
    val entities = entities.toImmList()

    private val innerExprCtx = exprCtx.update(nameCtx = C_NameContext.createAt(exprCtx.blkCtx, this))

    override fun innerExprCtx() = innerExprCtx

    override fun makeDefaultWhat(): C_AtWhat {
        val fields = entities.map {
            val name = if (entities.size == 1) null else it.alias
            val dbExpr = it.compileExpr()
            val vExpr = V_DbExpr.create(it.pos, dbExpr)
            C_AtWhatField(name, vExpr, R_AtWhatFlags.DEFAULT, null)
        }
        return C_AtWhat(fields)
    }

    override fun resolvePlaceholder(pos: S_Pos): V_Expr {
        throw C_Error(pos, "expr:dollar:db_at", "Placeholder supported only in collection-at")
    }

    override fun findDefinitionByAlias(alias: S_Name): C_NameResolution? {
        //TODO use a lookup table
        for (entity in entities) {
            if (entity.alias == alias.str) {
                return C_NameResolution_Entity(alias, entity)
            }
        }
        return null
    }

    override fun findAttributesByName(name: String): List<C_ExprContextAttr> {
        return findContextAttrs { rEntity ->
            val attrRef = C_EntityAttrRef.resolveByName(rEntity, name)
            if (attrRef == null) listOf() else listOf(attrRef)
        }
    }

    override fun findAttributesByType(type: R_Type): List<C_ExprContextAttr> {
        return findContextAttrs { rEntity ->
            C_EntityAttrRef.resolveByType(rEntity, type)
        }
    }

    private fun findContextAttrs(resolver: (R_Entity) -> List<C_EntityAttrRef>): List<C_ExprContextAttr> {
        val attrs = mutableListOf<C_ExprContextAttr>()

        //TODO take other kinds of fields into account
        //TODO fail when there is more than one match
        //TODO use a table lookup
        for (entity in entities) {
            val entityAttrs = resolver(entity.rEntity)
            val ctxAttrs = entityAttrs.map { C_ExprContextAttr_Entity(entity, it) }
            attrs.addAll(ctxAttrs)
        }

        return attrs.toImmList()
    }

    override fun compile(startPos: S_Pos, resType: R_Type, cardinality: R_AtCardinality, details: C_AtDetails): V_Expr {
        val cFrom = details.base.from as C_AtFrom_Entities
        val rFrom = cFrom.entities.map { it.compile() }

        val rWhat = details.base.what.fields.filter { !it.flags.ignore }.map {
            var dbExpr = it.expr.toDbExpr()
            if (it.aggrFn != null) dbExpr = Db_CallExpr(dbExpr.type, it.aggrFn.sysFn, listOf(dbExpr))
            R_DbAtWhatField(dbExpr, it.flags)
        }

        val rBase = R_DbAtExprBase(rFrom, rWhat, details.base.where?.toDbExpr())
        val rLimit = details.limit?.toRExpr()
        val rOffset = details.offset?.toRExpr()
        val rowDecoder = details.resType.dbRowDecoder()
        val rExpr = R_DbAtExpr(resType, rBase, cardinality, rLimit, rOffset, rowDecoder)

        return V_RExpr(startPos, rExpr, details.exprFacts)
    }
}

class C_AtFrom_Iterable(
        exprCtx: C_ExprContext,
        private val pos: S_Pos,
        alias: S_Name?,
        private val item: C_AtFromItem_Iterable
): C_AtFrom() {
    private val innerBlkCtx = exprCtx.blkCtx.createSubContext("@")

    private val innerExprCtx: C_ExprContext
    private val varPtr: R_VarPtr

    init {
        var factsCtx = exprCtx.factsCtx

        if (alias == null) {
            varPtr = innerBlkCtx.addPlaceholder(item.elemType)
        } else {
            val cVar = innerBlkCtx.addLocalVar(alias, item.elemType, false)
            factsCtx = factsCtx.sub(C_VarFacts.of(inited = mapOf(cVar.uid to C_VarFact.YES)))
            varPtr = cVar.ptr
        }

        innerExprCtx = exprCtx.update(
                blkCtx = innerBlkCtx,
                nameCtx = C_NameContext.createAt(innerBlkCtx, this),
                factsCtx = factsCtx
        )
    }

    override fun innerExprCtx() = innerExprCtx

    override fun makeDefaultWhat(): C_AtWhat {
        val vExpr = V_AtPlaceholderExpr(pos, item.elemType, varPtr)
        val field = C_AtWhatField(null, vExpr, R_AtWhatFlags.DEFAULT, null)
        return C_AtWhat(listOf(field))
    }

    override fun resolvePlaceholder(pos: S_Pos): V_Expr {
        val ph = innerBlkCtx.lookupPlaceholder()
        if (ph == null) {
            throw C_Error(pos, "expr:at:placeholder_none", "Placeholder not defined")
        } else if (ph.ambiguous) {
            throw C_Error(pos, "expr:at:placeholder_ambiguous", "Placeholder is ambiguous, can belong to more than one expression; use aliases")
        }
        return V_AtPlaceholderExpr(pos, item.elemType, ph.ptr)
    }

    override fun findDefinitionByAlias(alias: S_Name): C_NameResolution? {
        return null // TODO
    }

    override fun findAttributesByName(name: String): List<C_ExprContextAttr> {
        return listOf() //TODO
    }

    override fun findAttributesByType(type: R_Type): List<C_ExprContextAttr> {
        return listOf() //TODO
    }

    override fun compile(startPos: S_Pos, resType: R_Type, cardinality: R_AtCardinality, details: C_AtDetails): V_Expr {
        checkConstraints(details, startPos)

        val cBlock = innerBlkCtx.buildBlock()
        val rParam = R_VarParam(C_Constants.AT_PLACEHOLDER, item.elemType, varPtr)
        val rFrom = item.vExpr.toRExpr()
        val rWhere = details.base.where?.toRExpr() ?: R_ConstantExpr.makeBool(true)
        val rWhat = details.resType.colWhatExpr()
        val rLimit = details.limit?.toRExpr()
        val rOffset = details.offset?.toRExpr()

        val rExpr = R_ColAtExpr(
                type = resType,
                block = cBlock.rBlock,
                param = rParam,
                from = rFrom,
                what = rWhat,
                where = rWhere,
                cardinality = cardinality,
                limit = rLimit,
                offset = rOffset
        )

        return V_RExpr(startPos, rExpr, details.exprFacts)
    }

    private fun checkConstraints(details: C_AtDetails, startPos: S_Pos) {
        val aggrField = details.base.what.fields.firstOrNull { it.flags.group || it.flags.aggregation }
        if (aggrField != null) {
            innerBlkCtx.frameCtx.msgCtx.error(aggrField.expr.pos, "expr:at:group:col", "Grouping or aggregation not supported for collections")
        }

        val sortField = details.base.what.fields.firstOrNull { it.flags.sort != null }
        if (sortField != null) {
            innerBlkCtx.frameCtx.msgCtx.error(sortField.expr.pos, "expr:at:sort:col", "Sorting not supported for collections")
        }

        if (!innerBlkCtx.isCollectionAtAllowed()) {
            innerBlkCtx.frameCtx.msgCtx.error(startPos, "expr:at:bad_context", "Collection-at now allowed here")
        }
    }
}

sealed class C_AtFromItem(val pos: S_Pos)
class C_AtFromItem_Entity(pos: S_Pos, val alias: String, val entity: R_Entity): C_AtFromItem(pos)
class C_AtFromItem_Iterable(pos: S_Pos, val vExpr: V_Expr, val elemType: R_Type): C_AtFromItem(pos)

class C_AtWhatField(val name: String?, val expr: V_Expr, val flags: R_AtWhatFlags, val aggrFn: C_AtAggregationFunction?)

class C_AtWhat(fields: List<C_AtWhatField>) {
    val fields = fields.toImmList()
}

class C_AtExprBase(
        val from: C_AtFrom,
        val what: C_AtWhat,
        val where: V_Expr?
)

sealed class C_AtExprResultType {
    abstract fun type(): R_Type
    abstract fun dbRowDecoder(): R_DbAtExprRowDecoder
    abstract fun colWhatExpr(): R_Expr
}

class C_AtExprResultType_Simple(private val type: R_Type, private val expr: V_Expr): C_AtExprResultType() {
    override fun type() = type
    override fun dbRowDecoder() = R_DbAtExprRowDecoder_Simple
    override fun colWhatExpr() = expr.toRExpr()
}

class C_AtExprResultType_Tuple(private val tupleType: R_TupleType, private val exprs: List<V_Expr>): C_AtExprResultType() {
    override fun type() = tupleType
    override fun dbRowDecoder() = R_DbAtExprRowDecoder_Tuple(tupleType)

    override fun colWhatExpr(): R_Expr {
        val rExprs = exprs.map { it.toRExpr() }
        return R_TupleExpr(tupleType, rExprs)
    }
}

class C_AtDetails(
        val base: C_AtExprBase,
        val limit: V_Expr?,
        val offset: V_Expr?,
        val resType: C_AtExprResultType,
        val exprFacts: C_ExprVarFacts
)

class S_AtExprWhatSort(val pos: S_Pos, val asc: Boolean) {
    val rSort = if (asc) R_AtWhatSort.ASC else R_AtWhatSort.DESC
}

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
        val fields = listOf(C_AtWhatField(null, vExpr, R_AtWhatFlags.DEFAULT, null))
        return C_AtWhat(fields)
    }
}

class S_AtExprWhatComplexField(
        val attr: S_Name?,
        val expr: S_Expr,
        val annotations: List<S_Annotation>,
        val sort: S_AtExprWhatSort?
)

class S_AtExprWhat_Complex(val fields: List<S_AtExprWhatComplexField>): S_AtExprWhat() {
    override fun compile(ctx: C_ExprContext, from: C_AtFrom, subValues: MutableList<V_Expr>): C_AtWhat {
        val procFields = processFields(ctx)
        subValues.addAll(procFields.map { it.vExpr })

        val selFields = procFields.filter { it.flags.select }
        if (selFields.isEmpty()) {
            ctx.msgCtx.error(fields[0].expr.startPos, "at:no_fields", "All fields are excluded from the result")
        }

        val cFields = procFields.map { field ->
            val name = if (field.flags.select && (field.nameExplicit || selFields.size > 1)) field.name else null
            C_AtWhatField(name, field.vExpr, field.flags, field.aggrFn)
        }

        return C_AtWhat(cFields)
    }

    private fun processFields(ctx: C_ExprContext): List<WhatField> {
        val procFields = fields.map { processField(ctx, it) }

        val (aggrFields, noAggrFields) = procFields.withIndex().partition { it.value.flags.group || it.value.flags.aggregation }
        if (aggrFields.isNotEmpty()) {
            for ((i, field) in noAggrFields) {
                val code = "at:what:no_aggr:$i"
                val anns = C_AtAggregation.values().joinToString(", ") { "@${it.annotation}" }
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
            modTarget.sort?.set(field.sort.rSort)
            val ann = if (field.sort.asc) C_Modifier.SORT else C_Modifier.SORT_DESC
            ctx.msgCtx.warning(field.sort.pos, "at:what:sort:deprecated:$ann", "Deprecated sort syntax; use @$ann annotation instead")
        }

        val modifierCtx = C_ModifierContext(ctx.msgCtx, R_MountName.EMPTY)
        for (annotation in field.annotations) {
            annotation.compile(modifierCtx, modTarget)
        }

        val omit = modTarget.omit?.get() ?: false
        val sort = modTarget.sort?.get()
        val aggregation = modTarget.aggregation?.get()

        val flags = R_AtWhatFlags(
                select = !omit,
                sort = sort,
                group = aggregation == C_AtAggregation.GROUP,
                aggregation = aggregation?.aggrFn != null
        )

        val vExpr = field.expr.compile(ctx).value()
        compileWhatAggregation(ctx, vExpr, aggregation)

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
        } else if (!omit && !flags.aggregation) {
            name = vExpr.implicitWhatName()
        }

        return WhatField(vExpr, namePos, name, nameExplicit, flags, aggregation?.aggrFn)
    }

    private fun compileWhatAggregation(ctx: C_ExprContext, vExpr: V_Expr, aggregation: C_AtAggregation?) {
        if (aggregation == null || aggregation.aggrFn == null) {
            return
        }

        val type = vExpr.type()
        val fn = aggregation.aggrFn

        if (!fn.typeChecker(type)) {
            val code = "at:what:aggr:bad_type:$aggregation:${type.toStrictString()}"
            val msg = "Invalid type of ${aggregation} expression: ${type.toStrictString()}"
            ctx.msgCtx.error(vExpr.pos, code, msg)
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
            val flags: R_AtWhatFlags,
            val aggrFn: C_AtAggregationFunction?
    ) {
        fun updateName(newName: String?): WhatField {
            return WhatField(
                    vExpr = vExpr,
                    namePos = namePos,
                    name = newName,
                    nameExplicit = nameExplicit,
                    flags = flags,
                    aggrFn = aggrFn
            )
        }
    }
}

class S_AtExprWhere(val exprs: List<S_Expr>) {
    fun compile(ctx: C_ExprContext, subValues: MutableList<V_Expr>): V_Expr? {
        val whereExprs = exprs.withIndex().map { (idx, expr) -> compileWhereExpr(ctx, idx, expr, subValues) }
        return makeWhere(whereExprs)
    }

    private fun compileWhereExpr(ctx: C_ExprContext, idx: Int, expr: S_Expr, subValues: MutableList<V_Expr>): V_Expr {
        val cExpr = expr.compileWhere(ctx, idx)
        val vExpr = cExpr.value()
        subValues.add(vExpr)

        val type = vExpr.type()
        if (type == R_BooleanType) {
            return vExpr
        }

        if (vExpr.dependsOnAtVariable()) {
            throw C_Errors.errTypeMismatch(vExpr.pos, type, R_BooleanType, "at_where:type:$idx",
                    "Wrong type of where-expression #${idx+1}")
        }

        val attrs = S_AtExpr.findWhereContextAttrsByType(ctx, type)
        if (attrs.isEmpty()) {
            throw C_Error(expr.startPos, "at_where_type:$idx:$type", "No attribute matches type of where-expression #${idx + 1}: $type")
        } else if (attrs.size > 1) {
            throw C_Errors.errMultipleAttrs(expr.startPos, attrs, "at_attr_type_ambig:$idx:$type",
                    "Multiple attributes match type of where-expression #${idx+1} ($type)")
        }

        val attr = attrs[0]
        val attrExpr = attr.compile(expr.startPos)
        return C_Utils.makeVBinaryExprEq(expr.startPos, attrExpr, vExpr)
    }

    private fun makeWhere(compiledExprs: List<V_Expr>): V_Expr? {
        for (value in compiledExprs) {
            check(value.type() == R_BooleanType)
        }

        if (compiledExprs.isEmpty()) {
            return null
        }

        return CommonUtils.foldSimple(compiledExprs) { left, right -> C_BinOp_And.compile(left, right)!! }
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
        val details = compileDetails(ctx)

        val rowType = details.resType.type()

        val type = if (cardinality.many) {
            R_ListType(rowType)
        } else if (cardinality.zero) {
            R_NullableType(rowType)
        } else {
            rowType
        }

        val vExpr = details.base.from.compile(startPos, type, cardinality, details)
        return C_VExpr(vExpr)
    }

    private fun compileDetails(ctx: C_ExprContext): C_AtDetails {
        val subValues = mutableListOf<V_Expr>()

        val cFrom = from.compileFrom(ctx, atPos, subValues)

        val atCtx = cFrom.innerExprCtx()
        val vWhere = where.compile(atCtx, subValues)

        val cWhat = what.compile(atCtx, cFrom, subValues)
        val resType = calcResultType(cWhat.fields)

        val vLimit = compileLimitOffset(limit, "limit", ctx, subValues)
        val vOffset = compileLimitOffset(offset, "offset", ctx, subValues)

        val base = C_AtExprBase(cFrom, cWhat, vWhere)
        val facts = C_ExprVarFacts.forSubExpressions(subValues)

        return C_AtDetails(base, vLimit, vOffset, resType, facts)
    }

    private fun calcResultType(whatFields: List<C_AtWhatField>): C_AtExprResultType {
        val selFields = whatFields.filter { it.flags.select }
        if (selFields.size == 1 && selFields[0].name == null) {
            val expr = selFields[0].expr
            val type = expr.type()
            return C_AtExprResultType_Simple(type, expr)
        } else {
            val tupleFields = selFields.map { R_TupleField(it.name, it.expr.type()) }
            val type = R_TupleType(tupleFields)
            val exprs = selFields.map { it.expr }
            return C_AtExprResultType_Tuple(type, exprs)
        }
    }

    private fun compileLimitOffset(sExpr: S_Expr?, msg: String, ctx: C_ExprContext, subValues: MutableList<V_Expr>): V_Expr? {
        if (sExpr == null) {
            return null
        }

        val vExpr = sExpr.compile(ctx).value()
        subValues.add(vExpr)

        val type = vExpr.type()
        C_Types.match(R_IntegerType, type, sExpr.startPos, "expr_at_${msg}_type", "Wrong $msg type")

        return vExpr
    }

    companion object {
        fun compileFromEntities(ctx: C_ExprContext, from: List<S_AtExprFrom>): List<C_AtEntity> {
            val cFrom = from.mapIndexed { i, f -> compileFromEntity(ctx, i, f) }

            val names = mutableSetOf<String>()
            for ((alias, entity) in cFrom) {
                if (!names.add(entity.alias)) {
                    throw C_Error(alias.pos, "at_dup_alias:${entity.alias}", "Duplicate entity alias: ${entity.alias}")
                }
            }

            return cFrom.map { ( _, entity ) -> entity }
        }

        private fun compileFromEntity(ctx: C_ExprContext, idx: Int, from: S_AtExprFrom): Pair<S_Name, C_AtEntity> {
            if (from.alias != null) {
                val name = from.alias
                val localVar = ctx.nameCtx.resolveNameLocalValue(name.str)
                if (localVar != null) {
                    throw C_Error(name.pos, "expr_at_conflict_alias:${name.str}", "Name conflict: '${name.str}'")
                }
            }

            val alias = from.alias ?: from.entityName[from.entityName.size - 1]
            val entity = ctx.nsCtx.getEntity(from.entityName)
            return Pair(alias, C_AtEntity(alias.pos, entity, alias.str, idx))
        }

        fun findWhereContextAttrsByType(ctx: C_ExprContext, type: R_Type): List<C_ExprContextAttr> {
            return if (type == R_BooleanType) {
                listOf()
            } else {
                ctx.nameCtx.findAttributesByType(type)
            }
        }
    }
}
