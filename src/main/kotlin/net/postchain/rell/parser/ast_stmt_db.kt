package net.postchain.rell.parser

import net.postchain.rell.model.*

class S_UpdateStatement(
        val from: List<S_AtExprFrom>,
        val where: S_AtExprWhere,
        val what: List<S_NameExprPair>): S_Statement()
{
    override fun compile(ctx: ExprCompilationContext): RStatement {
        ctx.checkDbUpdateAllowed()

        val rFrom = S_AtExpr.compileFrom(ctx, from)
        val cls = rFrom[0]
        val extraClasses = rFrom.subList(1, rFrom.size)

        val dbCtx = DbCompilationContext(null, ctx, rFrom)
        val dbWhere = where.compile(dbCtx)
        val dbWhat = compileWhat(cls.rClass, dbCtx)

        return RUpdateStatement(cls, extraClasses, dbWhere, dbWhat)
    }

    private fun compileWhat(cls: RClass, dbCtx: DbCompilationContext): List<RUpdateStatementAttr> {
        val dbWhat = what.map { it.expr.compileDb(dbCtx) }
        val types = dbWhat.map { it.type }
        val attrs = S_UpdateExprMatcher.matchExpressions(cls, what, types, true)
        val updAttrs = attrs.withIndex().map { (idx, attr) -> RUpdateStatementAttr(attr, dbWhat[idx]) }
        return updAttrs
    }
}

class S_DeleteStatement(val from: List<S_AtExprFrom>, val where: S_AtExprWhere): S_Statement() {
    override fun compile(ctx: ExprCompilationContext): RStatement {
        ctx.checkDbUpdateAllowed()

        val rFrom = S_AtExpr.compileFrom(ctx, from)
        val cls = rFrom[0]
        val extraClasses = rFrom.subList(1, rFrom.size)

        val dbCtx = DbCompilationContext(null, ctx, rFrom)
        val dbWhere = where.compile(dbCtx)

        return RDeleteStatement(cls, extraClasses, dbWhere)
    }
}

object S_UpdateExprMatcher {
    fun matchExpressions(cls: RClass, exprs: List<S_NameExprPair>, types: List<RType>, mutableOnly: Boolean)
            : List<RAttrib>
    {
        val explicitExprs = matchExplicitExprs(cls, exprs, types, mutableOnly)
        val implicitExprs = matchImplicitExprs(cls, exprs, types, mutableOnly)

        checkImplicitExprsConflicts1(explicitExprs, implicitExprs)
        checkImplicitExprsConflicts2(implicitExprs)

        val combinedExprs = (explicitExprs + implicitExprs).sortedBy { it.index }.toList()
        combinedExprs.withIndex().forEach { check(it.index == it.value.index ) }

        val result = combinedExprs.map { (idx, attr) -> attr }.toList()
        return result
    }

    private fun matchExplicitExprs(cls: RClass, exprs: List<S_NameExprPair>, types: List<RType>, mutableOnly: Boolean)
            : List<IndexedValue<RAttrib>>
    {
        val explicitNames = mutableSetOf<String>()
        val explicitExprs = mutableListOf<IndexedValue<RAttrib>>()

        for ((idx, pair) in exprs.withIndex()) {
            if (pair.name != null) {
                val name = pair.name
                val attr = cls.attributes[name]
                if (attr == null) {
                    throw CtError("attr_unknown_name:${name}", "Unknown attribute: ${name}")
                } else if (!explicitNames.add(name)) {
                    throw CtError("attr_dup_name:${name}", "Attribute already specified: ${name}")
                } else if (mutableOnly && !attr.mutable) {
                    throw CtError("update_attr_not_mutable:${name}", "Attribute is not mutable: '${name}'")
                }
                val type = types[idx]
                typeCheck(idx, attr, type)
                explicitExprs.add(IndexedValue(idx, attr))
            }
        }

        return explicitExprs.toList()
    }

    private fun matchImplicitExprs(cls: RClass, exprs: List<S_NameExprPair>, types: List<RType>, mutableOnly: Boolean)
            : List<IndexedValue<RAttrib>>
    {
        val result = mutableListOf<IndexedValue<RAttrib>>()

        for ((idx, pair) in exprs.withIndex()) {
            if (pair.name == null) {
                val type = types[idx]
                val attr = implicitMatch(cls, idx, pair.expr, type, mutableOnly)
                result.add(IndexedValue(idx, attr))
            }
        }

        return result.toList()
    }

    private fun checkImplicitExprsConflicts1(
            explicitExprs: List<IndexedValue<RAttrib>>,
            implicitExprs: List<IndexedValue<RAttrib>>)
    {
        val explicitNames = explicitExprs.map { ( idx, attr ) -> attr.name }.toSet()

        for ((idx, attr) in implicitExprs) {
            val name = attr.name
            if (name in explicitNames) {
                throw CtError("attr_implic_explic:$idx:$name",
                        "Expression #${idx + 1} matches attribute '$name', but it is specified explicitly")
            }
        }
    }

    private fun checkImplicitExprsConflicts2(implicitExprs: List<IndexedValue<RAttrib>>) {
        val implicitConflicts = mutableMapOf<String, MutableList<Int>>()
        for ((idx, attr) in implicitExprs) {
            implicitConflicts[attr.name] = mutableListOf()
        }

        for ((idx, attr) in implicitExprs) {
            implicitConflicts[attr.name]!!.add(idx)
        }

        for ((name, list) in implicitConflicts) {
            if (list.size > 1) {
                throw CtError("attr_implic_multi:$name:${list.joinToString(",")}",
                        "Multiple expressions match attribute '$name': ${list.joinToString { "" + (it + 1) }}")
            }
        }
    }

    private fun implicitMatch(cls: RClass, idx: Int, expr: S_Expression, type: RType, mutableOnly: Boolean): RAttrib {
        val byName = implicitMatchByName(cls, expr)
        if (byName != null) {
            typeCheck(idx, byName, type)
            if (mutableOnly && !byName.mutable) {
                throw CtError("update_attr_not_mutable:${byName.name}", "Attribute is not mutable: '${byName.name}'")
            }
            return byName
        }

        val byType = implicitMatchByType(cls, type, mutableOnly)
        if (byType.size == 1) {
            return byType[0]
        } else if (byType.size > 1) {
            throw CtError("attr_implic_multi:$idx:${byType.joinToString(","){it.name}}",
                    "Multiple attributes match expression #${idx + 1}: ${byType.joinToString(", ")}")
        }

        throw CtError("attr_implic_unknown:$idx", "Cannot find attribute for expression #${idx + 1}")
    }

    private fun implicitMatchByName(cls: RClass, expr: S_Expression): RAttrib? {
        if (!(expr is S_NameExpr)) { //TODO consider not using "is"
            return null
        }
        return cls.attributes[expr.name]
    }

    private fun implicitMatchByType(cls: RClass, type: RType, mutableOnly: Boolean): List<RAttrib> {
        return cls.attributes.values.filter{ it.type == type && (!mutableOnly || it.mutable) }.toList()
    }

    private fun typeCheck(idx: Int, attr: RAttrib, exprType: RType) {
        if (!attr.type.accepts(exprType)) {
            throw CtError("attr_bad_type:$idx:${attr.name}:${attr.type.toStrictString()}:${exprType.toStrictString()}",
                    "Attribute type missmatch for '${attr.name}': ${exprType} instead of ${attr.type}")
        }
    }
}
