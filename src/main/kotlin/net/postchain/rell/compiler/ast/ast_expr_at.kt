/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.model.*
import net.postchain.rell.utils.toImmList

class S_AtExprFrom(val alias: S_Name?, val entityName: List<S_Name>)

class C_AtWhatField(val name: String?, val expr: Db_Expr, val flags: R_AtWhatFlags)

class C_AtWhat(fields: List<C_AtWhatField>) {
    val fields = fields.toImmList()
}

class S_AtExprWhatSort(val pos: S_Pos, val asc: Boolean) {
    val rSort = if (asc) R_AtWhatSort.ASC else R_AtWhatSort.DESC
}

sealed class S_AtExprWhat {
    abstract fun compile(ctx: C_ExprContext, from: List<C_AtEntity>, subValues: MutableList<C_Value>): C_AtWhat
}

class S_AtExprWhat_Default: S_AtExprWhat() {
    override fun compile(ctx: C_ExprContext, from: List<C_AtEntity>, subValues: MutableList<C_Value>): C_AtWhat {
        val fields = from.map {
            val name = if (from.size == 1) null else it.alias
            val expr = it.compileExpr()
            C_AtWhatField(name, expr, R_AtWhatFlags.DEFAULT)
        }
        return C_AtWhat(fields)
    }
}

class S_AtExprWhat_Simple(val path: List<S_Name>): S_AtExprWhat() {
    override fun compile(ctx: C_ExprContext, from: List<C_AtEntity>, subValues: MutableList<C_Value>): C_AtWhat {
        var expr = ctx.nameCtx.resolveAttr(path[0])
        for (step in path.subList(1, path.size)) {
            expr = expr.member(ctx, step, false)
        }

        val dbExpr = expr.value().toDbExpr()
        val fields = listOf(C_AtWhatField(null, dbExpr, R_AtWhatFlags.DEFAULT))
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
    override fun compile(ctx: C_ExprContext, from: List<C_AtEntity>, subValues: MutableList<C_Value>): C_AtWhat {
        val procFields = processFields(ctx)
        subValues.addAll(procFields.map { it.value })

        val selFields = procFields.filter { it.flags.select }
        if (selFields.isEmpty()) {
            ctx.msgCtx.error(fields[0].expr.startPos, "at:no_fields", "All fields are excluded from the result")
        }

        val cFields = procFields.map { field ->
            val name = if (field.flags.select && (field.nameExplicit || selFields.size > 1)) field.name else null
            C_AtWhatField(name, field.dbExpr, field.flags)
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
                ctx.msgCtx.error(field.value.pos, code, msg)
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
                aggregation = aggregation != null
        )

        val value = field.expr.compile(ctx).value()
        val dbExpr = compileWhatExpr(ctx, value, aggregation)

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
        } else if (!omit) {
            name = dbExpr.implicitName()
        }

        return WhatField(value, dbExpr, namePos, name, nameExplicit, flags)
    }

    private fun compileWhatExpr(ctx: C_ExprContext, value: C_Value, aggregation: C_AtAggregation?): Db_Expr {
        val dbExpr = value.toDbExpr()
        if (aggregation == null || aggregation.aggrFn == null) {
            return dbExpr
        }

        val fn = aggregation.aggrFn

        if (!fn.typeChecker(dbExpr.type)) {
            val code = "at:what:aggr:bad_type:$aggregation:${dbExpr.type.toStrictString()}"
            val msg = "Invalid type of ${aggregation} expression: ${dbExpr.type}"
            ctx.msgCtx.error(value.pos, code, msg)
        }

        val aggrDbExpr = Db_CallExpr(dbExpr.type, fn.sysFn, listOf(dbExpr))
        return aggrDbExpr
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
            val value: C_Value,
            val dbExpr: Db_Expr,
            val namePos: S_Pos,
            val name: String?,
            val nameExplicit: Boolean,
            val flags: R_AtWhatFlags
    ) {
        fun updateName(newName: String?): WhatField {
            return WhatField(
                    value = value,
                    dbExpr = dbExpr,
                    namePos = namePos,
                    name = newName,
                    nameExplicit = nameExplicit,
                    flags = flags
            )
        }
    }
}

class S_AtExprWhere(val exprs: List<S_Expr>) {
    fun compile(ctx: C_ExprContext, subValues: MutableList<C_Value>): Db_Expr? {
        val whereExprs = exprs.withIndex().map { (idx, expr) -> compileWhereExpr(ctx, idx, expr, subValues) }
        val dbWhere = makeWhere(whereExprs)
        return dbWhere
    }

    private fun compileWhereExpr(ctx: C_ExprContext, idx: Int, expr: S_Expr, subValues: MutableList<C_Value>): C_Expr {
        val cExpr = expr.compileWhere(ctx, idx)
        val cValue = cExpr.value()
        subValues.add(cValue)

        val type = cValue.type()
        if (type == R_BooleanType) {
            return cExpr
        }

        if (cValue.isDb()) {
            throw C_Errors.errTypeMismatch(cValue.pos, type, R_BooleanType, "at_where:type:$idx",
                    "Wrong type of where-expression #${idx+1}")
        }

        val dbExpr = cValue.toDbExpr()

        val attrs = S_AtExpr.findWhereContextAttrsByType(ctx, type)
        if (attrs.isEmpty()) {
            throw C_Error(expr.startPos, "at_where_type:$idx:$type", "No attribute matches type of where-expression #${idx + 1}: $type")
        } else if (attrs.size > 1) {
            throw C_Errors.errMultipleAttrs(expr.startPos, attrs, "at_attr_type_ambig:$idx:$type",
                    "Multiple attributes match type of where-expression #${idx+1} ($type)")
        }

        val attr = attrs[0]
        val attrExpr = attr.compile()

        val dbEqExpr = C_Utils.makeDbBinaryExprEq(attrExpr, dbExpr)
        return C_DbValue.createExpr(expr.startPos, dbEqExpr)
    }

    private fun makeWhere(compiledExprs: List<C_Expr>): Db_Expr? {
        val compiledValues = compiledExprs.map { it.value() }
        for (value in compiledValues) {
            check(value.type() == R_BooleanType)
        }

        val dbExprs = compiledValues.filter { it.isDb() }.map { it.toDbExpr() }
        val rExprs = compiledValues.filter { !it.isDb() }.map { it.toRExpr() }

        val dbTree = exprsToTree(dbExprs)
        val rTree = exprsToTree(rExprs)

        val dbRTree = if (rTree == null) null else {
            val pos = compiledValues.first { !it.isDb() }.pos
            C_Utils.toDbExpr(pos, rTree)
        }

        if (dbTree != null && dbRTree != null) {
            return C_Utils.makeDbBinaryExpr(R_BooleanType, R_BinaryOp_And, Db_BinaryOp_And, dbTree, dbRTree)
        } else if (dbTree != null) {
            return dbTree
        } else if (dbRTree != null) {
            return dbRTree
        } else {
            return null
        }
    }

    private fun exprsToTree(exprs: List<Db_Expr>): Db_Expr? {
        return if (exprs.isEmpty()) {
            null
        } else {
            C_Utils.makeDbBinaryExprChain(R_BooleanType, R_BinaryOp_And, Db_BinaryOp_And, exprs)
        }
    }

    private fun exprsToTree(exprs: List<R_Expr>): R_Expr? {
        if (exprs.isEmpty()) {
            return null
        }

        var left = exprs[0]
        for (right in exprs.subList(1, exprs.size)) {
            left = R_BinaryExpr(R_BooleanType, R_BinaryOp_And, left, right)
        }

        return left
    }
}

enum class S_AtCardinality(val rCardinality: R_AtCardinality) {
    ZERO_ONE(R_AtCardinality.ZERO_ONE),
    ONE(R_AtCardinality.ONE),
    ZERO_MANY(R_AtCardinality.ZERO_MANY),
    ONE_MANY(R_AtCardinality.ONE_MANY),
}

class S_AtExpr(
        startPos: S_Pos,
        val cardinality: S_AtCardinality,
        val from: List<S_AtExprFrom>,
        val where: S_AtExprWhere,
        val what: S_AtExprWhat,
        val limit: S_Expr?
): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        val base = compileBase(ctx)

        val type = if (cardinality.rCardinality.many) {
            R_ListType(base.resType.type)
        } else if (cardinality.rCardinality.zero) {
            R_NullableType(base.resType.type)
        } else {
            base.resType.type
        }

        var rExpr = R_AtExpr(type, base.rBase, cardinality.rCardinality, base.limit, base.resType.rowDecoder)
        return C_RValue.makeExpr(startPos, rExpr, base.exprFacts)
    }

    private fun compileBase(ctx: C_ExprContext): AtBase {
        val subValues = mutableListOf<C_Value>()

        val cFrom = compileFrom(ctx, from)
        val rFrom = cFrom.map { it.compile() }

        val dbCtx = ctx.update(nameCtx = C_NameContext.createAt(ctx.nameCtx, cFrom))
        val dbWhere = where.compile(dbCtx, subValues)

        val cWhat = what.compile(dbCtx, cFrom, subValues)
        val resType = calcResultType(cWhat.fields)

        val rWhat = cWhat.fields.filter { !it.flags.ignore }.map { R_AtWhatField(it.expr, it.flags) }

        val rLimit = compileLimit(ctx, subValues)

        val base = R_AtExprBase(rFrom, rWhat, dbWhere)
        val facts = C_ExprVarFacts.forSubExpressions(subValues)

        return AtBase(base, rLimit, resType, facts)
    }

    private fun calcResultType(whatFields: List<C_AtWhatField>): AtResultType {
        val selFields = whatFields.filter { it.flags.select }
        if (selFields.size == 1 && selFields[0].name == null) {
            val type = selFields[0].expr.type
            return AtResultType(type, R_AtExprRowType_Simple)
        } else {
            val tupleFields = selFields.map { R_TupleField(it.name, it.expr.type) }
            val type = R_TupleType(tupleFields)
            return AtResultType(type, R_AtExprRowType_Tuple(type))
        }
    }

    private fun compileLimit(ctx: C_ExprContext, subValues: MutableList<C_Value>): R_Expr? {
        if (limit == null) {
            return null
        }

        val cValue = limit.compile(ctx).value()
        subValues.add(cValue)

        val type = cValue.type()
        if (type != R_IntegerType) {
            throw C_Error(limit.startPos, "expr_at_limit_type:${type.toStrictString()}",
                    "Wrong limit type: ${type.toStrictString()} instead of ${R_IntegerType.toStrictString()}")
        }

        return cValue.toRExpr()
    }

    private class AtResultType(val type: R_Type, val rowDecoder: R_AtExprRowType)

    companion object {
        fun compileFrom(ctx: C_ExprContext, from: List<S_AtExprFrom>): List<C_AtEntity> {
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
            return Pair(alias, C_AtEntity(entity, alias.str, idx))
        }

        fun findWhereContextAttrsByType(ctx: C_ExprContext, type: R_Type): List<C_ExprContextAttr> {
            return if (type == R_BooleanType) {
                listOf()
            } else {
                ctx.nameCtx.findAttributesByType(type)
            }
        }

        private class AtBase(
                val rBase: R_AtExprBase,
                val limit: R_Expr?,
                val resType: AtResultType,
                val exprFacts: C_ExprVarFacts
        )
    }
}
