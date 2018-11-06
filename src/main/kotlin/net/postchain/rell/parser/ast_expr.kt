package net.postchain.rell.parser

import net.postchain.rell.model.*
import net.postchain.rell.runtime.RtBooleanValue
import net.postchain.rell.runtime.RtByteArrayValue
import net.postchain.rell.runtime.RtIntValue
import net.postchain.rell.runtime.RtTextValue

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

class S_NameExprPair(val name: String?, val expr: S_Expression)

abstract class S_Expression {
    internal abstract fun compile(ctx: CtExprContext): RExpr
    internal abstract fun compileDb(ctx: CtDbExprContext): DbExpr
    internal open fun compileDbWhere(ctx: CtDbExprContext, idx: Int): DbExpr = compileDb(ctx)
    internal open fun compileCall(ctx: CtExprContext, args: List<RExpr>): RExpr = TODO()
    internal open fun compileCallDb(ctx: CtDbExprContext, args: List<DbExpr>): DbExpr = TODO()
    internal open fun compileAsBoolean(ctx: CtExprContext): RExpr = compile(ctx)

    internal open fun compileDestination(ctx: CtExprContext): RDestinationExpr {
        throw CtError("expr_bad_dst", "Invalid assignment destination")
    }

    internal open fun discoverPathExpr(tailPath: List<String>): Pair<S_Expression?, List<String>> {
        return Pair(this, tailPath)
    }

    internal fun delegateCompileDb(ctx: CtDbExprContext): DbExpr = InterpretedDbExpr(compile(ctx.exprCtx))
}

class S_StringLiteralExpr(val literal: String): S_Expression() {
    override fun compile(ctx: CtExprContext): RExpr = RConstantExpr.makeText(literal)
    override fun compileDb(ctx: CtDbExprContext): DbExpr = delegateCompileDb(ctx)
}

class S_ByteALiteralExpr(val bytes: ByteArray): S_Expression() {
    override fun compile(ctx: CtExprContext): RExpr = RConstantExpr.makeBytes(bytes)
    override fun compileDb(ctx: CtDbExprContext): DbExpr = delegateCompileDb(ctx)
}

class S_IntLiteralExpr(val value: Long): S_Expression() {
    override fun compile(ctx: CtExprContext): RExpr = RConstantExpr.makeInt(value)
    override fun compileDb(ctx: CtDbExprContext): DbExpr = delegateCompileDb(ctx)
}

class S_BooleanLiteralExpr(val value: Boolean): S_Expression() {
    override fun compile(ctx: CtExprContext): RExpr = RConstantExpr.makeBool(value)
    override fun compileDb(ctx: CtDbExprContext): DbExpr = delegateCompileDb(ctx)
}

class S_CallExpr(val base: S_Expression, val args: List<S_Expression>): S_Expression() {
    override fun compile(ctx: CtExprContext): RExpr {
        val rArgs = args.map { it.compile(ctx) }
        checkUnitTypes(rArgs.map { it.type })
        return base.compileCall(ctx, rArgs)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        val dbArgs = args.map { it.compileDb(ctx) }
        checkUnitTypes(dbArgs.map { it.type })
        return base.compileCallDb(ctx, dbArgs)
    }

    private fun checkUnitTypes(types: List<RType>) {
        for (type in types) {
            CtUtils.checkUnitType(type, "expr_arg_unit", "Type of argument is unit")
        }
    }
}

class S_LookupExpr(val base: S_Expression, val expr: S_Expression): S_Expression() {
    override fun compile(ctx: CtExprContext): RExpr {
        val rBase = base.compile(ctx)
        val rExpr = expr.compile(ctx)

        val baseType = rBase.type
        if (baseType == RTextType) {
            return compileText(rBase, rExpr)
        } else if (baseType == RByteArrayType) {
            return compileByteArray(rBase, rExpr)
        } else {
            return compileDestination0(rBase, rExpr, baseType)
        }
    }

    override fun compileDestination(ctx: CtExprContext): RDestinationExpr {
        val rBase = base.compile(ctx)
        val rExpr = expr.compile(ctx)
        return compileDestination0(rBase, rExpr, rBase.type)
    }

    private fun compileDestination0(rBase: RExpr, rExpr: RExpr, baseType: RType): RDestinationExpr {
        if (baseType is RListType) {
            return compileList(rBase, rExpr, baseType.elementType)
        } else if (baseType is RMapType) {
            return compileMap(rBase, rExpr, baseType.keyType, baseType.valueType)
        }

        throw CtError("expr_lookup_base:${baseType.toStrictString()}",
                "Operator '[]' undefined for type ${baseType.toStrictString()}")
    }

    private fun compileList(rBase: RExpr, rExpr: RExpr, elementType: RType): RDestinationExpr {
        val rExpr2 = matchKey(RIntegerType, rExpr)
        return RListLookupExpr(elementType, rBase, rExpr2)
    }

    private fun compileMap(rBase: RExpr, rExpr: RExpr, keyType: RType, valueType: RType): RDestinationExpr {
        val rExpr2 = matchKey(keyType, rExpr)
        return RMapLookupExpr(valueType, rBase, rExpr2)
    }

    private fun compileText(rBase: RExpr, rExpr: RExpr): RExpr {
        val rExpr2 = matchKey(RIntegerType, rExpr)
        return RTextSubscriptExpr(rBase, rExpr2)
    }

    private fun compileByteArray(rBase: RExpr, rExpr: RExpr): RExpr {
        val rExpr2 = matchKey(RIntegerType, rExpr)
        return RByteArraySubscriptExpr(rBase, rExpr2)
    }

    private fun matchKey(rType: RType, rExpr: RExpr): RExpr {
        return rType.match(rExpr, "expr_lookup_keytype", "Invalid lookup key type")
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

class S_CreateExpr(val className: String, val exprs: List<S_NameExprPair>): S_Expression() {
    override fun compileDb(ctx: CtDbExprContext): DbExpr = delegateCompileDb(ctx)

    override fun compile(ctx: CtExprContext): RExpr {
        ctx.entCtx.checkDbUpdateAllowed()

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
        S_UpdateExprMatcher.checkExplicitExprTypes(explicitExprs, types)
        return S_UpdateExprMatcher.matchImplicitExprs(cls, exprs, types, explicitExprs, false)
    }

    private fun matchDefaultExpressions(cls: RClass, attrExprs: List<RCreateExprAttr>): List<RCreateExprAttr> {
        val provided = attrExprs.map { it.attr.name }.toSet()
        return cls.attributes.values.filter { it.expr != null && !(it.name in provided) }.map { RCreateExprAttr(it, it.expr!!) }
    }

    private fun checkMissingAttrs(cls: RClass, attrs: List<RCreateExprAttr>) {
        val names = attrs.map { it.attr.name }.toSet()

        val missing = (cls.attributes.keys - names).sorted().toList()
        if (!missing.isEmpty()) {
            throw CtError("attr_missing:${missing.joinToString(",")}",
                    "Attributes not specified: ${missing.joinToString()}")
        }
    }
}

class S_TupleExpression(val exprs: List<S_Expression>): S_Expression() {
    override fun compile(ctx: CtExprContext): RExpr {
        val rExprs = exprs.map {
            val rExpr = it.compile(ctx)
            CtUtils.checkUnitType(rExpr.type, "expr_tuple_unit", "Type of expression is unit")
            rExpr
        }

        val fields = rExprs.map { RTupleField(null, it.type) }
        val type = RTupleType(fields)

        return RTupleExpr(type, rExprs)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        throw CtError("expr_tuple_at", "Tuples are not allowed within @-expressions")
    }
}

class S_ListLiteralExpression(val exprs: List<S_Expression>): S_Expression() {
    override fun compile(ctx: CtExprContext): RExpr {
        if (exprs.isEmpty()) {
            throw CtError("expr_list_empty", "Type of empty list literal is unknown; use list<T>() instead")
        }

        val rExprs = exprs.map { it.compile(ctx) }
        val rType = rExprs[0].type
        CtUtils.checkUnitType(rType, "expr_list_unit", "Invalid list element type: ${rType.toStrictString()}")

        val rExprs2 = rExprs.map { rType.match(it, "expr_list_itemtype", "Wrong list item type") }

        val rListType = RListType(rType)
        return RListLiteralExpr(rListType, rExprs2)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

class S_MapLiteralExpression(val entries: List<Pair<S_Expression, S_Expression>>): S_Expression() {
    override fun compile(ctx: CtExprContext): RExpr {
        if (entries.isEmpty()) {
            throw CtError("expr_map_empty", "Type of empty map literal is unknown; use map<K,V>() instead")
        }

        val rEntries = entries.map { Pair(it.first.compile(ctx), it.second.compile(ctx)) }
        val rKeyType = rEntries[0].first.type
        val rValueType = rEntries[0].second.type

        CtUtils.checkUnitType(rKeyType, "expr_list_unit", "Invalid list element type: ${rKeyType.toStrictString()}")
        CtUtils.checkUnitType(rValueType, "expr_list_unit", "Invalid list element type: ${rValueType.toStrictString()}")

        val rEntries2 = rEntries.map {
            val key = rKeyType.match(it.first, "expr_map_keytype", "Wrong map entry key type")
            val value = rValueType.match(it.second, "expr_map_valuetype", "Wrong map entry value type")
            Pair(key, value)
        }

        val rMapType = RMapType(rKeyType, rValueType)
        return RMapLiteralExpr(rMapType, rEntries2)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

sealed class S_CollectionExpression(val type: S_Type?, val args: List<S_Expression>, val colType: String): S_Expression() {
    abstract fun makeExpr(rType: RType, rArg: RExpr?): RExpr

    override fun compile(ctx: CtExprContext): RExpr {
        val rType = type?.compile(ctx)
        val rArgs = args.map { it.compile(ctx) }

        if (rArgs.size == 0) {
            return compileNoArgs(rType)
        } else if (rArgs.size == 1) {
            val rArg = rArgs[0]
            return compileOneArg(rType, rArg)
        } else {
            throw CtError("expr_${colType}_argcnt:${rArgs.size}",
                    "Wrong number of arguments for $colType<>: ${rArgs.size}")
        }
    }

    private fun compileNoArgs(rType: RType?): RExpr {
        if (rType == null) {
            throw CtError("expr_${colType}_notype", "Element type not specified for $colType")
        }
        return makeExpr(rType, null)
    }

    private fun compileOneArg(rType: RType?, rArg: RExpr): RExpr {
        val rArgType = rArg.type
        if (!(rArgType is RCollectionType)) {
            throw CtError("expr_${colType}_badtype",
                    "Wrong argument type for $colType<>: ${rArgType.toStrictString()}")
        }

        if (rType != null && rArgType.elementType != rType) {
            throw CtError("expr_${colType}_typemiss:${rType.toStrictString()}:${rArgType.elementType.toStrictString()}",
                    "Element type missmatch for $colType<>: ${rArgType.elementType.toStrictString()} " +
                            "instead of ${rType.toStrictString()}")
        }

        return makeExpr(rArgType.elementType, rArg)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        TODO()
    }
}

class S_ListExpression(type: S_Type?, args: List<S_Expression>): S_CollectionExpression(type, args, "list") {
    override fun makeExpr(rType: RType, rArg: RExpr?): RExpr {
        val rListType = RListType(rType)
        return RListExpr(rListType, rArg)
    }
}

class S_SetExpression(type: S_Type?, args: List<S_Expression>): S_CollectionExpression(type, args, "set") {
    override fun makeExpr(rType: RType, rArg: RExpr?): RExpr {
        val rSetType = RSetType(rType)
        return RSetExpr(rSetType, rArg)
    }
}

class S_MapExpression(val keyValueTypes: Pair<S_Type, S_Type>?, val args: List<S_Expression>): S_Expression() {
    override fun compile(ctx: CtExprContext): RExpr {
        val rKeyType = keyValueTypes?.first?.compile(ctx)
        val rValueType = keyValueTypes?.second?.compile(ctx)
        val rArgs = args.map { it.compile(ctx) }

        if (rArgs.size == 0) {
            return compileNoArgs(rKeyType, rValueType)
        } else if (rArgs.size == 1) {
            val rArg = rArgs[0]
            return compileOneArg(rKeyType, rValueType, rArg)
        } else {
            throw CtError("expr_map_argcnt:${rArgs.size}",
                    "Wrong number of arguments for map<>: ${rArgs.size}")
        }
    }

    private fun compileNoArgs(rKeyType: RType?, rValueType: RType?): RExpr {
        if (rKeyType == null || rValueType == null) {
            throw CtError("expr_map_notype", "Key/value types not specified for map")
        }
        val rMapType = RMapType(rKeyType, rValueType)
        return RMapExpr(rMapType, null)
    }

    private fun compileOneArg(rKeyType: RType?, rValueType: RType?, rArg: RExpr): RExpr {
        val rArgType = rArg.type

        if (rKeyType != null && rValueType != null) {
            val rMapType = RMapType(rKeyType, rValueType)
            if (rArgType != rMapType) {
                throw CtError("expr_map_typemiss:${rMapType.toStrictString()}:${rArgType.toStrictString()}",
                        "Argument type missmatch for map: ${rArgType.toStrictString()} " +
                                "instead of ${rMapType.toStrictString()}")
            }
            return RMapExpr(rMapType, rArg)
        }

        if (!(rArgType is RMapType)) {
            throw CtError("expr_map_badtype",
                    "Wrong argument type for map<>: ${rArgType.toStrictString()}")
        }

        return RMapExpr(rArgType, rArg)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}
