/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LibRellTestOtherTest: BaseRellTest(false) {
    init {
        tst.testLib = true
    }

    @Test fun testKeypairType() {
        chkEx("{ val x: rell.test.keypair; return _type_of(x); }", "text[rell.test.keypair]")

        chk("rell.test.keypair()", "ct_err:attr_missing:pub,priv")
        chk("rell.test.keypair(x'', x'')", "ct_err:attr_implic_multi:0:pub,priv")
        chk("rell.test.keypair(pub = x'11', priv = x'22')", "rell.test.keypair[pub=byte_array[11],priv=byte_array[22]]")

        chk("""rell.test.keypair.from_gtv(gtv.from_json('["11","22"]'))""",
                "rell.test.keypair[pub=byte_array[11],priv=byte_array[22]]")
    }

    @Test fun testPredefinedKeypairs() {
        val bobPriv = "byte_array[1111111111111111111111111111111111111111111111111111111111111111]"
        val bobPub = "byte_array[034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa]"
        val alicePriv = "byte_array[2222222222222222222222222222222222222222222222222222222222222222]"
        val alicePub = "byte_array[02466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f27]"
        val trudyPriv = "byte_array[3333333333333333333333333333333333333333333333333333333333333333]"
        val trudyPub = "byte_array[023c72addb4fdf09af94f0c94d7fe92a386a7e70cf8a1d85916386bb2535c7b1b1]"

        chk("rell.test.keypairs.bob", "rell.test.keypair[pub=$bobPub,priv=$bobPriv]")
        chk("rell.test.keypairs.alice", "rell.test.keypair[pub=$alicePub,priv=$alicePriv]")
        chk("rell.test.keypairs.trudy", "rell.test.keypair[pub=$trudyPub,priv=$trudyPriv]")

        chk("rell.test.keypairs.bob.pub", bobPub)
        chk("rell.test.keypairs.bob.priv", bobPriv)
        chk("rell.test.keypairs.alice.pub", alicePub)
        chk("rell.test.keypairs.alice.priv", alicePriv)
        chk("rell.test.keypairs.trudy.pub", trudyPub)
        chk("rell.test.keypairs.trudy.priv", trudyPriv)

        chk("rell.test.pubkeys.bob", bobPub)
        chk("rell.test.pubkeys.alice", alicePub)
        chk("rell.test.pubkeys.trudy", trudyPub)

        chk("rell.test.privkeys.bob", bobPriv)
        chk("rell.test.privkeys.alice", alicePriv)
        chk("rell.test.privkeys.trudy", trudyPriv)
    }

    @Test fun testPredefinedKeypairsTypes() {
        val bobPriv = "byte_array[1111111111111111111111111111111111111111111111111111111111111111]"
        val bobPub = "byte_array[034f355bdcb7cc0af728ef3cceb9615d90684bb5b2ca5f859ab0f0b704075871aa]"

        chk("_type_of(rell.test.keypair(priv = x'11', pub = x'22'))", "text[rell.test.keypair]")
        chk("_type_of(rell.test.keypairs.bob)", "text[rell.test.keypair]")

        chkEx("{ val x: rell.test.keypair = rell.test.keypairs.bob; return x; }",
                "rell.test.keypair[pub=$bobPub,priv=$bobPriv]")

        chkEx("{ val x: rell.test.keypair = rell.test.keypair(priv = x'11', pub = x'22'); return x; }",
                "rell.test.keypair[pub=byte_array[22],priv=byte_array[11]]")
    }

    @Test fun testNoTestLib() {
        tst.testLib = false
        chk("rell.test.block()", "ct_err:unknown_name:rell.test")
        chk("rell.test.tx()", "ct_err:unknown_name:rell.test")
        chk("rell.test.op()", "ct_err:unknown_name:rell.test")
        chk("rell.test.keypairs.bob", "ct_err:unknown_name:rell.test")
        chk("rell.test.privkeys.bob", "ct_err:unknown_name:rell.test")
        chk("rell.test.pubkeys.bob", "ct_err:unknown_name:rell.test")
        chk("rell.test.keypair()", "ct_err:unknown_name:rell.test")
        chk("rell.test.assert_equals(0,0)", "ct_err:unknown_name:rell.test")
        chk("assert_equals(0,0)", "ct_err:unknown_name:assert_equals")
    }
}
