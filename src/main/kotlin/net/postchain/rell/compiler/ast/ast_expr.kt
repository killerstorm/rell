/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.toImmSet

abstract class S_Expr(val startPos: S_Pos) {
    abstract fun compile(ctx: C_ExprContext, typeHint: C_TypeHint = C_TypeHint.NONE): C_Expr

    fun compileOpt(ctx: C_ExprContext, typeHint: C_TypeHint = C_TypeHint.NONE): C_Expr? {
        return ctx.msgCtx.consumeError { compile(ctx, typeHint) }
    }

    fun compileSafe(ctx: C_ExprContext, typeHint: C_TypeHint = C_TypeHint.NONE): C_Expr {
        return compileOpt(ctx, typeHint) ?: C_Utils.errorExpr(ctx, startPos)
    }

    fun compileWithFacts(ctx: C_ExprContext, facts: C_VarFacts, typeHint: C_TypeHint = C_TypeHint.NONE): C_Expr {
        val factsCtx = ctx.updateFacts(facts)
        return compile(factsCtx, typeHint)
    }

    fun compile(ctx: C_StmtContext, typeHint: C_TypeHint = C_TypeHint.NONE) = compile(ctx.exprCtx, typeHint)
    fun compileOpt(ctx: C_StmtContext, typeHint: C_TypeHint = C_TypeHint.NONE) = compileOpt(ctx.exprCtx, typeHint)

    open fun compileFrom(ctx: C_ExprContext, fromCtx: C_AtFromContext, subValues: MutableList<V_Expr>): C_AtFrom {
        val item = compileFromItem(ctx)
        return when (item) {
            is C_AtFromItem_Entity -> {
                val atEntityId = ctx.appCtx.nextAtEntityId(fromCtx.atExprId)
                val cEntity = C_AtEntity(item.pos, item.entity, item.alias.str, false, atEntityId)
                C_AtFrom_Entities(ctx, fromCtx, listOf(cEntity))
            }
            is C_AtFromItem_Iterable -> {
                subValues.add(item.vExpr)
                C_AtFrom_Iterable(ctx, fromCtx, null, item)
            }
        }
    }

    open fun compileFromItem(ctx: C_ExprContext): C_AtFromItem {
        val cExpr = compileSafe(ctx)
        val vExpr = cExpr.value()

        val type = vExpr.type
        val cIterator = C_ForIterator.compile(ctx, type, false)

        return if (cIterator == null) {
            val s = type.toStrictString()
            ctx.msgCtx.error(startPos, "at:from:bad_type:$s", "Invalid type for at-expression: $s")
            C_AtFromItem_Iterable(startPos, vExpr, type, R_ForIterator_Collection)
        } else {
            C_AtFromItem_Iterable(startPos, vExpr, cIterator.itemType, cIterator.rIterator)
        }
    }

    open fun compileNestedAt(ctx: C_ExprContext, parentAtCtx: C_AtContext): C_Expr = compileSafe(ctx)

    open fun asName(): S_Name? = null
    open fun constantValue(): Rt_Value? = null
}

sealed class S_LiteralExpr(pos: S_Pos): S_Expr(pos) {
    abstract fun value(): Rt_Value

    final override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val v = value()
        val vExpr = V_ConstantValueExpr(ctx, startPos, v)
        return C_VExpr(vExpr)
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

        val baseType = vBase.type
        val effectiveType = C_Types.removeNullable(baseType)

        val kind = compileSubscriptKind(ctx, effectiveType)
        if (kind == null) {
            return C_Utils.errorExpr(ctx, opPos)
        }

        C_Errors.check(ctx.msgCtx, baseType !is R_NullableType, opPos, "expr_subscript_null", "Cannot apply '[]' on nullable value")
        C_Types.match(kind.keyType, vExpr.type, expr.startPos, "expr_subscript_keytype", "Invalid subscript key type")

        val vResExpr = kind.compile(ctx, vBase, vExpr)
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
        val indexZ = C_Utils.evaluate(expr.startPos) {
            val value = vExpr.constantValue(V_ConstantValueEvalContext())
            value?.asInteger()
        }

        val index = C_Errors.checkNotNull(indexZ, expr.startPos, "expr_subscript:tuple:no_const",
                "Subscript key for a tuple must be a constant value, not an expression")

        val fields = baseType.fields
        C_Errors.check(index >= 0 && index < fields.size, expr.startPos) {
            "expr_subscript:tuple:index:$index:${fields.size}" to "Index out of bounds, must be from 0 to ${fields.size - 1}"
        }

        return index.toInt()
    }

    private inner abstract class Subscript(val keyType: R_Type) {
        abstract fun compile(ctx: C_ExprContext, base: V_Expr, key: V_Expr): V_Expr
    }

    private inner class Subscript_Common(keyType: R_Type, val kind: V_CommonSubscriptKind): Subscript(keyType) {
        override fun compile(ctx: C_ExprContext, base: V_Expr, key: V_Expr): V_Expr {
            return V_CommonSubscriptExpr(ctx, startPos, base, key, kind)
        }
    }

    private inner class Subscript_Tuple(private val baseType: R_TupleType): Subscript(R_IntegerType) {
        override fun compile(ctx: C_ExprContext, base: V_Expr, key: V_Expr): V_Expr {
            val index = compileTuple0(key, baseType)
            val resType = baseType.fields[index].type
            return V_TupleSubscriptExpr(ctx, startPos, base, V_TupleSubscriptKind_Simple, resType, index)
        }
    }

    private inner class Subscript_VirtualTuple(private val baseType: R_VirtualTupleType): Subscript(R_IntegerType) {
        override fun compile(ctx: C_ExprContext, base: V_Expr, key: V_Expr): V_Expr {
            val index = compileTuple0(key, baseType.innerType)
            val fieldType = baseType.innerType.fields[index].type
            val resType = S_VirtualType.virtualMemberType(fieldType)
            return V_TupleSubscriptExpr(ctx, startPos, base, V_TupleSubscriptKind_Virtual, resType, index)
        }
    }
}

class S_CreateExpr(pos: S_Pos, val entityName: List<S_Name>, val args: List<S_CallArgument>): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        ctx.checkDbUpdateAllowed(startPos)

        val entity = ctx.nsCtx.getEntity(entityName)
        val cArgs = C_CallArgument.compileAttributes(ctx, args, entity.attributes)

        var vExpr = compileStruct(ctx, entity, cArgs, entity.mirrorStructs.immutable)
        if (vExpr == null) {
            vExpr = compileStruct(ctx, entity, cArgs, entity.mirrorStructs.mutable)
        }
        if (vExpr == null) {
            vExpr = compileRegular(ctx, entity, cArgs)
        }

        return C_VExpr(vExpr)
    }

    private fun compileRegular(ctx: C_ExprContext, entity: R_EntityDefinition, callArgs: List<C_CallArgument>): V_Expr {
        val createCtx = C_CreateContext(ctx, entity.initFrameGetter, startPos.toFilePos())

        val attrArgs = C_CallArgument.toAttrArguments(ctx, callArgs, C_CodeMsg("create_expr", "create expression"))
        val attrs = C_AttributeResolver.resolveCreate(createCtx, entity.attributes, attrArgs, startPos)

        C_Errors.check(entity.flags.canCreate, startPos) {
            val entityNameStr = C_Utils.nameStr(entityName)
            "expr_create_cant:$entityNameStr" to "Not allowed to create instances of entity '$entityNameStr'"
        }

        return V_RegularCreateExpr(ctx, startPos, entity, attrs)
    }

    private fun compileStruct(
            ctx: C_ExprContext,
            entity: R_EntityDefinition,
            args: List<C_CallArgument>,
            struct: R_Struct
    ): V_Expr? {
        if (args.size != 1 || args[0].name != null) return null

        val arg = args[0]

        return when (arg.value) {
            is C_CallArgumentValue_Wildcard -> null
            is C_CallArgumentValue_Expr -> {
                val vExpr = arg.value.vExpr
                val structType = struct.type
                if (!structType.isAssignableFrom(vExpr.type)) null else {
                    V_StructCreateExpr(ctx, startPos, entity, structType, vExpr)
                }
            }
        }
    }
}

class S_ParenthesesExpr(startPos: S_Pos, val expr: S_Expr): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint) = expr.compile(ctx, typeHint)
    override fun compileNestedAt(ctx: C_ExprContext, parentAtCtx: C_AtContext) = expr.compileNestedAt(ctx, parentAtCtx)
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
                is S_TupleExprField_NameColonExpr -> {
                    ctx.msgCtx.error(it.colonPos, "tuple_name_colon_expr:${it.name}", "Syntax error")
                    S_NameOptValue(it.name, it.expr)
                }
                is S_TupleExprField_NameEqExpr -> S_NameOptValue(it.name, it.expr)
                is S_TupleExprField_Expr -> S_NameOptValue(null, it.expr)
            }
        }

        checkNameConflicts(ctx, pairs, "field")

        val vExprs = pairs.mapIndexed { index, (_, expr) ->
            val fieldTypeHint = typeHint.getTupleFieldHint(index)
            expr.compileSafe(ctx, fieldTypeHint).value()
        }

        val vExpr = compile0(ctx, pairs, vExprs)
        return C_VExpr(vExpr)
    }

    private fun compile0(ctx: C_ExprContext, pairs: List<S_NameOptValue<S_Expr>>, vExprs: List<V_Expr>): V_Expr {
        for ((i, vExpr) in vExprs.withIndex()) {
            C_Utils.checkUnitType(pairs[i].value.startPos, vExpr.type, "expr_tuple_unit", "Type of expression is unit")
        }

        val fields = vExprs.mapIndexed { i, vExpr -> R_TupleField(pairs[i].name?.str, vExpr.type) }
        val type = R_TupleType(fields)
        return V_TupleExpr(ctx, startPos, type, vExprs)
    }

    override fun compileFrom(ctx: C_ExprContext, fromCtx: C_AtFromContext, subValues: MutableList<V_Expr>): C_AtFrom {
        val pairs = fields.map {
            when (it) {
                is S_TupleExprField_NameColonExpr -> Pair(it.name, it.expr)
                is S_TupleExprField_NameEqExpr -> {
                    ctx.msgCtx.error(it.eqPos, "expr:at:from:tuple_name_eq_expr:${it.name}", "Syntax error")
                    Pair(it.name, it.expr)
                }
                is S_TupleExprField_Expr -> Pair(null, it.expr)
            }
        }

        val items = pairs.map { it.second.compileFromItem(ctx) }

        val entities = mutableListOf<C_AtFromItem_Entity>()
        val iterables = mutableListOf<C_AtFromItem_Iterable>()

        for (item in items) {
            when (item) {
                is C_AtFromItem_Entity -> processFromItem(ctx, item, entities, iterables)
                is C_AtFromItem_Iterable -> processFromItem(ctx, item, iterables, entities)
            }
        }

        if (entities.isEmpty()) {
            subValues.addAll(iterables.map { it.vExpr })
            if (iterables.size > 1 && iterables.any { it.vExpr.type.isNotError() }) {
                ctx.msgCtx.error(iterables[1].pos, "at:from:many_iterables:${iterables.size}",
                        "Only one collection is allowed in at-expression")
            }
            val alias = pairs[0].first
            return C_AtFrom_Iterable(ctx, fromCtx, alias, iterables[0])
        }

        val cEntities = entities.mapIndexed { i, item ->
            val explicitAlias = pairs[i].first
            val alias = explicitAlias ?: item.alias
            val atEntityId = ctx.appCtx.nextAtEntityId(fromCtx.atExprId)
            C_AtEntity(item.pos, item.entity, alias.str, explicitAlias != null, atEntityId)
        }

        return C_AtFrom_Entities(ctx, fromCtx, cEntities)
    }

    private fun <T: C_AtFromItem> processFromItem(ctx: C_ExprContext, item: T, targets: MutableList<T>, opposites: List<*>) {
        if (opposites.isNotEmpty()) {
            ctx.msgCtx.error(item.pos, "at:from:mix_entity_iterable", "Cannot mix entities and collections in at-expression")
        }
        targets.add(item)
    }

    private fun checkNameConflicts(ctx: C_ExprContext, pairs: List<S_NameOptValue<S_Expr>>, kind: String): Set<String> {
        val names = mutableSetOf<String>()
        val dups = mutableSetOf<String>()

        for ((name, _) in pairs) {
            val nameStr = name?.str
            if (nameStr != null) {
                if (!names.add(nameStr)) {
                    ctx.msgCtx.error(name.pos, "expr_tuple_dupname:$nameStr", "Duplicate $kind: '$nameStr'")
                    dups.add(nameStr)
                }
            }
        }

        return dups.toImmSet()
    }
}

class S_IfExpr(pos: S_Pos, val cond: S_Expr, val trueExpr: S_Expr, val falseExpr: S_Expr): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val cCond = cond.compile(ctx).value()
        val (cTrue, cFalse, resFacts) = compileTrueFalse(ctx, cCond, typeHint)

        C_Types.match(R_BooleanType, cCond.type, cond.startPos, "expr_if_cond_type", "Wrong type of condition expression")
        checkUnitType(trueExpr, cTrue)
        checkUnitType(falseExpr, cFalse)

        val trueType = cTrue.type
        val falseType = cFalse.type
        val resType = C_Types.commonType(trueType, falseType, startPos, "expr_if_restype", "Incompatible types of if branches")

        val vExpr = V_IfExpr(ctx, startPos, resType, cCond, cTrue, cFalse, resFacts)
        return C_VExpr(vExpr)
    }

    private fun compileTrueFalse(ctx: C_ExprContext, cCond: V_Expr, typeHint: C_TypeHint): Triple<V_Expr, V_Expr, C_ExprVarFacts> {
        val condFacts = cCond.varFacts
        val trueFacts = condFacts.postFacts.and(condFacts.trueFacts)
        val falseFacts = condFacts.postFacts.and(condFacts.falseFacts)

        val cTrue0 = trueExpr.compileWithFacts(ctx, trueFacts, typeHint).value()
        val cFalse0 = falseExpr.compileWithFacts(ctx, falseFacts, typeHint).value()
        val (cTrue, cFalse) = C_BinOp_Common.promoteNumeric(ctx, cTrue0, cFalse0)

        val truePostFacts = trueFacts.and(cTrue.varFacts.postFacts)
        val falsePostFacts = falseFacts.and(cFalse.varFacts.postFacts)
        val resPostFacts = condFacts.postFacts.and(C_VarFacts.forBranches(ctx, listOf(truePostFacts, falsePostFacts)))
        val resFacts = C_ExprVarFacts.of(postFacts = resPostFacts)

        return Triple(cTrue, cFalse, resFacts)
    }

    private fun checkUnitType(expr: S_Expr, vExpr: V_Expr) {
        C_Utils.checkUnitType(expr.startPos, vExpr.type, "expr_if_unit", "Expression returns nothing")
    }
}

class S_ListLiteralExpr(pos: S_Pos, val exprs: List<S_Expr>): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val vExprs = exprs.map { it.compile(ctx).value() }
        val listType = compileType(vExprs, typeHint)
        val vExpr = V_ListLiteralExpr(ctx, startPos, vExprs, listType)
        return C_VExpr(vExpr)
    }

    private fun compileType(vExprs: List<V_Expr>, typeHint: C_TypeHint): R_ListType {
        for (vExpr in vExprs) {
            C_Utils.checkUnitType(vExpr.pos, vExpr.type, "expr_list_unit", "Element expression returns nothing")
        }

        val hintElemType = typeHint.getListElementType()
        val rElemType = compileElementType(vExprs, hintElemType)
        return R_ListType(rElemType)
    }

    private fun compileElementType(vExprs: List<V_Expr>, hintElemType: R_Type?): R_Type {
        if (vExprs.isEmpty()) {
            return C_Errors.checkNotNull(hintElemType, startPos, "expr_list_no_type",
                    "Cannot determine the type of the list; use list<T>() syntax to specify the type")
        }

        var rType = vExprs[0].type
        for ((i, vExpr) in vExprs.withIndex()) {
            rType = C_Types.commonType(rType, vExpr.type, exprs[i].startPos, "expr_list_itemtype", "Wrong list item type")
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
        val mapType = compileType(ctx, typeHint, valueEntries)
        val vExpr = V_MapLiteralExpr(ctx, startPos, valueEntries, mapType)
        return C_VExpr(vExpr)
    }

    private fun compileType(ctx: C_ExprContext, typeHint: C_TypeHint, vEntries: List<Pair<V_Expr, V_Expr>>): R_MapType {
        for ((i, vEntry) in vEntries.withIndex()) {
            val keyType = vEntry.first.type
            val valueType = vEntry.second.type
            val keyExpr = entries[i].first
            val valueExpr = entries[i].second
            C_Utils.checkUnitType(keyExpr.startPos, keyType, "expr_map_key_unit", "Key expression returns nothing")
            C_Utils.checkUnitType(valueExpr.startPos, valueType, "expr_map_value_unit", "Value expression returns nothing")
            C_Utils.checkMapKeyType(ctx.nsCtx, valueExpr.startPos, keyType)
        }

        val hintKeyValueTypes = typeHint.getMapKeyValueTypes()
        val rKeyValueTypes = compileKeyValueTypes(vEntries, hintKeyValueTypes)
        return R_MapType(rKeyValueTypes)
    }

    private fun compileKeyValueTypes(vEntries: List<Pair<V_Expr, V_Expr>>, hintTypes: R_MapKeyValueTypes?): R_MapKeyValueTypes {
        if (vEntries.isEmpty()) {
            return C_Errors.checkNotNull(hintTypes, startPos, "expr_map_notype",
                    "Cannot determine type of the map; use map<K,V>() syntax to specify the type")
        }

        var rTypes = R_MapKeyValueTypes(vEntries[0].first.type, vEntries[0].second.type)

        for ((i, kv) in vEntries.withIndex()) {
            val (vKey, vValue) = kv
            val rKeyType = C_Types.commonType(rTypes.key, vKey.type, entries[i].first.startPos, "expr_map_keytype",
                    "Wrong map entry key type")
            val rValueType = C_Types.commonType(rTypes.value, vValue.type, entries[i].second.startPos, "expr_map_valuetype",
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
    abstract fun makeKind(rElementType: R_Type): R_CollectionKind

    fun makeExpr(ctx: C_ExprContext, rElementType: R_Type, vArg: V_Expr?): V_Expr {
        val kind = makeKind(rElementType)
        return V_CollectionConstructorExpr(ctx, startPos, kind, vArg)
    }

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
        val rType = makeKind(rElementType).type
        return C_TypeExpr(startPos, rType)
    }

    private fun compileConstructor(ctx: C_ExprContext, typeHint: C_TypeHint, args: List<S_Expr>): C_Expr {
        val vArgs = args.map { it.compile(ctx).value() }
        val vExpr = compileConstructor0(ctx, typeHint, vArgs)
        return C_VExpr(vExpr)
    }

    private fun compileConstructor0(ctx: C_ExprContext, typeHint: C_TypeHint, vArgs: List<V_Expr>): V_Expr {
        val rType = compileType(ctx)
        if (vArgs.size == 0) {
            return compileConstructorNoArgs(ctx, typeHint, rType)
        } else if (vArgs.size == 1) {
            val vArg = vArgs[0]
            return compileConstructorOneArg(ctx, rType, vArg)
        } else {
            throw C_Error.more(startPos, "expr_${colType}_argcnt:${vArgs.size}",
                    "Wrong number of arguments for $colType<>: ${vArgs.size}")
        }
    }

    private fun compileConstructorNoArgs(
            ctx: C_ExprContext,
            typeHint: C_TypeHint,
            rType: R_Type?
    ): V_Expr {
        val elemType = rType ?: elementTypeFromTypeHint(typeHint)
        val rTypeReq = requireType(elemType)
        return makeExpr(ctx, rTypeReq, null)
    }

    private fun compileConstructorOneArg(
            ctx: C_ExprContext,
            rType: R_Type?,
            vArg: V_Expr
    ): V_Expr {
        val rArgType = vArg.type
        if (rArgType !is R_CollectionType) {
            throw C_Error.more(startPos, "expr_${colType}_badtype:$rArgType",
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

        return makeExpr(ctx, rElementType, vArg)
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
    override fun makeKind(rElementType: R_Type) = R_CollectionKind_List(R_ListType(rElementType))
}

class S_SetExpr(pos: S_Pos, type: S_Type?, args: List<S_Expr>?): S_CollectionExpr(pos, type, args, "set") {
    override fun checkType(ctx: C_ExprContext, rType: R_Type) {
        C_Utils.checkSetElementType(ctx.nsCtx, startPos, rType)
    }

    override fun elementTypeFromTypeHint(typeHint: C_TypeHint) = typeHint.getSetElementType()
    override fun makeKind(rElementType: R_Type) = R_CollectionKind_Set(R_SetType(rElementType))
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
        val vArgs = args.map { it.compile(ctx).value() }

        val rKeyValueTypes = compileTypes(ctx)

        val vExpr = if (vArgs.isEmpty()) {
            val rMapType = compileConstructorMapTypeNoArgs(rKeyValueTypes, typeHint)
            V_MapConstructorExpr(ctx, startPos, rMapType, null)
        } else if (vArgs.size == 1) {
            val vArg = vArgs[0]
            val rArgType = vArg.type
            val rMapType = compileConstructorMapTypeOneArg(rKeyValueTypes, rArgType)
            V_MapConstructorExpr(ctx, startPos, rMapType, vArg)
        } else {
            throw C_Error.more(startPos, "expr_map_argcnt:${vArgs.size}", "Wrong number of arguments for map<>: ${vArgs.size}")
        }

        return C_VExpr(vExpr)
    }

    private fun compileConstructorMapTypeNoArgs(rKeyValueType: R_MapKeyValueTypes?, typeHint: C_TypeHint): R_Type {
        val hintKeyValueTypes = typeHint.getMapKeyValueTypes()
        val rKeyValueTypes = requireTypes(rKeyValueType ?: hintKeyValueTypes)
        return R_MapType(rKeyValueTypes)
    }

    private fun compileConstructorMapTypeOneArg(rKeyValueTypes: R_MapKeyValueTypes?, rArgType: R_Type): R_Type {
        if (rArgType !is R_MapType) {
            throw C_Error.more(startPos, "expr_map_badtype:${rArgType.toStrictString()}",
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

        return R_MapType(R_MapKeyValueTypes(rActualKeyType, rActualValueType))
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

sealed class S_CallArgumentValue {
    abstract fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_CallArgumentValue
}

class S_CallArgumentValue_Expr(val expr: S_Expr): S_CallArgumentValue() {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_CallArgumentValue {
        val vExpr = expr.compile(ctx, typeHint).value()
        val implicitName = expr.asName()?.str
        return C_CallArgumentValue_Expr(expr.startPos, vExpr, implicitName)
    }
}

class S_CallArgumentValue_Wildcard(val pos: S_Pos): S_CallArgumentValue() {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint) = C_CallArgumentValue_Wildcard(pos)
}

class S_CallArgument(val name: S_Name?, val value: S_CallArgumentValue)

class S_CallExpr(val base: S_Expr, val args: List<S_CallArgument>): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val cBase = base.compileSafe(ctx)
        return cBase.call(ctx, base.startPos, args, typeHint)
    }
}

class S_TypeExpr(val type: S_Type): S_Expr(type.pos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val rType = type.compile(ctx)
        return C_TypeExpr(type.pos, rType)
    }
}

class S_MirrorStructExpr(pos: S_Pos, val mutable: Boolean, val type: S_Type): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_Expr {
        val structType = type.compileMirrorStructType(ctx.nsCtx, mutable)
        structType ?: return C_Utils.errorExpr(ctx, startPos)

        val ns = ctx.globalCtx.libFunctions.makeStructNamespace(structType.struct)
        return C_MirrorStructExpr(startPos, structType.struct, ns)
    }
}
