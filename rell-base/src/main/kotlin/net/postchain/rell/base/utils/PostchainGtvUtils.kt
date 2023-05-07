/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.utils

import com.google.gson.GsonBuilder
import net.postchain.crypto.CryptoSystem
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.gtv.*
import net.postchain.gtv.gtvml.GtvMLEncoder
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import net.postchain.gtv.merkle.MerkleHashCalculator
import net.postchain.rell.base.model.R_StructDefinition
import net.postchain.rell.base.runtime.GtvToRtContext
import net.postchain.rell.base.runtime.Rt_Value

object PostchainGtvUtils {
    val cryptoSystem: CryptoSystem = Secp256K1CryptoSystem()

    private val merkleCalculator: MerkleHashCalculator<Gtv> = GtvMerkleHashCalculator(cryptoSystem)

    private val GSON = make_gtv_gson()

    private val PRETTY_GSON = GsonBuilder()
        .registerTypeAdapter(Gtv::class.java, GtvAdapter())
        .serializeNulls()
        .setPrettyPrinting()
        .create()!!

    fun gtvToBytes(v: Gtv): ByteArray = GtvEncoder.encodeGtv(v)
    fun bytesToGtv(v: ByteArray): Gtv = GtvFactory.decodeGtv(v)

    fun xmlToGtv(s: String): Gtv = GtvMLParser.parseGtvML(s)
    fun gtvToXml(v: Gtv): String = GtvMLEncoder.encodeXMLGtv(v)

    fun gtvToJson(v: Gtv): String = GSON.toJson(v, Gtv::class.java)
    fun jsonToGtv(s: String): Gtv = GSON.fromJson(s, Gtv::class.java) ?: GtvNull
    fun gtvToJsonPretty(v: Gtv): String = PRETTY_GSON.toJson(v, Gtv::class.java)

    fun merkleHash(v: Gtv): ByteArray = v.merkleHash(merkleCalculator)

    fun moduleArgsGtvToRt(struct: R_StructDefinition, gtv: Gtv): Rt_Value {
        val convCtx = GtvToRtContext.make(true)
        return struct.type.gtvToRt(convCtx, gtv)
    }
}
