package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Statement
import net.postchain.rell.model.R_EmptyStatement
import net.postchain.rell.model.R_Statement
import net.postchain.rell.toImmList

class C_Statement(val rStmt: R_Statement, val returnAlways: Boolean, val varFacts: C_VarFacts = C_VarFacts.EMPTY) {
    fun updateStmt(newStmt: R_Statement): C_Statement {
        return C_Statement(newStmt, returnAlways, varFacts)
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

class C_BlockCodeBuilder(ctx: C_StmtContext, private val repl: Boolean, proto: C_BlockCodeProto) {
    private val ctx = ctx.updateFacts(proto.varFacts)
    private val rStmts = mutableListOf<R_Statement>()
    private var returnAlways = false
    private var deadCode = false
    private val blkVarFacts = C_BlockVarFacts(this.ctx.exprCtx.factsCtx)
    private var build = false

    fun add(stmt: S_Statement) {
        check(!build)

        val subFactsCtx = ctx.update(exprCtx = ctx.exprCtx.update(factsCtx = blkVarFacts.subContext()))
        val cStmt = stmt.compile(subFactsCtx, repl)

        if (returnAlways && !deadCode) {
            ctx.msgCtx.error(stmt.pos, "stmt_deadcode", "Dead code")
            deadCode = true
        }

        rStmts.add(cStmt.rStmt)
        returnAlways = returnAlways || cStmt.returnAlways
        blkVarFacts.putFacts(cStmt.varFacts)
    }

    fun build(): C_BlockCode {
        check(!build)
        build = true
        val deltaVarFacts = blkVarFacts.copyFacts()
        val factsCtx = ctx.exprCtx.factsCtx.sub(deltaVarFacts)
        return C_BlockCode(rStmts, returnAlways, deltaVarFacts, factsCtx)
    }
}
