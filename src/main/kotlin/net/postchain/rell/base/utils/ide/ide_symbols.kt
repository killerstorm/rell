/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.utils.ide

import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.model.R_Name
import net.postchain.rell.base.utils.immListOf
import net.postchain.rell.base.utils.toImmList
import net.postchain.rell.base.utils.toImmMap
import java.util.*

enum class IdeSymbolKind {
    UNKNOWN,
    DEF_CONSTANT,
    DEF_ENTITY,
    DEF_ENUM,
    DEF_FUNCTION,
    DEF_FUNCTION_ABSTRACT,
    DEF_FUNCTION_EXTEND,
    DEF_FUNCTION_EXTENDABLE,
    DEF_FUNCTION_SYSTEM,
    DEF_IMPORT_ALIAS,
    DEF_IMPORT_MODULE,
    DEF_NAMESPACE,
    DEF_OBJECT,
    DEF_OPERATION,
    DEF_QUERY,
    DEF_STRUCT,
    DEF_TYPE,
    MEM_ENTITY_ATTR_INDEX,
    MEM_ENTITY_ATTR_INDEX_VAR,
    MEM_ENTITY_ATTR_KEY,
    MEM_ENTITY_ATTR_KEY_VAR,
    MEM_ENTITY_ATTR_NORMAL,
    MEM_ENTITY_ATTR_NORMAL_VAR,
    MEM_ENTITY_ATTR_ROWID,
    MEM_ENUM_VALUE,
    MEM_STRUCT_ATTR,
    MEM_STRUCT_ATTR_VAR,
    MEM_SYS_PROPERTY,
    MEM_TUPLE_ATTR,
    MOD_ANNOTATION,
    MOD_ANNOTATION_LEGACY,
    LOC_AT_ALIAS,
    LOC_PARAMETER,
    LOC_VAL,
    LOC_VAR,
    EXPR_CALL_ARG,
    EXPR_IMPORT_ALIAS,
}

enum class IdeSymbolCategory(@JvmField val code: String) {
    NONE("none"),
    ATTRIBUTE("attr"),
    CONSTANT("constant"),
    ENTITY("entity"),
    ENUM("enum"),
    ENUM_VALUE("value"),
    FUNCTION("function"),
    IMPORT("import"),
    NAMESPACE("namespace"),
    OBJECT("object"),
    OPERATION("operation"),
    PARAMETER("param"),
    QUERY("query"),
    STRUCT("struct"),
    TUPLE("tuple"),
}

class IdeSymbolId(
    private val category: IdeSymbolCategory,
    private val name: String,
    members: List<Pair<IdeSymbolCategory, R_Name>> = immListOf(),
) {
    private val members = members.toImmList()

    fun encode(): String = (listOf(category to name) + members.map { it.first to it.second.str }).joinToString(".") { "${it.first.code}[${it.second}]" }

    fun appendMember(memberCategory: IdeSymbolCategory, memberName: R_Name): IdeSymbolId {
        val members2 = (members + listOf(memberCategory to memberName)).toImmList()
        return IdeSymbolId(category, name, members2)
    }

    override fun equals(other: Any?) = other is IdeSymbolId && category == other.category && name == other.name && members == other.members
    override fun hashCode() = Objects.hash(category, name, members)
    override fun toString() = encode()
}

class IdeSymbolGlobalId(@JvmField val file: IdeFilePath, @JvmField val symId: IdeSymbolId) {
    fun encode(): String = "$file/${symId.encode()}"
    override fun toString() = encode()
}

sealed class IdeSymbolLink {
    abstract fun encode(): String
    final override fun toString(): String = encode()

    open fun moduleFile(): IdeFilePath? = null
    open fun localPos(): S_Pos? = null
    open fun globalId(): IdeSymbolGlobalId? = null
}

class IdeModuleSymbolLink(private val file: IdeFilePath): IdeSymbolLink() {
    override fun encode() = file.toString()
    override fun moduleFile() = file
}

class IdeLocalSymbolLink(private val pos: S_Pos): IdeSymbolLink() {
    override fun encode() = "local[$pos]"
    override fun localPos() = pos
}

class IdeGlobalSymbolLink(private val globalId: IdeSymbolGlobalId): IdeSymbolLink() {
    override fun encode() = globalId.encode()
    override fun globalId() = globalId
}

class IdeSymbolInfo(
    @JvmField val kind: IdeSymbolKind,
    @JvmField val defId: IdeSymbolId? = null,
    @JvmField val link: IdeSymbolLink? = null,
) {
    override fun toString() = kind.name

    fun update(
        kind: IdeSymbolKind = this.kind,
        defId: IdeSymbolId? = this.defId,
        link: IdeSymbolLink? = this.link,
    ): IdeSymbolInfo {
        return if (kind == this.kind && defId === this.defId && link === this.link) this else {
            IdeSymbolInfo(kind = kind, defId = defId, link = link)
        }
    }

    companion object {
        private val byKind: Map<IdeSymbolKind, IdeSymbolInfo> = IdeSymbolKind.values().map { it to IdeSymbolInfo(it) }.toImmMap()

        fun get(kind: IdeSymbolKind): IdeSymbolInfo = byKind.getValue(kind)

        val UNKNOWN = get(IdeSymbolKind.UNKNOWN)
        val DEF_CONSTANT = get(IdeSymbolKind.DEF_CONSTANT)
        val DEF_FUNCTION_SYSTEM = get(IdeSymbolKind.DEF_FUNCTION_SYSTEM)
        val DEF_TYPE = get(IdeSymbolKind.DEF_TYPE)
        val DEF_STRUCT = get(IdeSymbolKind.DEF_STRUCT)
        val MEM_TUPLE_ATTR = get(IdeSymbolKind.MEM_TUPLE_ATTR)
    }
}
