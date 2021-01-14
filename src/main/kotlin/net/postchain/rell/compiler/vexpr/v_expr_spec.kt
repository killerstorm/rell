package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Error
import net.postchain.rell.runtime.Rt_StructValue
import net.postchain.rell.runtime.Rt_Value

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

class V_ObjectExpr(private val name: List<S_Name>, private val rObject: R_ObjectDefinition): V_Expr(name[0].pos) {
    override fun type() = rObject.type
    override fun isDb() = false
    override fun toRExpr0() = R_ObjectExpr(rObject.type)
    override fun toDbExpr0(msgCtx: C_MessageContext) = C_Utils.toDbExpr(pos, toRExpr())

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val attr = rObject.rEntity.attributes[memberName.str]
        val attrExpr = if (attr == null) null else C_VExpr(V_ObjectAttrExpr(memberName.pos, rObject, attr))

        val memberRef = C_MemberRef(pos, this, memberName, safe)
        val fnExpr = C_MemberResolver.functionForType(rObject.type, memberRef)

        val cExpr = C_ValueFunctionExpr.create(memberName, attrExpr, fnExpr)
        return cExpr ?: throw C_Errors.errUnknownMember(rObject.type, memberName)
    }
}

private class V_ObjectAttrExpr(pos: S_Pos, private val rObject: R_ObjectDefinition, private val attr: R_Attribute): V_Expr(pos) {
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
        val whatExpr = Db_AttrExpr(Db_EntityExpr(atEntity), attr)
        val whatValue = R_DbAtWhatValue_Simple(whatExpr, whatExpr.type)
        val whatField = R_DbAtWhatField(R_AtWhatFieldFlags.DEFAULT, whatValue)
        return createRExpr(rObject, atEntity, whatField, attr.type)
    }

    companion object {
        fun createRExpr(
                rObject: R_ObjectDefinition,
                atEntity: R_DbAtEntity,
                whatField: R_DbAtWhatField,
                resType: R_Type
        ): R_Expr {
            val from = listOf(atEntity)
            val what = listOf(whatField)
            val atBase = R_DbAtExprBase(from, what, null)
            return R_ObjectAttrExpr(resType, rObject, atBase)
        }
    }
}

class V_EntityToStructExpr(
        private val memberRef: C_MemberRef,
        private val entityType: R_EntityType
): V_Expr(memberRef.base.pos) {
    private val isDb = isDb(memberRef.base)
    private val structType = entityType.rEntity.mirrorStruct.type
    private val resultType = C_Utils.effectiveMemberType(structType, memberRef.safe)

    override fun type() = resultType
    override fun isDb() = isDb

    override fun toRExpr0(): R_Expr {
        val atEntity = R_DbAtEntity(entityType.rEntity, 0)
        val whatValue = createWhatValue(Db_EntityExpr(atEntity))
        val whatField = R_DbAtWhatField(R_AtWhatFieldFlags.DEFAULT, whatValue)
        return V_EntityAttrExpr.createRExpr(memberRef.base, atEntity, whatField, memberRef.safe, structType)
    }

    override fun toDbExprWhat(exprCtx: C_ExprContext, field: C_AtWhatField): R_DbAtWhatValue {
        val flags = field.flags
        checkFlag(exprCtx, flags.sort?.pos, "sort", "sort")
        checkFlag(exprCtx, flags.group, "group", "group")
        checkFlag(exprCtx, flags.aggregate, "aggregate", "aggregate")

        val dbEntityExpr = memberRef.base.toDbExpr(exprCtx.msgCtx) as Db_TableExpr
        return createWhatValue(dbEntityExpr)
    }

    private fun checkFlag(exprCtx: C_ExprContext, flagPos: S_Pos?, code: String, msg: String) {
        if (flagPos != null) {
            exprCtx.msgCtx.error(flagPos, "to_struct:$code", "Cannot $msg to_struct()")
        }
    }

    private fun createWhatValue(dbEntityExpr: Db_TableExpr): R_DbAtWhatValue {
        val rEntity = entityType.rEntity
        val dbExprs = rEntity.attributes.map {
            C_EntityAttrRef.create(rEntity, it.value).createDbContextAttrExpr(dbEntityExpr)
        }
        return R_DbAtWhatValue_Complex(dbExprs, R_DbAtWhatCombiner_ToStruct(entityType.rEntity))
    }
}

class V_ObjectToStructExpr(pos: S_Pos, private val objectType: R_ObjectType): V_Expr(pos) {
    private val structType = objectType.rObject.rEntity.mirrorStruct.type
    private val resultType = structType

    override fun type() = resultType
    override fun isDb() = false

    override fun toRExpr0(): R_Expr {
        val atEntity = R_DbAtEntity(objectType.rObject.rEntity, 0)
        val whatValue = createWhatValue(Db_EntityExpr(atEntity))
        val whatField = R_DbAtWhatField(R_AtWhatFieldFlags.DEFAULT, whatValue)
        return V_ObjectAttrExpr.createRExpr(objectType.rObject, atEntity, whatField, structType)
    }

    private fun createWhatValue(dbEntityExpr: Db_TableExpr): R_DbAtWhatValue {
        val rEntity = objectType.rObject.rEntity
        val dbExprs = rEntity.attributes.map {
            C_EntityAttrRef.create(rEntity, it.value).createDbContextAttrExpr(dbEntityExpr)
        }
        return R_DbAtWhatValue_Complex(dbExprs, R_DbAtWhatCombiner_ToStruct(objectType.rObject.rEntity))
    }
}

private class R_DbAtWhatCombiner_ToStruct(private val rEntity: R_EntityDefinition): R_DbAtWhatCombiner() {
    override fun combine(values: List<Rt_Value>): Rt_Value {
        val struct = rEntity.mirrorStruct
        val attrs = struct.attributesList

        if (values.size != attrs.size) {
            throw Rt_Error("to_struct:values_size:${attrs.size}:${values.size}",
                    "Received wrong number of values: ${values.size} instead of ${attrs.size}")
        }

        val attrValues = values.toMutableList()
        return Rt_StructValue(struct.type, attrValues)
    }
}
