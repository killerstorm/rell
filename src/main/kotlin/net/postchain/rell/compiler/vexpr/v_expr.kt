package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_TupleValue
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.immSetOf
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmSet

abstract class V_Expr(protected val exprCtx: C_ExprContext, val pos: S_Pos) {
    protected val msgCtx = exprCtx.msgCtx

    abstract fun type(): R_Type
    protected abstract fun isDb(): Boolean
    protected abstract fun toRExpr0(): R_Expr
    protected open fun toDbExpr0(): Db_Expr = throw C_Errors.errExprDbNotAllowed(pos)

    protected fun isDb(vExpr: V_Expr) = vExpr.isDb()

    open fun atDependencies(): Set<R_AtExprId> = immSetOf()
    open fun isAtExprItem(): Boolean = false
    open fun implicitAtWhereAttrName(): String? = null
    open fun implicitAtWhatAttrName(): String? = null

    fun toRExpr(): R_Expr {
        var rExpr = toRExpr0()
        val filePos = pos.toFilePos()
        rExpr = R_StackTraceExpr(rExpr, filePos)
        if (exprCtx.globalCtx.compilerOptions.blockCheck) {
            rExpr = R_BlockCheckExpr(rExpr, exprCtx.blkCtx.blockUid)
        }
        return rExpr
    }

    fun toDbExpr(): Db_Expr {
        if (isDb()) {
            return toDbExpr0()
        }
        val rExpr = toRExpr()
        return C_Utils.toDbExpr(pos, rExpr)
    }

    open fun toDbExprWhat(field: C_AtWhatField): Db_AtWhatValue {
        var dbExpr = toDbExpr()

        if (field.summarization != null) {
            dbExpr = field.summarization.compileDb(exprCtx.nsCtx, dbExpr)
        }

        return Db_AtWhatValue_Simple(dbExpr, field.resultType)
    }

    open fun constantValue(): Rt_Value? = null
    open fun varId(): C_VarUid? = null
    open fun varFacts(): C_ExprVarFacts = C_ExprVarFacts.EMPTY

    open fun asNullable(): V_Expr = this

    open fun destination(): C_Destination {
        throw C_Errors.errBadDestination(pos)
    }

    open fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val baseType = type()
        val effectiveBaseType = if (baseType is R_NullableType) baseType.valueType else baseType

        val memberRef = C_MemberRef(this, memberName, safe)
        val valueExpr = C_MemberResolver.valueForType(ctx, effectiveBaseType, memberRef)
        val fnExpr = C_MemberResolver.functionForType(ctx, effectiveBaseType, memberRef)

        val expr = C_ValueFunctionExpr.create(memberName, valueExpr, fnExpr)
        if (expr == null) {
            C_Errors.errUnknownMember(msgCtx, effectiveBaseType, memberName)
            return C_Utils.errorExpr(ctx, pos)
        }

        C_MemberResolver.checkNullAccess(baseType, memberName, safe)
        return expr
    }
}

class V_IfExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val resType: R_Type,
        private val cond: V_Expr,
        private val trueExpr: V_Expr,
        private val falseExpr: V_Expr,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    private val isDb: Boolean
    private val atDependencies: Set<R_AtExprId>

    init {
        val exprs = immListOf(cond, trueExpr, falseExpr)
        isDb = exprs.any { isDb(it) }
        atDependencies = exprs.flatMap { it.atDependencies() }.toImmSet()
    }

    override fun type() = resType
    override fun isDb() = isDb
    override fun atDependencies() = atDependencies
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rCond = cond.toRExpr()
        val rTrue = trueExpr.toRExpr()
        val rFalse = falseExpr.toRExpr()
        return R_IfExpr(resType, rCond, rTrue, rFalse)
    }

    override fun toDbExpr0(): Db_Expr {
        val dbCond = cond.toDbExpr()
        val dbTrue = trueExpr.toDbExpr()
        val dbFalse = falseExpr.toDbExpr()
        val cases = listOf(Db_WhenCase(listOf(dbCond), dbTrue))
        return Db_WhenExpr(resType, null, cases, dbFalse)
    }
}

class V_TupleExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val type: R_TupleType,
        private val exprs: List<V_Expr>,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    private val isDb = exprs.any { isDb(it) }
    private val atDependencies = exprs.flatMap { it.atDependencies() }.toImmSet()

    override fun type() = type
    override fun isDb() = isDb
    override fun atDependencies() = atDependencies
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rExprs = exprs.map { it.toRExpr() }
        return R_TupleExpr(type, rExprs)
    }

    override fun constantValue(): Rt_Value? {
        val values = exprs.map { it.constantValue() }.filterNotNull()
        if (values.size != exprs.size) return null
        return Rt_TupleValue(type, values)
    }
}

class V_ToTextExpr(
        exprCtx: C_ExprContext,
        private val expr: V_Expr,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, expr.pos) {
    private val isDb = isDb(expr)
    private val atDependencies = expr.atDependencies()

    override fun type() = R_TextType
    override fun isDb() = isDb
    override fun atDependencies() = atDependencies
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val type = expr.type()
        val rExpr = expr.toRExpr()
        return C_Utils.createSysCallExpr(R_TextType, R_SysFn_Any.ToText, listOf(rExpr), pos, type.toTextFunction)
    }

    override fun toDbExpr0(): Db_Expr {
        val type = expr.type()
        val dbFn = getDbToStringFunction(type)
        return if (dbFn == null) {
            val typeStr = type.toStrictString()
            msgCtx.error(pos, "expr:to_text:nosql:$typeStr", "Value of type $typeStr cannot be converted to text in SQL")
            C_Utils.errorDbExpr(R_TextType)
        } else {
            val dbExpr = expr.toDbExpr()
            Db_CallExpr(R_TextType, dbFn, listOf(dbExpr))
        }
    }

    private fun getDbToStringFunction(type: R_Type): Db_SysFunction? {
        return when (type) {
            R_BooleanType, R_IntegerType, R_RowidType, R_JsonType -> Db_SysFn_ToText
            R_DecimalType -> Db_SysFn_Decimal.ToText
            is R_EntityType -> Db_SysFn_ToText
            else -> null
        }
    }

    override fun constantValue(): Rt_Value? {
        val value = expr.constantValue()
        val res = if (value == null) null else R_SysFn_Any.ToText.call(value)
        return res
    }
}

class V_IntegerToDecimalExpr(
        exprCtx: C_ExprContext,
        private val expr: V_Expr,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, expr.pos) {
    private val isDb = isDb(expr)
    private val atDependencies = expr.atDependencies()

    override fun type() = R_DecimalType
    override fun isDb() = isDb
    override fun atDependencies() = atDependencies
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rExpr = expr.toRExpr()
        return C_Utils.createSysCallExpr(R_DecimalType, R_SysFn_Decimal.FromInteger, listOf(rExpr), expr.pos, "decimal")
    }

    override fun toDbExpr0(): Db_Expr {
        val dbExpr = expr.toDbExpr()
        return Db_CallExpr(R_DecimalType, Db_SysFn_Decimal.FromInteger, listOf(dbExpr))
    }

    override fun constantValue(): Rt_Value? {
        val value = expr.constantValue()
        val res = if (value == null) null else R_SysFn_Decimal.FromInteger.call(value)
        return res
    }
}

class V_AtEntityExpr(exprCtx: C_ExprContext, pos: S_Pos, private val cAtEntity: C_AtEntity): V_Expr(exprCtx, pos) {
    private val atDependencies = immSetOf(cAtEntity.atExprId)

    override fun type() = cAtEntity.rEntity.type
    override fun isDb() = true
    override fun varFacts() = C_ExprVarFacts.EMPTY

    override fun atDependencies() = atDependencies
    override fun isAtExprItem() = true
    override fun implicitAtWhereAttrName() = cAtEntity.alias

    override fun toRExpr0() = throw C_Errors.errExprDbNotAllowed(pos)

    override fun toDbExpr0(): Db_Expr {
        val rAtEntity = cAtEntity.toRAtEntityValidated(exprCtx, pos)
        return Db_EntityExpr(rAtEntity)
    }

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val entity = cAtEntity.rEntity
        val entityType = entity.type

        val attrRef = C_EntityAttrRef.resolveByName(entity, memberName.str)
        val attrExpr = if (attrRef == null) null else C_VExpr(V_AtAttrExpr(ctx, pos, cAtEntity, attrRef))

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
        private val attrRef: C_EntityAttrRef
): V_Expr(exprCtx, pos) {
    private val atDependencies = immSetOf(cAtEntity.atExprId)

    override fun type() = attrRef.type()
    override fun isDb() = true
    override fun atDependencies() = atDependencies
    override fun varFacts() = C_ExprVarFacts.EMPTY

    override fun implicitAtWhereAttrName() = attrRef.attrName
    override fun implicitAtWhatAttrName() = attrRef.attrName

    override fun toRExpr0() = throw C_Errors.errExprDbNotAllowed(pos)

    override fun toDbExpr0(): Db_Expr {
        val rAtEntity = cAtEntity.toRAtEntityValidated(exprCtx, pos)
        val dbEntityExpr = Db_EntityExpr(rAtEntity)
        return attrRef.createDbContextAttrExpr(dbEntityExpr)
    }

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val dbExpr = toDbExpr0()
        val vExpr = V_DbExpr.create(ctx, pos, dbExpr)
        return vExpr.member(ctx, memberName, safe)
    }
}

class V_SysGlobalCaseCallExpr(
        exprCtx: C_ExprContext,
        private val caseCtx: C_GlobalFuncCaseCtx,
        private val match: C_GlobalFuncCaseMatch,
        args: List<V_Expr>
): V_Expr(exprCtx, caseCtx.linkPos) {
    private val isDb = match.canBeDb && args.any { isDb(it) }
    private val atDependencies = args.flatMap { it.atDependencies() }.toImmSet()

    override fun type() = match.resType
    override fun isDb() = isDb
    override fun atDependencies() = atDependencies
    override fun varFacts() = match.varFacts()
    override fun toRExpr0() = match.compileCall(exprCtx, caseCtx)
    override fun toDbExpr0() = match.compileCallDb(exprCtx, caseCtx)
}

class V_SysMemberCaseCallExpr(
        exprCtx: C_ExprContext,
        private val caseCtx: C_MemberFuncCaseCtx,
        private val match: C_MemberFuncCaseMatch,
        args: List<V_Expr>
): V_Expr(exprCtx, caseCtx.member.base.pos) {
    private val isDb: Boolean
    private val atDependencies: Set<R_AtExprId>
    private val resType = C_Utils.effectiveMemberType(match.resType, caseCtx.member.safe)
    private val varFacts = caseCtx.member.base.varFacts().and(match.varFacts())

    init {
        val exprs = listOf(caseCtx.member.base) + args
        isDb = exprs.any { isDb(it) }
        atDependencies = exprs.flatMap { it.atDependencies() }.toImmSet()
    }

    override fun type() = resType
    override fun isDb() = isDb
    override fun atDependencies() = atDependencies
    override fun varFacts() = varFacts
    override fun toRExpr0() = match.compileCall(exprCtx, caseCtx)
    override fun toDbExpr0() = match.compileCallDb(exprCtx, caseCtx)
}

class V_SysMemberPropertyExpr(
        exprCtx: C_ExprContext,
        private val caseCtx: C_MemberFuncCaseCtx,
        private val prop: C_SysMemberFormalParamsFuncBody
): V_Expr(exprCtx, caseCtx.member.base.pos) {
    private val isDb = isDb(caseCtx.member.base)
    private val atDependencies = caseCtx.member.base.atDependencies()
    private val resType = C_Utils.effectiveMemberType(prop.resType, caseCtx.member.safe)
    private val varFacts = caseCtx.member.base.varFacts()

    override fun type() = resType
    override fun isDb() = isDb
    override fun atDependencies() = atDependencies
    override fun varFacts() = varFacts
    override fun toRExpr0() = prop.compileCall(exprCtx, caseCtx, listOf())
    override fun toDbExpr0() = prop.compileCallDb(exprCtx, caseCtx, listOf())
    override fun implicitAtWhatAttrName() = if (caseCtx.member.base.isAtExprItem()) caseCtx.memberName else null
}

class V_ListLiteralExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        elems: List<V_Expr>,
        private val listType: R_ListType,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    val elems = elems.toImmList()

    private val isDb = elems.any { isDb(it) }
    private val atDependencies = elems.flatMap { it.atDependencies() }.toImmSet()

    override fun type() = listType
    override fun isDb() = isDb
    override fun atDependencies() = atDependencies
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rExprs = elems.map { it.toRExpr() }
        return R_ListLiteralExpr(listType, rExprs)
    }
}
