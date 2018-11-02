package net.postchain.rell.runtime

import net.postchain.rell.model.*
import net.postchain.rell.sql.SqlExecutor
import java.lang.UnsupportedOperationException

sealed class RtBaseError(msg: String): Exception(msg)
class RtError(val code: String, msg: String): RtBaseError(msg)
class RtRequireError(msg: String): RtBaseError(msg)

class RtGlobalContext(val stdoutPrinter: RtPrinter, val logPrinter: RtPrinter, val sqlExec: SqlExecutor)

class RtModuleContext(val globalCtx: RtGlobalContext, val module: RModule)

class RtEntityContext(val modCtx: RtModuleContext, val dbUpdateAllowed: Boolean) {
    fun checkDbUpdateAllowed() {
        if (!dbUpdateAllowed) {
            throw RtError("no_db_update", "Database modifications are not allowed in this context")
        }
    }
}

class RtCallFrame(val entCtx: RtEntityContext, rFrame: RCallFrame) {
    private var curBlock = rFrame.rootBlock
    private val values = Array<RtValue?>(rFrame.size) { null }

    fun <T> block(block: RFrameBlock, code: () -> T): T {
        val oldBlock = curBlock
        check(block.parentId == oldBlock.id)
        check(block.offset + block.size <= values.size)

        for (i in 0 until block.size) {
            check(values[block.offset + i] == null)
        }

        curBlock = block
        try {
            val res = code()
            return res
        } finally {
            curBlock = oldBlock
            for (i in 0 until block.size) {
                values[block.offset + i] = null
            }
        }
    }

    fun set(ptr: RVarPtr, value: RtValue, overwrite: Boolean) {
        val offset = checkPtr(ptr)
        if (!overwrite) {
            check(values[offset] == null)
        }
        values[offset] = value
    }

    fun get(ptr: RVarPtr): RtValue {
        val value = getOpt(ptr)
        check(value != null) { "Variable not initialized: $ptr" }
        return value!!
    }

    fun getOpt(ptr: RVarPtr): RtValue? {
        val offset = checkPtr(ptr)
        val value = values[offset]
        return value
    }

    private fun checkPtr(ptr: RVarPtr): Int {
        val block = curBlock
        check(ptr.blockId == block.id)
        val offset = ptr.offset
        check(offset >= 0)
        check(offset < block.offset + block.size)
        return offset
    }
}

abstract class RtPrinter {
    abstract fun print(str: String)
}

object FailingRtPrinter: RtPrinter() {
    override fun print(str: String) {
        throw UnsupportedOperationException()
    }
}

object RtUtils {
    // https://stackoverflow.com/a/2632501
    fun saturatedAdd(a: Long, b: Long): Long {
        if (a == 0L || b == 0L || ((a > 0) != (b > 0))) {
            return a + b
        } else if (a > 0) {
            return if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else (a + b)
        } else {
            return if (Long.MIN_VALUE - a > b) Long.MIN_VALUE else (a + b)
        }
    }
}
