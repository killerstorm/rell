package net.postchain.rell.runtime

import com.google.common.collect.Sets
import net.postchain.gtx.GTXValue
import net.postchain.rell.model.*
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.toHex
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

sealed class Rt_BaseError(msg: String): Exception(msg)
class Rt_Error(val code: String, msg: String): Rt_BaseError(msg)
class Rt_RequireError(val userMsg: String?): Rt_BaseError(userMsg ?: "Requirement error")
class Rt_ValueTypeError(val expected: String, val actual: String):
        Rt_BaseError("Value type missmatch: expected $expected, but was $actual")
class Rt_GtxValueError(val code: String, msg: String): Rt_BaseError(msg)

class Rt_ChainDependency(val rid: ByteArray, val height: Long)

class Rt_ExternalChain(val chainId: Long, val rid: ByteArray, val height: Long) {
    val sqlMapping = Rt_ChainSqlMapping(chainId)
}

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

class Rt_SqlContext private constructor(
        val module: R_Module,
        val mainChainMapping: Rt_ChainSqlMapping,
        private val linkedExternalChains: List<Rt_ExternalChain>
) {
    companion object {
        fun createNoExternalChains(module: R_Module, mainChainMapping: Rt_ChainSqlMapping): Rt_SqlContext {
            check(module.externalChains.isEmpty()) { "Module uses external chains" }
            return Rt_SqlContext(module, mainChainMapping, listOf())
        }

        fun create(
                module: R_Module,
                mainChainMapping: Rt_ChainSqlMapping,
                chainDependencies: Map<String, Rt_ChainDependency>,
                sqlExec: SqlExecutor
        ): Rt_SqlContext {
            val externalChains = getExternalChains(sqlExec, chainDependencies)
            val linkedExternalChains = calcLinkedExternalChains(module, externalChains)
            val sqlCtx = Rt_SqlContext(module, mainChainMapping, linkedExternalChains)
            checkExternalMetaInfo(sqlCtx, externalChains, sqlExec)
            return sqlCtx
        }

        private fun getExternalChains(
                sqlExec: SqlExecutor,
                dependencies: Map<String, Rt_ChainDependency>
        ): Map<String, Rt_ExternalChain> {
            if (dependencies.isEmpty()) return mapOf()

            val rids = mutableSetOf<String>()
            for ((name, dep) in dependencies) {
                val ridStr = dep.rid.toHex()
                if (!rids.add(ridStr)) {
                    throw Rt_Error("external_chain_dup_rid:$name:$ridStr",
                            "Duplicate external chain RID: '$name', 0x$ridStr")
                }
            }

            val dbChains = loadDatabaseBlockchains(sqlExec)
            val dbRidMap = dbChains.map { (chainId, rid) -> Pair(rid.toHex(), chainId) }.toMap()

            val res = mutableMapOf<String, Rt_ExternalChain>()
            for ((name, dep) in dependencies) {
                val ridStr = dep.rid.toHex()
                val chainId = dbRidMap[ridStr]
                if (chainId == null) {
                    throw Rt_Error("external_chain_norid:$name:$ridStr",
                            "External chain '$name' not found in the database by RID 0x$ridStr")
                }
                res[name] = Rt_ExternalChain(chainId, dep.rid, dep.height)
            }

            return res
        }

        private fun loadDatabaseBlockchains(sqlExec: SqlExecutor): Map<Long, ByteArray> {
            val res = mutableMapOf<Long, ByteArray>()
            sqlExec.executeQuery("SELECT chain_id, blockchain_rid FROM blockchains ORDER BY chain_id;", {}) { rs ->
                val chainId = rs.getLong(1)
                val rid = rs.getBytes(2)
                check(chainId !in res)
                res[chainId] = rid
            }
            return res
        }

        private fun calcLinkedExternalChains(
                module: R_Module,
                externalChains: Map<String, Rt_ExternalChain>
        ): List<Rt_ExternalChain> {
            val chainIds = mutableSetOf<Long>()
            val chainRids = mutableSetOf<String>()
            for ((name, c) in externalChains) {
                val id = c.chainId
                val rid = c.rid.toHex()
                if (!chainIds.add(id)) {
                    throw Rt_Error("external_chain_dup_id:$name:$id", "Duplicate external chain ID: '$name', $id")
                }
                if (!chainRids.add(rid)) {
                    throw Rt_Error("external_chain_dup_rid:$name:$rid", "Duplicate external chain RID: '$name', 0x$rid")
                }
            }

            return module.externalChains.map { rChain ->
                val name = rChain.name
                val rtChain = externalChains[name]
                if (rtChain == null) {
                    throw Rt_Error("external_chain_unknown:$name", "External chain not found: '$name'")
                }
                rtChain!!
            }
        }

        private fun checkExternalMetaInfo(sqlCtx: Rt_SqlContext, chains: Map<String, Rt_ExternalChain>, sqlExec: SqlExecutor) {
            val chainMetaClasses = chains.mapValues { (_, chain) -> loadExternalMetaClasses(chain, sqlExec) }
            val chainExternalClasses = getChainExternalClasses(sqlCtx.module)

            for (chain in chainExternalClasses.keys) {
                val extClasses = chainExternalClasses.getValue(chain)
                val metaClasses = chainMetaClasses.getOrDefault(chain, mapOf())
                checkMissingClasses(chain, extClasses, metaClasses)

                for (clsName in extClasses.keys.sorted()) {
                    val extCls = extClasses.getValue(clsName)
                    val metaCls = metaClasses.getValue(clsName)
                    if (!metaCls.log) {
                        throw Rt_Error("external_meta_nolog:$chain:$clsName",
                                "Class '$clsName' in external chain '$chain' is not a log class")
                    }

                    checkMissingAttrs(chain, extCls, metaCls)
                    checkAttrTypes(sqlCtx, chain, extCls, metaCls)
                }
            }
        }

        private fun checkMissingClasses(chain: String, extClasses: Map<String, R_Class>, metaClasses: Map<String, Rt_ExternalClass>) {
            val missingClasses = Sets.difference(extClasses.keys, metaClasses.keys)
            if (!missingClasses.isEmpty()) {
                val list = missingClasses.sorted()
                throw Rt_Error("external_meta_nocls:$chain:${list.joinToString(",")}",
                        "Classes not found in external chain '$chain': ${list.joinToString()}")
            }
        }

        private fun checkMissingAttrs(chain: String, extCls: R_Class, metaCls: Rt_ExternalClass) {
            val metaAttrNames = metaCls.attrs.keys
            val extAttrNames = extCls.attributes.keys
            val missingAttrs = Sets.difference(extAttrNames, metaAttrNames)
            if (!missingAttrs.isEmpty()) {
                val clsName = extCls.name
                val list = missingAttrs.sorted()
                throw Rt_Error("external_meta_noattrs:$chain:$clsName:${list.joinToString(",")}",
                        "Missing attributes of class '$clsName' in external chain '$chain': ${list.joinToString()}")
            }
        }

        private fun checkAttrTypes(sqlCtx: Rt_SqlContext, chain: String, extCls: R_Class, metaCls: Rt_ExternalClass) {
            for (extAttr in extCls.attributes.values.sortedBy { it.name }) {
                val attrName = extAttr.name
                val metaAttr = metaCls.attrs.getValue(attrName)
                val metaType = metaAttr.type
                val extType = extAttr.type.sqlAdapter.metaName(sqlCtx)
                if (metaType != extType) {
                    val clsName = extCls.name
                    throw Rt_Error("external_meta_attrtype:$chain:$clsName:$attrName:[$extType]:[$metaType]",
                            "Attribute type missmatch for class '$clsName' in external chain '$chain': " +
                                    "expected '$extType', actual '$metaType'")
                }
            }
        }

        private fun getChainExternalClasses(module: R_Module): Map<String, Map<String, R_Class>> {
            val res = mutableMapOf<String, MutableMap<String, R_Class>>()
            for (cls in module.classes.values) {
                if (cls.external != null && cls.external.metaCheck) {
                    val map = res.computeIfAbsent(cls.external.chain.name) { mutableMapOf() }
                    check(cls.external.externalName !in map)
                    map[cls.external.externalName] = cls
                }
            }
            return res
        }

        private fun loadExternalMetaClasses(chain: Rt_ExternalChain, sqlExec: SqlExecutor): Map<String, Rt_ExternalClass> {
            val res = mutableMapOf<String, Rt_ExternalClass>()
            sqlExec.transaction {
                val clsAttrMap = loadExternalMetaAttrs(sqlExec, chain.sqlMapping.metaAttributesTable)
                val clsMap = loadExternalMetaClasses(sqlExec, chain.sqlMapping.metaClassTable, clsAttrMap)
                res.putAll(clsMap)
            }
            return res.toMap()
        }

        private fun loadExternalMetaClasses(
                sqlExec: SqlExecutor,
                table: String,
                clsAttrMap: Map<Int, Map<String, Rt_ExternalAttr>>
        ): Map<String, Rt_ExternalClass> {
            val sql = """SELECT "id", "name", "log" FROM "$table";"""
            val res = mutableMapOf<String, Rt_ExternalClass>()
            sqlExec.executeQuery(sql, {}) { rs ->
                val clsId = rs.getInt(1)
                val name = rs.getString(2)
                val log = rs.getBoolean(3)
                check(name !in res)
                val attrs = clsAttrMap.getOrDefault(clsId, mapOf())
                res[name] = Rt_ExternalClass(log, attrs)
            }
            return res.toMap()
        }

        private fun loadExternalMetaAttrs(sqlExec: SqlExecutor, table: String): Map<Int, Map<String, Rt_ExternalAttr>> {
            val sql = """SELECT "class_id", "name", "type" FROM "$table";"""
            val res = mutableMapOf<Int, MutableMap<String, Rt_ExternalAttr>>()
            sqlExec.executeQuery(sql, {}) { rs ->
                val clsId = rs.getInt(1)
                val name = rs.getString(2)
                val type = rs.getString(3)
                val attrMap = res.computeIfAbsent(clsId) { mutableMapOf() }
                check(name !in attrMap)
                attrMap[name] = Rt_ExternalAttr(type)
            }
            return res
        }

        private class Rt_ExternalAttr(val type: String)
        private class Rt_ExternalClass(val log: Boolean, val attrs: Map<String, Rt_ExternalAttr>)
    }

    fun linkedChain(chain: R_ExternalChain): Rt_ExternalChain {
        return linkedExternalChains[chain.index]
    }

    fun chainMapping(externalChain: R_ExternalChain?): Rt_ChainSqlMapping {
        return if (externalChain == null) mainChainMapping else linkedChain(externalChain).sqlMapping
    }
}

class Rt_ModuleContext(val globalCtx: Rt_GlobalContext, val module: R_Module, val sqlCtx: Rt_SqlContext) {
    fun insertObjectRecords() {
        val rFrameBlock = R_FrameBlock(null, R_FrameBlockId(0), 0, 0)
        val rFrame = R_CallFrame(0, rFrameBlock)
        val entCtx = Rt_EntityContext(this, true)
        val frame = Rt_CallFrame(entCtx, rFrame)

        for (rObject in module.objects.values) {
            rObject.insert(frame)
        }
    }
}

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

class Rt_SqlExecutor(private val sqlExec: SqlExecutor, private val logErrors: Boolean): SqlExecutor() {
    override fun transaction(code: () -> Unit) {
        wrapErr("(transaction)") {
            sqlExec.transaction(code)
        }
    }

    override fun execute(sql: String) {
        wrapErr(sql) {
            sqlExec.execute(sql)
        }
    }

    override fun execute(sql: String, preparator: (PreparedStatement) -> Unit) {
        wrapErr(sql) {
            sqlExec.execute(sql, preparator)
        }
    }

    override fun executeQuery(sql: String, preparator: (PreparedStatement) -> Unit, consumer: (ResultSet) -> Unit) {
        wrapErr(sql) {
            sqlExec.executeQuery(sql, preparator, consumer)
        }
    }

    private fun <T> wrapErr(sql: String, code: () -> T): T {
        try {
            val res = code()
            return res
        } catch (e: SQLException) {
            if (logErrors) {
                System.err.println("SQL: " + sql)
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
