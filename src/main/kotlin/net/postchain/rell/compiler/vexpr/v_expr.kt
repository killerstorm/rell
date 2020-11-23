package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_TupleValue
import net.postchain.rell.runtime.Rt_Value

abstract class V_Expr(val pos: S_Pos) {
    abstract fun type(): R_Type
    protected abstract fun isDb(): Boolean
    protected abstract fun toRExpr0(): R_Expr
    protected open fun toDbExpr0(): Db_Expr = throw C_Errors.errExprDbNotAllowed(pos)

    protected fun isDb(vExpr: V_Expr) = vExpr.isDb()

    fun dependsOnAtVariable() = isDb() //TODO support for collection-at

    fun toRExpr(): R_Expr {
        val rExpr = toRExpr0()
        val filePos = pos.toFilePos()
        return R_StackTraceExpr(rExpr, filePos)
    }

    fun toDbExpr(): Db_Expr {
        if (isDb()) {
            return toDbExpr0()
        }
        val rExpr = toRExpr()
        return C_Utils.toDbExpr(pos, rExpr)
    }

    open fun constantValue(): Rt_Value? = null
    open fun varId(): C_VarUid? = null
    open fun varFacts(): C_ExprVarFacts = C_ExprVarFacts.EMPTY

    open fun asNullable(): V_Expr = this

    open fun destination(ctx: C_ExprContext): C_Destination {
        throw C_Errors.errBadDestination(pos)
    }

    open fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val baseType = type()
        val effectiveBaseType = if (baseType is R_NullableType) baseType.valueType else baseType

        val memberRef = C_MemberRef(pos, this, memberName, safe)
        val valueExpr = C_MemberResolver.valueForType(ctx, effectiveBaseType, memberRef)
        val fnExpr = C_MemberResolver.functionForType(effectiveBaseType, memberRef)

        if (valueExpr == null && fnExpr == null) {
            throw C_Errors.errUnknownMember(effectiveBaseType, memberName)
        }

        C_MemberResolver.checkNullAccess(baseType, memberName, safe)

        val expr = C_ValueFunctionExpr.create(memberName, valueExpr, fnExpr)
        return expr!!
    }

    open fun isAtExprItem(): Boolean = false
    open fun implicitWhatName(): String? = null
}

class V_IfExpr(
        pos: S_Pos,
        private val resType: R_Type,
        private val cond: V_Expr,
        private val trueExpr: V_Expr,
        private val falseExpr: V_Expr,
        private val varFacts: C_ExprVarFacts
): V_Expr(pos) {
    private val isDb = isDb(cond) || isDb(trueExpr) || isDb(falseExpr)

    override fun type() = resType
    override fun isDb() = isDb
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
        pos: S_Pos,
        private val type: R_TupleType,
        private val exprs: List<V_Expr>,
        private val varFacts: C_ExprVarFacts
): V_Expr(pos) {
    private val isDb = exprs.any { isDb(it) }

    override fun type() = type
    override fun isDb() = isDb
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
        private val expr: V_Expr,
        private val varFacts: C_ExprVarFacts
): V_Expr(expr.pos) {
    private val isDb = isDb(expr)

    override fun type() = R_TextType
    override fun isDb() = isDb
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val type = expr.type()
        val rExpr = expr.toRExpr()
        return C_Utils.createSysCallExpr(R_TextType, R_SysFn_Any.ToText, listOf(rExpr), pos, type.toTextFunction)
    }

    override fun toDbExpr0(): Db_Expr {
        val type = expr.type()
        val dbFn = getDbToStringFunction(type)
        if (dbFn == null) {
            val typeStr = type.toStrictString()
            throw C_Error(pos, "expr:to_text:nosql:$typeStr", "Value of type $typeStr cannot be converted to text in SQL")
        }

        val dbExpr = expr.toDbExpr()
        return Db_CallExpr(R_TextType, dbFn, listOf(dbExpr))
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
        private val expr: V_Expr,
        private val varFacts: C_ExprVarFacts
): V_Expr(expr.pos) {
    private val isDb = isDb(expr)

    override fun type() = R_DecimalType
    override fun isDb() = isDb
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

class V_AtEntityExpr(pos: S_Pos, private val rAtEntity: R_DbAtEntity): V_Expr(pos) {
    override fun type() = rAtEntity.rEntity.type
    override fun isDb() = true
    override fun varFacts() = C_ExprVarFacts.EMPTY

    override fun toRExpr0() = throw C_Errors.errExprDbNotAllowed(pos)
    override fun toDbExpr0() = Db_EntityExpr(rAtEntity)

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val attrRef = C_EntityAttrRef.resolveByName(rAtEntity.rEntity, memberName.str)
        attrRef ?: throw C_Errors.errUnknownMember(rAtEntity.rEntity.type, memberName)
        val vExpr = V_AtAttrExpr(pos, rAtEntity, attrRef)
        return C_VExpr(vExpr)
    }
}

class V_AtAttrExpr(pos: S_Pos, private val rAtEntity: R_DbAtEntity, private val attrRef: C_EntityAttrRef): V_Expr(pos) {
    override fun type() = attrRef.type()
    override fun isDb() = true
    override fun varFacts() = C_ExprVarFacts.EMPTY

    override fun toRExpr0() = throw C_Errors.errExprDbNotAllowed(pos)

    override fun toDbExpr0(): Db_Expr {
        val dbEntityExpr = Db_EntityExpr(rAtEntity)
        return attrRef.createDbContextAttrExpr(dbEntityExpr)
    }

    override fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val dbExpr = toDbExpr0()
        val vExpr = V_DbExpr.create(pos, dbExpr)
        return vExpr.member(ctx, memberName, safe)
    }

    override fun implicitWhatName() = attrRef.name
}

class V_AtPlaceholderExpr(pos: S_Pos, private val type: R_Type, private val varPtr: R_VarPtr): V_Expr(pos) {
    override fun type() = type
    override fun isDb() = false
    override fun varFacts() = C_ExprVarFacts.EMPTY
    override fun toRExpr0() = R_VarExpr(type, varPtr, C_Constants.AT_PLACEHOLDER)
    override fun isAtExprItem() = true
}

class V_SysGlobalCaseCallExpr(
        private val ctx: C_ExprContext,
        private val caseCtx: C_GlobalFuncCaseCtx,
        private val match: C_GlobalFuncCaseMatch,
        args: List<V_Expr>
): V_Expr(caseCtx.fullName.pos) {
    private val isDb = match.canBeDb && args.any { isDb(it) }

    override fun type() = match.resType
    override fun isDb() = isDb
    override fun varFacts() = match.varFacts()
    override fun toRExpr0() = match.compileCall(ctx, caseCtx)
    override fun toDbExpr0() = match.compileCallDb(ctx, caseCtx)
}

class V_SysMemberCaseCallExpr(
        private val ctx: C_ExprContext,
        private val caseCtx: C_MemberFuncCaseCtx,
        private val match: C_MemberFuncCaseMatch,
        args: List<V_Expr>
): V_Expr(caseCtx.fullName.pos) {
    private val isDb = isDb(caseCtx.member.base) || args.any { isDb(it) }
    private val resType = C_Utils.effectiveMemberType(match.resType, caseCtx.member.safe)
    private val varFacts = caseCtx.member.base.varFacts().and(match.varFacts())

    override fun type() = resType
    override fun isDb() = isDb
    override fun varFacts() = varFacts
    override fun toRExpr0() = match.compileCall(ctx, caseCtx)
    override fun toDbExpr0() = match.compileCallDb(ctx, caseCtx)
}

class V_SysMemberPropertyExpr(
        private val ctx: C_ExprContext,
        private val caseCtx: C_MemberFuncCaseCtx,
        private val prop: C_SysMemberFormalParamsFuncBody
): V_Expr(caseCtx.fullName.pos) {
    private val isDb = isDb(caseCtx.member.base)
    private val resType = C_Utils.effectiveMemberType(prop.resType, caseCtx.member.safe)
    private val varFacts = caseCtx.member.base.varFacts()

    override fun type() = resType
    override fun isDb() = isDb
    override fun varFacts() = varFacts
    override fun toRExpr0() = prop.compileCall(ctx, caseCtx, listOf())
    override fun toDbExpr0() = prop.compileCallDb(ctx, caseCtx, listOf())
}
