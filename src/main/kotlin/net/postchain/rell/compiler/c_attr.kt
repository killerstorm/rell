/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import com.google.common.collect.LinkedHashMultimap
import com.google.common.collect.Multimap
import com.google.common.collect.Sets
import net.postchain.rell.compiler.ast.S_Expr
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_NameExprPair
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.*
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap

class C_CreateContext(exprCtx: C_ExprContext, val initFrameGetter: C_LateGetter<R_CallFrame>, val filePos: R_FilePos) {
    val msgCtx = exprCtx.msgCtx
}

class C_CreateAttributes(val rAttrs: List<R_CreateExprAttr>, val exprFacts: C_ExprVarFacts)

object C_AttributeResolver {
    fun resolveCreate(
            ctx: C_CreateContext,
            attributes: Map<String, R_Attribute>,
            args: List<C_Argument>,
            pos: S_Pos
    ): C_CreateAttributes {
        val rExprMap = args.map { it to it.vExpr.toRExpr() }.toMap().toImmMap()

        val errWatcher = ctx.msgCtx.errorWatcher()
        val matchedAttrs = matchCreateAttrs(ctx.msgCtx, attributes, args)

        val unmatchedArgs = Sets.difference(args.toSet(), matchedAttrs.keys)
        if (unmatchedArgs.isNotEmpty() && !errWatcher.hasNewErrors()) {
            // Must not happen, added for safety.
            ctx.msgCtx.error(pos, "create:unmatched_args", "Not all arguments matched to attributes")
        }

        val attrExprs = matchedAttrs.map {
            val rExpr = rExprMap.getValue(it.key)
            R_CreateExprAttr_Specified(it.value, rExpr)
        }

        val attrExprsDef = attrExprs + matchDefaultExprs(ctx, attributes, attrExprs)
        checkMissingAttrs(attributes, attrExprsDef, pos)

        for ((arg, attr) in matchedAttrs) {
            val exprPos = arg.vExpr.pos
            C_Errors.check(attr.canSetInCreate, exprPos) {
                val name = attr.name
                "create_attr_cantset:$name" to "Cannot set value of system attribute '$name'"
            }
        }

        val exprFacts = C_ExprVarFacts.forSubExpressions(args.map { it.vExpr })
        return C_CreateAttributes(attrExprsDef, exprFacts)
    }

    private fun matchCreateAttrs(
            msgCtx: C_MessageContext,
            attributes: Map<String, R_Attribute>,
            args: List<C_Argument>
    ): Map<C_Argument, R_Attribute> {
        val explicitAttrs = matchExplicitAttrs(msgCtx, attributes, args, false)
        checkExplicitExprTypes(explicitAttrs)
        return matchImplicitAttrs(msgCtx, attributes, args, explicitAttrs, false)
    }

    private fun matchDefaultExprs(
            ctx: C_CreateContext,
            attributes: Map<String, R_Attribute>,
            attrExprs: List<R_CreateExprAttr>
    ): List<R_CreateExprAttr> {
        val provided = attrExprs.map { it.attr.name }.toSet()
        return attributes.values
                .filter { it.hasExpr && it.name !in provided }
                .map { R_CreateExprAttr_Default(it, ctx.initFrameGetter, ctx.filePos) }
    }

    private fun checkMissingAttrs(attributes: Map<String, R_Attribute>, attrs: List<R_CreateExprAttr>, pos: S_Pos) {
        val names = attrs.map { it.attr.name }.toSet()

        val missing = (attributes.keys - names).sorted().toList()
        C_Errors.check(missing.isEmpty(), pos) {
            "attr_missing:${missing.joinToString(",")}" to "Attributes not specified: ${missing.joinToString()}"
        }
    }

    fun resolveUpdate(
            msgCtx: C_MessageContext,
            entity: R_EntityDefinition,
            args: List<C_Argument>
    ): Map<C_Argument, R_Attribute> {
        val errWatcher = msgCtx.errorWatcher()
        val explicitAttrs = matchExplicitAttrs(msgCtx, entity.attributes, args, entity.flags.canUpdate)
        val matchedAttrs = matchImplicitAttrs(msgCtx, entity.attributes, args, explicitAttrs, true)

        val unmatched = Sets.difference(args.toSet(), matchedAttrs.keys)
        if (unmatched.isNotEmpty() && !errWatcher.hasNewErrors() && !args.any { it.vExpr.type() == R_CtErrorType }) {
            val pos = unmatched.first().vExpr.pos
            msgCtx.error(pos, "update:unmatched_args", "Not all arguments matched to attributes")
        }

        return matchedAttrs
    }

    private fun matchExplicitAttrs(
            msgCtx: C_MessageContext,
            attrs: Map<String, R_Attribute>,
            args: List<C_Argument>,
            mutableOnly: Boolean
    ): Map<C_Argument, R_Attribute> {
        val explicitNames = mutableSetOf<String>()
        val explicitExprs = mutableMapOf<C_Argument, R_Attribute>()

        for (arg in args) {
            val name = arg.name
            name ?: continue

            val attrZ = attrs[name.str]
            val attr = C_Errors.checkNotNull(attrZ, name.pos) { "attr_unknown_name:${name.str}" to "Unknown attribute: '${name.str}'" }

            C_Errors.check(explicitNames.add(name.str), name.pos) {
                "attr_dup_name:${name.str}" to "Attribute already specified: '${name.str}'"
            }

            if (mutableOnly && !attr.mutable) {
                msgCtx.error(name.pos, "update_attr_not_mutable:${name.str}", "Attribute is not mutable: '${name.str}'")
            }

            explicitExprs[arg] = attr
        }

        return explicitExprs.toImmMap()
    }

    private fun checkExplicitExprTypes(explicitAttrs: Map<C_Argument, R_Attribute>) {
        for ((arg, attr) in explicitAttrs) {
            val vExpr = arg.vExpr
            val type = vExpr.type()
            typeCheck(vExpr.pos, arg.index, attr, type)
        }
    }

    private fun matchImplicitAttrs(
            msgCtx: C_MessageContext,
            attrs: Map<String, R_Attribute>,
            args: List<C_Argument>,
            explicitExprs: Map<C_Argument, R_Attribute>,
            mutableOnly: Boolean
    ) : Map<C_Argument, R_Attribute> {
        val implicitAttrs = matchImplicitAttrs0(msgCtx, attrs, args, mutableOnly)
        val res = combineMatchedExprs(explicitExprs, implicitAttrs)
        return res
    }

    private fun combineMatchedExprs(
            explicitAttrs: Map<C_Argument, R_Attribute>,
            implicitAttrs: Map<C_Argument, R_Attribute>
    ): Map<C_Argument, R_Attribute> {
        checkImplicitExprsConflicts1(explicitAttrs, implicitAttrs)
        checkImplicitExprsConflicts2(implicitAttrs)
        val res = explicitAttrs + implicitAttrs
        return res
    }

    private fun matchImplicitAttrs0(
            msgCtx: C_MessageContext,
            attrs: Map<String, R_Attribute>,
            args: List<C_Argument>,
            mutableOnly: Boolean
    ): Map<C_Argument, R_Attribute> {
        val res = mutableMapOf<C_Argument, R_Attribute>()

        for (arg in args) {
            if (arg.name == null) {
                val type = arg.vExpr.type()
                val attr = implicitMatch(msgCtx, attrs, arg.index, arg.sExpr, type, mutableOnly)
                if (attr != null) {
                    res[arg] = attr
                }
            }
        }

        return res.toImmMap()
    }

    private fun checkImplicitExprsConflicts1(
            explicitAttrs: Map<C_Argument, R_Attribute>,
            implicitAttrs: Map<C_Argument, R_Attribute>
    ) {
        val explicitNames = explicitAttrs.map { it.value.name }.toSet()
        for ((arg, attr) in implicitAttrs) {
            val name = attr.name
            C_Errors.check(name !in explicitNames, arg.sExpr.startPos) {
                    "attr_implic_explic:${arg.index}:$name" to
                            "Expression #${arg.index + 1} matches attribute '$name' which is already specified"
            }
        }
    }

    private fun checkImplicitExprsConflicts2(implicitAttrs: Map<C_Argument, R_Attribute>) {
        val implicitConflicts: Multimap<String, C_Argument> = LinkedHashMultimap.create()
        for ((arg, attr) in implicitAttrs) {
            implicitConflicts.put(attr.name, arg)
        }

        for (name in implicitConflicts.keySet()) {
            val args = implicitConflicts.get(name)
            if (args.size > 1) {
                val arg = args.first()
                val idxs = args.map { it.index }
                val code = "attr_implic_multi:$name:${idxs.joinToString(",")}"
                val msg = "Multiple expressions match attribute '$name': ${idxs.joinToString { "#" + (it + 1) }}"
                throw C_Error.stop(arg.sExpr.startPos, code, msg)
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

class C_Argument(val index: Int, val name: S_Name?, val sExpr: S_Expr, val vExpr: V_Expr) {
    companion object {
        fun compile(
                ctx: C_ExprContext,
                attributes: Map<String, R_Attribute>,
                args: List<S_NameExprPair>
        ): List<C_Argument> {
            return args.mapIndexed { i, arg ->
                val vExpr = compileArg(ctx, attributes, arg)
                C_Argument(i, arg.name, arg.expr, vExpr)
            }.toImmList()
        }

        private fun compileArg(ctx: C_ExprContext, attributes: Map<String, R_Attribute>, expr: S_NameExprPair): V_Expr {
            val attr = if (expr.name != null) {
                attributes[expr.name.str]
            } else if (attributes.size == 1) {
                attributes.values.iterator().next()
            } else {
                null
            }
            val typeHint = C_TypeHint.ofType(attr?.type)
            return expr.expr.compile(ctx, typeHint).value()
        }
    }
}
