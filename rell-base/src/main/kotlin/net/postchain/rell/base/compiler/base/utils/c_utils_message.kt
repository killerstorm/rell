/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.base.utils

import com.google.common.math.IntMath
import net.postchain.rell.base.compiler.ast.S_Pos
import net.postchain.rell.base.utils.toImmList

enum class C_MessageType(val text: String, val ignorable: Boolean) {
    WARNING("Warning", true),
    ERROR("ERROR", false)
}

class C_Message(
        val type: C_MessageType,
        val pos: S_Pos,
        val code: String,
        val text: String
) {
    override fun toString(): String {
        return "$pos ${type.text}: $text"
    }
}

class C_MessageManager {
    private val messages = mutableListOf<C_Message>()
    private var errorCount = 0

    fun message(type: C_MessageType, pos: S_Pos, code: String, text: String) {
        messages.add(C_Message(type, pos, code, text))
        if (type == C_MessageType.ERROR) errorCount = IntMath.checkedAdd(errorCount, 1)
    }

    fun warning(pos: S_Pos, code: String, text: String) {
        message(C_MessageType.WARNING, pos, code, text)
    }

    fun error(pos: S_Pos, code: String, msg: String) {
        message(C_MessageType.ERROR, pos, code, msg)
    }

    fun error(error: C_Error) {
        error(error.pos, error.code, error.errMsg)
    }

    fun messages() = messages.toImmList()

    fun <T> consumeError(code: () -> T): T? {
        try {
            return code()
        } catch (e: C_Error) {
            error(e)
            return null
        }
    }

    fun errorWatcher() = C_ErrorWatcher()
    fun firstErrorReporter() = C_FirstErrorReporter()

    inner class C_ErrorWatcher {
        private var lastErrorCount = errorCount

        fun hasNewErrors(): Boolean {
            val count = errorCount
            val res = count > lastErrorCount
            lastErrorCount = count
            return res
        }
    }

    inner class C_FirstErrorReporter {
        private var reported = false

        fun error(pos: S_Pos, code: String, msg: String) {
            if (!reported) {
                reported = true
                this@C_MessageManager.error(pos, code, msg)
            }
        }

        fun error(pos: S_Pos, codeMsg: C_CodeMsg) = error(pos, codeMsg.code, codeMsg.msg)
    }
}
