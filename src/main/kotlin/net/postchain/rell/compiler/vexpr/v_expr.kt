package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_StructValue
import net.postchain.rell.runtime.Rt_TupleValue
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.*

class V_ExprInfo(
        val isDb: Boolean,
        val isDbModification: Boolean,
        atDependencies: Set<R_AtExprId> = immSetOf()
) {
    val atDependencies = atDependencies.toImmSet()

    companion object {
        fun make(expr: V_Expr): V_ExprInfo {
            return make(listOf(expr))
        }

        fun make(exprs: List<V_Expr>): V_ExprInfo {
            val isDb = exprs.any { it.isDb() }
            val isDbMod = exprs.any { it.isDbModification() }
            val atDependencies = exprs.flatMap { it.atDependencies() }.toSet()
            return V_ExprInfo(isDb, isDbMod, atDependencies)
        }
    }
}

abstract class V_Expr(protected val exprCtx: C_ExprContext, val pos: S_Pos) {
    protected val msgCtx = exprCtx.msgCtx

    protected abstract val exprInfo: V_ExprInfo

    abstract fun type(): R_Type
    protected abstract fun toRExpr0(): R_Expr
    protected open fun toDbExpr0(): Db_Expr = throw C_Errors.errExprDbNotAllowed(pos)

    fun isDb(): Boolean = exprInfo.isDb
    fun isDbModification(): Boolean = exprInfo.isDbModification
    fun atDependencies(): Set<R_AtExprId> = exprInfo.atDependencies

    protected fun isDb(vExpr: V_Expr) = vExpr.isDb()

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

    open fun toDbExprWhat(): C_DbAtWhatValue {
        val dbExpr = toDbExpr()
        return C_DbAtWhatValue_Simple(dbExpr)
    }

    open fun constantValue(): Rt_Value? = null
    open fun varId(): C_VarUid? = null
    open fun varFacts(): C_ExprVarFacts = C_ExprVarFacts.EMPTY

    open fun asNullable(): V_Expr = this

    open fun destination(): C_Destination {
        throw C_Errors.errBadDestination(pos)
    }

    open fun member(ctx: C_ExprContext, memberName: S_Name, safe: Boolean): C_Expr {
        val memberRef = C_MemberRef(this, memberName, safe)

        val baseType = type()
        val effectiveBaseType = if (baseType is R_NullableType) baseType.valueType else baseType

        val valueExpr = C_MemberResolver.valueForType(ctx, effectiveBaseType, memberRef)
        val fnExpr = C_MemberResolver.functionForType(ctx, effectiveBaseType, memberRef)

        val expr = C_ValueFunctionExpr.create(memberName, valueExpr, fnExpr)
        if (expr == null) {
            C_Errors.errUnknownMember(ctx.msgCtx, effectiveBaseType, memberName)
            return C_Utils.errorExpr(ctx, pos)
        }

        C_MemberResolver.checkNullAccess(baseType, memberName, safe)
        return expr
    }
}

class V_ConstantExpr(exprCtx: C_ExprContext, pos: S_Pos, private val value: Rt_Value): V_Expr(exprCtx, pos) {
    override val exprInfo = V_ExprInfo.make(listOf())

    override fun type() = value.type()
    override fun toRExpr0() = R_ConstantExpr(value)
    override fun constantValue() = value
    override fun varFacts() = C_ExprVarFacts.EMPTY
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
    override val exprInfo: V_ExprInfo

    init {
        val exprs = immListOf(cond, trueExpr, falseExpr)
        exprInfo = V_ExprInfo.make(exprs)
    }

    override fun type() = resType
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
    override val exprInfo = V_ExprInfo.make(exprs)

    override fun type() = type
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rExprs = exprs.map { it.toRExpr() }
        return R_TupleExpr(type, rExprs)
    }

    override fun toDbExprWhat(): C_DbAtWhatValue {
        val evaluator = object: Db_ComplexAtWhatEvaluator() {
            override fun evaluate(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
                return Rt_TupleValue(type, values)
            }
        }
        return V_Utils.createAtWhatValueComplex(exprs, evaluator)
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
    override val exprInfo = V_ExprInfo.make(expr)

    override fun type() = R_TextType
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
    override val exprInfo = V_ExprInfo.make(expr)

    override fun type() = R_DecimalType
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

class V_SysGlobalCaseCallExpr(
        exprCtx: C_ExprContext,
        private val caseCtx: C_GlobalFuncCaseCtx,
        private val match: C_GlobalFuncCaseMatch,
        args: List<V_Expr>
): V_Expr(exprCtx, caseCtx.linkPos) {
    override val exprInfo = V_ExprInfo(
            isDb = match.canBeDb && args.any { isDb(it) },
            isDbModification = args.any { it.isDbModification() },
            atDependencies = args.flatMap { it.atDependencies() }.toImmSet()
    )

    override fun type() = match.resType
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
    private val resType = C_Utils.effectiveMemberType(match.resType, caseCtx.member.safe)
    private val varFacts = caseCtx.member.base.varFacts().and(match.varFacts())

    override val exprInfo: V_ExprInfo

    init {
        val exprs = listOf(caseCtx.member.base) + args
        exprInfo = V_ExprInfo.make(exprs)
    }

    override fun type() = resType
    override fun varFacts() = varFacts
    override fun toRExpr0() = match.compileCall(exprCtx, caseCtx)
    override fun toDbExpr0() = match.compileCallDb(exprCtx, caseCtx)
}

class V_SysMemberPropertyExpr(
        exprCtx: C_ExprContext,
        private val caseCtx: C_MemberFuncCaseCtx,
        private val prop: C_SysMemberFormalParamsFuncBody
): V_Expr(exprCtx, caseCtx.member.base.pos) {
    private val resType = C_Utils.effectiveMemberType(prop.resType, caseCtx.member.safe)
    private val varFacts = caseCtx.member.base.varFacts()

    override val exprInfo = V_ExprInfo.make(caseCtx.member.base)

    override fun type() = resType
    override fun varFacts() = varFacts
    override fun toRExpr0() = prop.compileCall(exprCtx, caseCtx, listOf())
    override fun toDbExpr0() = prop.compileCallDb(exprCtx, caseCtx, listOf())
    override fun implicitAtWhatAttrName() = if (caseCtx.member.base.isAtExprItem()) caseCtx.memberName else null
}

class V_UserFunctionCallExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val retType: R_Type,
        private val fn: R_RoutineDefinition,
        args: List<V_Expr>,
        private val filePos: R_FilePos,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    private val args = args.toImmList()

    override val exprInfo = V_ExprInfo.make(args)

    override fun type() = retType
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rArgs = args.map { it.toRExpr() }
        return R_UserCallExpr(retType, fn, rArgs, filePos)
    }

    override fun toDbExprWhat(): C_DbAtWhatValue {
        val evaluator = object: Db_ComplexAtWhatEvaluator() {
            override fun evaluate(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
                return fn.call(frame, values, filePos)
            }
        }
        return V_Utils.createAtWhatValueComplex(args, evaluator)
    }
}

class V_CreateExprAttr(val attr: R_Attribute, val expr: V_Expr) {
    fun toRAttr(): R_CreateExprAttr {
        val rExpr = expr.toRExpr()
        return R_CreateExprAttr(attr, rExpr)
    }
}

class V_StructExpr(
        exprCtx: C_ExprContext,
        pos: S_Pos,
        private val struct: R_Struct,
        explicitAttrs: List<V_CreateExprAttr>,
        implicitAttrs: List<R_CreateExprAttr>,
        private val varFacts: C_ExprVarFacts
): V_Expr(exprCtx, pos) {
    private val explicitAttrs = explicitAttrs.toImmList()
    private val implicitAttrs = implicitAttrs.toImmList()

    override val exprInfo: V_ExprInfo

    init {
        val impIdxs = implicitAttrs.map { it.attr.index }.toSet()
        val expIdxs = explicitAttrs.map { it.attr.index }.toSet()
        val dupIdxs = impIdxs.intersect(expIdxs)
        require(dupIdxs.isEmpty()) { dupIdxs }

        val allIdxs = impIdxs + expIdxs
        for (attr in struct.attributesList) {
            check(attr.index in allIdxs) { attr }
        }

        implicitAttrs.forEach {
            require(it.attr.hasExpr) { it.attr }
        }

        exprInfo = V_ExprInfo.make(explicitAttrs.map { it.expr })
    }

    override fun type() = struct.type
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rAttrs = implicitAttrs + explicitAttrs.map { it.toRAttr() }
        return R_StructExpr(struct, rAttrs)
    }

    override fun toDbExprWhat(): C_DbAtWhatValue {
        val (attrsDb, attrsR) = explicitAttrs.partition { isDb(it.expr) }
        val dbAttrs = attrsDb.map { it.attr }
        val rAttrs = implicitAttrs + attrsR.map { it.toRAttr() }

        val subWhatValues = attrsDb.map { it.expr.toDbExprWhat().toDbWhatSub() }
        val rExprs = rAttrs.map { it.expr }

        val dbItems = dbAttrs.mapIndexed { i, attr -> Triple(attr.index, i, true) }
        val rItems = rAttrs.mapIndexed { i, attr -> Triple(attr.attr.index, i, false) }
        val allItems = (dbItems + rItems).sortedBy { it.first }
        checkEquals(allItems.map { it.first }, struct.attributesList.map { it.index })
        val items = allItems.map { it.third to it.second }

        val evaluator = object: Db_ComplexAtWhatEvaluator() {
            override fun evaluate(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
                checkEquals(values.size, struct.attributesList.size)
                return Rt_StructValue(struct.type, values.toMutableList())
            }
        }

        val dbWhatValue = Db_AtWhatValue_Complex(subWhatValues, rExprs, items, evaluator)
        return C_DbAtWhatValue_Other(dbWhatValue)
    }
}

object V_Utils {
    fun createAtWhatValueComplex(vExprs: List<V_Expr>, evaluator: Db_ComplexAtWhatEvaluator): C_DbAtWhatValue {
        val items = mutableListOf<Pair<Boolean, Int>>()
        val dbExprs = mutableListOf<Db_AtWhatValue>()
        val rExprs = mutableListOf<R_Expr>()

        for (vExpr in vExprs) {
            if (vExpr.isDb()) {
                items.add(true to dbExprs.size)
                dbExprs.add(vExpr.toDbExprWhat().toDbWhatSub())
            } else {
                items.add(false to rExprs.size)
                rExprs.add(vExpr.toRExpr())
            }
        }

        val dbWhatValue = Db_AtWhatValue_Complex(dbExprs, rExprs, items, evaluator)
        return C_DbAtWhatValue_Other(dbWhatValue)
    }

    fun hasWhatModifiers(field: C_AtWhatField): Boolean {
        val flags = field.flags
        return flags.sort != null || flags.group != null || flags.aggregate != null
    }

    fun checkNoWhatModifiers(msgCtx: C_MessageContext, field: C_AtWhatField) {
        val flags = field.flags
        checkWhatFlag(msgCtx, flags.sort?.pos, "sort", "sort")
        checkWhatFlag(msgCtx, flags.group, "group", "group")
        checkWhatFlag(msgCtx, flags.aggregate, "aggregate", "aggregate")
    }

    private fun checkWhatFlag(msgCtx: C_MessageContext, flagPos: S_Pos?, code: String, msg: String) {
        if (flagPos != null) {
            msgCtx.error(flagPos, "expr:at:$code", "Cannot $msg this expression")
        }
    }
}
