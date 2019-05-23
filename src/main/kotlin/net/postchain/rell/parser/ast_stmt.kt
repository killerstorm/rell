package net.postchain.rell.parser

import net.postchain.rell.MutableTypedKeyMap
import net.postchain.rell.TypedKey
import net.postchain.rell.model.*

abstract class S_Statement(val pos: S_Pos) {
    private val modifiedVars = TypedKey<Set<String>>()

    abstract fun compile(ctx: C_ExprContext): C_Statement

    fun compileWithFacts(ctx: C_ExprContext, facts: C_VarFacts): C_Statement {
        val subCtx = ctx.updateFacts(facts)
        val cStmt = compile(subCtx)
        return if (facts.isEmpty()) cStmt else {
            val facts2 = facts.put(cStmt.varFacts)
            C_Statement(cStmt.rStmt, cStmt.returnAlways, facts2)
        }
    }

    fun discoverVars(map: MutableTypedKeyMap): C_StatementVars {
        val vars = discoverVars0(map)
        map.put(modifiedVars, vars.modified)
        return vars
    }

    protected open fun discoverVars0(map: MutableTypedKeyMap) = C_StatementVars.EMPTY

    fun getModifiedVars(ctx: C_EntityContext): Set<String> {
        val res = ctx.statementVars.get(modifiedVars)
        return res
    }
}

class S_EmptyStatement(pos: S_Pos): S_Statement(pos) {
    override fun compile(ctx: C_ExprContext) = C_Statement.EMPTY
}

sealed class S_VarDeclarator {
    abstract fun compile(ctx: C_ExprContext, mutable: Boolean, rExprType: R_Type?, varFacts: C_MutableVarFacts): R_VarDeclarator
    abstract fun discoverVars(vars: MutableSet<String>)
}

class S_SimpleVarDeclarator(val name: S_Name, val type: S_Type?): S_VarDeclarator() {
    override fun compile(ctx: C_ExprContext, mutable: Boolean, rExprType: R_Type?, varFacts: C_MutableVarFacts): R_VarDeclarator {
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
            S_Type.match(rType, rExprType, name.pos, "stmt_var_type:${name.str}", "Type mismatch for '${name.str}'")
        }

        val rVarType = rType ?: rExprType!!
        val (cId, ptr) = ctx.blkCtx.add(name, rVarType, mutable)

        val facts = if (rExprType != null) {
            val inited = mapOf(cId to C_VarFact.YES)
            val nulled = C_VarFacts.varTypeToNulled(cId, rVarType, rExprType)
            C_VarFacts.of(inited = inited, nulled = nulled)
        } else {
            val inited = mapOf(cId to C_VarFact.NO)
            C_VarFacts.of(inited = inited)
        }

        varFacts.putFacts(facts)

        return R_SimpleVarDeclarator(ptr, rVarType)
    }

    override fun discoverVars(vars: MutableSet<String>) {
        vars.add(name.str)
    }
}

class S_TupleVarDeclarator(val pos: S_Pos, val subDeclarators: List<S_VarDeclarator>): S_VarDeclarator() {
    override fun compile(ctx: C_ExprContext, mutable: Boolean, rExprType: R_Type?, varFacts: C_MutableVarFacts): R_VarDeclarator {
        val rSubDeclarators = compileSub(ctx, mutable, rExprType, varFacts)
        return R_TupleVarDeclarator(rSubDeclarators)
    }

    private fun compileSub(
            ctx: C_ExprContext,
            mutable: Boolean,
            rExprType: R_Type?,
            varFacts: C_MutableVarFacts
    ): List<R_VarDeclarator> {
        if (rExprType == null) {
            return subDeclarators.map { it.compile(ctx, mutable, null, varFacts) }
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
            subDeclarator.compile(ctx, mutable, rExprType.fields[i].type, varFacts)
        }
    }

    override fun discoverVars(vars: MutableSet<String>) {
        for (subDeclarator in subDeclarators) {
            subDeclarator.discoverVars(vars)
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
        val cValue = expr?.compile(ctx)?.value()
        val rExpr = cValue?.toRExpr()

        val varFacts = C_MutableVarFacts()
        varFacts.putFacts(cValue?.varFacts()?.postFacts ?: C_VarFacts.EMPTY)

        var rDeclarator = declarator.compile(ctx, mutable, rExpr?.type, varFacts)

        val rStmt = R_VarStatement(rDeclarator, rExpr)
        return C_Statement(rStmt, false, varFacts.immutableCopy())
    }

    override fun discoverVars0(map: MutableTypedKeyMap): C_StatementVars {
        val declaredVars = mutableSetOf<String>()
        declarator.discoverVars(declaredVars)
        return C_StatementVars(declaredVars, setOf())
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
        val rStmts = mutableListOf<R_Statement>()
        var returnAlways = false

        val subCtx = ctx.subBlock(ctx.blkCtx.loop)
        val blkVarFacts = C_BlockVarFacts(subCtx.factsCtx)

        for (stmt in stmts) {
            val subFactsCtx = subCtx.update(factsCtx = blkVarFacts.subContext())
            val cStmt = stmt.compile(subFactsCtx)

            if (returnAlways) {
                throw C_Error(stmt.pos, "stmt_deadcode", "Dead code")
            }

            rStmts.add(cStmt.rStmt)
            returnAlways = returnAlways || cStmt.returnAlways
            blkVarFacts.putFacts(cStmt.varFacts)
        }

        val frameBlock = subCtx.blkCtx.makeFrameBlock()
        val rStmt = R_BlockStatement(rStmts, frameBlock)
        return C_Statement(rStmt, returnAlways, blkVarFacts.copyFacts())
    }

    override fun discoverVars0(map: MutableTypedKeyMap): C_StatementVars {
        val block = C_StatementVarsBlock()

        for (stmt in stmts) {
            val vars = stmt.discoverVars(map)
            block.declared(vars.declared)
            block.modified(vars.modified)
        }

        val modified = block.modified()
        return C_StatementVars(setOf(), modified)
    }
}

class S_ExprStatement(val expr: S_Expr): S_Statement(expr.startPos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val value = expr.compile(ctx).value()

        val rExpr = value.toRExpr()
        val rStmt = R_ExprStatement(rExpr)

        val varFacts = value.varFacts().postFacts
        return C_Statement(rStmt, false, varFacts)
    }
}

class S_AssignStatement(val dstExpr: S_Expr, val op: S_Node<S_AssignOpCode>, val srcExpr: S_Expr): S_Statement(dstExpr.startPos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val cDstValue = dstExpr.compile(ctx).value()
        val cSrcValue = srcExpr.compile(ctx).value()
        return op.value.op.compile(ctx, op.pos, cDstValue, cSrcValue)
    }

    override fun discoverVars0(map: MutableTypedKeyMap): C_StatementVars {
        val name = dstExpr.asName()
        return if (name == null) C_StatementVars.EMPTY else C_StatementVars(setOf(), setOf(name.str))
    }
}

class S_IfStatement(pos: S_Pos, val expr: S_Expr, val trueStmt: S_Statement, val falseStmt: S_Statement?): S_Statement(pos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val value = expr.compile(ctx).value()
        val rExpr = value.toRExpr()
        S_Type.match(R_BooleanType, rExpr.type, expr.startPos, "stmt_if_expr_type", "Wrong type of if-expression")

        val exprVarFacts = value.varFacts()

        val trueFacts = exprVarFacts.postFacts.and(exprVarFacts.trueFacts)
        val cTrueStmt = trueStmt.compileWithFacts(ctx, trueFacts)

        val falseFacts = exprVarFacts.postFacts.and(exprVarFacts.falseFacts)
        val cFalseStmt = if (falseStmt != null) {
            falseStmt.compileWithFacts(ctx, falseFacts)
        } else {
            C_Statement.empty(falseFacts)
        }

        val returns = cTrueStmt.returnAlways && cFalseStmt.returnAlways

        val rStmt = R_IfStatement(rExpr, cTrueStmt.rStmt, cFalseStmt.rStmt)

        val varFacts = C_Statement.calcBranchedVarFacts(ctx, listOf(cTrueStmt, cFalseStmt))
        return C_Statement(rStmt, returns, varFacts)
    }

    override fun discoverVars0(map: MutableTypedKeyMap): C_StatementVars {
        val trueVars = trueStmt.discoverVars(map)
        val falseVars = if (falseStmt != null) falseStmt.discoverVars(map) else C_StatementVars.EMPTY
        return C_StatementVars(setOf(), trueVars.modified + falseVars.modified)
    }
}

class S_WhenStatementCase(val cond: S_WhenCondition, val stmt: S_Statement)

class S_WhenStatement(pos: S_Pos, val expr: S_Expr?, val cases: List<S_WhenStatementCase>): S_Statement(pos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val conds = cases.map { it.cond }

        val chooser = S_WhenExpr.compileChooser(ctx, expr, conds)

        val cStmts = cases.mapIndexed { i, case -> case.stmt.compileWithFacts(chooser.bodyCtx, chooser.caseFacts[i]) }
        val returns = chooser.full && cStmts.all { it.returnAlways }

        val rStmts = cStmts.map { it.rStmt }
        val rStmt = R_WhenStatement(chooser.rChooser, rStmts)

        val fullStmts = if (chooser.full) cStmts else cStmts + listOf(C_Statement.empty(chooser.elseFacts))
        val stmtFacts = C_Statement.calcBranchedVarFacts(chooser.bodyCtx, fullStmts)
        val varFacts = chooser.keyPostFacts.and(stmtFacts)

        return C_Statement(rStmt, returns, varFacts)
    }

    override fun discoverVars0(map: MutableTypedKeyMap): C_StatementVars {
        val modified = mutableSetOf<String>()
        for (case in cases) {
            val caseVars = case.stmt.discoverVars(map)
            modified.addAll(caseVars.modified)
        }
        return C_StatementVars(setOf(), modified)
    }
}

class C_LoopStatement(
        val condCtx: C_ExprContext,
        val condExpr: R_Expr,
        val condFacts: C_ExprVarFacts,
        val postFacts: C_VarFacts
)

class S_WhileStatement(pos: S_Pos, val expr: S_Expr, val stmt: S_Statement): S_Statement(pos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val loop = compileLoop(ctx, this, expr)

        val rExpr = loop.condExpr
        S_Type.match(R_BooleanType, rExpr.type, expr.startPos, "stmt_while_expr_type", "Wrong type of while-expression")

        val loopId = ctx.blkCtx.entCtx.nextLoopId()
        val loopCtx = loop.condCtx.subBlock(loopId)

        val condFacts = loop.condFacts
        val bodyFacts = condFacts.postFacts.and(condFacts.trueFacts)
        val bodyCtx = loopCtx.updateFacts(bodyFacts)

        val cBodyStmt = stmt.compile(bodyCtx)
        val rBodyStmt = cBodyStmt.rStmt

        val rBlock = loopCtx.blkCtx.makeFrameBlock()
        val rStmt = R_WhileStatement(rExpr, rBodyStmt, rBlock)

        val varFacts = loop.postFacts.and(calcVarFacts(ctx, cBodyStmt))
        return C_Statement(rStmt, false, varFacts)
    }

    override fun discoverVars0(map: MutableTypedKeyMap): C_StatementVars {
        val bodyVars = stmt.discoverVars(map)
        return C_StatementVars(setOf(), bodyVars.modified)
    }

    companion object {
        fun compileLoop(ctx: C_ExprContext, stmt: S_Statement, expr: S_Expr): C_LoopStatement {
            val modifiedVars = getModifiedVars(stmt, ctx)
            val condCtx = ctx.updateFacts(calcUpdatedVarFacts(modifiedVars, ctx.factsCtx))
            val condValue = expr.compile(condCtx).value()

            val condFacts = condValue.varFacts()
            val postFacts = calcPostFacts(condFacts.postFacts, modifiedVars)

            val rExpr = condValue.toRExpr()
            return C_LoopStatement(condCtx, rExpr, condFacts, postFacts)
        }

        fun updateModifiedVarsFacts(stmt: S_Statement, ctx: C_ExprContext): C_ExprContext {
            val modVars = getModifiedVars(stmt, ctx)
            val varFacts = calcUpdatedVarFacts(modVars, ctx.factsCtx)
            return ctx.updateFacts(varFacts)
        }

        private fun getModifiedVars(stmt: S_Statement, ctx: C_ExprContext): List<C_ScopeEntry> {
            val modVars = stmt.getModifiedVars(ctx.blkCtx.entCtx)
            val res = ArrayList<C_ScopeEntry>(modVars.size)

            for (name in modVars) {
                val entry = ctx.blkCtx.lookupLocalVar(name)
                if (entry != null) {
                    res.add(entry)
                }
            }

            return res
        }

        private fun calcUpdatedVarFacts(modifiedVars: List<C_ScopeEntry>, factsCtx: C_VarFactsContext): C_VarFacts {
            val inited = mutableMapOf<C_VarId, C_VarFact>()
            val nulled = mutableMapOf<C_VarId, C_VarFact>()

            for (entry in modifiedVars) {
                val id = entry.varId

                val initedFact = factsCtx.inited(id)
                if (initedFact == C_VarFact.NO) {
                    inited[id] = C_VarFact.MAYBE
                }

                if (entry.type is R_NullableType) {
                    val nulledFact = factsCtx.nulled(id)
                    if (nulledFact != C_VarFact.MAYBE) {
                        nulled[id] = C_VarFact.MAYBE
                    }
                }
            }

            return C_VarFacts.of(inited = inited, nulled = nulled)
        }

        private fun calcPostFacts(facts: C_VarFacts, modifiedVars: List<C_ScopeEntry>): C_VarFacts {
            if (facts.isEmpty() || modifiedVars.isEmpty()) {
                return facts
            }
            val nulled = facts.nulled.toMutableMap()
            for (entry in modifiedVars) {
                nulled.remove(entry.varId)
            }
            return C_VarFacts.of(nulled = nulled)
        }

        fun calcVarFacts(ctx: C_ExprContext, cBodyStmt: C_Statement): C_VarFacts {
            val stmts = listOf(cBodyStmt, C_Statement.EMPTY)
            val varFacts = C_Statement.calcBranchedVarFacts(ctx, stmts)
            return varFacts
        }
    }
}

class S_ForStatement(pos: S_Pos, val declarator: S_VarDeclarator, val expr: S_Expr, val stmt: S_Statement): S_Statement(pos) {
    override fun compile(ctx: C_ExprContext): C_Statement {
        val loop = S_WhileStatement.compileLoop(ctx, this, expr)

        val rExpr = loop.condExpr
        val exprType = rExpr.type
        val (itemType, iterator) = compileForIterator(exprType)

        val loopId = ctx.blkCtx.entCtx.nextLoopId()
        val loopCtx = loop.condCtx.subBlock(loopId)

        val mutVarFacts = C_MutableVarFacts()
        val rDeclarator = declarator.compile(loopCtx, false, itemType, mutVarFacts)
        val iterFactsCtx = loopCtx.updateFacts(mutVarFacts.immutableCopy())

        val bodyCtx = iterFactsCtx.updateFacts(loop.condFacts.postFacts)
        val cBodyStmt = stmt.compile(bodyCtx)
        val rBodyStmt = cBodyStmt.rStmt

        val rBlock = loopCtx.blkCtx.makeFrameBlock()
        val rStmt = R_ForStatement(rDeclarator, rExpr, iterator, rBodyStmt, rBlock)

        val varFacts = loop.postFacts.and(S_WhileStatement.calcVarFacts(ctx, cBodyStmt))
        return C_Statement(rStmt, false, varFacts)
    }

    private fun compileForIterator(exprType: R_Type): Pair<R_Type, R_ForIterator> {
        return when (exprType) {
            is R_CollectionType -> Pair(exprType.elementType, R_ForIterator_Collection)
            is R_VirtualCollectionType -> Pair(
                    S_VirtualType.virtualMemberType(exprType.elementType()),
                    R_ForIterator_VirtualCollection
            )
            is R_MapType -> {
                val keyField = R_TupleField(null, exprType.keyType)
                val valueField = R_TupleField(null, exprType.valueType)
                val itemType = R_TupleType(listOf(keyField, valueField))
                return Pair(itemType, R_ForIterator_Map(itemType))
            }
            is R_VirtualMapType -> {
                val mapType = exprType.innerType
                val keyField = R_TupleField(null, S_VirtualType.virtualMemberType(mapType.keyType))
                val valueField = R_TupleField(null, S_VirtualType.virtualMemberType(mapType.valueType))
                val itemType = R_TupleType(listOf(keyField, valueField))
                return Pair(itemType, R_ForIterator_Map(itemType))
            }
            is R_RangeType -> Pair(R_IntegerType, R_ForIterator_Range)
            else -> {
                throw C_Error(expr.startPos, "stmt_for_expr_type:${exprType.toStrictString()}",
                        "Wrong type of for-expression: ${exprType.toStrictString()}")
            }
        }
    }

    override fun discoverVars0(map: MutableTypedKeyMap): C_StatementVars {
        val block = C_StatementVarsBlock()

        val declared = mutableSetOf<String>()
        declarator.discoverVars(declared)
        block.declared(declared)

        val bodyVars = stmt.discoverVars(map)
        block.declared(bodyVars.declared)
        block.modified(bodyVars.modified)

        val modified = block.modified()
        return C_StatementVars(setOf(), modified)
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
