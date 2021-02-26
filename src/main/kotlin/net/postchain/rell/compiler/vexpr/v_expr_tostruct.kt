package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_Error
import net.postchain.rell.runtime.Rt_StructValue
import net.postchain.rell.runtime.Rt_Value

class V_EntityToStructExpr(
        exprCtx: C_ExprContext,
        private val memberLink: C_MemberLink,
        private val entityType: R_EntityType,
        mutable: Boolean
): V_Expr(exprCtx, memberLink.base.pos) {
    private val isDb = isDb(memberLink.base)
    private val atDependencies = memberLink.base.atDependencies()
    private val struct = entityType.rEntity.mirrorStructs.getStruct(mutable)
    private val structType = struct.type
    private val resultType = C_Utils.effectiveMemberType(structType, memberLink.safe)

    override fun type() = resultType
    override fun isDb() = isDb
    override fun atDependencies() = atDependencies

    override fun toRExpr0(): R_Expr {
        val atEntity = exprCtx.makeAtEntity(entityType.rEntity, exprCtx.appCtx.nextAtExprId())
        val whatValue = createWhatValue(Db_EntityExpr(atEntity))
        val whatField = Db_AtWhatField(R_AtWhatFieldFlags.DEFAULT, whatValue)
        return V_EntityAttrExpr.createRExpr(exprCtx, memberLink.base, atEntity, whatField, memberLink.safe, structType)
    }

    override fun toDbExprWhat(field: C_AtWhatField): Db_AtWhatValue {
        val flags = field.flags
        checkFlag(exprCtx, flags.sort?.pos, "sort", "sort")
        checkFlag(exprCtx, flags.group, "group", "group")
        checkFlag(exprCtx, flags.aggregate, "aggregate", "aggregate")

        val dbEntityExpr = memberLink.base.toDbExpr() as Db_TableExpr
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
