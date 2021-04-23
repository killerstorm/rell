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

    @Test fun testAssertEqualsBasic() {
        val fn = "assert_equals"
        chkEx("{ $fn(0, 0); return 0; }", "int[0]")
        chkEx("{ $fn(0, 1); return 0; }", "rt_err:assert_equals:int[1]:int[0]")
        chkEx("{ $fn('Hello', 'Hello'); return 0; }", "int[0]")
        chkEx("{ $fn('Hello', 'World'); return 0; }", "rt_err:assert_equals:text[World]:text[Hello]")
        chkEx("{ $fn(true, true); return 0; }", "int[0]")
        chkEx("{ $fn(true, false); return 0; }", "rt_err:assert_equals:boolean[false]:boolean[true]")
        chkEx("{ $fn(12.34, 12.34); return 0; }", "int[0]")
        chkEx("{ $fn(12.34, 56.78); return 0; }", "rt_err:assert_equals:dec[56.78]:dec[12.34]")
        chkEx("{ $fn(x'beef', x'beef'); return 0; }", "int[0]")
        chkEx("{ $fn(x'beef', x'feed'); return 0; }", "rt_err:assert_equals:byte_array[feed]:byte_array[beef]")
        chkEx("{ $fn(json('[1,2,3]'), json('[1,2,3]')); return 0; }", "int[0]")
        chkEx("{ $fn(json('[1,2,3]'), json('[4,5,6]')); return 0; }", "rt_err:assert_equals:json[[4,5,6]]:json[[1,2,3]]")
        chkEx("{ $fn(range(5), range(5)); return 0; }", "int[0]")
        chkEx("{ $fn(range(5), range(6)); return 0; }", "rt_err:assert_equals:range[0,6,1]:range[0,5,1]")
    }

    @Test fun testAssertEqualsComplex() {
        def("struct rec { x: integer; }")
        val fn = "assert_equals"

        chkEx("{ $fn((1,'a'), (1,'a')); return 0; }", "int[0]")
        chkEx("{ $fn((1,'a'), (1,'b')); return 0; }", "rt_err:assert_equals:(int[1],text[b]):(int[1],text[a])")
        chkEx("{ $fn((1,'a'), (2,'a')); return 0; }", "rt_err:assert_equals:(int[2],text[a]):(int[1],text[a])")
        chkEx("{ $fn((1,'a'), ('a',1)); return 0; }",
                "ct_err:expr_call_argtypes:assert_equals:(integer,text),(text,integer)")
        chkEx("{ $fn((1,'a'), (1,2)); return 0; }",
                "ct_err:expr_call_argtypes:assert_equals:(integer,text),(integer,integer)")
        chkEx("{ $fn((1,'a'), (1,'a',2)); return 0; }",
                "ct_err:expr_call_argtypes:assert_equals:(integer,text),(integer,text,integer)")
        chkEx("{ $fn((x=1,y='a'), (x=1,z='a')); return 0; }",
                "ct_err:expr_call_argtypes:assert_equals:(x:integer,y:text),(x:integer,z:text)")

        chkEx("{ $fn(rec(123), rec(123)); return 0; }", "int[0]")
        chkEx("{ $fn(rec(123), rec(456)); return 0; }", "rt_err:assert_equals:rec[x=int[456]]:rec[x=int[123]]")
    }

    @Test fun testAssertEqualsCollections() {
        val fn = "assert_equals"

        chkEx("{ $fn([1,2,3], [1,2,3]); return 0; }", "int[0]")
        chkEx("{ $fn([1,2,3], [4,5,6]); return 0; }",
                "rt_err:assert_equals:list<integer>[int[4],int[5],int[6]]:list<integer>[int[1],int[2],int[3]]")
        chkEx("{ $fn([1,2,3], ['a','b','c']); return 0; }",
                "ct_err:expr_call_argtypes:assert_equals:list<integer>,list<text>")

        chkEx("{ $fn(set([1,2,3]), set([1,2,3])); return 0; }", "int[0]")
        chkEx("{ $fn(set([1,2,3]), set([4,5,6])); return 0; }",
                "rt_err:assert_equals:set<integer>[int[4],int[5],int[6]]:set<integer>[int[1],int[2],int[3]]")
        chkEx("{ $fn(set([1,2,3]), set(['a','b','c'])); return 0; }",
                "ct_err:expr_call_argtypes:assert_equals:set<integer>,set<text>")
        chkEx("{ $fn(set([1,2,3]), [1,2,3]); return 0; }",
                "ct_err:expr_call_argtypes:assert_equals:set<integer>,list<integer>")

        chkEx("{ $fn([1:'a'], [1:'a']); return 0; }", "int[0]")
        chkEx("{ $fn([1:'a'], [1:'b']); return 0; }",
                "rt_err:assert_equals:map<integer,text>[int[1]=text[b]]:map<integer,text>[int[1]=text[a]]")
        chkEx("{ $fn([1:'a'], ['a':1]); return 0; }",
                "ct_err:expr_call_argtypes:assert_equals:map<integer,text>,map<text,integer>")
    }

    @Test fun testAssertEqualsSpecial() {
        def("struct rec { x: integer; }")
        val fn = "assert_equals"

        chkEx("{ $fn(123.0, 123); return 0; }", "int[0]")
        chkEx("{ $fn(123.0, 456); return 0; }", "rt_err:assert_equals:dec[456]:dec[123]")
        chkEx("{ $fn(123, 123.0); return 0; }", "int[0]")
        chkEx("{ $fn(123, 456.0); return 0; }", "rt_err:assert_equals:dec[456]:dec[123]")

        chkEx("{ $fn(_nullable_int(123), 123); return 0; }", "int[0]")
        chkEx("{ $fn(_nullable_int(123), 456); return 0; }", "rt_err:assert_equals:int[456]:int[123]")
        chkEx("{ $fn(_nullable_int(123), _nullable_int(123)); return 0; }", "int[0]")
        chkEx("{ $fn(_nullable_int(123), _nullable_int(456)); return 0; }", "rt_err:assert_equals:int[456]:int[123]")
        chkEx("{ $fn(_nullable_int(123), null); return 0; }", "rt_err:assert_equals:null:int[123]")
        chkEx("{ $fn(123, _nullable_int(123)); return 0; }", "int[0]")
        chkEx("{ $fn(123, _nullable_int(456)); return 0; }", "rt_err:assert_equals:int[456]:int[123]")
        chkEx("{ $fn(null, _nullable_int(123)); return 0; }", "rt_err:assert_equals:int[123]:null")

        chkEx("{ $fn(_nullable(rec(123)), rec(123)); return 0; }", "int[0]")
        chkEx("{ $fn(_nullable(rec(123)), rec(456)); return 0; }", "rt_err:assert_equals:rec[x=int[456]]:rec[x=int[123]]")
        chkEx("{ $fn(rec(123), _nullable(rec(123))); return 0; }", "int[0]")
        chkEx("{ $fn(rec(123), _nullable(rec(456))); return 0; }", "rt_err:assert_equals:rec[x=int[456]]:rec[x=int[123]]")
        chkEx("{ $fn(_nullable(rec(123)), null); return 0; }", "rt_err:assert_equals:null:rec[x=int[123]]")
        chkEx("{ $fn(null, _nullable(rec(123))); return 0; }", "rt_err:assert_equals:rec[x=int[123]]:null")

        // More complex type promotion not supported yet.
        chkEx("{ $fn(_nullable_int(123), 123.0); return 0; }", "ct_err:expr_call_argtypes:assert_equals:integer?,decimal")
        chkEx("{ $fn(123.0, _nullable_int(123)); return 0; }", "ct_err:expr_call_argtypes:assert_equals:decimal,integer?")
        chkEx("{ $fn(_nullable(123.0), 123); return 0; }", "ct_err:expr_call_argtypes:assert_equals:decimal?,integer")
        chkEx("{ $fn(123, _nullable(123.0)); return 0; }", "ct_err:expr_call_argtypes:assert_equals:integer,decimal?")
    }

    @Test fun testAssertEqualsBadArgs() {
        val fn = "assert_equals"
        chkEx("{ $fn(); return 0; }", "ct_err:expr_call_argtypes:assert_equals:")
        chkEx("{ $fn(0); return 0; }", "ct_err:expr_call_argtypes:assert_equals:integer")
        chkEx("{ $fn(0, 1, 'Hello'); return 0; }", "ct_err:expr_call_argtypes:assert_equals:integer,integer,text")
        chkEx("{ $fn(0, 1, 2); return 0; }", "ct_err:expr_call_argtypes:assert_equals:integer,integer,integer")
        chkEx("{ $fn(123, 'Hello'); return 0; }", "ct_err:expr_call_argtypes:assert_equals:integer,text")
    }

    @Test fun testAssertEqualsRellTestNs() {
        val fn = "rell.test.assert_equals"
        chkEx("{ $fn(123, 123); return 0; }", "int[0]")
        chkEx("{ $fn(123, 456); return 0; }", "rt_err:assert_equals:int[456]:int[123]")
        chkEx("{ $fn('Bob', 'Bob'); return 0; }", "int[0]")
        chkEx("{ $fn('Bob', 'Alice'); return 0; }", "rt_err:assert_equals:text[Alice]:text[Bob]")
        chkEx("{ $fn('Bob', 123); return 0; }", "ct_err:expr_call_argtypes:assert_equals:text,integer")
    }
}
