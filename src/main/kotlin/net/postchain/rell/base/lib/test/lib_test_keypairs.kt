/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib.test

import net.postchain.common.hexStringToByteArray
import net.postchain.crypto.secp256k1_derivePubKey
import net.postchain.rell.base.compiler.base.core.C_DefinitionPath
import net.postchain.rell.base.compiler.base.def.C_SysAttribute
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceProperty_RtValue
import net.postchain.rell.base.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.base.compiler.base.utils.C_LibUtils
import net.postchain.rell.base.compiler.base.utils.C_Utils
import net.postchain.rell.base.compiler.base.utils.toCodeMsg
import net.postchain.rell.base.model.R_ByteArrayType
import net.postchain.rell.base.runtime.Rt_ByteArrayValue
import net.postchain.rell.base.runtime.Rt_Exception
import net.postchain.rell.base.runtime.Rt_StructValue
import net.postchain.rell.base.runtime.Rt_Value
import net.postchain.rell.base.runtime.utils.Rt_Utils
import net.postchain.rell.base.utils.BytesKeyPair
import net.postchain.rell.base.utils.ide.IdeSymbolInfo
import net.postchain.rell.base.utils.toImmMap

object C_Lib_Test_KeyPairs {
    private val KEYPAIR_STRUCT = C_Utils.createSysStruct(
            "${C_Lib_Test.MODULE}.keypair",
            C_SysAttribute("pub", R_ByteArrayType),
            C_SysAttribute("priv", R_ByteArrayType)
    )

    val KEYPAIR_TYPE = KEYPAIR_STRUCT.type

    private val PREDEFINED_KEYPAIRS = createPredefinedKeyPairs()

    private val DEF_PATH = C_DefinitionPath(C_Lib_Test.MODULE, C_Lib_Test.MODULE_NAME.parts.map { it.str })

    private val KEYPAIRS_NAMESPACE = C_LibUtils.makeNsValues(
            DEF_PATH,
            PREDEFINED_KEYPAIRS.mapValues { e -> keyPairToStruct(e.value) }
    )

    private val PUBKEYS_NAMESPACE = C_LibUtils.makeNsValues(
            DEF_PATH,
            PREDEFINED_KEYPAIRS.mapValues { Rt_ByteArrayValue(it.value.pub.toByteArray()) }
    )

    private val PRIVKEYS_NAMESPACE = C_LibUtils.makeNsValues(
            DEF_PATH,
            PREDEFINED_KEYPAIRS.mapValues { Rt_ByteArrayValue(it.value.priv.toByteArray()) }
    )

    fun bind(b: C_SysNsProtoBuilder) {
        b.addNamespace("keypairs", KEYPAIRS_NAMESPACE)
        b.addNamespace("privkeys", PRIVKEYS_NAMESPACE)
        b.addNamespace("pubkeys", PUBKEYS_NAMESPACE)
        b.addStruct("keypair", KEYPAIR_STRUCT, IdeSymbolInfo.DEF_STRUCT)

        val keyPairValue = keyPairToStruct(C_Lib_Test.BLOCK_RUNNER_KEYPAIR)
        b.addProperty("BLOCKCHAIN_SIGNER_KEYPAIR", C_NamespaceProperty_RtValue(IdeSymbolInfo.DEF_CONSTANT, keyPairValue))
    }

    private fun createPredefinedKeyPairs(): Map<String, BytesKeyPair> {
        // Names are taken from https://en.wikipedia.org/wiki/Alice_and_Bob
        val names = listOf("bob", "alice", "trudy", "charlie", "dave", "eve", "frank", "grace", "heidi")
        return names.mapIndexed { i, name ->
            val privKeyBytes = (i + 1).toString().repeat(64).hexStringToByteArray()
            val pubKeyBytes = secp256k1_derivePubKey(privKeyBytes)
            name to BytesKeyPair(privKeyBytes, pubKeyBytes)
        }.toMap().toImmMap()
    }

    fun structToKeyPair(v: Rt_Value): BytesKeyPair {
        val v2 = v.asStruct()
        val actualType = v2.type()
        if (actualType != KEYPAIR_TYPE) {
            throw Rt_Exception.common("type:struct:$KEYPAIR_TYPE:$actualType", "Wrong struct type: $actualType instead of $KEYPAIR_TYPE")
        }

        val pub = toByteArray(v2.get(0), 33)
        val priv = toByteArray(v2.get(1), 32)
        return BytesKeyPair(priv, pub)
    }

    private fun toByteArray(v: Rt_Value, n: Int): ByteArray {
        val bs = v.asByteArray()
        Rt_Utils.check(bs.size == n) { "keypair:wrong_byte_array_size:$n:${bs.size}" toCodeMsg
                "Wrong byte array size: ${bs.size} instead of $n" }
        return bs
    }

    private fun keyPairToStruct(keyPair: BytesKeyPair): Rt_Value {
        val attrs = listOf(keyPair.pub, keyPair.priv)
            .map { Rt_ByteArrayValue(it.toByteArray()) as Rt_Value }
            .toMutableList()
        return Rt_StructValue(KEYPAIR_TYPE, attrs)
    }
}
