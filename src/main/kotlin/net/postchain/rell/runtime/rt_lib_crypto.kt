/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.runtime

import net.postchain.rell.utils.EthereumSignature
import org.spongycastle.jcajce.provider.digest.Keccak
import java.math.BigInteger
import java.security.MessageDigest
import java.util.*

object Rt_CryptoUtils {
    fun keccak256(data: ByteArray): ByteArray {
        val md: MessageDigest = Keccak.Digest256()
        val res = md.digest(data)
        return res
    }

    fun ethereumPubkeyFromSignature(r: ByteArray, s: ByteArray, recId: Long, hash: ByteArray): ByteArray {
        check(recId >= 0 && recId <= 100000) { "recId out of range: $recId" }
        val rVal = BigInteger(r)
        val sVal = BigInteger(s)
        val sign = EthereumSignature(hash, recId.toInt() + 27, rVal, sVal)
        val pubKey = sign.ecrecover()
        return pubKey
    }
}
