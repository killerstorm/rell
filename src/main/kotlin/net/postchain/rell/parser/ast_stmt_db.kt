package net.postchain.rell.parser

import net.postchain.rell.model.*

class S_UpdateWhat(val pos: S_Pos, val name: S_Name?, val op: S_AssignOpCode?, val expr: S_Expr)

class S_UpdateStatement(
        val pos: S_Pos,
        val from: List<S_AtExprFrom>,
        val where: S_AtExprWhere,
        val what: List<S_UpdateWhat>): S_Statement()
{
    override fun compile(ctx: C_ExprContext): RStatement {
        ctx.entCtx.checkDbUpdateAllowed(pos)

        val rFrom = S_AtExpr.compileFrom(ctx, from)
        val cls = rFrom[0]
        val extraClasses = rFrom.subList(1, rFrom.size)

        val dbCtx = C_DbExprContext(null, ctx, rFrom)
        val dbWhere = where.compile(dbCtx)
        val dbWhat = compileWhat(cls.rClass, dbCtx)

        return RUpdateStatement(cls, extraClasses, dbWhere, dbWhat)
    }

    private fun compileWhat(cls: RClass, dbCtx: C_DbExprContext): List<RUpdateStatementWhat> {
        val dbWhat = what.map { compileWhatExpr(cls, dbCtx, it) }
        val types = dbWhat.map { it.type }
        val whatPairs = what.map { S_NameExprPair(it.name, it.expr) }
        val attrs = C_AttributeResolver.resolveUpdate(cls, whatPairs, types)
        val updAttrs = attrs.withIndex().map { (idx, attr) ->
            val w = what[idx]
            val op = if (w.op == null) S_AssignOp_Eq else w.op.op
            op.compileDbUpdate(w.pos, attr, dbWhat[idx])
        }
        return updAttrs
    }

    private fun compileWhatExpr(cls: RClass, ctx: C_DbExprContext, pair: S_UpdateWhat): DbExpr {
        val locName = pair.expr.asName()
        if (locName != null && pair.name == null) {
            val clsAttr = cls.attributes[locName.str]
            val localVar = ctx.exprCtx.lookupOpt(locName.str)
            if (clsAttr != null && localVar != null) {
                val rExpr = localVar.toVarExpr()
                return InterpretedDbExpr(rExpr)
            }
        }
        return pair.expr.compileDb(ctx)
    }
}

class S_DeleteStatement(val pos: S_Pos, val from: List<S_AtExprFrom>, val where: S_AtExprWhere): S_Statement() {
    override fun compile(ctx: C_ExprContext): RStatement {
        ctx.entCtx.checkDbUpdateAllowed(pos)

        val rFrom = S_AtExpr.compileFrom(ctx, from)
        val cls = rFrom[0]
        val extraClasses = rFrom.subList(1, rFrom.size)

        val dbCtx = C_DbExprContext(null, ctx, rFrom)
        val dbWhere = where.compile(dbCtx)

        return RDeleteStatement(cls, extraClasses, dbWhere)
    }
}
