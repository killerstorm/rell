package net.postchain.rell.runtime

open class RtError(msg: String): Exception(msg)
class RtErrWrongNumberOfArguments(msg: String): RtError(msg)
class RtErrWrongArgumentType(msg: String): RtError(msg)
