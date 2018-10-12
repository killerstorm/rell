package net.postchain.rell.parser

import net.postchain.rell.model.*

abstract class S_Statement {
    internal abstract fun compile(ctx: ExprCompilationContext): RStatement
}

class S_ValStatement(val name: String, val expr: S_Expression): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compile(ctx)
        val entry = ctx.add(RAttrib(name, rExpr.type))
        return RValStatement(entry.offset, entry.attr, rExpr)
    }
}

class S_ReturnStatement(val expr: S_Expression): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compile(ctx)
        return RReturnStatement(rExpr)
    }
}

class S_BlockStatement(val stmts: List<S_Statement>): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rStmts = stmts.map { it.compile(ctx) }
        return RBlockStatement(rStmts)
    }
}
