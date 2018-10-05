package net.postchain.rell.parser

import net.postchain.rell.model.*
import net.postchain.rell.runtime.RtTupleValue

class S_AtExpr(
        val atClasses: List<Pair<String?, String>>,
        val exprs: List<S_Expression>,
        val all: Boolean
): S_Expression()
{
    override fun compile(ctx: ExprCompilationContext): RExpr {
        val rAtClasses = compileAtClasses(ctx)
        val dbCtx = DbCompilationContext(null, ctx, rAtClasses)
        val compiledExprs = exprs.indices.map { compileWhereExpr(dbCtx, it, exprs[it]) }
        val where = makeWhere(compiledExprs)

        val resType = calcResultType(rAtClasses)
        val type = if (all) RListType(resType.type) else resType.type
        return RAtExpr(type, rAtClasses, where, all, resType.rowDecoder)
    }

    private fun compileAtClasses(ctx: ExprCompilationContext): List<RAtClass> {
        val rAtClasses = atClasses.indices.map { compileAtClass(ctx, it, atClasses[it]) }
        val names = mutableSetOf<String>()
        for (cls in rAtClasses) {
            if (!names.add(cls.alias)) {
                throw CtError("at_dup_alias:${cls.alias}", "Duplicated class alias: ${cls.alias}")
            }
        }
        return rAtClasses
    }

    private fun compileAtClass(ctx: ExprCompilationContext, idx: Int, pair: Pair<String?, String>): RAtClass {
        val name = pair.second
        val alias = if (pair.first != null) pair.first!! else name
        val cls = ctx.modCtx.getClass(name)
        return RAtClass(cls, alias, idx)
    }

    private fun calcResultType(rAtClasses: List<RAtClass>): AtResultType {
        if (rAtClasses.size == 1) {
            val type = RInstanceRefType(rAtClasses[0].rClass)
            return AtResultType(type, RAtExprRowTypeSimple)
        } else {
            val elementTypes = rAtClasses.map { RInstanceRefType(it.rClass) }
            val type = RTupleType(elementTypes)
            return AtResultType(type, RAtExprRowTypeTuple(type))
        }
    }

    override fun compileDb(ctx: DbCompilationContext): DbExpr = TODO()

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
        return BinaryDbExpr(RBooleanType, attrExpr, dbExpr, DbBinaryOpEq)
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
            return BinaryDbExpr(RBooleanType, dbTree, InterpretedDbExpr(rTree), DbBinaryOpAnd)
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
            left = BinaryDbExpr(RBooleanType, left, right, DbBinaryOpAnd)
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

    private class AtResultType(val type: RType, val rowDecoder: RAtExprRowType)
}
