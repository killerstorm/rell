package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.C_CreateAttributes
import net.postchain.rell.compiler.C_ExprContext
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*

sealed class V_CreateExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        protected val entity: R_EntityDefinition
): V_Expr(exprCtx, pos)

class V_RegularCreateExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        entity: R_EntityDefinition,
        private val attrs: C_CreateAttributes
): V_CreateExpr(exprCtx, pos, entity) {
    override fun exprInfo0() = V_ExprInfo.simple(
            entity.type,
            attrs.explicitAttrs.map { it.expr },
            hasDbModifications = true,
            canBeDbExpr = false
    )

    override fun globalConstantRestriction() = V_GlobalConstantRestriction("create", null)

    override fun toRExpr0(): R_Expr {
        val rAttrs = (attrs.explicitAttrs + attrs.implicitAttrs).map { it.toRAttr() }
        return R_RegularCreateExpr(entity, rAttrs)
    }
}

class V_StructCreateExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        entity: R_EntityDefinition,
        private val structType: R_StructType,
        private val structExpr: V_Expr
): V_CreateExpr(exprCtx, pos, entity) {
    override fun exprInfo0() = V_ExprInfo.simple(
            entity.type,
            structExpr,
            hasDbModifications = true,
            canBeDbExpr = false
    )

    override fun globalConstantRestriction() = V_GlobalConstantRestriction("create:struct", null)

    override fun toRExpr0(): R_Expr {
        val rStructExpr = structExpr.toRExpr()
        return R_StructCreateExpr(entity, structType, rStructExpr)
    }
}
