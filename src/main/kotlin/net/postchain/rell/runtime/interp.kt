package net.postchain.rell.runtime

import net.postchain.rell.model.*
import net.postchain.rell.sql.SqlExecutor
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

sealed class RtBaseError(msg: String): Exception(msg)
class RtError(val code: String, msg: String): RtBaseError(msg)
class RtRequireError(val userMsg: String?): RtBaseError(userMsg ?: "Requirement error")

class RtGlobalContext(
        val stdoutPrinter: RtPrinter,
        val logPrinter: RtPrinter,
        sqlExec: SqlExecutor,
        val opCtx: RtOpContext?
){
    val sqlExec: SqlExecutor = RtSqlExecutor(sqlExec)
}

class RtModuleContext(val globalCtx: RtGlobalContext, val module: RModule)

class RtEntityContext(val modCtx: RtModuleContext, val dbUpdateAllowed: Boolean) {
    fun checkDbUpdateAllowed() {
        if (!dbUpdateAllowed) {
            throw RtError("no_db_update", "Database modifications are not allowed in this context")
        }
    }
}

class RtOpContext(val lastBlockTime: Long, val signers: List<ByteArray>)

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

class RtSqlExecutor(private val sqlExec: SqlExecutor): SqlExecutor() {
    override fun transaction(code: () -> Unit) {
        wrapErr {
            sqlExec.transaction(code)
        }
    }

    override fun execute(sql: String) {
        wrapErr {
            sqlExec.execute(sql)
        }
    }

    override fun execute(sql: String, preparator: (PreparedStatement) -> Unit) {
        wrapErr {
            sqlExec.execute(sql, preparator)
        }
    }

    override fun executeQuery(sql: String, preparator: (PreparedStatement) -> Unit, consumer: (ResultSet) -> Unit) {
        wrapErr {
            sqlExec.executeQuery(sql, preparator, consumer)
        }
    }

    private fun wrapErr(code: () -> Unit) {
        try {
            code()
        } catch (e: SQLException) {
            throw RtError("sqlerr:${e.errorCode}", "SQL Error: ${e.message}")
        }
    }
}

object RtUtils {
    fun errNotSupported(msg: String): RtError {
        return RtError("not_supported", msg)
    }
}
