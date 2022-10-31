/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_LocalVarRef
import net.postchain.rell.compiler.base.core.C_QualifiedName
import net.postchain.rell.compiler.base.expr.*
import net.postchain.rell.compiler.base.utils.C_CodeMsg
import net.postchain.rell.compiler.base.utils.C_Error
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.model.R_Attribute
import net.postchain.rell.model.R_ObjectDefinition
import net.postchain.rell.model.R_Type
import net.postchain.rell.model.expr.*
import net.postchain.rell.model.stmt.R_AssignStatement
import net.postchain.rell.model.stmt.R_Statement
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmSet

class V_LocalVarExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val varRef: C_LocalVarRef
): V_Expr(exprCtx, pos) {
    override fun exprInfo0() = V_ExprInfo(
            varRef.target.type,
            immListOf(),
            dependsOnAtExprs = listOfNotNull(varRef.target.atExprId).toImmSet()
    )

    override fun varId() = varRef.target.uid

    override fun isAtExprItem() = varRef.target.atExprId != null
    override fun implicitAtWhereAttrName() = varRef.target.rName

    override fun toRExpr0(): R_Expr {
        checkInitialized()
        return varRef.toRExpr()
    }

    override fun destination(): C_Destination {
        if (!varRef.target.mutable) {
            if (exprCtx.factsCtx.inited(varRef.target.uid) != C_VarFact.NO) {
                val name = varRef.target.metaName
                throw C_Error.stop(pos, "expr_assign_val:$name", "Value of '$name' cannot be changed")
            }
        }
        return C_Destination_LocalVar()
    }

    private fun checkInitialized() {
        if (exprCtx.factsCtx.inited(varRef.target.uid) != C_VarFact.YES) {
            val name = varRef.target.metaName
            throw C_Error.stop(pos, "expr_var_uninit:$name", "Variable '$name' may be uninitialized")
        }
    }

    private inner class C_Destination_LocalVar: C_Destination() {
        override fun type() = varRef.target.type
        override fun effectiveType() = varRef.target.type

        override fun compileAssignStatement(ctx: C_ExprContext, srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
            if (op != null) {
                checkInitialized()
            }
            val rDstExpr = varRef.toRExpr()
            return R_AssignStatement(rDstExpr, srcExpr, op?.rOp)
        }

        override fun compileAssignExpr(
                ctx: C_ExprContext,
                startPos: S_Pos,
                resType: R_Type,
                srcExpr: R_Expr,
                op: C_AssignOp,
                post: Boolean
        ): R_Expr {
            checkInitialized()
            val rDstExpr = varRef.toRExpr()
            return R_AssignExpr(resType, op.rOp, rDstExpr, srcExpr, post)
        }
    }
}

class V_SmartNullableExpr(
        exprCtx: C_ExprContext,
        private val subExpr: V_Expr,
        private val nulled: Boolean,
        private val smartType: R_Type?,
        private val name: String,
        private val kind: C_CodeMsg
): V_Expr(exprCtx, subExpr.pos) {
    override fun exprInfo0() = V_ExprInfo.simple(smartType ?: subExpr.type, subExpr)

    override fun toDbExpr0() = subExpr.toDbExpr()
    override fun varId() = subExpr.varId()

    override fun isAtExprItem() = subExpr.isAtExprItem()
    override fun implicitAtWhereAttrName() = subExpr.implicitAtWhereAttrName()

    override fun toRExpr0(): R_Expr {
        val rExpr = subExpr.toRExpr()
        return if (smartType == null) rExpr else R_NotNullExpr(smartType, rExpr)
    }

    override fun asNullable(): V_Expr {
        val (freq, msg) = if (nulled) Pair("always", "is always") else Pair("never", "cannot be")
        msgCtx.warning(pos, "expr:smartnull:${kind.code}:$freq:$name", "${kind.msg} '$name' $msg null at this location")
        return subExpr
    }

    override fun destination(): C_Destination {
        val dst = subExpr.destination()
        return if (smartType == null) dst else C_Destination_SmartNullable(dst, smartType)
    }

    private class C_Destination_SmartNullable(
            val destination: C_Destination,
            val effectiveType: R_Type
    ): C_Destination() {
        override fun type() = destination.type()
        override fun effectiveType() = effectiveType

        override fun compileAssignExpr(
                ctx: C_ExprContext,
                startPos: S_Pos,
                resType: R_Type,
                srcExpr: R_Expr,
                op: C_AssignOp,
                post: Boolean
        ): R_Expr {
            return destination.compileAssignExpr(ctx, startPos, resType, srcExpr, op, post)
        }

        override fun compileAssignStatement(ctx: C_ExprContext, srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
            return destination.compileAssignStatement(ctx, srcExpr, op)
        }
    }
}

class V_ObjectExpr(
        exprCtx: C_ExprContext,
        qName: C_QualifiedName,
        private val rObject: R_ObjectDefinition
): V_Expr(exprCtx, qName.pos) {
    override fun exprInfo0() = V_ExprInfo.simple(rObject.type)
    override fun globalConstantRestriction() = V_GlobalConstantRestriction("object", null)
    override fun toRExpr0() = R_ObjectExpr(rObject.type)
}

class V_ObjectAttrExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val rObject: R_ObjectDefinition,
        private val attr: R_Attribute
): V_Expr(exprCtx, pos) {
    override fun exprInfo0() = V_ExprInfo.simple(attr.type)

    override fun globalConstantRestriction() = V_GlobalConstantRestriction("object_attr", null)

    override fun toRExpr0() = createAccessExpr()
    override fun toDbExpr0() = C_ExprUtils.toDbExpr(exprCtx.msgCtx, pos, toRExpr())

    override fun destination(): C_Destination {
        if (!attr.mutable) {
            throw C_Errors.errAttrNotMutable(pos, attr.name)
        }
        exprCtx.checkDbUpdateAllowed(pos)
        return C_Destination_ObjectAttr(rObject, attr)
    }

    private fun createAccessExpr(): R_Expr {
        val rEntity = rObject.rEntity
        val atEntity = exprCtx.makeAtEntity(rEntity, exprCtx.appCtx.nextAtExprId())
        val whatExpr = Db_AttrExpr(Db_EntityExpr(atEntity), attr)
        val whatValue = Db_AtWhatValue_DbExpr(whatExpr, whatExpr.type)
        val whatField = Db_AtWhatField(R_AtWhatFieldFlags.DEFAULT, whatValue)
        return createRExpr(rObject, atEntity, whatField, attr.type)
    }

    companion object {
        fun createRExpr(
                rObject: R_ObjectDefinition,
                atEntity: R_DbAtEntity,
                whatField: Db_AtWhatField,
                resType: R_Type
        ): R_Expr {
            val from = listOf(atEntity)
            val what = listOf(whatField)
            val atBase = Db_AtExprBase(from, what, null)
            return R_ObjectAttrExpr(resType, rObject, atBase)
        }
    }
}
