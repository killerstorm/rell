package net.postchain.rell.parser

import net.postchain.rell.model.*

class DbClassAttr(val cls: RAtClass, val attr: RAttrib)

class C_DbExprContext(
        val parent: C_DbExprContext?,
        val exprCtx: C_ExprContext,
        val classes: List<RAtClass>
) {
    fun findAttributesByName(name: String): List<DbClassAttr> {
        return findAttrsInChain({ it.name == name })
    }

    fun findAttributesByType(type: RType): List<DbClassAttr> {
        return findAttrsInChain({ it.type == type })
    }

    private fun findAttrsInChain(matcher: (RAttrib) -> Boolean): List<DbClassAttr> {
        val attrs = mutableListOf<DbClassAttr>()
        var ctx: C_DbExprContext? = this
        while (ctx != null) {
            ctx.findAttrInternal(matcher, attrs)
            ctx = ctx.parent
        }
        return attrs.toList()
    }

    private fun findAttrInternal(matcher: (RAttrib) -> Boolean, attrs: MutableList<DbClassAttr>): DbClassAttr? {
        //TODO take other kinds of fields into account
        //TODO fail when there is more than one match
        //TODO use a table lookup
        for (cls in classes) {
            for (attr in cls.rClass.attributes.values) {
                if (matcher(attr)) {
                    attrs.add(DbClassAttr(cls, attr))
                }
            }
        }
        return null
    }

    fun findClassByAlias(alias: String): RAtClass? {
        var ctx: C_DbExprContext? = this
        while (ctx != null) {
            //TODO use a lookup table
            for (cls in ctx.classes) {
                if (cls.alias == alias) {
                    return cls
                }
            }
            ctx = ctx.parent
        }
        return null
    }
}

class S_NameExprPair(val name: S_Name?, val expr: S_Expr)

abstract class S_Expr(val startPos: S_Pos) {
    abstract fun compile(ctx: C_ExprContext): RExpr

    open fun compileDb(ctx: C_DbExprContext): DbExpr = throw errNoDb()

    open fun compileDbWhere(ctx: C_DbExprContext, idx: Int): DbExpr = compileDb(ctx)

    open fun compileCall(ctx: C_ExprContext, args: List<RExpr>): RExpr = throw errCallNoFn(compile(ctx).type)

    open fun compileCallDb(ctx: C_DbExprContext, args: List<DbExpr>): DbExpr =
            throw errCallNoFn(compileDb(ctx).type)

    open fun compileDestination(opPos: S_Pos, ctx: C_ExprContext): RDestinationExpr {
        throw C_Utils.errBadDestination(opPos)
    }

    open fun asName(): S_Name? = null

    open fun discoverPathExpr(tailPath: List<S_Name>): Pair<S_Expr?, List<S_Name>> {
        return Pair(this, tailPath)
    }

    fun delegateCompileDb(ctx: C_DbExprContext): DbExpr = InterpretedDbExpr(compile(ctx.exprCtx))

    fun errNoDb(): C_Error = C_Error(startPos, "expr_nosql", "Expression cannot be converted to SQL")

    private fun errCallNoFn(type: RType): C_Error {
        return C_Error(startPos, "expr_call_nofn:${type.toStrictString()}",
                "Function call on type ${type.toStrictString()}")
    }
}

class S_StringLiteralExpr(pos: S_Pos, val literal: String): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): RExpr = RConstantExpr.makeText(literal)
    override fun compileDb(ctx: C_DbExprContext): DbExpr = delegateCompileDb(ctx)
}

class S_ByteALiteralExpr(pos: S_Pos, val bytes: ByteArray): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): RExpr = RConstantExpr.makeBytes(bytes)
    override fun compileDb(ctx: C_DbExprContext): DbExpr = delegateCompileDb(ctx)
}

class S_IntLiteralExpr(pos: S_Pos, val value: Long): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): RExpr = RConstantExpr.makeInt(value)
    override fun compileDb(ctx: C_DbExprContext): DbExpr = delegateCompileDb(ctx)
}

class S_BooleanLiteralExpr(pos: S_Pos, val value: Boolean): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): RExpr = RConstantExpr.makeBool(value)
    override fun compileDb(ctx: C_DbExprContext): DbExpr = delegateCompileDb(ctx)
}

class S_NullLiteralExpr(pos: S_Pos): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext) = RConstantExpr.makeNull()
    override fun compileDb(ctx: C_DbExprContext) = delegateCompileDb(ctx)
}

class S_CallExpr(val base: S_Expr, val args: List<S_Expr>): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext): RExpr {
        val rArgs = args.map {
            val rArg = it.compile(ctx)
            checkUnitType(it, rArg.type)
            rArg
        }
        return base.compileCall(ctx, rArgs)
    }

    override fun compileDb(ctx: C_DbExprContext): DbExpr {
        val dbArgs = args.map {
            val dbArg = it.compileDb(ctx)
            checkUnitType(it, dbArg.type)
            dbArg
        }
        return base.compileCallDb(ctx, dbArgs)
    }

    private fun checkUnitType(arg: S_Expr, type: RType) {
        C_Utils.checkUnitType(arg.startPos, type, "expr_arg_unit", "Argument expression returns nothing")
    }
}

class S_LookupExpr(val opPos: S_Pos, val base: S_Expr, val expr: S_Expr): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext): RExpr {
        val rBase = base.compile(ctx)
        val rExpr = expr.compile(ctx)
        return compile0(rBase, rExpr)
    }

    override fun compileDb(ctx: C_DbExprContext): DbExpr {
        val dbBase = base.compileDb(ctx)
        val dbExpr = expr.compileDb(ctx)

        if (dbBase is InterpretedDbExpr && dbExpr is InterpretedDbExpr) {
            val rResExpr = compile0(dbBase.expr, dbExpr.expr)
            return InterpretedDbExpr(rResExpr)
        }

        throw errNoDb()
    }

    private fun compile0(rBase: RExpr, rExpr: RExpr): RExpr {
        val baseType = rBase.type
        val effectiveType = if (baseType is RNullableType) baseType.valueType else baseType

        val lookup = compileDestination0(opPos, rBase, rExpr, effectiveType)
        val rResExpr = lookup.source()

        if (baseType is RNullableType) {
            throw C_Error(opPos, "expr_lookup_null", "Cannot apply '[]' on null")
        }

        return rResExpr
    }

    override fun compileDestination(opPos2: S_Pos, ctx: C_ExprContext): RDestinationExpr {
        val rBase = base.compile(ctx)
        val rExpr = expr.compile(ctx)
        val lookup = compileDestination0(opPos2, rBase, rExpr, rBase.type)
        return lookup.destination(opPos2)
    }

    private fun compileDestination0(opPos2: S_Pos, rBase: RExpr, rExpr: RExpr, baseType: RType): C_Lookup {
        if (baseType == RTextType) {
            return compileText(rBase, rExpr)
        } else if (baseType == RByteArrayType) {
            return compileByteArray(rBase, rExpr)
        } else if (baseType is RListType) {
            return compileList(rBase, rExpr, baseType.elementType)
        } else if (baseType is RMapType) {
            return compileMap(rBase, rExpr, baseType.keyType, baseType.valueType)
        }

        throw C_Error(opPos2, "expr_lookup_base:${baseType.toStrictString()}",
                "Operator '[]' undefined for type ${baseType.toStrictString()}")
    }

    private fun compileList(rBase: RExpr, rExpr: RExpr, elementType: RType): C_Lookup {
        matchKey(RIntegerType, rExpr)
        return C_Lookup_Writeable(RListLookupExpr(elementType, rBase, rExpr))
    }

    private fun compileMap(rBase: RExpr, rExpr: RExpr, keyType: RType, valueType: RType): C_Lookup {
        matchKey(keyType, rExpr)
        return C_Lookup_Writeable(RMapLookupExpr(valueType, rBase, rExpr))
    }

    private fun compileText(rBase: RExpr, rExpr: RExpr): C_Lookup {
        matchKey(RIntegerType, rExpr)
        return C_Lookup_Readable(rBase.type, RTextSubscriptExpr(rBase, rExpr))
    }

    private fun compileByteArray(rBase: RExpr, rExpr: RExpr): C_Lookup {
        matchKey(RIntegerType, rExpr)
        return C_Lookup_Readable(rBase.type, RByteArraySubscriptExpr(rBase, rExpr))
    }

    private fun matchKey(rType: RType, rExpr: RExpr) {
        S_Type.match(rType, rExpr.type, expr.startPos, "expr_lookup_keytype", "Invalid lookup key type")
    }

    private abstract class C_Lookup() {
        abstract fun source(): RExpr
        abstract fun destination(pos: S_Pos): RDestinationExpr
    }

    private class C_Lookup_Readable(val baseType: RType, val expr: RExpr): C_Lookup() {
        override fun source() = expr

        override fun destination(pos: S_Pos): RDestinationExpr {
            val type = baseType.toStrictString()
            throw C_Error(pos, "expr_lookup_unmodifiable:$type", "Value of type '$type' cannot be modified")
        }
    }

    private class C_Lookup_Writeable(val expr: RDestinationExpr): C_Lookup() {
        override fun source() = expr
        override fun destination(pos: S_Pos) = expr
    }
}

class S_CreateExpr(pos: S_Pos, val className: S_Name, val exprs: List<S_NameExprPair>): S_Expr(pos) {
    override fun compileDb(ctx: C_DbExprContext): DbExpr = delegateCompileDb(ctx)

    override fun compile(ctx: C_ExprContext): RExpr {
        ctx.entCtx.checkDbUpdateAllowed(startPos)

        val cls = ctx.entCtx.modCtx.getClass(className)
        val attrs = C_AttributeResolver.resolveCreate(ctx, cls.attributes, exprs, startPos)

        val type = RInstanceRefType(cls)
        return RCreateExpr(type, cls, attrs)
    }
}

class S_ParenthesesExpr(startPos: S_Pos, val expr: S_Expr): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext) = expr.compile(ctx)
    override fun compileDb(ctx: C_DbExprContext) = expr.compileDb(ctx)
}

class S_TupleExpr(startPos: S_Pos, val fields: List<Pair<S_Name?, S_Expr>>): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext): RExpr {
        checkNames()
        val rExprs = fields.map { (_, expr) -> expr.compile(ctx) }
        return compile0(rExprs)
    }

    override fun compileDb(ctx: C_DbExprContext): DbExpr {
        val dbExprs = fields.map { (_, expr) -> expr.compileDb(ctx) }

        val rExprs = dbExprs.map { (it as? InterpretedDbExpr)?.expr }.filterNotNull()
        if (rExprs.size == dbExprs.size) {
            val rResExpr = compile0(rExprs)
            return InterpretedDbExpr(rResExpr)
        }

        throw errNoDb()
    }

    private fun compile0(rExprs: List<RExpr>): RExpr {
        for ((i, rExpr) in rExprs.withIndex()) {
            C_Utils.checkUnitType(fields[i].second.startPos, rExpr.type, "expr_tuple_unit", "Type of expression is unit")
        }

        val fields = rExprs.mapIndexed { i, rExpr -> RTupleField(fields[i].first?.str, rExpr.type) }
        val type = RTupleType(fields)
        return RTupleExpr(type, rExprs)
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
    override fun compile(ctx: C_ExprContext): RExpr {
        checkEmpty()
        val rExprs = exprs.map { it.compile(ctx) }
        return compile0(rExprs)
    }

    override fun compileDb(ctx: C_DbExprContext): DbExpr {
        checkEmpty()

        val dbExprs = exprs.map { it.compileDb(ctx) }
        val rExprs = dbExprs.map { (it as? InterpretedDbExpr)?.expr }.filterNotNull()

        if (rExprs.size == dbExprs.size) {
            val rResExpr = compile0(rExprs)
            return InterpretedDbExpr(rResExpr)
        }

        throw errNoDb()
    }

    private fun checkEmpty() {
        if (exprs.isEmpty()) {
            throw C_Error(startPos, "expr_list_empty", "Type of empty list literal is unknown; use list<T>() instead")
        }
    }

    private fun compile0(rExprs: List<RExpr>): RExpr {
        for ((i, rExpr) in rExprs.withIndex()) {
            C_Utils.checkUnitType(exprs[i].startPos, rExpr.type, "expr_list_unit", "Element expression returns nothing")
        }

        var rType = rExprs[0].type
        for ((i, rExpr) in rExprs.subList(1, rExprs.size).withIndex()) {
            rType = S_Type.commonType(rType, rExpr.type, exprs[i].startPos, "expr_list_itemtype", "Wrong list item type")
        }

        val rListType = RListType(rType)
        return RListLiteralExpr(rListType, rExprs)
    }
}

class S_MapLiteralExpr(startPos: S_Pos, val entries: List<Pair<S_Expr, S_Expr>>): S_Expr(startPos) {
    override fun compile(ctx: C_ExprContext): RExpr {
        checkEmpty()
        val rEntries = entries.map { (key, value) -> Pair(key.compile(ctx), value.compile(ctx)) }
        return compile0(rEntries)
    }

    override fun compileDb(ctx: C_DbExprContext): DbExpr {
        checkEmpty()

        val dbEntries = entries.map { (key, value) -> Pair(key.compileDb(ctx), value.compileDb(ctx)) }

        val rEntries = dbEntries.map { (key, value) -> Pair((key as? InterpretedDbExpr)?.expr, (value as? InterpretedDbExpr)?.expr) }
                .filter{ (key, value) ->  key != null && value != null }
                .map { (key, value) -> Pair(key!!, value!!) }
        if (rEntries.size == dbEntries.size) {
            val rResExpr = compile0(rEntries)
            return InterpretedDbExpr(rResExpr)
        }

        throw errNoDb()
    }

    private fun checkEmpty() {
        if (entries.isEmpty()) {
            throw C_Error(startPos, "expr_map_empty", "Type of empty map literal is unknown; use map<K,V>() instead")
        }
    }

    private fun compile0(rEntries: List<Pair<RExpr, RExpr>>): RExpr {
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

        val rMapType = RMapType(rKeyType, rValueType)
        return RMapLiteralExpr(rMapType, rEntries)
    }
}

sealed class S_CollectionExpr(
        pos: S_Pos,
        val type: S_Type?,
        val args: List<S_Expr>,
        val colType: String
): S_Expr(pos)
{
    abstract fun makeExpr(rType: RType, rArg: RExpr?): RExpr

    open fun checkType(rType: RType) {
    }

    override fun compile(ctx: C_ExprContext): RExpr {
        val rArgs = args.map { it.compile(ctx) }
        return compile0(ctx, rArgs)
    }

    override fun compileDb(ctx: C_DbExprContext): DbExpr {
        val dbArgs = args.map { it.compileDb(ctx) }
        val rArgs = dbArgs.map { (it as? InterpretedDbExpr)?.expr }.filterNotNull()
        if (rArgs.size == dbArgs.size) {
            val rResExpr = compile0(ctx.exprCtx, rArgs)
            return InterpretedDbExpr(rResExpr)
        }
        throw errNoDb()
    }

    private fun compile0(ctx: C_ExprContext, rArgs: List<RExpr>): RExpr {
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

    private fun compileNoArgs(rType: RType?): RExpr {
        if (rType == null) {
            throw C_Error(startPos, "expr_${colType}_notype", "Element type not specified for $colType")
        }
        return makeExpr(rType, null)
    }

    private fun compileOneArg(rType: RType?, rArg: RExpr): RExpr {
        val rArgType = rArg.type
        if (rArgType !is RCollectionType) {
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
        fun checkElementType(pos: S_Pos, declaredType: RType?, argumentType: RType, errCode: String, errMsg: String): RType {
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
    override fun makeExpr(rType: RType, rArg: RExpr?): RExpr {
        val rListType = RListType(rType)
        return RListExpr(rListType, rArg)
    }
}

class S_SetExpr(pos: S_Pos, type: S_Type?, args: List<S_Expr>)
    : S_CollectionExpr(pos, type, args, "set")
{
    override fun checkType(rType: RType) {
        C_Utils.checkSetElementType(startPos, rType)
    }

    override fun makeExpr(rType: RType, rArg: RExpr?): RExpr {
        C_Utils.checkSetElementType(startPos, rType)
        val rSetType = RSetType(rType)
        return RSetExpr(rSetType, rArg)
    }
}

class S_MapExpr(
        pos: S_Pos,
        val keyValueTypes: Pair<S_Type, S_Type>?,
        val args: List<S_Expr>
): S_Expr(pos)
{
    override fun compile(ctx: C_ExprContext): RExpr {
        val rArgs = args.map { it.compile(ctx) }
        return compile0(ctx, rArgs)
    }

    override fun compileDb(ctx: C_DbExprContext): DbExpr {
        val dbArgs = args.map { it.compileDb(ctx) }
        val rArgs = dbArgs.map { (it as? InterpretedDbExpr)?.expr }.filterNotNull()
        if (rArgs.size == dbArgs.size) {
            val rResExpr = compile0(ctx.exprCtx, rArgs)
            return InterpretedDbExpr(rResExpr)
        }
        throw errNoDb()
    }

    private fun compile0(ctx: C_ExprContext, rArgs: List<RExpr>): RExpr {
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

    private fun compileNoArgs(rKeyType: RType?, rValueType: RType?): RExpr {
        if (rKeyType == null || rValueType == null) {
            throw C_Error(startPos, "expr_map_notype", "Key/value types not specified for map")
        }
        val rMapType = RMapType(rKeyType, rValueType)
        return RMapExpr(rMapType, null)
    }

    private fun compileOneArg(rKeyType: RType?, rValueType: RType?, rArg: RExpr): RExpr {
        val rArgType = rArg.type

        if (rArgType !is RMapType) {
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

        val rMapType = RMapType(rActualKeyType, rActualValueType)
        return RMapExpr(rMapType, rArg)
    }
}

class S_RecordOrCallExpr(val base: S_Expr, val args: List<S_NameExprPair>): S_Expr(base.startPos) {
    override fun compile(ctx: C_ExprContext): RExpr {
        val expr = disambiguate(ctx)
        return expr.compile(ctx)
    }

    override fun compileDb(ctx: C_DbExprContext): DbExpr {
        val expr = disambiguate(ctx.exprCtx)
        return expr.compileDb(ctx)
    }

    private fun disambiguate(ctx: C_ExprContext): S_Expr {
        if (base is S_NameExpr) {
            val record = ctx.entCtx.modCtx.getRecordOpt(base.name.str)
            if (record != null) {
                return S_RecordExpr(startPos, base.name, args)
            }
        }

        val namedArg = args.map { it.name }.filterNotNull().firstOrNull()
        if (namedArg != null) {
            val argName = namedArg.str
            throw C_Error(namedArg.pos, "expr_call_namedarg:$argName", "Named function arguments not supported")
        }

        val argExprs = args.map { it.expr }
        return S_CallExpr(base, argExprs)
    }
}

class S_RecordExpr(pos: S_Pos, val name: S_Name, val args: List<S_NameExprPair>): S_Expr(pos) {
    override fun compile(ctx: C_ExprContext): RExpr {
        val record = ctx.entCtx.modCtx.getRecord(name)
        val attrs = C_AttributeResolver.resolveCreate(ctx, record.attributes, args, startPos)
        return RRecordExpr(record, attrs)
    }

    override fun compileDb(ctx: C_DbExprContext): DbExpr = delegateCompileDb(ctx)
}
