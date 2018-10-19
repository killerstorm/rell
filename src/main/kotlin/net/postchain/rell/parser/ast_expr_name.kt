package net.postchain.rell.parser

import net.postchain.rell.model.*

class S_NameExpr(val name: String): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr {
        val entry = ctx.lookup(name)
        return entry.toVarExpr()
    }

    override fun compileDb(ctx: DbCompilationContext): DbExpr {
        val dbExpr = compileDbPathExprOpt(ctx, listOf(name), true)
        if (dbExpr != null) {
            return dbExpr
        }

        val localAttr = ctx.exprCtx.lookup(name)
        val rExpr = localAttr.toVarExpr()
        return InterpretedDbExpr(rExpr)
    }

    override fun compileDbWhere(ctx: DbCompilationContext, idx: Int): DbExpr {
        val clsAttrs = ctx.findAttributesByName(name)
        val localAttr = ctx.exprCtx.lookupOpt(name)

        if (clsAttrs.isEmpty() || localAttr == null) {
            return compileDb(ctx)
        }

        // There is a class attribute and a local variable with such name -> implicit where condition.

        val argType = localAttr.type
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
        val localAttrExpr = localAttr.toVarExpr()
        return BinaryDbExpr(RBooleanType, DbBinaryOp_Eq, clsAttrExpr, InterpretedDbExpr(localAttrExpr))
    }

    override fun compileCall(ctx: ExprCompilationContext, args: List<RExpr>): RExpr {
        val fn = ctx.modCtx.getFunction(name)
        return fn.compileCall(args)
    }

    override fun compileCallDb(ctx: DbCompilationContext, args: List<DbExpr>): DbExpr {
        val fn = ctx.exprCtx.modCtx.getFunction(name)
        return fn.compileCallDb(args)
    }

    override fun iteratePathExpr(path: MutableList<String>): Boolean {
        path.add(name)
        return true
    }
}

class S_AttributeExpr(val base: S_Expression, val name: String): S_Expression() {
    override fun compile(ctx: ExprCompilationContext): RExpr {
        val rBase = base.compile(ctx)
        val type = rBase.type

        if (type is RTupleType) {
            val idx = type.fields.indexOfFirst { it.name == name }
            if (idx == -1) {
                throw CtError("unknown_member:$name", "Unknown member: $name")
            }
            return RTupleFieldExpr(type.fields[idx].type, rBase, idx)
        } else {
            TODO(type.toStrictString())
        }
    }

    override fun compileDb(ctx: DbCompilationContext): DbExpr {
        val mutPath = mutableListOf<String>()

        if (iteratePathExpr(mutPath)) {
            mutPath.reverse()

            val dbExpr = compileDbPathExprOpt(ctx, mutPath.toList(), true)
            if (dbExpr != null) {
                return dbExpr
            }
        }

        return delegateCompileDb(ctx)
    }

    override fun compileCall(ctx: ExprCompilationContext, args: List<RExpr>): RExpr {
        val rBase = base.compile(ctx)
        return compileCall0(rBase, args)
    }

    override fun compileCallDb(ctx: DbCompilationContext, args: List<DbExpr>): DbExpr {
        val dbBase = base.compileDb(ctx)

        if (dbBase is InterpretedDbExpr) {
            val rArgs = args.filter { it is InterpretedDbExpr }.map { (it as InterpretedDbExpr).expr }
            if (rArgs.size == args.size) {
                val rExpr = compileCall0(dbBase.expr, rArgs)
                return InterpretedDbExpr(rExpr)
            }
        }

        val fn = S_SysMemberFunctions.getMemberFunction(dbBase.type, name)
        return fn.compileMemberCallDb(dbBase, args)
    }

    private fun compileCall0(rBase: RExpr, args: List<RExpr>): RExpr {
        val fn = S_SysMemberFunctions.getMemberFunction(rBase.type, name)
        return fn.compileMemberCall(rBase, args)
    }

    override fun iteratePathExpr(path: MutableList<String>): Boolean {
        path.add(name)
        return base.iteratePathExpr(path)
    }
}

internal fun compileDbPathExpr(ctx: DbCompilationContext, path: List<String>, classAliasAllowed: Boolean): DbExpr {
    val dbExpr = compileDbPathExprOpt(ctx, path, classAliasAllowed)
    if (dbExpr == null) {
        throw errBadPath(path, path.size)
    }
    return dbExpr
}

private fun compileDbPathExprOpt(ctx: DbCompilationContext, path: List<String>, classAliasAllowed: Boolean): DbExpr? {
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
    val resultClass = if (resultType is RInstanceRefType) resultType.rclass else null
    return PathEntry(name, resultType, resultClass)
}

private fun errBadPath(path: List<String>, errOfs: Int): CtError {
    val pathStr = path.subList(0, errOfs).joinToString(".")
    return CtError("bad_path_expr:$pathStr", "Inavlid path expression: '$pathStr'")
}

private class PathEntry(val name: String, val resultType: RType, val resultClass: RClass?)
