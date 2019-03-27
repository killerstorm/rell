package net.postchain.rell.parser

import net.postchain.rell.model.*

class C_Statement(val rStmt: R_Statement, val alwaysReturn: Boolean)

abstract class S_Statement {
    abstract fun compile(ctx: C_ExprContext): C_Statement
}

class S_EmptyStatement: S_Statement() {
    override fun compile(ctx: C_ExprContext) = C_Statement(R_EmptyStatement, false)
}

class S_ValStatement(val name: S_Name, val type: S_Type?, val expr: S_Expr): S_Statement() {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val rExpr = expr.compile(ctx).value().toRExpr()

        C_Utils.checkUnitType(name.pos, rExpr.type, "stmt_val_unit:${name.str}",
                "Expression for '${name.str}' returns nothing")

        val rType = type?.compile(ctx)
        if (rType != null) {
            S_Type.match(rType, rExpr.type, name.pos, "stmt_val_type:${name.str}", "Type missmatch for '${name.str}'")
        }

        val varType = rType ?: rExpr.type
        val ptr = ctx.blkCtx.add(name, varType, false)
        val rStmt = R_ValStatement(ptr, rExpr)
        return C_Statement(rStmt, false)
    }
}

class S_VarStatement(val name: S_Name, val type: S_Type?, val expr: S_Expr?): S_Statement() {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val rExpr = expr?.compile(ctx)?.value()?.toRExpr()
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
        val rStmt = R_VarStatement(ptr, rExpr)
        return C_Statement(rStmt, false)
    }
}

class S_ReturnStatement(val pos: S_Pos, val expr: S_Expr?): S_Statement() {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val cExpr = expr?.compile(ctx)
        val rExpr = cExpr?.value()?.toRExpr()
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

        val rStmt = R_ReturnStatement(rExpr)
        return C_Statement(rStmt, true)
    }
}

class S_BlockStatement(val stmts: List<S_Statement>): S_Statement() {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val blkCtx = ctx.blkCtx
        val subBlkCtx = C_BlockContext(blkCtx.entCtx, blkCtx, blkCtx.insideLoop)
        val subCtx = C_RExprContext(subBlkCtx)

        val cStmts = stmts.map { it.compile(subCtx) }
        val rStmts = cStmts.map { it.rStmt }
        val returns = cStmts.any { it.alwaysReturn }

        val frameBlock = subBlkCtx.makeFrameBlock()
        val rStmt = R_BlockStatement(rStmts, frameBlock)
        return C_Statement(rStmt, returns)
    }
}

class S_ExprStatement(val expr: S_Expr): S_Statement() {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val rExpr = expr.compile(ctx).value().toRExpr()
        val rStmt = R_ExprStatement(rExpr)
        return C_Statement(rStmt, false)
    }
}

class S_AssignStatement(val dstExpr: S_Expr, val op: S_Node<S_AssignOpCode>, val srcExpr: S_Expr): S_Statement() {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val cDstExpr = dstExpr.compile(ctx).value().destination(ctx)
        val rSrcExpr = srcExpr.compile(ctx).value().toRExpr()
        val rStmt = op.value.op.compile(op.pos, cDstExpr, rSrcExpr)
        return C_Statement(rStmt, false)
    }
}

class S_IfStatement(val expr: S_Expr, val trueStmt: S_Statement, val falseStmt: S_Statement?): S_Statement() {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val rExpr = expr.compile(ctx).value().toRExpr()
        S_Type.match(R_BooleanType, rExpr.type, expr.startPos, "stmt_if_expr_type", "Wrong type of if-expression")

        val cTrueStmt = trueStmt.compile(ctx)
        val cFalseStmt = if (falseStmt != null) falseStmt.compile(ctx) else C_Statement(R_EmptyStatement, false)
        val returns = cTrueStmt.alwaysReturn && cFalseStmt.alwaysReturn

        val rStmt = R_IfStatement(rExpr, cTrueStmt.rStmt, cFalseStmt.rStmt)
        return C_Statement(rStmt, returns)

    }
}

class S_WhenStatementCase(val cond: S_WhenCondition, val stmt: S_Statement)

class S_WhenStatement(val pos: S_Pos, val expr: S_Expr?, val cases: List<S_WhenStatementCase>): S_Statement() {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val conds = cases.map { it.cond }

        val (full, chooser) = S_WhenExpr.compileChooser(ctx, expr, conds)

        val cStmts = cases.map { it.stmt.compile(ctx) }
        val returns = full && cStmts.all { it.alwaysReturn }

        val rStmts = cStmts.map { it.rStmt }
        val rStmt = R_WhenStatement(chooser, rStmts)
        return C_Statement(rStmt, returns)
    }
}

class S_WhileStatement(val expr: S_Expr, val stmt: S_Statement): S_Statement() {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val rExpr = expr.compile(ctx).value().toRExpr()
        S_Type.match(R_BooleanType, rExpr.type, expr.startPos, "stmt_while_expr_type", "Wrong type of while-expression")

        val subBlkCtx = C_BlockContext(ctx.blkCtx.entCtx, ctx.blkCtx, true)
        val subCtx = C_RExprContext(subBlkCtx)

        val rBodyStmt = stmt.compile(subCtx).rStmt

        val rBlock = subBlkCtx.makeFrameBlock()
        val rStmt = R_WhileStatement(rExpr, rBodyStmt, rBlock)
        return C_Statement(rStmt, false)
    }
}

class S_ForStatement(val name: S_Name, val expr: S_Expr, val stmt: S_Statement): S_Statement() {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val rExpr = expr.compile(ctx).value().toRExpr()
        val exprType = rExpr.type

        val (varType, iterator) = compileForIterator(exprType)

        val subBlkCtx = C_BlockContext(ctx.blkCtx.entCtx, ctx.blkCtx, true)
        val subCtx = C_RExprContext(subBlkCtx)

        val ptr = subBlkCtx.add(name, varType, false)
        val rBodyStmt = stmt.compile(subCtx).rStmt

        val rBlock = subBlkCtx.makeFrameBlock()
        val rStmt = R_ForStatement(ptr, rExpr, iterator, rBodyStmt, rBlock)
        return C_Statement(rStmt, false)
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
    override fun compile(ctx: C_ExprContext): C_Statement {
        if (!ctx.blkCtx.insideLoop) {
            throw C_Error(pos, "stmt_break_noloop", "Break without a loop")
        }
        val rStmt = R_BreakStatement()
        return C_Statement(rStmt, false)
    }
}
