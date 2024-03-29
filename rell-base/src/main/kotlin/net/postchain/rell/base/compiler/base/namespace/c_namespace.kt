/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.namespace

import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.compiler.base.core.*
import net.postchain.rell.base.compiler.base.expr.C_Expr
import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.utils.C_MessageType
import net.postchain.rell.base.compiler.base.utils.toCodeMsg
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.utils.*
import net.postchain.rell.base.utils.doc.DocDefinition
import net.postchain.rell.base.utils.doc.DocSymbol

class C_Deprecated(
    private val useInstead: String?,
    val error: Boolean = false
) {
    fun detailsCode(): String {
        return if (useInstead != null) ":$useInstead" else ""
    }

    fun detailsMessage(): String {
        return if (useInstead != null) ", use '$useInstead' instead" else ""
    }

    companion object {
        fun make(error: Boolean = false, useInstead: String? = null): C_Deprecated {
            return C_Deprecated(useInstead = useInstead, error = error)
        }

        fun makeOrNull(messageType: C_MessageType?, useInstead: String?): C_Deprecated? {
            return if (messageType == null) null else {
                C_Deprecated(useInstead = useInstead, error = messageType == C_MessageType.ERROR)
            }
        }

        fun warning(useInstead: String? = null): C_Deprecated {
            return make(error = false, useInstead = useInstead)
        }

        fun error(useInstead: String? = null): C_Deprecated {
            return make(error = true, useInstead = useInstead)
        }
    }
}

enum class C_DeclarationType(val msg: String, val article: String = "a") {
    MODULE("module"),
    NAMESPACE("namespace"),
    TYPE("type"),
    ENTITY("entity", "an"),
    STRUCT("struct"),
    ENUM("enum", "an"),
    OBJECT("object", "an"),
    FUNCTION("function"),
    OPERATION("operation", "an"),
    QUERY("query"),
    IMPORT("import", "an"),
    CONSTANT("constant"),
    PROPERTY("property"),
    ;

    val capitalizedMsg = msg.capitalize()
}

class C_DefDeprecation(val defName: C_DefinitionName, val deprecated: C_Deprecated)

class C_NamespaceElement(
    val item: C_NamespaceItem,
    allItems: List<C_NamespaceItem>,
) {
    private val allItems = allItems.toImmList()

    val member: C_NamespaceMember = item.member

    fun access(msgCtx: C_MessageContext, nameFn: () -> C_QualifiedName) {
        if (allItems.size > 1) {
            val qName = nameFn()
            val qNameStr = qName.str()
            val listCodeMsg = allItems.map {
                val declType = it.member.declarationType()
                val defName = it.member.defName.appLevelName
                "$declType:$defName" toCodeMsg "${declType.msg} $defName"
            }
            val listCode = listCodeMsg.joinToString(",") { it.code }
            val listMsg = listCodeMsg.joinToString { it.msg }
            msgCtx.error(qName.last.pos, "namespace:ambig:$qNameStr:[$listCode]", "Name '$qNameStr' is ambiguous: $listMsg")
        }

        val deprecation = item.deprecation
        if (deprecation != null) {
            val qName = nameFn()
            val nameStr = deprecation.defName.qualifiedName.str()
            deprecatedMessage(msgCtx, qName.last.pos, nameStr, member.declarationType(), deprecation.deprecated)
        }
    }

    fun toExprMember(ctx: C_ExprContext, qName: C_QualifiedName, ideInfoHand: C_IdeSymbolInfoHandle): C_Expr {
        access(ctx.msgCtx) { qName }

        val ideInfoPtr = C_UniqueDefaultIdeInfoPtr(ideInfoHand, item.ideInfo)
        val expr = member.toExpr(ctx, qName, ideInfoPtr)

        if (ideInfoPtr.isValid()) {
            ideInfoPtr.setDefault()
        }

        return expr
    }

    companion object {
        fun deprecatedMessage(
            msgCtx: C_MessageContext,
            pos: S_Pos,
            nameMsg: String,
            declarationType: C_DeclarationType,
            deprecated: C_Deprecated,
        ) {
            val typeStr = declarationType.msg.capitalize()
            val depCode = deprecated.detailsCode()
            val depStr = deprecated.detailsMessage()
            val code = "deprecated:$declarationType:[$nameMsg]$depCode"
            val msg = "$typeStr '$nameMsg' is deprecated$depStr"

            val error = deprecated.error || msgCtx.globalCtx.compilerOptions.deprecatedError
            val msgType = if (error) C_MessageType.ERROR else C_MessageType.WARNING

            msgCtx.message(msgType, pos, code, msg)
        }
    }
}

// This class is needed to override IDE info. Exact import alias must have different IDE info than the referenced
// member (def ID and link).
class C_NamespaceItem(
    val member: C_NamespaceMember,
    val ideInfo: C_IdeSymbolInfo = member.ideInfo,
    val deprecation: C_DefDeprecation? = member.deprecation,
): DocDefinition {
    override val docSymbol: DocSymbol get() = ideInfo.getIdeInfo().doc ?: DocSymbol.NONE

    override fun getDocMember(name: String): DocDefinition? {
        return member.getDocMember(name)
    }
}

class C_NamespaceEntry(
    directItems: List<C_NamespaceItem>,
    importItems: List<C_NamespaceItem>,
) {
    val directItems = directItems.toImmList()
    val importItems = importItems.toImmList()

    init {
        check(this.directItems.isNotEmpty() || this.importItems.isNotEmpty())
    }

    fun hasTag(tags: List<C_NamespaceMemberTag>): Boolean {
        return directItems.any { it.member.hasTag(tags) } || importItems.any { it.member.hasTag(tags) }
    }

    fun element(tag: C_NamespaceMemberTag): C_NamespaceElement {
        return element(immListOf(tag))
    }

    fun element(tags: List<C_NamespaceMemberTag> = immListOf()): C_NamespaceElement {
        return element0(tags) ?: element0(immListOf())!!
    }

    private fun element0(tags: List<C_NamespaceMemberTag>): C_NamespaceElement? {
        val items = directItems.filter { it.member.hasTag(tags) }
            .ifEmpty { importItems.filter { it.member.hasTag(tags) } }
            .toSet().toImmList()
        return when {
            items.isEmpty() -> null
            items.size == 1 -> C_NamespaceElement(items[0], immListOf())
            else -> C_NamespaceElement(items[0], items)
        }
    }
}

sealed class C_Namespace {
    abstract fun getEntries(): Map<R_Name, C_NamespaceEntry>
    abstract fun getEntry(name: R_Name): C_NamespaceEntry?

    fun getElement(name: R_Name, tags: List<C_NamespaceMemberTag> = immListOf()): C_NamespaceElement? {
        val entry = getEntry(name)
        return entry?.element(tags)
    }

    companion object {
        val EMPTY: C_Namespace = C_BasicNamespace(immMapOf())

        fun makeLate(getter: LateGetter<C_Namespace>): C_Namespace {
            return C_LateNamespace(getter)
        }
    }
}

private class C_BasicNamespace(entries: Map<R_Name, C_NamespaceEntry>): C_Namespace() {
    private val entries = entries.toImmMap()

    override fun getEntries(): Map<R_Name, C_NamespaceEntry> {
        return entries
    }

    override fun getEntry(name: R_Name): C_NamespaceEntry? {
        return entries[name]
    }
}

private class C_LateNamespace(private val getter: LateGetter<C_Namespace>): C_Namespace() {
    override fun getEntries() = getter.get().getEntries()
    override fun getEntry(name: R_Name) = getter.get().getEntry(name)
}

class C_NamespaceBuilder {
    private val directItems = mutableMultimapOf<R_Name, C_NamespaceItem>()
    private val importItems = mutableMultimapOf<R_Name, C_NamespaceItem>()

    fun add(name: R_Name, item: C_NamespaceItem) {
        directItems.put(name, item)
    }

    fun add(name: R_Name, entry: C_NamespaceEntry) {
        directItems.putAll(name, entry.directItems)
        importItems.putAll(name, entry.importItems)
    }

    fun build(): C_Namespace {
        val names = directItems.keySet() + importItems.keySet()
        val entries = names.associateWith {
            val directIts = directItems.get(it).toImmList()
            val importIts = importItems.get(it).toImmList()
            C_NamespaceEntry(directIts, importIts)
        }.toImmMap()
        return C_BasicNamespace(entries)
    }
}
