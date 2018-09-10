package net.postchain.rell.model

class ROperation(val name: String, val params: Array<RAttrib>, val statements: Array<RStatement>)
class RModule(val relations: Array<RRel>, val operations: Array<ROperation>)