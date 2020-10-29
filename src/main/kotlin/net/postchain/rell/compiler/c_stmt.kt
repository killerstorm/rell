/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Statement
import net.postchain.rell.model.R_EmptyStatement
import net.postchain.rell.model.R_Statement
import net.postchain.rell.utils.toImmList

class C_Statement(
        val rStmt: R_Statement,
        val returnAlways: Boolean,
        val varFacts: C_VarFacts = C_VarFacts.EMPTY,
        val guardBlock: Boolean = false
) {
    fun update(
            rStmt: R_Statement? = null,
            returnAlways: Boolean? = null,
            varFacts: C_VarFacts? = null,
            guardBlock: Boolean? = null
    ): C_Statement {
        val rStmt2 = rStmt ?: this.rStmt
        val returnAlways2 = returnAlways ?: this.returnAlways
        val varFacts2 = varFacts ?: this.varFacts
        val guardBlock2 = guardBlock ?: this.guardBlock
        return if (rStmt2 === this.rStmt
                && returnAlways2 == this.returnAlways
                && varFacts2 === this.varFacts
                && guardBlock2 == this.guardBlock) this
                else C_Statement(rStmt = rStmt2, returnAlways = returnAlways2, varFacts = varFacts2, guardBlock = guardBlock2)
    }

    companion object {
        val EMPTY = C_Statement(R_EmptyStatement, false)
        val ERROR = C_Statement(C_Utils.ERROR_STATEMENT, false)

        fun empty(varFacts: C_VarFacts): C_Statement {
            return if (varFacts.isEmpty()) EMPTY else C_Statement(R_EmptyStatement, false, varFacts)
        }

        fun calcBranchedVarFacts(ctx: C_StmtContext, stmts: List<C_Statement>): C_VarFacts {
            val noRetStmts = stmts.filter { !it.returnAlways }
            val cases = noRetStmts.map { it.varFacts }
            return C_VarFacts.forBranches(ctx.exprCtx, cases)
        }
    }
}

class C_BlockCode(
        rStmts: List<R_Statement>,
        val returnAlways: Boolean,
        val guardBlock: Boolean,
        val deltaVarFacts: C_VarFacts,
        val factsCtx: C_VarFactsContext
) {
    val rStmts = rStmts.toImmList()

    fun createProto(): C_BlockCodeProto {
        val varFacts = factsCtx.toVarFacts()
        return C_BlockCodeProto(varFacts)
    }
}

class C_BlockCodeProto(val varFacts: C_VarFacts) {
    companion object { val EMPTY = C_BlockCodeProto(C_VarFacts.EMPTY) }
}

class C_BlockCodeBuilder(ctx: C_StmtContext, private val repl: Boolean, hasGuardBlock: Boolean, proto: C_BlockCodeProto) {
    private val ctx = ctx.updateFacts(proto.varFacts)
    private val rStmts = mutableListOf<R_Statement>()
    private var returnAlways = false
    private var deadCode = false
    private var beforeGuardBlock = hasGuardBlock
    private var afterGuardBlock = false
    private val blkVarFacts = C_BlockVarFacts(this.ctx.exprCtx.factsCtx)
    private var build = false

    fun add(stmt: S_Statement) {
        check(!build)

        val subExprCtx = ctx.exprCtx.update(factsCtx = blkVarFacts.subContext(), insideGuardBlock = beforeGuardBlock)

        val subCtx = ctx.update(
                exprCtx = subExprCtx,
                afterGuardBlock = afterGuardBlock
        )
        val cStmt = stmt.compile(subCtx, repl)

        if (returnAlways && !deadCode) {
            ctx.msgCtx.error(stmt.pos, "stmt_deadcode", "Dead code")
            deadCode = true
        }

        rStmts.add(cStmt.rStmt)

        if (cStmt.guardBlock) {
            beforeGuardBlock = false
            afterGuardBlock = true
        }

        returnAlways = returnAlways || cStmt.returnAlways
        blkVarFacts.putFacts(cStmt.varFacts)
    }

    fun build(): C_BlockCode {
        check(!build)
        build = true
        val deltaVarFacts = blkVarFacts.copyFacts()
        val factsCtx = ctx.exprCtx.factsCtx.sub(deltaVarFacts)
        return C_BlockCode(rStmts, returnAlways, afterGuardBlock, deltaVarFacts, factsCtx)
    }
}
