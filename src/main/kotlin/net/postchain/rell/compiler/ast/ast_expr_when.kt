package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_BooleanValue
import net.postchain.rell.runtime.Rt_EnumValue
import net.postchain.rell.runtime.Rt_NullValue
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap

class C_WhenChooserDetailsBuilder(val keyExpr: V_Expr?, val keyPostFacts: C_VarFacts, val bodyExprCtx: C_ExprContext) {
    val constantCases = mutableMapOf<Rt_Value, Int>()
    val variableCases = mutableListOf<IndexedValue<V_Expr>>()
    var elseCase: IndexedValue<S_Pos>? = null
    var fullCoverage = false
    val caseFacts = mutableMapOf<Int, C_VarFacts>()
    val elseFacts = C_MutableVarFacts()
}

class C_WhenChooserDetails(b: C_WhenChooserDetailsBuilder) {
    val keyExpr = b.keyExpr
    val keyPostFacts = b.keyPostFacts
    val bodyExprCtx = b.bodyExprCtx
    val constantCases = b.constantCases.toImmMap()
    val variableCases = b.variableCases.toImmList()
    val elseCase = b.elseCase
    val full = b.fullCoverage
    val caseFacts = (0 until b.caseFacts.size).map { b.caseFacts.getValue(it) }.toImmList()
    val elseFacts = b.elseFacts.toVarFacts()

    fun toVDetails() = V_WhenChooserDetails(
            keyExpr = keyExpr,
            keyPostFacts = keyPostFacts,
            constantCases = constantCases,
            variableCases = variableCases,
            elseCase = elseCase,
            full = full,
            caseFacts = caseFacts,
            elseFacts = elseFacts
    )
}

class C_WhenChooser(details: C_WhenChooserDetails) {
    val bodyExprCtx = details.bodyExprCtx
    val keyPostFacts = details.keyPostFacts
    val full = details.full
    val caseFacts = details.caseFacts
    val elseFacts = details.elseFacts

    val rChooser = let {
        val vDetails = details.toVDetails()
        vDetails.makeChooser()
    }
}

sealed class S_WhenCondition {
    abstract fun compileBad(ctx: C_ExprContext)

    abstract fun compile(
            ctx: C_ExprContext,
            builder: C_WhenChooserDetailsBuilder,
            keyVarUid: C_VarUid?,
            keyType: R_Type?,
            idx: Int,
            last: Boolean
    )
}

class S_WhenConditionExpr(val exprs: List<S_Expr>): S_WhenCondition() {
    override fun compileBad(ctx: C_ExprContext) {
        for (expr in exprs) {
            expr.compileOpt(ctx)
        }
    }

    override fun compile(
            ctx: C_ExprContext,
            builder: C_WhenChooserDetailsBuilder,
            keyVarUid: C_VarUid?,
            keyType: R_Type?,
            idx: Int,
            last: Boolean
    ) {
        var caseFacts = C_VarFacts.EMPTY

        for (expr in exprs) {
            val elseFacts = builder.elseFacts.toVarFacts()
            val exprCtx = ctx.updateFacts(elseFacts)
            val vExpr = compileExpr(exprCtx, keyType, expr)

            builder.variableCases.add(IndexedValue(idx, vExpr))

            val valueFacts = getVarFacts(keyVarUid, keyType, vExpr)
            builder.elseFacts.andFacts(valueFacts.falseFacts)

            if (exprs.size == 1) {
                caseFacts = elseFacts.and(valueFacts.trueFacts)
            }

            val value = evaluateConstantValue(vExpr)
            if (value != null) {
                if (value in builder.constantCases) {
                    ctx.msgCtx.error(expr.startPos, "when_expr_dupvalue:$value", "Value already used")
                }
                builder.constantCases[value] = idx
            }
        }

        builder.caseFacts[idx] = caseFacts
    }

    private fun evaluateConstantValue(vExpr: V_Expr): Rt_Value? {
        return C_Utils.evaluate(vExpr.pos) {
            vExpr.constantValue(V_ConstantValueEvalContext())
        }
    }

    private fun getVarFacts(keyVarUid: C_VarUid?, keyType: R_Type?, vExpr: V_Expr): C_ExprVarFacts {
        if (keyType == null) {
            return vExpr.varFacts
        }

        val type = vExpr.type
        if (keyVarUid != null && type == R_NullType) {
            val trueFacts = C_VarFacts.of(nulled = mapOf(keyVarUid to C_VarFact.YES))
            val falseFacts = C_VarFacts.of(nulled = mapOf(keyVarUid to C_VarFact.NO))
            return C_ExprVarFacts.of(trueFacts, falseFacts)
        }

        return C_ExprVarFacts.EMPTY
    }

    private fun compileExpr(ctx: C_ExprContext, keyType: R_Type?, expr: S_Expr): V_Expr {
        val valueType = if (keyType == null) null else C_Types.removeNullable(keyType)
        val name = expr.asName()

        if (valueType is R_EnumType && name != null) {
            val attr = valueType.enum.attr(name.str)
            if (attr != null) {
                val value = Rt_EnumValue(valueType, attr)
                return V_ConstantValueExpr(ctx, expr.startPos, value)
            }
        }

        val cExpr = expr.compileOpt(ctx)
        if (cExpr == null) {
            return C_Utils.errorVExpr(ctx, expr.startPos, valueType ?: R_CtErrorType)
        }

        val cValue = cExpr.value()
        return cValue
    }
}

class S_WhenCondtiionElse(val pos: S_Pos): S_WhenCondition() {
    override fun compileBad(ctx: C_ExprContext) {
        // Do nothing.
    }

    override fun compile(
            ctx: C_ExprContext,
            builder: C_WhenChooserDetailsBuilder,
            keyVarUid: C_VarUid?,
            keyType: R_Type?,
            idx: Int,
            last: Boolean
    ) {
        if (!last) {
            ctx.msgCtx.error(pos, "when_else_notlast", "Else case must be the last one")
        }

        check(builder.elseCase == null)
        builder.elseCase = IndexedValue(idx, pos)
        builder.caseFacts[idx] = builder.elseFacts.toVarFacts()
        builder.elseFacts.clear()
    }
}

class S_WhenExprCase(val cond: S_WhenCondition, val expr: S_Expr)

class S_WhenExpr(pos: S_Pos, val expr: S_Expr?, val cases: List<S_WhenExprCase>): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val conds = cases.map { it.cond }

        val chooserDetails = compileChooserDetails(ctx, expr, conds)?.toVDetails()
        if (chooserDetails == null) {
            return C_Utils.errorExpr(ctx, startPos)
        }

        val missingElseReported = !chooserDetails.full
        if (missingElseReported) {
            ctx.msgCtx.error(startPos, "when_no_else", "Else case missing")
        }

        val (resType, valueExprs) = compileExprs(ctx, chooserDetails.caseFacts)

        val resFacts = C_ExprVarFacts.of(postFacts = chooserDetails.keyPostFacts)
        val vResExpr = V_WhenExpr(ctx, startPos, chooserDetails, valueExprs, resType, resFacts)
        return C_VExpr(vResExpr)
    }

    private fun compileExprs(ctx: C_ExprContext, caseFacts: List<C_VarFacts>): Pair<R_Type, List<V_Expr>> {
        val cValuesRaw = cases.mapIndexed { i, case ->
            case.expr.compileWithFacts(ctx, caseFacts[i]).value()
        }

        val cValues = C_BinOp_Common.promoteNumeric(ctx, cValuesRaw)

        if (cValues.isEmpty()) {
            return Pair(R_CtErrorType, immListOf())
        }

        val type = cValues.withIndex().fold(cValues[0].type) { t, (i, value) ->
            C_Types.commonType(t, value.type, cases[i].expr.startPos, "expr_when_incompatible_type",
                    "When case expressions have incompatible types")
        }

        for (cValue in cValues) {
            val valueType = cValue.type
            C_Utils.checkUnitType(ctx.msgCtx, cValue.pos, valueType, "when_exprtype_unit", "Expression returns nothing")
        }

        return Pair(type, cValues)
    }

    companion object {
        fun compileChooser(
                ctx: C_ExprContext,
                expr: S_Expr?,
                conds: List<S_WhenCondition>
        ): C_WhenChooser? {
            val chooserDetails = compileChooserDetails(ctx, expr, conds)
            if (chooserDetails == null) {
                return null
            }
            return C_WhenChooser(chooserDetails)
        }

        private fun compileChooserDetails(
                ctx: C_ExprContext,
                expr: S_Expr?,
                conds: List<S_WhenCondition>
        ): C_WhenChooserDetails? {
            val keyValue = if (expr == null) null else {
                val keyExpr = expr.compileOpt(ctx)
                if (keyExpr == null) {
                    conds.forEach { it.compileBad(ctx) }
                    return null
                }
                keyExpr.value()
            }

            val keyVarId = keyValue?.varId()
            val keyType = keyValue?.type
            val keyPostFacts = keyValue?.varFacts?.postFacts ?: C_VarFacts.EMPTY

            if (keyType == R_NullType) {
                ctx.msgCtx.error(expr!!.startPos, "when_expr_type:null", "Cannot use null as when expression")
            }

            val bodyCtx = ctx.updateFacts(keyPostFacts)
            val builder = C_WhenChooserDetailsBuilder(keyValue, keyPostFacts, bodyCtx)

            for ((i, cond) in conds.withIndex()) {
                cond.compile(bodyCtx, builder, keyVarId, keyType, i, i == conds.size - 1)
            }

            checkTypes(builder)
            builder.fullCoverage = checkFullCoverage(ctx, builder)

            return C_WhenChooserDetails(builder)
        }

        private fun checkTypes(builder: C_WhenChooserDetailsBuilder) {
            val keyValue = builder.keyExpr

            if (keyValue == null) {
                for (case in builder.variableCases) {
                    C_Types.match(R_BooleanType, case.value.type, case.value.pos, "when_case_type", "Type mismatch")
                }
            } else {
                val keyType = keyValue.type
                for (case in builder.variableCases) {
                    val caseType = case.value.type
                    C_Errors.check(checkCaseType(keyType, caseType), case.value.pos) {
                        "when_case_type:$keyType:$caseType" to "Type mismatch: $caseType instead of $keyType"
                    }
                }
            }
        }

        private fun checkFullCoverage(ctx: C_ExprContext, builder: C_WhenChooserDetailsBuilder): Boolean {
            val keyValue = builder.keyExpr
            if (keyValue == null) {
                return builder.elseCase != null
            }

            val keyType = keyValue.type
            val allValues = allTypeValues(keyType)
            val allValuesCovered = !allValues.isEmpty() && allValues == builder.constantCases.keys

            if (allValuesCovered && builder.elseCase != null) {
                ctx.msgCtx.error(builder.elseCase!!.value, "when_else_allvalues:$keyType",
                        "No values of type '$keyType' left for the else case")
            }

            return allValuesCovered || builder.elseCase != null
        }

        private fun checkCaseType(keyType: R_Type, caseType: R_Type): Boolean {
            val eq = C_BinOp_EqNe.checkTypes(keyType, caseType)
            if (eq) return true

            if (keyType is R_NullableType) {
                val eq2 = C_BinOp_EqNe.checkTypes(keyType.valueType, caseType)
                return eq2
            }

            return false
        }

        private fun allTypeValues(type: R_Type): Set<Rt_Value> {
            if (type == R_BooleanType) {
                return setOf(Rt_BooleanValue(false), Rt_BooleanValue(true))
            } else if (type is R_EnumType) {
                return type.enum.values().toSet()
            } else if (type is R_NullableType) {
                val values = allTypeValues(type.valueType)
                return if (values.isEmpty()) values else (values + setOf(Rt_NullValue))
            } else {
                return setOf()
            }
        }
    }
}
