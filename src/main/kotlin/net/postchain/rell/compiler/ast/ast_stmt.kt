/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.MutableTypedKeyMap
import net.postchain.rell.TypedKey
import net.postchain.rell.compiler.*
import net.postchain.rell.model.*

abstract class S_Statement(val pos: S_Pos) {
    private val modifiedVars = TypedKey<Set<String>>()

    protected abstract fun compile0(ctx: C_StmtContext, repl: Boolean = false): C_Statement

    fun compile(ctx: C_StmtContext, repl: Boolean = false): C_Statement {
        val cStmt = ctx.msgCtx.consumeError { compile0(ctx, repl) }
        if (cStmt == null) return C_Statement.ERROR
        val filePos = pos.toFilePos()
        val rStmt = R_StackTraceStatement(cStmt.rStmt, filePos)
        return cStmt.updateStmt(rStmt)
    }

    fun compileWithFacts(ctx: C_StmtContext, facts: C_VarFacts): C_Statement {
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

    fun getModifiedVars(ctx: C_FunctionContext): Set<String> {
        val res = ctx.statementVars.get(modifiedVars)
        return res
    }
}

class S_EmptyStatement(pos: S_Pos): S_Statement(pos) {
    override fun compile0(ctx: C_StmtContext, repl: Boolean) = C_Statement.EMPTY
}

sealed class S_VarDeclarator {
    abstract fun compile(ctx: C_StmtContext, mutable: Boolean, rExprType: R_Type?, varFacts: C_MutableVarFacts): R_VarDeclarator
    abstract fun discoverVars(vars: MutableSet<String>)
}

class S_SimpleVarDeclarator(private val attrHeader: S_AttrHeader): S_VarDeclarator() {
    override fun compile(ctx: C_StmtContext, mutable: Boolean, rExprType: R_Type?, varFacts: C_MutableVarFacts): R_VarDeclarator {
        val name = attrHeader.name

        if (name.str == "_") {
            if (attrHeader.hasExplicitType()) {
                throw C_Error(name.pos, "var_wildcard_type", "Name '${name.str}' is a wildcard, it cannot have a type")
            }
            return R_WildcardVarDeclarator
        }

        val rType = if (attrHeader.hasExplicitType() || rExprType == null) attrHeader.compileType(ctx.nsCtx) else null

        if (rType == null && rExprType == null) {
            throw C_Error(name.pos, "stmt_var_notypeexpr:${name.str}", "Neither type nor expression specified for '${name.str}'")
        } else if (rExprType != null) {
            C_Utils.checkUnitType(name.pos, rExprType, "stmt_var_unit:${name.str}", "Expression for '${name.str}' returns nothing")
        }

        val typeAdapter = if (rExprType != null && rType != null) {
            S_Type.adapt(rType, rExprType, name.pos, "stmt_var_type:${name.str}", "Type mismatch for '${name.str}'")
        } else {
            R_TypeAdapter_Direct
        }

        val rVarType = rType ?: rExprType!!
        val cVar = ctx.blkCtx.addLocalVar(name, rVarType, mutable)

        val facts = if (rExprType != null) {
            val inited = mapOf(cVar.uid to C_VarFact.YES)
            val nulled = C_VarFacts.varTypeToNulled(cVar.uid, rVarType, rExprType)
            C_VarFacts.of(inited = inited, nulled = nulled)
        } else {
            val inited = mapOf(cVar.uid to C_VarFact.NO)
            C_VarFacts.of(inited = inited)
        }

        varFacts.putFacts(facts)

        return R_SimpleVarDeclarator(cVar.ptr, rVarType, typeAdapter)
    }

    override fun discoverVars(vars: MutableSet<String>) {
        vars.add(attrHeader.name.str)
    }
}

class S_TupleVarDeclarator(val pos: S_Pos, val subDeclarators: List<S_VarDeclarator>): S_VarDeclarator() {
    override fun compile(ctx: C_StmtContext, mutable: Boolean, rExprType: R_Type?, varFacts: C_MutableVarFacts): R_VarDeclarator {
        val rSubDeclarators = compileSub(ctx, mutable, rExprType, varFacts)
        return R_TupleVarDeclarator(rSubDeclarators)
    }

    private fun compileSub(
            ctx: C_StmtContext,
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
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        val cValue = expr?.compile(ctx)?.value()
        val rExpr = cValue?.toRExpr()

        val varFacts = C_MutableVarFacts()
        varFacts.putFacts(cValue?.varFacts()?.postFacts ?: C_VarFacts.EMPTY)

        var rDeclarator = declarator.compile(ctx, mutable, rExpr?.type, varFacts)

        val rStmt = R_VarStatement(rDeclarator, rExpr)
        return C_Statement(rStmt, false, varFacts.toVarFacts())
    }

    override fun discoverVars0(map: MutableTypedKeyMap): C_StatementVars {
        val declaredVars = mutableSetOf<String>()
        declarator.discoverVars(declaredVars)
        return C_StatementVars(declaredVars, setOf())
    }
}

class S_ReturnStatement(pos: S_Pos, val expr: S_Expr?): S_Statement(pos) {
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        val rStmt = ctx.msgCtx.consumeError { compileInternal(ctx) } ?: C_Utils.ERROR_STATEMENT
        return C_Statement(rStmt, true)
    }

    private fun compileInternal(ctx: C_StmtContext): R_Statement {
        val cExpr = expr?.compile(ctx)
        val rExpr = cExpr?.value()?.toRExpr()
        if (rExpr != null) {
            C_Utils.checkUnitType(pos, rExpr.type, "stmt_return_unit", "Expression returns nothing")
        }

        val defType = ctx.defCtx.definitionType

        when (defType) {
            C_DefinitionType.OPERATION -> {
                if (rExpr != null) {
                    ctx.msgCtx.error(pos, "stmt_return_op_value", "Operation must return nothing")
                }
            }
            C_DefinitionType.FUNCTION, C_DefinitionType.QUERY -> {
                if (defType == C_DefinitionType.QUERY && rExpr == null) {
                    ctx.msgCtx.error(pos, "stmt_return_query_novalue", "Query must return a value")
                }

                val rRetType = if (rExpr == null) R_UnitType else rExpr.type
                ctx.fnCtx.matchReturnType(pos, rRetType)
            }
            else -> {
                ctx.msgCtx.error(pos, "stmt_return_disallowed:$defType", "Return is not allowed here")
            }
        }

        return R_ReturnStatement(rExpr)
    }
}

class S_BlockStatement(pos: S_Pos, val stmts: List<S_Statement>): S_Statement(pos) {
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        val (subCtx, subBlkCtx) = ctx.subBlock(ctx.blkCtx.loop)

        val builder = C_BlockCodeBuilder(subCtx, false, C_BlockCodeProto.EMPTY)
        for (stmt in stmts) {
            builder.add(stmt)
        }
        val blockCode = builder.build()

        val frameBlock = subBlkCtx.buildBlock()
        val rStmt = R_BlockStatement(blockCode.rStmts, frameBlock.rBlock)
        return C_Statement(rStmt, blockCode.returnAlways, blockCode.deltaVarFacts)
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
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        val value = expr.compile(ctx).value()

        val rExpr = value.toRExpr()
        val rStmt = if (repl) R_ReplExprStatement(rExpr) else R_ExprStatement(rExpr)

        val varFacts = value.varFacts().postFacts
        return C_Statement(rStmt, false, varFacts)
    }
}

class S_AssignStatement(val dstExpr: S_Expr, val op: S_PosValue<S_AssignOpCode>, val srcExpr: S_Expr): S_Statement(dstExpr.startPos) {
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        val cDstExpr = dstExpr.compileOpt(ctx)
        val cSrcExpr = srcExpr.compileOpt(ctx)
        if (cDstExpr == null || cSrcExpr == null) {
            return C_Statement.EMPTY
        }

        val cDstValue = cDstExpr.value()
        val cSrcValue = cSrcExpr.value()
        return op.value.op.compile(ctx.exprCtx, op.pos, cDstValue, cSrcValue)
    }

    override fun discoverVars0(map: MutableTypedKeyMap): C_StatementVars {
        val name = dstExpr.asName()
        return if (name == null) C_StatementVars.EMPTY else C_StatementVars(setOf(), setOf(name.str))
    }
}

class S_IfStatement(pos: S_Pos, val expr: S_Expr, val trueStmt: S_Statement, val falseStmt: S_Statement?): S_Statement(pos) {
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        val rExpr: R_Expr
        val exprVarFacts: C_ExprVarFacts

        val cExpr = expr.compileOpt(ctx)
        if (cExpr != null) {
            val value = cExpr.value()
            rExpr = value.toRExpr()
            S_Type.matchOpt(ctx.msgCtx, R_BooleanType, rExpr.type, expr.startPos, "stmt_if_expr_type", "Wrong type of if-expression")
            exprVarFacts = value.varFacts()
        } else {
            rExpr = C_Utils.crashExpr(R_BooleanType)
            exprVarFacts = C_ExprVarFacts.EMPTY
        }

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
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        val conds = cases.map { it.cond }

        val chooser = S_WhenExpr.compileChooser(ctx.exprCtx, expr, conds)
        if (chooser == null) {
            cases.forEach { it.stmt.compile(ctx) }
            return C_Statement.ERROR
        }

        val bodyCtx = ctx.update(exprCtx = chooser.bodyExprCtx)

        val cStmts = cases.mapIndexed { i, case -> case.stmt.compileWithFacts(bodyCtx, chooser.caseFacts[i]) }
        val returns = chooser.full && cStmts.all { it.returnAlways }

        val rStmts = cStmts.map { it.rStmt }
        val rStmt = R_WhenStatement(chooser.rChooser, rStmts)

        val fullStmts = if (chooser.full) cStmts else cStmts + listOf(C_Statement.empty(chooser.elseFacts))
        val stmtFacts = C_Statement.calcBranchedVarFacts(bodyCtx, fullStmts)
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
        val condCtx: C_StmtContext,
        val condExpr: R_Expr,
        val condFacts: C_ExprVarFacts,
        val postFacts: C_VarFacts
)

class S_WhileStatement(pos: S_Pos, val expr: S_Expr, val stmt: S_Statement): S_Statement(pos) {
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        val loop = compileLoop(ctx, this, expr)
        if (loop == null) {
            stmt.compile(ctx)
            return C_Statement.ERROR
        }

        val rExpr = loop.condExpr
        S_Type.matchOpt(ctx.msgCtx, R_BooleanType, rExpr.type, expr.startPos, "stmt_while_expr_type", "Wrong type of while-expression")

        val loopUid = ctx.fnCtx.nextLoopUid()
        val (loopCtx, loopBlkCtx) = loop.condCtx.subBlock(loopUid)

        val condFacts = loop.condFacts
        val bodyFacts = condFacts.postFacts.and(condFacts.trueFacts)
        val bodyCtx = loopCtx.updateFacts(bodyFacts)

        val cBodyStmt = stmt.compile(bodyCtx)
        val rBodyStmt = cBodyStmt.rStmt

        val cBlock = loopBlkCtx.buildBlock()
        val rStmt = R_WhileStatement(rExpr, rBodyStmt, cBlock.rBlock)

        val varFacts = loop.postFacts.and(calcVarFacts(ctx, cBodyStmt))
        return C_Statement(rStmt, false, varFacts)
    }

    override fun discoverVars0(map: MutableTypedKeyMap): C_StatementVars {
        val bodyVars = stmt.discoverVars(map)
        return C_StatementVars(setOf(), bodyVars.modified)
    }

    companion object {
        fun compileLoop(ctx: C_StmtContext, stmt: S_Statement, expr: S_Expr): C_LoopStatement? {
            val modifiedVars = getModifiedVars(stmt, ctx)
            val condCtx = ctx.updateFacts(calcUpdatedVarFacts(modifiedVars, ctx.exprCtx.factsCtx))

            val condExpr = expr.compileOpt(condCtx)
            if (condExpr == null) {
                return null
            }

            val condValue = condExpr.value()
            val condFacts = condValue.varFacts()
            val postFacts = calcPostFacts(condFacts.postFacts, modifiedVars)

            val rExpr = condValue.toRExpr()
            return C_LoopStatement(condCtx, rExpr, condFacts, postFacts)
        }

        private fun getModifiedVars(stmt: S_Statement, ctx: C_StmtContext): List<C_LocalVar> {
            val modVars = stmt.getModifiedVars(ctx.fnCtx)
            val res = ArrayList<C_LocalVar>(modVars.size)

            for (name in modVars) {
                val localVar = ctx.blkCtx.lookupLocalVar(name)
                if (localVar != null) {
                    res.add(localVar)
                }
            }

            return res
        }

        private fun calcUpdatedVarFacts(modifiedVars: List<C_LocalVar>, factsCtx: C_VarFactsContext): C_VarFacts {
            val inited = mutableMapOf<C_VarUid, C_VarFact>()
            val nulled = mutableMapOf<C_VarUid, C_VarFact>()

            for (localVar in modifiedVars) {
                val id = localVar.uid

                val initedFact = factsCtx.inited(id)
                if (initedFact == C_VarFact.NO) {
                    inited[id] = C_VarFact.MAYBE
                }

                if (localVar.type is R_NullableType) {
                    val nulledFact = factsCtx.nulled(id)
                    if (nulledFact != C_VarFact.MAYBE) {
                        nulled[id] = C_VarFact.MAYBE
                    }
                }
            }

            return C_VarFacts.of(inited = inited, nulled = nulled)
        }

        private fun calcPostFacts(facts: C_VarFacts, modifiedVars: List<C_LocalVar>): C_VarFacts {
            if (facts.isEmpty() || modifiedVars.isEmpty()) {
                return facts
            }
            val nulled = facts.nulled.toMutableMap()
            for (localVar in modifiedVars) {
                nulled.remove(localVar.uid)
            }
            return C_VarFacts.of(nulled = nulled)
        }

        fun calcVarFacts(ctx: C_StmtContext, cBodyStmt: C_Statement): C_VarFacts {
            val stmts = listOf(cBodyStmt, C_Statement.EMPTY)
            val varFacts = C_Statement.calcBranchedVarFacts(ctx, stmts)
            return varFacts
        }
    }
}

class S_ForStatement(pos: S_Pos, val declarator: S_VarDeclarator, val expr: S_Expr, val stmt: S_Statement): S_Statement(pos) {
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        val loop = S_WhileStatement.compileLoop(ctx, this, expr)
        if (loop == null) {
            stmt.compile(ctx)
            return C_Statement.ERROR
        }

        val rExpr = loop.condExpr
        val exprType = rExpr.type
        val (itemType, iterator) = compileForIterator(ctx, exprType)
        if (itemType == null || iterator == null) {
            stmt.compile(ctx)
            return C_Statement.ERROR
        }

        val loopUid = ctx.fnCtx.nextLoopUid()
        val (loopCtx, loopBlkCtx) = loop.condCtx.subBlock(loopUid)

        val mutVarFacts = C_MutableVarFacts()
        val rDeclarator = declarator.compile(loopCtx, false, itemType, mutVarFacts)
        val iterFactsCtx = loopCtx.updateFacts(mutVarFacts.toVarFacts())

        val bodyCtx = iterFactsCtx.updateFacts(loop.condFacts.postFacts)
        val cBodyStmt = stmt.compile(bodyCtx)
        val rBodyStmt = cBodyStmt.rStmt

        val cBlock = loopBlkCtx.buildBlock()
        val rStmt = R_ForStatement(rDeclarator, rExpr, iterator, rBodyStmt, cBlock.rBlock)

        val varFacts = loop.postFacts.and(S_WhileStatement.calcVarFacts(ctx, cBodyStmt))
        return C_Statement(rStmt, false, varFacts)
    }

    private fun compileForIterator(ctx: C_StmtContext, exprType: R_Type): Pair<R_Type?, R_ForIterator?> {
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
                ctx.msgCtx.error(expr.startPos, "stmt_for_expr_type:[${exprType.toStrictString()}]",
                        "Wrong type of for-expression: ${exprType.toStrictString()}")
                return Pair(null, null)
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
    override fun compile0(ctx: C_StmtContext, repl: Boolean): C_Statement {
        if (ctx.blkCtx.loop == null) {
            throw C_Error(pos, "stmt_break_noloop", "Break without a loop")
        }
        val rStmt = R_BreakStatement()
        return C_Statement(rStmt, false)
    }
}