/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.crypto.CURVE_PARAMS
import net.postchain.crypto.Signature
import net.postchain.rell.compiler.base.core.C_DefinitionName
import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_GlobalFuncBuilder
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_SysFunction
import net.postchain.rell.compiler.base.utils.toCodeMsg
import net.postchain.rell.model.R_BooleanType
import net.postchain.rell.model.R_ByteArrayType
import net.postchain.rell.model.R_IntegerType
import net.postchain.rell.model.R_TupleType
import net.postchain.rell.runtime.*
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.utils.PostchainUtils
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.etherjar.PrivateKey
import net.postchain.rell.utils.etherjar.Signer
import net.postchain.rell.utils.immListOf
import org.spongycastle.jcajce.provider.digest.Keccak
import java.math.BigInteger
import java.security.MessageDigest

private val SIGNATURE_TYPE = R_TupleType.create(R_ByteArrayType, R_ByteArrayType, R_IntegerType)

object C_Lib_Crypto {
    private const val NAMESPACE_NAME = "crypto"

    private val GLOBAL_FUNCTIONS = C_GlobalFuncBuilder()
            .add("sha256", R_ByteArrayType, listOf(R_ByteArrayType), CryptoFns.Sha256)
            .add("keccak256", R_ByteArrayType, listOf(R_ByteArrayType), CryptoFns.Keccak256)
            .add("verify_signature", R_BooleanType, listOf(R_ByteArrayType, R_ByteArrayType, R_ByteArrayType), CryptoFns.VerifySignature)
            .add("eth_ecrecover", R_ByteArrayType, listOf(R_ByteArrayType, R_ByteArrayType, R_IntegerType, R_ByteArrayType), CryptoFns.EthEcRecover)
            .build()

    private val NAMESPACE_FNS = C_GlobalFuncBuilder(C_DefinitionName(C_LibUtils.DEFAULT_MODULE_STR, NAMESPACE_NAME).toPath())
            .add("sha256", R_ByteArrayType, listOf(R_ByteArrayType), CryptoFns.Sha256)
            .add("keccak256", R_ByteArrayType, listOf(R_ByteArrayType), CryptoFns.Keccak256)
            .add("verify_signature", R_BooleanType, listOf(R_ByteArrayType, R_ByteArrayType, R_ByteArrayType), CryptoFns.VerifySignature)
            .add("eth_ecrecover", R_ByteArrayType, listOf(R_ByteArrayType, R_ByteArrayType, R_IntegerType, R_ByteArrayType), CryptoFns.EthEcRecover)
            .add("eth_sign", SIGNATURE_TYPE, listOf(R_ByteArrayType, R_ByteArrayType), CryptoFns.EthSign)
            .add("privkey_to_pubkey", R_ByteArrayType, listOf(R_ByteArrayType), CryptoFns.PrivKeyToPubKey)
            .add("privkey_to_pubkey", R_ByteArrayType, listOf(R_ByteArrayType, R_BooleanType), CryptoFns.PrivKeyToPubKey)
            .build()

    val Sha256 = CryptoFns.Sha256

    fun bind(nsBuilder: C_SysNsProtoBuilder) {
        C_LibUtils.bindFunctions(nsBuilder, GLOBAL_FUNCTIONS)

        val b = C_SysNsProtoBuilder(nsBuilder.basePath.subPath(NAMESPACE_NAME))
        C_LibUtils.bindFunctions(b, NAMESPACE_FNS)
        nsBuilder.addNamespace(NAMESPACE_NAME, b.build().toNamespace())
    }
}

private object CryptoFns {
    val Sha256 = C_SysFunction.simple1(pure = true) { a ->
        val ba = a.asByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        Rt_ByteArrayValue(md.digest(ba))
    }

    val Keccak256 = C_SysFunction.simple1(pure = true) { a ->
        val data = a.asByteArray()
        val md: MessageDigest = Keccak.Digest256()
        val res = md.digest(data)
        Rt_ByteArrayValue(res)
    }

    val VerifySignature = C_SysFunction.simple3(pure = true) { a, b, c ->
        val digest = a.asByteArray()
        val res = try {
            val signature = Signature(b.asByteArray(), c.asByteArray())
            PostchainUtils.cryptoSystem.verifyDigest(digest, signature)
        } catch (e: Exception) {
            throw Rt_Error("verify_signature", e.message ?: "")
        }
        Rt_BooleanValue(res)
    }

    val EthEcRecover = C_SysFunction.simple4(pure = true) { a, b, c, d ->
        val r = a.asByteArray()
        val s = b.asByteArray()
        val recId = c.asInteger()
        val hash = d.asByteArray()

        check(recId in 0..100000) { "recId out of range: $recId" }
        val rVal = BigInteger(1, r)
        val sVal = BigInteger(1, s)
        val v = recId.toInt() + 27
        val signature = net.postchain.rell.utils.etherjar.Signature(hash, v, rVal, sVal)
        val res = Signer.ecrecover(signature)

        Rt_ByteArrayValue(res)
    }

    val EthSign = C_SysFunction.simple2(pure = true) { a, b ->
        val hash = a.asByteArray()
        val privKey = b.asByteArray()
        checkPrivKeySize(privKey, "eth_sign")

        val signer = Signer(null)
        val privKeyObj = PrivateKey.create(privKey)
        val sign = signer.create(hash, privKeyObj, net.postchain.rell.utils.etherjar.Signature::class.java)

        val r = bigIntToRS(sign.r)
        val s = bigIntToRS(sign.s)
        val recId = sign.recId

        checkEquals(r.size, 32)
        checkEquals(s.size, 32)

        val elems = immListOf(Rt_ByteArrayValue(r), Rt_ByteArrayValue(s), Rt_IntValue(recId.toLong()))
        Rt_TupleValue(SIGNATURE_TYPE, elems)
    }

    private fun bigIntToRS(i: BigInteger): ByteArray {
        val res = i.toByteArray()
        return if (res.size < 32) {
            // Less than 32 bytes -> add zero bytes to the left.
            ByteArray(32 - res.size) { 0 } + res
        } else if (res.size == 33 && res[0] == (0).toByte()) {
            // BigInteger.toByteArray() adds a leading zero byte for negative values, we must remove it.
            res.copyOfRange(1, res.size)
        } else {
            checkEquals(res.size, 32)
            res
        }
    }

    val PrivKeyToPubKey = C_SysFunction.simple(pure = true) { args ->
        check(args.size == 1 || args.size == 2) { args.size }

        val privKey = args[0].asByteArray()
        checkPrivKeySize(privKey, "privkey_to_pubkey")

        val compress = if (args.size < 2) false else args[1].asBoolean()

        val d = BigInteger(1, privKey)
        val q = CURVE_PARAMS.g.multiply(d)
        val pubKey = q.getEncoded(compress)

        checkEquals(pubKey.size, if (compress) 33 else 65)
        Rt_ByteArrayValue(pubKey)
    }

    private fun checkPrivKeySize(privKey: ByteArray, fn: String) {
        val expPrivKeySize = 32
        Rt_Utils.check(privKey.size == expPrivKeySize) {
            "fn:$fn:privkey_size:${privKey.size}" toCodeMsg "Wrong size of private key: ${privKey.size} instead of $expPrivKeySize"
        }
    }
}
