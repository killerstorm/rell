package net.postchain.rell.parser

import net.postchain.rell.model.*

class S_AtExprFrom(val alias: S_Name?, val className: S_Name)

class C_AtWhat(val exprs: List<Pair<String?, Db_Expr>>, val sort: List<Pair<Db_Expr, Boolean>>)

sealed class S_AtExprWhat {
    abstract fun compile(ctx: C_DbExprContext): C_AtWhat
}

class S_AtExprWhatDefault: S_AtExprWhat() {
    override fun compile(ctx: C_DbExprContext): C_AtWhat {
        val exprs = ctx.classes.map {
            val name = if (ctx.classes.size == 1) null else it.alias
            val expr = Db_ClassExpr(it)
            Pair(name, expr)
        }
        return C_AtWhat(exprs, listOf())
    }
}

class S_AtExprWhatSimple(val path: List<S_Name>): S_AtExprWhat() {
    override fun compile(ctx: C_DbExprContext): C_AtWhat {
        var expr = ctx.resolveAttr(path[0])
        for (step in path.subList(1, path.size)) {
            expr = expr.member(ctx, step, false)
        }

        var dbExpr = expr.toDbExpr()
        val exprs = listOf(Pair(null, dbExpr))
        return C_AtWhat(exprs, listOf())
    }
}

class S_AtExprWhatAttr(val name: S_Name?)

class S_AtExprWhatComplexField(val attr: S_AtExprWhatAttr?, val expr: S_Expr, val sort: Boolean?)

class S_AtExprWhatComplex(val fields: List<S_AtExprWhatComplexField>): S_AtExprWhat() {
    override fun compile(ctx: C_DbExprContext): C_AtWhat {
        checkExplicitNames()

        val dbExprs = fields.map { it.expr.compile(ctx).toDbExpr() }

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
    fun compile(ctx: C_DbExprContext): Db_Expr? {
        val whereExprs = exprs.withIndex().map { (idx, expr) -> compileWhereExpr(ctx, idx, expr) }
        val dbWhere = makeWhere(whereExprs)
        return dbWhere
    }

    private fun compileWhereExpr(ctx: C_DbExprContext, idx: Int, expr: S_Expr): C_Expr {
        val cExpr = expr.compileWhere(ctx, idx)
        val type = cExpr.type()
        if (type == R_BooleanType) {
            return cExpr
        }

        val dbExpr = cExpr.toDbExpr()

        val attrs = ctx.findAttributesByType(type)
        if (attrs.isEmpty()) {
            throw C_Error(expr.startPos, "at_where_type:$idx:$type", "No attribute matches type of where-expression #${idx+1}: $type")
        } else if (attrs.size > 1) {
            throw C_Errors.errMutlipleAttrs(expr.startPos, attrs, "at_attr_type_ambig:$idx:$type",
                    "Multiple attributes match type of where-expression #${idx+1} ($type)")
        }

        val attr = attrs[0]
        val attrExpr = Db_AttrExpr(Db_ClassExpr(attr.cls), attr.attr)

        val dbEqExpr = Db_BinaryExpr(R_BooleanType, Db_BinaryOp_Eq, attrExpr, dbExpr)
        return C_DbExpr(expr.startPos, dbEqExpr)
    }

    private fun makeWhere(compiledExprs: List<C_Expr>): Db_Expr? {
        for (expr in compiledExprs) {
            check(expr.type() == R_BooleanType)
        }

        val dbExprs = compiledExprs.filter { it.isDb() }.map { it.toDbExpr() }
        val rExprs = compiledExprs.filter { !it.isDb() }.map { it.toRExpr() }

        val dbTree = exprsToTree(dbExprs)
        val rTree = exprsToTree(rExprs)

        val dbRTree = if (rTree == null) null else {
            val pos = compiledExprs.first { !it.isDb() }.startPos()
            C_Utils.toDbExpr(pos, rTree)
        }

        if (dbTree != null && dbRTree != null) {
            return Db_BinaryExpr(R_BooleanType, Db_BinaryOp_And, dbTree, dbRTree)
        } else if (dbTree != null) {
            return dbTree
        } else if (dbRTree != null) {
            return dbRTree
        } else {
            return null
        }
    }

    private fun exprsToTree(exprs: List<Db_Expr>): Db_Expr? {
        if (exprs.isEmpty()) {
            return null
        }

        var left = exprs[0]
        for (right in exprs.subList(1, exprs.size)) {
            left = Db_BinaryExpr(R_BooleanType, Db_BinaryOp_And, left, right)
        }
        return left
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

        var rExpr = R_AtExpr(type, base.rBase, base.limit, base.resType.rowDecoder)
        return C_RExpr(startPos, rExpr)
    }

    private fun compileBase(ctx: C_ExprContext): AtBase {
        val rFrom = compileFrom(ctx, from)
        val dbCtx = C_DbExprContext(ctx.blkCtx, rFrom)

        val dbWhere = where.compile(dbCtx)

        val ctWhat = what.compile(dbCtx)
        val resType = calcResultType(ctWhat.exprs)

        val dbWhatExprs = ctWhat.exprs.map { it.second }

        val rLimit = compileLimit(ctx)

        val base = R_AtExprBase(rFrom, dbWhatExprs, dbWhere, ctWhat.sort, cardinality.rCardinality)
        return AtBase(base, rLimit, resType)
    }

    private fun calcResultType(dbWhat: List<Pair<String?, Db_Expr>>): AtResultType {
        if (dbWhat.size == 1 && dbWhat[0].first == null) {
            val type = dbWhat[0].second.type
            return AtResultType(type, R_AtExprRowTypeSimple)
        } else {
            val fields = dbWhat.map { R_TupleField(it.first, it.second.type) }
            val type = R_TupleType(fields)
            return AtResultType(type, R_AtExprRowTypeTuple(type))
        }
    }

    private fun compileLimit(ctx: C_ExprContext): R_Expr? {
        if (limit == null) {
            return null
        }

        val cExpr = limit.compile(ctx)
        val type = cExpr.type()
        if (type != R_IntegerType) {
            throw C_Error(limit.startPos, "expr_at_limit_type:${type.toStrictString()}",
                    "Wrong limit type: ${type.toStrictString()} instead of ${R_IntegerType.toStrictString()}")
        }

        return cExpr.toRExpr()
    }

    private class AtResultType(val type: R_Type, val rowDecoder: R_AtExprRowType)

    companion object {
        fun compileFrom(ctx: C_ExprContext, from: List<S_AtExprFrom>): List<R_AtClass> {
            val rFrom = from.mapIndexed { i, f -> compileFromClass(ctx, i, f) }

            val names = mutableSetOf<String>()
            for ((alias, cls) in rFrom) {
                if (!names.add(cls.alias)) {
                    throw C_Error(alias.pos, "at_dup_alias:${cls.alias}", "Duplicate class alias: ${cls.alias}")
                }
            }

            return rFrom.map { ( _, cls ) -> cls }
        }

        private fun compileFromClass(ctx: C_ExprContext, idx: Int, from: S_AtExprFrom): Pair<S_Name, R_AtClass> {
            if (from.alias != null) {
                val name = from.alias
                val entry = ctx.blkCtx.lookupLocalVar(name.str)
                if (entry != null) {
                    throw C_Error(name.pos, "expr_at_conflict_alias:${name.str}", "Name conflict: '${name.str}'")
                }
            }

            val alias = from.alias ?: from.className
            val cls = ctx.blkCtx.entCtx.modCtx.getClass(from.className)
            return Pair(alias, R_AtClass(cls, alias.str, idx))
        }

        private class AtBase(val rBase: R_AtExprBase, val limit: R_Expr?, val resType: AtResultType)
    }
}
