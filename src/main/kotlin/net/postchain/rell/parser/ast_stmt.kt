package net.postchain.rell.parser

import net.postchain.rell.model.*

abstract class S_Statement {
    internal abstract fun compile(ctx: C_ExprContext): RStatement
    internal open fun returns(): Boolean = false
}

class S_EmptyStatement: S_Statement() {
    override fun compile(ctx: C_ExprContext): RStatement = REmptyStatement
}

class S_ValStatement(val name: S_Name, val type: S_Type?, val expr: S_Expr): S_Statement() {
    override fun compile(ctx: C_ExprContext): RStatement {
        val rExpr = expr.compile(ctx)

        C_Utils.checkUnitType(name.pos, rExpr.type, "stmt_val_unit:${name.str}",
                "Expression for '${name.str}' returns nothing")

        val rType = type?.compile(ctx)
        if (rType != null) {
            S_Type.match(rType, rExpr.type, name.pos, "stmt_val_type:${name.str}", "Type missmatch for '${name.str}'")
        }

        val varType = rType ?: rExpr.type
        val ptr = ctx.add(name, varType, false)
        return RValStatement(ptr, rExpr)
    }
}

class S_VarStatement(val name: S_Name, val type: S_Type?, val expr: S_Expr?): S_Statement() {
    override fun compile(ctx: C_ExprContext): RStatement {
        val rExpr = expr?.compile(ctx)
        val rType = type?.compile(ctx)

        if (rExpr == null && rType == null) {
            throw C_Error(name.pos, "stmt_var_notypeexpr:${name.str}", "Neither type nor expression specified for '${name.str}'")
        } else if (rExpr != null) {
            C_Utils.checkUnitType(name.pos, rExpr.type, "stmt_var_unit:${name.str}", "Expression for '${name.str}' returns nothing")
        }

        if (rExpr != null && rType != null) {
            S_Type.match(rType, rExpr.type, name.pos, "stmt_var_type:${name.str}", "Type missmatch for '${name.str}'")
        }

        val rVarType = if (rType != null) rType else rExpr!!.type
        val ptr = ctx.add(name, rVarType, true)
        return RVarStatement(ptr, rExpr)
    }
}

class S_ReturnStatement(val pos: S_Pos, val expr: S_Expr?): S_Statement() {
    override fun compile(ctx: C_ExprContext): RStatement {
        val rExpr = expr?.compile(ctx)
        if (rExpr != null) {
            C_Utils.checkUnitType(pos, rExpr.type, "stmt_return_unit", "Expression returns nothing")
        }

        if (ctx.entCtx.entityType == C_EntityType.OPERATION) {
            if (rExpr != null) {
                throw C_Error(pos, "stmt_return_op_value", "Operation must return nothing")
            }
        } else {
            check(ctx.entCtx.entityType == C_EntityType.FUNCTION || ctx.entCtx.entityType == C_EntityType.QUERY)
            if (ctx.entCtx.entityType == C_EntityType.QUERY && rExpr == null) {
                throw C_Error(pos, "stmt_return_query_novalue", "Query must return a value")
            }

            val rRetType = if (rExpr == null) RUnitType else rExpr.type
            ctx.entCtx.matchReturnType(pos, rRetType)
        }

        return RReturnStatement(rExpr)
    }

    override fun returns(): Boolean = true
}

class S_BlockStatement(val stmts: List<S_Statement>): S_Statement() {
    override fun compile(ctx: C_ExprContext): RStatement {
        val subCtx = C_ExprContext(ctx.entCtx, ctx, ctx.insideLoop)
        val rStmts = stmts.map { it.compile(subCtx) }
        val frameBlock = subCtx.makeFrameBlock()
        return RBlockStatement(rStmts, frameBlock)
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

class S_ExprStatement(val expr: S_Expr): S_Statement() {
    override fun compile(ctx: C_ExprContext): RStatement {
        val rExpr = expr.compile(ctx)
        return RExprStatement(rExpr)
    }
}

class S_AssignStatement(val dstExpr: S_Expr, val op: S_Node<S_AssignOpCode>, val srcExpr: S_Expr): S_Statement() {
    override fun compile(ctx: C_ExprContext): RStatement {
        val rDstExpr = dstExpr.compileDestination(op.pos, ctx)
        val rSrcExpr = srcExpr.compile(ctx)
        return op.value.op.compile(op.pos, rDstExpr, rSrcExpr)
    }
}

class S_IfStatement(val expr: S_Expr, val trueStmt: S_Statement, val falseStmt: S_Statement?): S_Statement() {
    override fun compile(ctx: C_ExprContext): RStatement {
        val rExpr = expr.compile(ctx)
        S_Type.match(RBooleanType, rExpr.type, expr.startPos, "stmt_if_expr_type", "Wrong type of if-expression")

        val rTrueStmt = trueStmt.compile(ctx)
        val rFalseStmt = if (falseStmt != null) falseStmt.compile(ctx) else REmptyStatement

        return RIfStatement(rExpr, rTrueStmt, rFalseStmt)
    }

    override fun returns(): Boolean {
        return falseStmt != null && trueStmt.returns() && falseStmt.returns()
    }
}

class S_WhileStatement(val expr: S_Expr, val stmt: S_Statement): S_Statement() {
    override fun compile(ctx: C_ExprContext): RStatement {
        val rExpr = expr.compile(ctx)
        S_Type.match(RBooleanType, rExpr.type, expr.startPos, "stmt_while_expr_type", "Wrong type of while-expression")

        val subCtx = C_ExprContext(ctx.entCtx, ctx, true)
        val rStmt = stmt.compile(subCtx)
        val rBlock = subCtx.makeFrameBlock()

        return RWhileStatement(rExpr, rStmt, rBlock)
    }
}

class S_ForStatement(val name: S_Name, val expr: S_Expr, val stmt: S_Statement): S_Statement() {
    override fun compile(ctx: C_ExprContext): RStatement {
        val rExpr = expr.compile(ctx)
        val exprType = rExpr.type

        val (varType, iterator) = compileForIterator(exprType)

        val subCtx = C_ExprContext(ctx.entCtx, ctx, true)
        val ptr = subCtx.add(name, varType, false)
        val rStmt = stmt.compile(subCtx)
        val rBlock = subCtx.makeFrameBlock()

        return RForStatement(ptr, rExpr, iterator, rStmt, rBlock)
    }

    private fun compileForIterator(exprType: RType): Pair<RType, RForIterator> {
        if (exprType is RCollectionType) {
            return Pair(exprType.elementType, RForIterator_Collection)
        } else if (exprType is RMapType) {
            return Pair(exprType.keyType, RForIterator_Map)
        } else if (exprType == RRangeType) {
            return Pair(RIntegerType, RForIterator_Range)
        } else {
            throw C_Error(expr.startPos, "stmt_for_expr_type:${exprType.toStrictString()}",
                    "Wrong type of for-expression: ${exprType.toStrictString()}")
        }
    }
}

class S_BreakStatement(val pos: S_Pos): S_Statement() {
    override fun compile(ctx: C_ExprContext): RStatement {
        if (!ctx.insideLoop) {
            throw C_Error(pos, "stmt_break_noloop", "Break without a loop")
        }
        return RBreakStatement()
    }
}
