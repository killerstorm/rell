/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.tools.api

enum class IdeSymbolKind {
    UNKNOWN,
    DEF_CONSTANT,
    DEF_ENTITY,
    DEF_ENUM,
    DEF_FUNCTION_ABSTRACT,
    DEF_FUNCTION_EXTEND,
    DEF_FUNCTION_EXTENDABLE,
    DEF_FUNCTION_REGULAR,
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
    MEM_ENUM_ATTR,
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

class IdeSymbolInfo(
        @JvmField val kind: IdeSymbolKind
) {
    override fun toString() = kind.name

    companion object {
        val UNKNOWN = IdeSymbolInfo(IdeSymbolKind.UNKNOWN)
        val DEF_FUNCTION_SYSTEM = IdeSymbolInfo(IdeSymbolKind.DEF_FUNCTION_SYSTEM)
        val DEF_NAMESPACE = IdeSymbolInfo(IdeSymbolKind.DEF_NAMESPACE)
        val DEF_TYPE = IdeSymbolInfo(IdeSymbolKind.DEF_TYPE)
        val MEM_TUPLE_FIELD = IdeSymbolInfo(IdeSymbolKind.MEM_TUPLE_ATTR)
        val EXPR_CALL_ARG = IdeSymbolInfo(IdeSymbolKind.EXPR_CALL_ARG)
    }
}
