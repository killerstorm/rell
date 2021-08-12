package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.C_CreateAttributes
import net.postchain.rell.compiler.C_ExprContext
import net.postchain.rell.compiler.C_ExprVarFacts
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*

sealed class V_CreateExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        protected val entity: R_EntityDefinition,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    //override val exprInfo = V_ExprInfo.make(listOf())

    final override fun type() = entity.type
    final override fun varFacts() = varFacts
}

class V_RegularCreateExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        entity: R_EntityDefinition,
        varFacts: C_ExprVarFacts,
        private val attrs: C_CreateAttributes
): V_CreateExpr(exprCtx, pos, entity, varFacts) {
    override val exprInfo = V_ExprInfo.make(
            attrs.explicitAttrs.map { it.expr },
            hasDbModifications = true,
            canBeDbExpr = false
    )

    override fun toRExpr0(): R_Expr {
        val rAttrs = attrs.implicitAttrs + attrs.explicitAttrs.map { it.toRAttr() }
        return R_RegularCreateExpr(entity, rAttrs)
    }
}

class V_StructCreateExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        entity: R_EntityDefinition,
        varFacts: C_ExprVarFacts,
        private val structType: R_StructType,
        private val structExpr: V_Expr
): V_CreateExpr(exprCtx, pos, entity, varFacts) {
    override val exprInfo = V_ExprInfo.make(listOf(structExpr), hasDbModifications = true, canBeDbExpr = false)

    override fun toRExpr0(): R_Expr {
        val rStructExpr = structExpr.toRExpr()
        return R_StructCreateExpr(entity, structType, rStructExpr)
    }
}
