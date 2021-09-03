/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.base.core.C_LambdaBlock
import net.postchain.rell.compiler.base.expr.*
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.*
import net.postchain.rell.utils.toImmList

class V_EntityAttrExpr(
        exprCtx: C_ExprContext,
        private val memberLink: C_MemberLink,
        private val attrRef: C_EntityAttrRef,
        private val resultType: R_Type
): V_Expr(exprCtx, memberLink.base.pos) {
    private val path = toPath()
    private val cLambda = createLambda(exprCtx, path.first().attrRef.rEntity)

    override fun exprInfo0() = V_ExprInfo.simple(resultType, memberLink.base)

    override fun globalConstantRestriction() = V_GlobalConstantRestriction("entity_attr", null)

    override fun implicitAtWhereAttrName(): String? {
        return if (memberLink.base.isAtExprItem()) attrRef.attrName else null
    }

    override fun implicitAtWhatAttrName(): String? {
        return if (memberLink.base.isAtExprItem()) attrRef.attrName else null
    }

    override fun toRExpr0(): R_Expr {
        val first = path.first()
        val atEntity = exprCtx.makeAtEntity(first.attrRef.rEntity, exprCtx.appCtx.nextAtExprId())

        val dbExpr = toDbExprPath(atEntity, path)
        val whatValue = Db_AtWhatValue_DbExpr(dbExpr, path.last().attrRef.type())
        val whatField = Db_AtWhatField(R_AtWhatFieldFlags.DEFAULT, whatValue)

        return createRExpr(first.memberLink.base, atEntity, whatField, first.memberLink.safe, resultType, cLambda)
    }

    private fun toPath(): List<V_EntityAttrExpr> {
        val path = mutableListOf<V_EntityAttrExpr>()
        var expr: V_Expr = this
        while (expr is V_EntityAttrExpr) {
            path.add(expr)
            expr = expr.memberLink.base
        }
        path.reverse()
        return path.toImmList()
    }

    private fun toDbExprPath(atEntity: R_DbAtEntity, path: List<V_EntityAttrExpr>): Db_Expr {
        var dbExpr: Db_Expr = Db_EntityExpr(atEntity)
        for (step in path) {
            val dbTableExpr = asTableExpr(dbExpr)
            dbTableExpr ?: return C_Utils.errorDbExpr(resultType)
            dbExpr = step.attrRef.createDbMemberExpr(exprCtx, dbTableExpr)
        }
        return dbExpr
    }

    override fun toDbExpr0(): Db_Expr {
        val dbBase = memberLink.base.toDbExpr()
        val dbBaseTable = asTableExpr(dbBase)
        dbBaseTable ?: return C_Utils.errorDbExpr(attrRef.type())
        return attrRef.createDbMemberExpr(exprCtx, dbBaseTable)
    }

    override fun destination(): C_Destination {
        val attr = attrRef.attribute()
        if (attr == null || !attr.mutable) {
            throw C_Errors.errAttrNotMutable(memberLink.linkPos, attrRef.attrName)
        }
        exprCtx.checkDbUpdateAllowed(pos)
        return C_EntityAttrDestination(memberLink.base, attrRef.rEntity, attr)
    }

    private fun asTableExpr(dbExpr: Db_Expr): Db_TableExpr? {
        val res = dbExpr as? Db_TableExpr
        if (res == null) {
            msgCtx.error(memberLink.linkPos, "expr:entity_attr:no_table:${attrRef.attrName}",
                    "Cannot access attribute '${attrRef.attrName}'")
        }
        return res
    }

    companion object {
        fun createLambda(ctx: C_ExprContext, rEntity: R_EntityDefinition): C_LambdaBlock {
            val cLambdaB = C_LambdaBlock.builder(ctx, rEntity.type)
            return cLambdaB.build()
        }

        fun createRExpr(
                base: V_Expr,
                atEntity: R_DbAtEntity,
                whatField: Db_AtWhatField,
                safe: Boolean,
                resType: R_Type,
                cLambda: C_LambdaBlock
        ): R_Expr {
            val whereLeft = Db_EntityExpr(atEntity)
            val whereRight = cLambda.compileVarDbExpr()
            val where = C_Utils.makeDbBinaryExprEq(whereLeft, whereRight)

            val what = listOf(whatField)

            val from = listOf(atEntity)
            val atBase = Db_AtExprBase(from, what, where)
            val calculator = R_MemberCalculator_DataAttribute(resType, atBase, cLambda.rLambda)

            val rBase = base.toRExpr()
            val rExpr = R_MemberExpr(rBase, safe, calculator)
            return rExpr
        }
    }
}
