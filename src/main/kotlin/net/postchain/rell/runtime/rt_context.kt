/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.runtime

import com.google.common.collect.Sets
import mu.KLogging
import net.postchain.common.BlockchainRid
import net.postchain.common.types.WrappedByteArray
import net.postchain.core.TxEContext
import net.postchain.gtv.Gtv
import net.postchain.gtx.data.OpData
import net.postchain.rell.compiler.base.core.C_CompilerOptions
import net.postchain.rell.model.*
import net.postchain.rell.module.RellPostchainModuleEnvironment
import net.postchain.rell.repl.ReplOutputChannel
import net.postchain.rell.runtime.utils.Rt_Messages
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.sql.*
import net.postchain.rell.utils.*

class Rt_GlobalContext(
        val compilerOptions: C_CompilerOptions,
        val outPrinter: Rt_Printer,
        val logPrinter: Rt_Printer,
        val pcModuleEnv: RellPostchainModuleEnvironment,
        val logSqlErrors: Boolean = false,
        val sqlUpdatePortionSize: Int = 1000, // Experimental maximum is 2^15
        val typeCheck: Boolean = false
) {
    private val rellVersion = Rt_RellVersion.getInstance()

    fun rellVersion(): Rt_RellVersion {
        return rellVersion ?: throw Rt_Error("fn:rell.git_info:no_data", "Version information not found")
    }
}

abstract class Rt_SqlContext(app: R_App) {
    val appDefs = app.sqlDefs

    abstract fun mainChainMapping(): Rt_ChainSqlMapping
    abstract fun linkedChain(chain: R_ExternalChainRef): Rt_ExternalChain
    abstract fun chainMapping(externalChain: R_ExternalChainRef?): Rt_ChainSqlMapping
}

class Rt_NullSqlContext private constructor(app: R_App): Rt_SqlContext(app) {
    override fun mainChainMapping() = throw UnsupportedOperationException()
    override fun linkedChain(chain: R_ExternalChainRef) = throw UnsupportedOperationException()
    override fun chainMapping(externalChain: R_ExternalChainRef?) = throw UnsupportedOperationException()

    companion object {
        fun create(app: R_App): Rt_SqlContext = Rt_NullSqlContext(app)
    }
}

class Rt_RegularSqlContext private constructor(
        app: R_App,
        private val mainChainMapping: Rt_ChainSqlMapping,
        private val linkedExternalChains: List<Rt_ExternalChain>
): Rt_SqlContext(app) {
    private val externalChainsRoot = app.externalChainsRoot

    override fun mainChainMapping() = mainChainMapping

    override fun linkedChain(chain: R_ExternalChainRef): Rt_ExternalChain {
        check(chain.root === externalChainsRoot)
        return linkedExternalChains[chain.index]
    }

    override fun chainMapping(externalChain: R_ExternalChainRef?): Rt_ChainSqlMapping {
        return if (externalChain == null) mainChainMapping else linkedChain(externalChain).sqlMapping
    }

    companion object : KLogging() {
        fun createNoExternalChains(app: R_App, mainChainMapping: Rt_ChainSqlMapping): Rt_SqlContext {
            require(app.valid)
            require(app.externalChains.isEmpty()) { "App uses external chains" }
            return Rt_RegularSqlContext(app, mainChainMapping, listOf())
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
            val sqlCtx = Rt_RegularSqlContext(app, mainChainMapping, linkedExternalChains)
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

                val ridKey = WrappedByteArray(dep.rid)
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
            val chainExternalEntities = getChainExternalEntities(sqlCtx.appDefs.entities)

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

        private fun checkMissingEntities(
                chain: String,
                extEntities: Map<String, R_EntityDefinition>,
                metaEntities: Map<String, MetaEntity>
        ) {
            val metaEntityNames = metaEntities.filter { (_, c) -> c.type == MetaEntityType.ENTITY }.keys
            val missingEntities = Sets.difference(extEntities.keys, metaEntityNames)
            if (!missingEntities.isEmpty()) {
                val list = missingEntities.sorted()
                throw errInit("external_meta_no_entity:$chain:${list.joinToString(",")}",
                        "Entities not found in external chain '$chain': ${list.joinToString()}")
            }
        }

        private fun checkMissingAttrs(chain: String, extEntity: R_EntityDefinition, metaEntity: MetaEntity) {
            val metaAttrNames = metaEntity.attrs.keys
            val extAttrNames = extEntity.strAttributes.keys
            val missingAttrs = Sets.difference(extAttrNames, metaAttrNames)
            if (!missingAttrs.isEmpty()) {
                val entityName = extEntity.appLevelName
                val list = missingAttrs.sorted()
                throw errInit("external_meta_noattrs:$chain:[$entityName]:${list.joinToString(",")}",
                        "Missing attributes of entity '$entityName' in external chain '$chain': ${list.joinToString()}")
            }
        }

        private fun checkAttrTypes(sqlCtx: Rt_SqlContext, chain: String, extEntity: R_EntityDefinition, metaEntity: MetaEntity) {
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

        private fun getChainExternalEntities(entities: List<R_EntityDefinition>): Map<String, Map<String, R_EntityDefinition>> {
            val res = mutableMapOf<String, MutableMap<String, R_EntityDefinition>>()
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

class Rt_AppContext(
        val globalCtx: Rt_GlobalContext,
        val chainCtx: Rt_ChainContext,
        val app: R_App,
        val repl: Boolean,
        val test: Boolean,
        val replOut: ReplOutputChannel?,
        val blockRunnerStrategy: Rt_BlockRunnerStrategy,
        globalConstantStates: List<Rt_GlobalConstantState> = immListOf()
) {
    private var objsInit: SqlObjectsInit? = null
    private var objsInited = false

    private val globalConstants = Rt_GlobalConstants(this, globalConstantStates)

    init {
        globalConstants.initialize()
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

    fun forceObjectInit(obj: R_ObjectDefinition): Boolean {
        val ref = objsInit
        return if (ref == null) false else {
            ref.forceObject(obj)
            true
        }
    }

    fun getGlobalConstant(constId: R_GlobalConstantId): Rt_Value {
        return globalConstants.getValue(constId)
    }

    fun dumpGlobalConstants(): List<Rt_GlobalConstantState> {
        return globalConstants.dump()
    }
}

class Rt_GlobalConstantState(val constId: R_GlobalConstantId, val value: Rt_Value)

private class Rt_GlobalConstants(private val appCtx: Rt_AppContext, oldStates: List<Rt_GlobalConstantState>) {
    private val slots = appCtx.app.constants.map { ConstantSlot(it.constId) }.toImmList()

    private var inited = false
    private var initExeCtx: Rt_ExecutionContext? = null

    init {
        check(oldStates.size <= slots.size)
        for (i in oldStates.indices) {
            slots[i].restore(oldStates[i])
        }
    }

    fun initialize() {
        check(!inited)
        check(initExeCtx == null)
        inited = true

        val sqlCtx = Rt_NullSqlContext.create(appCtx.app)
        initExeCtx = Rt_ExecutionContext(appCtx, null, sqlCtx, NoConnSqlExecutor)

        try {
            for (c in appCtx.app.constants) {
                val slot = getSlot(c.constId)
                slot.getValue()
            }
        } finally {
            initExeCtx = null
        }
    }

    fun getValue(constId: R_GlobalConstantId): Rt_Value {
        val slot = getSlot(constId)
        return slot.getValue()
    }

    fun dump(): List<Rt_GlobalConstantState> {
        return slots.map { it.dump() }.toImmList()
    }

    private fun getSlot(constId: R_GlobalConstantId): ConstantSlot {
        val slot = slots[constId.index]
        checkEquals(slot.constId, constId)
        return slot
    }

    private inner class ConstantSlot(val constId: R_GlobalConstantId) {
        private var value: Rt_Value? = null
        private var initing = false

        fun restore(state: Rt_GlobalConstantState) {
            check(value == null)
            check(!initing)
            checkEquals(state.constId, constId)
            value = state.value
        }

        fun dump() = Rt_GlobalConstantState(constId, value!!)

        fun getValue(): Rt_Value {
            val v = value
            if (v != null) {
                return v
            }

            Rt_Utils.check(!initing) {
                "const:recursion:${constId.strCode()}" to "Constant has recursive expression: ${constId.appLevelName}"
            }
            initing = true

            val exeCtx = checkNotNull(initExeCtx) { constId }

            val c = appCtx.app.constants[constId.index]
            checkEquals(c.constId, constId)

            val v2 = c.evaluate(exeCtx)
            value = v2
            initing = false

            return v2
        }
    }
}

class Rt_ExecutionContext(
        val appCtx: Rt_AppContext,
        val opCtx: Rt_OpContext?,
        val sqlCtx: Rt_SqlContext,
        val sqlExec: SqlExecutor
) {
    val globalCtx = appCtx.globalCtx

    private var nextNopNonce = 0L

    fun nextNopNonce(): Long {
        val r = nextNopNonce
        ++nextNopNonce
        return r
    }
}

class Rt_CallContext(val defCtx: Rt_DefinitionContext) {
    val exeCtx = defCtx.exeCtx
    val appCtx = exeCtx.appCtx
    val sqlCtx = exeCtx.sqlCtx
    val globalCtx = appCtx.globalCtx
    val chainCtx = appCtx.chainCtx
}

class Rt_DefinitionContext(val exeCtx: Rt_ExecutionContext, val dbUpdateAllowed: Boolean, val defId: R_DefinitionId) {
    val appCtx = exeCtx.appCtx
    val globalCtx = appCtx.globalCtx
    val sqlCtx = exeCtx.sqlCtx
    val callCtx = Rt_CallContext(this)
}

abstract class Rt_TxContext {
    abstract fun emitEvent(type: String, data: Gtv)
}

class Rt_PostchainTxContext(private val txCtx: TxEContext): Rt_TxContext() {
    override fun emitEvent(type: String, data: Gtv) {
        txCtx.emitEvent(type, data)
    }
}

class Rt_OpContext(
        val txCtx: Rt_TxContext,
        val lastBlockTime: Long,
        val transactionIid: Long,
        val blockHeight: Long,
        val opIndex: Int,
        signers: List<ByteArray>,
        allOperations: List<OpData>
) {
    val signers = signers.toImmList()
    val allOperations = allOperations.toImmList()
}

abstract class Rt_BlockRunnerStrategy {
    abstract fun createGtvConfig(): Gtv
    abstract fun getKeyPair(): BytesKeyPair
}

class Rt_StaticBlockRunnerStrategy(
        private val gtvConfig: Gtv,
        private val keyPair: BytesKeyPair
): Rt_BlockRunnerStrategy() {
    override fun createGtvConfig() = gtvConfig
    override fun getKeyPair() = keyPair
}

object Rt_UnsupportedBlockRunnerStrategy: Rt_BlockRunnerStrategy() {
    private val errMsg = "Block execution not supported"
    override fun createGtvConfig() = throw Rt_Utils.errNotSupported(errMsg)
    override fun getKeyPair() = throw Rt_Utils.errNotSupported(errMsg)
}

class Rt_ChainContext(val rawConfig: Gtv, moduleArgs: Map<R_ModuleName, Rt_Value>, val blockchainRid: BlockchainRid) {
    val moduleArgs = moduleArgs.toImmMap()
}
