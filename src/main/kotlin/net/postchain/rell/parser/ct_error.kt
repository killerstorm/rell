package net.postchain.rell.parser

open class CtError(msg: String): Exception(msg)
class CtOperandTypeError(msg: String): CtError(msg)
