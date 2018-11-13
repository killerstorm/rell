package net.postchain.rell.parser

import net.postchain.rell.model.*
import java.util.*

class S_NameExpr(val name: String): S_Expression() {
    override fun compile(ctx: CtExprContext): RExpr {
        val res = resolveName(ctx, name)
        if (res is CtNameResolution_Local) {
            return res.loc.toVarExpr()
        } else {
            throw errUnknownName(name)
        }
    }

    override fun compileDestination(ctx: CtExprContext): RDestinationExpr {
        val res = resolveName(ctx, name)
        if (res is CtNameResolution_Local) {
            if (!res.loc.modifiable) {
                throw CtError("stmt_assign_val:$name", "Value of '$name' cannot be changed")
            }
            return res.loc.toVarExpr()
        } else {
            throw errUnknownName(name)
        }
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        val res = resolveNameDb(ctx, name)
        if (res is CtDbNameResolution_Class) {
            return PathDbExpr(res.cls.type, res.cls, listOf(), null)
        } else if (res is CtDbNameResolution_Attr) {
            return makeDbPathExpr(res.attr.cls, listOf(makeDbPathEntry(res.attr.attr)))
        } else if (res is CtDbNameResolution_Local) {
            val rExpr = res.loc.toVarExpr()
            return InterpretedDbExpr(rExpr)
        } else {
            throw errUnknownName(name)
        }
    }

    override fun compileDbWhere(ctx: CtDbExprContext, idx: Int): DbExpr {
        val clsAttrs = ctx.findAttributesByName(name)
        val localVar = ctx.exprCtx.lookupOpt(name)

        if (clsAttrs.isEmpty() || localVar == null) {
            return compileDb(ctx)
        }

        // There is a class attribute and a local variable with such name -> implicit where condition.

        val argType = localVar.type
        val clsAttrsByType = clsAttrs.filter { S_BinaryOp_EqNe.checkTypes(it.attr.type, argType) }

        if (clsAttrsByType.isEmpty()) {
            val typeStr = argType.toStrictString()
            throw CtError("at_attr_type:$idx:$name:$typeStr",
                    "Attribute '$name' type missmatch: expected $typeStr")
        } else if (clsAttrsByType.size > 1) {
            val n = clsAttrs.size
            throw CtError("at_attr_name_ambig:$idx:$name:$n", "Multiple attributes match '$name': $n")
        }

        val clsAttr = clsAttrsByType[0]
        val attrType = clsAttr.attr.type
        if (!S_BinaryOp_EqNe.checkTypes(attrType, argType)) {
            throw CtError("at_param_attr_type_missmatch:$name:$attrType:$argType",
                    "Parameter type does not match attribute type for '$name': $argType instead of $attrType")
        }

        val clsAttrExpr = PathDbExpr(clsAttr.attr.type, clsAttr.cls, listOf(), clsAttr.attr.name)
        val localAttrExpr = localVar.toVarExpr()
        return BinaryDbExpr(RBooleanType, DbBinaryOp_Eq, clsAttrExpr, InterpretedDbExpr(localAttrExpr))
    }

    override fun compileCall(ctx: CtExprContext, args: List<RExpr>): RExpr {
        val fn = ctx.entCtx.modCtx.getFunction(name)
        return fn.compileCall(args)
    }

    override fun compileCallDb(ctx: CtDbExprContext, args: List<DbExpr>): DbExpr {
        val fn = ctx.exprCtx.entCtx.modCtx.getFunction(name)
        return compileCallDbGlobal(fn, args)
    }

    override fun discoverPathExpr(tailPath: List<String>): Pair<S_Expression?, List<String>> {
        return Pair(null, listOf(name) + tailPath)
    }
}

class S_MemberExpr(val base: S_Expression, val name: String): S_Expression() {
    override fun compile(ctx: CtExprContext): RExpr {
        val (deepBase, path) = discoverPathExpr(listOf())

        if (deepBase != null) {
            val rBase = deepBase.compile(ctx)
            return C_PathExprUtils.compilePath(ctx, rBase, path)
        } else {
            return C_PathExprUtils.compilePath(ctx, path)
        }
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        val (deepBase, path) = discoverPathExpr(listOf())
        if (deepBase != null) {
            val dbBase = deepBase.compileDb(ctx)
            return C_PathExprUtils.compilePathDb(ctx, dbBase, path)
        }
        return C_PathExprUtils.compilePathDb(ctx, path)
    }

    override fun compileCall(ctx: CtExprContext, args: List<RExpr>): RExpr {
        val (deepBase, path) = discoverPathExpr(listOf())
        check(path.size > 0)
        check(path[path.size - 1] == name)
        val leftPath = path.subList(0, path.size - 1)

        if (deepBase != null) {
            val rDeepBase = deepBase.compile(ctx)
            val rBase = C_PathExprUtils.compilePath(ctx, rDeepBase, leftPath)
            return compileCall0(rBase, args)
        }

        if (leftPath.size > 1) {
            val rBase = C_PathExprUtils.compilePath(ctx, leftPath)
            return compileCall0(rBase, args)
        }

        check(leftPath.size == 1)
        val head = leftPath[0]
        val res = resolveName(ctx, head)

        if (res is CtNameResolution_Local) {
            val rBase = res.loc.toVarExpr()
            return compileCall0(rBase, args)
        } else if (res is CtNameResolution_Namespace) {
            val fn = res.ns.getFunctionOpt(name)
            if (fn == null) throw errUnknownFunction("$head.$name")
            return fn.compileCall(args)
        } else {
            throw errUnknownName(head)
        }
    }

    override fun compileCallDb(ctx: CtDbExprContext, args: List<DbExpr>): DbExpr {
        val (deepBase, path) = discoverPathExpr(listOf())
        check(path.size > 0)
        check(path[path.size - 1] == name)
        val leftPath = path.subList(0, path.size - 1)

        if (deepBase != null) {
            val dbDeepBase = deepBase.compileDb(ctx)
            val dbBase = C_PathExprUtils.compilePathDb(ctx, dbDeepBase, leftPath)
            return compileCallDb0(dbBase, args)
        }

        check(leftPath.size >= 1)

        if (leftPath.size > 1) {
            val dbBase = C_PathExprUtils.compilePathDb(ctx, leftPath)
            return compileCallDb0(dbBase, args)
        }

        check(leftPath.size == 1)
        val head = leftPath[0]

        val res = resolveNameDb(ctx, head)

        if (res is CtDbNameResolution_Class) {
            val dbBase = compileDbPathExpr0(res.cls, leftPath, 1)
            return compileCallDb0(dbBase, args)
        } else if (res is CtDbNameResolution_Attr) {
            val dbBase = compileDbPathExpr0(res.attr.cls, leftPath, 0)
            return compileCallDb0(dbBase, args)
        } else if (res is CtDbNameResolution_Local) {
            val rBase = res.loc.toVarExpr()
            val dbBase = InterpretedDbExpr(rBase)
            return compileCallDb0(dbBase, args)
        } else if (res is CtDbNameResolution_Namespace) {
            val fn = res.ns.getFunctionOpt(name)
            if (fn == null) throw errUnknownFunction("$head.$name")
            return compileCallDbGlobal(fn, args)
        } else {
            throw errBadPath(path, 2)
        }
    }

    private fun compileCall0(rBase: RExpr, args: List<RExpr>): RExpr {
        val calculator = C_PathExprUtils.compileCallStep(rBase.type, name, args)
        return RMemberExpr(rBase, false, calculator)
    }

    private fun compileCallDb0(dbBase: DbExpr, args: List<DbExpr>): DbExpr {
        val fn = S_LibFunctions.getMemberFunction(dbBase.type, name)

        val rArgs = args.filter { it is InterpretedDbExpr }.map { (it as InterpretedDbExpr).expr }
        if (dbBase is InterpretedDbExpr && rArgs.size == args.size) {
            val calculator = fn.compileCall(dbBase.type, rArgs)
            val rExpr = RMemberExpr(dbBase.expr, false, calculator)
            return InterpretedDbExpr(rExpr)
        }

        return fn.compileCallDb(dbBase, args)
    }

    override fun discoverPathExpr(tailPath: List<String>): Pair<S_Expression?, List<String>> {
        return base.discoverPathExpr(listOf(name) + tailPath)
    }
}

class S_SafeMemberExpr(val base: S_Expression, val name: String): S_Expression() {
    override fun compile(ctx: CtExprContext): RExpr {
        val rBase = base.compile(ctx)
        return compile0(ctx, rBase)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        val dbBase = base.compileDb(ctx)
        if (dbBase is InterpretedDbExpr) {
            val rExpr = compile0(ctx.exprCtx, dbBase.expr)
            return InterpretedDbExpr(rExpr)
        } else {
            throw errWrongType(dbBase.type)
        }
    }

    private fun compile0(ctx: CtExprContext, rBase: RExpr): RExpr {
        val valueType = getValueType(rBase)
        val tailPath = LinkedList(listOf(name))
        val getter = C_PathExprUtils.compilePathStep(ctx, valueType, tailPath)
        check(tailPath.isEmpty())
        return RMemberExpr(rBase, true, getter)
    }

    override fun compileCall(ctx: CtExprContext, args: List<RExpr>): RExpr {
        val rBase = base.compile(ctx)
        val valueType = getValueType(rBase)
        val calculator = C_PathExprUtils.compileCallStep(valueType, name, args)
        return RMemberExpr(rBase, true, calculator)
    }

    private fun getValueType(rBase: RExpr): RType {
        val baseType = rBase.type
        if (baseType !is RNullableType) {
            throw errWrongType(baseType)
        }
        return baseType.valueType
    }

    private fun errWrongType(type: RType): CtError {
        return CtError("expr_safemem_type:${type.toStrictString()}",
                "Wrong type for operator '?.': ${type.toStrictString()}")
    }
}

internal object C_PathExprUtils {
    fun compilePath(ctx: CtExprContext, path: List<String>): RExpr {
        check(!path.isEmpty())

        val head = path[0]
        val res = resolveName(ctx, head)

        if (res is CtNameResolution_Local) {
            val rBase = res.loc.toVarExpr()
            return compilePath(ctx, rBase, path.subList(1, path.size))
        } else if (res is CtNameResolution_Namespace && path.size >= 2) {
            val second = path[1]
            val const = res.ns.getConstantOpt(second)
            if (const == null) throw errUnknownName("$head.$second")
            val rBase = RConstantExpr(const)
            return compilePath(ctx, rBase, path.subList(2, path.size))
        } else {
            throw errUnknownName(head)
        }
    }

    fun compilePath(ctx: CtExprContext, rBase: RExpr, path: List<String>): RExpr {
        var headBase = rBase
        val tailPath = LinkedList(path)

        while (!tailPath.isEmpty()) {
            val getter = compilePathStep(ctx, headBase.type, tailPath)
            headBase = RMemberExpr(headBase, false, getter)
        }

        return headBase
    }

    fun compilePathStep(ctx: CtExprContext, baseType: RType, tailPath: Queue<String>): RMemberCalculator {
        val name = tailPath.peek()

        val effectiveType = if (baseType is RNullableType) baseType.valueType else baseType
        val calculator = compilePathStep0(ctx, effectiveType, tailPath)

        if (calculator == null) {
            throw CtError("expr_badfield:${baseType.toStrictString()}:$name",
                    "Type ${baseType.toStrictString()} has no field '$name'")
        }

        if (baseType is RNullableType) {
            throw CtError("expr_mem_null:$name", "Cannot access member '$name' of null")
        }

        return calculator
    }

    private fun compilePathStep0(ctx: CtExprContext, baseType: RType, tailPath: Queue<String>): RMemberCalculator? {
        if (baseType is RTupleType) {
            return compilePathStepTuple(baseType, tailPath)
        } else if (baseType is RInstanceRefType) {
            return compilePathStepDataObject(ctx, baseType, tailPath)
        } else {
            return null
        }
    }

    private fun compilePathStepTuple(type: RTupleType, path: Queue<String>): RMemberCalculator {
        val step = path.remove()
        val idx = type.fields.indexOfFirst { it.name == step }
        if (idx == -1) {
            throw CtError("unknown_member:${type.toStrictString()}:$step",
                    "Unknown member for type ${type.toStrictString()}: '$step'")
        }
        return RMemberCalculator_TupleField(type.fields[idx].type, idx)
    }

    private fun compilePathStepDataObject(
            ctx: CtExprContext,
            type: RInstanceRefType,
            path: Queue<String>): RMemberCalculator
    {
        val atClass = RAtClass(type.rClass, "", 0)
        val from = listOf(atClass)
        val dbCtx = CtDbExprContext(null, ctx, from)

        val pathList = path.toList()
        path.clear()
        val what = compileDbPathExpr(dbCtx, pathList, false)

        val whereLeft = PathDbExpr(atClass.type, atClass, listOf(), null)
        val whereRight = ParameterDbExpr(atClass.type, 0)
        val where = BinaryDbExpr(RBooleanType, DbBinaryOp_Eq, whereLeft, whereRight)

        val atBase = RAtExprBase(from, listOf(what), where, listOf(), false, false)
        return RMemberCalculator_DataAttribute(what.type, atBase)
    }

    fun compilePathDb(ctx: CtDbExprContext, path: List<String>): DbExpr {
        val head = path[0]
        val res = resolveNameDb(ctx, head)

        if (res is CtDbNameResolution_Class) {
            return compileDbPathExpr0(res.cls, path, 1)
        } else if (res is CtDbNameResolution_Attr) {
            return compileDbPathExpr0(res.attr.cls, path, 0)
        } else if (res is CtDbNameResolution_Local) {
            val rBase = res.loc.toVarExpr()
            val rExpr = compilePath(ctx.exprCtx, rBase, path.subList(1, path.size))
            return InterpretedDbExpr(rExpr)
        } else if (res is CtDbNameResolution_Namespace && path.size >= 2) {
            val second = path[1]
            val const = res.ns.getConstantOpt(second)
            if (const == null) throw errUnknownName("$head.$second")
            val rBase = RConstantExpr(const)
            val rExpr = compilePath(ctx.exprCtx, rBase, path.subList(2, path.size))
            return InterpretedDbExpr(rExpr)
        } else {
            throw errUnknownName(head)
        }
    }

    fun compilePathDb(ctx: CtDbExprContext, base: DbExpr, path: List<String>): DbExpr {
        if (base is InterpretedDbExpr) {
            val rExpr = compilePath(ctx.exprCtx, base.expr, path)
            return InterpretedDbExpr(rExpr)
        }

        var res = base
        for (step in path) {
            res = compilePathDbStep(res, step)
        }
        return res
    }

    private fun compilePathDbStep(base: DbExpr, step: String): DbExpr {
        val type = base.type
        throw CtError("expr_attr_member:${type.toStrictString()}:$step",
                "Type ${type.toStrictString()} has no member '$step'")
    }

    fun compileCallStep(baseType: RType, name: String, args: List<RExpr>): RMemberCalculator {
        val effectiveType = if (baseType is RNullableType) baseType.valueType else baseType

        val fn = S_LibFunctions.getMemberFunction(effectiveType, name)
        val calculator = fn.compileCall(baseType, args)

        if (baseType is RNullableType) {
            throw CtError("expr_call_null:$name", "Cannot call function '$name' on null")
        }

        return calculator
    }
}

private fun compileCallDbGlobal(fn: Ct_Function, args: List<DbExpr>): DbExpr {
    val rArgs = args.filter { it is InterpretedDbExpr }.map { (it as InterpretedDbExpr).expr }
    if (rArgs.size == args.size) {
        val rExpr = fn.compileCall(rArgs)
        return InterpretedDbExpr(rExpr)
    } else {
        return fn.compileCallDb(args)
    }
}

internal fun compileDbPathExpr(ctx: CtDbExprContext, path: List<String>, classAliasAllowed: Boolean): DbExpr {
    val dbExpr = compileDbPathExprOpt(ctx, path, classAliasAllowed)
    if (dbExpr == null) {
        throw errBadPath(path, path.size)
    }
    return dbExpr
}

private fun compileDbPathExprOpt(ctx: CtDbExprContext, path: List<String>, classAliasAllowed: Boolean): DbExpr? {
    val head = path[0]

    val cls = if (classAliasAllowed) ctx.findClassByAlias(head) else null
    val attrs = ctx.findAttributesByName(head)

    if (cls == null && attrs.isEmpty()) {
        return null
    } else if (cls != null) {
        return compileDbPathExpr0(cls, path, 1)
    } else if (!attrs.isEmpty()) {
        val local = ctx.exprCtx.lookupOpt(head)
        if (local != null) {
            // Locals have priority over attributes
            return null
        }

        if (attrs.size > 1) {
            val n = attrs.size
            throw CtError("at_attr_name_ambig:$head:$n", "Multiple attributes with name '$head': $n")
        }

        val attr = attrs[0]
        return compileDbPathExpr0(attr.cls, path, 0)
    } else {
        throw IllegalStateException("impossible")
    }
}

private fun compileDbPathExpr0(baseCls: RAtClass, path: List<String>, startOfs: Int): DbExpr {
    val entries = resolveDbPathEntries(baseCls, path, startOfs)
    return makeDbPathExpr(baseCls, entries)
}

private fun resolveDbPathEntries(baseCls: RAtClass, path: List<String>, startOfs: Int): List<DbPathEntry> {
    val entries = mutableListOf<DbPathEntry>()

    var cls = baseCls.rClass
    for (ofs in startOfs .. path.size - 2) {
        val entry = resolveDbPathEntry(cls, path, ofs)
        if (entry.resultClass == null) throw errBadPath(path, ofs + 1)
        entries.add(entry)
        cls = entry.resultClass
    }

    if (startOfs < path.size) {
        val entry = resolveDbPathEntry(cls, path, path.size - 1)
        entries.add(entry)
    }

    return entries.toList()
}

private fun resolveDbPathEntry(cls: RClass, path: List<String>, ofs: Int): DbPathEntry {
    val name = path[ofs]
    val attr = cls.attributes[name]
    if (attr == null) {
        throw errBadPath(path, ofs + 1)
    }

    val resultType = attr.type
    val resultClass = if (resultType is RInstanceRefType) resultType.rClass else null
    return DbPathEntry(name, resultType, resultClass)
}

private fun makeDbPathEntry(attr: RAttrib): DbPathEntry {
    val resultType = attr.type
    val resultClass = if (resultType is RInstanceRefType) resultType.rClass else null
    return DbPathEntry(attr.name, resultType, resultClass)
}

private fun makeDbPathExpr(cls: RAtClass, entries: List<DbPathEntry>): DbExpr {
    if (entries.isEmpty()) {
        return PathDbExpr(cls.type, cls, listOf(), null)
    }

    val last = entries[entries.size - 1]
    if (last.resultClass == null) {
        return makeDbPathExpr0(last.resultType, cls, entries.subList(0, entries.size - 1), last.name)
    } else {
        return makeDbPathExpr0(last.resultType, cls, entries, null)
    }
}

private fun makeDbPathExpr0(type: RType, cls: RAtClass, entries: List<DbPathEntry>, attr: String?): DbExpr {
    val steps = entries.map { PathDbExprStep(it.name, it.resultClass!!) }
    return PathDbExpr(type, cls, steps, attr)
}

private fun errBadPath(path: List<String>, errOfs: Int): CtError {
    val pathStr = path.subList(0, errOfs).joinToString(".")
    return CtError("bad_path_expr:$pathStr", "Inavlid path expression: '$pathStr'")
}

private class DbPathEntry(val name: String, val resultType: RType, val resultClass: RClass?)

private fun resolveName(ctx: CtExprContext, name: String): CtNameResolution {
    val loc = ctx.lookupOpt(name)
    if (loc != null) return CtNameResolution_Local(loc)

    val ns = S_LibFunctions.getNamespace(name)
    if (ns != null) return CtNameResolution_Namespace(ns)

    throw errUnknownName(name)
}

private fun resolveNameDb(ctx: CtDbExprContext, name: String): CtDbNameResolution {
    val cls = ctx.findClassByAlias(name)
    if (cls != null) return CtDbNameResolution_Class(cls)

    val loc = ctx.exprCtx.lookupOpt(name)
    if (loc != null) return CtDbNameResolution_Local(loc)

    val ns = S_LibFunctions.getNamespace(name)
    if (ns != null) return CtDbNameResolution_Namespace(ns)

    val attrs = ctx.findAttributesByName(name)
    if (attrs.size > 1) {
        val n = attrs.size
        throw CtError("at_attr_name_ambig:$name:$n", "Multiple attributes with name '$name': $n")
    }
    if (attrs.size == 1) return CtDbNameResolution_Attr(attrs[0])

    throw errUnknownName(name)
}

private sealed class CtNameResolution
private class CtNameResolution_Local(val loc: CtScopeEntry): CtNameResolution()
private class CtNameResolution_Namespace(val ns: S_LibNamespace): CtNameResolution()

private sealed class CtDbNameResolution
private class CtDbNameResolution_Class(val cls: RAtClass): CtDbNameResolution()
private class CtDbNameResolution_Attr(val attr: DbClassAttr): CtDbNameResolution()
private class CtDbNameResolution_Local(val loc: CtScopeEntry): CtDbNameResolution()
private class CtDbNameResolution_Namespace(val ns: S_LibNamespace): CtDbNameResolution()

private fun errUnknownName(name: String): CtError = CtError("unknown_name:$name", "Unknown name: '$name'")
private fun errUnknownFunction(name: String): CtError = CtError("unknown_fn:$name", "Unknown function: '$name'")
