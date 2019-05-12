package net.postchain.rell.lib

import net.postchain.base.secp256k1_derivePubKey
import net.postchain.base.secp256k1_sign
import net.postchain.rell.CommonUtils
import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LibCryptoTest: BaseRellTest(false) {
    @Test fun testVerifySignature() {
        val privKeyBytes = ByteArray(32) { it.toByte() }
        val pubKey = CommonUtils.bytesToHex(secp256k1_derivePubKey(privKeyBytes))

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
        return CommonUtils.bytesToHex(secp256k1_sign(CommonUtils.hexToBytes(messageHex), privKeyBytes))
    }

    @Test fun testHashRecord() {
        tst.strictToString = false
        tst.defs = listOf("record rec { i: integer; t: text; }", "record rec_nogtv { m: range; }")
        chk("rec(123,'Hello').hash()", "0x74443c7de4d4fee6f6f4d9b0aa5d4749dbfb0965b422e578802701b9ac2e063a")
        chk("rec(456,'Bye').hash()", "0x7758916e7f9f1a9e0a84351f402dbc9c906492879a9d71dc4ff1f5b7d67bdf53")
        chk("rec_nogtv(range(10)).hash()", "ct_err:fn:invalid:rec_nogtv:rec_nogtv.hash")
        chk("rec_nogtv(range(10)).hash()", "ct_err:fn:invalid:rec_nogtv:rec_nogtv.hash")
        chk("rec(123,'Hello').to_gtv().hash()", "0x74443c7de4d4fee6f6f4d9b0aa5d4749dbfb0965b422e578802701b9ac2e063a")
        chk("rec(456,'Bye').to_gtv().hash()", "0x7758916e7f9f1a9e0a84351f402dbc9c906492879a9d71dc4ff1f5b7d67bdf53")
    }

    @Test fun testHashSimple() {
        tst.strictToString = false
        tst.defs = listOf("enum E {A,B,C}")
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
        chk("range(10).hash()", "ct_err:unknown_member:range:hash")
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

    @Test fun testHashClassObject() {
        tstCtx.useSql = true
        tst.strictToString = false
        tst.defs = listOf("class cls { x: integer; }", "object obj { mutable s: text = 'Hello'; }")
        tst.insert("c0.cls", "x", "1,123")
        tst.insert("c0.cls", "x", "2,456")
        chk("(cls@{123}).hash()", "0x6ccd14b5a877874ddc7ca52bd3aeded5543b73a354779224bbb86b0fd315b418")
        chk("(cls@{456}).hash()", "0x4317338211726f61b281d62f0683fd55e355011b6e7495cf56f9e03059a3bc0a")
        chk("obj.hash()", "ct_err:unknown_name:obj.hash")
    }
}
