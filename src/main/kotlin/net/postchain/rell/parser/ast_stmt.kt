package net.postchain.rell.parser

import net.postchain.rell.model.*

abstract class S_Statement {
    abstract fun compile(ctx: C_ExprContext): R_Statement
    open fun returns(): Boolean = false
}

class S_EmptyStatement: S_Statement() {
    override fun compile(ctx: C_ExprContext): R_Statement = R_EmptyStatement
}

class S_ValStatement(val name: S_Name, val type: S_Type?, val expr: S_Expr): S_Statement() {
    override fun compile(ctx: C_ExprContext): R_Statement {
        val rExpr = expr.compile(ctx).toRExpr()

        C_Utils.checkUnitType(name.pos, rExpr.type, "stmt_val_unit:${name.str}",
                "Expression for '${name.str}' returns nothing")

        val rType = type?.compile(ctx)
        if (rType != null) {
            S_Type.match(rType, rExpr.type, name.pos, "stmt_val_type:${name.str}", "Type missmatch for '${name.str}'")
        }

        val varType = rType ?: rExpr.type
        val ptr = ctx.blkCtx.add(name, varType, false)
        return R_ValStatement(ptr, rExpr)
    }
}

class S_VarStatement(val name: S_Name, val type: S_Type?, val expr: S_Expr?): S_Statement() {
    override fun compile(ctx: C_ExprContext): R_Statement {
        val rExpr = expr?.compile(ctx)?.toRExpr()
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
        val ptr = ctx.blkCtx.add(name, rVarType, true)
        return R_VarStatement(ptr, rExpr)
    }
}

class S_ReturnStatement(val pos: S_Pos, val expr: S_Expr?): S_Statement() {
    override fun compile(ctx: C_ExprContext): R_Statement {
        val cExpr = expr?.compile(ctx)
        val rExpr = cExpr?.toRExpr()
        if (rExpr != null) {
            C_Utils.checkUnitType(pos, rExpr.type, "stmt_return_unit", "Expression returns nothing")
        }

        val entCtx = ctx.blkCtx.entCtx

        if (entCtx.entityType == C_EntityType.OPERATION) {
            if (rExpr != null) {
                throw C_Error(pos, "stmt_return_op_value", "Operation must return nothing")
            }
        } else {
            check(entCtx.entityType == C_EntityType.FUNCTION || entCtx.entityType == C_EntityType.QUERY)
            if (entCtx.entityType == C_EntityType.QUERY && rExpr == null) {
                throw C_Error(pos, "stmt_return_query_novalue", "Query must return a value")
            }

            val rRetType = if (rExpr == null) R_UnitType else rExpr.type
            entCtx.matchReturnType(pos, rRetType)
        }

        return R_ReturnStatement(rExpr)
    }

    override fun returns(): Boolean = true
}

class S_BlockStatement(val stmts: List<S_Statement>): S_Statement() {
    override fun compile(ctx: C_ExprContext): R_Statement {
        val blkCtx = ctx.blkCtx
        val subBlkCtx = C_BlockContext(blkCtx.entCtx, blkCtx, blkCtx.insideLoop)
        val subCtx = C_RExprContext(subBlkCtx)

        val rStmts = stmts.map { it.compile(subCtx) }

        val frameBlock = subBlkCtx.makeFrameBlock()
        return R_BlockStatement(rStmts, frameBlock)
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
    override fun compile(ctx: C_ExprContext): R_Statement {
        val rExpr = expr.compile(ctx).toRExpr()
        return R_ExprStatement(rExpr)
    }
}

class S_AssignStatement(val dstExpr: S_Expr, val op: S_Node<S_AssignOpCode>, val srcExpr: S_Expr): S_Statement() {
    override fun compile(ctx: C_ExprContext): R_Statement {
        val rDstExpr = dstExpr.compile(ctx).destination()
        val rSrcExpr = srcExpr.compile(ctx).toRExpr()
        return op.value.op.compile(op.pos, rDstExpr, rSrcExpr)
    }
}

class S_IfStatement(val expr: S_Expr, val trueStmt: S_Statement, val falseStmt: S_Statement?): S_Statement() {
    override fun compile(ctx: C_ExprContext): R_Statement {
        val rExpr = expr.compile(ctx).toRExpr()
        S_Type.match(R_BooleanType, rExpr.type, expr.startPos, "stmt_if_expr_type", "Wrong type of if-expression")

        val rTrueStmt = trueStmt.compile(ctx)
        val rFalseStmt = if (falseStmt != null) falseStmt.compile(ctx) else R_EmptyStatement

        return R_IfStatement(rExpr, rTrueStmt, rFalseStmt)
    }

    override fun returns(): Boolean {
        return falseStmt != null && trueStmt.returns() && falseStmt.returns()
    }
}

class S_WhileStatement(val expr: S_Expr, val stmt: S_Statement): S_Statement() {
    override fun compile(ctx: C_ExprContext): R_Statement {
        val rExpr = expr.compile(ctx).toRExpr()
        S_Type.match(R_BooleanType, rExpr.type, expr.startPos, "stmt_while_expr_type", "Wrong type of while-expression")

        val subBlkCtx = C_BlockContext(ctx.blkCtx.entCtx, ctx.blkCtx, true)
        val subCtx = C_RExprContext(subBlkCtx)

        val rStmt = stmt.compile(subCtx)

        val rBlock = subBlkCtx.makeFrameBlock()
        return R_WhileStatement(rExpr, rStmt, rBlock)
    }
}

class S_ForStatement(val name: S_Name, val expr: S_Expr, val stmt: S_Statement): S_Statement() {
    override fun compile(ctx: C_ExprContext): R_Statement {
        val rExpr = expr.compile(ctx).toRExpr()
        val exprType = rExpr.type

        val (varType, iterator) = compileForIterator(exprType)

        val subBlkCtx = C_BlockContext(ctx.blkCtx.entCtx, ctx.blkCtx, true)
        val subCtx = C_RExprContext(subBlkCtx)

        val ptr = subBlkCtx.add(name, varType, false)
        val rStmt = stmt.compile(subCtx)

        val rBlock = subBlkCtx.makeFrameBlock()
        return R_ForStatement(ptr, rExpr, iterator, rStmt, rBlock)
    }

    private fun compileForIterator(exprType: R_Type): Pair<R_Type, R_ForIterator> {
        if (exprType is R_CollectionType) {
            return Pair(exprType.elementType, R_ForIterator_Collection)
        } else if (exprType is R_MapType) {
            return Pair(exprType.keyType, R_ForIterator_Map)
        } else if (exprType == R_RangeType) {
            return Pair(R_IntegerType, R_ForIterator_Range)
        } else {
            throw C_Error(expr.startPos, "stmt_for_expr_type:${exprType.toStrictString()}",
                    "Wrong type of for-expression: ${exprType.toStrictString()}")
        }
    }
}

class S_BreakStatement(val pos: S_Pos): S_Statement() {
    override fun compile(ctx: C_ExprContext): R_Statement {
        if (!ctx.blkCtx.insideLoop) {
            throw C_Error(pos, "stmt_break_noloop", "Break without a loop")
        }
        return R_BreakStatement()
    }
}
