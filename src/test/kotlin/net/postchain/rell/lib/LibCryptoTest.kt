package net.postchain.rell.lib

import net.postchain.base.secp256k1_derivePubKey
import net.postchain.base.secp256k1_sign
import net.postchain.rell.hexStringToByteArray
import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.toHex
import org.junit.Test

class LibCryptoTest: BaseRellTest(false) {
    @Test fun testVerifySignature() {
        val privKeyBytes = ByteArray(32) { it.toByte() }
        val pubKey = secp256k1_derivePubKey(privKeyBytes).toHex()

        val sign1 = calcSignature("DEADBEEF", privKeyBytes)
        val sign2 = calcSignature("DEADBEFF", privKeyBytes)

        chk("verify_signature(x'DEADBEEF', x'$pubKey', x'$sign1')", "boolean[true]")
        chk("verify_signature(x'DEADBEEF', x'$pubKey', x'$sign2')", "boolean[false]")
        chk("verify_signature(x'DEADBEFF', x'$pubKey', x'$sign1')", "boolean[false]")
        chk("verify_signature(x'DEADBEFF', x'$pubKey', x'$sign2')", "boolean[true]")
    }

    @Test fun testVerifySignatureErr() {
        chk("verify_signature(x'0123', x'4567', x'89AB')", "rt_err:verify_signature")
    }

    private fun calcSignature(messageHex: String, privKeyBytes: ByteArray): String {
        return secp256k1_sign(messageHex.hexStringToByteArray(), privKeyBytes).toHex()
    }
}
