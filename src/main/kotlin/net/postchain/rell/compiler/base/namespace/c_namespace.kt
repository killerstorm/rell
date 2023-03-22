/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.base.namespace

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.base.core.C_DefinitionName
import net.postchain.rell.compiler.base.core.C_MessageContext
import net.postchain.rell.compiler.base.core.C_QualifiedName
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.expr.C_ExprMember
import net.postchain.rell.compiler.base.utils.C_MessageType
import net.postchain.rell.compiler.base.utils.toCodeMsg
import net.postchain.rell.model.R_Name
import net.postchain.rell.tools.api.IdeSymbolInfo
import net.postchain.rell.utils.*

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

    val member = item.member

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
            msgCtx.error(qName.last.pos, "name:ambig:$qNameStr:[$listCode]", "Name '$qNameStr' is ambiguous: $listMsg")
        }

        if (member.deprecation != null) {
            val qName = nameFn()
            val nameStr = member.deprecation.defName.appLevelName
            deprecatedMessage(msgCtx, qName.last.pos, nameStr, member.declarationType(), member.deprecation.deprecated)
        }
    }

    fun toExprMember(ctx: C_ExprContext, qName: C_QualifiedName): C_ExprMember {
        access(ctx.msgCtx) { qName }
        val expr = member.toExpr(ctx, qName)
        return C_ExprMember(expr, item.ideInfo)
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
            val code = "deprecated:$declarationType:$nameMsg$depCode"
            val msg = "$typeStr '$nameMsg' is deprecated$depStr"

            val error = deprecated.error || msgCtx.globalCtx.compilerOptions.deprecatedError
            val msgType = if (error) C_MessageType.ERROR else C_MessageType.WARNING

            msgCtx.message(msgType, pos, code, msg)
        }
    }
}

// This class is needed to override IDE info. Exact import alias must have different IDE info than the referenced member (def ID and link).
class C_NamespaceItem(val member: C_NamespaceMember, val ideInfo: IdeSymbolInfo) {
    constructor(member: C_NamespaceMember): this(member, member.ideInfo)
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
        return when {
            items.isEmpty() -> null
            items.size == 1 -> C_NamespaceElement(items[0], immListOf())
            else -> C_NamespaceElement(items[0], items)
        }
    }
}

sealed class C_Namespace {
    abstract fun getEntry(name: R_Name): C_NamespaceEntry?
    abstract fun addTo(b: C_NamespaceBuilder)

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

    override fun getEntry(name: R_Name): C_NamespaceEntry? {
        return entries[name]
    }

    override fun addTo(b: C_NamespaceBuilder) {
        entries.forEach { b.add(it.key, it.value) }
    }
}

private class C_LateNamespace(private val getter: LateGetter<C_Namespace>): C_Namespace() {
    override fun getEntry(name: R_Name) = getter.get().getEntry(name)
    override fun addTo(b: C_NamespaceBuilder) = getter.get().addTo(b)
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
