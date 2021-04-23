package net.postchain.rell.lib.test

import net.postchain.base.secp256k1_derivePubKey
import net.postchain.common.hexStringToByteArray
import net.postchain.rell.compiler.*
import net.postchain.rell.lib.C_Lib_Rell_Test
import net.postchain.rell.model.R_ByteArrayType
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.BytesKeyPair
import net.postchain.rell.utils.toImmMap

object C_Lib_Rell_Test_KeyPairs {
    private val KEYPAIR_STRUCT = C_Utils.createSysStruct(
            "${C_Lib_Rell_Test.MODULE}.keypair",
            C_SysAttribute("pub", R_ByteArrayType),
            C_SysAttribute("priv", R_ByteArrayType)
    )

    val KEYPAIR_TYPE = KEYPAIR_STRUCT.type

    fun structToKeyPair(v: Rt_Value): BytesKeyPair {
        val v2 = v.asStruct()
        val actualType = v2.type()
        if (actualType != KEYPAIR_TYPE) {
            throw Rt_Error("type:struct:$KEYPAIR_TYPE:$actualType", "Wrong struct type: $actualType instead of $KEYPAIR_TYPE")
        }

        val pub = toByteArray(v2.get(0), 33)
        val priv = toByteArray(v2.get(1), 32)
        return BytesKeyPair(priv, pub)
    }

    private fun toByteArray(v: Rt_Value, n: Int): ByteArray {
        val bs = v.asByteArray()
        Rt_Utils.check(bs.size == n) { "keypair:wrong_byte_array_size:$n:${bs.size}" to
                "Wrong byte array size: ${bs.size} instead of $n" }
        return bs
    }

    private val PREDEFINED_KEYPAIRS = createPredefinedKeyPairs()

    private val KEYPAIRS_NAMESPACE = C_LibUtils.makeNsValues(
            PREDEFINED_KEYPAIRS.mapValues {
                val attrs = listOf(it.value.pub, it.value.priv)
                        .map { Rt_ByteArrayValue(it.toByteArray()) as Rt_Value }
                        .toMutableList()
                Rt_StructValue(KEYPAIR_TYPE, attrs)
            }
    )

    private val PUBKEYS_NAMESPACE = C_LibUtils.makeNsValues(
            PREDEFINED_KEYPAIRS.mapValues { Rt_ByteArrayValue(it.value.pub.toByteArray()) }
    )

    private val PRIVKEYS_NAMESPACE = C_LibUtils.makeNsValues(
            PREDEFINED_KEYPAIRS.mapValues { Rt_ByteArrayValue(it.value.priv.toByteArray()) }
    )

    val NAMESPACE = createNamespace()

    private fun createNamespace(): C_Namespace {
        val b = C_SysNsProtoBuilder()
        b.addNamespace("keypairs", KEYPAIRS_NAMESPACE)
        b.addNamespace("privkeys", PRIVKEYS_NAMESPACE)
        b.addNamespace("pubkeys", PUBKEYS_NAMESPACE)
        b.addStruct("keypair", KEYPAIR_STRUCT)
        val nsProto = b.build()
        return C_NsEntry.createNamespace(nsProto.entries)
    }

    private fun createPredefinedKeyPairs(): Map<String, BytesKeyPair> {
        val names = listOf("bob", "alice", "trudy")
        return names.mapIndexed { i, name ->
            val privKeyBytes = (i + 1).toString().repeat(64).hexStringToByteArray()
            val pubKeyBytes = secp256k1_derivePubKey(privKeyBytes)
            name to BytesKeyPair(privKeyBytes, pubKeyBytes)
        }.toMap().toImmMap()
    }
}