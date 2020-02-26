/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.tools.api

import net.postchain.rell.compiler.ast.S_Name
import net.postchain.rell.compiler.ast.S_Node

enum class IdeOutlineNodeType {
    ENTITY,
    OBJECT,
    STRUCT,
    ATTRIBUTE,
    KEY_INDEX,
    ENUM,
    ENUM_ATTRIBUTE,
    NAMESPACE,
    FUNCTION,
    OPERATION,
    QUERY,
    IMPORT,
}

interface IdeOutlineTreeBuilder {
    fun node(node: S_Node, name: S_Name, type: IdeOutlineNodeType): IdeOutlineTreeBuilder
}
