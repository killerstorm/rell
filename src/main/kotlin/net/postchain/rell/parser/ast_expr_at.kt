package net.postchain.rell.parser

import net.postchain.rell.model.*

class S_AtExprFrom(val alias: String?, val className: String)

internal class S_AtWhat(val exprs: List<Pair<String?, DbExpr>>, val sort: List<Pair<DbExpr, Boolean>>)

sealed class S_AtExprWhat {
    internal abstract fun compile(ctx: CtDbExprContext): S_AtWhat
}

class S_AtExprWhatDefault: S_AtExprWhat() {
    override fun compile(ctx: CtDbExprContext): S_AtWhat {
        val exprs = ctx.classes.map {
            val name = if (ctx.classes.size == 1) null else it.alias
            val expr = PathDbExpr(RInstanceRefType(it.rClass), it, listOf(), null)
            Pair(name, expr)
        }
        return S_AtWhat(exprs, listOf())
    }
}

class S_AtExprWhatSimple(val path: List<String>): S_AtExprWhat() {
    override fun compile(ctx: CtDbExprContext): S_AtWhat {
        val dbExpr = compileDbPathExpr(ctx, path, false)
        val exprs = listOf(Pair(null, dbExpr))
        return S_AtWhat(exprs, listOf())
    }
}

class S_AtExprWhatComplexField(val name: String?, val expr: S_Expression, val sort: Boolean?)

class S_AtExprWhatComplex(val fields: List<S_AtExprWhatComplexField>): S_AtExprWhat() {
    override fun compile(ctx: CtDbExprContext): S_AtWhat {
        checkExplicitNames()

        val dbExprs = fields.map { it.expr.compileDb(ctx) }

        val implicitNames = fields.withIndex().map { (idx, field) ->
            if (field.name != null) null else dbExprs[idx].implicitName()
        }

        val names = mutableSetOf<String>()
        names.addAll(fields.filter { it.name != null && !it.name.isEmpty() }.map { it.name!! })

        val dupNames = mutableSetOf<String>()

        for (name in implicitNames) {
            if (name != null && !names.add(name)) {
                dupNames.add(name)
            }
        }

        val exprs = fields.withIndex().map { (idx, field) ->
            val name = if (field.name != null) {
                if (field.name.isEmpty()) null else field.name
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
                .filter { (idx, field) -> field.sort != null }
                .map { (idx, field) -> Pair(exprs[idx].second, field.sort!!) }

        return S_AtWhat(exprs, sort)
    }

    private fun checkExplicitNames() {
        val names = mutableSetOf<String>()
        for (field in fields) {
            if (field.name != null && !field.name.isEmpty() && !names.add(field.name)) {
                throw CtError("ct_err:at_dup_what_name:${field.name}", "Duplicated field name: ${field.name}")
            }
        }
    }
}

class S_AtExprWhere(val exprs: List<S_Expression>) {
    internal fun compile(ctx: CtDbExprContext): DbExpr? {
        val dbWhereExprs = exprs.withIndex().map { (idx, expr) -> compileWhereExpr(ctx, idx, expr) }
        val dbWhere = makeWhere(dbWhereExprs)
        return dbWhere
    }

    private fun compileWhereExpr(ctx: CtDbExprContext, idx: Int, expr: S_Expression): DbExpr {
        val dbExpr = expr.compileDbWhere(ctx, idx)
        val type = dbExpr.type
        if (type == RBooleanType) {
            return dbExpr
        }

        val attrs = ctx.findAttributesByType(type)
        if (attrs.isEmpty()) {
            throw CtError("at_where_type:$idx:$type", "No attribute matches type of where-expression #${idx+1}: $type")
        } else if (attrs.size > 1) {
            val n = attrs.size
            throw CtError("at_attr_type_ambig:$idx:$type:$n",
                    "Multiple attributes match type of where-expression #${idx+1} ($type): $n")
        }

        val attr = attrs[0]
        val attrExpr = PathDbExpr(attr.type, attr.cls, listOf(), attr.name)
        return BinaryDbExpr(RBooleanType, DbBinaryOp_Eq, attrExpr, dbExpr)
    }

    private fun makeWhere(compiledExprs: List<DbExpr>): DbExpr? {
        for (i in compiledExprs.indices) {
            val type = compiledExprs[i].type
            if (type != RBooleanType) {
                throw CtError("at_where_type:$i:$type", "Wrong type of where-expression $i: $type")
            }
        }

        val dbExprs = compiledExprs.filter { !(it is InterpretedDbExpr) }
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
            TODO("TODO")
        }
        return left
    }
}

class S_AtExpr(
        val from: List<S_AtExprFrom>,
        val what: S_AtExprWhat,
        val where: S_AtExprWhere,
        val limit: S_Expression?,
        val zero: Boolean,
        val many: Boolean
): S_Expression()
{
    override fun compile(ctx: CtExprContext): RExpr {
        val base = compileBase(ctx)
        val type = if (many) RListType(base.resType.type) else base.resType.type
        return RAtExpr(type, base.rBase, base.limit, base.resType.rowDecoder)
    }

    private fun compileBase(ctx: CtExprContext): AtBase {
        val rFrom = compileFrom(ctx, from)
        val dbCtx = CtDbExprContext(null, ctx, rFrom)

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

    private fun compileLimit(ctx: CtExprContext): RExpr? {
        if (limit == null) {
            return null
        }

        val r = limit.compile(ctx)
        if (r.type != RIntegerType) {
            throw CtError("expr_at_limit_type:${r.type.toStrictString()}",
                    "Wrong limit type: ${r.type.toStrictString()} instead of ${RIntegerType.toStrictString()}")
        }

        if (!many) {
            throw CtError("expr_at_limit_one", "Limit cannot be used with a single-object @-expression")
        }

        return r
    }

    override fun compileAsBoolean(ctx: CtExprContext): RExpr {
        val base = compileBase(ctx)
        return RBooleanAtExpr(base.rBase, base.limit)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr = delegateCompileDb(ctx)

    private class AtResultType(val type: RType, val rowDecoder: RAtExprRowType)

    companion object {
        internal fun compileFrom(ctx: CtExprContext, from: List<S_AtExprFrom>): List<RAtClass> {
            val rFrom = from.indices.map { compileFromClass(ctx, it, from[it]) }
            val names = mutableSetOf<String>()
            for (cls in rFrom) {
                if (!names.add(cls.alias)) {
                    throw CtError("at_dup_alias:${cls.alias}", "Duplicated class alias: ${cls.alias}")
                }
            }
            return rFrom
        }

        private fun compileFromClass(ctx: CtExprContext, idx: Int, from: S_AtExprFrom): RAtClass {
            val name = from.className
            val alias = if (from.alias != null) from.alias else name
            val cls = ctx.entCtx.modCtx.getClass(name)
            return RAtClass(cls, alias, idx)
        }

        private class AtBase(val rBase: RAtExprBase, val limit: RExpr?, val resType: AtResultType)
    }
}
