package net.postchain.rell.parser

import net.postchain.rell.model.*

class S_NameExprPair(val name: S_Name?, val expr: S_Expr)

abstract class S_Expr(val startPos: S_Pos) {
    abstract fun compile(ctx: C_ExprContext): C_Expr
    open fun compileWhere(ctx: C_DbExprContext, idx: Int): C_Expr = compile(ctx)
    open fun asName(): S_Name? = null
}

class S_StringLiteralExpr(pos: S_Pos, val literal: String): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr = C_RExpr(startPos, R_ConstantExpr.makeText(literal))
}

class S_ByteArrayLiteralExpr(pos: S_Pos, val bytes: ByteArray): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr = C_RExpr(startPos, R_ConstantExpr.makeBytes(bytes))
}

class S_IntLiteralExpr(pos: S_Pos, val value: Long): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr = C_RExpr(startPos, R_ConstantExpr.makeInt(value))
}

class S_BooleanLiteralExpr(pos: S_Pos, val value: Boolean): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr = C_RExpr(startPos, R_ConstantExpr.makeBool(value))
}

class S_NullLiteralExpr(pos: S_Pos): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr = C_RExpr(startPos, R_ConstantExpr.makeNull())
}

class S_LookupExpr(val opPos: S_Pos, val base: S_Expr, val expr: S_Expr): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        val rBase = base.compile(ctx).toRExpr()
        val rExpr = expr.compile(ctx).toRExpr()

        val baseType = rBase.type
        val effectiveType = if (baseType is R_NullableType) baseType.valueType else baseType

        val resExpr = compile0(opPos, rBase, rExpr, effectiveType)

        if (baseType is R_NullableType) {
            throw C_Error(opPos, "expr_lookup_null", "Cannot apply '[]' on nullable value")
        }

        return resExpr
    }

    private fun compile0(opPos2: S_Pos, rBase: R_Expr, rExpr: R_Expr, baseType: R_Type): C_Expr {
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

    private fun compileList(rBase: R_Expr, rExpr: R_Expr, elementType: R_Type): C_Expr {
        matchKey(R_IntegerType, rExpr)
        val rExpr = R_ListLookupExpr(elementType, rBase, rExpr)
        return C_LookupExpr(startPos, rBase.type, rExpr, rExpr)
    }

    private fun compileMap(rBase: R_Expr, rExpr: R_Expr, keyType: R_Type, valueType: R_Type): C_Expr {
        matchKey(keyType, rExpr)
        val rExpr = R_MapLookupExpr(valueType, rBase, rExpr)
        return C_LookupExpr(startPos, rBase.type, rExpr, rExpr)
    }

    private fun compileText(rBase: R_Expr, rExpr: R_Expr): C_Expr {
        matchKey(R_IntegerType, rExpr)
        return C_LookupExpr(startPos, rBase.type, R_TextSubscriptExpr(rBase, rExpr), null)
    }

    private fun compileByteArray(rBase: R_Expr, rExpr: R_Expr): C_Expr {
        matchKey(R_IntegerType, rExpr)
        return C_LookupExpr(startPos, rBase.type, R_ByteArraySubscriptExpr(rBase, rExpr), null)
    }

    private fun matchKey(rType: R_Type, rExpr: R_Expr) {
        S_Type.match(rType, rExpr.type, expr.startPos, "expr_lookup_keytype", "Invalid lookup key type")
    }
}

class S_CreateExpr(pos: S_Pos, val className: S_Name, val exprs: List<S_NameExprPair>): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        ctx.blkCtx.entCtx.checkDbUpdateAllowed(startPos)

        val cls = ctx.blkCtx.entCtx.modCtx.getClass(className)
        val attrs = C_AttributeResolver.resolveCreate(ctx, cls.attributes, exprs, startPos)

        val type = R_ClassType(cls)
        val rExpr = R_CreateExpr(type, cls, attrs)
        return C_RExpr(startPos, rExpr)
    }
}

class S_ParenthesesExpr(startPos: S_Pos, val expr: S_Expr): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext) = expr.compile(ctx)
}

class S_TupleExpr(startPos: S_Pos, val fields: List<Pair<S_Name?, S_Expr>>): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        checkNames()
        val rExprs = fields.map { (_, expr) -> expr.compile(ctx).toRExpr() }
        val rExpr = compile0(rExprs)
        return C_RExpr(startPos, rExpr)
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

class S_ListLiteralExpr(pos: S_Pos, val exprs: List<S_Expr>): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        checkEmpty()
        val rExprs = exprs.map { it.compile(ctx).toRExpr() }
        val rExpr = compile0(rExprs)
        return C_RExpr(startPos, rExpr)
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
        val rEntries = entries.map { (key, value) -> Pair(key.compile(ctx).toRExpr(), value.compile(ctx).toRExpr()) }
        val rExpr = compile0(rEntries)
        return C_RExpr(startPos, rExpr)
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
        val rArgs = args.map { it.compile(ctx).toRExpr() }
        val rExpr = compile0(ctx, rArgs)
        return C_RExpr(startPos, rExpr)
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
                "Element type missmatch for $colType<>")

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
        val rArgs = args.map { it.compile(ctx).toRExpr() }
        val rExpr = compile0(ctx, rArgs)
        return C_RExpr(startPos, rExpr)
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
                "Key type missmatch for map<>")

        val rActualValueType = S_CollectionExpr.checkElementType(
                startPos,
                rValueType,
                rArgType.valueType,
                "expr_map_value_typemiss",
                "Value type missmatch for map<>")

        val rMapType = R_MapType(rActualKeyType, rActualValueType)
        return R_MapExpr(rMapType, rArg)
    }
}

class S_RecordOrCallExpr(val base: S_Expr, val args: List<S_NameExprPair>): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext): C_Expr {
        if (base is S_NameExpr) {
            val record = ctx.blkCtx.entCtx.modCtx.getRecordOpt(base.name.str)
            if (record != null) {
                return compileRecord(ctx, record)
            }
        }

        return compileCall(ctx)
    }

    private fun compileRecord(ctx: C_ExprContext, record: R_RecordType): C_Expr {
        val attrs = C_AttributeResolver.resolveCreate(ctx, record.attributes, args, startPos)
        val rExpr = R_RecordExpr(record, attrs)
        return C_RExpr(startPos, rExpr)
    }

    private fun compileCall(ctx: C_ExprContext): C_Expr {
        val namedArg = args.map { it.name }.filterNotNull().firstOrNull()
        if (namedArg != null) {
            val argName = namedArg.str
            throw C_Error(namedArg.pos, "expr_call_namedarg:$argName", "Named function arguments not supported")
        }

        val cBase = base.compile(ctx)

        val cArgs = args.map {
            val sArg = it.expr
            val cArg = sArg.compile(ctx)
            val type = cArg.type()
            C_Utils.checkUnitType(sArg.startPos, type, "expr_arg_unit", "Argument expression returns nothing")
            cArg
        }

        return cBase.call(base.startPos, cArgs)
    }
}
