package net.postchain.rell.runtime

import net.postchain.core.ByteArrayKey
import net.postchain.rell.model.*

sealed class Rt_BaseError(msg: String): Exception(msg)
class Rt_Error(val code: String, msg: String): Rt_BaseError(msg)
class Rt_RequireError(val userMsg: String?): Rt_BaseError(userMsg ?: "Requirement error")
class Rt_ValueTypeError(val expected: Rt_ValueType, val actual: Rt_ValueType):
        Rt_BaseError("Value type mismatch: expected $expected, but was $actual")
class Rt_GtvError(val code: String, msg: String): Rt_BaseError(msg)

class Rt_ChainDependency(val rid: ByteArray)

class Rt_ExternalChain(val chainId: Long, val rid: ByteArray, val height: Long) {
    val sqlMapping = Rt_ChainSqlMapping(chainId)
}

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

    fun set(ptr: R_VarPtr, varType: R_Type, value: Rt_Value, overwrite: Boolean) {
        R_Expr.typeCheck(this, varType, value)
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

class Rt_ChainSqlMapping(val chainId: Long) {
    private val prefix = "c" + chainId + "."

    val rowidTable = fullName("rowid_gen")
    val rowidFunction = fullName("make_rowid")
    val metaClassTable = fullName("sys.classes")
    val metaAttributesTable = fullName("sys.attributes")

    fun fullName(baseName: String): String {
        return prefix + baseName
    }
}

interface Rt_ChainHeightProvider {
    fun getChainHeight(rid: ByteArrayKey, id: Long): Long?
}

class Rt_ConstantChainHeightProvider(private val height: Long): Rt_ChainHeightProvider {
    override fun getChainHeight(rid: ByteArrayKey, id: Long) = height
}
