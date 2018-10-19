package net.postchain.rell.parser

import net.postchain.rell.model.*

abstract class S_Statement {
    internal abstract fun compile(ctx: ExprCompilationContext): RStatement
}

class S_ValStatement(val name: String, val type: String?, val expr: S_Expression): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compile(ctx)

        val rType = if (type == null) null else ctx.modCtx.getType(type)
        if (rType != null) {
            if (!rType.accepts(rExpr.type)) {
                throw CtError("stmt_val_type:$name:${rType.toStrictString()}:${rExpr.type.toStrictString()}",
                        "Type missmatch for '$name': ${rExpr.type.toStrictString()} instead of ${rType.toStrictString()}")
            }
        }

        val varType = if (rType != null) rType else rExpr.type

        val entry = ctx.add(name, varType, false)
        return RValStatement(entry.offset, rExpr)
    }
}

class S_VarStatement(val name: String, val type: String?, val expr: S_Expression?): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr?.compile(ctx)
        val rType = if (type == null) null else ctx.modCtx.getType(type)

        if (rExpr == null && rType == null) {
            throw CtError("stmt_var_notypeexpr:$name", "Neither type nor expression specified for '$name'")
        } else if (rExpr != null && rType != null) {
            if (!rType.accepts(rExpr.type)) {
                throw CtError("stmt_var_type:$name:${rType.toStrictString()}:${rExpr.type.toStrictString()}",
                        "Type missmatch for '$name': ${rExpr.type.toStrictString()} instead of ${rType.toStrictString()}")
            }
        }

        val rVarType = if (rType != null) rType else rExpr!!.type
        val entry = ctx.add(name, rVarType, true)
        return RVarStatement(entry.offset, rExpr)
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

class S_ExprStatement(val expr: S_Expression): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compile(ctx)
        return RExprStatement(rExpr)
    }
}

class S_AssignStatement(val name: String, val expr: S_Expression): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val entry = ctx.lookup(name)
        if (!entry.modifiable) {
            throw CtError("stmt_assign_val:$name", "Value of '$name' cannot be changed")
        }

        val rExpr = expr.compile(ctx)
        if (!entry.type.accepts(rExpr.type)) {
            throw CtError("stmt_assign_type:$name:${entry.type.toStrictString()}:${rExpr.type.toStrictString()}",
                    "Type missmatch for '$name': ${rExpr.type.toStrictString()} instead of ${entry.type.toStrictString()}")
        }

        return RAssignStatement(entry.offset, rExpr)
    }
}

class S_IfStatement(val expr: S_Expression, val trueStmt: S_Statement, val falseStmt: S_Statement?): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compileAsBoolean(ctx)
        if (!RBooleanType.accepts(rExpr.type)) {
            throw CtError("stmt_if_expr_type:${rExpr.type.toStrictString()}",
                    "Type of if-expression is ${rExpr.type.toStrictString()}")
        }

        val rTrueStmt = trueStmt.compile(ctx)
        val rFalseStmt = if (falseStmt != null) falseStmt.compile(ctx) else RBlockStatement(listOf())

        return RIfStatement(rExpr, rTrueStmt, rFalseStmt)
    }
}

class S_WhileStatement(val expr: S_Expression, val stmt: S_Statement): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compileAsBoolean(ctx)
        if (!RBooleanType.accepts(rExpr.type)) {
            throw CtError("stmt_while_expr_type:${rExpr.type.toStrictString()}",
                    "Type of while-expression is ${rExpr.type.toStrictString()}")
        }

        val subCtx = ExprCompilationContext(ctx.modCtx, ctx, ctx.dbUpdateAllowed, true)
        val rStmt = stmt.compile(subCtx)

        return RWhileStatement(rExpr, rStmt)
    }
}

class S_ForStatement(val name: String, val expr: S_Expression, val stmt: S_Statement): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compile(ctx)
        val exprType = rExpr.type

        if (!(exprType is RListType)) {
            throw CtError("stmt_for_expr_type:${exprType.toStrictString()}",
                    "Type of for-expression is ${exprType.toStrictString()}")
        }

        val subCtx = ExprCompilationContext(ctx.modCtx, ctx, ctx.dbUpdateAllowed, true)
        val entry = subCtx.add(name, exprType.elementType, false)

        val rStmt = stmt.compile(subCtx)

        return RForStatement(entry.offset, rExpr, rStmt)
    }
}

class S_BreakStatement(): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        if (!ctx.insideLoop) {
            throw CtError("stmt_break_noloop", "Break without a loop")
        }
        return RBreakStatement()
    }
}

class S_RequireStatement(val expr: S_Expression, val msgExpr: S_Expression?): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compileAsBoolean(ctx)
        if (!RBooleanType.accepts(rExpr.type)) {
            throw CtError("stmt_require_expr_type:${rExpr.type.toStrictString()}",
                    "Type of require-expression is ${rExpr.type.toStrictString()}")
        }

        val rMsgExpr = msgExpr?.compile(ctx)
        if (rMsgExpr != null) {
            if (!RTextType.accepts(rMsgExpr.type)) {
                throw CtError("stmt_require_msg_type:${rMsgExpr.type.toStrictString()}",
                        "Type of message expression is ${rMsgExpr.type.toStrictString()}")
            }
        }

        return RRequireStatement(rExpr, rMsgExpr)
    }
}
