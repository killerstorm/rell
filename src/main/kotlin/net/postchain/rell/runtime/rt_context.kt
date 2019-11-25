package net.postchain.rell.runtime

import com.google.common.collect.Sets
import mu.KLogging
import net.postchain.core.ByteArrayKey
import net.postchain.gtv.Gtv
import net.postchain.rell.CommonUtils
import net.postchain.rell.model.*
import net.postchain.rell.sql.*
import net.postchain.rell.toImmMap

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

    private val rellVersion = Rt_RellVersion.getInstance()

    fun rellVersion(): Rt_RellVersion {
        return rellVersion ?: throw Rt_Error("fn:rell.git_info:no_data", "Version information not found")
    }
}

class Rt_SqlContext private constructor(
        app: R_App,
        val mainChainMapping: Rt_ChainSqlMapping,
        private val linkedExternalChains: List<Rt_ExternalChain>
) {
    val entities = app.entities
    val objects = app.objects
    val topologicalEntities = app.topologicalEntities
    private val externalChainsRoot = app.externalChainsRoot

    fun linkedChain(chain: R_ExternalChainRef): Rt_ExternalChain {
        check(chain.root === externalChainsRoot)
        return linkedExternalChains[chain.index]
    }

    fun chainMapping(externalChain: R_ExternalChainRef?): Rt_ChainSqlMapping {
        return if (externalChain == null) mainChainMapping else linkedChain(externalChain).sqlMapping
    }

    class InitError(val code: String, val msg: String): java.lang.RuntimeException(msg)

    companion object : KLogging() {
        fun createNoExternalChains(app: R_App, mainChainMapping: Rt_ChainSqlMapping): Rt_SqlContext {
            require(app.valid)
            require(app.externalChains.isEmpty()) { "App uses external chains" }
            return Rt_SqlContext(app, mainChainMapping, listOf())
        }

        fun create(
                app: R_App,
                mainChainMapping: Rt_ChainSqlMapping,
                chainDependencies: Map<String, Rt_ChainDependency>,
                sqlExec: SqlExecutor,
                heightProvider: Rt_ChainHeightProvider
        ): Rt_SqlContext {
            require(app.valid)
            val externalChains = getExternalChains(sqlExec, chainDependencies, heightProvider)
            val linkedExternalChains = calcLinkedExternalChains(app, externalChains)
            val sqlCtx = Rt_SqlContext(app, mainChainMapping, linkedExternalChains)
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
                    throw errInit("external_chain_dup_rid:$name:$ridStr",
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
                    throw errInit("external_chain_no_rid:$name:$ridStr",
                            "External chain '$name' not found in the database by RID 0x$ridStr")
                }

                val ridKey = ByteArrayKey(dep.rid)
                val height = heightProvider.getChainHeight(ridKey, chainId)
                if (height == null) {
                    throw errInit("external_chain_no_height:$name:$ridStr:$chainId",
                            "Unknown height of the external chain '$name' (RID: 0x$ridStr, ID: $chainId)")
                }

                res[name] = Rt_ExternalChain(chainId, dep.rid, height)
            }

            return res
        }

        private fun loadDatabaseBlockchains(sqlExec: SqlExecutor): Map<Long, ByteArray> {
            val res = mutableMapOf<Long, ByteArray>()
            sqlExec.executeQuery("SELECT chain_iid, blockchain_rid FROM blockchains ORDER BY chain_iid;", {}) { rs ->
                val chainId = rs.getLong(1)
                val rid = rs.getBytes(2)
                check(chainId !in res)
                res[chainId] = rid
            }
            return res
        }

        private fun calcLinkedExternalChains(
                app: R_App,
                externalChains: Map<String, Rt_ExternalChain>
        ): List<Rt_ExternalChain> {
            val chainIds = mutableSetOf<Long>()
            val chainRids = mutableSetOf<String>()
            for ((name, c) in externalChains) {
                val id = c.chainId
                val rid = CommonUtils.bytesToHex(c.rid)
                if (!chainIds.add(id)) {
                    throw errInit("external_chain_dup_id:$name:$id", "Duplicate external chain ID: '$name', $id")
                }
                if (!chainRids.add(rid)) {
                    throw errInit("external_chain_dup_rid:$name:$rid", "Duplicate external chain RID: '$name', 0x$rid")
                }
            }

            return app.externalChains.map { rChain ->
                val name = rChain.name
                val rtChain = externalChains[name]
                if (rtChain == null) {
                    throw errInit("external_chain_unknown:$name", "External chain not found: '$name'")
                }
                rtChain!!
            }
        }

        private fun checkExternalMetaInfo(sqlCtx: Rt_SqlContext, chains: Map<String, Rt_ExternalChain>, sqlExec: SqlExecutor) {
            val chainMetaEntities = chains.mapValues { (name, chain) -> loadExternalMetaData(name, chain, sqlExec) }
            val chainExternalEntities = getChainExternalEntities(sqlCtx.entities)

            for (chain in chainExternalEntities.keys) {
                val extEntities = chainExternalEntities.getValue(chain)
                val metaEntities = chainMetaEntities.getOrDefault(chain, mapOf())
                checkMissingEntities(chain, extEntities, metaEntities)

                for (entityName in extEntities.keys.sorted()) {
                    val extEntity = extEntities.getValue(entityName)
                    val metaEntity = metaEntities.getValue(entityName)
                    if (!metaEntity.log) {
                        throw errInit("external_meta_nolog:$chain:$entityName",
                                "Entity '$entityName' in external chain '$chain' is not a log entity")
                    }

                    checkMissingAttrs(chain, extEntity, metaEntity)
                    checkAttrTypes(sqlCtx, chain, extEntity, metaEntity)
                }
            }
        }

        private fun checkMissingEntities(chain: String, extEntities: Map<String, R_Entity>, metaEntities: Map<String, MetaEntity>) {
            val metaEntityNames = metaEntities.filter { (_, c) -> c.type == MetaEntityType.ENTITY }.keys
            val missingEntities = Sets.difference(extEntities.keys, metaEntityNames)
            if (!missingEntities.isEmpty()) {
                val list = missingEntities.sorted()
                throw errInit("external_meta_no_entity:$chain:${list.joinToString(",")}",
                        "Entities not found in external chain '$chain': ${list.joinToString()}")
            }
        }

        private fun checkMissingAttrs(chain: String, extEntity: R_Entity, metaEntity: MetaEntity) {
            val metaAttrNames = metaEntity.attrs.keys
            val extAttrNames = extEntity.attributes.keys
            val missingAttrs = Sets.difference(extAttrNames, metaAttrNames)
            if (!missingAttrs.isEmpty()) {
                val entityName = extEntity.appLevelName
                val list = missingAttrs.sorted()
                throw errInit("external_meta_noattrs:$chain:[$entityName]:${list.joinToString(",")}",
                        "Missing attributes of entity '$entityName' in external chain '$chain': ${list.joinToString()}")
            }
        }

        private fun checkAttrTypes(sqlCtx: Rt_SqlContext, chain: String, extEntity: R_Entity, metaEntity: MetaEntity) {
            for (extAttr in extEntity.attributes.values.sortedBy { it.name }) {
                val attrName = extAttr.name
                val metaAttr = metaEntity.attrs.getValue(attrName)
                val metaType = metaAttr.type
                val extType = extAttr.type.sqlAdapter.metaName(sqlCtx)
                if (metaType != extType) {
                    val entityName = extEntity.appLevelName
                    throw errInit("external_meta_attrtype:$chain:[$entityName]:$attrName:[$extType]:[$metaType]",
                            "Attribute type mismatch for '$entityName.$attrName' in external chain '$chain': " +
                                    "expected '$extType', actual '$metaType'")
                }
            }
        }

        private fun getChainExternalEntities(entities: List<R_Entity>): Map<String, Map<String, R_Entity>> {
            val res = mutableMapOf<String, MutableMap<String, R_Entity>>()
            for (entity in entities) {
                if (entity.external != null && entity.external.metaCheck) {
                    val map = res.computeIfAbsent(entity.external.chain.name) { mutableMapOf() }
                    check(entity.metaName !in map)
                    map[entity.metaName] = entity
                }
            }
            return res
        }

        private fun loadExternalMetaData(name: String, chain: Rt_ExternalChain, sqlExec: SqlExecutor): Map<String, MetaEntity> {
            val res: Map<String, MetaEntity>

            val msgs = Rt_Messages(logger)
            try {
                res = SqlMeta.loadMetaData(sqlExec, chain.sqlMapping, msgs)
                msgs.checkErrors()
            } catch (e: Rt_Error) {
                throw errInit("external_meta_error:${chain.chainId}:$name:${e.code}",
                        "Failed to load metadata for external chain '$name' (chain_iid = ${chain.chainId}): ${e.message}")
            }

            return res
        }

        private fun errInit(code: String, msg: String): RuntimeException = Rt_Error(code, msg)
    }
}

class Rt_AppContext(val globalCtx: Rt_GlobalContext, val sqlCtx: Rt_SqlContext, val app: R_App) {
    private var objsInit: SqlObjectsInit? = null
    private var objsInited = false

    fun createRootFrame(defPos: R_DefinitionPos): Rt_CallFrame {
        val rFrameBlock = R_FrameBlock(null, R_FrameBlockId(0, "app"), 0, 0)
        val rFrame = R_CallFrame(0, rFrameBlock)
        val defCtx = Rt_DefinitionContext(this, true, defPos)
        return Rt_CallFrame(null, null, defCtx, rFrame)
    }

    fun objectsInitialization(objsInit: SqlObjectsInit, code: () -> Unit) {
        check(this.objsInit == null)
        check(!objsInited)
        objsInited = true
        this.objsInit = objsInit
        try {
            code()
        } finally {
            this.objsInit = null
        }
    }

    fun forceObjectInit(obj: R_Object): Boolean {
        val ref = objsInit
        return if (ref == null) false else {
            ref.forceObject(obj)
            true
        }
    }
}

class Rt_CallContext(val defCtx: Rt_DefinitionContext) {
    val appCtx = defCtx.appCtx
    val globalCtx = appCtx.globalCtx
    val chainCtx = globalCtx.chainCtx
}

class Rt_DefinitionContext(val appCtx: Rt_AppContext, val dbUpdateAllowed: Boolean, val pos: R_DefinitionPos) {
    val globalCtx = appCtx.globalCtx
    val sqlCtx = appCtx.sqlCtx
    val callCtx = Rt_CallContext(this)

    fun checkDbUpdateAllowed() {
        if (!dbUpdateAllowed) {
            throw Rt_Error("no_db_update", "Database modifications are not allowed in this context")
        }
    }
}

class Rt_OpContext(val lastBlockTime: Long, val transactionIid: Long, val blockHeight: Long, val signers: List<ByteArray>)

class Rt_ChainContext(val rawConfig: Gtv, args: Map<R_ModuleName, Rt_Value>, val blockchainRid: ByteArray) {
    val args = args.toImmMap()
}
