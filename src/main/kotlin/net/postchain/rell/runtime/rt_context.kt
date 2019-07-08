package net.postchain.rell.runtime

import com.google.common.collect.Sets
import mu.KLogging
import net.postchain.core.ByteArrayKey
import net.postchain.gtv.Gtv
import net.postchain.rell.CommonUtils
import net.postchain.rell.model.*
import net.postchain.rell.sql.MetaClass
import net.postchain.rell.sql.MetaClassType
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlMeta

class Rt_GlobalContext(
        val stdoutPrinter: Rt_Printer,
        val logPrinter: Rt_Printer,
        sqlExec: SqlExecutor,
        val opCtx: Rt_OpContext?,
        val chainCtx: Rt_ChainContext,
        val logSqlErrors: Boolean = false,
        val sqlUpdatePortionSize: Int = 1000, // Experimental maximum is 2^15
        val typeCheck: Boolean = false
){
    val sqlExec: SqlExecutor = Rt_SqlExecutor(sqlExec, logSqlErrors)
}

class Rt_SqlContext private constructor(
        val module: R_Module,
        val mainChainMapping: Rt_ChainSqlMapping,
        private val linkedExternalChains: List<Rt_ExternalChain>
) {
    companion object : KLogging() {
        fun createNoExternalChains(module: R_Module, mainChainMapping: Rt_ChainSqlMapping): Rt_SqlContext {
            check(module.externalChains.isEmpty()) { "Module uses external chains" }
            return Rt_SqlContext(module, mainChainMapping, listOf())
        }

        fun create(
                module: R_Module,
                mainChainMapping: Rt_ChainSqlMapping,
                chainDependencies: Map<String, Rt_ChainDependency>,
                sqlExec: SqlExecutor,
                heightProvider: Rt_ChainHeightProvider
        ): Rt_SqlContext {
            val externalChains = getExternalChains(sqlExec, chainDependencies, heightProvider)
            val linkedExternalChains = calcLinkedExternalChains(module, externalChains)
            val sqlCtx = Rt_SqlContext(module, mainChainMapping, linkedExternalChains)
            checkExternalMetaInfo(sqlCtx, externalChains, sqlExec)
            return sqlCtx
        }

        private fun getExternalChains(
                sqlExec: SqlExecutor,
                dependencies: Map<String, Rt_ChainDependency>,
                heightProvider: Rt_ChainHeightProvider
        ): Map<String, Rt_ExternalChain> {
            if (dependencies.isEmpty()) return mapOf()

            val rids = mutableSetOf<String>()
            for ((name, dep) in dependencies) {
                val ridStr = CommonUtils.bytesToHex(dep.rid)
                if (!rids.add(ridStr)) {
                    throw Rt_Error("external_chain_dup_rid:$name:$ridStr",
                            "Duplicate external chain RID: '$name', 0x$ridStr")
                }
            }

            val dbChains = loadDatabaseBlockchains(sqlExec)
            val dbRidMap = dbChains.map { (chainId, rid) -> Pair(CommonUtils.bytesToHex(rid), chainId) }.toMap()

            val res = mutableMapOf<String, Rt_ExternalChain>()
            for ((name, dep) in dependencies) {
                val ridStr = CommonUtils.bytesToHex(dep.rid)
                val chainId = dbRidMap[ridStr]
                if (chainId == null) {
                    throw Rt_Error("external_chain_no_rid:$name:$ridStr",
                            "External chain '$name' not found in the database by RID 0x$ridStr")
                }

                val ridKey = ByteArrayKey(dep.rid)
                val height = heightProvider.getChainHeight(ridKey, chainId)
                if (height == null) {
                    throw Rt_Error("external_chain_no_height:$name:$ridStr:$chainId",
                            "Unknown height of the external chain '$name' (RID: 0x$ridStr, ID: $chainId)")
                }

                res[name] = Rt_ExternalChain(chainId, dep.rid, height)
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
                val rid = CommonUtils.bytesToHex(c.rid)
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
            val chainMetaClasses = chains.mapValues { (name, chain) -> loadExternalMetaData(name, chain, sqlExec) }
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

        private fun checkMissingClasses(chain: String, extClasses: Map<String, R_Class>, metaClasses: Map<String, MetaClass>) {
            val metaClassNames = metaClasses.filter { (_, c) -> c.type == MetaClassType.CLASS }.keys
            val missingClasses = Sets.difference(extClasses.keys, metaClassNames)
            if (!missingClasses.isEmpty()) {
                val list = missingClasses.sorted()
                throw Rt_Error("external_meta_nocls:$chain:${list.joinToString(",")}",
                        "Classes not found in external chain '$chain': ${list.joinToString()}")
            }
        }

        private fun checkMissingAttrs(chain: String, extCls: R_Class, metaCls: MetaClass) {
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

        private fun checkAttrTypes(sqlCtx: Rt_SqlContext, chain: String, extCls: R_Class, metaCls: MetaClass) {
            for (extAttr in extCls.attributes.values.sortedBy { it.name }) {
                val attrName = extAttr.name
                val metaAttr = metaCls.attrs.getValue(attrName)
                val metaType = metaAttr.type
                val extType = extAttr.type.sqlAdapter.metaName(sqlCtx)
                if (metaType != extType) {
                    val clsName = extCls.name
                    throw Rt_Error("external_meta_attrtype:$chain:$clsName:$attrName:[$extType]:[$metaType]",
                            "Attribute type mismatch for class '$clsName' in external chain '$chain': " +
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

        private fun loadExternalMetaData(name: String, chain: Rt_ExternalChain, sqlExec: SqlExecutor): Map<String, MetaClass> {
            var res = mapOf<String, MetaClass>()

            sqlExec.transaction {
                val msgs = Rt_Messages(logger)
                try {
                    res = SqlMeta.loadMetaData(sqlExec, chain.sqlMapping, msgs)
                    msgs.checkErrors()
                } catch (e: Rt_Error) {
                    throw Rt_Error(e.code, "Failed to load metadata for external chain '$name' (chain_id = ${chain.chainId})", e)
                }
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
    fun createRootFrame(): Rt_CallFrame {
        val rFrameBlock = R_FrameBlock(null, R_FrameBlockId(0), 0, 0)
        val rFrame = R_CallFrame(0, rFrameBlock)
        val entCtx = Rt_EntityContext(this, true)
        return Rt_CallFrame(entCtx, rFrame)
    }

    fun insertObjectRecord(rObject: R_Object) {
        val frame = createRootFrame()
        rObject.insert(frame)
    }
}

class Rt_EntityContext(val modCtx: Rt_ModuleContext, val dbUpdateAllowed: Boolean) {
    fun checkDbUpdateAllowed() {
        if (!dbUpdateAllowed) {
            throw Rt_Error("no_db_update", "Database modifications are not allowed in this context")
        }
    }
}

class Rt_OpContext(val lastBlockTime: Long, val transactionIid: Long, val blockHeight: Long, val signers: List<ByteArray>)

class Rt_ChainContext(val rawConfig: Gtv, val args: Rt_Value?, val blockchainRid: ByteArray)
