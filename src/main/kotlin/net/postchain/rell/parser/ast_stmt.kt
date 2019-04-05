package net.postchain.rell.parser

import net.postchain.rell.model.*

class C_Statement(
        val rStmt: R_Statement,
        val returnAlways: Boolean,
        val varsInitedAlways: Set<C_VarId> = setOf(),
        val varsInitedSometimes: Set<C_VarId> = setOf()
)

abstract class S_Statement(val pos: S_Pos) {
    abstract fun compile(ctx: C_ExprContext): C_Statement
}

class S_EmptyStatement(pos: S_Pos): S_Statement(pos) {
    override fun compile(ctx: C_ExprContext) = C_Statement(R_EmptyStatement, false)
}

sealed class S_VarDeclarator {
    abstract fun compile(ctx: C_ExprContext, mutable: Boolean, rExprType: R_Type?, initedVars: MutableSet<C_VarId>): R_VarDeclarator
}

class S_SimpleVarDeclarator(val name: S_Name, val type: S_Type?): S_VarDeclarator() {
    override fun compile(ctx: C_ExprContext, mutable: Boolean, rExprType: R_Type?, initedVars: MutableSet<C_VarId>): R_VarDeclarator {
        val rType = type?.compile(ctx)

        if (name.str == "_") {
            if (rType != null) {
                throw C_Error(name.pos, "var_wildcard_type", "Name '${name.str}' is a wildcard, it cannot have a type")
            }
            return R_WildcardVarDeclarator
        }

        if (type == null && rExprType == null) {
            throw C_Error(name.pos, "stmt_var_notypeexpr:${name.str}", "Neither type nor expression specified for '${name.str}'")
        } else if (rExprType != null) {
            C_Utils.checkUnitType(name.pos, rExprType, "stmt_var_unit:${name.str}", "Expression for '${name.str}' returns nothing")
        }

        if (rExprType != null && rType != null) {
            S_Type.match(rType, rExprType, name.pos, "stmt_var_type:${name.str}", "Type missmatch for '${name.str}'")
        }

        val rVarType = rType ?: rExprType!!
        val (cId, ptr) = ctx.blkCtx.add(name, rVarType, mutable)

        if (rExprType != null) {
            initedVars.add(cId)
        }

        return R_SimpleVarDeclarator(ptr)
    }
}

class S_TupleVarDeclarator(val pos: S_Pos, val subDeclarators: List<S_VarDeclarator>): S_VarDeclarator() {
    override fun compile(ctx: C_ExprContext, mutable: Boolean, rExprType: R_Type?, initedVars: MutableSet<C_VarId>): R_VarDeclarator {
        val rSubDeclarators = compileSub(ctx, mutable, rExprType, initedVars)
        return R_TupleVarDeclarator(rSubDeclarators)
    }

    private fun compileSub(
            ctx: C_ExprContext,
            mutable: Boolean,
            rExprType: R_Type?,
            initedVars: MutableSet<C_VarId>
    ): List<R_VarDeclarator> {
        if (rExprType == null) {
            return subDeclarators.map { it.compile(ctx, mutable, null, initedVars) }
        }

        if (rExprType !is R_TupleType) {
            throw C_Error(pos, "var_notuple:$rExprType", "Expression must return a tuple, but it returns '$rExprType'")
        }

        val n1 = subDeclarators.size
        val n2 = rExprType.fields.size
        if (n1 != n2) {
            throw C_Error(pos, "var_tuple_wrongsize:$n1:$n2:$rExprType",
                    "Expression returns a tuple of $n2 element(s) instead of $n1 element(s): $rExprType")
        }

        return subDeclarators.withIndex().map { (i, subDeclarator) ->
            subDeclarator.compile(ctx, mutable, rExprType.fields[i].type, initedVars)
        }
    }
}

class S_VarStatement(
        pos: S_Pos,
        val declarator: S_VarDeclarator,
        val expr: S_Expr?,
        val mutable: Boolean
): S_Statement(pos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val rExpr = expr?.compile(ctx)?.value()?.toRExpr()

        val initedVars = mutableSetOf<C_VarId>()
        var rDeclarator = declarator.compile(ctx, mutable, rExpr?.type, initedVars)

        val rStmt = R_VarStatement(rDeclarator, rExpr)
        return C_Statement(rStmt, false, varsInitedAlways = initedVars, varsInitedSometimes = initedVars)
    }
}

class S_ReturnStatement(pos: S_Pos, val expr: S_Expr?): S_Statement(pos) {
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

class S_BlockStatement(pos: S_Pos, val stmts: List<S_Statement>): S_Statement(pos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val blkCtx = ctx.blkCtx
        val subBlkCtx = C_BlockContext(blkCtx.entCtx, blkCtx, blkCtx.loop)
        val subCtx = C_RExprContext(subBlkCtx)

        val rStmts = mutableListOf<R_Statement>()
        var returnAlways = false

        for (stmt in stmts) {
            val cStmt = stmt.compile(subCtx)

            if (returnAlways) {
                throw C_Error(stmt.pos, "stmt_deadcode", "Dead code")
            }

            rStmts.add(cStmt.rStmt)
            returnAlways = returnAlways || cStmt.returnAlways
            subBlkCtx.addVarsInited(cStmt.varsInitedAlways, cStmt.varsInitedSometimes)
        }

        val frameBlock = subBlkCtx.makeFrameBlock()
        val rStmt = R_BlockStatement(rStmts, frameBlock)
        return C_Statement(rStmt, returnAlways, subBlkCtx.varsInitedAlways(), subBlkCtx.varsInitedSometimes())
    }
}

class S_ExprStatement(val expr: S_Expr): S_Statement(expr.startPos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val rExpr = expr.compile(ctx).value().toRExpr()
        val rStmt = R_ExprStatement(rExpr)
        return C_Statement(rStmt, false)
    }
}

class S_AssignStatement(val dstExpr: S_Expr, val op: S_Node<S_AssignOpCode>, val srcExpr: S_Expr): S_Statement(dstExpr.startPos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val cDstValue = dstExpr.compile(ctx).value()
        val cDstExpr = cDstValue.destination(ctx)
        val rSrcExpr = srcExpr.compile(ctx).value().toRExpr()
        val rStmt = op.value.op.compile(op.pos, cDstExpr, rSrcExpr)
        val cVarId = cDstValue.varId()
        val initedVars = if (cVarId != null) setOf(cVarId) else setOf()
        return C_Statement(rStmt, false, varsInitedAlways = initedVars, varsInitedSometimes = initedVars)
    }
}

class S_IfStatement(pos: S_Pos, val expr: S_Expr, val trueStmt: S_Statement, val falseStmt: S_Statement?): S_Statement(pos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val rExpr = expr.compile(ctx).value().toRExpr()
        S_Type.match(R_BooleanType, rExpr.type, expr.startPos, "stmt_if_expr_type", "Wrong type of if-expression")

        val cTrueStmt = trueStmt.compile(ctx)
        val cFalseStmt = if (falseStmt != null) falseStmt.compile(ctx) else C_Statement(R_EmptyStatement, false)
        val returns = cTrueStmt.returnAlways && cFalseStmt.returnAlways

        val rStmt = R_IfStatement(rExpr, cTrueStmt.rStmt, cFalseStmt.rStmt)

        val (varsInitedAlways, varsInitedSometimes) = S_WhenStatement.calcInitedVars(listOf(cTrueStmt, cFalseStmt))
        return C_Statement(rStmt, returns, varsInitedAlways, varsInitedSometimes)
    }
}

class S_WhenStatementCase(val cond: S_WhenCondition, val stmt: S_Statement)

class S_WhenStatement(pos: S_Pos, val expr: S_Expr?, val cases: List<S_WhenStatementCase>): S_Statement(pos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val conds = cases.map { it.cond }

        val (full, chooser) = S_WhenExpr.compileChooser(ctx, expr, conds)

        val cStmts = cases.map { it.stmt.compile(ctx) }
        val returns = full && cStmts.all { it.returnAlways }

        val rStmts = cStmts.map { it.rStmt }
        val rStmt = R_WhenStatement(chooser, rStmts)

        val fullStmts = if (full) cStmts else cStmts + listOf(C_Statement(R_EmptyStatement, false))
        val (varsInitedAlways, varsInitedSometimes) = calcInitedVars(fullStmts)

        return C_Statement(rStmt, returns, varsInitedAlways, varsInitedSometimes)
    }

    companion object {
        fun calcInitedVars(stmts: List<C_Statement>): Pair<Set<C_VarId>, Set<C_VarId>> {
            val initedAlways = mutableSetOf<C_VarId>()
            val initedSometimes = mutableSetOf<C_VarId>()

            val noRetStmts = stmts.filter { !it.returnAlways }
            if (!noRetStmts.isEmpty()) {
                val stmt0 = noRetStmts[0]
                initedAlways += stmt0.varsInitedAlways
                initedSometimes += stmt0.varsInitedSometimes
                for (stmt in noRetStmts) {
                    initedAlways.retainAll(stmt.varsInitedAlways)
                    initedSometimes += stmt.varsInitedSometimes
                }
            }

            return Pair(initedAlways, initedSometimes)
        }
    }
}

class S_WhileStatement(pos: S_Pos, val expr: S_Expr, val stmt: S_Statement): S_Statement(pos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val rExpr = expr.compile(ctx).value().toRExpr()
        S_Type.match(R_BooleanType, rExpr.type, expr.startPos, "stmt_while_expr_type", "Wrong type of while-expression")

        val loopId = ctx.blkCtx.entCtx.nextLoopId()
        val subBlkCtx = C_BlockContext(ctx.blkCtx.entCtx, ctx.blkCtx, loopId)
        val subCtx = C_RExprContext(subBlkCtx)

        val cBodyStmt = stmt.compile(subCtx)
        val rBodyStmt = cBodyStmt.rStmt

        val rBlock = subBlkCtx.makeFrameBlock()
        val rStmt = R_WhileStatement(rExpr, rBodyStmt, rBlock)

        return C_Statement(
                rStmt,
                false,
                varsInitedAlways = setOf(),
                varsInitedSometimes =  cBodyStmt.varsInitedSometimes
        )
    }
}

class S_ForStatement(pos: S_Pos, val declarator: S_VarDeclarator, val expr: S_Expr, val stmt: S_Statement): S_Statement(pos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val rExpr = expr.compile(ctx).value().toRExpr()
        val exprType = rExpr.type

        val (itemType, iterator) = compileForIterator(exprType)

        val loopId = ctx.blkCtx.entCtx.nextLoopId()
        val subBlkCtx = C_BlockContext(ctx.blkCtx.entCtx, ctx.blkCtx, loopId)
        val subCtx = C_RExprContext(subBlkCtx)

        val initedVars = mutableSetOf<C_VarId>()
        val rDeclarator = declarator.compile(subCtx, false, itemType, initedVars)
        subBlkCtx.addVarsInited(initedVars, initedVars)

        val cBodyStmt = stmt.compile(subCtx)
        val rBodyStmt = cBodyStmt.rStmt

        val rBlock = subBlkCtx.makeFrameBlock()
        val rStmt = R_ForStatement(rDeclarator, rExpr, iterator, rBodyStmt, rBlock)

        return C_Statement(
                rStmt,
                false,
                varsInitedAlways = setOf(),
                varsInitedSometimes = cBodyStmt.varsInitedSometimes
        )
    }

    private fun compileForIterator(exprType: R_Type): Pair<R_Type, R_ForIterator> {
        if (exprType is R_CollectionType) {
            return Pair(exprType.elementType, R_ForIterator_Collection)
        } else if (exprType is R_MapType) {
            val itemType = R_TupleType(listOf(R_TupleField(null, exprType.keyType), R_TupleField(null, exprType.valueType)))
            return Pair(itemType, R_ForIterator_Map(itemType))
        } else if (exprType == R_RangeType) {
            return Pair(R_IntegerType, R_ForIterator_Range)
        } else {
            throw C_Error(expr.startPos, "stmt_for_expr_type:${exprType.toStrictString()}",
                    "Wrong type of for-expression: ${exprType.toStrictString()}")
        }
    }
}

class S_BreakStatement(pos: S_Pos): S_Statement(pos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        if (ctx.blkCtx.loop == null) {
            throw C_Error(pos, "stmt_break_noloop", "Break without a loop")
        }
        val rStmt = R_BreakStatement()
        return C_Statement(rStmt, false)
    }
}
