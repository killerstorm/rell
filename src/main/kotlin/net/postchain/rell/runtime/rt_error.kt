/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.runtime

import net.postchain.rell.model.R_FilePos
import net.postchain.rell.model.R_StackPos

sealed class Rt_BaseError: RuntimeException {
    constructor(msg: String, cause: Throwable? = null): super(msg, cause)
    abstract fun updateMessage(msg: String): Rt_BaseError
}

class Rt_Error: Rt_BaseError {
    val code: String

    constructor(code: String, msg: String): super(msg) {
        this.code = code
    }

    constructor(code: String, msg: String, cause: Throwable): super(msg, cause) {
        this.code = code
    }

    override fun updateMessage(msg: String) = Rt_Error(code, msg, this)
}

class Rt_RequireError: Rt_BaseError {
    val userMsg: String?

    constructor(userMsg: String?): this(userMsg, userMsg ?: "Requirement error", null)

    private constructor(userMsg: String?, message: String, cause: Throwable?): super(message, cause) {
        this.userMsg = userMsg
    }

    override fun updateMessage(msg: String) = Rt_RequireError(userMsg, msg, this)
}

class Rt_ValueTypeError: Rt_BaseError {
    val expected: Rt_ValueType
    val actual: Rt_ValueType

    constructor(expected: Rt_ValueType, actual: Rt_ValueType)
            : this(expected, actual, "Value type mismatch: expected $expected, but was $actual", null)

    private constructor(expected: Rt_ValueType, actual: Rt_ValueType, message: String, cause: Throwable?): super(message, cause) {
        this.expected = expected
        this.actual = actual
    }

    override fun updateMessage(msg: String) = Rt_ValueTypeError(expected, actual, msg, this)
}

class Rt_GtvError: Rt_BaseError {
    val code: String

    constructor(code: String, msg: String): this(code, msg, null)

    private constructor(code: String, msg: String, cause: Throwable?): super(msg, cause) {
        this.code = code
    }

    override fun updateMessage(msg: String) = Rt_GtvError(code, msg, this)
}

class Rt_StackTraceError private constructor(
        message: String,
        val realCause: Throwable,
        val stack: List<R_StackPos>
): Rt_BaseError(message, realCause) {
    override fun updateMessage(msg: String) = Rt_StackTraceError(msg, realCause, stack)

    companion object {
        fun <T> trackStack(frame: Rt_CallFrame, filePos: R_FilePos, code: () -> T): T {
            val defPos = frame.defCtx.defId
            if (defPos == null) {
                return code()
            }

            try {
                return code()
            } catch (e: Rt_StackTraceError) {
                throw e
            } catch (e: Rt_BaseError) {
                val stack = frame.stackTrace(filePos)
                throw Rt_StackTraceError(e.message ?: "", e, stack)
            }
        }
    }
}
