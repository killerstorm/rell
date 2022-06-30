/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.expr

import com.google.common.collect.LinkedHashMultimap
import com.google.common.collect.Multimap
import com.google.common.collect.Sets
import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_MessageContext
import net.postchain.rell.compiler.base.core.C_Name
import net.postchain.rell.compiler.base.fn.C_ArgTypeMatcher_Simple
import net.postchain.rell.compiler.base.utils.C_Error
import net.postchain.rell.compiler.base.utils.C_Errors
import net.postchain.rell.compiler.base.utils.C_LateGetter
import net.postchain.rell.compiler.base.utils.toCodeMsg
import net.postchain.rell.compiler.vexpr.V_AttributeDefaultValueExpr
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
        implicitAttrs: List<V_CreateExprAttr>
) {
    val explicitAttrs = explicitAttrs.toImmList()
    val implicitAttrs = implicitAttrs.toImmList()
}

class C_AttrArgument(val index: Int, val name: C_Name?, val vExpr: V_Expr, val exprImplicitName: R_Name?)

class C_AttrMatch(val attr: R_Attribute, val vExpr: V_Expr)

object C_AttributeResolver {
    fun resolveCreate(
            ctx: C_CreateContext,
            attributes: Map<R_Name, R_Attribute>,
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

        val implicitAttrs = matchDefaultExprs(ctx, pos, attributes, explicitAttrs)
        checkMissingAttrs(attributes, explicitAttrs, implicitAttrs, pos)

        for ((arg, attrMatch) in matchedAttrs) {
            val exprPos = arg.vExpr.pos
            C_Errors.check(attrMatch.attr.canSetInCreate, exprPos) {
                val name = attrMatch.attr.name
                "create_attr_cantset:$name" toCodeMsg "Cannot set value of system attribute '$name'"
            }
        }

        return C_CreateAttributes(explicitAttrs, implicitAttrs)
    }

    private fun matchCreateAttrs(
            ctx: C_ExprContext,
            attributes: Map<R_Name, R_Attribute>,
            args: List<C_AttrArgument>
    ): Map<C_AttrArgument, C_AttrMatch> {
        val explicitAttrs = matchExplicitAttrs(ctx.msgCtx, attributes, args, false)
        val explicitAttrs2 = checkExplicitExprTypes(ctx, explicitAttrs)
        return matchImplicitAttrs(ctx, attributes, args, explicitAttrs2, false)
    }

    private fun matchDefaultExprs(
            ctx: C_CreateContext,
            pos: S_Pos,
            attributes: Map<*, R_Attribute>,
            attrExprs: List<V_CreateExprAttr>
    ): List<V_CreateExprAttr> {
        val provided = attrExprs.map { it.attr.name }.toSet()
        return attributes.values
                .filter { it.hasExpr && it.name !in provided }
                .map {
                    val vExpr = V_AttributeDefaultValueExpr(ctx.exprCtx, pos, it, ctx.filePos, ctx.initFrameGetter)
                    V_CreateExprAttr(it, vExpr)
                }
    }

    private fun checkMissingAttrs(
            attributes: Map<R_Name, R_Attribute>,
            explicitAttrs: List<V_CreateExprAttr>,
            implicitAttrs: List<V_CreateExprAttr>,
            pos: S_Pos
    ) {
        val names = (explicitAttrs.map { it.attr } + implicitAttrs.map { it.attr }).map { it.rName }.toImmSet()

        val missing = attributes
                .toList()
                .filter { it.first !in names }
                .sortedBy { it.second.index }
                .map { it.first }
                .toList()

        C_Errors.check(missing.isEmpty(), pos) {
            "attr_missing:${missing.joinToString(",")}" toCodeMsg "Attributes not specified: ${missing.joinToString()}"
        }
    }

    fun resolveUpdate(
            ctx: C_ExprContext,
            entity: R_EntityDefinition,
            args: List<C_AttrArgument>
    ): Map<C_AttrArgument, R_Attribute> {
        val errWatcher = ctx.msgCtx.errorWatcher()
        val explicitAttrs = matchExplicitAttrs(ctx.msgCtx, entity.attributes, args, entity.flags.canUpdate)
        val matchedAttrs = matchImplicitAttrs(ctx, entity.attributes, args, explicitAttrs, true)

        val unmatched = Sets.difference(args.toSet(), matchedAttrs.keys)
        if (unmatched.isNotEmpty() && !errWatcher.hasNewErrors() && !args.any { it.vExpr.type.isError() }) {
            val pos = unmatched.first().vExpr.pos
            ctx.msgCtx.error(pos, "update:unmatched_args", "Not all arguments matched to attributes")
        }

        return matchedAttrs.mapValues { it.value.attr }
    }

    private fun matchExplicitAttrs(
            msgCtx: C_MessageContext,
            attrs: Map<R_Name, R_Attribute>,
            args: List<C_AttrArgument>,
            mutableOnly: Boolean
    ): Map<C_AttrArgument, C_AttrMatch> {
        val explicitNames = mutableSetOf<R_Name>()
        val explicitExprs = mutableMapOf<C_AttrArgument, C_AttrMatch>()

        for (arg in args) {
            val name = arg.name
            name ?: continue

            val attrZ = attrs[name.rName]
            val attr = C_Errors.checkNotNull(attrZ, name.pos) {
                "attr_unknown_name:$name" toCodeMsg "Unknown attribute: '$name'"
            }

            C_Errors.check(explicitNames.add(name.rName), name.pos) {
                "attr_dup_name:$name" toCodeMsg "Attribute already specified: '$name'"
            }

            if (mutableOnly && !attr.mutable) {
                msgCtx.error(name.pos, "update_attr_not_mutable:$name", "Attribute is not mutable: '$name'")
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
            val type = vExpr.type

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
            ctx: C_ExprContext,
            attrs: Map<R_Name, R_Attribute>,
            args: List<C_AttrArgument>,
            explicitExprs: Map<C_AttrArgument, C_AttrMatch>,
            mutableOnly: Boolean
    ) : Map<C_AttrArgument, C_AttrMatch> {
        val implicitAttrs = matchImplicitAttrs0(ctx, attrs, args, mutableOnly)
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
            ctx: C_ExprContext,
            attrs: Map<R_Name, R_Attribute>,
            args: List<C_AttrArgument>,
            mutableOnly: Boolean
    ): Map<C_AttrArgument, C_AttrMatch> {
        val res = mutableMapOf<C_AttrArgument, C_AttrMatch>()

        for (arg in args) {
            if (arg.name == null) {
                val attrMatch = implicitMatch(ctx, attrs, arg, mutableOnly)
                if (attrMatch != null) {
                    res[arg] = attrMatch
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
                "attr_implic_explic:${arg.index}:$name" toCodeMsg
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
            ctx: C_ExprContext,
            attributes: Map<R_Name, R_Attribute>,
            arg: C_AttrArgument,
            mutableOnly: Boolean
    ): C_AttrMatch? {
        val argIndex = arg.index
        val exprPos = arg.vExpr.pos
        val exprType = arg.vExpr.type

        val byName = if (arg.exprImplicitName == null) null else attributes[arg.exprImplicitName]

        if (byName != null) {
            val adapter = byName.type.getTypeAdapter(exprType)
            val vExpr = if (adapter != null) {
                adapter.adaptExpr(ctx, arg.vExpr)
            } else {
                errWrongType(ctx.msgCtx, exprPos, argIndex, byName, exprType)
                arg.vExpr
            }

            if (mutableOnly && !byName.mutable) {
                ctx.msgCtx.error(exprPos, C_Errors.msgAttrNotMutable(byName.name))
            }

            return C_AttrMatch(byName, vExpr)
        }

        val byType = implicitMatchByType(attributes, exprType, mutableOnly)
        if (byType.size == 1) {
            return C_AttrMatch(byType[0], arg.vExpr)
        }

        C_Errors.check(byType.isEmpty(), exprPos) {
            "attr_implic_multi:$argIndex:${byType.joinToString(",") { it.name }}" toCodeMsg
            "Multiple attributes match expression #${argIndex + 1}: ${byType.joinToString(", ") { it.name }}"
        }

        if (exprType.isNotError()) {
            ctx.msgCtx.error(exprPos, "attr_implic_unknown:$argIndex:${exprType.strCode()}",
                    "Cannot find attribute for expression #${argIndex + 1} of type ${exprType.str()}")
        }

        return null
    }

    private fun implicitMatchByType(attributes: Map<R_Name, R_Attribute>, type: R_Type, mutableOnly: Boolean): List<R_Attribute> {
        return attributes.values.filter { it.type.isAssignableFrom(type) && (!mutableOnly || it.mutable) }.toList()
    }

    private fun errWrongType(msgCtx: C_MessageContext, pos: S_Pos, idx: Int, attr: R_Attribute, exprType: R_Type) {
        if (attr.type.isError() || exprType.isError()) return
        val code = "attr_bad_type:$idx:${attr.name}:${attr.type.strCode()}:${exprType.strCode()}"
        val msg = "Attribute type mismatch for '${attr.name}': ${exprType.str()} instead of ${attr.type.str()}"
        msgCtx.error(pos, code, msg)
    }
}
