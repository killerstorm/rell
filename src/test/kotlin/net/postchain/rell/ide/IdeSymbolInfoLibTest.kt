/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.ide

import org.junit.Test

class IdeSymbolInfoLibTest: BaseIdeSymbolInfoTest() {
    @Test fun testChainContext() {
        file("module.rell", "struct module_args { x: integer; }")
        chkExprErr("chain_context", "expr_novalue:namespace", "chain_context:DEF_NAMESPACE")
        chkExpr("chain_context.args", "chain_context:DEF_NAMESPACE", "args:DEF_CONSTANT")
        chkExpr("chain_context.args.x", "chain_context:DEF_NAMESPACE", "args:DEF_CONSTANT", "x:MEM_STRUCT_ATTR")
        chkExpr("chain_context.blockchain_rid", "chain_context:DEF_NAMESPACE", "blockchain_rid:DEF_CONSTANT")
        chkExpr("chain_context.raw_config", "chain_context:DEF_NAMESPACE", "raw_config:DEF_CONSTANT")
    }

    @Test fun testOpContext() {
        file("module.rell", "function g() = gtv.from_json('');")
        val common = arrayOf("f:DEF_FUNCTION_REGULAR", "op_context:DEF_NAMESPACE")

        chkSyms("function f() = op_context.last_block_time;", *common, "last_block_time:MEM_SYS_PROPERTY")
        chkSyms("function f() = op_context.block_height;", *common, "block_height:MEM_SYS_PROPERTY")
        chkSyms("function f() = op_context.op_index;", *common, "op_index:MEM_SYS_PROPERTY")
        chkSyms("function f() = op_context.transaction;", *common, "transaction:MEM_SYS_PROPERTY")

        chkSyms("function f() = op_context.get_signers();", *common, "get_signers:DEF_FUNCTION_SYSTEM")
        chkSyms("function f() = op_context.is_signer(x'');", *common, "is_signer:DEF_FUNCTION_SYSTEM")
        chkSyms("function f() = op_context.get_all_operations();", *common, "get_all_operations:DEF_FUNCTION_SYSTEM")
        chkSyms("function f() = op_context.emit_event('', g());", *common, "emit_event:DEF_FUNCTION_SYSTEM", "g:DEF_FUNCTION_REGULAR")
    }

    @Test fun testInteger() {
        chkExpr("integer.MIN_VALUE", "integer:DEF_TYPE", "MIN_VALUE:DEF_CONSTANT")
        chkExpr("integer.MAX_VALUE", "integer:DEF_TYPE", "MAX_VALUE:DEF_CONSTANT")

        chkExpr("integer('123')", "integer:DEF_TYPE")
        chkExpr("integer('123', 10)", "integer:DEF_TYPE")
        chkExpr("integer(123.456)", "integer:DEF_TYPE")

        chkExpr("integer.from_text('123')", "integer:DEF_TYPE", "from_text:DEF_FUNCTION_SYSTEM")
        chkExpr("integer.from_text('123', 10)", "integer:DEF_TYPE", "from_text:DEF_FUNCTION_SYSTEM")
        chkExpr("integer.from_hex('1234')", "integer:DEF_TYPE", "from_hex:DEF_FUNCTION_SYSTEM")
    }

    @Test fun testDecimal() {
        chkExpr("decimal.MIN_VALUE", "decimal:DEF_TYPE", "MIN_VALUE:DEF_CONSTANT")
        chkExpr("decimal.MAX_VALUE", "decimal:DEF_TYPE", "MAX_VALUE:DEF_CONSTANT")
        chkExpr("decimal.PRECISION", "decimal:DEF_TYPE", "PRECISION:DEF_CONSTANT")
        chkExpr("decimal.SCALE", "decimal:DEF_TYPE", "SCALE:DEF_CONSTANT")
        chkExpr("decimal.INT_DIGITS", "decimal:DEF_TYPE", "INT_DIGITS:DEF_CONSTANT")

        chkExpr("decimal(123)", "decimal:DEF_TYPE")
        chkExpr("decimal('123')", "decimal:DEF_TYPE")

        chkExpr("decimal.from_text('123')", "decimal:DEF_TYPE", "from_text:DEF_FUNCTION_SYSTEM")
    }

    @Test fun testTypes() {
        fun c(type: String) = chkType(type, "$type:DEF_TYPE")
        chkSyms("function f(): unit {}", "f:DEF_FUNCTION_REGULAR", "unit:DEF_TYPE")
        c("boolean")
        c("text")
        c("byte_array")
        c("integer")
        c("decimal")
        c("rowid")
        c("pubkey")
        c("name")
        c("timestamp")
        c("signer")
        c("guid")
        c("tuid")
        c("json")
        c("range")
        c("gtv")
    }

    @Test fun testTypeFunctions() {
        chkExpr("unit()", "unit:DEF_TYPE")
        chkExpr("json('')", "json:DEF_TYPE")

        chkExpr("byte_array('1234')", "byte_array:DEF_TYPE")
        chkExpr("byte_array.from_list([1,2,3])", "byte_array:DEF_TYPE", "from_list:DEF_FUNCTION_SYSTEM")
        chkExpr("byte_array.from_hex('1234')", "byte_array:DEF_TYPE", "from_hex:DEF_FUNCTION_SYSTEM")
        chkExpr("byte_array.from_base64('1234')", "byte_array:DEF_TYPE", "from_base64:DEF_FUNCTION_SYSTEM")

        chkExpr("gtv.from_json('')", "gtv:DEF_TYPE", "from_json:DEF_FUNCTION_SYSTEM")
        chkExpr("gtv.from_bytes(x'')", "gtv:DEF_TYPE", "from_bytes:DEF_FUNCTION_SYSTEM")

        chkExpr("range(10)", "range:DEF_TYPE")
        chkExpr("range(1, 10)", "range:DEF_TYPE")
        chkExpr("range(1, 10, 2)", "range:DEF_TYPE")

        chkExpr("text.from_bytes(x'')", "text:DEF_TYPE", "from_bytes:DEF_FUNCTION_SYSTEM")
        chkExpr("text.from_bytes(x'', true)", "text:DEF_TYPE", "from_bytes:DEF_FUNCTION_SYSTEM")
    }

    @Test fun testTestTxApi() {
        tst.testLib = true

        chkType("rell.test.block", "rell:DEF_NAMESPACE", "test:DEF_NAMESPACE", "block:DEF_TYPE")
        chkType("rell.test.tx", "rell:DEF_NAMESPACE", "test:DEF_NAMESPACE", "tx:DEF_TYPE")
        chkType("rell.test.op", "rell:DEF_NAMESPACE", "test:DEF_NAMESPACE", "op:DEF_TYPE")

        chkExpr("rell.test.block()", "rell:DEF_NAMESPACE", "test:DEF_NAMESPACE", "block:DEF_TYPE")
        chkExpr("rell.test.tx()", "rell:DEF_NAMESPACE", "test:DEF_NAMESPACE", "tx:DEF_TYPE")
        chkExpr("rell.test.op('foo')", "rell:DEF_NAMESPACE", "test:DEF_NAMESPACE", "op:DEF_TYPE")
    }

    @Test fun testTestKeypairs() {
        tst.testLib = true
        file("module.rell", "function kp() = rell.test.keypair(pub = x'', priv = x'');")

        val rellTest = arrayOf("rell:DEF_NAMESPACE", "test:DEF_NAMESPACE")

        chkExprErr("rell.test", "expr_novalue:namespace", *rellTest)
        chkExprErr("rell.test.keypair()", "attr_missing:pub,priv", *rellTest, "keypair:DEF_STRUCT")
        chkExpr("kp().pub", "kp:DEF_FUNCTION_REGULAR", "pub:MEM_STRUCT_ATTR")
        chkExpr("kp().priv", "kp:DEF_FUNCTION_REGULAR", "priv:MEM_STRUCT_ATTR")

        chkExprErr("rell.test.keypairs", "expr_novalue:namespace", *rellTest, "keypairs:DEF_NAMESPACE")
        chkExpr("rell.test.keypairs.bob", *rellTest, "keypairs:DEF_NAMESPACE", "bob:DEF_CONSTANT")
        chkExpr("rell.test.keypairs.alice", *rellTest, "keypairs:DEF_NAMESPACE", "alice:DEF_CONSTANT")
        chkExpr("rell.test.keypairs.trudy", *rellTest, "keypairs:DEF_NAMESPACE", "trudy:DEF_CONSTANT")

        chkExprErr("rell.test.privkeys", "expr_novalue:namespace", *rellTest, "privkeys:DEF_NAMESPACE")
        chkExpr("rell.test.privkeys.bob", *rellTest, "privkeys:DEF_NAMESPACE", "bob:DEF_CONSTANT")
        chkExpr("rell.test.privkeys.alice", *rellTest, "privkeys:DEF_NAMESPACE", "alice:DEF_CONSTANT")
        chkExpr("rell.test.privkeys.trudy", *rellTest, "privkeys:DEF_NAMESPACE", "trudy:DEF_CONSTANT")

        chkExprErr("rell.test.pubkeys", "expr_novalue:namespace", *rellTest, "pubkeys:DEF_NAMESPACE")
        chkExpr("rell.test.pubkeys.bob", *rellTest, "pubkeys:DEF_NAMESPACE", "bob:DEF_CONSTANT")
        chkExpr("rell.test.pubkeys.alice", *rellTest, "pubkeys:DEF_NAMESPACE", "alice:DEF_CONSTANT")
        chkExpr("rell.test.pubkeys.trudy", *rellTest, "pubkeys:DEF_NAMESPACE", "trudy:DEF_CONSTANT")
    }

    @Test fun testStruct() {
        file("module.rell", "struct rec { x: integer; } function g() = gtv.from_json('');")
        chkExpr("rec.from_gtv(g())", "rec:DEF_STRUCT", "from_gtv:DEF_FUNCTION_SYSTEM", "g:DEF_FUNCTION_REGULAR")
    }

    @Test fun testCollectionType() {
        chkType("list<integer>", "list:DEF_TYPE", "integer:DEF_TYPE")
        chkType("set<integer>", "set:DEF_TYPE", "integer:DEF_TYPE")
        chkType("map<integer,text>", "map:DEF_TYPE", "integer:DEF_TYPE", "text:DEF_TYPE")
    }

    @Test fun testCollectionConstructor() {
        chkExpr("list<integer>()", "list:DEF_TYPE", "integer:DEF_TYPE")
        chkExpr("set<integer>()", "set:DEF_TYPE", "integer:DEF_TYPE")
        chkExpr("map<integer,text>()", "map:DEF_TYPE", "integer:DEF_TYPE", "text:DEF_TYPE")
        chkExpr("list([123])", "list:DEF_TYPE")
        chkExpr("set([123])", "set:DEF_TYPE")
        chkExpr("map([123:'ABC'])", "map:DEF_TYPE")
    }

    @Test fun testCollectionStaticMethod() {
        chkExpr("list<integer>.from_gtv(null.to_gtv())", "list:DEF_TYPE", "integer:DEF_TYPE",
            "from_gtv:DEF_FUNCTION_SYSTEM", "to_gtv:DEF_FUNCTION_SYSTEM")
        chkExpr("set<integer>.from_gtv(null.to_gtv())", "set:DEF_TYPE", "integer:DEF_TYPE",
            "from_gtv:DEF_FUNCTION_SYSTEM", "to_gtv:DEF_FUNCTION_SYSTEM")
        chkExpr("map<integer,text>.from_gtv(null.to_gtv())", "map:DEF_TYPE", "integer:DEF_TYPE", "text:DEF_TYPE",
            "from_gtv:DEF_FUNCTION_SYSTEM", "to_gtv:DEF_FUNCTION_SYSTEM")
    }
}
