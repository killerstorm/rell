package net.postchain.rell.parser

import net.postchain.rell.model.*

class S_UpdateWhat(val pos: S_Pos, val name: S_Name?, val op: S_AssignOpCode?, val expr: S_Expr)

class S_UpdateStatement(
        val pos: S_Pos,
        val from: List<S_AtExprFrom>,
        val where: S_AtExprWhere,
        val what: List<S_UpdateWhat>): S_Statement()
{
    override fun compile(ctx: C_ExprContext): R_Statement {
        ctx.blkCtx.entCtx.checkDbUpdateAllowed(pos)

        val rFrom = S_AtExpr.compileFrom(ctx, from)
        val cls = rFrom[0]
        val extraClasses = rFrom.subList(1, rFrom.size)

        if (!cls.rClass.flags.canUpdate) {
            throw C_Errors.errCannotUpdate(pos, cls.rClass.name)
        }

        val dbCtx = C_DbExprContext(ctx.blkCtx, rFrom)
        val dbWhere = where.compile(dbCtx)
        val dbWhat = compileWhat(cls.rClass, dbCtx)

        return R_UpdateStatement(cls, extraClasses, dbWhere, dbWhat)
    }

    private fun compileWhat(cls: R_Class, dbCtx: C_DbExprContext): List<R_UpdateStatementWhat> {
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

    private fun compileWhatExpr(cls: R_Class, ctx: C_DbExprContext, pair: S_UpdateWhat): Db_Expr {
        val impName = pair.expr.asName()
        if (impName != null && pair.name == null) {
            val clsAttr = cls.attributes[impName.str]
            val localVar = ctx.blkCtx.lookupLocalVar(impName.str)
            if (clsAttr != null && localVar != null) {
                val rExpr = localVar.toVarExpr()
                return C_Utils.toDbExpr(impName.pos, rExpr)
            }
        }
        return pair.expr.compile(ctx).toDbExpr()
    }
}

class S_DeleteStatement(val pos: S_Pos, val from: List<S_AtExprFrom>, val where: S_AtExprWhere): S_Statement() {
    override fun compile(ctx: C_ExprContext): R_Statement {
        ctx.blkCtx.entCtx.checkDbUpdateAllowed(pos)

        val rFrom = S_AtExpr.compileFrom(ctx, from)
        val cls = rFrom[0]
        val extraClasses = rFrom.subList(1, rFrom.size)

        if (!cls.rClass.flags.canDelete) {
            throw C_Errors.errCannotDelete(pos, cls.rClass.name)
        }

        val dbCtx = C_DbExprContext(ctx.blkCtx, rFrom)
        val dbWhere = where.compile(dbCtx)

        return R_DeleteStatement(cls, extraClasses, dbWhere)
    }
}
