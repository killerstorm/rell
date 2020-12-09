package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*

class V_RExpr(
        pos: S_Pos,
        private val rExpr: R_Expr,
        private val exprVarFacts: C_ExprVarFacts = C_ExprVarFacts.EMPTY
): V_Expr(pos) {
    override fun type() = rExpr.type
    override fun isDb() = false
    override fun toRExpr0() = rExpr
    override fun toDbExpr0(msgCtx: C_MessageContext) = C_Utils.toDbExpr(pos, rExpr)
    override fun constantValue() = rExpr.constantValue()
    override fun varFacts() = exprVarFacts

    companion object {
        fun makeExpr(pos: S_Pos, rExpr: R_Expr, varFacts: C_ExprVarFacts = C_ExprVarFacts.EMPTY): C_Expr {
            val vExpr = V_RExpr(pos, rExpr, varFacts)
            return C_VExpr(vExpr)
        }
    }
}

class V_DbExpr private constructor(pos: S_Pos, private val dbExpr: Db_Expr, private val varFacts: C_ExprVarFacts): V_Expr(pos) {
    override fun type() = dbExpr.type
    override fun isDb() = true
    override fun toRExpr0() = throw C_Errors.errExprDbNotAllowed(pos)
    override fun toDbExpr0(msgCtx: C_MessageContext) = dbExpr
    override fun constantValue() = dbExpr.constantValue()
    override fun varFacts() = varFacts

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        if (dbExpr is Db_TableExpr) {
            val attrRef = C_EntityAttrRef.resolveByName(dbExpr.rEntity, memberName.str)
            attrRef ?: throw C_Errors.errUnknownMember(dbExpr.type, memberName)
            return attrRef.createDbMemberExpr(dbExpr, pos, memberName)
        }
        return super.member(ctx, memberName, safe)
    }

    companion object {
        fun create(pos: S_Pos, dbExpr: Db_Expr, varFacts: C_ExprVarFacts = C_ExprVarFacts.EMPTY): V_Expr {
            return if (dbExpr is Db_InterpretedExpr) V_RExpr(pos, dbExpr.expr, varFacts) else V_DbExpr(pos, dbExpr, varFacts)
        }

        fun createExpr(pos: S_Pos, dbExpr: Db_Expr, varFacts: C_ExprVarFacts = C_ExprVarFacts.EMPTY): C_Expr {
            val vExpr = create(pos, dbExpr, varFacts)
            return C_VExpr(vExpr)
        }
    }
}

class V_LocalVarExpr(
        private val ctx: C_ExprContext,
        private val name: S_Name,
        private val localVar: C_LocalVar,
        private val nulled: C_VarFact,
        private val smartType: R_Type?
): V_Expr(name.pos) {
    override fun type() = smartType ?: localVar.type
    override fun isDb() = false
    override fun toDbExpr0(msgCtx: C_MessageContext) = C_Utils.toDbExpr(pos, toRExpr())
    override fun varId() = localVar.uid

    override fun toRExpr0(): R_Expr {
        checkInitialized()
        var rExpr: R_Expr = localVar.toExpr()
        if (smartType != null) {
            rExpr = R_NotNullExpr(smartType, rExpr)
        }
        return rExpr
    }

    override fun asNullable(): V_Expr {
        if (localVar.type !is R_NullableType || nulled == C_VarFact.MAYBE) {
            return this
        }

        val (freq, msg) = if (nulled == C_VarFact.YES) Pair("always", "is always") else Pair("never", "cannot be")
        ctx.msgCtx.warning(name.pos, "expr_var_null:$freq:${name.str}", "Variable '${name.str}' $msg null at this location")

        return V_LocalVarExpr(ctx, name, localVar, nulled, null)
    }

    override fun destination(ctx: C_ExprContext): C_Destination {
        check(ctx === this.ctx)
        if (!localVar.mutable) {
            if (ctx.factsCtx.inited(localVar.uid) != C_VarFact.NO) {
                throw C_Error.stop(name.pos, "expr_assign_val:${name.str}", "Value of '${name.str}' cannot be changed")
            }
        }
        val effectiveType = smartType ?: localVar.type
        return C_LocalVarDestination(effectiveType)
    }

    private fun checkInitialized() {
        if (ctx.factsCtx.inited(localVar.uid) != C_VarFact.YES) {
            val nameStr = name.str
            throw C_Error.stop(pos, "expr_var_uninit:$nameStr", "Variable '$nameStr' may be uninitialized")
        }
    }

    private inner class C_LocalVarDestination(private val effectiveType: R_Type): C_Destination() {
        override fun type() = localVar.type
        override fun effectiveType() = effectiveType

        override fun compileAssignStatement(srcExpr: R_Expr, op: C_AssignOp?): R_Statement {
            if (op != null) {
                checkInitialized()
            }
            val rDstExpr = localVar.toExpr()
            return R_AssignStatement(rDstExpr, srcExpr, op?.rOp)
        }

        override fun compileAssignExpr(startPos: S_Pos, resType: R_Type, srcExpr: R_Expr, op: C_AssignOp, post: Boolean): R_Expr {
            checkInitialized()
            val rDstExpr = localVar.toExpr()
            return R_AssignExpr(resType, op.rOp, rDstExpr, srcExpr, post)
        }
    }
}

class V_ObjectExpr(private val name: List<S_Name>, private val rObject: R_Object): V_Expr(name[0].pos) {
    override fun type() = rObject.type
    override fun isDb() = false
    override fun toRExpr0() = R_ObjectExpr(rObject.type)
    override fun toDbExpr0(msgCtx: C_MessageContext) = C_Utils.toDbExpr(pos, toRExpr())

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val attr = rObject.rEntity.attributes[memberName.str]
        attr ?: throw C_Errors.errUnknownName(name, memberName)
        val vExpr = V_ObjectAttrExpr(memberName.pos, rObject, attr)
        return C_VExpr(vExpr)
    }
}

private class V_ObjectAttrExpr(pos: S_Pos, private val rObject: R_Object, private val attr: R_Attrib): V_Expr(pos) {
    override fun type() = attr.type
    override fun isDb() = false
    override fun toRExpr0() = createAccessExpr()
    override fun toDbExpr0(msgCtx: C_MessageContext) = C_Utils.toDbExpr(pos, toRExpr())

    override fun destination(ctx: C_ExprContext): C_Destination {
        if (!attr.mutable) {
            throw C_Errors.errAttrNotMutable(pos, attr.name)
        }
        ctx.checkDbUpdateAllowed(pos)
        return C_ObjectAttrDestination(ctx.msgCtx, rObject, attr)
    }

    private fun createAccessExpr(): R_Expr {
        val rEntity = rObject.rEntity
        val atEntity = R_DbAtEntity(rEntity, 0)
        val from = listOf(atEntity)

        val whatExpr = Db_AttrExpr(Db_EntityExpr(atEntity), attr)
        val whatField = R_DbAtWhatField(whatExpr.type, whatExpr, R_AtWhatFieldFlags.DEFAULT)
        val what = listOf(whatField)

        val atBase = R_DbAtExprBase(from, what, null)
        return R_ObjectAttrExpr(attr.type, rObject, atBase)
    }
}
