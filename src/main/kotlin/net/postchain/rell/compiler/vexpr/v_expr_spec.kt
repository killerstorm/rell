package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Error
import net.postchain.rell.runtime.Rt_StructValue
import net.postchain.rell.runtime.Rt_Value

class V_RExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val rExpr: R_Expr,
        private val exprVarFacts: C_ExprVarFacts = C_ExprVarFacts.EMPTY
): V_Expr(exprCtx, pos) {
    override fun type() = rExpr.type
    override fun isDb() = false
    override fun toRExpr0() = rExpr
    override fun toDbExpr0() = C_Utils.toDbExpr(pos, rExpr)
    override fun constantValue() = rExpr.constantValue()
    override fun varFacts() = exprVarFacts

    companion object {
        fun makeExpr(exprCtx: C_ExprContext, pos: S_Pos, rExpr: R_Expr, varFacts: C_ExprVarFacts = C_ExprVarFacts.EMPTY): C_Expr {
            val vExpr = V_RExpr(exprCtx, pos, rExpr, varFacts)
            return C_VExpr(vExpr)
        }
    }
}

class V_DbExpr private constructor(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val dbExpr: Db_Expr,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    override fun type() = dbExpr.type
    override fun isDb() = true
    override fun toRExpr0() = throw C_Errors.errExprDbNotAllowed(pos)
    override fun toDbExpr0() = dbExpr
    override fun constantValue() = dbExpr.constantValue()
    override fun varFacts() = varFacts

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        if (dbExpr is Db_TableExpr) {
            val attrRef = C_EntityAttrRef.resolveByName(dbExpr.rEntity, memberName.str)
            attrRef ?: throw C_Errors.errUnknownMember(dbExpr.type, memberName)
            return attrRef.createDbMemberExpr(ctx, dbExpr, pos, memberName)
        }
        return super.member(ctx, memberName, safe)
    }

    companion object {
        fun create(exprCtx: C_ExprContext, pos: S_Pos, dbExpr: Db_Expr, varFacts: C_ExprVarFacts = C_ExprVarFacts.EMPTY): V_Expr {
            return if (dbExpr is Db_InterpretedExpr) {
                V_RExpr(exprCtx, pos, dbExpr.expr, varFacts)
            } else {
                V_DbExpr(exprCtx, pos, dbExpr, varFacts)
            }
        }

        fun createExpr(exprCtx: C_ExprContext, pos: S_Pos, dbExpr: Db_Expr, varFacts: C_ExprVarFacts = C_ExprVarFacts.EMPTY): C_Expr {
            val vExpr = create(exprCtx, pos, dbExpr, varFacts)
            return C_VExpr(vExpr)
        }
    }
}

class V_LocalVarExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val varRef: C_LocalVarRef,
        private val nulled: C_VarFact,
        private val smartType: R_Type?
): V_Expr(exprCtx, pos) {
    override fun type() = smartType ?: varRef.target.type
    override fun isDb() = false
    override fun toDbExpr0() = C_Utils.toDbExpr(pos, toRExpr())
    override fun varId() = varRef.target.uid

    override fun toRExpr0(): R_Expr {
        checkInitialized()
        var rExpr: R_Expr = varRef.toRExpr()
        if (smartType != null) {
            rExpr = R_NotNullExpr(smartType, rExpr)
        }
        return rExpr
    }

    override fun asNullable(): V_Expr {
        if (varRef.target.type !is R_NullableType || nulled == C_VarFact.MAYBE) {
            return this
        }

        val (freq, msg) = if (nulled == C_VarFact.YES) Pair("always", "is always") else Pair("never", "cannot be")
        val name = varRef.target.name
        msgCtx.warning(pos, "expr_var_null:$freq:$name", "Variable '$name' $msg null at this location")

        return V_LocalVarExpr(exprCtx, pos, varRef, nulled, null)
    }

    override fun destination(): C_Destination {
        if (!varRef.target.mutable) {
            if (exprCtx.factsCtx.inited(varRef.target.uid) != C_VarFact.NO) {
                val name = varRef.target.name
                throw C_Error.stop(pos, "expr_assign_val:$name", "Value of '$name' cannot be changed")
            }
        }
        val effectiveType = smartType ?: varRef.target.type
        return C_LocalVarDestination(effectiveType)
    }

    override fun isAtExprItem() = varRef.target.atItem

    private fun checkInitialized() {
        if (exprCtx.factsCtx.inited(varRef.target.uid) != C_VarFact.YES) {
            val name = varRef.target.name
            throw C_Error.stop(pos, "expr_var_uninit:$name", "Variable '$name' may be uninitialized")
        }
    }

    private inner class C_LocalVarDestination(private val effectiveType: R_Type): C_Destination() {
        override fun type() = varRef.target.type
        override fun effectiveType() = effectiveType

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

class V_ObjectExpr(
        exprCtx: C_ExprContext,
        name: List<S_Name>,
        private val rObject: R_ObjectDefinition
): V_Expr(exprCtx, name[0].pos) {
    override fun type() = rObject.type
    override fun isDb() = false
    override fun toRExpr0() = R_ObjectExpr(rObject.type)
    override fun toDbExpr0() = C_Utils.toDbExpr(pos, toRExpr())

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val attr = rObject.rEntity.attributes[memberName.str]
        val attrExpr = if (attr == null) null else C_VExpr(V_ObjectAttrExpr(ctx, memberName.pos, rObject, attr))

        val memberRef = C_MemberRef(pos, this, memberName, safe)
        val fnExpr = C_MemberResolver.functionForType(rObject.type, memberRef)

        val cExpr = C_ValueFunctionExpr.create(memberName, attrExpr, fnExpr)
        return cExpr ?: throw C_Errors.errUnknownMember(rObject.type, memberName)
    }
}

private class V_ObjectAttrExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val rObject: R_ObjectDefinition,
        private val attr: R_Attribute
): V_Expr(exprCtx, pos) {
    override fun type() = attr.type
    override fun isDb() = false
    override fun toRExpr0() = createAccessExpr()
    override fun toDbExpr0() = C_Utils.toDbExpr(pos, toRExpr())

    override fun destination(): C_Destination {
        if (!attr.mutable) {
            throw C_Errors.errAttrNotMutable(pos, attr.name)
        }
        exprCtx.checkDbUpdateAllowed(pos)
        return C_ObjectAttrDestination(rObject, attr)
    }

    private fun createAccessExpr(): R_Expr {
        val rEntity = rObject.rEntity
        val atEntity = exprCtx.makeAtEntity(rEntity, exprCtx.appCtx.nextAtExprId())
        val whatExpr = Db_AttrExpr(Db_EntityExpr(atEntity), attr)
        val whatValue = Db_AtWhatValue_Simple(whatExpr, whatExpr.type)
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

class V_EntityToStructExpr(
        exprCtx: C_ExprContext,
        private val memberRef: C_MemberRef,
        private val entityType: R_EntityType,
        mutable: Boolean
): V_Expr(exprCtx, memberRef.base.pos) {
    private val isDb = isDb(memberRef.base)
    private val struct = entityType.rEntity.mirrorStructs.getStruct(mutable)
    private val structType = struct.type
    private val resultType = C_Utils.effectiveMemberType(structType, memberRef.safe)

    override fun type() = resultType
    override fun isDb() = isDb

    override fun toRExpr0(): R_Expr {
        val atEntity = exprCtx.makeAtEntity(entityType.rEntity, exprCtx.appCtx.nextAtExprId())
        val whatValue = createWhatValue(Db_EntityExpr(atEntity))
        val whatField = Db_AtWhatField(R_AtWhatFieldFlags.DEFAULT, whatValue)
        return V_EntityAttrExpr.createRExpr(exprCtx, memberRef.base, atEntity, whatField, memberRef.safe, structType)
    }

    override fun toDbExprWhat(field: C_AtWhatField): Db_AtWhatValue {
        val flags = field.flags
        checkFlag(exprCtx, flags.sort?.pos, "sort", "sort")
        checkFlag(exprCtx, flags.group, "group", "group")
        checkFlag(exprCtx, flags.aggregate, "aggregate", "aggregate")

        val dbEntityExpr = memberRef.base.toDbExpr() as Db_TableExpr
        return createWhatValue(dbEntityExpr)
    }

    private fun checkFlag(exprCtx: C_ExprContext, flagPos: S_Pos?, code: String, msg: String) {
        if (flagPos != null) {
            exprCtx.msgCtx.error(flagPos, "to_struct:$code", "Cannot $msg to_struct()")
        }
    }

    private fun createWhatValue(dbEntityExpr: Db_TableExpr): Db_AtWhatValue {
        val rEntity = entityType.rEntity
        val dbExprs = rEntity.attributes.map {
            C_EntityAttrRef.create(rEntity, it.value).createDbContextAttrExpr(dbEntityExpr)
        }
        return Db_AtWhatValue_Complex(dbExprs, R_DbAtWhatCombiner_ToStruct(struct))
    }
}

class V_ObjectToStructExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val objectType: R_ObjectType,
        mutable: Boolean
): V_Expr(exprCtx, pos) {
    private val struct = objectType.rObject.rEntity.mirrorStructs.getStruct(mutable)
    private val structType = struct.type

    override fun type() = structType
    override fun isDb() = false

    override fun toRExpr0(): R_Expr {
        val atEntity = exprCtx.makeAtEntity(objectType.rObject.rEntity, exprCtx.appCtx.nextAtExprId())
        val whatValue = createWhatValue(Db_EntityExpr(atEntity))
        val whatField = Db_AtWhatField(R_AtWhatFieldFlags.DEFAULT, whatValue)
        return V_ObjectAttrExpr.createRExpr(objectType.rObject, atEntity, whatField, structType)
    }

    private fun createWhatValue(dbEntityExpr: Db_TableExpr): Db_AtWhatValue {
        val rEntity = objectType.rObject.rEntity
        val dbExprs = rEntity.attributes.map {
            C_EntityAttrRef.create(rEntity, it.value).createDbContextAttrExpr(dbEntityExpr)
        }
        return Db_AtWhatValue_Complex(dbExprs, R_DbAtWhatCombiner_ToStruct(struct))
    }
}

private class R_DbAtWhatCombiner_ToStruct(private val rStruct: R_Struct): R_DbAtWhatCombiner() {
    override fun combine(values: List<Rt_Value>): Rt_Value {
        val attrs = rStruct.attributesList

        if (values.size != attrs.size) {
            throw Rt_Error("to_struct:values_size:${attrs.size}:${values.size}",
                    "Received wrong number of values: ${values.size} instead of ${attrs.size}")
        }

        val attrValues = values.toMutableList()
        return Rt_StructValue(rStruct.type, attrValues)
    }
}
