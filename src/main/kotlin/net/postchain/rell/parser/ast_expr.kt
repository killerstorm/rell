package net.postchain.rell.parser

import net.postchain.rell.model.*
import net.postchain.rell.runtime.*

class S_NameExprPair(val name: S_Name?, val expr: S_Expr)

abstract class S_Expr(val startPos: S_Pos) {
    abstract fun compile(ctx: C_ExprContext): C_Expr
    open fun compileWhere(ctx: C_ExprContext, idx: Int): C_Expr = compile(ctx)
    open fun asName(): S_Name? = null
    open fun constantValue(): Rt_Value? = null

    fun compileWithFacts(ctx: C_ExprContext, facts: C_VarFacts): C_Expr {
        val factsCtx = ctx.updateFacts(facts)
        return compile(factsCtx)
    }
}

class S_StringLiteralExpr(pos: S_Pos, val literal: String): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr = C_RValue.makeExpr(startPos, R_ConstantExpr.makeText(literal))
}

class S_ByteArrayLiteralExpr(pos: S_Pos, val bytes: ByteArray): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr = C_RValue.makeExpr(startPos, R_ConstantExpr.makeBytes(bytes))
}

class S_IntLiteralExpr(pos: S_Pos, val value: Long): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr = C_RValue.makeExpr(startPos, R_ConstantExpr.makeInt(value))
}

class S_BooleanLiteralExpr(pos: S_Pos, val value: Boolean): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr = C_RValue.makeExpr(startPos, R_ConstantExpr.makeBool(value))
}

class S_NullLiteralExpr(pos: S_Pos): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr = C_RValue.makeExpr(startPos, R_ConstantExpr.makeNull())
}

class S_LookupExpr(val opPos: S_Pos, val base: S_Expr, val expr: S_Expr): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        val baseValue = base.compile(ctx).value()
        val exprValue = expr.compile(ctx).value()

        val rBase = baseValue.toRExpr()
        val rExpr = exprValue.toRExpr()

        val baseType = rBase.type
        val effectiveType = if (baseType is R_NullableType) baseType.valueType else baseType

        val lookup = compile0(opPos, rBase, rExpr, effectiveType)

        if (baseType is R_NullableType) {
            throw C_Error(opPos, "expr_lookup_null", "Cannot apply '[]' on nullable value")
        }

        val postFacts = baseValue.varFacts().postFacts.and(exprValue.varFacts().postFacts)
        val exprFacts = C_ExprVarFacts.of(postFacts = postFacts)

        val lookupValue = C_LookupValue(startPos, rBase.type, lookup.expr, lookup.dstExpr, exprFacts)
        return C_ValueExpr(lookupValue)
    }

    private fun compile0(opPos2: S_Pos, rBase: R_Expr, rExpr: R_Expr, baseType: R_Type): C_LookupInternal {
        if (baseType == R_TextType) {
            return compileText(rBase, rExpr)
        } else if (baseType == R_ByteArrayType) {
            return compileByteArray(rBase, rExpr)
        } else if (baseType is R_ListType) {
            return compileList(rBase, rExpr, baseType.elementType)
        } else if (baseType is R_MapType) {
            return compileMap(rBase, rExpr, baseType.keyType, baseType.valueType)
        }

        throw C_Error(opPos2, "expr_lookup_base:${baseType.toStrictString()}",
                "Operator '[]' undefined for type ${baseType.toStrictString()}")
    }

    private fun compileList(rBase: R_Expr, rExpr: R_Expr, elementType: R_Type): C_LookupInternal {
        matchKey(R_IntegerType, rExpr)
        val rResExpr = R_ListLookupExpr(elementType, rBase, rExpr)
        return C_LookupInternal(rResExpr, rResExpr)
    }

    private fun compileMap(rBase: R_Expr, rExpr: R_Expr, keyType: R_Type, valueType: R_Type): C_LookupInternal {
        matchKey(keyType, rExpr)
        val rResExpr = R_MapLookupExpr(valueType, rBase, rExpr)
        return C_LookupInternal(rResExpr, rResExpr)
    }

    private fun compileText(rBase: R_Expr, rExpr: R_Expr): C_LookupInternal {
        matchKey(R_IntegerType, rExpr)
        val rResExpr = R_TextSubscriptExpr(rBase, rExpr)
        return C_LookupInternal(rResExpr, null)
    }

    private fun compileByteArray(rBase: R_Expr, rExpr: R_Expr): C_LookupInternal {
        matchKey(R_IntegerType, rExpr)
        val rResExpr = R_ByteArraySubscriptExpr(rBase, rExpr)
        return C_LookupInternal(rResExpr, null)
    }

    private fun matchKey(rType: R_Type, rExpr: R_Expr) {
        S_Type.match(rType, rExpr.type, expr.startPos, "expr_lookup_keytype", "Invalid lookup key type")
    }

    private class C_LookupInternal(val expr: R_Expr, val dstExpr: R_DestinationExpr?)
}

class S_CreateExpr(pos: S_Pos, val className: List<S_Name>, val exprs: List<S_NameExprPair>): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        ctx.blkCtx.entCtx.checkDbUpdateAllowed(startPos)

        val nsCtx = ctx.blkCtx.entCtx.nsCtx
        val cls = nsCtx.getClass(className)
        val attrs = C_AttributeResolver.resolveCreate(ctx, cls.attributes, exprs, startPos)

        if (!cls.flags.canCreate) {
            val classNameStr = C_Utils.nameStr(className)
            throw C_Error(startPos, "expr_create_cant:$classNameStr",
                    "Not allowed to create instances of class '$classNameStr'")
        }

        val type = R_ClassType(cls)
        val rExpr = R_CreateExpr(type, cls, attrs.rAttrs)
        return C_RValue.makeExpr(startPos, rExpr, attrs.exprFacts)
    }
}

class S_ParenthesesExpr(startPos: S_Pos, val expr: S_Expr): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext) = expr.compile(ctx)
}

class S_TupleExpr(startPos: S_Pos, val fields: List<Pair<S_Name?, S_Expr>>): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        checkNames()
        val values = fields.map { (_, expr) -> expr.compile(ctx).value() }
        val rExprs = values.map { value -> value.toRExpr() }
        val rExpr = compile0(rExprs)
        val exprFacts = C_ExprVarFacts.forSubExpressions(values)
        return C_RValue.makeExpr(startPos, rExpr, exprFacts)
    }

    private fun compile0(rExprs: List<R_Expr>): R_Expr {
        for ((i, rExpr) in rExprs.withIndex()) {
            C_Utils.checkUnitType(fields[i].second.startPos, rExpr.type, "expr_tuple_unit", "Type of expression is unit")
        }

        val fields = rExprs.mapIndexed { i, rExpr -> R_TupleField(fields[i].first?.str, rExpr.type) }
        val type = R_TupleType(fields)
        return R_TupleExpr(type, rExprs)
    }

    private fun checkNames() {
        val names = mutableSetOf<String>()
        for ((name, _) in fields) {
            val nameStr = name?.str
            if (nameStr != null && !names.add(nameStr)) {
                throw C_Error(name.pos, "expr_tuple_dupname:$nameStr", "Duplicate field: '$nameStr'")
            }
        }
    }
}

class S_IfExpr(pos: S_Pos, val cond: S_Expr, val trueExpr: S_Expr, val falseExpr: S_Expr): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        val cCond = cond.compile(ctx).value()

        val condFacts = cCond.varFacts()
        val trueFacts = condFacts.postFacts.and(condFacts.trueFacts)
        val falseFacts = condFacts.postFacts.and(condFacts.falseFacts)

        val cTrue = trueExpr.compileWithFacts(ctx, trueFacts).value()
        val cFalse = falseExpr.compileWithFacts(ctx, falseFacts).value()

        val truePostFacts = trueFacts.and(cTrue.varFacts().postFacts)
        val falsePostFacts = falseFacts.and(cFalse.varFacts().postFacts)
        val resPostFacts = condFacts.postFacts.and(C_VarFacts.forBranches(ctx, listOf(truePostFacts, falsePostFacts)))
        val resFacts = C_ExprVarFacts.of(postFacts = resPostFacts)

        S_Type.match(R_BooleanType, cCond.type(), cond.startPos, "expr_if_cond_type", "Wrong type of condition expression")
        checkUnitType(trueExpr, cTrue)
        checkUnitType(falseExpr, cFalse)

        val trueType = cTrue.type()
        val falseType = cFalse.type()
        val resType = S_Type.commonType(trueType, falseType, startPos, "expr_if_restype", "Incompatible types of if branches")

        if (cCond.isDb() || cTrue.isDb() || cFalse.isDb()) {
            val dbCond = cCond.toDbExpr()
            val dbTrue = cTrue.toDbExpr()
            val dbFalse = cFalse.toDbExpr()
            val cases = listOf(Db_WhenCase(dbCond, dbTrue))
            val dbExpr = Db_WhenExpr(resType, cases, dbFalse)
            return C_DbValue.makeExpr(startPos, dbExpr, resFacts)
        } else {
            val rCond = cCond.toRExpr()
            val rTrue = cTrue.toRExpr()
            val rFalse = cFalse.toRExpr()
            val rExpr = R_IfExpr(resType, rCond, rTrue, rFalse)
            return C_RValue.makeExpr(startPos, rExpr, resFacts)
        }
    }

    private fun checkUnitType(expr: S_Expr, cValue: C_Value) {
        C_Utils.checkUnitType(expr.startPos, cValue.type(), "expr_if_unit", "Expression returns nothing")
    }
}

sealed class S_WhenCondition {
    abstract fun compile(
            ctx: C_ExprContext,
            builder: C_WhenChooserBuilder,
            keyVarId: C_VarId?,
            keyType: R_Type?,
            idx: Int,
            last: Boolean
    )
}

class S_WhenConditionExpr(val exprs: List<S_Expr>): S_WhenCondition() {
    override fun compile(
            ctx: C_ExprContext,
            builder: C_WhenChooserBuilder,
            keyVarId: C_VarId?,
            keyType: R_Type?,
            idx: Int,
            last: Boolean
    ) {
        var caseFacts = C_VarFacts.EMPTY

        for (expr in exprs) {
            val elseFacts = builder.elseFacts.immutableCopy()
            val exprCtx = ctx.updateFacts(elseFacts)
            val cValue = compileExpr(exprCtx, keyType, expr)

            builder.variableCases.add(C_ChooserCase(expr, cValue, idx))

            val valueFacts = getVarFacts(keyVarId, keyType, cValue)
            builder.elseFacts.andFacts(valueFacts.falseFacts)

            if (exprs.size == 1) {
                caseFacts = elseFacts.and(valueFacts.trueFacts)
            }

            val value = evaluateConstantValue(cValue)
            if (value != null) {
                if (value in builder.constantCases) {
                    throw C_Error(expr.startPos, "when_expr_dupvalue:$value", "Value already used")
                }
                builder.constantCases[value] = idx
            }
        }

        builder.caseFacts[idx] = caseFacts
    }

    private fun evaluateConstantValue(cValue: C_Value): Rt_Value? {
        try {
            val v = cValue.constantValue()
            return v
        } catch (e: Rt_Error) {
            throw C_Error(cValue.pos, "expr_eval_fail:${e.code}", e.message ?: "Evaluation failed")
        } catch (e: Throwable) {
            throw C_Error(cValue.pos, "expr_eval_fail:${e.javaClass.canonicalName}", "Evaluation failed")
        }
    }

    private fun getVarFacts(keyVarId: C_VarId?, keyType: R_Type?, cValue: C_Value): C_ExprVarFacts {
        if (keyType == null) {
            return cValue.varFacts()
        }

        val type = cValue.type()
        if (keyVarId != null && type == R_NullType) {
            val trueFacts = C_VarFacts.of(nulled = mapOf(keyVarId to C_VarFact.YES))
            val falseFacts = C_VarFacts.of(nulled = mapOf(keyVarId to C_VarFact.NO))
            return C_ExprVarFacts.of(trueFacts, falseFacts)
        }

        return C_ExprVarFacts.EMPTY
    }

    private fun compileExpr(ctx: C_ExprContext, keyType: R_Type?, expr: S_Expr): C_Value {
        val valueType = if (keyType is R_NullableType) keyType.valueType else keyType
        val name = expr.asName()

        if (valueType is R_EnumType && name != null) {
            val attr = valueType.attr(name.str)
            if (attr != null) {
                val value = Rt_EnumValue(valueType, attr)
                val rExpr = R_ConstantExpr(value)
                return C_RValue(expr.startPos, rExpr)
            }
        }

        val cValue = expr.compile(ctx).value()
        return cValue
    }
}

class S_WhenCondtiionElse(val pos: S_Pos): S_WhenCondition() {
    override fun compile(
            ctx: C_ExprContext,
            builder: C_WhenChooserBuilder,
            keyVarId: C_VarId?,
            keyType: R_Type?,
            idx: Int,
            last: Boolean
    ) {
        if (!last) {
            throw C_Error(pos, "when_else_notlast", "Else case must be the last one")
        }

        check(builder.elseCase == null)
        builder.elseCase = Pair(pos, idx)
        builder.caseFacts[idx] = builder.elseFacts.immutableCopy()
        builder.elseFacts.clear()
    }
}

class C_WhenChooserBuilder(val keyValue: C_Value?, val keyPostFacts: C_VarFacts, val bodyCtx: C_ExprContext) {
    val constantCases = mutableMapOf<Rt_Value, Int>()
    val variableCases = mutableListOf<C_ChooserCase>()
    var elseCase: Pair<S_Pos, Int>? = null
    var fullCoverage = false
    val caseFacts = mutableMapOf<Int, C_VarFacts>()
    val elseFacts = C_MutableVarFacts()

    fun caseFactsToList(): List<C_VarFacts> {
        val list = ArrayList<C_VarFacts>(caseFacts.size)
        for (i in 0 until caseFacts.size) {
            list.add(caseFacts.getValue(i))
        }
        return list
    }
}

class C_ChooserCase(val expr: S_Expr, val cValue: C_Value, val idx: Int)

class C_WhenChooser(
        val rChooser: R_WhenChooser,
        val keyPostFacts: C_VarFacts,
        val bodyCtx: C_ExprContext,
        val full: Boolean,
        val caseFacts: List<C_VarFacts>,
        val elseFacts: C_VarFacts
)

class S_WhenExprCase(val cond: S_WhenCondition, val expr: S_Expr)

class S_WhenExpr(pos: S_Pos, val expr: S_Expr?, val cases: List<S_WhenExprCase>): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        val conds = cases.map { it.cond }

        val builder = createWhenBuilder(ctx, expr, conds)
        if (!builder.fullCoverage) {
            throw C_Error(startPos, "when_no_else", "Else case missing")
        }

        val caseFacts = builder.caseFactsToList()
        val (type, cValues) = compileExprs(ctx, caseFacts)

        val db = (builder.keyValue?.isDb() ?: false)
                || builder.variableCases.any { it.cValue.isDb() }
                || cValues.any { it.isDb() }

        val resFacts = C_ExprVarFacts.of(postFacts = builder.keyPostFacts)

        if (db) {
            val dbExpr = compileDb(builder, type, cValues)
            return C_DbValue.makeExpr(startPos, dbExpr, resFacts)
        } else {
            val rChooser = compileChooserR(builder)
            val rExprs = cValues.map { it.toRExpr() }
            val rExpr = R_WhenExpr(type, rChooser, rExprs)
            return C_RValue.makeExpr(startPos, rExpr, resFacts)
        }
    }

    private fun compileExprs(ctx: C_ExprContext, caseFacts: List<C_VarFacts>): Pair<R_Type, List<C_Value>> {
        val cValues = cases.mapIndexed { i, case ->
            case.expr.compileWithFacts(ctx, caseFacts[i]).value()
        }

        val type = cValues.withIndex().fold(cValues[0].type()) { t, (i, value) ->
            S_Type.commonType(t, value.type(), cases[i].expr.startPos, "expr_when_incompatible_type",
                    "When case expressions have incompatible types")
        }
        return Pair(type, cValues)
    }

    private fun compileDb(builder: C_WhenChooserBuilder, type: R_Type, cValues: List<C_Value>): Db_Expr {
        val caseCondMap = mutableMapOf<Int, MutableList<Db_Expr>>()
        for (case in builder.variableCases) caseCondMap[case.idx] = mutableListOf()
        for (case in builder.variableCases) caseCondMap.getValue(case.idx).add(case.cValue.toDbExpr())

        val keyExpr = builder.keyValue?.toDbExpr()

        val caseExprs = caseCondMap.keys.sorted().map { idx ->
            val conds = caseCondMap.getValue(idx)
            val value = cValues[idx]
            val dbCond = compileDbCondition(keyExpr, conds)
            Db_WhenCase(dbCond, value.toDbExpr())
        }

        val elseIdx = builder.elseCase
        val elseExpr = if (elseIdx == null) null else cValues[elseIdx.second].toDbExpr()

        return Db_WhenExpr(type, caseExprs, elseExpr)
    }

    private fun compileDbCondition(keyExpr: Db_Expr?, conds: List<Db_Expr>): Db_Expr {
        check(!conds.isEmpty())

        if (keyExpr != null) {
            if (conds.size == 1) {
                return Db_BinaryExpr(R_BooleanType, Db_BinaryOp_Eq, keyExpr, conds[0])
            } else {
                return Db_InExpr(keyExpr, conds)
            }
        }

        var res = conds[0]
        for (cond in conds.subList(1, conds.size)) {
            res = Db_BinaryExpr(R_BooleanType, Db_BinaryOp_Or, res, cond)
        }

        return res
    }

    companion object {
        fun compileChooser(
                ctx: C_ExprContext,
                expr: S_Expr?,
                conds: List<S_WhenCondition>
        ): C_WhenChooser {
            val builder = createWhenBuilder(ctx, expr, conds)
            val chooser = compileChooserR(builder)
            return C_WhenChooser(
                    chooser,
                    builder.keyPostFacts,
                    builder.bodyCtx,
                    builder.fullCoverage,
                    builder.caseFactsToList(),
                    builder.elseFacts.immutableCopy()
            )
        }

        private fun createWhenBuilder(
                ctx: C_ExprContext,
                expr: S_Expr?,
                conds: List<S_WhenCondition>
        ): C_WhenChooserBuilder {
            val keyValue = if (expr == null) null else expr.compile(ctx).value()

            val keyVarId = keyValue?.varId()
            val keyType = keyValue?.type()
            val keyPostFacts = keyValue?.varFacts()?.postFacts ?: C_VarFacts.EMPTY

            if (keyType == R_NullType) {
                throw C_Error(expr!!.startPos, "when_expr_type:null", "Cannot use null as when expression")
            }

            val bodyCtx = ctx.updateFacts(keyPostFacts)
            val builder = C_WhenChooserBuilder(keyValue, keyPostFacts, bodyCtx)

            for ((i, cond) in conds.withIndex()) {
                cond.compile(bodyCtx, builder, keyVarId, keyType, i, i == conds.size - 1)
            }

            checkTypes(builder)
            builder.fullCoverage = checkFullCoverage(builder)

            return builder
        }

        private fun checkTypes(builder: C_WhenChooserBuilder) {
            val keyValue = builder.keyValue

            if (keyValue == null) {
                for (case in builder.variableCases) {
                    S_Type.match(R_BooleanType, case.cValue.type(), case.expr.startPos, "when_case_type", "Type mismatch")
                }
            } else {
                val keyType = keyValue.type()
                for (case in builder.variableCases) {
                    val caseType = case.cValue.type()
                    if (!checkCaseType(keyType, caseType)) {
                        throw C_Error(case.expr.startPos, "when_case_type:$keyType:$caseType",
                                "Type mismatch: $caseType instead of $keyType")
                    }
                }
            }
        }

        private fun checkFullCoverage(builder: C_WhenChooserBuilder): Boolean {
            val keyValue = builder.keyValue
            if (keyValue == null) {
                return builder.elseCase != null
            }

            val keyType = keyValue.type()
            val allValues = allTypeValues(keyType)
            val allValuesCovered = !allValues.isEmpty() && allValues == builder.constantCases.keys

            if (allValuesCovered && builder.elseCase != null) {
                throw C_Error(builder.elseCase!!.first, "when_else_allvalues:$keyType",
                        "No values of type '$keyType' left for the else case")
            }

            return allValuesCovered || builder.elseCase != null
        }

        private fun compileChooserR(builder: C_WhenChooserBuilder): R_WhenChooser {
            val keyValue = builder.keyValue

            if (keyValue == null) {
                val keyExpr = R_ConstantExpr.makeBool(true)
                val caseExprs = builder.variableCases.map { IndexedValue(it.idx, it.cValue.toRExpr()) }
                return R_IterativeWhenChooser(keyExpr, caseExprs, builder.elseCase?.second)
            }

            val rKeyExpr = keyValue.toRExpr()

            val chooser = if (builder.constantCases.size == builder.variableCases.size) {
                R_LookupWhenChooser(rKeyExpr, builder.constantCases.toMap(), builder.elseCase?.second)
            } else {
                val caseExprs = builder.variableCases.map { IndexedValue(it.idx, it.cValue.toRExpr()) }
                R_IterativeWhenChooser(rKeyExpr, caseExprs, builder.elseCase?.second)
            }

            return chooser
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
                return type.values().toSet()
            } else if (type is R_NullableType) {
                val values = allTypeValues(type.valueType)
                return if (values.isEmpty()) values else (values + setOf(Rt_NullValue))
            } else {
                return setOf()
            }
        }
    }
}

class S_ListLiteralExpr(pos: S_Pos, val exprs: List<S_Expr>): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        checkEmpty()
        val values = exprs.map { it.compile(ctx).value() }
        val rExprs = values.map { it.toRExpr() }
        val rExpr = compile0(rExprs)
        val exprFacts = C_ExprVarFacts.forSubExpressions(values)
        return C_RValue.makeExpr(startPos, rExpr, exprFacts)
    }

    private fun checkEmpty() {
        if (exprs.isEmpty()) {
            throw C_Error(startPos, "expr_list_empty", "Type of empty list literal is unknown; use list<T>() instead")
        }
    }

    private fun compile0(rExprs: List<R_Expr>): R_Expr {
        for ((i, rExpr) in rExprs.withIndex()) {
            C_Utils.checkUnitType(exprs[i].startPos, rExpr.type, "expr_list_unit", "Element expression returns nothing")
        }

        var rType = rExprs[0].type
        for ((i, rExpr) in rExprs.subList(1, rExprs.size).withIndex()) {
            rType = S_Type.commonType(rType, rExpr.type, exprs[i].startPos, "expr_list_itemtype", "Wrong list item type")
        }

        val rListType = R_ListType(rType)
        return R_ListLiteralExpr(rListType, rExprs)
    }
}

class S_MapLiteralExpr(startPos: S_Pos, val entries: List<Pair<S_Expr, S_Expr>>): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        checkEmpty()

        val valueEntries = entries.map { (key, value) -> Pair(key.compile(ctx).value(), value.compile(ctx).value()) }
        val rEntries = valueEntries.map { (key, value) -> Pair(key.toRExpr(), value.toRExpr()) }

        val rExpr = compile0(rEntries)

        val values = valueEntries.flatMap { (key, value) -> listOf(key, value) }
        val exprFacts = C_ExprVarFacts.forSubExpressions(values)

        return C_RValue.makeExpr(startPos, rExpr, exprFacts)
    }

    private fun checkEmpty() {
        if (entries.isEmpty()) {
            throw C_Error(startPos, "expr_map_empty", "Type of empty map literal is unknown; use map<K,V>() instead")
        }
    }

    private fun compile0(rEntries: List<Pair<R_Expr, R_Expr>>): R_Expr {
        for ((i, rEntry) in rEntries.withIndex()) {
            val (rKey, rValue) = rEntry
            val keyExpr = entries[i].first
            val valueExpr = entries[i].second
            C_Utils.checkUnitType(keyExpr.startPos, rKey.type, "expr_map_key_unit", "Key expression returns nothing")
            C_Utils.checkUnitType(valueExpr.startPos, rValue.type, "expr_map_value_unit", "Value expression returns nothing")
            C_Utils.checkMapKeyType(valueExpr.startPos, rKey.type)
        }

        var rKeyType = rEntries[0].first.type
        var rValueType = rEntries[0].second.type

        for ((i, kv) in rEntries.subList(1, rEntries.size).withIndex()) {
            val (rKey, rValue) = kv
            rKeyType = S_Type.commonType(rKeyType, rKey.type, entries[i].first.startPos, "expr_map_keytype",
                    "Wrong map entry key type")
            rValueType = S_Type.commonType(rValueType, rValue.type, entries[i].second.startPos, "expr_map_valuetype",
                    "Wrong map entry value type")
        }

        val rMapType = R_MapType(rKeyType, rValueType)
        return R_MapLiteralExpr(rMapType, rEntries)
    }
}

sealed class S_CollectionExpr(
        pos: S_Pos,
        val type: S_Type?,
        val args: List<S_Expr>,
        val colType: String
): S_Expr(pos)
{
    abstract fun makeExpr(rType: R_Type, rArg: R_Expr?): R_Expr

    open fun checkType(rType: R_Type) {
    }

    override fun compile(ctx: C_ExprContext): C_Expr {
        val values = args.map { it.compile(ctx).value() }
        val rArgs = values.map { it.toRExpr() }
        val rExpr = compile0(ctx, rArgs)
        val exprFacts = C_ExprVarFacts.forSubExpressions(values)
        return C_RValue.makeExpr(startPos, rExpr, exprFacts)
    }

    private fun compile0(ctx: C_ExprContext, rArgs: List<R_Expr>): R_Expr {
        val rType = type?.compile(ctx)

        if (rType != null) {
            checkType(rType)
        }

        if (rArgs.size == 0) {
            return compileNoArgs(rType)
        } else if (rArgs.size == 1) {
            val rArg = rArgs[0]
            return compileOneArg(rType, rArg)
        } else {
            throw C_Error(startPos, "expr_${colType}_argcnt:${rArgs.size}",
                    "Wrong number of arguments for $colType<>: ${rArgs.size}")
        }
    }

    private fun compileNoArgs(rType: R_Type?): R_Expr {
        if (rType == null) {
            throw C_Error(startPos, "expr_${colType}_notype", "Element type not specified for $colType")
        }
        return makeExpr(rType, null)
    }

    private fun compileOneArg(rType: R_Type?, rArg: R_Expr): R_Expr {
        val rArgType = rArg.type
        if (rArgType !is R_CollectionType) {
            throw C_Error(startPos, "expr_${colType}_badtype",
                    "Wrong argument type for $colType<>: ${rArgType.toStrictString()}")
        }

        val rElementType = checkElementType(
                startPos,
                rType,
                rArgType.elementType,
                "expr_${colType}_typemiss",
                "Element type mismatch for $colType<>")

        return makeExpr(rElementType, rArg)
    }

    companion object {
        fun checkElementType(pos: S_Pos, declaredType: R_Type?, argumentType: R_Type, errCode: String, errMsg: String): R_Type {
            if (declaredType == null) {
                return argumentType
            }

            if (!declaredType.isAssignableFrom(argumentType)) {
                throw C_Error(
                        pos,
                        "$errCode:${declaredType.toStrictString()}:${argumentType.toStrictString()}",
                        "$errMsg: ${argumentType.toStrictString()} instead of ${declaredType.toStrictString()}"
                )
            }

            return declaredType
        }
    }
}

class S_ListExpr(pos: S_Pos, type: S_Type?, args: List<S_Expr>)
    : S_CollectionExpr(pos, type, args, "list")
{
    override fun makeExpr(rType: R_Type, rArg: R_Expr?): R_Expr {
        val rListType = R_ListType(rType)
        return R_ListExpr(rListType, rArg)
    }
}

class S_SetExpr(pos: S_Pos, type: S_Type?, args: List<S_Expr>)
    : S_CollectionExpr(pos, type, args, "set")
{
    override fun checkType(rType: R_Type) {
        C_Utils.checkSetElementType(startPos, rType)
    }

    override fun makeExpr(rType: R_Type, rArg: R_Expr?): R_Expr {
        C_Utils.checkSetElementType(startPos, rType)
        val rSetType = R_SetType(rType)
        return R_SetExpr(rSetType, rArg)
    }
}

class S_MapExpr(
        pos: S_Pos,
        val keyValueTypes: Pair<S_Type, S_Type>?,
        val args: List<S_Expr>
): S_Expr(pos)
{
    override fun compile(ctx: C_ExprContext): C_Expr {
        val values = args.map { it.compile(ctx).value() }
        val rArgs = values.map { it.toRExpr() }
        val rExpr = compile0(ctx, rArgs)
        val exprFacts = C_ExprVarFacts.forSubExpressions(values)
        return C_RValue.makeExpr(startPos, rExpr, exprFacts)
    }

    private fun compile0(ctx: C_ExprContext, rArgs: List<R_Expr>): R_Expr {
        val rKeyType = keyValueTypes?.first?.compile(ctx)
        val rValueType = keyValueTypes?.second?.compile(ctx)

        if (rKeyType != null) {
            C_Utils.checkMapKeyType(startPos, rKeyType)
        }

        if (rArgs.size == 0) {
            return compileNoArgs(rKeyType, rValueType)
        } else if (rArgs.size == 1) {
            val rArg = rArgs[0]
            return compileOneArg(rKeyType, rValueType, rArg)
        } else {
            throw C_Error(startPos, "expr_map_argcnt:${rArgs.size}", "Wrong number of arguments for map<>: ${rArgs.size}")
        }
    }

    private fun compileNoArgs(rKeyType: R_Type?, rValueType: R_Type?): R_Expr {
        if (rKeyType == null || rValueType == null) {
            throw C_Error(startPos, "expr_map_notype", "Key/value types not specified for map")
        }
        val rMapType = R_MapType(rKeyType, rValueType)
        return R_MapExpr(rMapType, null)
    }

    private fun compileOneArg(rKeyType: R_Type?, rValueType: R_Type?, rArg: R_Expr): R_Expr {
        val rArgType = rArg.type

        if (rArgType !is R_MapType) {
            throw C_Error(startPos, "expr_map_badtype:${rArgType.toStrictString()}",
                    "Wrong argument type for map<>: ${rArgType.toStrictString()}")
        }

        val rActualKeyType = S_CollectionExpr.checkElementType(
                startPos,
                rKeyType,
                rArgType.keyType,
                "expr_map_key_typemiss",
                "Key type mismatch for map<>")

        val rActualValueType = S_CollectionExpr.checkElementType(
                startPos,
                rValueType,
                rArgType.valueType,
                "expr_map_value_typemiss",
                "Value type mismatch for map<>")

        val rMapType = R_MapType(rActualKeyType, rActualValueType)
        return R_MapExpr(rMapType, rArg)
    }
}

class S_RecordOrCallExpr(val base: S_Expr, val args: List<S_NameExprPair>): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        val cBase = base.compile(ctx)
        return cBase.call(ctx, base.startPos, args)
    }
}
