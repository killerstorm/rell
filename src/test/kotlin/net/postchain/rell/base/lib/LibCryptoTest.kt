/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib

import com.google.common.io.Resources
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.crypto.KeyPair
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.crypto.secp256k1_derivePubKey
import net.postchain.gtv.Gtv
import net.postchain.rell.base.testutils.BaseRellTest
import net.postchain.rell.base.utils.CommonUtils
import net.postchain.rell.base.utils.PostchainGtvUtils
import net.postchain.rell.base.utils.checkEquals
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest

class LibCryptoTest: BaseRellTest(false) {
    @Test fun testVerifySignature() {
        val privKeyBytes = ByteArray(32) { it.toByte() }
        val pubKey = CommonUtils.bytesToHex(secp256k1_derivePubKey(privKeyBytes))
        val keyPair = KeyPair(pubKey.hexStringToByteArray(), privKeyBytes)

        val sign1 = calcSignature("DEADBEEF", keyPair)
        val sign2 = calcSignature("DEADBEFF", keyPair)

        chk("verify_signature(x'DEADBEEF', x'$pubKey', x'$sign1')", "boolean[true]")
        chk("verify_signature(x'DEADBEEF', x'$pubKey', x'$sign2')", "boolean[false]")
        chk("verify_signature(x'DEADBEFF', x'$pubKey', x'$sign1')", "boolean[false]")
        chk("verify_signature(x'DEADBEFF', x'$pubKey', x'$sign2')", "boolean[true]")

        chk("verify_signature(x'DEADBEFF', x'$pubKey', x'${sign2.dropLast(2)}')", "boolean[false]")
        chk("verify_signature(x'DEADBEFF', x'$pubKey', x'${sign2.dropLast(4)}')", "boolean[false]")
        chk("verify_signature(x'DEADBEFF', x'$pubKey', x'${sign2.dropLast(6)}')", "boolean[false]")
        chk("verify_signature(x'DEADBEFF', x'$pubKey', x'123456')", "boolean[false]")
        chk("verify_signature(x'DEADBEFF', x'$pubKey', x'')", "boolean[false]")
    }

    @Test fun testKeccak256() {
        chk("keccak256(x'')", "byte_array[c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470]")
        chk("keccak256('1'.to_bytes())", "byte_array[c89efdaa54c0f20c7adf612882df0950f5a951637e0307cdcb4c672f298b8bc6]")
        chk("keccak256('Hello world!'.to_bytes())", "byte_array[ecd0e108a98e192af1d2c25055f4e3bed784b5c877204e73219a5203251feaab]")
    }

    @Test fun testSha256() {
        chk("sha256(x'')", "byte_array[e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855]")
        chk("sha256('1'.to_bytes())", "byte_array[6b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b]")
        chk("sha256('Hello world!'.to_bytes())", "byte_array[c0535e4be2b79ffd93291305436bf889314e4a3faec05ecffcbb7df31ad9e51a]")
    }

    @Test fun testEthEcrecover() {
        chk("""eth_ecrecover(
                x'13d6965f2a0f9306e96c65d799516d81e7a324ae9ce4f4dbe8acb8bb08bc18a5',
                x'6789b81fd6f24b8b7c3313ffc873c44659b2ba2554efd0074613e9b03fc99c0c',
                0,
                x'16ac6804d09d6c64eb2bf61db42907f7c4ee2b7c7c34998c42b7659c2fb33929'
            )""",
            "byte_array[f117f07ef53a4c90e1d6573c62ceeb5caebeb94c68ecd5d4c088f1e2395ed4438193b2b00c56fa62494f5bfb180c437ff301d8eb6993034bf0d8bc4a0a93bf0d]"
        )

        chk("""eth_ecrecover(
                x'410f3bbc0fc384de5504aeddd523adff920c559e11c6bfc1ba98b5debb9af497',
                x'6a5419f6f20d87cb4ff2589dd9a908003629fb54c0b004e5029c58abf888e200',
                0,
                x'bff7e44c2273efdbfc7a3192c5d6d3f2871c6f59b69ad2cfac71e05ba3f89ef9'
            )""",
            "byte_array[f117f07ef53a4c90e1d6573c62ceeb5caebeb94c68ecd5d4c088f1e2395ed4438193b2b00c56fa62494f5bfb180c437ff301d8eb6993034bf0d8bc4a0a93bf0d]"
        )
    }

    @Test fun testEthEcrecoverWeb3Cases() {
        val url = Resources.getResource(javaClass, "/eth_ecrecover_testcases.json")
        val text = Resources.toString(url, Charsets.UTF_8)
        val gtv = PostchainGtvUtils.jsonToGtv(text)

        fun getBytes(v: Gtv, k: String): String {
            val w = v.asDict().getValue(k)
            val s = w.asString()
            check(s.matches(Regex("0x[0-9A-Fa-f]+"))) { s }
            return s.substring(2)
        }

        for (obj in gtv.asArray()) {
            val r = getBytes(obj, "r")
            val s = getBytes(obj, "s")
            val h = getBytes(obj, "h")
            val v = getBytes(obj, "v")
            val res = obj.asDict().getValue("res").asString()
            val recId = Integer.parseInt(v, 16) - 27

            val expected = if (res == "error") {
                "rt_err:fn:error:eth_ecrecover:java.lang.IllegalArgumentException"
            } else {
                "byte_array[${res.substring(2).toLowerCase()}]"
            }

            chk("keccak256(eth_ecrecover(x'$r', x'$s', $recId, x'$h')).sub(12)", expected)
        }
    }

    @Test fun testEthSignViaEthEcrecover() {
        tst.testLib = true
        def("""
            function test(privkey: byte_array) {
                assert_equals(privkey.size(), 32);

                val pubkey = crypto.privkey_to_pubkey(privkey);
                assert_equals(pubkey.size(), 65);

                val msg = 'Hello'.to_bytes();
                val (r, s, rec_id) = crypto.eth_sign(msg, privkey);
                assert_equals(r.size(), 32);
                assert_equals(s.size(), 32);

                val recovered = crypto.eth_ecrecover(r, s, rec_id, msg);
                assert_equals(x'04' + recovered, pubkey);
            }
        """)

        for (i in 0 until 500) {
            val md = MessageDigest.getInstance("SHA-256")
            val privKey = md.digest(BigInteger.valueOf(i.toLong()).toByteArray())
            checkEquals(privKey.size, 32)

            val code = "{ test(x'${privKey.toHex()}'); return 0; }"
            chkEx(code, "int[0]")
        }
    }

    @Test fun testPrivKeyToPubKey() {
        val privKey = ByteArray(32) { it.toByte() }.toHex()

        chk("crypto.privkey_to_pubkey(x'$privKey').size()", "int[65]")
        chk("crypto.privkey_to_pubkey(x'$privKey')",
                "byte_array[046d6caac248af96f6afa7f904f550253a0f3ef3f5aa2fe6838a95b216691468e2487e6222a6664e079c8edf7518defd562dbeda1e7593dfd7f0be285880a24dab]")

        chk("crypto.privkey_to_pubkey(x'$privKey', false).size()", "int[65]")
        chk("crypto.privkey_to_pubkey(x'$privKey', false)",
                "byte_array[046d6caac248af96f6afa7f904f550253a0f3ef3f5aa2fe6838a95b216691468e2487e6222a6664e079c8edf7518defd562dbeda1e7593dfd7f0be285880a24dab]")

        chk("crypto.privkey_to_pubkey(x'$privKey', true).size()", "int[33]")
        chk("crypto.privkey_to_pubkey(x'$privKey', true)", "byte_array[036d6caac248af96f6afa7f904f550253a0f3ef3f5aa2fe6838a95b216691468e2]")
    }

    @Test fun testVerifySignatureErr() {
        chk("verify_signature(x'0123', x'4567', x'89AB')", "rt_err:verify_signature")
    }

    private fun calcSignature(messageHex: String, keyPair: KeyPair): String {
        val sigMaker = Secp256K1CryptoSystem().buildSigMaker(keyPair)
        return CommonUtils.bytesToHex(sigMaker.signDigest(CommonUtils.hexToBytes(messageHex)).data)
    }

    @Test fun testHashStruct() {
        tst.strictToString = false
        def("struct rec { i: integer; t: text; }")
        def("struct rec_nogtv { m: range; }")
        chk("rec(123,'Hello').hash()", "0x74443c7de4d4fee6f6f4d9b0aa5d4749dbfb0965b422e578802701b9ac2e063a")
        chk("rec(456,'Bye').hash()", "0x7758916e7f9f1a9e0a84351f402dbc9c906492879a9d71dc4ff1f5b7d67bdf53")
        chk("rec_nogtv(range(10)).hash()", "ct_err:fn:invalid:rec_nogtv:hash")
        chk("rec_nogtv(range(10)).hash()", "ct_err:fn:invalid:rec_nogtv:hash")
        chk("rec(123,'Hello').to_gtv().hash()", "0x74443c7de4d4fee6f6f4d9b0aa5d4749dbfb0965b422e578802701b9ac2e063a")
        chk("rec(456,'Bye').to_gtv().hash()", "0x7758916e7f9f1a9e0a84351f402dbc9c906492879a9d71dc4ff1f5b7d67bdf53")
    }

    @Test fun testHashSimple() {
        tst.strictToString = false
        def("enum E {A,B,C}")
        chk("true.hash()", "0x6ccd14b5a877874ddc7ca52bd3aeded5543b73a354779224bbb86b0fd315b418")
        chk("false.hash()", "0x90b136dfc51e08ee70ed929c620c0808d4230ec1015d46c92ccaa30772651dc0")
        chk("''.hash()", "0x36cb80657ea32c81c1985c76ec5930d5d4993093f48b313728c6746e3ea6c79f")
        chk("'Hello'.hash()", "0xfe1937fa779c7ef4cbfdab18264a12151a93b584490a0bc23e171e71790af174")
        chk("(0).hash()", "0x90b136dfc51e08ee70ed929c620c0808d4230ec1015d46c92ccaa30772651dc0")
        chk("(1).hash()", "0x6ccd14b5a877874ddc7ca52bd3aeded5543b73a354779224bbb86b0fd315b418")
        chk("(2).hash()", "0x4317338211726f61b281d62f0683fd55e355011b6e7495cf56f9e03059a3bc0a")
        chk("(123).hash()", "0x1100c41df25b87fee6921937b38c863d05445bc20d8760ad282c8c7d220e844b")
        chk("x''.hash()", "0xe91787fed131491cab96c4682e5d9a4f51e58f31d511c5d1929f12ba1bee19a1")
        chk("x'deadbeef'.hash()", "0x0b0b7c37a3619160b4d326b1aeaa01754b93a15d14a1dabb9c744ee47b939006")
        chk("json('{}').hash()", "0x51a96761ff6f2503dbed757938696f9e5ddf81f5144eef909e76d195d7495371")
        chk("json('[]').hash()", "0x7acaa91a451b89e7d9631cc5e80990771caab10695cfff972074be07ef12e353")
        chk("json('[1,2,3]').hash()", "0x6134bdf94a714fd2b8170d58cdfa6f1cf7e23e62bcbbc91f4efcece1f3dd89a1")
        chk("E.A.hash()", "0x90b136dfc51e08ee70ed929c620c0808d4230ec1015d46c92ccaa30772651dc0")
        chk("E.B.hash()", "0x6ccd14b5a877874ddc7ca52bd3aeded5543b73a354779224bbb86b0fd315b418")
        chk("E.C.hash()", "0x4317338211726f61b281d62f0683fd55e355011b6e7495cf56f9e03059a3bc0a")
        chk("'A'.hash()", "0xba96d941ab620e0d6092bfbd494266922b6242427840aa452195ded17e13c696")
        chk("'B'.hash()", "0xc065296148ac7ec279e36057709c0f398bf2ca81f99b9395f9155686ceedfde0")
        chk("'C'.hash()", "0xef9f6fb8ab1cca16984702e79ea420ca8922ce15670ccd6bf12ee9b26f9fdac9")
        chk("range(10).hash()", "ct_err:unknown_member:[range]:hash")
    }

    @Test fun testHashCollection() {
        tst.strictToString = false

        chk("list<integer>().hash()", "0x46af9064f12528cad6a7c377204acd0ac38cdc6912903e7dab3703764c8dd5e5")
        chk("[123].hash()", "0x341fdf9993ea5847fb8ad1ba7f92cd3c0fb2932e2ab1dee5fcbcbd7d995d0aa3")
        chk("[1,2,3].hash()", "0x8a6ec7112c4e652c1d6971525a2fbebd9a26d38c026a7eb5bde8aaa54fd57101")
        chk("['Hello'].hash()", "0xd801dd39d403f923583a943cbbe641cb73f665bff3c19984a3389d58b620ab9a")

        chk("set<integer>().hash()", "0x46af9064f12528cad6a7c377204acd0ac38cdc6912903e7dab3703764c8dd5e5")
        chk("set([1,2,3]).hash()", "0x8a6ec7112c4e652c1d6971525a2fbebd9a26d38c026a7eb5bde8aaa54fd57101")
        chk("set(['Hello']).hash()", "0xd801dd39d403f923583a943cbbe641cb73f665bff3c19984a3389d58b620ab9a")

        chk("map<text,integer>().hash()", "0x300b4292a3591228725e6e2e20be3ab63a6a99cc695e925c6c20a90c570a5e71")
        chk("[1:'A',2:'B'].hash()", "0x51626de7acc3fe1070354211eaa7dd072728f5bb863a30866613cae41a8290cf")
        chk("['Hello':1,'Bye':2].hash()", "0xb2ce92049c9ce75afccf2fe979ff8626ffaebbcaa538bfb8fe9efdc150035e23")
    }

    @Test fun testHashTuple() {
        tst.strictToString = false
        chk("(123,).hash()", "0x341fdf9993ea5847fb8ad1ba7f92cd3c0fb2932e2ab1dee5fcbcbd7d995d0aa3")
        chk("('Hello',).hash()", "0xd801dd39d403f923583a943cbbe641cb73f665bff3c19984a3389d58b620ab9a")
        chk("(123,'Hello').hash()", "0x74443c7de4d4fee6f6f4d9b0aa5d4749dbfb0965b422e578802701b9ac2e063a")
        chk("('Hello',123).hash()", "0xde381c2785cc7cd91992db61d859d04ce2b9b1cd96442594beb06f9202a0c583")
        chk("(x=123,y='Hello').hash()", "0x74443c7de4d4fee6f6f4d9b0aa5d4749dbfb0965b422e578802701b9ac2e063a")
        chk("(x=123,'Hello').hash()", "0x74443c7de4d4fee6f6f4d9b0aa5d4749dbfb0965b422e578802701b9ac2e063a")
        chk("(123,y='Hello').hash()", "0x74443c7de4d4fee6f6f4d9b0aa5d4749dbfb0965b422e578802701b9ac2e063a")
    }

    @Test fun testHashEntityObject() {
        tstCtx.useSql = true
        tst.strictToString = false
        def("entity cls { x: integer; }")
        def("object obj { mutable s: text = 'Hello'; }")
        insert("c0.cls", "x", "1,123")
        insert("c0.cls", "x", "2,456")
        chk("(cls@{123}).hash()", "0x6ccd14b5a877874ddc7ca52bd3aeded5543b73a354779224bbb86b0fd315b418")
        chk("(cls@{456}).hash()", "0x4317338211726f61b281d62f0683fd55e355011b6e7495cf56f9e03059a3bc0a")
        chk("obj.hash()", "ct_err:unknown_member:[obj]:hash")
    }
}
