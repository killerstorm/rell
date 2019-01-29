package net.postchain.rell.runtime

import net.postchain.gtx.GTXValue
import net.postchain.rell.model.*
import net.postchain.rell.sql.SqlExecutor
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

sealed class Rt_BaseError(msg: String): Exception(msg)
class Rt_Error(val code: String, msg: String): Rt_BaseError(msg)
class Rt_RequireError(val userMsg: String?): Rt_BaseError(userMsg ?: "Requirement error")
class Rt_ValueTypeError(val expected: String, val actual: String):
        Rt_BaseError("Value type missmatch: expected $expected, but was $actual")
class Rt_GtxValueError(val code: String, msg: String): Rt_BaseError(msg)

class Rt_GlobalContext(
        val stdoutPrinter: Rt_Printer,
        val logPrinter: Rt_Printer,
        sqlExec: SqlExecutor,
        val opCtx: Rt_OpContext?,
        val chainCtx: Rt_ChainContext,
        val logSqlErrors: Boolean = false,
        val sqlUpdatePortionSize: Int = 1000 // Experimental maximum is 2^15
){
    val sqlExec: SqlExecutor = Rt_SqlExecutor(sqlExec, logSqlErrors)
}

class Rt_ModuleContext(val globalCtx: Rt_GlobalContext, val module: R_Module)

class Rt_EntityContext(val modCtx: Rt_ModuleContext, val dbUpdateAllowed: Boolean) {
    fun checkDbUpdateAllowed() {
        if (!dbUpdateAllowed) {
            throw Rt_Error("no_db_update", "Database modifications are not allowed in this context")
        }
    }
}

class Rt_OpContext(val lastBlockTime: Long, val transactionIid: Long, val signers: List<ByteArray>)

class Rt_ChainContext(val rawConfig: GTXValue, val args: Rt_Value)

class Rt_CallFrame(val entCtx: Rt_EntityContext, rFrame: R_CallFrame) {
    private var curBlock = rFrame.rootBlock
    private val values = Array<Rt_Value?>(rFrame.size) { null }

    fun <T> block(block: R_FrameBlock, code: () -> T): T {
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

    fun set(ptr: R_VarPtr, value: Rt_Value, overwrite: Boolean) {
        val offset = checkPtr(ptr)
        if (!overwrite) {
            check(values[offset] == null)
        }
        values[offset] = value
    }

    fun get(ptr: R_VarPtr): Rt_Value {
        val value = getOpt(ptr)
        check(value != null) { "Variable not initialized: $ptr" }
        return value!!
    }

    fun getOpt(ptr: R_VarPtr): Rt_Value? {
        val offset = checkPtr(ptr)
        val value = values[offset]
        return value
    }

    private fun checkPtr(ptr: R_VarPtr): Int {
        val block = curBlock
        check(ptr.blockId == block.id)
        val offset = ptr.offset
        check(offset >= 0)
        check(offset < block.offset + block.size)
        return offset
    }
}

abstract class Rt_Printer {
    abstract fun print(str: String)
}

object Rt_FailingPrinter: Rt_Printer() {
    override fun print(str: String) {
        throw UnsupportedOperationException()
    }
}

class Rt_SqlExecutor(private val sqlExec: SqlExecutor, private val logErrors: Boolean): SqlExecutor() {
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

    private fun <T> wrapErr(code: () -> T): T {
        try {
            val res = code()
            return res
        } catch (e: SQLException) {
            if (logErrors) {
                e.printStackTrace()
            }
            throw Rt_Error("sqlerr:${e.errorCode}", "SQL Error: ${e.message}")
        }
    }
}

object Rt_Utils {
    fun errNotSupported(msg: String): Rt_Error {
        return Rt_Error("not_supported", msg)
    }

    fun <T> wrapErr(errCode: String, code: () -> T): T {
        try {
            val res = code()
            return res
        } catch (e: Rt_BaseError) {
            throw e
        } catch (e: Throwable) {
            throw Rt_Error(errCode, e.message ?: "")
        }
    }
}
