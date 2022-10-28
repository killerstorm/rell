/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.core

import net.postchain.rell.compiler.base.def.C_GlobalFunction
import net.postchain.rell.compiler.base.def.C_TypeDef
import net.postchain.rell.compiler.base.expr.C_Expr
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.expr.C_ExprUtils
import net.postchain.rell.compiler.base.modifier.C_ModifierValue
import net.postchain.rell.compiler.base.namespace.*
import net.postchain.rell.compiler.base.utils.*
import net.postchain.rell.model.*
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList

class C_NamespaceContext(
    val modCtx: C_ModuleContext,
    val symCtx: C_SymbolContext,
    val namespacePath: C_RNamePath,
    val scopeBuilder: C_ScopeBuilder
) {
    val globalCtx = modCtx.globalCtx
    val appCtx = modCtx.appCtx
    val msgCtx = modCtx.msgCtx
    val executor = modCtx.executor

    private val scope = scopeBuilder.scope()

    fun resolveName(name: C_QualifiedNameHandle, tags: List<C_NamespaceMemberTag>): C_GlobalNameRes {
        return C_GlobalNameResolver.resolve(msgCtx, scope, name, tags)
    }

    fun getType(name: C_QualifiedNameHandle): C_TypeDef? {
        val nameRes = resolveName(name, C_NamespaceMemberTag.TYPE.list)
        return nameRes.getType()
    }

    fun getEntity(name: C_QualifiedNameHandle, error: Boolean = true, unknownInfo: Boolean = true): R_EntityDefinition? {
        val nameRes = resolveName(name, C_NamespaceMemberTag.TYPE.list)
        return nameRes.getEntity(error = error, unknownInfo = unknownInfo)
    }

    fun getFunction(name: C_QualifiedNameHandle): C_GlobalFunction? {
        val nameRes = resolveName(name, C_NamespaceMemberTag.CALLABLE.list)
        return nameRes.getFunction()
    }
}

class C_DefinitionName(private val module: String, val qualifiedName: C_StringQualifiedName) {
    constructor(module: String, name: String): this(module, C_StringQualifiedName.of(name))

    val appLevelName: String by lazy { R_DefinitionName.appLevelName(module, qualifiedName.str()) }

    fun toRDefName(): R_DefinitionName {
        val qName = qualifiedName.str()
        return R_DefinitionName(module, qName, qualifiedName.last)
    }

    fun toPath() = C_DefinitionPath(module, qualifiedName.parts)
}

class C_DefinitionPath(private val module: String, path: List<String>) {
    val path = path.toImmList()

    fun subName(name: R_Name): C_DefinitionName {
        val qName = C_StringQualifiedName.of(path + name.str)
        return C_DefinitionName(module, qName)
    }

    fun subPath(name: String): C_DefinitionPath {
        return C_DefinitionPath(module, path + name)
    }

    companion object {
        val ROOT = C_DefinitionPath(C_LibUtils.DEFAULT_MODULE_STR, immListOf())
    }
}

class C_DefinitionBase(
    val defId: R_DefinitionId,
    val cDefName: C_DefinitionName,
    val defName: R_DefinitionName,
) {
    val simpleName = defName.simpleName
    val appLevelName = defName.appLevelName
    val qualifiedName = defName.qualifiedName

    fun rBase(initFrameGetter: C_LateGetter<R_CallFrame>) = R_DefinitionBase(defId, defName, cDefName, initFrameGetter)

    fun nsMemBase(ideInfo: IdeSymbolInfo, deprecatedValue: C_ModifierValue<C_Deprecated>): C_NamespaceMemberBase {
        val deprecated = deprecatedValue.value()
        return C_NamespaceMemberBase(cDefName, ideInfo, deprecated)
    }
}

private class C_NameNode(
    val prev: C_NameNode?,
    val valid: Boolean,
    val nameHand: C_NameHandle,
    val elem: C_NamespaceElement?,
) {
    fun qualifiedName(): C_QualifiedName {
        val names = mutableListOf<C_Name>()
        var ptr: C_NameNode? = this
        while (ptr != null) {
            names.add(ptr.nameHand.name)
            ptr = ptr.prev
        }
        return C_QualifiedName(names.reversed())
    }

    fun access() {
        nameHand.setIdeInfo(elem?.member?.ideInfo ?: IdeSymbolInfo.UNKNOWN)
    }
}

sealed class C_GlobalNameRes(
    private val msgCtx: C_MessageContext,
    private val qName: C_QualifiedName,
    private val elem: C_NamespaceElement?,
) {
    fun isValid() = elem != null
    fun isCallable() = elem?.member?.isCallable() ?: false

    fun compile(ctx: C_ExprContext, implicitAttrMatchName: R_Name): C_Expr {
        access()
        val member = elem?.member
        if (member == null) {
            val nameStr = getErrorName().str()
            C_Errors.errUnknownName(msgCtx, qName.pos, nameStr, nameStr)
            return C_ExprUtils.errorExpr(ctx, qName.pos)
        }
        return member.toExpr(ctx, qName, implicitAttrMatchName = implicitAttrMatchName)
    }

    fun getType(): C_TypeDef? {
        return getDef(C_DeclarationType.TYPE) { it.getTypeOpt() }
    }

    fun getEntity(error: Boolean = true, unknownInfo: Boolean = true): R_EntityDefinition? {
        return getDef(C_DeclarationType.ENTITY, error = error, unknownInfo = unknownInfo) { it.getEntityOpt() }
    }

    fun getFunction(): C_GlobalFunction? {
        return getDef(C_DeclarationType.FUNCTION) { it.getFunctionOpt() }
    }

    fun getOperation(error: Boolean = true, unknownInfo: Boolean = true): R_OperationDefinition? {
        return getDef(C_DeclarationType.OPERATION, error = error, unknownInfo = unknownInfo) { it.getOperationOpt() }
    }

    fun getObject(error: Boolean = true, unknownInfo: Boolean = true): R_ObjectDefinition? {
        return getDef(C_DeclarationType.OBJECT, error = error, unknownInfo = unknownInfo) { it.getObjectOpt() }
    }

    private fun <T> getDef(
        expDecType: C_DeclarationType,
        error: Boolean = true,
        unknownInfo: Boolean = true,
        getter: (C_NamespaceMember) -> T?,
    ): T? {
        val member = elem?.member
        val res = if (member == null) null else getter(member)

        if (res != null || unknownInfo) {
            access()
        }

        if (res == null && error) {
            val fullMsg = if (member == null || member.declarationType() == expDecType) {
                val errName = getErrorName()
                "unknown_name:$errName" toCodeMsg "Unknown name: '$errName'"
            } else {
                val actDecType = member.declarationType()
                val code = "wrong_name:${expDecType.msg}:${actDecType.msg}:$qName"
                val msg = "'$qName' is ${actDecType.article} ${actDecType.msg}, but ${expDecType.msg} required"
                code toCodeMsg msg
            }
            msgCtx.error(qName.pos, fullMsg.code, fullMsg.msg)
        }

        return res
    }

    private fun access() {
        accessPrivate(msgCtx)
    }

    protected abstract fun accessPrivate(msgCtx: C_MessageContext)
    protected abstract fun getErrorName(): C_QualifiedName
}

private class C_GlobalNameRes_Private(
    msgCtx: C_MessageContext,
    qName: C_QualifiedName,
    private val node: C_NameNode,
): C_GlobalNameRes(msgCtx, qName, node.elem) {
    override fun accessPrivate(msgCtx: C_MessageContext) {
        var nodePtr: C_NameNode? = node
        while (nodePtr != null) {
            nodePtr.access()
            nodePtr.elem?.access(msgCtx, nodePtr::qualifiedName)
            nodePtr = nodePtr.prev
        }
    }

    override fun getErrorName(): C_QualifiedName {
        var nodePtr = node
        while (true) {
            val prev = nodePtr.prev
            if (prev == null || prev.valid) break
            nodePtr = prev
        }
        return nodePtr.qualifiedName()
    }
}

private object C_GlobalNameResolver {
    fun resolve(msgCtx: C_MessageContext, scope: C_Scope, qName: C_QualifiedNameHandle, tags: List<C_NamespaceMemberTag>): C_GlobalNameRes {
        val tags0 = if (qName.size == 1) tags else C_NamespaceMemberTag.NAMESPACE.list
        val entry0 = scope.findEntry(qName.first.rName, tags0)
        var node = entryToNode(qName.first, null, entry0, tags0)

        for ((i, name) in qName.parts.withIndex().drop(1)) {
            val ns = node.elem?.member?.getNamespaceOpt()
            val entry = ns?.getEntry(name.rName)
            val tagsI = if (i == qName.parts.indices.last) tags else C_NamespaceMemberTag.NAMESPACE.list
            node = entryToNode(name, node, entry, tagsI)
        }

        return C_GlobalNameRes_Private(msgCtx, qName.qName, node)
    }

    private fun entryToNode(name: C_NameHandle, prevNode: C_NameNode?, entry: C_NamespaceEntry?, tags: List<C_NamespaceMemberTag>): C_NameNode {
        val elem = entry?.element(tags)
        return C_NameNode(prev = prevNode, valid = entry != null, nameHand = name, elem = elem)
    }
}
