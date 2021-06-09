package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*

class V_EntityToStructExpr(
        exprCtx: C_ExprContext,
        private val memberLink: C_MemberLink,
        private val entityType: R_EntityType,
        mutable: Boolean
): V_Expr(exprCtx, memberLink.base.pos) {
    private val struct = entityType.rEntity.mirrorStructs.getStruct(mutable)
    private val structType = struct.type
    private val resultType = C_Utils.effectiveMemberType(structType, memberLink.safe)
    private val cLambda = V_EntityAttrExpr.createLambda(exprCtx, entityType.rEntity)

    override val exprInfo = V_ExprInfo.make(memberLink.base)

    override fun type() = resultType

    override fun toRExpr0(): R_Expr {
        val atEntity = exprCtx.makeAtEntity(entityType.rEntity, exprCtx.appCtx.nextAtExprId())
        val cWhatValue = createWhatValue(Db_EntityExpr(atEntity))
        val dbWhatValue = cWhatValue.toDbWhatSub()
        val whatField = Db_AtWhatField(R_AtWhatFieldFlags.DEFAULT, dbWhatValue)
        return V_EntityAttrExpr.createRExpr(memberLink.base, atEntity, whatField, memberLink.safe, structType, cLambda)
    }

    override fun toDbExprWhat(): C_DbAtWhatValue {
        val dbEntityExpr = memberLink.base.toDbExpr() as Db_TableExpr
        return createWhatValue(dbEntityExpr)
    }

    private fun createWhatValue(dbEntityExpr: Db_TableExpr): C_DbAtWhatValue {
        val rEntity = entityType.rEntity
        val dbExprs = rEntity.attributes.map {
            C_EntityAttrRef.create(rEntity, it.value).createDbContextAttrExpr(dbEntityExpr)
        }
        val dbWhatValue = Db_AtWhatValue_ToStruct(struct, dbExprs)
        return C_DbAtWhatValue_Other(dbWhatValue)
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

    override val exprInfo = V_ExprInfo(false, false)

    override fun type() = structType

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
        return Db_AtWhatValue_ToStruct(struct, dbExprs)
    }
}
