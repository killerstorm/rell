/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.utils

import com.google.gson.GsonBuilder
import net.postchain.base.data.PostgreSQLDatabaseAccess
import net.postchain.base.data.SQLDatabaseAccess
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.UserMistake
import net.postchain.crypto.CryptoSystem
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.gtv.*
import net.postchain.gtv.gtvml.GtvMLEncoder
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import net.postchain.gtv.merkle.MerkleHashCalculator
import net.postchain.gtx.StandardOpsGTXModule
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.model.R_MountName
import net.postchain.rell.module.GtvToRtContext
import net.postchain.rell.runtime.Rt_ChainContext
import net.postchain.rell.runtime.Rt_Value

object PostchainUtils {
    val DATABASE_VERSION = 2

    val cryptoSystem: CryptoSystem = Secp256K1CryptoSystem()

    private val merkleCalculator: MerkleHashCalculator<Gtv> = GtvMerkleHashCalculator(cryptoSystem)

    private val GSON = make_gtv_gson()

    private val PRETTY_GSON = GsonBuilder()
            .registerTypeAdapter(Gtv::class.java, GtvAdapter())
            .serializeNulls()
            .setPrettyPrinting()
            .create()!!

    val STD_OPS: Set<R_MountName>
    val STD_QUERIES: Set<R_MountName>

    init {
        val m = StandardOpsGTXModule()
        STD_OPS = m.getOperations().map { R_MountName.of(it) }.toImmSet()
        STD_QUERIES = m.getQueries().map { R_MountName.of(it) }.toImmSet()
    }

    fun gtvToBytes(v: Gtv): ByteArray = GtvEncoder.encodeGtv(v)
    fun bytesToGtv(v: ByteArray): Gtv = GtvFactory.decodeGtv(v)

    fun xmlToGtv(s: String): Gtv = GtvMLParser.parseGtvML(s)
    fun gtvToXml(v: Gtv): String = GtvMLEncoder.encodeXMLGtv(v)

    fun gtvToJson(v: Gtv): String = GSON.toJson(v, Gtv::class.java)
    fun jsonToGtv(s: String): Gtv = GSON.fromJson<Gtv>(s, Gtv::class.java) ?: GtvNull
    fun gtvToJsonPretty(v: Gtv): String = PRETTY_GSON.toJson(v, Gtv::class.java)

    fun merkleHash(v: Gtv): ByteArray = v.merkleHash(merkleCalculator)

    fun hexToRid(s: String): BlockchainRid = BlockchainRid(CommonUtils.hexToBytes(s))

    fun createDatabaseAccess(): SQLDatabaseAccess = PostgreSQLDatabaseAccess()

    fun calcBlockchainRid(config: Gtv): Bytes32 {
        val hash = merkleHash(config)
        return Bytes32(hash)
    }

    fun createChainContext(rawConfig: Gtv, rApp: R_App, blockchainRid: BlockchainRid): Rt_ChainContext {
        val gtxNode = rawConfig.asDict().getValue("gtx")
        val rellNode = gtxNode.asDict().getValue("rell")
        val gtvArgsDict = rellNode.asDict()["moduleArgs"]?.asDict() ?: mapOf()

        val moduleArgs = mutableMapOf<R_ModuleName, Rt_Value>()

        for (rModule in rApp.modules) {
            val argsStruct = rModule.moduleArgs

            if (argsStruct != null) {
                val gtvArgs = gtvArgsDict[rModule.name.str()]
                if (gtvArgs == null) {
                    throw UserMistake("No moduleArgs in blockchain configuration for module '${rModule.name}', " +
                            "but type ${argsStruct.moduleLevelName} defined in the code")
                }

                val convCtx = GtvToRtContext(true)
                val rtArgs = argsStruct.type.gtvToRt(convCtx, gtvArgs)
                moduleArgs[rModule.name] = rtArgs
            }
        }

        return Rt_ChainContext(rawConfig, moduleArgs, blockchainRid)
    }
}
