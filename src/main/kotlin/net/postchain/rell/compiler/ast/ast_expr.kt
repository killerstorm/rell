/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*

class S_NameExprPair(val name: S_Name?, val expr: S_Expr)

abstract class S_Expr(val startPos: S_Pos) {
    abstract fun compile(ctx: C_ExprContext, typeHint: C_TypeHint = C_TypeHint.NONE): C_Expr

    fun compileOpt(ctx: C_ExprContext, typeHint: C_TypeHint = C_TypeHint.NONE): C_Expr? {
        return ctx.msgCtx.consumeError { compile(ctx, typeHint) }
    }

    fun compileWithFacts(ctx: C_ExprContext, facts: C_VarFacts, typeHint: C_TypeHint = C_TypeHint.NONE): C_Expr {
        val factsCtx = ctx.updateFacts(facts)
        return compile(factsCtx, typeHint)
    }

    fun compile(ctx: C_StmtContext, typeHint: C_TypeHint = C_TypeHint.NONE) = compile(ctx.exprCtx, typeHint)
    fun compileOpt(ctx: C_StmtContext, typeHint: C_TypeHint = C_TypeHint.NONE) = compileOpt(ctx.exprCtx, typeHint)

    open fun compileFrom(ctx: C_ExprContext, atPos: S_Pos, subValues: MutableList<V_Expr>): C_AtFrom {
        val item = compileFromItem(ctx)
        return when (item) {
            is C_AtFromItem_Entity -> C_AtFrom_Entities(ctx, listOf(C_AtEntity(item.pos, item.entity, item.alias, 0)))
            is C_AtFromItem_Iterable -> {
                subValues.add(item.vExpr)
                C_AtFrom_Iterable(ctx, atPos, null, item)
            }
        }
    }

    open fun compileFromItem(ctx: C_ExprContext): C_AtFromItem {
        val cExpr = compile(ctx)
        val cValue = cExpr.value()
        val type = cValue.type()
        return when (type) {
            is R_CollectionType -> C_AtFromItem_Collection(startPos, cValue, type.elementType)
            is R_MapType -> {
                val tupleType = R_TupleType(listOf(
                        R_TupleField("k", type.keyType),
                        R_TupleField("v", type.valueType)
                ))
                C_AtFromItem_Map(startPos, cValue, tupleType)
            }
            else -> {
                val s = type.toStrictString()
                throw C_Error(startPos, "at:from:bad_type:$s", "Invalid type for at-expression: $s")
            }
        }
    }

    open fun compileWhere(ctx: C_ExprContext, idx: Int): C_Expr = compile(ctx)

    open fun asName(): S_Name? = null
    open fun constantValue(): Rt_Value? = null
}

sealed class S_LiteralExpr(pos: S_Pos): S_Expr(pos) {
    abstract fun value(): Rt_Value

    final override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val v = value()
        return V_RExpr.makeExpr(startPos, R_ConstantExpr(v))
    }
}

class S_StringLiteralExpr(pos: S_Pos, val literal: String): S_LiteralExpr(pos) {
    override fun value() = Rt_TextValue(literal)
}

class S_ByteArrayLiteralExpr(pos: S_Pos, val bytes: ByteArray): S_LiteralExpr(pos) {
    override fun value() = Rt_ByteArrayValue(bytes)
}

class S_IntegerLiteralExpr(pos: S_Pos, val value: Long): S_LiteralExpr(pos) {
    override fun value() = Rt_IntValue(value)
}

class S_DecimalLiteralExpr(pos: S_Pos, val value: Rt_Value): S_LiteralExpr(pos) {
    override fun value() = value
}

class S_BooleanLiteralExpr(pos: S_Pos, val value: Boolean): S_LiteralExpr(pos) {
    override fun value() = Rt_BooleanValue(value)
}

class S_NullLiteralExpr(pos: S_Pos): S_LiteralExpr(pos) {
    override fun value() = Rt_NullValue
}

class S_SubscriptExpr(val opPos: S_Pos, val base: S_Expr, val expr: S_Expr): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val vBase = base.compile(ctx).value()
        val vExpr = expr.compile(ctx).value()

        val baseType = vBase.type()
        val effectiveType = if (baseType is R_NullableType) baseType.valueType else baseType

        val kind = compileSubscriptKind(ctx, effectiveType)
        if (kind == null) {
            return C_Utils.errorExpr(opPos)
        }

        C_Errors.check(ctx.msgCtx, baseType !is R_NullableType, opPos, "expr_subscript_null", "Cannot apply '[]' on nullable value")
        C_Types.match(kind.keyType, vExpr.type(), expr.startPos, "expr_subscript_keytype", "Invalid subscript key type")

        val postFacts = vBase.varFacts().postFacts.and(vExpr.varFacts().postFacts)
        val exprFacts = C_ExprVarFacts.of(postFacts = postFacts)

        val vResExpr = kind.compile(vBase, vExpr, exprFacts)
        return C_VExpr(vResExpr)
    }

    private fun compileSubscriptKind(ctx: C_ExprContext, baseType: R_Type): Subscript? {
        return when (baseType) {
            R_TextType -> Subscript_Common(R_IntegerType, V_CommonSubscriptKind_Text)
            R_ByteArrayType -> Subscript_Common(R_IntegerType, V_CommonSubscriptKind_ByteArray)
            is R_ListType -> Subscript_Common(R_IntegerType, V_CommonSubscriptKind_List(baseType.elementType))
            is R_VirtualListType -> {
                val resType = S_VirtualType.virtualMemberType(baseType.innerType.elementType)
                Subscript_Common(R_IntegerType, V_CommonSubscriptKind_VirtualList(resType))
            }
            is R_MapType -> Subscript_Common(baseType.keyType, V_CommonSubscriptKind_Map(baseType.valueType))
            is R_VirtualMapType -> {
                val resType = S_VirtualType.virtualMemberType(baseType.innerType.valueType)
                Subscript_Common(baseType.innerType.keyType, V_CommonSubscriptKind_VirtualMap(resType))
            }
            is R_TupleType -> Subscript_Tuple(baseType)
            is R_VirtualTupleType -> Subscript_VirtualTuple(baseType)
            else -> {
                val typeStr = baseType.toStrictString()
                ctx.msgCtx.error(opPos, "expr_subscript_base:$typeStr", "Operator '[]' undefined for type $typeStr")
                null
            }
        }
    }

    private fun compileTuple0(vExpr: V_Expr, baseType: R_TupleType): Int {
        val indexZ = C_Utils.evaluate(expr.startPos) { vExpr.constantValue()?.asInteger() }
        val index = C_Errors.checkNotNull(indexZ, expr.startPos, "expr_subscript:tuple:no_const",
                "Subscript key for a tuple must be a constant value, not an expression")

        val fields = baseType.fields
        C_Errors.check(index >= 0 && index < fields.size, expr.startPos) {
            "expr_subscript:tuple:index:$index:${fields.size}" to "Index out of bounds, must be from 0 to ${fields.size - 1}"
        }

        return index.toInt()
    }

    private inner abstract class Subscript(val keyType: R_Type) {
        abstract fun compile(base: V_Expr, key: V_Expr, varFacts: C_ExprVarFacts): V_Expr
    }

    private inner class Subscript_Common(keyType: R_Type, val kind: V_CommonSubscriptKind): Subscript(keyType) {
        override fun compile(base: V_Expr, key: V_Expr, varFacts: C_ExprVarFacts): V_Expr {
            return V_CommonSubscriptExpr(startPos, base, varFacts, key, kind)
        }
    }

    private inner class Subscript_Tuple(private val baseType: R_TupleType): Subscript(R_IntegerType) {
        override fun compile(base: V_Expr, key: V_Expr, varFacts: C_ExprVarFacts): V_Expr {
            val index = compileTuple0(key, baseType)
            val resType = baseType.fields[index].type
            return V_TupleSubscriptExpr(startPos, base, varFacts, V_TupleSubscriptKind_Simple, resType, index)
        }
    }

    private inner class Subscript_VirtualTuple(private val baseType: R_VirtualTupleType): Subscript(R_IntegerType) {
        override fun compile(base: V_Expr, key: V_Expr, varFacts: C_ExprVarFacts): V_Expr {
            val index = compileTuple0(key, baseType.innerType)
            val fieldType = baseType.innerType.fields[index].type
            val resType = S_VirtualType.virtualMemberType(fieldType)
            return V_TupleSubscriptExpr(startPos, base, varFacts, V_TupleSubscriptKind_Virtual, resType, index)
        }
    }
}

class S_CreateExpr(pos: S_Pos, val entityName: List<S_Name>, val exprs: List<S_NameExprPair>): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        ctx.checkDbUpdateAllowed(startPos)

        val entity = ctx.nsCtx.getEntity(entityName)
        val attrs = C_AttributeResolver.resolveCreate(ctx, entity.attributes, exprs, startPos)

        C_Errors.check(entity.flags.canCreate, startPos) {
            val entityNameStr = C_Utils.nameStr(entityName)
            "expr_create_cant:$entityNameStr" to "Not allowed to create instances of entity '$entityNameStr'"
        }

        val rExpr = R_CreateExpr(entity, attrs.rAttrs)
        return V_RExpr.makeExpr(startPos, rExpr, attrs.exprFacts)
    }
}

class S_ParenthesesExpr(startPos: S_Pos, val expr: S_Expr): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint) = expr.compile(ctx, typeHint)
    override fun compileFromItem(ctx: C_ExprContext) = expr.compileFromItem(ctx)
}

sealed class S_TupleExprField
class S_TupleExprField_Expr(val expr: S_Expr): S_TupleExprField()
class S_TupleExprField_NameEqExpr(val name: S_Name, val eqPos: S_Pos, val expr: S_Expr): S_TupleExprField()
class S_TupleExprField_NameColonExpr(val name: S_Name, val colonPos: S_Pos, val expr: S_Expr): S_TupleExprField()

class S_TupleExpr(startPos: S_Pos, val fields: List<S_TupleExprField>): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val pairs = fields.map {
            when (it) {
                is S_TupleExprField_NameColonExpr -> throw C_Error(it.colonPos, "tuple_name_colon_expr:${it.name}", "Syntax error")
                is S_TupleExprField_NameEqExpr -> Pair(it.name, it.expr)
                is S_TupleExprField_Expr -> Pair(null, it.expr)
            }
        }

        checkNames(pairs, "field")

        val vExprs = pairs.map { (_, expr) -> expr.compile(ctx).value() }
        val vExpr = compile0(pairs, vExprs)
        return C_VExpr(vExpr)
    }

    private fun compile0(pairs: List<Pair<S_Name?, S_Expr>>, vExprs: List<V_Expr>): V_Expr {
        for ((i, vExpr) in vExprs.withIndex()) {
            C_Utils.checkUnitType(pairs[i].second.startPos, vExpr.type(), "expr_tuple_unit", "Type of expression is unit")
        }

        val fields = vExprs.mapIndexed { i, vExpr -> R_TupleField(pairs[i].first?.str, vExpr.type()) }
        val type = R_TupleType(fields)

        val varFacts = C_ExprVarFacts.forSubExpressions(vExprs)
        return V_TupleExpr(startPos, type, vExprs, varFacts)
    }

    override fun compileFrom(ctx: C_ExprContext, atPos: S_Pos, subValues: MutableList<V_Expr>): C_AtFrom {
        val pairs = fields.map {
            when (it) {
                is S_TupleExprField_NameColonExpr -> Pair(it.name, it.expr)
                is S_TupleExprField_NameEqExpr -> throw C_Error(it.eqPos, "expr:at:from:tuple_name_eq_expr:${it.name}", "Syntax error")
                is S_TupleExprField_Expr -> Pair(null, it.expr)
            }
        }

        checkNames(pairs, "alias")

        for (pair in pairs) {
            val alias = pair.first
            if (alias != null) {
                val localVar = ctx.nameCtx.resolveNameLocalValue(alias.str)
                if (localVar != null) {
                    throw C_Error(alias.pos, "expr_at_conflict_alias:${alias.str}", "Name conflict: '${alias.str}'")
                }
            }
        }

        val items = pairs.map { it.second.compileFromItem(ctx) }

        val entities = mutableListOf<C_AtFromItem_Entity>()
        val iterables = mutableListOf<C_AtFromItem_Iterable>()

        for (item in items) {
            when (item) {
                is C_AtFromItem_Entity -> processFromItem(item, entities, iterables)
                is C_AtFromItem_Iterable -> processFromItem(item, iterables, entities)
            }
        }

        if (entities.isEmpty()) {
            subValues.addAll(iterables.map { it.vExpr })
            if (iterables.size > 1) {
                throw C_Error(iterables[1].pos, "at:from:many_iterables:${iterables.size}", "Only one collection is allowed in at-expression")
            }
            val alias = pairs[0].first
            return C_AtFrom_Iterable(ctx, atPos, alias, iterables[0])
        }

        val cEntities = entities.mapIndexed { i, item ->
            val alias = pairs[i].first?.str ?: item.alias
            C_AtEntity(item.pos, item.entity, alias, i)
        }

        val names = mutableSetOf<String>()
        for ((i, entity) in cEntities.withIndex()) {
            if (!names.add(entity.alias)) {
                val pos = pairs[i].first?.pos ?: pairs[i].second.startPos
                throw C_Error(pos, "at_dup_alias:${entity.alias}", "Duplicate entity alias: ${entity.alias}")
            }
        }

        return C_AtFrom_Entities(ctx, cEntities)
    }

    private fun <T: C_AtFromItem> processFromItem(item: T, targets: MutableList<T>, opposites: List<*>) {
        if (opposites.isNotEmpty()) {
            throw C_Error(item.pos, "at:from:mix_entity_iterable", "Cannot mix entities and collections in at-expression")
        }
        targets.add(item)
    }

    private fun checkNames(pairs: List<Pair<S_Name?, S_Expr>>, kind: String) {
        val names = mutableSetOf<String>()
        for ((name, _) in pairs) {
            val nameStr = name?.str
            if (nameStr != null) {
                C_Errors.check(names.add(nameStr), name.pos) {
                    "expr_tuple_dupname:$nameStr" to "Duplicate $kind: '$nameStr'"
                }
            }
        }
    }
}

class S_IfExpr(pos: S_Pos, val cond: S_Expr, val trueExpr: S_Expr, val falseExpr: S_Expr): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val cCond = cond.compile(ctx).value()
        val (cTrue, cFalse, resFacts) = compileTrueFalse(ctx, cCond, typeHint)

        C_Types.match(R_BooleanType, cCond.type(), cond.startPos, "expr_if_cond_type", "Wrong type of condition expression")
        checkUnitType(trueExpr, cTrue)
        checkUnitType(falseExpr, cFalse)

        val trueType = cTrue.type()
        val falseType = cFalse.type()
        val resType = C_Types.commonType(trueType, falseType, startPos, "expr_if_restype", "Incompatible types of if branches")

        val vExpr = V_IfExpr(startPos, resType, cCond, cTrue, cFalse, resFacts)
        return C_VExpr(vExpr)
    }

    private fun compileTrueFalse(ctx: C_ExprContext, cCond: V_Expr, typeHint: C_TypeHint): Triple<V_Expr, V_Expr, C_ExprVarFacts> {
        val condFacts = cCond.varFacts()
        val trueFacts = condFacts.postFacts.and(condFacts.trueFacts)
        val falseFacts = condFacts.postFacts.and(condFacts.falseFacts)

        val cTrue0 = trueExpr.compileWithFacts(ctx, trueFacts, typeHint).value()
        val cFalse0 = falseExpr.compileWithFacts(ctx, falseFacts, typeHint).value()
        val (cTrue, cFalse) = C_BinOp_Common.promoteNumeric(cTrue0, cFalse0)

        val truePostFacts = trueFacts.and(cTrue.varFacts().postFacts)
        val falsePostFacts = falseFacts.and(cFalse.varFacts().postFacts)
        val resPostFacts = condFacts.postFacts.and(C_VarFacts.forBranches(ctx, listOf(truePostFacts, falsePostFacts)))
        val resFacts = C_ExprVarFacts.of(postFacts = resPostFacts)

        return Triple(cTrue, cFalse, resFacts)
    }

    private fun checkUnitType(expr: S_Expr, vExpr: V_Expr) {
        C_Utils.checkUnitType(expr.startPos, vExpr.type(), "expr_if_unit", "Expression returns nothing")
    }
}

class S_ListLiteralExpr(pos: S_Pos, val exprs: List<S_Expr>): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val values = exprs.map { it.compile(ctx).value() }
        val rExprs = values.map { it.toRExpr() }
        val rExpr = compile0(rExprs, typeHint)
        val exprFacts = C_ExprVarFacts.forSubExpressions(values)
        return V_RExpr.makeExpr(startPos, rExpr, exprFacts)
    }

    private fun compile0(rExprs: List<R_Expr>, typeHint: C_TypeHint): R_Expr {
        for ((i, rExpr) in rExprs.withIndex()) {
            C_Utils.checkUnitType(exprs[i].startPos, rExpr.type, "expr_list_unit", "Element expression returns nothing")
        }

        val hintElemType = typeHint.getListElementType()
        val rElemType = compileElementType(rExprs, hintElemType)
        val rListType = R_ListType(rElemType)
        return R_ListLiteralExpr(rListType, rExprs)
    }

    private fun compileElementType(rExprs: List<R_Expr>, hintElemType: R_Type?): R_Type {
        if (rExprs.isEmpty()) {
            return C_Errors.checkNotNull(hintElemType, startPos, "expr_list_no_type",
                    "Cannot determine the type of the list; use list<T>() syntax to specify the type")
        }

        var rType = rExprs[0].type
        for ((i, rExpr) in rExprs.withIndex()) {
            rType = C_Types.commonType(rType, rExpr.type, exprs[i].startPos, "expr_list_itemtype", "Wrong list item type")
        }

        if (hintElemType != null) {
            rType = C_Types.commonTypeOpt(rType, hintElemType) ?: rType
        }

        return rType
    }
}

class S_MapLiteralExpr(startPos: S_Pos, val entries: List<Pair<S_Expr, S_Expr>>): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val valueEntries = entries.map { (key, value) -> Pair(key.compile(ctx).value(), value.compile(ctx).value()) }
        val rEntries = valueEntries.map { (key, value) -> Pair(key.toRExpr(), value.toRExpr()) }

        val rExpr = compile0(ctx, typeHint, rEntries)

        val values = valueEntries.flatMap { (key, value) -> listOf(key, value) }
        val exprFacts = C_ExprVarFacts.forSubExpressions(values)

        return V_RExpr.makeExpr(startPos, rExpr, exprFacts)
    }

    private fun compile0(ctx: C_ExprContext, typeHint: C_TypeHint, rEntries: List<Pair<R_Expr, R_Expr>>): R_Expr {
        for ((i, rEntry) in rEntries.withIndex()) {
            val (rKey, rValue) = rEntry
            val keyExpr = entries[i].first
            val valueExpr = entries[i].second
            C_Utils.checkUnitType(keyExpr.startPos, rKey.type, "expr_map_key_unit", "Key expression returns nothing")
            C_Utils.checkUnitType(valueExpr.startPos, rValue.type, "expr_map_value_unit", "Value expression returns nothing")
            C_Utils.checkMapKeyType(ctx.nsCtx, valueExpr.startPos, rKey.type)
        }

        val hintKeyValueTypes = typeHint.getMapKeyValueTypes()
        val rKeyValueTypes = compileKeyValueTypes(rEntries, hintKeyValueTypes)
        val rMapType = R_MapType(rKeyValueTypes)
        return R_MapLiteralExpr(rMapType, rEntries)
    }

    private fun compileKeyValueTypes(rEntries: List<Pair<R_Expr, R_Expr>>, hintTypes: R_MapKeyValueTypes?): R_MapKeyValueTypes {
        if (rEntries.isEmpty()) {
            return C_Errors.checkNotNull(hintTypes, startPos, "expr_map_notype",
                    "Cannot determine type of the map; use map<K,V>() syntax to specify the type")
        }

        var rTypes = R_MapKeyValueTypes(rEntries[0].first.type, rEntries[0].second.type)

        for ((i, kv) in rEntries.withIndex()) {
            val (rKey, rValue) = kv
            val rKeyType = C_Types.commonType(rTypes.key, rKey.type, entries[i].first.startPos, "expr_map_keytype",
                    "Wrong map entry key type")
            val rValueType = C_Types.commonType(rTypes.value, rValue.type, entries[i].second.startPos, "expr_map_valuetype",
                    "Wrong map entry value type")
            rTypes = R_MapKeyValueTypes(rKeyType, rValueType)
        }

        if (hintTypes != null) {
            rTypes = C_Types.commonTypesOpt(rTypes, hintTypes) ?: rTypes
        }

        return rTypes
    }
}

sealed class S_CollectionExpr(pos: S_Pos, val type: S_Type?, val args: List<S_Expr>?, val colType: String): S_Expr(pos) {
    abstract fun elementTypeFromTypeHint(typeHint: C_TypeHint): R_Type?
    abstract fun makeType(rElementType: R_Type): R_Type
    abstract fun makeExpr(ctx: C_ExprContext, rType: R_Type, rArg: R_Expr?): R_Expr

    open fun checkType(ctx: C_ExprContext, rType: R_Type) {
    }

    final override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        if (args == null) {
            return compileNamespace(ctx)
        } else {
            return compileConstructor(ctx, typeHint, args)
        }
    }

    private fun compileNamespace(ctx: C_ExprContext): C_Expr {
        val rElementTypeOpt = compileType(ctx)
        val rElementType = requireType(rElementTypeOpt)
        val rType = makeType(rElementType)
        return C_TypeExpr(startPos, rType)
    }

    private fun compileConstructor(ctx: C_ExprContext, typeHint: C_TypeHint, args: List<S_Expr>): C_Expr {
        val values = args.map { it.compile(ctx).value() }
        val rArgs = values.map { it.toRExpr() }
        val rExpr = compileConstructor0(ctx, typeHint, rArgs)
        val exprFacts = C_ExprVarFacts.forSubExpressions(values)
        return V_RExpr.makeExpr(startPos, rExpr, exprFacts)
    }

    private fun compileConstructor0(ctx: C_ExprContext, typeHint: C_TypeHint, rArgs: List<R_Expr>): R_Expr {
        val rType = compileType(ctx)
        if (rArgs.size == 0) {
            return compileConstructorNoArgs(ctx, typeHint, rType)
        } else if (rArgs.size == 1) {
            val rArg = rArgs[0]
            return compileConstructorOneArg(ctx, rType, rArg)
        } else {
            throw C_Error(startPos, "expr_${colType}_argcnt:${rArgs.size}",
                    "Wrong number of arguments for $colType<>: ${rArgs.size}")
        }
    }

    private fun compileConstructorNoArgs(ctx: C_ExprContext, typeHint: C_TypeHint, rType: R_Type?): R_Expr {
        val elemType = rType ?: elementTypeFromTypeHint(typeHint)
        val rTypeReq = requireType(elemType)
        return makeExpr(ctx, rTypeReq, null)
    }

    private fun compileConstructorOneArg(ctx: C_ExprContext, rType: R_Type?, rArg: R_Expr): R_Expr {
        val rArgType = rArg.type
        if (rArgType !is R_CollectionType) {
            throw C_Error(startPos, "expr_${colType}_badtype:$rArgType",
                    "Wrong argument type for $colType<>: ${rArgType.toStrictString()}")
        }

        val rElementType = checkElementType(
                startPos,
                rType,
                rArgType.elementType,
                "expr_${colType}_typemiss",
                "Element type mismatch for $colType<>"
        )

        if (rType == null) {
            checkType(ctx, rArgType.elementType)
        }

        return makeExpr(ctx, rElementType, rArg)
    }

    private fun compileType(ctx: C_ExprContext): R_Type? {
        val rType = type?.compile(ctx)
        if (rType != null) {
            checkType(ctx, rType)
        }
        return rType
    }

    private fun requireType(rType: R_Type?): R_Type {
        return C_Errors.checkNotNull(rType, startPos) {
            "expr_${colType}_notype" to "Element type not specified for $colType"
        }
    }

    companion object {
        fun checkElementType(pos: S_Pos, declaredType: R_Type?, argumentType: R_Type, errCode: String, errMsg: String): R_Type {
            if (declaredType == null) {
                return argumentType
            }

            C_Errors.check(declaredType.isAssignableFrom(argumentType), pos) {
                    "$errCode:${declaredType.toStrictString()}:${argumentType.toStrictString()}" to
                    "$errMsg: ${argumentType.toStrictString()} instead of ${declaredType.toStrictString()}"
            }

            return declaredType
        }
    }
}

class S_ListExpr(pos: S_Pos, type: S_Type?, args: List<S_Expr>?): S_CollectionExpr(pos, type, args, "list") {
    override fun elementTypeFromTypeHint(typeHint: C_TypeHint) = typeHint.getListElementType()
    override fun makeType(rElementType: R_Type) = R_ListType(rElementType)

    override fun makeExpr(ctx: C_ExprContext, rType: R_Type, rArg: R_Expr?): R_Expr {
        val rListType = R_ListType(rType)
        return R_ListExpr(rListType, rArg)
    }
}

class S_SetExpr(pos: S_Pos, type: S_Type?, args: List<S_Expr>?): S_CollectionExpr(pos, type, args, "set") {
    override fun checkType(ctx: C_ExprContext, rType: R_Type) {
        C_Utils.checkSetElementType(ctx.nsCtx, startPos, rType)
    }

    override fun elementTypeFromTypeHint(typeHint: C_TypeHint) = typeHint.getSetElementType()
    override fun makeType(rElementType: R_Type) = R_SetType(rElementType)

    override fun makeExpr(ctx: C_ExprContext, rType: R_Type, rArg: R_Expr?): R_Expr {
        val rSetType = R_SetType(rType)
        return R_SetExpr(rSetType, rArg)
    }
}

class S_MapExpr(pos: S_Pos, val keyValueTypes: Pair<S_Type, S_Type>?, val args: List<S_Expr>?): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        if (args == null) {
            return compileNamespace(ctx)
        } else {
            return compileConstructor(ctx, typeHint, args)
        }
    }

    private fun compileNamespace(ctx: C_ExprContext): C_Expr {
        val rKeyValueTypeOpt = compileTypes(ctx)
        val rKeyValueTypes = requireTypes(rKeyValueTypeOpt)
        val rType = R_MapType(rKeyValueTypes)
        return C_TypeExpr(startPos, rType)
    }

    private fun compileConstructor(ctx: C_ExprContext, typeHint: C_TypeHint, args: List<S_Expr>): C_Expr {
        val values = args.map { it.compile(ctx).value() }
        val rArgs = values.map { it.toRExpr() }
        val rExpr = compileConstructor0(ctx, typeHint, rArgs)
        val exprFacts = C_ExprVarFacts.forSubExpressions(values)
        return V_RExpr.makeExpr(startPos, rExpr, exprFacts)
    }

    private fun compileConstructor0(ctx: C_ExprContext, typeHint: C_TypeHint, rArgs: List<R_Expr>): R_Expr {
        val rKeyValueTypes = compileTypes(ctx)
        if (rArgs.size == 0) {
            return compileConstructorNoArgs(rKeyValueTypes, typeHint)
        } else if (rArgs.size == 1) {
            val rArg = rArgs[0]
            return compileConstructorOneArg(rKeyValueTypes, rArg)
        } else {
            throw C_Error(startPos, "expr_map_argcnt:${rArgs.size}", "Wrong number of arguments for map<>: ${rArgs.size}")
        }
    }

    private fun compileConstructorNoArgs(rKeyValueType: R_MapKeyValueTypes?, typeHint: C_TypeHint): R_Expr {
        val hintKeyValueTypes = typeHint.getMapKeyValueTypes()
        val rKeyValueTypes = requireTypes(rKeyValueType ?: hintKeyValueTypes)
        val rMapType = R_MapType(rKeyValueTypes)
        return R_MapExpr(rMapType, null)
    }

    private fun compileConstructorOneArg(rKeyValueTypes: R_MapKeyValueTypes?, rArg: R_Expr): R_Expr {
        val rArgType = rArg.type

        if (rArgType !is R_MapType) {
            throw C_Error(startPos, "expr_map_badtype:${rArgType.toStrictString()}",
                    "Wrong argument type for map<>: ${rArgType.toStrictString()}")
        }

        val rActualKeyType = S_CollectionExpr.checkElementType(
                startPos,
                rKeyValueTypes?.key,
                rArgType.keyType,
                "expr_map_key_typemiss",
                "Key type mismatch for map<>"
        )

        val rActualValueType = S_CollectionExpr.checkElementType(
                startPos,
                rKeyValueTypes?.value,
                rArgType.valueType,
                "expr_map_value_typemiss",
                "Value type mismatch for map<>"
        )

        val rMapType = R_MapType(R_MapKeyValueTypes(rActualKeyType, rActualValueType))
        return R_MapExpr(rMapType, rArg)
    }

    private fun compileTypes(ctx: C_ExprContext): R_MapKeyValueTypes? {
        if (keyValueTypes == null) {
            return null
        }

        val rKeyType = keyValueTypes.first.compile(ctx)
        val rValueType = keyValueTypes.second.compile(ctx)
        C_Utils.checkMapKeyType(ctx.nsCtx, startPos, rKeyType)

        return R_MapKeyValueTypes(rKeyType, rValueType)
    }

    private fun requireTypes(rKeyValueTypes: R_MapKeyValueTypes?): R_MapKeyValueTypes {
        return C_Errors.checkNotNull(rKeyValueTypes, startPos, "expr_map_notype", "Key/value types not specified for map")
    }
}

class S_StructOrCallExpr(val base: S_Expr, val args: List<S_NameExprPair>): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val cBase = base.compile(ctx)
        return cBase.call(ctx, base.startPos, args)
    }
}

class S_TypeExpr(val type: S_Type): S_Expr(type.pos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val rType = type.compile(ctx)
        return C_TypeExpr(type.pos, rType)
    }
}
