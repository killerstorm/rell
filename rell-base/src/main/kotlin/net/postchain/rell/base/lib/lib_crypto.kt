/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib

import net.postchain.crypto.CURVE_PARAMS
import net.postchain.crypto.Signature
import net.postchain.rell.base.compiler.base.lib.C_SysFunction
import net.postchain.rell.base.compiler.base.utils.toCodeMsg
import net.postchain.rell.base.lmodel.L_ParamArity
import net.postchain.rell.base.lmodel.dsl.Ld_NamespaceDsl
import net.postchain.rell.base.model.R_ByteArrayType
import net.postchain.rell.base.model.R_IntegerType
import net.postchain.rell.base.model.R_TupleType
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.runtime.utils.Rt_Utils
import net.postchain.rell.base.utils.PostchainGtvUtils
import net.postchain.rell.base.utils.checkEquals
import net.postchain.rell.base.utils.etherjar.PrivateKey
import net.postchain.rell.base.utils.etherjar.Signer
import net.postchain.rell.base.utils.immListOf
import org.bouncycastle.jcajce.provider.digest.Keccak
import java.math.BigInteger
import java.security.MessageDigest

object Lib_Crypto {
    val Sha256 = C_SysFunction.simple(pure = true) { a ->
        val ba = a.asByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        Rt_ByteArrayValue(md.digest(ba))
    }

    val NAMESPACE = Ld_NamespaceDsl.make {
        link(target = "crypto.sha256")
        link(target = "crypto.keccak256")
        link(target = "crypto.verify_signature")
        link(target = "crypto.eth_ecrecover")

        namespace("crypto") {
            function("sha256", "byte_array") {
                param("byte_array")
                bodyFunction(Sha256)
            }

            function("keccak256", "byte_array", pure = true) {
                param("byte_array")
                body { a ->
                    val data = a.asByteArray()
                    val md: MessageDigest = Keccak.Digest256()
                    val res = md.digest(data)
                    Rt_ByteArrayValue(res)
                }
            }

            function("verify_signature", "boolean", pure = true) {
                param("byte_array")
                param("byte_array")
                param("byte_array")

                body { a, b, c ->
                    val digest = a.asByteArray()
                    val res = try {
                        val signature = Signature(b.asByteArray(), c.asByteArray())
                        PostchainGtvUtils.cryptoSystem.verifyDigest(digest, signature)
                    } catch (e: Exception) {
                        throw Rt_Exception.common("verify_signature", e.message ?: "Signature verification crashed")
                    }
                    Rt_BooleanValue(res)
                }
            }

            function("eth_ecrecover", "byte_array", pure = true) {
                param("byte_array")
                param("byte_array")
                param("integer")
                param("byte_array")

                body { a, b, c, d ->
                    val r = a.asByteArray()
                    val s = b.asByteArray()
                    val recId = c.asInteger()
                    val hash = d.asByteArray()

                    check(recId in 0..100000) { "recId out of range: $recId" }
                    val rVal = BigInteger(1, r)
                    val sVal = BigInteger(1, s)
                    val v = recId.toInt() + 27
                    val signature = net.postchain.rell.base.utils.etherjar.Signature(hash, v, rVal, sVal)
                    val res = Signer.ecrecover(signature)

                    Rt_ByteArrayValue(res)
                }
            }

            val signatureType = R_TupleType.create(R_ByteArrayType, R_ByteArrayType, R_IntegerType)
            val signatureTypeStr = "(byte_array,byte_array,integer)"

            function("eth_sign", signatureTypeStr, pure = true) {
                param("byte_array")
                param("byte_array")

                body { a, b ->
                    val hash = a.asByteArray()
                    val privKey = b.asByteArray()
                    checkPrivKeySize(privKey, "eth_sign")

                    val signer = Signer(null)
                    val privKeyObj = PrivateKey.create(privKey)
                    val sign = signer.create(hash, privKeyObj, net.postchain.rell.base.utils.etherjar.Signature::class.java)

                    val r = bigIntToRS(sign.r)
                    val s = bigIntToRS(sign.s)
                    val recId = sign.recId

                    checkEquals(r.size, 32)
                    checkEquals(s.size, 32)

                    val elems = immListOf(Rt_ByteArrayValue(r), Rt_ByteArrayValue(s), Rt_IntValue(recId.toLong()))
                    Rt_TupleValue(signatureType, elems)
                }
            }

            function("privkey_to_pubkey", "byte_array", pure = true) {
                param("byte_array")
                param("boolean", arity = L_ParamArity.ZERO_ONE)

                bodyOpt1 { arg1, arg2 ->
                    val privKey = arg1.asByteArray()
                    checkPrivKeySize(privKey, "privkey_to_pubkey")

                    val compress = arg2?.asBoolean() ?: false

                    val d = BigInteger(1, privKey)
                    val q = CURVE_PARAMS.g.multiply(d)
                    val pubKey = q.getEncoded(compress)

                    checkEquals(pubKey.size, if (compress) 33 else 65)
                    Rt_ByteArrayValue(pubKey)
                }
            }
        }
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

    private fun checkPrivKeySize(privKey: ByteArray, fn: String) {
        val expPrivKeySize = 32
        Rt_Utils.check(privKey.size == expPrivKeySize) {
            "fn:$fn:privkey_size:${privKey.size}" toCodeMsg "Wrong size of private key: ${privKey.size} instead of $expPrivKeySize"
        }
    }
}
