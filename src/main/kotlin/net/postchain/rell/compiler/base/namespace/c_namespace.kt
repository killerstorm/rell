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
    val member: C_NamespaceMember,
    allMembers: List<C_NamespaceMember>,
) {
    private val allMembers = allMembers.toImmList()

    fun access(msgCtx: C_MessageContext, nameFn: () -> C_QualifiedName) {
        if (allMembers.size > 1) {
            val qName = nameFn()
            val qNameStr = qName.str()
            val listCodeMsg = allMembers.map {
                val declType = it.declarationType()
                val defName = it.defName.appLevelName
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
        return C_ExprMember(expr, member.ideInfo)
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

class C_NamespaceEntry(
    directMembers: List<C_NamespaceMember>,
    importMembers: List<C_NamespaceMember>,
) {
    val directMembers = directMembers.toImmList()
    val importMembers = importMembers.toImmList()

    init {
        check(this.directMembers.isNotEmpty() || this.importMembers.isNotEmpty())
    }

    fun hasTag(tags: List<C_NamespaceMemberTag>): Boolean {
        return directMembers.any { it.hasTag(tags) } || importMembers.any { it.hasTag(tags) }
    }

    fun element(tag: C_NamespaceMemberTag): C_NamespaceElement {
        return element(immListOf(tag))
    }

    fun element(tags: List<C_NamespaceMemberTag> = immListOf()): C_NamespaceElement {
        return element0(tags) ?: element0(immListOf())!!
    }

    private fun element0(tags: List<C_NamespaceMemberTag>): C_NamespaceElement? {
        val members = directMembers.filter { it.hasTag(tags) }
            .ifEmpty { importMembers.filter { it.hasTag(tags) } }
        return when {
            members.isEmpty() -> null
            members.size == 1 -> C_NamespaceElement(members[0], immListOf())
            else -> C_NamespaceElement(members[0], members)
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
    private val directMembers = mutableMultimapOf<R_Name, C_NamespaceMember>()
    private val importMembers = mutableMultimapOf<R_Name, C_NamespaceMember>()

    fun add(name: R_Name, member: C_NamespaceMember) {
        directMembers.put(name, member)
    }

    fun add(name: R_Name, entry: C_NamespaceEntry) {
        directMembers.putAll(name, entry.directMembers)
        importMembers.putAll(name, entry.importMembers)
    }

    fun build(): C_Namespace {
        val names = directMembers.keySet() + importMembers.keySet()
        val entries = names.associateWith {
            val directMems = directMembers.get(it).toImmList()
            val importMems = importMembers.get(it).toImmList()
            C_NamespaceEntry(directMems, importMems)
        }.toImmMap()
        return C_BasicNamespace(entries)
    }
}
