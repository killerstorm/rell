package net.postchain.rell.parser

import net.postchain.rell.model.*

class S_AtExprFrom(val alias: S_Name?, val className: S_Name)

internal class S_AtWhat(val exprs: List<Pair<String?, DbExpr>>, val sort: List<Pair<DbExpr, Boolean>>)

sealed class S_AtExprWhat {
    internal abstract fun compile(ctx: C_DbExprContext): S_AtWhat
}

class S_AtExprWhatDefault: S_AtExprWhat() {
    override fun compile(ctx: C_DbExprContext): S_AtWhat {
        val exprs = ctx.classes.map {
            val name = if (ctx.classes.size == 1) null else it.alias
            val expr = ClassDbExpr(it)
            Pair(name, expr)
        }
        return S_AtWhat(exprs, listOf())
    }
}

class S_AtExprWhatSimple(val path: List<S_Name>): S_AtExprWhat() {
    override fun compile(ctx: C_DbExprContext): S_AtWhat {
        val dbExpr = compileDbPathExpr(ctx, path)
        val exprs = listOf(Pair(null, dbExpr))
        return S_AtWhat(exprs, listOf())
    }
}

class S_AtExprWhatAttr(val name: S_Name?)

class S_AtExprWhatComplexField(val attr: S_AtExprWhatAttr?, val expr: S_Expr, val sort: Boolean?)

class S_AtExprWhatComplex(val fields: List<S_AtExprWhatComplexField>): S_AtExprWhat() {
    override fun compile(ctx: C_DbExprContext): S_AtWhat {
        checkExplicitNames()

        val dbExprs = fields.map { it.expr.compileDb(ctx) }

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

        return S_AtWhat(exprs, sort)
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
    internal fun compile(ctx: C_DbExprContext): DbExpr? {
        val dbWhereExprs = exprs.withIndex().map { (idx, expr) -> compileWhereExpr(ctx, idx, expr) }
        val dbWhere = makeWhere(dbWhereExprs)
        return dbWhere
    }

    private fun compileWhereExpr(ctx: C_DbExprContext, idx: Int, expr: S_Expr): DbExpr {
        val dbExpr = expr.compileDbWhere(ctx, idx)
        val type = dbExpr.type
        if (type == RBooleanType) {
            return dbExpr
        }

        val attrs = ctx.findAttributesByType(type)
        if (attrs.isEmpty()) {
            throw C_Error(expr.startPos, "at_where_type:$idx:$type", "No attribute matches type of where-expression #${idx+1}: $type")
        } else if (attrs.size > 1) {
            throw C_Utils.errMutlipleAttrs(expr.startPos, attrs, "at_attr_type_ambig:$idx:$type",
                    "Multiple attributes match type of where-expression #${idx+1} ($type)")
        }

        val attr = attrs[0]
        val attrExpr = AttrDbExpr(ClassDbExpr(attr.cls), attr.attr)

        return BinaryDbExpr(RBooleanType, DbBinaryOp_Eq, attrExpr, dbExpr)
    }

    private fun makeWhere(compiledExprs: List<DbExpr>): DbExpr? {
        for (expr in compiledExprs) {
            check(expr.type == RBooleanType)
        }

        val dbExprs = compiledExprs.filter { it !is InterpretedDbExpr }
        val rExprs = compiledExprs.filter { it is InterpretedDbExpr }.map { (it as InterpretedDbExpr).expr }

        val dbTree = exprsToTree(dbExprs)
        val rTree = exprsToTree(rExprs)

        if (dbTree != null && rTree != null) {
            return BinaryDbExpr(RBooleanType, DbBinaryOp_And, dbTree, InterpretedDbExpr(rTree))
        } else if (dbTree != null) {
            return dbTree
        } else if (rTree != null) {
            return InterpretedDbExpr(rTree)
        } else {
            return null
        }
    }

    private fun exprsToTree(exprs: List<DbExpr>): DbExpr? {
        if (exprs.isEmpty()) {
            return null
        }

        var left = exprs[0]
        for (right in exprs.subList(1, exprs.size)) {
            left = BinaryDbExpr(RBooleanType, DbBinaryOp_And, left, right)
        }
        return left
    }

    private fun exprsToTree(exprs: List<RExpr>): RExpr? {
        if (exprs.isEmpty()) {
            return null
        }

        var left = exprs[0]
        for (right in exprs.subList(1, exprs.size)) {
            left = RBinaryExpr(RBooleanType, RBinaryOp_And, left, right)
        }

        return left
    }
}

class S_AtExpr(
        startPos: S_Pos,
        val from: List<S_AtExprFrom>,
        val what: S_AtExprWhat,
        val where: S_AtExprWhere,
        val limit: S_Expr?,
        val zero: Boolean,
        val many: Boolean
): S_Expr(startPos)
{
    override fun compile(ctx: C_ExprContext): RExpr {
        val base = compileBase(ctx)

        val type = if (many) {
            RListType(base.resType.type)
        } else if (zero) {
            RNullableType(base.resType.type)
        } else {
            base.resType.type
        }

        return RAtExpr(type, base.rBase, base.limit, base.resType.rowDecoder)
    }

    private fun compileBase(ctx: C_ExprContext): AtBase {
        val rFrom = compileFrom(ctx, from)
        val dbCtx = C_DbExprContext(null, ctx, rFrom)

        val dbWhere = where.compile(dbCtx)

        val ctWhat = what.compile(dbCtx)
        val resType = calcResultType(ctWhat.exprs)

        val dbWhatExprs = ctWhat.exprs.map { it.second }

        val rLimit = compileLimit(ctx)

        val base = RAtExprBase(rFrom, dbWhatExprs, dbWhere, ctWhat.sort, zero, many)
        return AtBase(base, rLimit, resType)
    }

    private fun calcResultType(dbWhat: List<Pair<String?, DbExpr>>): AtResultType {
        if (dbWhat.size == 1 && dbWhat[0].first == null) {
            val type = dbWhat[0].second.type
            return AtResultType(type, RAtExprRowTypeSimple)
        } else {
            val fields = dbWhat.map { RTupleField(it.first, it.second.type) }
            val type = RTupleType(fields)
            return AtResultType(type, RAtExprRowTypeTuple(type))
        }
    }

    private fun compileLimit(ctx: C_ExprContext): RExpr? {
        if (limit == null) {
            return null
        }

        val r = limit.compile(ctx)
        if (r.type != RIntegerType) {
            throw C_Error(limit.startPos, "expr_at_limit_type:${r.type.toStrictString()}",
                    "Wrong limit type: ${r.type.toStrictString()} instead of ${RIntegerType.toStrictString()}")
        }

        return r
    }

    override fun compileDb(ctx: C_DbExprContext): DbExpr = delegateCompileDb(ctx)

    private class AtResultType(val type: RType, val rowDecoder: RAtExprRowType)

    companion object {
        internal fun compileFrom(ctx: C_ExprContext, from: List<S_AtExprFrom>): List<RAtClass> {
            val rFrom = from.mapIndexed { i, f -> compileFromClass(ctx, i, f) }

            val names = mutableSetOf<String>()
            for ((alias, cls) in rFrom) {
                if (!names.add(cls.alias)) {
                    throw C_Error(alias.pos, "at_dup_alias:${cls.alias}", "Duplicate class alias: ${cls.alias}")
                }
            }

            return rFrom.map { ( _, cls ) -> cls }
        }

        private fun compileFromClass(ctx: C_ExprContext, idx: Int, from: S_AtExprFrom): Pair<S_Name, RAtClass> {
            if (from.alias != null) {
                val name = from.alias
                val entry = ctx.lookupOpt(name.str)
                if (entry != null) {
                    throw C_Error(name.pos, "expr_at_conflict_alias:${name.str}", "Name conflict: '${name.str}'")
                }
            }

            val alias = from.alias ?: from.className
            val cls = ctx.entCtx.modCtx.getClass(from.className)
            return Pair(alias, RAtClass(cls, alias.str, idx))
        }

        private class AtBase(val rBase: RAtExprBase, val limit: RExpr?, val resType: AtResultType)
    }
}
