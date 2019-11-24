package net.postchain.rell.parser

import net.postchain.rell.model.R_EmptyStatement
import net.postchain.rell.model.R_Statement

data class C_VarId(val id: Int, val name: String)
data class C_LoopId(val id: Int)

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

        fun calcBranchedVarFacts(ctx: C_ExprContext, stmts: List<C_Statement>): C_VarFacts {
            val noRetStmts = stmts.filter { !it.returnAlways }
            val cases = noRetStmts.map { it.varFacts }
            return C_VarFacts.forBranches(ctx, cases)
        }
    }
}
