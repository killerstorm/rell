/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Expr
import net.postchain.rell.compiler.ast.S_NameExprPair
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*

object C_AttributeResolver {
    fun resolveCreate(
            ctx: C_ExprContext,
            attributes: Map<String, R_Attribute>,
            exprs: List<S_NameExprPair>,
            pos: S_Pos
    ): C_CreateAttributes {
        val values = exprs.map { compileCreateArg(ctx, attributes, it).value() }
        val rExprs = values.map { it.toRExpr() }
        val types = rExprs.map { it.type }

        val attrs = matchCreateAttrs(ctx.msgCtx, attributes, exprs, types)
        val attrExprs = attrs.mapIndexed { idx, attr ->
            val rExpr = rExprs[idx]
            R_CreateExprAttr_Specified(attr, rExpr)
        }

        val attrExprsDef = attrExprs + matchDefaultExprs(attributes, attrExprs)
        checkMissingAttrs(attributes, attrExprsDef, pos)

        for ((idx, attr) in attrs.withIndex()) {
            val expr = exprs[idx].expr
            C_Errors.check(attr.canSetInCreate, expr.startPos) {
                val name = attr.name
                "create_attr_cantset:$name" to "Cannot set value of system attribute '$name'"
            }
        }

        val exprFacts = C_ExprVarFacts.forSubExpressions(values)
        return C_CreateAttributes(attrExprsDef, exprFacts)
    }

    private fun compileCreateArg(ctx: C_ExprContext, attributes: Map<String, R_Attribute>, expr: S_NameExprPair): C_Expr {
        val attr = if (expr.name != null) {
            attributes[expr.name.str]
        } else if (attributes.size == 1) {
            attributes.values.iterator().next()
        } else {
            null
        }
        val typeHint = C_TypeHint.ofType(attr?.type)
        return expr.expr.compile(ctx, typeHint)
    }

    private fun matchCreateAttrs(
            msgCtx: C_MessageContext,
            attributes: Map<String, R_Attribute>,
            exprs: List<S_NameExprPair>,
            types: List<R_Type>
    ): List<R_Attribute> {
        val explicitExprs = matchExplicitExprs(msgCtx, attributes, exprs, false)
        checkExplicitExprTypes(exprs, explicitExprs, types)
        return matchImplicitExprs(msgCtx, attributes, exprs, types, explicitExprs, false)
    }

    private fun matchDefaultExprs(attributes: Map<String, R_Attribute>, attrExprs: List<R_CreateExprAttr>): List<R_CreateExprAttr> {
        val provided = attrExprs.map { it.attr.name }.toSet()
        return attributes.values.filter { it.hasExpr && it.name !in provided }.map { R_CreateExprAttr_Default(it) }
    }

    private fun checkMissingAttrs(attributes: Map<String, R_Attribute>, attrs: List<R_CreateExprAttr>, pos: S_Pos) {
        val names = attrs.map { it.attr.name }.toSet()

        val missing = (attributes.keys - names).sorted().toList()
        C_Errors.check(missing.isEmpty(), pos) {
            "attr_missing:${missing.joinToString(",")}" to "Attributes not specified: ${missing.joinToString()}"
        }
    }

    fun resolveUpdate(msgCtx: C_MessageContext, entity: R_Entity, exprs: List<S_NameExprPair>, types: List<R_Type>): List<R_Attribute> {
        val explicitExprs = matchExplicitExprs(msgCtx, entity.attributes, exprs, entity.flags.canUpdate)
        return matchImplicitExprs(msgCtx, entity.attributes, exprs, types, explicitExprs, true)
    }

    private fun matchExplicitExprs(
            msgCtx: C_MessageContext,
            attributes: Map<String, R_Attribute>,
            exprs: List<S_NameExprPair>,
            mutableOnly: Boolean
    ): List<IndexedValue<R_Attribute>> {
        val explicitNames = mutableSetOf<String>()
        val explicitExprs = mutableListOf<IndexedValue<R_Attribute>>()

        for ((idx, pair) in exprs.withIndex()) {
            if (pair.name != null) {
                val name = pair.name
                val attrZ = attributes[name.str]
                val attr = C_Errors.checkNotNull(attrZ, name.pos) { "attr_unknown_name:${name.str}" to "Unknown attribute: '${name.str}'" }

                C_Errors.check(explicitNames.add(name.str), name.pos) {
                    "attr_dup_name:${name.str}" to "Attribute already specified: '${name.str}'"
                }

                if (mutableOnly && !attr.mutable) {
                    msgCtx.error(name.pos, "update_attr_not_mutable:${name.str}", "Attribute is not mutable: '${name.str}'")
                }

                explicitExprs.add(IndexedValue(idx, attr))
            }
        }

        return explicitExprs.toList()
    }

    private fun checkExplicitExprTypes(exprs: List<S_NameExprPair>, explicitExprs: List<IndexedValue<R_Attribute>>, types: List<R_Type>) {
        for ((idx, attr) in explicitExprs) {
            val pos = exprs[idx].expr.startPos
            val type = types[idx]
            typeCheck(pos, idx, attr, type)
        }
    }

    private fun matchImplicitExprs(
            msgCtx: C_MessageContext,
            attributes: Map<String, R_Attribute>,
            exprs: List<S_NameExprPair>,
            types: List<R_Type>,
            explicitExprs: List<IndexedValue<R_Attribute>>,
            mutableOnly: Boolean
    ) : List<R_Attribute> {
        val implicitExprs = matchImplicitExprs0(msgCtx, attributes, exprs, types, mutableOnly)
        val result = combineMatchedExprs(exprs, explicitExprs, implicitExprs)
        return result
    }

    private fun combineMatchedExprs(
            exprs: List<S_NameExprPair>,
            explicitExprs: List<IndexedValue<R_Attribute>>,
            implicitExprs: List<IndexedValue<R_Attribute>>
    ) : List<R_Attribute> {
        checkImplicitExprsConflicts1(exprs, explicitExprs, implicitExprs)
        checkImplicitExprsConflicts2(exprs, implicitExprs)

        val combinedExprs = (explicitExprs + implicitExprs).sortedBy { it.index }.toList()
        combinedExprs.withIndex().forEach { check(it.index == it.value.index ) }

        val result = combinedExprs.map { (_, attr) -> attr }.toList()
        return result
    }

    private fun matchImplicitExprs0(
            msgCtx: C_MessageContext,
            attributes: Map<String, R_Attribute>,
            exprs: List<S_NameExprPair>,
            types: List<R_Type>,
            mutableOnly: Boolean
    ): List<IndexedValue<R_Attribute>> {
        val result = mutableListOf<IndexedValue<R_Attribute>>()

        for ((idx, pair) in exprs.withIndex()) {
            if (pair.name == null) {
                val type = types[idx]
                val attr = implicitMatch(msgCtx, attributes, idx, pair.expr, type, mutableOnly)
                if (attr != null) {
                    result.add(IndexedValue(idx, attr))
                }
            }
        }

        return result.toList()
    }

    private fun checkImplicitExprsConflicts1(
            exprs: List<S_NameExprPair>,
            explicitExprs: List<IndexedValue<R_Attribute>>,
            implicitExprs: List<IndexedValue<R_Attribute>>
    ) {
        val explicitNames = explicitExprs.map { (_, attr) -> attr.name }.toSet()

        for ((idx, attr) in implicitExprs) {
            val name = attr.name
            C_Errors.check(name !in explicitNames, exprs[idx].expr.startPos) {
                    "attr_implic_explic:$idx:$name" to
                            "Expression #${idx + 1} matches attribute '$name' which is already specified"
            }
        }
    }

    private fun checkImplicitExprsConflicts2(exprs: List<S_NameExprPair>, implicitExprs: List<IndexedValue<R_Attribute>>) {
        val implicitConflicts = mutableMapOf<String, MutableList<Int>>()
        for ((_, attr) in implicitExprs) {
            implicitConflicts[attr.name] = mutableListOf()
        }

        for ((idx, attr) in implicitExprs) {
            implicitConflicts[attr.name]!!.add(idx)
        }

        for ((name, list) in implicitConflicts) {
            C_Errors.check(list.size <= 1, exprs[list[0]].expr.startPos) {
                "attr_implic_multi:$name:${list.joinToString(",")}" to
                        "Multiple expressions match attribute '$name': ${list.joinToString { "#" + (it + 1) }}"
            }
        }
    }

    private fun implicitMatch(
            msgCtx: C_MessageContext,
            attributes: Map<String, R_Attribute>,
            idx: Int,
            expr: S_Expr,
            type: R_Type,
            mutableOnly: Boolean
    ): R_Attribute? {
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
        }

        C_Errors.check(byType.size == 0, expr.startPos) {
                "attr_implic_multi:$idx:${byType.joinToString(",") { it.name }}" to
                        "Multiple attributes match expression #${idx + 1}: ${byType.joinToString(", ") { it.name }}"
        }

        if (type != R_CtErrorType) {
            msgCtx.error(expr.startPos, "attr_implic_unknown:$idx:$type",
                    "Cannot find attribute for expression #${idx + 1} of type ${type.toStrictString()}")
        }

        return null
    }

    private fun implicitMatchByName(attributes: Map<String, R_Attribute>, expr: S_Expr): R_Attribute? {
        val name = expr.asName()
        return if (name == null) null else attributes[name.str]
    }

    private fun implicitMatchByType(attributes: Map<String, R_Attribute>, type: R_Type, mutableOnly: Boolean): List<R_Attribute> {
        return attributes.values.filter{ it.type.isAssignableFrom(type) && (!mutableOnly || it.mutable) }.toList()
    }

    private fun typeCheck(pos: S_Pos, idx: Int, attr: R_Attribute, exprType: R_Type) {
        C_Errors.check(attr.type.isAssignableFrom(exprType), pos) {
                "attr_bad_type:$idx:${attr.name}:${attr.type.toStrictString()}:${exprType.toStrictString()}" to
                        "Attribute type mismatch for '${attr.name}': ${exprType} instead of ${attr.type}"
        }
    }
}

class C_CreateAttributes(val rAttrs: List<R_CreateExprAttr>, val exprFacts: C_ExprVarFacts)
