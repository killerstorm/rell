package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.C_DbAtWhatValue
import net.postchain.rell.compiler.C_ExprContext
import net.postchain.rell.compiler.C_ExprVarFacts
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_ListValue
import net.postchain.rell.runtime.Rt_MapValue
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList

class V_ListLiteralExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        elems: List<V_Expr>,
        private val listType: R_ListType,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    val elems = elems.toImmList()

    override val exprInfo = V_ExprInfo.make(elems, canBeDbExpr = false)

    override fun type() = listType
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rExprs = elems.map { it.toRExpr() }
        return R_ListLiteralExpr(listType, rExprs)
    }

    override fun toDbExprWhat0(): C_DbAtWhatValue {
        val evaluator = object: Db_ComplexAtWhatEvaluator() {
            override fun evaluate(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
                return Rt_ListValue(listType, values.toMutableList())
            }
        }
        return V_AtUtils.createAtWhatValueComplex(elems, evaluator)
    }
}

class V_MapLiteralExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        entries: List<Pair<V_Expr, V_Expr>>,
        private val mapType: R_MapType,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    val entries = entries.toImmList()

    override val exprInfo = V_ExprInfo.make(entries.flatMap { it.toList() }, canBeDbExpr = false)

    override fun type() = mapType
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rEntries = entries.map { it.first.toRExpr() to it.second.toRExpr() }
        return R_MapLiteralExpr(mapType, rEntries)
    }

    override fun toDbExprWhat0(): C_DbAtWhatValue {
        val vExprs = entries.flatMap { it.toList() }

        val evaluator = object: Db_ComplexAtWhatEvaluator() {
            override fun evaluate(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
                check(values.size % 2 == 0) { values.size }
                val map = values.indices.step(2).map { values[it] to values[it + 1] }.toMap().toMutableMap()
                return Rt_MapValue(mapType, map)
            }
        }

        return V_AtUtils.createAtWhatValueComplex(vExprs, evaluator)
    }
}

class V_CollectionConstructorExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val kind: R_CollectionKind,
        private val arg: V_Expr?,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    override val exprInfo = V_ExprInfo.make(listOfNotNull(arg), canBeDbExpr = false)

    override fun type() = kind.type
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rArg = arg?.toRExpr()
        return R_CollectionConstructorExpr(kind, rArg)
    }

    override fun toDbExprWhat0(): C_DbAtWhatValue {
        val evaluator = object: Db_ComplexAtWhatEvaluator() {
            override fun evaluate(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
                val col = values.firstOrNull()?.asCollection() ?: immListOf<Rt_Value>()
                return kind.makeRtValue(col)
            }
        }

        val args = listOfNotNull(arg)
        return V_AtUtils.createAtWhatValueComplex(args, evaluator)
    }
}
