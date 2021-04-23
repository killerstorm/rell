package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.C_ExprContext
import net.postchain.rell.compiler.C_ExprVarFacts
import net.postchain.rell.compiler.C_Utils
import net.postchain.rell.compiler.C_VarFacts
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap
import net.postchain.rell.utils.toImmSet

class V_WhenChooserDetails(
        val keyExpr: V_Expr?,
        val keyPostFacts: C_VarFacts,
        constantCases: Map<Rt_Value, Int>,
        variableCases: List<IndexedValue<V_Expr>>,
        val elseCase: IndexedValue<S_Pos>?,
        val full: Boolean,
        caseFacts: List<C_VarFacts>,
        val elseFacts: C_VarFacts
) {
    val constantCases = constantCases.toImmMap()
    val variableCases = variableCases.toImmList()
    val caseFacts = caseFacts.toImmList()

    fun makeChooser(): R_WhenChooser {
        if (keyExpr == null) {
            val keyExpr = R_ConstantExpr.makeBool(true)
            val caseExprs = variableCases.map { IndexedValue(it.index, it.value.toRExpr()) }
            return R_IterativeWhenChooser(keyExpr, caseExprs, elseCase?.index)
        }

        val rKeyExpr = keyExpr.toRExpr()

        val chooser = if (constantCases.size == variableCases.size) {
            R_LookupWhenChooser(rKeyExpr, constantCases.toMap(), elseCase?.index)
        } else {
            val caseExprs = variableCases.map { IndexedValue(it.index, it.value.toRExpr()) }
            R_IterativeWhenChooser(rKeyExpr, caseExprs, elseCase?.index)
        }

        return chooser
    }
}

class V_WhenExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val chooserDetails: V_WhenChooserDetails,
        private val valueExprs: List<V_Expr>,
        private val resType: R_Type,
        private val resVarFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    private val isDb: Boolean
    private val atDependencies: Set<R_AtExprId>

    init {
        val exprs = listOfNotNull(chooserDetails.keyExpr) + chooserDetails.variableCases.map { it.value } + valueExprs
        isDb = exprs.any { isDb(it) }
        atDependencies = exprs.flatMap { it.atDependencies() }.toImmSet()
    }

    override fun type() = resType
    override fun isDb() = isDb
    override fun atDependencies() = atDependencies
    override fun varFacts() = resVarFacts

    override fun toRExpr0(): R_Expr {
        val rChooser = chooserDetails.makeChooser()
        val rExprs = valueExprs.map { it.toRExpr() }
        return R_WhenExpr(resType, rChooser, rExprs)
    }

    override fun toDbExpr0(): Db_Expr {
        val caseCondMap = mutableMapOf<Int, MutableList<Db_Expr>>()
        for (case in chooserDetails.variableCases) caseCondMap[case.index] = mutableListOf()
        for (case in chooserDetails.variableCases) caseCondMap.getValue(case.index).add(case.value.toDbExpr())

        val keyExpr = chooserDetails.keyExpr?.toDbExpr()

        val caseExprs = caseCondMap.keys.sorted().map { idx ->
            val conds = caseCondMap.getValue(idx)
            val value = valueExprs[idx]
            Db_WhenCase(conds, value.toDbExpr())
        }

        val elseIdx = chooserDetails.elseCase
        if (elseIdx == null) {
            if (chooserDetails.full) {
                msgCtx.error(pos, "expr_when:no_else", "When must have an 'else' in a database expression")
            }
            return C_Utils.errorDbExpr(resType)
        }

        val elseExpr = valueExprs[elseIdx.index].toDbExpr()
        return Db_WhenExpr(resType, keyExpr, caseExprs, elseExpr)
    }
}