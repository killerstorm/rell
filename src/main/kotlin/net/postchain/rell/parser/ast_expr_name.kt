package net.postchain.rell.parser

import net.postchain.rell.model.*
import java.util.*

class S_NameExpr(val name: String): S_Expression() {
    override fun compile(ctx: CtExprContext): RExpr {
        val entry = ctx.lookup(name)
        return entry.toVarExpr()
    }

    override fun compileDestination(ctx: CtExprContext): RDestinationExpr {
        val entry = ctx.lookup(name)
        if (!entry.modifiable) {
            throw CtError("stmt_assign_val:$name", "Value of '$name' cannot be changed")
        }
        return entry.toVarExpr()
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        val dbExpr = compileDbPathExprOpt(ctx, listOf(name), true)
        if (dbExpr != null) {
            return dbExpr
        }

        val localVar = ctx.exprCtx.lookup(name)
        val rExpr = localVar.toVarExpr()
        return InterpretedDbExpr(rExpr)
    }

    override fun compileDbWhere(ctx: CtDbExprContext, idx: Int): DbExpr {
        val clsAttrs = ctx.findAttributesByName(name)
        val localVar = ctx.exprCtx.lookupOpt(name)

        if (clsAttrs.isEmpty() || localVar == null) {
            return compileDb(ctx)
        }

        // There is a class attribute and a local variable with such name -> implicit where condition.

        val argType = localVar.type
        val clsAttrsByType = clsAttrs.filter { S_BinaryOp_EqNe.checkTypes(it.type, argType) }

        if (clsAttrsByType.isEmpty()) {
            val typeStr = argType.toStrictString()
            throw CtError("at_attr_type:$idx:$name:$typeStr",
                    "Attribute '$name' type missmatch: expected $typeStr")
        } else if (clsAttrsByType.size > 1) {
            val n = clsAttrs.size
            throw CtError("at_attr_name_ambig:$idx:$name:$n", "Multiple attributes match '$name': $n")
        }

        val clsAttr = clsAttrsByType[0]
        val attrType = clsAttr.type
        if (!S_BinaryOp_EqNe.checkTypes(attrType, argType)) {
            throw CtError("at_param_attr_type_missmatch:$name:$attrType:$argType",
                    "Parameter type does not match attribute type for '$name': $argType instead of $attrType")
        }

        val clsAttrExpr = PathDbExpr(clsAttr.type, clsAttr.cls, listOf(), clsAttr.name)
        val localAttrExpr = localVar.toVarExpr()
        return BinaryDbExpr(RBooleanType, DbBinaryOp_Eq, clsAttrExpr, InterpretedDbExpr(localAttrExpr))
    }

    override fun compileCall(ctx: CtExprContext, args: List<RExpr>): RExpr {
        val fn = ctx.entCtx.modCtx.getFunction(name)
        return fn.compileCall(args)
    }

    override fun compileCallDb(ctx: CtDbExprContext, args: List<DbExpr>): DbExpr {
        val fn = ctx.exprCtx.entCtx.modCtx.getFunction(name)
        return fn.compileCallDb(args)
    }

    override fun discoverFullPathExpr(path: MutableList<String>): List<String>? {
        path.add(name)
        return path.reversed()
    }
}

class S_AttributeExpr(val base: S_Expression, val name: String): S_Expression() {
    override fun compile(ctx: CtExprContext): RExpr {
        val mutPath = mutableListOf<String>()
        val (deepBase, path) = discoverTailPathExpr(mutPath)
        val rBase = deepBase.compile(ctx)
        return compilePath(ctx, rBase, path)
    }

    override fun compileDb(ctx: CtDbExprContext): DbExpr {
        val mutPath = mutableListOf<String>()
        val fullPath = discoverFullPathExpr(mutPath)

        if (fullPath != null) {
            val dbExpr = compileDbPathExprOpt(ctx, fullPath, true)
            if (dbExpr != null) {
                return dbExpr
            }
        }

        return delegateCompileDb(ctx)
    }

    override fun compileCall(ctx: CtExprContext, args: List<RExpr>): RExpr {
        val rBase = base.compile(ctx)
        return compileCall0(rBase, args)
    }

    override fun compileCallDb(ctx: CtDbExprContext, args: List<DbExpr>): DbExpr {
        val dbBase = base.compileDb(ctx)

        if (dbBase is InterpretedDbExpr) {
            val rArgs = args.filter { it is InterpretedDbExpr }.map { (it as InterpretedDbExpr).expr }
            if (rArgs.size == args.size) {
                val rExpr = compileCall0(dbBase.expr, rArgs)
                return InterpretedDbExpr(rExpr)
            }
        }

        val fn = S_LibFunctions.getMemberFunction(dbBase.type, name)
        return fn.compileCallDb(dbBase, args)
    }

    private fun compileCall0(rBase: RExpr, args: List<RExpr>): RExpr {
        val fn = S_LibFunctions.getMemberFunction(rBase.type, name)
        return fn.compileCall(rBase, args)
    }

    override fun discoverFullPathExpr(path: MutableList<String>): List<String>? {
        path.add(name)
        return base.discoverFullPathExpr(path)
    }

    override fun discoverTailPathExpr(path: MutableList<String>): Pair<S_Expression, List<String>> {
        path.add(name)
        return base.discoverTailPathExpr(path)
    }

    companion object {
        private fun compilePath(ctx: CtExprContext, rBase: RExpr, path: List<String>): RExpr {
            var headBase = rBase
            var tailPath = LinkedList(path)

            while (!tailPath.isEmpty()) {
                val type = headBase.type
                if (type is RTupleType) {
                    headBase = compilePathStepTuple(headBase, type, tailPath)
                } else if (type is RInstanceRefType) {
                    headBase = compilePathStepDataObject(ctx, headBase, type, tailPath)
                } else {
                    TODO(type.toStrictString())
                }
            }

            return headBase
        }

        private fun compilePathStepTuple(base: RExpr, type: RTupleType, path: Queue<String>): RExpr {
            val step = path.remove()
            val idx = type.fields.indexOfFirst { it.name == step }
            if (idx == -1) {
                throw CtError("unknown_member:${type.toStrictString()}:$step",
                        "Unknown member for type ${type.toStrictString()}: '$step'")
            }
            return RTupleFieldExpr(type.fields[idx].type, base, idx)
        }

        private fun compilePathStepDataObject(
                ctx: CtExprContext,
                base: RExpr,
                type: RInstanceRefType,
                path: Queue<String>): RExpr
        {
            val atClass = RAtClass(type.rClass, "", 0)
            val from = listOf(atClass)
            val dbCtx = CtDbExprContext(null, ctx, from)

            val pathList = path.toList()
            path.clear()
            val what = compileDbPathExpr(dbCtx, pathList, false)

            val whereLeft = PathDbExpr(type, atClass, listOf(), null)
            val whereRight = InterpretedDbExpr(base)
            val where = BinaryDbExpr(RBooleanType, DbBinaryOp_Eq, whereLeft, whereRight)

            val atExprBase = RAtExprBase(from, listOf(what), where, listOf(), false, false)
            return RAtExpr(what.type, atExprBase, null, RAtExprRowTypeSimple)
        }
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
        return compilePathExpr0(cls, path, 1)
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
        return compilePathExpr0(attr.cls, path, 0)
    } else {
        throw IllegalStateException("impossible")
    }
}

private fun compilePathExpr0(baseCls: RAtClass, path: List<String>, startOfs: Int): DbExpr {
    var cls = baseCls.rClass
    var resType: RType = RInstanceRefType(cls)
    var attr: String? = null
    val steps = mutableListOf<PathDbExprStep>()

    var ofs = startOfs
    var errOfs = ofs

    while (ofs < path.size) {
        val name = path[ofs]
        errOfs = ofs + 1

        val entry = resolvePathEntry(cls, name)
        if (entry == null) {
            break
        }

        ++ofs
        resType = entry.resultType

        if (entry.resultClass != null) {
            cls = entry.resultClass
            steps.add(PathDbExprStep(name, cls))
        } else {
            attr = name
            break
        }
    }

    if (ofs < path.size) {
        throw errBadPath(path, errOfs)
    }

    return PathDbExpr(resType, baseCls, steps, attr)
}

private fun resolvePathEntry(cls: RClass, name: String): PathEntry? {
    val attr = cls.attributes[name]
    if (attr == null) {
        return null
    }

    val resultType = attr.type
    val resultClass = if (resultType is RInstanceRefType) resultType.rClass else null
    return PathEntry(name, resultType, resultClass)
}

private fun errBadPath(path: List<String>, errOfs: Int): CtError {
    val pathStr = path.subList(0, errOfs).joinToString(".")
    return CtError("bad_path_expr:$pathStr", "Inavlid path expression: '$pathStr'")
}

private class PathEntry(val name: String, val resultType: RType, val resultClass: RClass?)
