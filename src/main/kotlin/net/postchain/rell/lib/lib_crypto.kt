package net.postchain.rell.lib

import net.postchain.base.CURVE_PARAMS
import net.postchain.core.Signature
import net.postchain.rell.compiler.C_GlobalFuncBuilder
import net.postchain.rell.compiler.C_LibUtils
import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
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
    const val NAMESPACE_NAME = "crypto"

    val GLOBAL_FUNCTIONS = C_GlobalFuncBuilder(null, pure = true)
            .add("sha256", R_ByteArrayType, listOf(R_ByteArrayType), Sha256)
            .add("keccak256", R_ByteArrayType, listOf(R_ByteArrayType), Keccak256)
            .add("verify_signature", R_BooleanType, listOf(R_ByteArrayType, R_ByteArrayType, R_ByteArrayType), VerifySignature)
            .add("eth_ecrecover", R_ByteArrayType, listOf(R_ByteArrayType, R_ByteArrayType, R_IntegerType, R_ByteArrayType), EthEcRecover)
            .build()

    private val NAMESPACE_FNS = C_GlobalFuncBuilder(NAMESPACE_NAME)
            .add("sha256", R_ByteArrayType, listOf(R_ByteArrayType), Sha256)
            .add("keccak256", R_ByteArrayType, listOf(R_ByteArrayType), Keccak256)
            .add("verify_signature", R_BooleanType, listOf(R_ByteArrayType, R_ByteArrayType, R_ByteArrayType), VerifySignature)
            .add("eth_ecrecover", R_ByteArrayType, listOf(R_ByteArrayType, R_ByteArrayType, R_IntegerType, R_ByteArrayType), EthEcRecover)
            .add("eth_sign", SIGNATURE_TYPE, listOf(R_ByteArrayType, R_ByteArrayType), EthSign)
            .add("privkey_to_pubkey", R_ByteArrayType, listOf(R_ByteArrayType), PrivKeyToPubKey)
            .add("privkey_to_pubkey", R_ByteArrayType, listOf(R_ByteArrayType, R_BooleanType), PrivKeyToPubKey)
            .build()

    val NAMESPACE = C_LibUtils.makeNs(
            NAMESPACE_FNS
    )

    val FN_SHA256: R_SysFunction = Sha256
}

private object Sha256: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val ba = arg.asByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        return Rt_ByteArrayValue(md.digest(ba))
    }
}

private object Keccak256: R_SysFunction_1() {
    override fun call(arg: Rt_Value): Rt_Value {
        val data = arg.asByteArray()
        val md: MessageDigest = Keccak.Digest256()
        val res = md.digest(data)
        return Rt_ByteArrayValue(res)
    }
}

private object VerifySignature: R_SysFunction_3() {
    override fun call(arg1: Rt_Value, arg2: Rt_Value, arg3: Rt_Value): Rt_Value {
        val digest = arg1.asByteArray()
        val res = try {
            val signature = Signature(arg2.asByteArray(), arg3.asByteArray())
            PostchainUtils.cryptoSystem.verifyDigest(digest, signature)
        } catch (e: Exception) {
            throw Rt_Error("verify_signature", e.message ?: "")
        }
        return Rt_BooleanValue(res)
    }
}

private object EthEcRecover: R_SysFunction_4() {
    override fun call(arg1: Rt_Value, arg2: Rt_Value, arg3: Rt_Value, arg4: Rt_Value): Rt_Value {
        val r = arg1.asByteArray()
        val s = arg2.asByteArray()
        val recId = arg3.asInteger()
        val hash = arg4.asByteArray()

        check(recId in 0..100000) { "recId out of range: $recId" }
        val rVal = BigInteger(1, r)
        val sVal = BigInteger(1, s)
        val v = recId.toInt() + 27
        val signature = net.postchain.rell.utils.etherjar.Signature(hash, v, rVal, sVal)
        val res = Signer.ecrecover(signature)

        return Rt_ByteArrayValue(res)
    }
}

private object EthSign: R_SysFunction_2() {
    override fun call(arg1: Rt_Value, arg2: Rt_Value): Rt_Value {
        val hash = arg1.asByteArray()
        val privKey = arg2.asByteArray()
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
        return Rt_TupleValue(SIGNATURE_TYPE, elems)
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
}

private object PrivKeyToPubKey: R_SysFunction() {
    override fun call(ctx: Rt_CallContext, args: List<Rt_Value>): Rt_Value {
        check(args.size == 1 || args.size == 2) { args.size }

        val privKey = args[0].asByteArray()
        checkPrivKeySize(privKey, "privkey_to_pubkey")

        val compress = if (args.size < 2) false else args[1].asBoolean()

        val d = BigInteger(1, privKey)
        val q = CURVE_PARAMS.g.multiply(d)
        val pubKey = q.getEncoded(compress)

        checkEquals(pubKey.size, if (compress) 33 else 65)
        return Rt_ByteArrayValue(pubKey)
    }
}

private fun checkPrivKeySize(privKey: ByteArray, fn: String) {
    val expPrivKeySize = 32
    Rt_Utils.check(privKey.size == expPrivKeySize) {
        "fn:$fn:privkey_size:${privKey.size}" to "Wrong size of private key: ${privKey.size} instead of $expPrivKeySize"
    }
}
