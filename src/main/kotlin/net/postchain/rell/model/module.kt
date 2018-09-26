package net.postchain.rell.model

class RAttrib(val name: String, val type: RType)

class ROperation(val name: String, val params: Array<RAttrib>, val statements: Array<RStatement>)

class RQuery(val name: String, val params: Array<RAttrib>, val statements: Array<RStatement>)

class RModule(
        val relations: Array<RRel>,
        val operations: Array<ROperation>,
        val queries: Array<RQuery>
)
