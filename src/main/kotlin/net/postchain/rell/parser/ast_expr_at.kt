package net.postchain.rell.parser

import net.postchain.rell.model.*

class S_AtExprFrom(val alias: S_Name?, val entityName: List<S_Name>)

class C_AtWhat(val exprs: List<Pair<String?, Db_Expr>>, val sort: List<Pair<Db_Expr, Boolean>>)

sealed class S_AtExprWhat {
    abstract fun compile(ctx: C_ExprContext, subValues: MutableList<C_Value>): C_AtWhat
}

class S_AtExprWhatDefault: S_AtExprWhat() {
    override fun compile(ctx: C_ExprContext, subValues: MutableList<C_Value>) = ctx.nameCtx.createDefaultAtWhat()
}

class S_AtExprWhatSimple(val path: List<S_Name>): S_AtExprWhat() {
    override fun compile(ctx: C_ExprContext, subValues: MutableList<C_Value>): C_AtWhat {
        var expr = ctx.nameCtx.resolveAttr(path[0])
        for (step in path.subList(1, path.size)) {
            expr = expr.member(ctx, step, false)
        }

        var dbExpr = expr.value().toDbExpr()
        val exprs = listOf(Pair(null, dbExpr))
        return C_AtWhat(exprs, listOf())
    }
}

class S_AtExprWhatAttr(val name: S_Name?)

class S_AtExprWhatComplexField(val attr: S_AtExprWhatAttr?, val expr: S_Expr, val sort: Boolean?)

class S_AtExprWhatComplex(val fields: List<S_AtExprWhatComplexField>): S_AtExprWhat() {
    override fun compile(ctx: C_ExprContext, subValues: MutableList<C_Value>): C_AtWhat {
        checkExplicitNames()

        val values = fields.map { it.expr.compile(ctx).value() }
        subValues.addAll(values)

        val dbExprs = values.map { it.toDbExpr() }

        val implicitNames = fields.withIndex().map { (idx, field) ->
            if (field.attr != null) null else dbExprs[idx].implicitName()
        }

        val names = mutableSetOf<String>()
        names.addAll(fields.filter { it.attr?.name != null }.map { it.attr!!.name!!.str })

        val dupNames = mutableSetOf<String>()

        for (name in implicitNames) {
            if (name != null && !names.add(name)) {
                dupNames.add(name)
            }
        }

        val exprs = fields.withIndex().map { (idx, field) ->
            val name = if (field.attr != null) {
                if (field.attr.name == null) null else field.attr.name.str
            } else {
                val impName = implicitNames[idx]
                if (impName != null && !dupNames.contains(impName) && fields.size > 1) {
                    impName
                } else {
                    null
                }
            }
            Pair(name, dbExprs[idx])
        }

        val sort = fields.withIndex()
                .filter { (_, field) -> field.sort != null }
                .map { (idx, field) -> Pair(exprs[idx].second, field.sort!!) }

        return C_AtWhat(exprs, sort)
    }

    private fun checkExplicitNames() {
        val names = mutableSetOf<String>()
        for (field in fields) {
            val name = field.attr?.name
            if (name != null && !names.add(name.str)) {
                throw C_Error(name.pos, "ct_err:at_dup_what_name:${name.str}", "Duplicate field: '${name.str}'")
            }
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
            throw C_Error(expr.startPos, "at_where_type:$idx:$type", "No attribute matches type of where-expression #${idx+1}: $type")
        } else if (attrs.size > 1) {
            throw C_Errors.errMultipleAttrs(expr.startPos, attrs, "at_attr_type_ambig:$idx:$type",
                    "Multiple attributes match type of where-expression #${idx+1} ($type)")
        }

        val attr = attrs[0]
        val attrExpr = attr.compile()

        val dbEqExpr = C_Utils.makeDbBinaryExprEq(attrExpr, dbExpr)
        return C_DbValue.makeExpr(expr.startPos, dbEqExpr)
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
): S_Expr(startPos)
{
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

        val dbCtx = ctx.update(nameCtx = C_DbNameContext(ctx.blkCtx, cFrom))
        val dbWhere = where.compile(dbCtx, subValues)

        val ctWhat = what.compile(dbCtx, subValues)
        val resType = calcResultType(ctWhat.exprs)

        val dbWhatExprs = ctWhat.exprs.map { it.second }

        val rLimit = compileLimit(ctx, subValues)

        val base = R_AtExprBase(rFrom, dbWhatExprs, dbWhere, ctWhat.sort)
        val facts = C_ExprVarFacts.forSubExpressions(subValues)

        return AtBase(base, rLimit, resType, facts)
    }

    private fun calcResultType(dbWhat: List<Pair<String?, Db_Expr>>): AtResultType {
        if (dbWhat.size == 1 && dbWhat[0].first == null) {
            val type = dbWhat[0].second.type
            return AtResultType(type, R_AtExprRowType_Simple)
        } else {
            val fields = dbWhat.map { R_TupleField(it.first, it.second.type) }
            val type = R_TupleType(fields)
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
                val entry = ctx.blkCtx.lookupLocalVar(name.str)
                if (entry != null) {
                    throw C_Error(name.pos, "expr_at_conflict_alias:${name.str}", "Name conflict: '${name.str}'")
                }
            }

            val alias = from.alias ?: from.entityName[from.entityName.size - 1]
            val entity = ctx.blkCtx.defCtx.nsCtx.getEntity(from.entityName)
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
