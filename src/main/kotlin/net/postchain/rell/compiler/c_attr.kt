/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler

import com.google.common.collect.LinkedHashMultimap
import com.google.common.collect.Multimap
import com.google.common.collect.Sets
import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.vexpr.V_CreateExprAttr
import net.postchain.rell.compiler.vexpr.V_Expr
import net.postchain.rell.model.*
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap
import net.postchain.rell.utils.toImmSet

class C_CreateContext(val exprCtx: C_ExprContext, val initFrameGetter: C_LateGetter<R_CallFrame>, val filePos: R_FilePos) {
    val msgCtx = exprCtx.msgCtx
}

class C_CreateAttributes(
        explicitAttrs: List<V_CreateExprAttr>,
        implicitAttrs: List<R_CreateExprAttr>,
        val exprFacts: C_ExprVarFacts
) {
    val explicitAttrs = explicitAttrs.toImmList()
    val implicitAttrs = implicitAttrs.toImmList()
}

class C_AttrArgument(val index: Int, val name: S_Name?, val vExpr: V_Expr, val exprImplicitName: String?)

class C_AttrMatch(val attr: R_Attribute, val vExpr: V_Expr)

object C_AttributeResolver {
    fun resolveCreate(
            ctx: C_CreateContext,
            attributes: Map<String, R_Attribute>,
            args: List<C_AttrArgument>,
            pos: S_Pos
    ): C_CreateAttributes {
        val errWatcher = ctx.msgCtx.errorWatcher()
        val matchedAttrs = matchCreateAttrs(ctx.exprCtx, attributes, args)

        val unmatchedArgs = Sets.difference(args.toSet(), matchedAttrs.keys)
        if (unmatchedArgs.isNotEmpty() && !errWatcher.hasNewErrors()) {
            // Must not happen, added for safety.
            ctx.msgCtx.error(pos, "create:unmatched_args", "Not all arguments matched to attributes")
        }

        val explicitAttrs = matchedAttrs.values.map {
            V_CreateExprAttr(it.attr, it.vExpr)
        }

        val implicitAttrs = matchDefaultExprs(ctx, attributes, explicitAttrs)
        checkMissingAttrs(attributes, explicitAttrs, implicitAttrs, pos)

        for ((arg, attrMatch) in matchedAttrs) {
            val exprPos = arg.vExpr.pos
            C_Errors.check(attrMatch.attr.canSetInCreate, exprPos) {
                val name = attrMatch.attr.name
                "create_attr_cantset:$name" to "Cannot set value of system attribute '$name'"
            }
        }

        val exprFacts = C_ExprVarFacts.forSubExpressions(args.map { it.vExpr })
        return C_CreateAttributes(explicitAttrs, implicitAttrs, exprFacts)
    }

    private fun matchCreateAttrs(
            ctx: C_ExprContext,
            attributes: Map<String, R_Attribute>,
            args: List<C_AttrArgument>
    ): Map<C_AttrArgument, C_AttrMatch> {
        val explicitAttrs = matchExplicitAttrs(ctx.msgCtx, attributes, args, false)
        val explicitAttrs2 = checkExplicitExprTypes(ctx, explicitAttrs)
        return matchImplicitAttrs(ctx.msgCtx, attributes, args, explicitAttrs2, false)
    }

    private fun matchDefaultExprs(
            ctx: C_CreateContext,
            attributes: Map<String, R_Attribute>,
            attrExprs: List<V_CreateExprAttr>
    ): List<R_CreateExprAttr> {
        val provided = attrExprs.map { it.attr.name }.toSet()
        return attributes.values
                .filter { it.hasExpr && it.name !in provided }
                .map {
                    val rExpr = R_AttributeDefaultValueExpr(it, ctx.initFrameGetter, ctx.filePos)
                    R_CreateExprAttr(it, rExpr)
                }
    }

    private fun checkMissingAttrs(
            attributes: Map<String, R_Attribute>,
            explicitAttrs: List<V_CreateExprAttr>,
            implicitAttrs: List<R_CreateExprAttr>,
            pos: S_Pos
    ) {
        val names = (explicitAttrs.map { it.attr } + implicitAttrs.map { it.attr }).map { it.name }.toImmSet()

        val missing = attributes
                .toList()
                .filter { it.first !in names }
                .sortedBy { it.second.index }
                .map { it.first }
                .toList()

        C_Errors.check(missing.isEmpty(), pos) {
            "attr_missing:${missing.joinToString(",")}" to "Attributes not specified: ${missing.joinToString()}"
        }
    }

    fun resolveUpdate(
            msgCtx: C_MessageContext,
            entity: R_EntityDefinition,
            args: List<C_AttrArgument>
    ): Map<C_AttrArgument, R_Attribute> {
        val errWatcher = msgCtx.errorWatcher()
        val explicitAttrs = matchExplicitAttrs(msgCtx, entity.attributes, args, entity.flags.canUpdate)
        val matchedAttrs = matchImplicitAttrs(msgCtx, entity.attributes, args, explicitAttrs, true)

        val unmatched = Sets.difference(args.toSet(), matchedAttrs.keys)
        if (unmatched.isNotEmpty() && !errWatcher.hasNewErrors() && !args.any { it.vExpr.type().isError() }) {
            val pos = unmatched.first().vExpr.pos
            msgCtx.error(pos, "update:unmatched_args", "Not all arguments matched to attributes")
        }

        return matchedAttrs.mapValues { it.value.attr }
    }

    private fun matchExplicitAttrs(
            msgCtx: C_MessageContext,
            attrs: Map<String, R_Attribute>,
            args: List<C_AttrArgument>,
            mutableOnly: Boolean
    ): Map<C_AttrArgument, C_AttrMatch> {
        val explicitNames = mutableSetOf<String>()
        val explicitExprs = mutableMapOf<C_AttrArgument, C_AttrMatch>()

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

            explicitExprs[arg] = C_AttrMatch(attr, arg.vExpr)
        }

        return explicitExprs.toImmMap()
    }

    private fun checkExplicitExprTypes(
            ctx: C_ExprContext,
            explicitAttrs: Map<C_AttrArgument, C_AttrMatch>
    ): Map<C_AttrArgument, C_AttrMatch> {
        return explicitAttrs.mapValues {
            val (arg, attrMatch) = it
            val vExpr = arg.vExpr
            val type = vExpr.type()

            val matcher = C_ArgTypeMatcher_Simple(attrMatch.attr.type)
            val m = matcher.match(type)

            val vExpr2 = if (m == null) {
                errWrongType(ctx.msgCtx, vExpr.pos, arg.index, attrMatch.attr, type)
                vExpr
            } else {
                m.adaptExpr(ctx, vExpr)
            }

            C_AttrMatch(attrMatch.attr, vExpr2)
        }
    }

    private fun matchImplicitAttrs(
            msgCtx: C_MessageContext,
            attrs: Map<String, R_Attribute>,
            args: List<C_AttrArgument>,
            explicitExprs: Map<C_AttrArgument, C_AttrMatch>,
            mutableOnly: Boolean
    ) : Map<C_AttrArgument, C_AttrMatch> {
        val implicitAttrs = matchImplicitAttrs0(msgCtx, attrs, args, mutableOnly)
        val res = combineMatchedExprs(explicitExprs, implicitAttrs)
        return res
    }

    private fun combineMatchedExprs(
            explicitAttrs: Map<C_AttrArgument, C_AttrMatch>,
            implicitAttrs: Map<C_AttrArgument, C_AttrMatch>
    ): Map<C_AttrArgument, C_AttrMatch> {
        checkImplicitExprsConflicts1(explicitAttrs, implicitAttrs)
        checkImplicitExprsConflicts2(implicitAttrs)
        val res = explicitAttrs + implicitAttrs
        return res
    }

    private fun matchImplicitAttrs0(
            msgCtx: C_MessageContext,
            attrs: Map<String, R_Attribute>,
            args: List<C_AttrArgument>,
            mutableOnly: Boolean
    ): Map<C_AttrArgument, C_AttrMatch> {
        val res = mutableMapOf<C_AttrArgument, C_AttrMatch>()

        for (arg in args) {
            if (arg.name == null) {
                val type = arg.vExpr.type()
                val attr = implicitMatch(msgCtx, attrs, arg, type, mutableOnly)
                if (attr != null) {
                    res[arg] = C_AttrMatch(attr, arg.vExpr)
                }
            }
        }

        return res.toImmMap()
    }

    private fun checkImplicitExprsConflicts1(
            explicitAttrs: Map<C_AttrArgument, C_AttrMatch>,
            implicitAttrs: Map<C_AttrArgument, C_AttrMatch>
    ) {
        val explicitNames = explicitAttrs.map { it.value.attr.name }.toSet()
        for ((arg, attrMatch) in implicitAttrs) {
            val name = attrMatch.attr.name
            C_Errors.check(name !in explicitNames, arg.vExpr.pos) {
                    "attr_implic_explic:${arg.index}:$name" to
                            "Expression #${arg.index + 1} matches attribute '$name' which is already specified"
            }
        }
    }

    private fun checkImplicitExprsConflicts2(implicitAttrs: Map<C_AttrArgument, C_AttrMatch>) {
        val implicitConflicts: Multimap<String, C_AttrArgument> = LinkedHashMultimap.create()
        for ((arg, attrMatch) in implicitAttrs) {
            implicitConflicts.put(attrMatch.attr.name, arg)
        }

        for (name in implicitConflicts.keySet()) {
            val args = implicitConflicts.get(name)
            if (args.size > 1) {
                val arg = args.first()
                val idxs = args.map { it.index }
                val code = "attr_implic_multi:$name:${idxs.joinToString(",")}"
                val msg = "Multiple expressions match attribute '$name': ${idxs.joinToString { "#" + (it + 1) }}"
                throw C_Error.stop(arg.vExpr.pos, code, msg)
            }
        }
    }

    private fun implicitMatch(
            msgCtx: C_MessageContext,
            attributes: Map<String, R_Attribute>,
            arg: C_AttrArgument,
            type: R_Type,
            mutableOnly: Boolean
    ): R_Attribute? {
        val argIndex = arg.index
        val exprPos = arg.vExpr.pos

        val byName = if (arg.exprImplicitName == null) null else attributes[arg.exprImplicitName]
        if (byName != null) {
            if (!byName.type.isAssignableFrom(type)) {
                errWrongType(msgCtx, exprPos, argIndex, byName, type)
            }
            if (mutableOnly && !byName.mutable) {
                msgCtx.error(exprPos, C_Errors.msgAttrNotMutable(byName.name))
            }
            return byName
        }

        val byType = implicitMatchByType(attributes, type, mutableOnly)
        if (byType.size == 1) {
            return byType[0]
        }

        C_Errors.check(byType.isEmpty(), exprPos) {
                "attr_implic_multi:$argIndex:${byType.joinToString(",") { it.name }}" to
                        "Multiple attributes match expression #${argIndex + 1}: ${byType.joinToString(", ") { it.name }}"
        }

        if (type.isNotError()) {
            msgCtx.error(exprPos, "attr_implic_unknown:$argIndex:$type",
                    "Cannot find attribute for expression #${argIndex + 1} of type ${type.toStrictString()}")
        }

        return null
    }

    private fun implicitMatchByType(attributes: Map<String, R_Attribute>, type: R_Type, mutableOnly: Boolean): List<R_Attribute> {
        return attributes.values.filter{ it.type.isAssignableFrom(type) && (!mutableOnly || it.mutable) }.toList()
    }

    private fun errWrongType(msgCtx: C_MessageContext, pos: S_Pos, idx: Int, attr: R_Attribute, exprType: R_Type) {
        if (attr.type.isError() || exprType.isError()) return
        val code = "attr_bad_type:$idx:${attr.name}:${attr.type.toStrictString()}:${exprType.toStrictString()}"
        val msg = "Attribute type mismatch for '${attr.name}': ${exprType} instead of ${attr.type}"
        throw C_Error.stop(pos, code, msg)
    }
}
