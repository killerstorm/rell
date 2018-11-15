package net.postchain.rell.parser

import net.postchain.rell.model.*

internal class DbClassAttr(val cls: RAtClass, val attr: RAttrib)

internal class CtDbExprContext(
        val parent: CtDbExprContext?,
        val exprCtx: CtExprContext,
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
        var ctx: CtDbExprContext? = this
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
        var ctx: CtDbExprContext? = this
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

class S_NameExprPair(val name: S_Name?, val expr: S_Expression)

abstract class S_Expression(val startPos: S_Pos) {
    internal abstract fun compile(ctx: CtExprContext): RExpr

//    internal abstract fun compileDb(ctx: CtDbExprContext): DbExpr
    internal open fun compileDb(ctx: CtDbExprContext): DbExpr = throw errNoDb()

    internal open fun compileDbWhere(ctx: CtDbExprContext, idx: Int): DbExpr = compileDb(ctx)

    internal open fun compileCall(ctx: CtExprContext, args: List<RExpr>): RExpr = throw errCallNoFn(compile(ctx).type)

    internal open fun compileCallDb(ctx: CtDbExprContext, args: List<DbExpr>): DbExpr =
            throw errCallNoFn(compileDb(ctx).type)

    internal open fun compileDestination(opPos: S_Pos, ctx: CtExprContext): RDestinationExpr {
        throw CtError(opPos, "expr_bad_dst", "Invalid assignment destination")
    }

    internal open fun discoverPathExpr(tailPath: List<S_Name>): Pair<S_Expression?, List<S_Name>> {
        return Pair(this, tailPath)
    }

    internal fun delegateCompileDb(ctx: CtDbExprContext): DbExpr = InterpretedDbExpr(compile(ctx.exprCtx))

    fun errNoDb(): CtError = CtError(startPos, "expr_nosql", "Expression cannot be converted to SQL")

    private fun errCallNoFn(type: RType): CtError {
        return CtError(startPos, "expr_call_nofn:${type.toStrictString()}",
                "Function call on type ${type.toStrictString()}")
    }
}

class S_StringLiteralExpr(pos: S_Pos, val literal: String): S_Expression(pos) {
    override fun compile(ctx: CtExprContext): RExpr = RConstantExpr.makeText(literal)
    override fun compileDb(ctx: CtDbExprContext): DbExpr = delegateCompileDb(ctx)
}

class S_ByteALiteralExpr(pos: S_Pos, val bytes: ByteArray): S_Expression(pos) {
    override fun compile(ctx: CtExprContext): RExpr = RConstantExpr.makeBytes(bytes)
    override fun compileDb(ctx: CtDbExprContext): DbExpr = delegateCompileDb(ctx)
}

class S_IntLiteralExpr(pos: S_Pos, val value: Long): S_Expression(pos) {
    override fun compile(ctx: CtExprContext): RExpr = RConstantExpr.makeInt(value)
    override fun compileDb(ctx: CtDbExprContext): DbExpr = delegateCompileDb(ctx)
}

class S_BooleanLiteralExpr(pos: S_Pos, val value: Boolean): S_Expression(pos) {
    override fun compile(ctx: CtExprContext): RExpr = RConstantExpr.makeBool(value)
    override fun compileDb(ctx: CtDbExprContext): DbExpr = delegateCompileDb(ctx)
}

class S_NullLiteralExpr(pos: S_Pos): S_Expression(pos) {
    override fun compile(ctx: CtExprContext) = RConstantExpr.makeNull()
    override fun compileDb(ctx: CtDbExprContext) = delegateCompileDb(ctx)
}

class S_CallExpr(val base: S_Expression, val args: List<S_Expression>): S_Expression(base.startPos) {
    override fun compile(ctx: CtExprContext): RExpr {
        val rArgs = args.map {
            val rArg = it.compile(ctx)
            checkUnitType(it, rArg.type)
            rArg
        }
        return base.compileCall(ctx, rArgs)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        val dbArgs = args.map {
            val dbArg = it.compileDb(ctx)
            checkUnitType(it, dbArg.type)
            dbArg
        }
        return base.compileCallDb(ctx, dbArgs)
    }

    private fun checkUnitType(arg: S_Expression, type: RType) {
        CtUtils.checkUnitType(arg.startPos, type, "expr_arg_unit", "Argument expression returns nothing")
    }
}

class S_LookupExpr(val opPos: S_Pos, val base: S_Expression, val expr: S_Expression): S_Expression(base.startPos) {
    override fun compile(ctx: CtExprContext): RExpr {
        val rBase = base.compile(ctx)
        val rExpr = expr.compile(ctx)
        return compile0(rBase, rExpr)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
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

        val rResExpr = compileType(effectiveType, rBase, rExpr)

        if (baseType is RNullableType) {
            throw CtError(opPos, "expr_lookup_null", "Cannot apply '[]' on null")
        }

        return rResExpr
    }

    private fun compileType(baseType: RType, rBase: RExpr, rExpr: RExpr): RExpr {
        if (baseType == RTextType) {
            return compileText(rBase, rExpr)
        } else if (baseType == RByteArrayType) {
            return compileByteArray(rBase, rExpr)
        } else {
            return compileDestination0(opPos, rBase, rExpr, baseType)
        }
    }

    override fun compileDestination(opPos2: S_Pos, ctx: CtExprContext): RDestinationExpr {
        val rBase = base.compile(ctx)
        val rExpr = expr.compile(ctx)
        return compileDestination0(opPos2, rBase, rExpr, rBase.type)
    }

    private fun compileDestination0(opPos2: S_Pos, rBase: RExpr, rExpr: RExpr, baseType: RType): RDestinationExpr {
        if (baseType is RListType) {
            return compileList(rBase, rExpr, baseType.elementType)
        } else if (baseType is RMapType) {
            return compileMap(rBase, rExpr, baseType.keyType, baseType.valueType)
        }

        throw CtError(opPos2, "expr_lookup_base:${baseType.toStrictString()}",
                "Operator '[]' undefined for type ${baseType.toStrictString()}")
    }

    private fun compileList(rBase: RExpr, rExpr: RExpr, elementType: RType): RDestinationExpr {
        matchKey(RIntegerType, rExpr)
        return RListLookupExpr(elementType, rBase, rExpr)
    }

    private fun compileMap(rBase: RExpr, rExpr: RExpr, keyType: RType, valueType: RType): RDestinationExpr {
        matchKey(keyType, rExpr)
        return RMapLookupExpr(valueType, rBase, rExpr)
    }

    private fun compileText(rBase: RExpr, rExpr: RExpr): RExpr {
        matchKey(RIntegerType, rExpr)
        return RTextSubscriptExpr(rBase, rExpr)
    }

    private fun compileByteArray(rBase: RExpr, rExpr: RExpr): RExpr {
        matchKey(RIntegerType, rExpr)
        return RByteArraySubscriptExpr(rBase, rExpr)
    }

    private fun matchKey(rType: RType, rExpr: RExpr) {
        S_Type.match(rType, rExpr.type, expr.startPos, "expr_lookup_keytype", "Invalid lookup key type")
    }
}

class S_CreateExpr(pos: S_Pos, val className: S_Name, val exprs: List<S_NameExprPair>): S_Expression(pos) {
    override fun compileDb(ctx: CtDbExprContext): DbExpr = delegateCompileDb(ctx)

    override fun compile(ctx: CtExprContext): RExpr {
        ctx.entCtx.checkDbUpdateAllowed(startPos)

        val cls = ctx.entCtx.modCtx.getClass(className)
        val rExprs = exprs.map { it.expr.compile(ctx) }

        val types = rExprs.map { it.type }
        val attrs = matchExprs(cls, exprs, types)
        val attrExprs = attrs.withIndex().map { (idx, attr) -> RCreateExprAttr(attr, rExprs[idx]) }

        val attrExprsDef = attrExprs + matchDefaultExpressions(cls, attrExprs)
        checkMissingAttrs(cls, attrExprsDef)

        val type = RInstanceRefType(cls)
        return RCreateExpr(type, cls, attrExprsDef)
    }

    private fun matchExprs(cls: RClass, exprs: List<S_NameExprPair>, types: List<RType>): List<RAttrib> {
        val explicitExprs = S_UpdateExprMatcher.matchExplicitExprs(cls, exprs, false)
        S_UpdateExprMatcher.checkExplicitExprTypes(exprs, explicitExprs, types)
        return S_UpdateExprMatcher.matchImplicitExprs(cls, exprs, types, explicitExprs, false)
    }

    private fun matchDefaultExpressions(cls: RClass, attrExprs: List<RCreateExprAttr>): List<RCreateExprAttr> {
        val provided = attrExprs.map { it.attr.name }.toSet()
        return cls.attributes.values.filter { it.expr != null && it.name !in provided }.map { RCreateExprAttr(it, it.expr!!) }
    }

    private fun checkMissingAttrs(cls: RClass, attrs: List<RCreateExprAttr>) {
        val names = attrs.map { it.attr.name }.toSet()

        val missing = (cls.attributes.keys - names).sorted().toList()
        if (!missing.isEmpty()) {
            throw CtError(startPos, "attr_missing:${missing.joinToString(",")}",
                    "Attributes not specified: ${missing.joinToString()}")
        }
    }
}

class S_TupleExpression(startPos: S_Pos, val fields: List<Pair<S_Name?, S_Expression>>): S_Expression(startPos) {
    override fun compile(ctx: CtExprContext): RExpr {
        checkNames()
        val rExprs = fields.map { (_, expr) -> expr.compile(ctx) }
        return compile0(rExprs)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
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
            CtUtils.checkUnitType(fields[i].second.startPos, rExpr.type, "expr_tuple_unit", "Type of expression is unit")
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
                throw CtError(name.pos, "expr_tuple_dupname:$nameStr", "Duplicate field: '$nameStr'")
            }
        }
    }
}

class S_ListLiteralExpression(pos: S_Pos, val exprs: List<S_Expression>): S_Expression(pos) {
    override fun compile(ctx: CtExprContext): RExpr {
        checkEmpty()
        val rExprs = exprs.map { it.compile(ctx) }
        return compile0(rExprs)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
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
            throw CtError(startPos, "expr_list_empty", "Type of empty list literal is unknown; use list<T>() instead")
        }
    }

    private fun compile0(rExprs: List<RExpr>): RExpr {
        for ((i, rExpr) in rExprs.withIndex()) {
            CtUtils.checkUnitType(exprs[i].startPos, rExpr.type, "expr_list_unit", "Element expression returns nothing")
        }

        var rType = rExprs[0].type
        for ((i, rExpr) in rExprs.subList(1, rExprs.size).withIndex()) {
            rType = S_Type.commonType(rType, rExpr.type, exprs[i].startPos, "expr_list_itemtype", "Wrong list item type")
        }

        val rListType = RListType(rType)
        return RListLiteralExpr(rListType, rExprs)
    }
}

class S_MapLiteralExpression(startPos: S_Pos, val entries: List<Pair<S_Expression, S_Expression>>): S_Expression(startPos) {
    override fun compile(ctx: CtExprContext): RExpr {
        checkEmpty()
        val rEntries = entries.map { (key, value) -> Pair(key.compile(ctx), value.compile(ctx)) }
        return compile0(rEntries)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
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
            throw CtError(startPos, "expr_map_empty", "Type of empty map literal is unknown; use map<K,V>() instead")
        }
    }

    private fun compile0(rEntries: List<Pair<RExpr, RExpr>>): RExpr {
        for ((i, rEntry) in rEntries.withIndex()) {
            val (rKey, rValue) = rEntry
            CtUtils.checkUnitType(entries[i].first.startPos, rKey.type, "expr_map_key_unit", "Key expression returns nothing")
            CtUtils.checkUnitType(entries[i].second.startPos, rValue.type, "expr_map_value_unit", "Value expression returns nothing")
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

sealed class S_CollectionExpression(
        pos: S_Pos,
        val type: S_Type?,
        val args: List<S_Expression>,
        val colType: String
): S_Expression(pos)
{
    abstract fun makeExpr(rType: RType, rArg: RExpr?): RExpr

    override fun compile(ctx: CtExprContext): RExpr {
        val rArgs = args.map { it.compile(ctx) }
        return compile0(ctx, rArgs)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        val dbArgs = args.map { it.compileDb(ctx) }
        val rArgs = dbArgs.map { (it as? InterpretedDbExpr)?.expr }.filterNotNull()
        if (rArgs.size == dbArgs.size) {
            val rResExpr = compile0(ctx.exprCtx, rArgs)
            return InterpretedDbExpr(rResExpr)
        }
        throw errNoDb()
    }

    private fun compile0(ctx: CtExprContext, rArgs: List<RExpr>): RExpr {
        val rType = type?.compile(ctx)

        if (rArgs.size == 0) {
            return compileNoArgs(rType)
        } else if (rArgs.size == 1) {
            val rArg = rArgs[0]
            return compileOneArg(rType, rArg)
        } else {
            throw CtError(startPos, "expr_${colType}_argcnt:${rArgs.size}",
                    "Wrong number of arguments for $colType<>: ${rArgs.size}")
        }
    }

    private fun compileNoArgs(rType: RType?): RExpr {
        if (rType == null) {
            throw CtError(startPos, "expr_${colType}_notype", "Element type not specified for $colType")
        }
        return makeExpr(rType, null)
    }

    private fun compileOneArg(rType: RType?, rArg: RExpr): RExpr {
        val rArgType = rArg.type
        if (rArgType !is RCollectionType) {
            throw CtError(startPos, "expr_${colType}_badtype",
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
                throw CtError(
                        pos,
                        "$errCode:${declaredType.toStrictString()}:${argumentType.toStrictString()}",
                        "$errMsg: ${argumentType.toStrictString()} instead of ${declaredType.toStrictString()}"
                )
            }

            return declaredType
        }
    }
}

class S_ListExpression(pos: S_Pos, type: S_Type?, args: List<S_Expression>)
    : S_CollectionExpression(pos, type, args, "list")
{
    override fun makeExpr(rType: RType, rArg: RExpr?): RExpr {
        val rListType = RListType(rType)
        return RListExpr(rListType, rArg)
    }
}

class S_SetExpression(pos: S_Pos, type: S_Type?, args: List<S_Expression>)
    : S_CollectionExpression(pos, type, args, "set")
{
    override fun makeExpr(rType: RType, rArg: RExpr?): RExpr {
        val rSetType = RSetType(rType)
        return RSetExpr(rSetType, rArg)
    }
}

class S_MapExpression(
        pos: S_Pos,
        val keyValueTypes: Pair<S_Type, S_Type>?,
        val args: List<S_Expression>
): S_Expression(pos)
{
    override fun compile(ctx: CtExprContext): RExpr {
        val rArgs = args.map { it.compile(ctx) }
        return compile0(ctx, rArgs)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        val dbArgs = args.map { it.compileDb(ctx) }
        val rArgs = dbArgs.map { (it as? InterpretedDbExpr)?.expr }.filterNotNull()
        if (rArgs.size == dbArgs.size) {
            val rResExpr = compile0(ctx.exprCtx, rArgs)
            return InterpretedDbExpr(rResExpr)
        }
        throw errNoDb()
    }

    private fun compile0(ctx: CtExprContext, rArgs: List<RExpr>): RExpr {
        val rKeyType = keyValueTypes?.first?.compile(ctx)
        val rValueType = keyValueTypes?.second?.compile(ctx)

        if (rArgs.size == 0) {
            return compileNoArgs(rKeyType, rValueType)
        } else if (rArgs.size == 1) {
            val rArg = rArgs[0]
            return compileOneArg(rKeyType, rValueType, rArg)
        } else {
            throw CtError(startPos, "expr_map_argcnt:${rArgs.size}", "Wrong number of arguments for map<>: ${rArgs.size}")
        }
    }

    private fun compileNoArgs(rKeyType: RType?, rValueType: RType?): RExpr {
        if (rKeyType == null || rValueType == null) {
            throw CtError(startPos, "expr_map_notype", "Key/value types not specified for map")
        }
        val rMapType = RMapType(rKeyType, rValueType)
        return RMapExpr(rMapType, null)
    }

    private fun compileOneArg(rKeyType: RType?, rValueType: RType?, rArg: RExpr): RExpr {
        val rArgType = rArg.type

        if (rArgType !is RMapType) {
            throw CtError(startPos, "expr_map_badtype:${rArgType.toStrictString()}",
                    "Wrong argument type for map<>: ${rArgType.toStrictString()}")
        }

        val rActualKeyType = S_CollectionExpression.checkElementType(
                startPos,
                rKeyType,
                rArgType.keyType,
                "expr_map_key_typemiss",
                "Key type missmatch for map<>")

        val rActualValueType = S_CollectionExpression.checkElementType(
                startPos,
                rValueType,
                rArgType.valueType,
                "expr_map_value_typemiss",
                "Value type missmatch for map<>")

        val rMapType = RMapType(rActualKeyType, rActualValueType)
        return RMapExpr(rMapType, rArg)
    }
}
