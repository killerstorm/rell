package net.postchain.rell.parser

import net.postchain.rell.model.*

class S_AtExprFrom(val alias: String?, val className: String)

sealed class S_AtExprWhat {
    internal abstract fun compile(ctx: DbCompilationContext): List<Pair<String?, DbExpr>>
}

class S_AtExprWhatDefault: S_AtExprWhat() {
    override fun compile(ctx: DbCompilationContext): List<Pair<String?, DbExpr>> {
        return ctx.classes.map {
            val name = if (ctx.classes.size == 1) null else it.alias
            val expr = PathDbExpr(RInstanceRefType(it.rClass), it, listOf(), null)
            Pair(name, expr)
        }
    }
}

class S_AtExprWhatSimple(val path: List<String>): S_AtExprWhat() {
    override fun compile(ctx: DbCompilationContext): List<Pair<String?, DbExpr>> {
        val dbExpr = compileDbPathExpr(ctx, path, false)
        return listOf(Pair(null, dbExpr))
    }
}

class S_AtExprWhatComplexField(val name: String?, val expr: S_Expression)

class S_AtExprWhatComplex(val fields: List<S_AtExprWhatComplexField>): S_AtExprWhat() {
    override fun compile(ctx: DbCompilationContext): List<Pair<String?, DbExpr>> {
        checkExplicitNames()

        val dbExprs = fields.map { it.expr.compileDb(ctx) }

        val implicitNames = fields.withIndex().map { (idx, field) ->
            if (field.name != null) null else dbExprs[idx].implicitName()
        }

        val names = mutableSetOf<String>()
        names.addAll(fields.filter { it.name != null }.map { it.name!! })

        val dupNames = mutableSetOf<String>()

        for (name in implicitNames) {
            if (name != null && !names.add(name)) {
                dupNames.add(name)
            }
        }

        return fields.withIndex().map { (idx, field) ->
            val name = if (field.name != null) {
                field.name
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
    }

    private fun checkExplicitNames() {
        val names = mutableSetOf<String>()
        for (field in fields) {
            if (field.name != null && !names.add(field.name)) {
                throw CtError("ct_err:at_dup_what_name:${field.name}", "Duplicated field name: ${field.name}")
            }
        }
    }
}

class S_AtExprWhere(val exprs: List<S_Expression>) {
    internal fun compile(ctx: DbCompilationContext): DbExpr? {
        val dbWhereExprs = exprs.withIndex().map { (idx, expr) -> compileWhereExpr(ctx, idx, expr) }
        val dbWhere = makeWhere(dbWhereExprs)
        return dbWhere
    }

    private fun compileWhereExpr(ctx: DbCompilationContext, idx: Int, expr: S_Expression): DbExpr {
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
        val all: Boolean
): S_Expression()
{
    override fun compile(ctx: ExprCompilationContext): RExpr {
        val rFrom = compileFrom(ctx, from)
        val dbCtx = DbCompilationContext(null, ctx, rFrom)

        val dbWhere = where.compile(dbCtx)

        val whatPairs = what.compile(dbCtx)
        val resType = calcResultType(whatPairs)
        val type = if (all) RListType(resType.type) else resType.type

        val dbWhatExprs = whatPairs.map { it.second }

        return RAtExpr(type, rFrom, dbWhatExprs, dbWhere, all, resType.rowDecoder)
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

    override fun compileDb(ctx: DbCompilationContext): DbExpr = delegateCompileDb(ctx)

    private class AtResultType(val type: RType, val rowDecoder: RAtExprRowType)

    companion object {
        internal fun compileFrom(ctx: ExprCompilationContext, from: List<S_AtExprFrom>): List<RAtClass> {
            val rFrom = from.indices.map { compileFromClass(ctx, it, from[it]) }
            val names = mutableSetOf<String>()
            for (cls in rFrom) {
                if (!names.add(cls.alias)) {
                    throw CtError("at_dup_alias:${cls.alias}", "Duplicated class alias: ${cls.alias}")
                }
            }
            return rFrom
        }

        private fun compileFromClass(ctx: ExprCompilationContext, idx: Int, from: S_AtExprFrom): RAtClass {
            val name = from.className
            val alias = if (from.alias != null) from.alias else name
            val cls = ctx.modCtx.getClass(name)
            return RAtClass(cls, alias, idx)
        }
    }
}
