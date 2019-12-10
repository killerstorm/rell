package net.postchain.rell.runtime

import mu.KLogging
import net.postchain.core.ByteArrayKey
import net.postchain.rell.model.*
import net.postchain.rell.sql.SqlConstants
import net.postchain.rell.toImmList

class Rt_ChainDependency(val rid: ByteArray)

class Rt_ExternalChain(val chainId: Long, val rid: ByteArray, val height: Long) {
    val sqlMapping = Rt_ChainSqlMapping(chainId)
}

class Rt_CallFrame(
        private val callerFrame: Rt_CallFrame?,
        private val callerPos: R_StackPos?,
        val defCtx: Rt_DefinitionContext,
        rFrame: R_CallFrame
) {
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
        return value
    }

    fun getOpt(ptr: R_VarPtr): Rt_Value? {
        val offset = checkPtr(ptr)
        val value = values[offset]
        return value
    }

    private fun checkPtr(ptr: R_VarPtr): Int {
        val block = curBlock
        check(ptr.blockId == block.id) { "ptr = $ptr, block.id = ${block.id}" }
        val offset = ptr.offset
        check(offset >= 0)
        check(offset < block.offset + block.size)
        return offset
    }

    fun stackPos(filePos: R_FilePos): R_StackPos {
        return R_StackPos(defCtx.pos, filePos)
    }

    fun stackTrace(lastPos: R_FilePos): List<R_StackPos> {
        val res = mutableListOf<R_StackPos>()

        res.add(R_StackPos(defCtx.pos, lastPos))

        var frame: Rt_CallFrame? = this
        while (frame != null) {
            val pos = frame.callerPos
            if (pos != null) res.add(pos)
            frame = frame.callerFrame
        }

        return res.toImmList()
    }
}

interface Rt_Printer {
    fun print(str: String)
}

object Rt_FailingPrinter: Rt_Printer {
    override fun print(str: String) {
        throw UnsupportedOperationException()
    }
}

object Rt_NopPrinter: Rt_Printer {
    override fun print(str: String) {
        // Do nothing.
    }
}

object Rt_StdoutPrinter: Rt_Printer {
    override fun print(str: String) {
        println(str)
    }
}

class Rt_LogPrinter(name: String = "net.postchain.Rell"): Rt_Printer {
    private val logger = KLogging().logger(name)

    override fun print(str: String) {
        logger.info(str)
    }
}

interface Rt_PrinterFactory {
    fun newPrinter(): Rt_Printer
}

class Rt_ChainSqlMapping(val chainId: Long) {
    private val prefix = "c" + chainId + "."

    val rowidTable = fullName(SqlConstants.ROWID_GEN)
    val rowidFunction = fullName(SqlConstants.MAKE_ROWID)
    val metaEntitiesTable = fullName("sys.classes")
    val metaAttributesTable = fullName("sys.attributes")

    val tableSqlFilter = "$prefix%"

    fun fullName(baseName: String): String {
        return prefix + baseName
    }

    fun fullName(mountName: R_MountName): String {
        return prefix + mountName.str()
    }

    fun isChainTable(table: String): Boolean {
        return table.startsWith(prefix) && table != rowidTable && table != rowidFunction
    }
}

interface Rt_ChainHeightProvider {
    fun getChainHeight(rid: ByteArrayKey, id: Long): Long?
}

class Rt_ConstantChainHeightProvider(private val height: Long): Rt_ChainHeightProvider {
    override fun getChainHeight(rid: ByteArrayKey, id: Long) = height
}
