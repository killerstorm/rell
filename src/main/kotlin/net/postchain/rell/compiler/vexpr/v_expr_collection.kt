package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.C_DbAtWhatValue
import net.postchain.rell.compiler.C_DbAtWhatValue_Complex
import net.postchain.rell.compiler.C_ExprContext
import net.postchain.rell.compiler.C_ForIterator
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_ListValue
import net.postchain.rell.runtime.Rt_MapValue
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList

class V_ListLiteralExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        elems: List<V_Expr>,
        private val listType: R_ListType
): V_Expr(exprCtx, pos) {
    val elems = elems.toImmList()

    override fun exprInfo0() = V_ExprInfo.simple(listType, elems, canBeDbExpr = false)

    override fun toRExpr0(): R_Expr {
        val rExprs = elems.map { it.toRExpr() }
        return R_ListLiteralExpr(listType, rExprs)
    }

    override fun toDbExprWhat0(): C_DbAtWhatValue {
        val evaluator = object: Db_ComplexAtWhatEvaluator() {
            override fun evaluate(frame: Rt_CallFrame, values: List<Rt_AtWhatItem>): Rt_Value {
                val values2 = values.map { it.value() }.toMutableList()
                return Rt_ListValue(listType, values2)
            }
        }
        return C_DbAtWhatValue_Complex(elems, evaluator)
    }

    override fun constantValue(ctx: V_ConstantValueEvalContext): Rt_Value? {
        val values = elems.mapNotNull { it.constantValue(ctx) }
        if (values.size != elems.size) return null
        return Rt_ListValue(listType, values.toMutableList())
    }
}

class V_MapLiteralExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        entries: List<Pair<V_Expr, V_Expr>>,
        private val mapType: R_MapType
): V_Expr(exprCtx, pos) {
    val entries = entries.toImmList()

    override fun exprInfo0() = V_ExprInfo.simple(mapType, entries.flatMap { it.toList() }, canBeDbExpr = false)

    override fun toRExpr0(): R_Expr {
        val rEntries = entries.map { it.first.toRExpr() to it.second.toRExpr() }
        return R_MapLiteralExpr(mapType, rEntries)
    }

    override fun toDbExprWhat0(): C_DbAtWhatValue {
        val vExprs = entries.flatMap { it.toList() }

        val evaluator = object: Db_ComplexAtWhatEvaluator() {
            override fun evaluate(frame: Rt_CallFrame, values: List<Rt_AtWhatItem>): Rt_Value {
                check(values.size % 2 == 0) { values.size }
                val map = values.indices.step(2)
                        .associate {
                            val key = values[it].value()
                            val value = values[it + 1].value()
                            key to value
                        }
                        .toMutableMap()
                return Rt_MapValue(mapType, map)
            }
        }

        return C_DbAtWhatValue_Complex(vExprs, evaluator)
    }

    override fun constantValue(ctx: V_ConstantValueEvalContext): Rt_Value? {
        val values = entries.map { (k, v) -> Pair(k.constantValue(ctx), v.constantValue(ctx)) }
        if (values.any { (k, v) -> k == null || v == null }) return null
        return Rt_MapValue(mapType, values.associate { (k, v) -> k!! to v!! }.toMutableMap())
    }
}

class V_EmptyCollectionConstructorExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val kind: R_CollectionKind
): V_Expr(exprCtx, pos) {
    override fun exprInfo0() = V_ExprInfo.simple(kind.type)
    override fun toRExpr0(): R_Expr = R_EmptyCollectionConstructorExpr(kind)
}

class V_CopyCollectionConstructorExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val kind: R_CollectionKind,
        private val arg: V_Expr,
        private val cIterator: C_ForIterator
): V_Expr(exprCtx, pos) {
    override fun exprInfo0() = V_ExprInfo.simple(kind.type, arg, canBeDbExpr = false)

    override fun toRExpr0(): R_Expr {
        val rArg = arg.toRExpr()
        return R_CopyCollectionConstructorExpr(kind, rArg, cIterator.rIterator)
    }

    override fun toDbExprWhat0(): C_DbAtWhatValue {
        val evaluator = object: Db_ComplexAtWhatEvaluator() {
            override fun evaluate(frame: Rt_CallFrame, values: List<Rt_AtWhatItem>): Rt_Value {
                checkEquals(values.size, 1)
                val col = values.first().value().asCollection()
                return kind.makeRtValue(col)
            }
        }

        return C_DbAtWhatValue_Complex(immListOf(arg), evaluator)
    }
}

class V_EmptyMapConstructorExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val resType: R_Type
): V_Expr(exprCtx, pos) {
    override fun exprInfo0() = V_ExprInfo.simple(resType)
    override fun toRExpr0(): R_Expr = R_EmptyMapConstructorExpr(resType)
}

class V_MapCopyMapConstructorExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val resType: R_Type,
        private val arg: V_Expr
): V_Expr(exprCtx, pos) {
    override fun exprInfo0() = V_ExprInfo.simple(resType, arg, canBeDbExpr = false)

    override fun toRExpr0(): R_Expr {
        val rArg = arg.toRExpr()
        return R_MapCopyMapConstructorExpr(resType, rArg)
    }
}

class V_IteratorCopyMapConstructorExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val resType: R_Type,
        private val arg: V_Expr,
        private val cIterator: C_ForIterator
): V_Expr(exprCtx, pos) {
    override fun exprInfo0() = V_ExprInfo.simple(resType, arg, canBeDbExpr = false)

    override fun toRExpr0(): R_Expr {
        val rArg = arg.toRExpr()
        return R_IteratorCopyMapConstructorExpr(resType, rArg, cIterator.rIterator)
    }
}
