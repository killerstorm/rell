/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.base.core.C_ForIterator
import net.postchain.rell.compiler.base.core.C_Name
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.core.C_Types
import net.postchain.rell.compiler.base.expr.*
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.compiler.vexpr.*
import net.postchain.rell.lib.C_LibMemberFunctions
import net.postchain.rell.lib.type.C_Lib_Type_Struct
import net.postchain.rell.model.*
import net.postchain.rell.model.expr.R_CollectionKind
import net.postchain.rell.model.expr.R_CollectionKind_List
import net.postchain.rell.model.expr.R_CollectionKind_Set
import net.postchain.rell.model.stmt.R_ForIterator_Collection
import net.postchain.rell.runtime.*
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.tools.api.IdeSymbolKind
import net.postchain.rell.utils.toImmSet

abstract class S_Expr(val startPos: S_Pos) {
    abstract fun compile(ctx: C_ExprContext, hint: C_ExprHint = C_ExprHint.DEFAULT): C_Expr

    fun compileOpt(ctx: C_ExprContext, hint: C_ExprHint = C_ExprHint.DEFAULT): C_Expr? {
        return ctx.msgCtx.consumeError {
            compile(ctx, hint)
        }
    }

    fun compileSafe(ctx: C_ExprContext, hint: C_ExprHint = C_ExprHint.DEFAULT): C_Expr {
        return compileOpt(ctx, hint) ?: C_ExprUtils.errorExpr(ctx, startPos)
    }

    fun compileWithFacts(ctx: C_ExprContext, facts: C_VarFacts, hint: C_ExprHint = C_ExprHint.DEFAULT): C_Expr {
        val factsCtx = ctx.updateFacts(facts)
        return compile(factsCtx, hint)
    }

    fun compile(ctx: C_StmtContext, hint: C_ExprHint = C_ExprHint.DEFAULT) = compile(ctx.exprCtx, hint)
    fun compileOpt(ctx: C_StmtContext, hint: C_ExprHint = C_ExprHint.DEFAULT) = compileOpt(ctx.exprCtx, hint)

    open fun compileFrom(ctx: C_ExprContext, fromCtx: C_AtFromContext, subValues: MutableList<V_Expr>): C_AtFrom {
        val item = compileFromItem(ctx)
        return when (item) {
            is C_AtFromItem_Entity -> {
                val atEntityId = ctx.appCtx.nextAtEntityId(fromCtx.atExprId)
                val cEntity = C_AtEntity(item.pos, item.entity, item.alias.rName, false, atEntityId)
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
        val cIterator = C_ForIterator.compile(ctx, type, true)

        return if (cIterator == null) {
            val s = type.strCode()
            ctx.msgCtx.error(startPos, "at:from:bad_type:$s", "Invalid type for at-expression: $s")
            C_AtFromItem_Iterable(startPos, vExpr, type, R_ForIterator_Collection)
        } else {
            C_AtFromItem_Iterable(startPos, vExpr, cIterator.itemType, cIterator.rIterator)
        }
    }

    open fun compileNestedAt(ctx: C_ExprContext, parentAtCtx: C_AtContext): C_Expr = compileSafe(ctx)

    protected open fun compileWhenEnum(ctx: C_ExprContext, type: R_EnumType): C_Expr = compile(ctx)

    fun compileWhenEnumOpt(ctx: C_ExprContext, type: R_EnumType): C_Expr? {
        return ctx.msgCtx.consumeError {
            compileWhenEnum(ctx, type)
        }
    }

    open fun asName(): S_Name? = null
    open fun constantValue(): Rt_Value? = null
}

sealed class S_LiteralExpr(pos: S_Pos): S_Expr(pos) {
    abstract fun value(): Rt_Value

    final override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
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
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        val vBase = base.compile(ctx).value()
        val vExpr = expr.compile(ctx).value()

        val baseType = vBase.type
        val effectiveType = C_Types.removeNullable(baseType)

        val kind = compileSubscriptKind(ctx, effectiveType)
        if (kind == null) {
            return C_ExprUtils.errorExpr(ctx, opPos)
        }

        C_Errors.check(ctx.msgCtx, baseType !is R_NullableType, opPos) {
            "expr_subscript_null" toCodeMsg "Cannot apply '[]' on nullable value"
        }

        C_Types.match(kind.keyType, vExpr.type, expr.startPos) {
            "expr_subscript_keytype" toCodeMsg "Invalid subscript key type"
        }

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
                val typeStr = baseType.strCode()
                ctx.msgCtx.error(opPos, "expr_subscript_base:$typeStr", "Operator '[]' undefined for type $typeStr")
                null
            }
        }
    }

    private fun compileTuple0(vExpr: V_Expr, baseType: R_TupleType): Int {
        val indexZ = C_ExprUtils.evaluate(expr.startPos) {
            val value = vExpr.constantValue(V_ConstantValueEvalContext())
            value?.asInteger()
        }

        val index = C_Errors.checkNotNull(indexZ, expr.startPos) {
            "expr_subscript:tuple:no_const" toCodeMsg
            "Subscript key for a tuple must be a constant value, not an expression"
        }

        val fields = baseType.fields
        C_Errors.check(index >= 0 && index < fields.size, expr.startPos) {
            "expr_subscript:tuple:index:$index:${fields.size}" toCodeMsg
            "Index out of bounds, must be from 0 to ${fields.size - 1}"
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

class S_CreateExpr(pos: S_Pos, val entityName: S_QualifiedName, val args: List<S_CallArgument>): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        ctx.checkDbUpdateAllowed(startPos)

        val entityNameHand = entityName.compile(ctx)
        val entity = ctx.nsCtx.getEntity(entityNameHand)
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
            val entityNameStr = entityName.str()
            "expr_create_cant:$entityNameStr" toCodeMsg "Not allowed to create instances of entity '$entityNameStr'"
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
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint) = expr.compile(ctx, hint)
    override fun compileNestedAt(ctx: C_ExprContext, parentAtCtx: C_AtContext) = expr.compileNestedAt(ctx, parentAtCtx)
    override fun compileFromItem(ctx: C_ExprContext) = expr.compileFromItem(ctx)
}

sealed class S_TupleExprField
class S_TupleExprField_Expr(val expr: S_Expr): S_TupleExprField()
class S_TupleExprField_NameEqExpr(val name: S_Name, val eqPos: S_Pos, val expr: S_Expr): S_TupleExprField()
class S_TupleExprField_NameColonExpr(val name: S_Name, val colonPos: S_Pos, val expr: S_Expr): S_TupleExprField()

class S_TupleExpr(startPos: S_Pos, val fields: List<S_TupleExprField>): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        val sPairs = fields.map {
            when (it) {
                is S_TupleExprField_NameColonExpr -> {
                    ctx.msgCtx.error(it.colonPos, "tuple_name_colon_expr:${it.name}", "Syntax error")
                    S_NameOptValue(it.name, it.expr)
                }
                is S_TupleExprField_NameEqExpr -> S_NameOptValue(it.name, it.expr)
                is S_TupleExprField_Expr -> S_NameOptValue(null, it.expr)
            }
        }

        val fields = sPairs.map { (name, expr) ->
            val ideInfo = IdeSymbolInfo.MEM_TUPLE_FIELD
            val cName = name?.compile(ctx, ideInfo)
            C_TupleField(cName, expr, ideInfo)
        }

        checkNameConflicts(ctx, fields)

        val vExprs = fields.mapIndexed { index, field ->
            val fieldTypeHint = hint.typeHint.getTupleFieldHint(index)
            val fieldExprHint = C_ExprHint(fieldTypeHint)
            field.sExpr.compileSafe(ctx, fieldExprHint).value()
        }

        val vExpr = compile0(ctx, fields, vExprs)
        return C_VExpr(vExpr)
    }

    private fun checkNameConflicts(ctx: C_ExprContext, fields: List<C_TupleField>): Set<String> {
        val names = mutableSetOf<String>()
        val dups = mutableSetOf<String>()

        for (field in fields) {
            val name = field.name
            if (name != null) {
                if (!names.add(name.str)) {
                    ctx.msgCtx.error(name.pos, "expr_tuple_dupname:$name", "Duplicate field: '$name'")
                    dups.add(name.str)
                }
            }
        }

        return dups.toImmSet()
    }

    private fun compile0(ctx: C_ExprContext, fields: List<C_TupleField>, vExprs: List<V_Expr>): V_Expr {
        for ((i, vExpr) in vExprs.withIndex()) {
            C_Utils.checkUnitType(fields[i].sExpr.startPos, vExpr.type) {
                "expr_tuple_unit" toCodeMsg "Type of expression is unit"
            }
        }

        val rFields = vExprs.mapIndexed { i, vExpr -> R_TupleField(fields[i].name?.rName, vExpr.type, fields[i].ideInfo) }
        val type = R_TupleType(rFields)
        return V_TupleExpr(ctx, startPos, type, vExprs)
    }

    override fun compileFrom(ctx: C_ExprContext, fromCtx: C_AtFromContext, subValues: MutableList<V_Expr>): C_AtFrom {
        val sPairs = fields.map {
            when (it) {
                is S_TupleExprField_NameColonExpr -> Pair(it.name, it.expr)
                is S_TupleExprField_NameEqExpr -> {
                    ctx.msgCtx.error(it.eqPos, "expr:at:from:tuple_name_eq_expr:${it.name}", "Syntax error")
                    Pair(it.name, it.expr)
                }
                is S_TupleExprField_Expr -> Pair(null, it.expr)
            }
        }

        val aliasIdeInfo = IdeSymbolInfo(IdeSymbolKind.LOC_AT_ALIAS)
        val pairs = sPairs.map { (name, expr) ->
            val cName = name?.compile(ctx, aliasIdeInfo)
            cName to expr
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
            val alias = pairs.first().first
            return C_AtFrom_Iterable(ctx, fromCtx, alias, iterables[0])
        }

        val cEntities = entities.mapIndexed { i, item ->
            val explicitAlias = pairs[i].first
            val alias = explicitAlias ?: item.alias
            val atEntityId = ctx.appCtx.nextAtEntityId(fromCtx.atExprId)
            C_AtEntity(item.pos, item.entity, alias.rName, explicitAlias != null, atEntityId)
        }

        return C_AtFrom_Entities(ctx, fromCtx, cEntities)
    }

    private fun <T: C_AtFromItem> processFromItem(ctx: C_ExprContext, item: T, targets: MutableList<T>, opposites: List<*>) {
        if (opposites.isNotEmpty()) {
            ctx.msgCtx.error(item.pos, "at:from:mix_entity_iterable", "Cannot mix entities and collections in at-expression")
        }
        targets.add(item)
    }

    private class C_TupleField(val name: C_Name?, val sExpr: S_Expr, val ideInfo: IdeSymbolInfo)
}

class S_IfExpr(pos: S_Pos, val cond: S_Expr, val trueExpr: S_Expr, val falseExpr: S_Expr): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        val cCond = cond.compile(ctx).value()
        val (cTrue, cFalse, resFacts) = compileTrueFalse(ctx, cCond, hint)

        C_Types.match(R_BooleanType, cCond.type, cond.startPos) {
            "expr_if_cond_type" toCodeMsg "Wrong type of condition expression"
        }

        checkUnitType(trueExpr, cTrue)
        checkUnitType(falseExpr, cFalse)

        val trueType = cTrue.type
        val falseType = cFalse.type

        val resType = C_Types.commonType(trueType, falseType, startPos) {
            "expr_if_restype" toCodeMsg "Incompatible types of if branches"
        }

        val vExpr = V_IfExpr(ctx, startPos, resType, cCond, cTrue, cFalse, resFacts)
        return C_VExpr(vExpr)
    }

    private fun compileTrueFalse(ctx: C_ExprContext, cCond: V_Expr, hint: C_ExprHint): Triple<V_Expr, V_Expr, C_ExprVarFacts> {
        val condFacts = cCond.varFacts
        val trueFacts = condFacts.postFacts.and(condFacts.trueFacts)
        val falseFacts = condFacts.postFacts.and(condFacts.falseFacts)

        val cTrue0 = trueExpr.compileWithFacts(ctx, trueFacts, hint).value()
        val cFalse0 = falseExpr.compileWithFacts(ctx, falseFacts, hint).value()
        val (cTrue, cFalse) = C_BinOp_Common.promoteNumeric(ctx, cTrue0, cFalse0)

        val truePostFacts = trueFacts.and(cTrue.varFacts.postFacts)
        val falsePostFacts = falseFacts.and(cFalse.varFacts.postFacts)
        val resPostFacts = condFacts.postFacts.and(C_VarFacts.forBranches(ctx, listOf(truePostFacts, falsePostFacts)))
        val resFacts = C_ExprVarFacts.of(postFacts = resPostFacts)

        return Triple(cTrue, cFalse, resFacts)
    }

    private fun checkUnitType(expr: S_Expr, vExpr: V_Expr) {
        C_Utils.checkUnitType(expr.startPos, vExpr.type) { "expr_if_unit" toCodeMsg "Expression returns nothing" }
    }
}

class S_ListLiteralExpr(pos: S_Pos, val exprs: List<S_Expr>): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        val vExprs = exprs.map { it.compile(ctx).value() }
        val listType = compileType(vExprs, hint.typeHint)
        val vExpr = V_ListLiteralExpr(ctx, startPos, vExprs, listType)
        return C_VExpr(vExpr)
    }

    private fun compileType(vExprs: List<V_Expr>, typeHint: C_TypeHint): R_ListType {
        for (vExpr in vExprs) {
            C_Utils.checkUnitType(vExpr.pos, vExpr.type) { "expr_list_unit" toCodeMsg "Element expression returns nothing" }
        }

        val hintElemType = typeHint.getListElementType()
        val rElemType = compileElementType(vExprs, hintElemType)
        return R_ListType(rElemType)
    }

    private fun compileElementType(vExprs: List<V_Expr>, hintElemType: R_Type?): R_Type {
        if (vExprs.isEmpty()) {
            return C_Errors.checkNotNull(hintElemType, startPos) {
                "expr_list_no_type" toCodeMsg
                "Cannot determine the type of the list; use list<T>() syntax to specify the type"
            }
        }

        var rType = vExprs[0].type
        for ((i, vExpr) in vExprs.withIndex()) {
            rType = C_Types.commonType(rType, vExpr.type, exprs[i].startPos) {
                "expr_list_itemtype" toCodeMsg "Wrong list item type"
            }
        }

        if (hintElemType != null) {
            rType = C_Types.commonTypeOpt(rType, hintElemType) ?: rType
        }

        return rType
    }
}

class S_MapLiteralExpr(startPos: S_Pos, val entries: List<Pair<S_Expr, S_Expr>>): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        val valueEntries = entries.map { (key, value) -> Pair(key.compile(ctx).value(), value.compile(ctx).value()) }
        val mapType = compileType(ctx, hint.typeHint, valueEntries)
        val vExpr = V_MapLiteralExpr(ctx, startPos, valueEntries, mapType)
        return C_VExpr(vExpr)
    }

    private fun compileType(ctx: C_ExprContext, typeHint: C_TypeHint, vEntries: List<Pair<V_Expr, V_Expr>>): R_MapType {
        for ((i, vEntry) in vEntries.withIndex()) {
            val keyType = vEntry.first.type
            val valueType = vEntry.second.type
            val keyExpr = entries[i].first
            val valueExpr = entries[i].second
            C_Utils.checkUnitType(keyExpr.startPos, keyType) { "expr_map_key_unit" toCodeMsg "Key expression returns nothing" }
            C_Utils.checkUnitType(valueExpr.startPos, valueType) { "expr_map_value_unit" toCodeMsg "Value expression returns nothing" }
            C_Utils.checkMapKeyType(ctx.nsCtx, valueExpr.startPos, keyType)
        }

        val hintKeyValueTypes = typeHint.getMapKeyValueTypes()
        val rKeyValueTypes = compileKeyValueTypes(vEntries, hintKeyValueTypes)
        return R_MapType(rKeyValueTypes)
    }

    private fun compileKeyValueTypes(vEntries: List<Pair<V_Expr, V_Expr>>, hintTypes: R_MapKeyValueTypes?): R_MapKeyValueTypes {
        if (vEntries.isEmpty()) {
            return C_Errors.checkNotNull(hintTypes, startPos) {
                "expr_map_notype" toCodeMsg
                "Cannot determine type of the map; use map<K,V>() syntax to specify the type"
            }
        }

        var rTypes = R_MapKeyValueTypes(vEntries[0].first.type, vEntries[0].second.type)

        for ((i, kv) in vEntries.withIndex()) {
            val (vKey, vValue) = kv
            val rKeyType = C_Types.commonType(rTypes.key, vKey.type, entries[i].first.startPos) {
                "expr_map_keytype" toCodeMsg "Wrong map entry key type"
            }
            val rValueType = C_Types.commonType(rTypes.value, vValue.type, entries[i].second.startPos) {
                "expr_map_valuetype" toCodeMsg "Wrong map entry value type"
            }
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

    open fun checkType(ctx: C_ExprContext, rType: R_Type) {
    }

    final override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        if (args == null) {
            return compileNamespace(ctx)
        } else {
            return compileConstructor(ctx, hint.typeHint, args)
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
        val kind = makeKind(rTypeReq)
        return V_EmptyCollectionConstructorExpr(ctx, startPos, kind)
    }

    private fun compileConstructorOneArg(
            ctx: C_ExprContext,
            rType: R_Type?,
            vArg: V_Expr
    ): V_Expr {
        val rArgType = vArg.type
        val cIterator = C_ForIterator.compile(ctx, rArgType, false)

        if (cIterator == null) {
            throw C_Error.more(startPos, "expr_${colType}_badtype:${rArgType.strCode()}",
                    "Wrong argument type for $colType<>: ${rArgType.strCode()}")
        }

        val rElementType = checkElementType(
                startPos,
                rType,
                cIterator.itemType,
                "expr_${colType}_typemiss",
                "Element type mismatch for $colType<>"
        )

        if (rType == null) {
            checkType(ctx, cIterator.itemType)
        }

        val kind = makeKind(rElementType)
        return V_CopyCollectionConstructorExpr(ctx, startPos, kind, vArg, cIterator)
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
            "expr_${colType}_notype" toCodeMsg "Element type not specified for $colType"
        }
    }

    companion object {
        fun checkElementType(pos: S_Pos, declaredType: R_Type?, argumentType: R_Type, errCode: String, errMsg: String): R_Type {
            if (declaredType == null) {
                return argumentType
            }

            C_Errors.check(declaredType.isAssignableFrom(argumentType), pos) {
                "$errCode:${declaredType.strCode()}:${argumentType.strCode()}" toCodeMsg
                "$errMsg: ${argumentType.strCode()} instead of ${declaredType.strCode()}"
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
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        if (args == null) {
            return compileNamespace(ctx)
        } else {
            return compileConstructor(ctx, hint.typeHint, args)
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
            compileConstructorNoArgs(ctx, rKeyValueTypes, typeHint)
        } else if (vArgs.size == 1) {
            val vArg = vArgs[0]
            compileConstructorOneArg(ctx, rKeyValueTypes, vArg)
        } else {
            throw C_Error.more(startPos, "expr_map_argcnt:${vArgs.size}", "Wrong number of arguments for map<>: ${vArgs.size}")
        }

        return C_VExpr(vExpr)
    }

    private fun compileConstructorNoArgs(ctx: C_ExprContext, rKeyValueType: R_MapKeyValueTypes?, typeHint: C_TypeHint): V_Expr {
        val hintKeyValueTypes = typeHint.getMapKeyValueTypes()
        val rKeyValueTypes = requireTypes(rKeyValueType ?: hintKeyValueTypes)
        val rMapType = R_MapType(rKeyValueTypes)
        return V_EmptyMapConstructorExpr(ctx, startPos, rMapType)
    }

    private fun compileConstructorOneArg(ctx: C_ExprContext, rKeyValueTypes: R_MapKeyValueTypes?, vArg: V_Expr): V_Expr {
        val rArgType = vArg.type
        if (rArgType is R_MapType) {
            return compileConstructorOneArgMap(ctx, rKeyValueTypes, vArg, rArgType)
        }

        val cIterator = C_ForIterator.compile(ctx, rArgType, false)
        if (cIterator != null) {
            val vExpr = compileConstructorOneArgIterator(ctx, rKeyValueTypes, vArg, cIterator)
            if (vExpr != null) {
                return vExpr
            }
        }

        throw C_Error.more(startPos, "expr_map_badtype:${rArgType.strCode()}",
                "Wrong argument type for map<>: ${rArgType.strCode()}")
    }

    private fun compileConstructorOneArgMap(
            ctx: C_ExprContext,
            rKeyValueTypes: R_MapKeyValueTypes?,
            vArg: V_Expr,
            rMapType: R_MapType
    ): V_Expr {
        val resTypes = checkMapTypes(rKeyValueTypes, rMapType.keyValueTypes)
        val rResMapType = R_MapType(resTypes)
        return V_MapCopyMapConstructorExpr(ctx, startPos, rResMapType, vArg)
    }

    private fun compileConstructorOneArgIterator(
            ctx: C_ExprContext,
            rKeyValueTypes: R_MapKeyValueTypes?,
            vArg: V_Expr,
            cIterator: C_ForIterator
    ): V_Expr? {
        val itemType = cIterator.itemType
        if (itemType !is R_TupleType || itemType.fields.size != 2) {
            return null
        }

        val actualTypes = R_MapKeyValueTypes(itemType.fields[0].type, itemType.fields[1].type)
        val resTypes = checkMapTypes(rKeyValueTypes, actualTypes)

        val rResMapType = R_MapType(resTypes)
        return V_IteratorCopyMapConstructorExpr(ctx, startPos, rResMapType, vArg, cIterator)
    }

    private fun checkMapTypes(formalTypes: R_MapKeyValueTypes?, actualTypes: R_MapKeyValueTypes): R_MapKeyValueTypes {
        val rKeyType = S_CollectionExpr.checkElementType(
                startPos,
                formalTypes?.key,
                actualTypes.key,
                "expr_map_key_typemiss",
                "Key type mismatch for map<>"
        )

        val rValueType = S_CollectionExpr.checkElementType(
                startPos,
                formalTypes?.value,
                actualTypes.value,
                "expr_map_value_typemiss",
                "Value type mismatch for map<>"
        )

        return R_MapKeyValueTypes(rKeyType, rValueType)
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
        return C_Errors.checkNotNull(rKeyValueTypes, startPos) {
            "expr_map_notype" toCodeMsg "Key/value types not specified for map"
        }
    }
}

sealed class S_CallArgumentValue {
    abstract fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_CallArgumentValue
}

class S_CallArgumentValue_Expr(val expr: S_Expr): S_CallArgumentValue() {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint): C_CallArgumentValue {
        val exprHint = C_ExprHint(typeHint)
        val cExpr = expr.compile(ctx, exprHint)
        val vExpr = cExpr.value()
        val implicitName = cExpr.implicitMatchName()
        return C_CallArgumentValue_Expr(expr.startPos, vExpr, implicitName)
    }
}

class S_CallArgumentValue_Wildcard(val pos: S_Pos): S_CallArgumentValue() {
    override fun compile(ctx: C_ExprContext, typeHint: C_TypeHint) = C_CallArgumentValue_Wildcard(pos)
}

class S_CallArgument(val name: S_Name?, val value: S_CallArgumentValue) {
    fun compile(
            ctx: C_ExprContext,
            index: Int,
            positional: Boolean,
            typeHints: C_CallTypeHints,
            ideInfoProvider: C_CallArgumentIdeInfoProvider
    ): C_CallArgument {
        val nameHand = name?.compile(ctx)
        if (nameHand != null) {
            val ideInfo = ideInfoProvider.getIdeInfo(nameHand.rName)
            nameHand.setIdeInfo(ideInfo)
        }

        val cName = nameHand?.name
        val hintIndex = if (positional) index else null
        val typeHint = typeHints.getTypeHint(hintIndex, cName?.rName)
        val argValue = value.compile(ctx, typeHint)

        return C_CallArgument(index, cName, argValue)
    }
}

class S_CallExpr(val base: S_Expr, val args: List<S_CallArgument>): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        val cBase = base.compileSafe(ctx, C_ExprHint.DEFAULT_CALLABLE)
        return cBase.call(ctx, base.startPos, args, hint.typeHint)
    }
}

class S_TypeExpr(val type: S_Type): S_Expr(type.pos) {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        val rType = type.compile(ctx)
        return C_TypeExpr(type.pos, rType)
    }
}

class S_MirrorStructExpr(pos: S_Pos, val mutable: Boolean, val type: S_Type): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext, hint: C_ExprHint): C_Expr {
        val structType = type.compileMirrorStructType(ctx.nsCtx, mutable)
        structType ?: return C_ExprUtils.errorExpr(ctx, startPos)

        val ns = C_Lib_Type_Struct.getNamespace(structType.struct)
        return C_MirrorStructExpr(startPos, structType.struct, ns)
    }
}
