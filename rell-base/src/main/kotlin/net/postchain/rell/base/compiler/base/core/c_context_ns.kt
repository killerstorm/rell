/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.core

import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.base.def.C_GlobalFunction
import net.postchain.rell.base.compiler.base.expr.C_Expr
import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.expr.C_ExprUtils
import net.postchain.rell.base.compiler.base.lib.C_LibUtils
import net.postchain.rell.base.compiler.base.lib.C_TypeDef
import net.postchain.rell.base.compiler.base.modifier.C_ModifierValue
import net.postchain.rell.base.compiler.base.namespace.*
import net.postchain.rell.base.compiler.base.utils.*
import net.postchain.rell.base.model.*
import net.postchain.rell.base.utils.Nullable
import net.postchain.rell.base.utils.doc.*
import net.postchain.rell.base.utils.ide.IdeSymbolCategory
import net.postchain.rell.base.utils.ide.IdeSymbolId
import net.postchain.rell.base.utils.ide.IdeSymbolKind
import net.postchain.rell.base.utils.immListOf
import net.postchain.rell.base.utils.toImmList

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

    fun getTypeEx(name: C_QualifiedNameHandle): C_GlobalNameResDef<C_TypeDef>? {
        val nameRes = resolveName(name, C_NamespaceMemberTag.TYPE.list)
        return nameRes.getTypeEx()
    }

    fun getEntity(name: C_QualifiedNameHandle, error: Boolean = true, unknownInfo: Boolean = true): R_EntityDefinition? {
        val nameRes = resolveName(name, C_NamespaceMemberTag.TYPE.list)
        return nameRes.getEntity(error = error, unknownInfo = unknownInfo)
    }

    fun getFunction(name: C_QualifiedNameHandle): C_GlobalFunction? {
        val nameRes = resolveName(name, C_NamespaceMemberTag.CALLABLE.list)
        return nameRes.getFunction()
    }

    fun getFullName(simpleName: R_Name): R_FullName {
        val qualifiedName = namespacePath.qualifiedName(simpleName)
        return R_FullName(modCtx.moduleName, qualifiedName)
    }
}

class C_DefinitionModuleName(val module: String, val chain: String? = null) {
    constructor(module: R_ModuleName): this(module.str())

    fun str(): String = if (chain == null) module else "$module[$chain]"
    override fun toString() = str()
}

class C_DefinitionName(val module: C_DefinitionModuleName, val qualifiedName: C_StringQualifiedName) {
    constructor(module: String, name: String): this(C_DefinitionModuleName(module), C_StringQualifiedName.of(name))
    constructor(module: String, qualifiedName: C_StringQualifiedName): this(C_DefinitionModuleName(module), qualifiedName)
    constructor(fullName: R_FullName): this(fullName.moduleName.str(), C_StringQualifiedName.of(fullName.qualifiedName))

    val appLevelName: String by lazy { R_DefinitionName.appLevelName(module.str(), qualifiedName.str()) }

    fun toRDefName(): R_DefinitionName {
        val qName = qualifiedName.str()
        return R_DefinitionName(module.str(), qName, qualifiedName.last)
    }

    fun toPath() = C_DefinitionPath(module, qualifiedName.parts)
}

class C_DefinitionPath(private val module: C_DefinitionModuleName, path: List<String>) {
    val path = path.toImmList()

    constructor(module: String, path: List<String>): this(C_DefinitionModuleName(module), path)
    constructor(module: R_ModuleName, path: List<String>): this(C_DefinitionModuleName(module), path)

    fun isEmpty(): Boolean = module.module.isEmpty() && module.chain == null && path.isEmpty()

    fun subName(name: R_Name): C_DefinitionName {
        val qName = C_StringQualifiedName.of(path + name.str)
        return C_DefinitionName(module, qName)
    }

    fun subName(name: R_QualifiedName): C_DefinitionName {
        val qName = C_StringQualifiedName.of(path + name.parts.map { it.str })
        return C_DefinitionName(module, qName)
    }

    fun subPath(name: String): C_DefinitionPath {
        return C_DefinitionPath(module, path + name)
    }

    companion object {
        val ROOT = C_DefinitionPath(C_LibUtils.DEFAULT_MODULE_STR, immListOf())
    }
}

class C_CommonDefinitionBase(
    private val defType: C_DefinitionType,
    private val ideKind: IdeSymbolKind,
    val defId: R_DefinitionId,
    val cDefName: C_DefinitionName,
    val defName: R_DefinitionName,
    private val mountName: R_MountName?,
    private val docFactory: DocSymbolFactory,
) {
    val simpleName = defName.simpleName
    val appLevelName = defName.appLevelName
    val qualifiedName = defName.qualifiedName

    private val ideId = IdeSymbolId(defType.ideCategory, defName.qualifiedName, immListOf())

    fun rBase(
        initFrameGetter: C_LateGetter<R_CallFrame>,
        docGetter: C_LateGetter<DocSymbol>,
    ): R_DefinitionBase {
        return R_DefinitionBase(defId, defName, cDefName, initFrameGetter, docGetter)
    }

    fun nsMemBase(
        deprecated: C_Deprecated? = null,
        defName: C_DefinitionName = cDefName,
        ideRefInfo: C_IdeSymbolInfo,
    ): C_NamespaceMemberBase {
        return C_NamespaceMemberBase(defName, ideRefInfo, deprecated)
    }

    fun memberIdeDef(
        pos: S_Pos,
        memberIdeCat: IdeSymbolCategory,
        memberIdeKind: IdeSymbolKind,
        memberDocKind: DocSymbolKind,
        memberName: R_Name,
        declaration: DocDeclaration,
    ): C_IdeSymbolDef {
        val memberIdeId = ideId(defType, defName, memberIdeCat to memberName)
        val doc = makeDocSymbol(memberDocKind, memberName, null, declaration)
        val docGetter = C_LateGetter.const(Nullable.of(doc))
        return ideDef(pos, memberIdeKind, memberIdeId, docGetter)
    }

    private fun makeDocSymbol(
        kind: DocSymbolKind,
        memberName: R_Name?,
        mountName: R_MountName?,
        declaration: DocDeclaration,
    ): DocSymbol {
        val fullDefName = if (memberName == null) cDefName else cDefName.toPath().subName(memberName)
        val docName = DocSymbolName.global(cDefName.module.module, fullDefName.qualifiedName.str())

        val chain = cDefName.module.chain
        val mountNameStr = if (chain == null || mountName == null) mountName?.str() else "$chain:${mountName.str()}"

        return docFactory.makeDocSymbol(
            kind = kind,
            symbolName = docName,
            mountName = mountNameStr,
            declaration = declaration,
        )
    }

    fun docGetter(docDeclarationGetter: C_LateGetter<DocDeclaration>): C_LateGetter<DocSymbol> {
        return docDeclarationGetter.transform { docDec ->
            makeDocSymbol(defType.docKind, null, mountName, docDec)
        }
    }

    fun ideDef(pos: S_Pos, docGetter: C_LateGetter<DocSymbol>): C_IdeSymbolDef {
        val docGetter2 = docGetter.transform { Nullable.of(it) }
        return ideDef(pos, ideKind, ideId, docGetter2)
    }

    fun userBase(pos: S_Pos): C_UserDefinitionBase {
        val docDecLate = C_LateInit(C_CompilerPass.DOCS, DocDeclaration.NONE)
        val docSymGetter = docGetter(docDecLate.getter)
        val ideDef = ideDef(pos, docSymGetter)
        return C_UserDefinitionBase(this, ideDef, docSymGetter, docDecLate)
    }

    fun defCtx(mntCtx: C_MountContext): C_DefinitionContext {
        return C_DefinitionContext(mntCtx, defType, defId, cDefName, defName, ideId)
    }

    companion object {
        fun ideId(
            defType: C_DefinitionType,
            defName: R_DefinitionName,
            member: Pair<IdeSymbolCategory, R_Name>?,
        ): IdeSymbolId {
            return IdeSymbolId(defType.ideCategory, defName.qualifiedName, listOfNotNull(member))
        }

        fun ideDef(
            pos: S_Pos,
            ideKind: IdeSymbolKind,
            ideId: IdeSymbolId,
            docGetter: C_LateGetter<Nullable<DocSymbol>>,
        ): C_IdeSymbolDef {
            return C_IdeSymbolDef.makeLate(ideKind, pos.idePath(), ideId, docGetter)
        }
    }
}

class C_UserDefinitionBase(
    private val comBase: C_CommonDefinitionBase,
    ideDef: C_IdeSymbolDef,
    private val docSymbolGetter: C_LateGetter<DocSymbol>,
    private val docDeclarationLate: C_LateInit<DocDeclaration>,
) {
    val defName = comBase.defName
    val ideDefInfo = ideDef.defInfo
    val ideRefInfo = ideDef.refInfo

    val simpleName = comBase.simpleName
    val appLevelName = comBase.appLevelName
    val qualifiedName = comBase.qualifiedName

    fun defCtx(mntCtx: C_MountContext) = comBase.defCtx(mntCtx)

    fun rBase(initFrameGetter: C_LateGetter<R_CallFrame>) = comBase.rBase(initFrameGetter, docSymbolGetter)

    fun nsMemBase(deprecatedValue: C_ModifierValue<C_Deprecated>): C_NamespaceMemberBase {
        val deprecated = deprecatedValue.value()
        return nsMemBase(deprecated = deprecated)
    }

    fun nsMemBase(
        deprecated: C_Deprecated? = null,
        defName: C_DefinitionName = comBase.cDefName,
    ): C_NamespaceMemberBase {
        return comBase.nsMemBase(deprecated, defName, ideRefInfo)
    }

    fun setDocDeclaration(declaration: DocDeclaration) {
        docDeclarationLate.set(declaration, allowEarly = true)
    }
}

private class C_NamePath(private val nodes: List<C_NameNode>) {
    init {
        check(nodes.isNotEmpty())
    }

    private val lastNode = nodes.last()
    val lastElem = lastNode.elem
    val lastIdeInfo = lastNode.ideInfo

    fun access(msgCtx: C_MessageContext): C_IdeSymbolInfoHandle {
        for ((i, node) in nodes.withIndex()) {
            if (i < nodes.size - 1) {
                node.nameHand.setIdeInfo(node.ideInfo)
            }
            node.elem?.access(msgCtx) { getQualifiedName(i + 1) }
        }
        return nodes.last().nameHand
    }

    fun getErrorName(): C_QualifiedName {
        var n = nodes.size
        for ((i, node) in nodes.withIndex()) {
            if (!node.valid) {
                n = i + 1
                break
            }
        }
        return getQualifiedName(n)
    }

    private fun getQualifiedName(n: Int = nodes.size): C_QualifiedName {
        val names = nodes.asSequence().take(n).map { it.name }.toList()
        return C_QualifiedName(names)
    }
}

private class C_NameNode(
    val valid: Boolean,
    val nameHand: C_NameHandle,
    val elem: C_NamespaceElement?,
) {
    val name = nameHand.name
    val ideInfo = elem?.item?.ideInfo ?: C_IdeSymbolInfo.UNKNOWN
}

class C_GlobalNameResDef<T>(
    val def: T,
    val ideInfoPtr: C_UniqueDefaultIdeInfoPtr,
)

sealed class C_GlobalNameRes(
    protected val msgCtx: C_MessageContext,
    private val qName: C_QualifiedName,
    private val elem: C_NamespaceElement?,
) {
    fun isValid() = elem != null
    fun isCallable() = elem?.member?.isCallable() ?: false

    fun compile(ctx: C_ExprContext): C_Expr {
        val ideInfoHand = access(false)

        if (elem == null) {
            val nameStr = getErrorName().str()
            ideInfoHand.setIdeInfo(C_IdeSymbolInfo.UNKNOWN)
            C_Errors.errUnknownName(msgCtx, qName.pos, nameStr, nameStr)
            return C_ExprUtils.errorExpr(ctx, qName.pos)
        }

        val ideInfoPtr = C_UniqueDefaultIdeInfoPtr(ideInfoHand, elem.item.ideInfo)
        val expr = elem.member.toExpr(ctx, qName, ideInfoPtr)

        if (ideInfoPtr.isValid()) {
            ideInfoPtr.setDefault()
        }

        return expr
    }

    fun getType(): C_TypeDef? {
        val def = getDef(C_DeclarationType.TYPE) { it.getTypeOpt() }
        return def?.def
    }

    fun getTypeEx(): C_GlobalNameResDef<C_TypeDef>? {
        return getDef(C_DeclarationType.TYPE, bindLastIdeInfo = false) { it.getTypeOpt() }
    }

    fun getEntity(error: Boolean = true, unknownInfo: Boolean = true): R_EntityDefinition? {
        val def = getDef(C_DeclarationType.ENTITY, error = error, unknownInfo = unknownInfo) { it.getEntityOpt() }
        return def?.def
    }

    fun getFunction(): C_GlobalFunction? {
        val def = getDef(C_DeclarationType.FUNCTION) { it.getFunctionOpt() }
        return def?.def
    }

    fun getOperation(error: Boolean = true, unknownInfo: Boolean = true): R_OperationDefinition? {
        val def = getDef(C_DeclarationType.OPERATION, error = error, unknownInfo = unknownInfo) { it.getOperationOpt() }
        return def?.def
    }

    fun getObject(error: Boolean = true, unknownInfo: Boolean = true): R_ObjectDefinition? {
        val def = getDef(C_DeclarationType.OBJECT, error = error, unknownInfo = unknownInfo) { it.getObjectOpt() }
        return def?.def
    }

    private fun <T> getDef(
        expDecType: C_DeclarationType,
        error: Boolean = true,
        unknownInfo: Boolean = true,
        bindLastIdeInfo: Boolean = true,
        getter: (C_NamespaceMember) -> T?,
    ): C_GlobalNameResDef<T>? {
        if (elem == null) {
            defNotFound(expDecType, error, unknownInfo)
            return null
        }

        val res = getter(elem.member)
        if (res == null) {
            defNotFound(expDecType, error, unknownInfo)
            return null
        }

        val ideInfoHand = access(bindLastIdeInfo)
        val ideInfoPtr = C_UniqueDefaultIdeInfoPtr(ideInfoHand, elem.item.ideInfo)
        return C_GlobalNameResDef(res, ideInfoPtr)
    }

    private fun defNotFound(
        expDecType: C_DeclarationType,
        error: Boolean,
        unknownInfo: Boolean,
    ) {
        if (unknownInfo) {
            access(true)
        }

        if (error) {
            val member = elem?.member
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
    }

    protected abstract fun access(bindLast: Boolean): C_IdeSymbolInfoHandle
    protected abstract fun getErrorName(): C_QualifiedName
}

private class C_GlobalNameRes_Private(
    msgCtx: C_MessageContext,
    qName: C_QualifiedName,
    private val path: C_NamePath,
): C_GlobalNameRes(msgCtx, qName, path.lastElem) {
    override fun access(bindLast: Boolean): C_IdeSymbolInfoHandle {
        val ideInfoHand = path.access(msgCtx)
        if (bindLast) {
            ideInfoHand.setIdeInfo(path.lastIdeInfo)
        }
        return ideInfoHand
    }

    override fun getErrorName() = path.getErrorName()
}

private object C_GlobalNameResolver {
    fun resolve(
        msgCtx: C_MessageContext,
        scope: C_Scope,
        qName: C_QualifiedNameHandle,
        tags: List<C_NamespaceMemberTag>,
    ): C_GlobalNameRes {
        val firstTags = if (qName.size == 1) tags else C_NamespaceMemberTag.NAMESPACE.list
        val firstEntry = scope.findEntry(qName.first.rName, firstTags)

        var node = entryToNode(qName.first, firstEntry, firstTags)
        val nodes = mutableListOf(node)

        for ((i, name) in qName.parts.withIndex().drop(1)) {
            val ns = node.elem?.member?.getNamespaceOpt()
            val entry = ns?.getEntry(name.rName)
            val isLast = i == qName.parts.indices.last
            val curTags = if (isLast) tags else C_NamespaceMemberTag.NAMESPACE.list
            node = entryToNode(name, entry, curTags)
            nodes.add(node)
        }

        val path = C_NamePath(nodes.toImmList())
        return C_GlobalNameRes_Private(msgCtx, qName.cName, path)
    }

    private fun entryToNode(
        name: C_NameHandle,
        entry: C_NamespaceEntry?,
        tags: List<C_NamespaceMemberTag>,
    ): C_NameNode {
        val elem = entry?.element(tags)
        return C_NameNode(valid = entry != null, nameHand = name, elem = elem)
    }
}
