package net.postchain.rell.parser

import net.postchain.rell.model.*

abstract class S_Statement {
    internal abstract fun compile(ctx: ExprCompilationContext): RStatement
    internal open fun returns(): Boolean = false
}

class S_EmptyStatement: S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement = REmptyStatement
}

class S_ValStatement(val name: String, val type: S_Type?, val expr: S_Expression): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compile(ctx)

        val rType = type?.compile(ctx)
        var rExpr2 = if (rType == null) rExpr else rType.match(rExpr, "stmt_val_type:$name", "Type missmatch for '$name'")

        val varType = if (rType != null) rType else rExpr.type
        CtUtils.checkUnitType(varType, "stmt_val_unit:$name", "Type of '$name' is ${varType.toStrictString()}")

        val entry = ctx.add(name, varType, false)
        return RValStatement(entry.offset, rExpr2)
    }
}

class S_VarStatement(val name: String, val type: S_Type?, val expr: S_Expression?): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr?.compile(ctx)
        val rType = type?.compile(ctx)

        if (rExpr == null && rType == null) {
            throw CtError("stmt_var_notypeexpr:$name", "Neither type nor expression specified for '$name'")
        }

        val rExpr2 = if (rExpr != null && rType != null) {
            rType.match(rExpr, "stmt_var_type:$name", "Type missmatch for '$name'")
        } else {
            rExpr
        }

        val rVarType = if (rType != null) rType else rExpr2!!.type
        CtUtils.checkUnitType(rVarType, "stmt_var_unit:$name", "Type of '$name' is ${rVarType.toStrictString()}")

        val entry = ctx.add(name, rVarType, true)
        return RVarStatement(entry.offset, rExpr2)
    }
}

class S_ReturnStatement(val expr: S_Expression?): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr?.compile(ctx)
        if (rExpr != null) {
            CtUtils.checkUnitType(rExpr.type, "stmt_return_unit", "Type of return value is unit")
        }

        val rExpr2 = if (ctx.entCtx.entityType == CtEntityType.OPERATION) {
            if (rExpr != null) {
                throw CtError("stmt_return_op_value", "Operation must return nothing")
            }
            rExpr
        } else {
            check(ctx.entCtx.entityType == CtEntityType.FUNCTION || ctx.entCtx.entityType == CtEntityType.QUERY)
            if (ctx.entCtx.entityType == CtEntityType.QUERY && rExpr == null) {
                throw CtError("stmt_return_query_novalue", "Query must return a value")
            }

            if (rExpr != null) {
                ctx.entCtx.matchReturnType(rExpr)
            } else {
                ctx.entCtx.matchReturnTypeUnit()
                null
            }
        }

        return RReturnStatement(rExpr2)
    }

    override fun returns(): Boolean = true
}

class S_BlockStatement(val stmts: List<S_Statement>): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val subCtx = ExprCompilationContext(ctx.entCtx, ctx, ctx.insideLoop)
        val rStmts = stmts.map { it.compile(subCtx) }
        return RBlockStatement(rStmts)
    }

    override fun returns(): Boolean {
        for (stmt in stmts) {
            if (stmt.returns()) {
                return true
            }
        }
        return false
    }
}

class S_ExprStatement(val expr: S_Expression): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compile(ctx)
        return RExprStatement(rExpr)
    }
}

class S_AssignStatement(val name: String, val opCode: S_AssignOpCode, val expr: S_Expression): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val entry = ctx.lookup(name)
        if (!entry.modifiable) {
            throw CtError("stmt_assign_val:$name", "Value of '$name' cannot be changed")
        }

        val rExpr = expr.compile(ctx)
        return opCode.op.compile(entry, rExpr)
    }
}

class S_IfStatement(val expr: S_Expression, val trueStmt: S_Statement, val falseStmt: S_Statement?): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compileAsBoolean(ctx)
        val rExpr2 = RBooleanType.match(rExpr, "stmt_if_expr_type", "Wrong type of if-expression")

        val rTrueStmt = trueStmt.compile(ctx)
        val rFalseStmt = if (falseStmt != null) falseStmt.compile(ctx) else RBlockStatement(listOf())

        return RIfStatement(rExpr2, rTrueStmt, rFalseStmt)
    }

    override fun returns(): Boolean {
        return falseStmt != null && trueStmt.returns() && falseStmt.returns()
    }
}

class S_WhileStatement(val expr: S_Expression, val stmt: S_Statement): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compileAsBoolean(ctx)
        val rExpr2 = RBooleanType.match(rExpr, "stmt_while_expr_type", "Wrong type of while-expression")

        val subCtx = ExprCompilationContext(ctx.entCtx, ctx, true)
        val rStmt = stmt.compile(subCtx)

        return RWhileStatement(rExpr2, rStmt)
    }
}

class S_ForStatement(val name: String, val expr: S_Expression, val stmt: S_Statement): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        val rExpr = expr.compile(ctx)
        val exprType = rExpr.type

        val subCtx = ExprCompilationContext(ctx.entCtx, ctx, true)

        if (exprType is RListType) {
            val entry = subCtx.add(name, exprType.elementType, false)
            val rStmt = stmt.compile(subCtx)
            return RForStatement(entry.offset, rExpr, RForIterator_List, rStmt)
        } else if (exprType == RRangeType) {
            val entry = subCtx.add(name, RIntegerType, false)
            val rStmt = stmt.compile(subCtx)
            return RForStatement(entry.offset, rExpr, RForIterator_Range, rStmt)
        } else {
            throw CtError("stmt_for_expr_type:${exprType.toStrictString()}",
                    "Type of for-expression is ${exprType.toStrictString()}")
        }
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
        val rExpr2 = RBooleanType.match(rExpr, "stmt_require_expr_type", "Wrong type of require-expression")

        val rMsgExpr = msgExpr?.compile(ctx)
        val rMsgExpr2 = if (rMsgExpr == null) null else {
            RTextType.match(rMsgExpr, "stmt_require_msg_type", "Wrong type of message expression")
        }

        return RRequireStatement(rExpr2, rMsgExpr2)
    }
}
