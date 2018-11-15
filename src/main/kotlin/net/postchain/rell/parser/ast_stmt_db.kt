package net.postchain.rell.parser

import net.postchain.rell.model.*

sealed class S_UpdateWhat(val expr: S_Expression) {
    abstract fun opCode(): S_AssignOpCode?
    abstract fun pos(): S_Pos
    abstract fun toNameExprPair(): S_NameExprPair
}

class S_UpdateWhatAnon(expr: S_Expression): S_UpdateWhat(expr) {
    override fun opCode(): S_AssignOpCode? = null
    override fun pos() = expr.startPos
    override fun toNameExprPair(): S_NameExprPair = S_NameExprPair(null, expr)
}

class S_UpdateWhatNamed(val name: S_Name, val op: S_Node<S_AssignOpCode>, expr: S_Expression): S_UpdateWhat(expr) {
    override fun opCode(): S_AssignOpCode? = op.value
    override fun pos() = name.pos
    override fun toNameExprPair(): S_NameExprPair = S_NameExprPair(name, expr)
}

class S_UpdateStatement(
        val pos: S_Pos,
        val from: List<S_AtExprFrom>,
        val where: S_AtExprWhere,
        val what: List<S_UpdateWhat>): S_Statement()
{
    override fun compile(ctx: CtExprContext): RStatement {
        ctx.entCtx.checkDbUpdateAllowed(pos)

        val rFrom = S_AtExpr.compileFrom(ctx, from)
        val cls = rFrom[0]
        val extraClasses = rFrom.subList(1, rFrom.size)

        val dbCtx = CtDbExprContext(null, ctx, rFrom)
        val dbWhere = where.compile(dbCtx)
        val dbWhat = compileWhat(cls.rClass, dbCtx)

        return RUpdateStatement(cls, extraClasses, dbWhere, dbWhat)
    }

    private fun compileWhat(cls: RClass, dbCtx: CtDbExprContext): List<RUpdateStatementWhat> {
        val dbWhat = what.map { it.expr.compileDb(dbCtx) }
        val types = dbWhat.map { it.type }
        val whatPairs = what.map { it.toNameExprPair() }
        val attrs = matchExprs(cls, whatPairs, types)
        val updAttrs = attrs.withIndex().map { (idx, attr) ->
            val w = what[idx]
            compileWhatExpr(w.pos(), attr, dbWhat[idx], w.opCode())
        }
        return updAttrs
    }

    private fun matchExprs(cls: RClass, exprs: List<S_NameExprPair>, types: List<RType>): List<RAttrib> {
        val explicitExprs = S_UpdateExprMatcher.matchExplicitExprs(cls, exprs, true)
        return S_UpdateExprMatcher.matchImplicitExprs(cls, exprs, types, explicitExprs, true)
    }

    private fun compileWhatExpr(pos: S_Pos, attr: RAttrib, expr: DbExpr, opCode: S_AssignOpCode?): RUpdateStatementWhat {
        val op = if (opCode == null) S_AssignOp_Eq else opCode.op
        return op.compileDbUpdate(pos, attr, expr)
    }
}

class S_DeleteStatement(val pos: S_Pos, val from: List<S_AtExprFrom>, val where: S_AtExprWhere): S_Statement() {
    override fun compile(ctx: CtExprContext): RStatement {
        ctx.entCtx.checkDbUpdateAllowed(pos)

        val rFrom = S_AtExpr.compileFrom(ctx, from)
        val cls = rFrom[0]
        val extraClasses = rFrom.subList(1, rFrom.size)

        val dbCtx = CtDbExprContext(null, ctx, rFrom)
        val dbWhere = where.compile(dbCtx)

        return RDeleteStatement(cls, extraClasses, dbWhere)
    }
}

object S_UpdateExprMatcher {
    fun matchExpressions(cls: RClass, exprs: List<S_NameExprPair>, types: List<RType>, mutableOnly: Boolean)
            : List<RAttrib>
    {
        val explicitExprs = matchExplicitExprs(cls, exprs, mutableOnly)
        checkExplicitExprTypes(exprs, explicitExprs, types)
        return matchImplicitExprs(cls, exprs, types, explicitExprs, mutableOnly)
    }

    fun matchExplicitExprs(cls: RClass, exprs: List<S_NameExprPair>, mutableOnly: Boolean)
            : List<IndexedValue<RAttrib>>
    {
        val explicitNames = mutableSetOf<String>()
        val explicitExprs = mutableListOf<IndexedValue<RAttrib>>()

        for ((idx, pair) in exprs.withIndex()) {
            if (pair.name != null) {
                val name = pair.name
                val attr = cls.attributes[name.str]
                if (attr == null) {
                    throw CtError(name.pos, "attr_unknown_name:${name.str}", "Unknown attribute: '${name.str}'")
                } else if (!explicitNames.add(name.str)) {
                    throw CtError(name.pos, "attr_dup_name:${name.str}", "Attribute already specified: '${name.str}'")
                } else if (mutableOnly && !attr.mutable) {
                    throw CtError(name.pos, "update_attr_not_mutable:${name.str}", "Attribute is not mutable: '${name.str}'")
                }
                explicitExprs.add(IndexedValue(idx, attr))
            }
        }

        return explicitExprs.toList()
    }

    fun checkExplicitExprTypes(exprs: List<S_NameExprPair>, explicitExprs: List<IndexedValue<RAttrib>>, types: List<RType>) {
        for ((idx, attr) in explicitExprs) {
            val pos = exprs[idx].expr.startPos
            val type = types[idx]
            typeCheck(pos, idx, attr, type)
        }
    }

    fun matchImplicitExprs(
            cls: RClass,
            exprs: List<S_NameExprPair>,
            types: List<RType>,
            explicitExprs: List<IndexedValue<RAttrib>>,
            mutableOnly: Boolean
    ) : List<RAttrib>
    {
        val implicitExprs = matchImplicitExprs0(cls, exprs, types, mutableOnly)

        checkImplicitExprsConflicts1(exprs, explicitExprs, implicitExprs)
        checkImplicitExprsConflicts2(exprs, implicitExprs)

        val combinedExprs = (explicitExprs + implicitExprs).sortedBy { it.index }.toList()
        combinedExprs.withIndex().forEach { check(it.index == it.value.index ) }

        val result = combinedExprs.map { (_, attr) -> attr }.toList()
        return result
    }

    private fun matchImplicitExprs0(cls: RClass, exprs: List<S_NameExprPair>, types: List<RType>, mutableOnly: Boolean)
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
            exprs: List<S_NameExprPair>,
            explicitExprs: List<IndexedValue<RAttrib>>,
            implicitExprs: List<IndexedValue<RAttrib>>)
    {
        val explicitNames = explicitExprs.map { (_, attr) -> attr.name }.toSet()

        for ((idx, attr) in implicitExprs) {
            val name = attr.name
            if (name in explicitNames) {
                throw CtError(
                        exprs[idx].expr.startPos,
                        "attr_implic_explic:$idx:$name",
                        "Expression #${idx + 1} matches attribute '$name' which is already specified"
                )
            }
        }
    }

    private fun checkImplicitExprsConflicts2(exprs: List<S_NameExprPair>, implicitExprs: List<IndexedValue<RAttrib>>) {
        val implicitConflicts = mutableMapOf<String, MutableList<Int>>()
        for ((_, attr) in implicitExprs) {
            implicitConflicts[attr.name] = mutableListOf()
        }

        for ((idx, attr) in implicitExprs) {
            implicitConflicts[attr.name]!!.add(idx)
        }

        for ((name, list) in implicitConflicts) {
            if (list.size > 1) {
                val pos = exprs[list[0]].expr.startPos
                throw CtError(pos, "attr_implic_multi:$name:${list.joinToString(",")}",
                        "Multiple expressions match attribute '$name': ${list.joinToString { "#" + (it + 1) }}")
            }
        }
    }

    private fun implicitMatch(cls: RClass, idx: Int, expr: S_Expression, type: RType, mutableOnly: Boolean): RAttrib {
        val byName = implicitMatchByName(cls, expr)
        if (byName != null) {
            typeCheck(expr.startPos, idx, byName, type)
            if (mutableOnly && !byName.mutable) {
                throw CtError(expr.startPos, "update_attr_not_mutable:${byName.name}", "Attribute is not mutable: '${byName.name}'")
            }
            return byName
        }

        val byType = implicitMatchByType(cls, type, mutableOnly)
        if (byType.size == 1) {
            return byType[0]
        } else if (byType.size > 1) {
            throw CtError(expr.startPos, "attr_implic_multi:$idx:${byType.joinToString(","){it.name}}",
                    "Multiple attributes match expression #${idx + 1}: ${byType.joinToString(", "){it.name}}")
        }

        throw CtError(expr.startPos, "attr_implic_unknown:$idx",
                "Cannot find attribute for expression #${idx + 1} of type ${type.toStrictString()}")
    }

    private fun implicitMatchByName(cls: RClass, expr: S_Expression): RAttrib? {
        if (expr !is S_NameExpr) { //TODO consider not using "is"
            return null
        }
        return cls.attributes[expr.name.str]
    }

    private fun implicitMatchByType(cls: RClass, type: RType, mutableOnly: Boolean): List<RAttrib> {
        return cls.attributes.values.filter{ it.type == type && (!mutableOnly || it.mutable) }.toList()
    }

    private fun typeCheck(pos: S_Pos, idx: Int, attr: RAttrib, exprType: RType) {
        if (attr.type != exprType) {
            throw CtError(
                    pos,
                    "attr_bad_type:$idx:${attr.name}:${attr.type.toStrictString()}:${exprType.toStrictString()}",
                    "Attribute type missmatch for '${attr.name}': ${exprType} instead of ${attr.type}"
            )
        }
    }
}
