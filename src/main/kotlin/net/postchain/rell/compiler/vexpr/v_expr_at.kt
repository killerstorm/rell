package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.utils.immSetOf
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmSet

class V_AtEntityExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val cAtEntity: C_AtEntity,
        private val ambiguous: Boolean
): V_Expr(exprCtx, pos) {
    override val exprInfo = V_ExprInfo(true, false, immSetOf(cAtEntity.atExprId))

    override fun type() = cAtEntity.rEntity.type
    override fun varFacts() = C_ExprVarFacts.EMPTY

    override fun isAtExprItem() = true
    override fun implicitAtWhereAttrName() = cAtEntity.alias

    override fun toRExpr0() = throw C_Errors.errExprDbNotAllowed(pos)

    override fun toDbExpr0(): Db_Expr {
        val rAtEntity = cAtEntity.toRAtEntityValidated(exprCtx, pos, ambiguous)
        return Db_EntityExpr(rAtEntity)
    }

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val entity = cAtEntity.rEntity
        val entityType = entity.type

        val attrRef = C_EntityAttrRef.resolveByName(entity, memberName.str)
        val attrExpr = if (attrRef == null) null else C_VExpr(V_AtAttrExpr(ctx, pos, cAtEntity, ambiguous, attrRef))

        val memberRef = C_MemberRef(this, memberName, safe)
        val fnExpr = C_MemberResolver.functionForType(ctx, entityType, memberRef)

        val cExpr = C_ValueFunctionExpr.create(memberName, attrExpr, fnExpr)
        return cExpr ?: throw C_Errors.errUnknownMember(entityType, memberName)
    }
}

class V_AtAttrExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val cAtEntity: C_AtEntity,
        private val entityAmbiguous: Boolean,
        private val attrRef: C_EntityAttrRef
): V_Expr(exprCtx, pos) {
    override val exprInfo = V_ExprInfo(true, false, immSetOf(cAtEntity.atExprId))

    override fun type() = attrRef.type()
    override fun varFacts() = C_ExprVarFacts.EMPTY

    override fun implicitAtWhereAttrName() = attrRef.attrName
    override fun implicitAtWhatAttrName() = attrRef.attrName

    override fun toRExpr0() = throw C_Errors.errExprDbNotAllowed(pos)

    override fun toDbExpr0(): Db_Expr {
        val rAtEntity = cAtEntity.toRAtEntityValidated(exprCtx, pos, entityAmbiguous)
        val dbExpr = Db_EntityExpr(rAtEntity)
        return attrRef.createDbContextAttrExpr(dbExpr)
    }
}

class V_AtExprBase(
        from: List<R_DbAtEntity>,
        what: List<C_AtWhatField>,
        private val where: V_Expr?
) {
    private val from = from.toImmList()
    private val what = what.toImmList()

    private val refAtExprIds = (what.map { it.expr } + listOfNotNull(where)).flatMap { it.atDependencies() }.toImmSet()

    fun referencedAtExprIds(): Set<R_AtExprId> = refAtExprIds

    fun toDbBase(ctx: C_ExprContext): Db_AtExprBase {
        val dbWhat = what.map { it.compile(ctx) }
        val dbWhere = where?.toDbExpr()
        return Db_AtExprBase(from, dbWhat, dbWhere)
    }
}

class V_NestedAtExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val resultType: R_Type,
        private val vBase: V_AtExprBase,
        private val extras: R_AtExprExtras,
        private val rBlock: R_FrameBlock,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    override val exprInfo = V_ExprInfo(true, false, vBase.referencedAtExprIds())

    override fun type() = resultType
    override fun toRExpr0() = throw C_Errors.errExprDbNotAllowed(pos)

    override fun toDbExpr0(): Db_Expr {
        val dbBase = vBase.toDbBase(exprCtx)
        return Db_NestedAtExpr(resultType, dbBase, extras, rBlock)
    }

    override fun varFacts() = varFacts
}
