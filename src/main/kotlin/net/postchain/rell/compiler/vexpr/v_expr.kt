package net.postchain.rell.compiler.vexpr

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.ast.S_CallArgument
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_StructValue
import net.postchain.rell.runtime.Rt_TupleValue
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.*

class V_ExprInfo(
        val hasDbModifications: Boolean = false,
        val canBeDbExpr: Boolean = true,
        val dependsOnDbAtEntity: Boolean = false,
        dependsOnAtExprs: Set<R_AtExprId> = immSetOf()
) {
    val dependsOnAtExprs = dependsOnAtExprs.toImmSet()

    companion object {
        fun make(
                expr: V_Expr,
                hasDbModifications: Boolean = false,
                canBeDbExpr: Boolean = true,
                dependsOnDbAtEntity: Boolean = false
        ): V_ExprInfo {
            return make(
                    listOf(expr),
                    hasDbModifications = hasDbModifications,
                    canBeDbExpr = canBeDbExpr,
                    dependsOnDbAtEntity = dependsOnDbAtEntity
            )
        }

        fun make(
                exprs: List<V_Expr>,
                hasDbModifications: Boolean = false,
                canBeDbExpr: Boolean = true,
                dependsOnDbAtEntity: Boolean = false
        ): V_ExprInfo {
            val depsOnDbAtEnt = dependsOnDbAtEntity || exprs.any { it.dependsOnDbAtEntity() }
            val canBeDb = !depsOnDbAtEnt || (canBeDbExpr && exprs.all { it.canBeDbExpr() })
            return V_ExprInfo(
                    hasDbModifications = hasDbModifications || exprs.any { it.hasDbModifications() },
                    canBeDbExpr = canBeDb,
                    dependsOnDbAtEntity = depsOnDbAtEnt,
                    dependsOnAtExprs = exprs.flatMap { it.dependsOnAtExprs() }.toSet()
            )
        }
    }
}

abstract class V_Expr(protected val exprCtx: C_ExprContext, val pos: S_Pos) {
    protected val msgCtx = exprCtx.msgCtx

    protected abstract val exprInfo: V_ExprInfo

    abstract fun type(): R_Type
    protected abstract fun toRExpr0(): R_Expr
    protected open fun toDbExpr0(): Db_Expr = throw C_Errors.errExprDbNotAllowed(pos)

    fun hasDbModifications(): Boolean = exprInfo.hasDbModifications
    fun canBeDbExpr(): Boolean = exprInfo.canBeDbExpr
    fun dependsOnDbAtEntity(): Boolean = exprInfo.dependsOnDbAtEntity
    fun dependsOnAtExprs(): Set<R_AtExprId> = exprInfo.dependsOnAtExprs

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
        if (dependsOnDbAtEntity()) {
            return toDbExpr0()
        }
        val rExpr = toRExpr()
        return C_Utils.toDbExpr(pos, rExpr)
    }

    fun toDbExprWhat(): C_DbAtWhatValue {
        return if (canBeDbExpr() && type().sqlAdapter.isSqlCompatible()) {
            toDbExprWhatDirect()
        } else {
            toDbExprWhat0()
        }
    }

    protected open fun toDbExprWhat0(): C_DbAtWhatValue {
        return toDbExprWhatDirect()
    }

    private fun toDbExprWhatDirect(): C_DbAtWhatValue {
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
        val effectiveBaseType = C_Types.removeNullable(baseType)

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

    open fun call(ctx: C_ExprContext, pos: S_Pos, args: List<S_CallArgument>, resTypeHint: C_TypeHint): V_Expr {
        val type = type()
        return callCommon(ctx, pos, args, resTypeHint, type, false)
    }

    protected fun callCommon(
            ctx: C_ExprContext,
            pos: S_Pos,
            args: List<S_CallArgument>,
            resTypeHint: C_TypeHint,
            type: R_Type,
            safe: Boolean
    ): V_Expr {
        if (type is R_FunctionType) {
            val callInfo = C_FunctionCallInfo.forFunctionType(pos, type)
            val callTarget = C_FunctionCallTarget_FunctionType(ctx, callInfo, this, type, safe)
            return C_FunctionUtils.compileRegularCall(ctx, callInfo, callTarget, args, resTypeHint)
        }

        // Validate args.
        args.forEach {
            ctx.msgCtx.consumeError {
                it.value.compile(ctx, C_TypeHint.NONE)
            }
        }

        if (type == R_CtErrorType) {
            return C_Utils.errorVExpr(ctx, pos)
        } else {
            val typeStr = type.toStrictString()
            throw C_Error.stop(pos, "expr_call_nofn:$typeStr", "Not a function: value of type $typeStr")
        }
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
    override val exprInfo = V_ExprInfo.make(exprs, canBeDbExpr = false)

    override fun type() = type
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rExprs = exprs.map { it.toRExpr() }
        return R_TupleExpr(type, rExprs)
    }

    override fun toDbExprWhat0(): C_DbAtWhatValue {
        val evaluator = object: Db_ComplexAtWhatEvaluator() {
            override fun evaluate(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value {
                return Rt_TupleValue(type, values)
            }
        }
        return V_AtUtils.createAtWhatValueComplex(exprs, evaluator)
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
    private val dbFn = getDbToStringFunction(expr.type())

    override val exprInfo = V_ExprInfo.make(expr, canBeDbExpr = dbFn != null)

    override fun type() = R_TextType
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val type = expr.type()
        val rExpr = expr.toRExpr()
        return C_Utils.createSysCallExpr(R_TextType, R_SysFn_Any.ToText, listOf(rExpr), pos, type.toTextFunction)
    }

    override fun toDbExpr0(): Db_Expr {
        val type = expr.type()
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

class V_TypeAdapterExpr(
        exprCtx: C_ExprContext,
        private val resType: R_Type,
        private val expr: V_Expr,
        private val adapter: C_TypeAdapter
): V_Expr(exprCtx, expr.pos) {
    override val exprInfo = V_ExprInfo.make(expr)

    private val varFacts = C_ExprVarFacts.forSubExpressions(listOf(expr))

    override fun type() = resType
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rExpr = expr.toRExpr()
        return adapter.adaptExprR(rExpr)
    }

    override fun toDbExpr0(): Db_Expr {
        val dbExpr = expr.toDbExpr()
        return adapter.adaptExprDb(dbExpr)
    }

    override fun constantValue(): Rt_Value? {
        val value = expr.constantValue()
        value ?: return null
        val rAdapter = adapter.toRAdapter()
        return rAdapter.adaptValue(value)
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

        exprInfo = V_ExprInfo.make(explicitAttrs.map { it.expr }, canBeDbExpr = false)
    }

    override fun type() = struct.type
    override fun varFacts() = varFacts

    override fun toRExpr0(): R_Expr {
        val rAttrs = implicitAttrs + explicitAttrs.map { it.toRAttr() }
        return R_StructExpr(struct, rAttrs)
    }

    override fun toDbExprWhat0(): C_DbAtWhatValue {
        val (attrsDb, attrsR) = explicitAttrs.partition { it.expr.dependsOnDbAtEntity() }
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
