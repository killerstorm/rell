package net.postchain.rell.parser.ide

import net.postchain.rell.parser.S_Name
import net.postchain.rell.parser.S_Node

enum class IdeOutlineNodeType {
    CLASS,
    OBJECT,
    RECORD,
    ATTRIBUTE,
    KEY_INDEX,
    ENUM,
    ENUM_ATTRIBUTE,
    NAMESPACE,
    FUNCTION,
    OPERATION,
    QUERY
}

interface IdeOutlineTreeBuilder {
    fun node(node: S_Node, name: S_Name, type: IdeOutlineNodeType): IdeOutlineTreeBuilder
}
