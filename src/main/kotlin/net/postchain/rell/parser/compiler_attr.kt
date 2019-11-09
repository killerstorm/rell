package net.postchain.rell.parser

import net.postchain.rell.model.*

object C_AttributeResolver {
    fun resolveCreate(
            ctx: C_ExprContext,
            attributes: Map<String, R_Attrib>,
            exprs: List<S_NameExprPair>,
            pos: S_Pos
    ): C_CreateAttributes
    {
        val values = exprs.map { it.expr.compile(ctx).value() }
        val rExprs = values.map { it.toRExpr() }
        val types = rExprs.map { it.type }

        val attrs = matchCreateAttrs(attributes, exprs, types)
        val attrExprs = attrs.mapIndexed { idx, attr ->
            val rExpr = rExprs[idx]
            R_CreateExprAttr_Specified(attr, rExpr)
        }

        val attrExprsDef = attrExprs + matchDefaultExprs(attributes, attrExprs)
        checkMissingAttrs(attributes, attrExprsDef, pos)

        for ((idx, attr) in attrs.withIndex()) {
            if (!attr.canSetInCreate) {
                val name = attr.name
                val expr = exprs[idx].expr
                throw C_Error(expr.startPos, "create_attr_cantset:$name", "Cannot set value of system attribute '$name'")
            }
        }

        val exprFacts = C_ExprVarFacts.forSubExpressions(values)
        return C_CreateAttributes(attrExprsDef, exprFacts)
    }

    private fun matchCreateAttrs(
            attributes: Map<String, R_Attrib>,
            exprs: List<S_NameExprPair>,
            types: List<R_Type>
    ): List<R_Attrib> {
        val explicitExprs = matchExplicitExprs(attributes, exprs, false)
        checkExplicitExprTypes(exprs, explicitExprs, types)
        return matchImplicitExprs(attributes, exprs, types, explicitExprs, false)
    }

    private fun matchDefaultExprs(attributes: Map<String, R_Attrib>, attrExprs: List<R_CreateExprAttr>): List<R_CreateExprAttr> {
        val provided = attrExprs.map { it.attr.name }.toSet()
        return attributes.values.filter { it.hasExpr && it.name !in provided }.map { R_CreateExprAttr_Default(it) }
    }

    private fun checkMissingAttrs(attributes: Map<String, R_Attrib>, attrs: List<R_CreateExprAttr>, pos: S_Pos) {
        val names = attrs.map { it.attr.name }.toSet()

        val missing = (attributes.keys - names).sorted().toList()
        if (!missing.isEmpty()) {
            throw C_Error(pos, "attr_missing:${missing.joinToString(",")}",
                    "Attributes not specified: ${missing.joinToString()}")
        }
    }

    fun resolveUpdate(entity: R_Entity, exprs: List<S_NameExprPair>, types: List<R_Type>): List<R_Attrib> {
        val explicitExprs = matchExplicitExprs(entity.attributes, exprs, true)
        return matchImplicitExprs(entity.attributes, exprs, types, explicitExprs, true)
    }

    private fun matchExplicitExprs(attributes: Map<String, R_Attrib>, exprs: List<S_NameExprPair>, mutableOnly: Boolean)
            : List<IndexedValue<R_Attrib>>
    {
        val explicitNames = mutableSetOf<String>()
        val explicitExprs = mutableListOf<IndexedValue<R_Attrib>>()

        for ((idx, pair) in exprs.withIndex()) {
            if (pair.name != null) {
                val name = pair.name
                val attr = attributes[name.str]
                if (attr == null) {
                    throw C_Error(name.pos, "attr_unknown_name:${name.str}", "Unknown attribute: '${name.str}'")
                } else if (!explicitNames.add(name.str)) {
                    throw C_Error(name.pos, "attr_dup_name:${name.str}", "Attribute already specified: '${name.str}'")
                } else if (mutableOnly && !attr.mutable) {
                    throw C_Error(name.pos, "update_attr_not_mutable:${name.str}", "Attribute is not mutable: '${name.str}'")
                }
                explicitExprs.add(IndexedValue(idx, attr))
            }
        }

        return explicitExprs.toList()
    }

    private fun checkExplicitExprTypes(exprs: List<S_NameExprPair>, explicitExprs: List<IndexedValue<R_Attrib>>, types: List<R_Type>) {
        for ((idx, attr) in explicitExprs) {
            val pos = exprs[idx].expr.startPos
            val type = types[idx]
            typeCheck(pos, idx, attr, type)
        }
    }

    private fun matchImplicitExprs(
            attributes: Map<String, R_Attrib>,
            exprs: List<S_NameExprPair>,
            types: List<R_Type>,
            explicitExprs: List<IndexedValue<R_Attrib>>,
            mutableOnly: Boolean
    ) : List<R_Attrib>
    {
        val implicitExprs = matchImplicitExprs0(attributes, exprs, types, mutableOnly)
        val result = combineMatchedExprs(exprs, explicitExprs, implicitExprs)
        return result
    }

    private fun combineMatchedExprs(
            exprs: List<S_NameExprPair>,
            explicitExprs: List<IndexedValue<R_Attrib>>,
            implicitExprs: List<IndexedValue<R_Attrib>>
    ) : List<R_Attrib>
    {
        checkImplicitExprsConflicts1(exprs, explicitExprs, implicitExprs)
        checkImplicitExprsConflicts2(exprs, implicitExprs)

        val combinedExprs = (explicitExprs + implicitExprs).sortedBy { it.index }.toList()
        combinedExprs.withIndex().forEach { check(it.index == it.value.index ) }

        val result = combinedExprs.map { (_, attr) -> attr }.toList()
        return result
    }

    private fun matchImplicitExprs0(
            attributes: Map<String, R_Attrib>,
            exprs: List<S_NameExprPair>,
            types: List<R_Type>,
            mutableOnly: Boolean
    ): List<IndexedValue<R_Attrib>>
    {
        val result = mutableListOf<IndexedValue<R_Attrib>>()

        for ((idx, pair) in exprs.withIndex()) {
            if (pair.name == null) {
                val type = types[idx]
                val attr = implicitMatch(attributes, idx, pair.expr, type, mutableOnly)
                result.add(IndexedValue(idx, attr))
            }
        }

        return result.toList()
    }

    private fun checkImplicitExprsConflicts1(
            exprs: List<S_NameExprPair>,
            explicitExprs: List<IndexedValue<R_Attrib>>,
            implicitExprs: List<IndexedValue<R_Attrib>>)
    {
        val explicitNames = explicitExprs.map { (_, attr) -> attr.name }.toSet()

        for ((idx, attr) in implicitExprs) {
            val name = attr.name
            if (name in explicitNames) {
                throw C_Error(
                        exprs[idx].expr.startPos,
                        "attr_implic_explic:$idx:$name",
                        "Expression #${idx + 1} matches attribute '$name' which is already specified"
                )
            }
        }
    }

    private fun checkImplicitExprsConflicts2(exprs: List<S_NameExprPair>, implicitExprs: List<IndexedValue<R_Attrib>>) {
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
                throw C_Error(pos, "attr_implic_multi:$name:${list.joinToString(",")}",
                        "Multiple expressions match attribute '$name': ${list.joinToString { "#" + (it + 1) }}")
            }
        }
    }

    private fun implicitMatch(
            attributes: Map<String, R_Attrib>,
            idx: Int,
            expr: S_Expr,
            type: R_Type,
            mutableOnly: Boolean
    ): R_Attrib
    {
        val byName = implicitMatchByName(attributes, expr)
        if (byName != null) {
            typeCheck(expr.startPos, idx, byName, type)
            if (mutableOnly && !byName.mutable) {
                throw C_Errors.errAttrNotMutable(expr.startPos, byName.name)
            }
            return byName
        }

        val byType = implicitMatchByType(attributes, type, mutableOnly)
        if (byType.size == 1) {
            return byType[0]
        } else if (byType.size > 1) {
            throw C_Error(expr.startPos, "attr_implic_multi:$idx:${byType.joinToString(","){it.name}}",
                    "Multiple attributes match expression #${idx + 1}: ${byType.joinToString(", "){it.name}}")
        }

        throw C_Error(expr.startPos, "attr_implic_unknown:$idx",
                "Cannot find attribute for expression #${idx + 1} of type ${type.toStrictString()}")
    }

    private fun implicitMatchByName(attributes: Map<String, R_Attrib>, expr: S_Expr): R_Attrib? {
        val name = expr.asName()
        return if (name == null) null else attributes[name.str]
    }

    private fun implicitMatchByType(attributes: Map<String, R_Attrib>, type: R_Type, mutableOnly: Boolean): List<R_Attrib> {
        return attributes.values.filter{ it.type.isAssignableFrom(type) && (!mutableOnly || it.mutable) }.toList()
    }

    private fun typeCheck(pos: S_Pos, idx: Int, attr: R_Attrib, exprType: R_Type) {
        if (!attr.type.isAssignableFrom(exprType)) {
            throw C_Error(
                    pos,
                    "attr_bad_type:$idx:${attr.name}:${attr.type.toStrictString()}:${exprType.toStrictString()}",
                    "Attribute type mismatch for '${attr.name}': ${exprType} instead of ${attr.type}"
            )
        }
    }
}

class C_CreateAttributes(val rAttrs: List<R_CreateExprAttr>, val exprFacts: C_ExprVarFacts)
