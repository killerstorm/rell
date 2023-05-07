/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.ide

import org.junit.Test

class IdeSymbolInfoLibTest: BaseIdeSymbolTest() {
    @Test fun testChainContext() {
        file("module.rell", "struct module_args { x: integer; }")

        val chainCtx = "chain_context:DEF_NAMESPACE;-;-"
        chkKdlsExprErr("chain_context", "expr_novalue:namespace:[chain_context]", chainCtx)
        chkKdlsExpr("chain_context.blockchain_rid", chainCtx, "blockchain_rid:DEF_CONSTANT;-;-")
        chkKdlsExpr("chain_context.raw_config", chainCtx, "raw_config:DEF_CONSTANT;-;-")

        val moduleArgs = "args:DEF_CONSTANT;-;module.rell/struct[module_args]"
        chkKdlsExpr("chain_context.args", chainCtx, moduleArgs)
        chkKdlsExpr("chain_context.args.x", chainCtx, moduleArgs, "x:MEM_STRUCT_ATTR;-;module.rell/struct[module_args].attr[x]")
    }

    @Test fun testOpContext() {
        file("module.rell", "function g() = gtv.from_json('');")
        val common = arrayOf("f:DEF_FUNCTION;function[f];-", "op_context:DEF_NAMESPACE;-;-")

        chkKdls("function f() = op_context.last_block_time;", *common, "last_block_time:MEM_SYS_PROPERTY;-;-")
        chkKdls("function f() = op_context.block_height;", *common, "block_height:MEM_SYS_PROPERTY;-;-")
        chkKdls("function f() = op_context.op_index;", *common, "op_index:MEM_SYS_PROPERTY;-;-")
        chkKdls("function f() = op_context.transaction;", *common, "transaction:MEM_SYS_PROPERTY;-;-")

        chkKdls("function f() = op_context.get_signers();", *common, "get_signers:DEF_FUNCTION_SYSTEM;-;-")
        chkKdls("function f() = op_context.is_signer(x'');", *common, "is_signer:DEF_FUNCTION_SYSTEM;-;-")
        chkKdls("function f() = op_context.get_all_operations();", *common, "get_all_operations:DEF_FUNCTION_SYSTEM;-;-")

        chkKdls("function f() = op_context.emit_event('', g());",
            *common,
            "emit_event:DEF_FUNCTION_SYSTEM;-;-",
            "g:DEF_FUNCTION;-;module.rell/function[g]"
        )
    }

    @Test fun testInteger() {
        chkKdlsExpr("integer.MIN_VALUE", "integer:DEF_TYPE;-;-", "MIN_VALUE:DEF_CONSTANT;-;-")
        chkKdlsExpr("integer.MAX_VALUE", "integer:DEF_TYPE;-;-", "MAX_VALUE:DEF_CONSTANT;-;-")

        chkKdlsExpr("integer('123')", "integer:DEF_TYPE;-;-")
        chkKdlsExpr("integer('123', 10)", "integer:DEF_TYPE;-;-")
        chkKdlsExpr("integer(123.456)", "integer:DEF_TYPE;-;-")

        chkKdlsExpr("integer.from_text('123')", "integer:DEF_TYPE;-;-", "from_text:DEF_FUNCTION_SYSTEM;-;-")
        chkKdlsExpr("integer.from_text('123', 10)", "integer:DEF_TYPE;-;-", "from_text:DEF_FUNCTION_SYSTEM;-;-")
        chkKdlsExpr("integer.from_hex('1234')", "integer:DEF_TYPE;-;-", "from_hex:DEF_FUNCTION_SYSTEM;-;-")
    }

    @Test fun testDecimal() {
        chkKdlsExpr("decimal.MIN_VALUE", "decimal:DEF_TYPE;-;-", "MIN_VALUE:DEF_CONSTANT;-;-")
        chkKdlsExpr("decimal.MAX_VALUE", "decimal:DEF_TYPE;-;-", "MAX_VALUE:DEF_CONSTANT;-;-")
        chkKdlsExpr("decimal.PRECISION", "decimal:DEF_TYPE;-;-", "PRECISION:DEF_CONSTANT;-;-")
        chkKdlsExpr("decimal.SCALE", "decimal:DEF_TYPE;-;-", "SCALE:DEF_CONSTANT;-;-")
        chkKdlsExpr("decimal.INT_DIGITS", "decimal:DEF_TYPE;-;-", "INT_DIGITS:DEF_CONSTANT;-;-")

        chkKdlsExpr("decimal(123)", "decimal:DEF_TYPE;-;-")
        chkKdlsExpr("decimal('123')", "decimal:DEF_TYPE;-;-")

        chkKdlsExpr("decimal.from_text('123')", "decimal:DEF_TYPE;-;-", "from_text:DEF_FUNCTION_SYSTEM;-;-")
    }

    @Test fun testTypes() {
        fun c(type: String) = chkKdlsType(type, "$type:DEF_TYPE;-;-")
        chkKdls("function f(): unit {}", "f:DEF_FUNCTION;function[f];-", "unit:DEF_TYPE;-;-")
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
        chkKdlsExpr("unit()", "unit:DEF_TYPE;-;-")
        chkKdlsExpr("json('')", "json:DEF_TYPE;-;-")

        chkKdlsExpr("byte_array('1234')", "byte_array:DEF_TYPE;-;-")
        chkKdlsExpr("byte_array.from_list([1,2,3])", "byte_array:DEF_TYPE;-;-", "from_list:DEF_FUNCTION_SYSTEM;-;-")
        chkKdlsExpr("byte_array.from_hex('1234')", "byte_array:DEF_TYPE;-;-", "from_hex:DEF_FUNCTION_SYSTEM;-;-")
        chkKdlsExpr("byte_array.from_base64('1234')", "byte_array:DEF_TYPE;-;-", "from_base64:DEF_FUNCTION_SYSTEM;-;-")

        chkKdlsExpr("gtv.from_json('')", "gtv:DEF_TYPE;-;-", "from_json:DEF_FUNCTION_SYSTEM;-;-")
        chkKdlsExpr("gtv.from_bytes(x'')", "gtv:DEF_TYPE;-;-", "from_bytes:DEF_FUNCTION_SYSTEM;-;-")

        chkKdlsExpr("range(10)", "range:DEF_TYPE;-;-")
        chkKdlsExpr("range(1, 10)", "range:DEF_TYPE;-;-")
        chkKdlsExpr("range(1, 10, 2)", "range:DEF_TYPE;-;-")

        chkKdlsExpr("text.from_bytes(x'')", "text:DEF_TYPE;-;-", "from_bytes:DEF_FUNCTION_SYSTEM;-;-")
        chkKdlsExpr("text.from_bytes(x'', true)", "text:DEF_TYPE;-;-", "from_bytes:DEF_FUNCTION_SYSTEM;-;-")
    }

    @Test fun testTestTxApi() {
        tst.testLib = true

        chkKdlsType("rell.test.block", "rell:DEF_NAMESPACE;-;-", "test:DEF_NAMESPACE;-;-", "block:DEF_TYPE;-;-")
        chkKdlsType("rell.test.tx", "rell:DEF_NAMESPACE;-;-", "test:DEF_NAMESPACE;-;-", "tx:DEF_TYPE;-;-")
        chkKdlsType("rell.test.op", "rell:DEF_NAMESPACE;-;-", "test:DEF_NAMESPACE;-;-", "op:DEF_TYPE;-;-")

        chkKdlsExpr("rell.test.block()", "rell:DEF_NAMESPACE;-;-", "test:DEF_NAMESPACE;-;-", "block:DEF_TYPE;-;-")
        chkKdlsExpr("rell.test.tx()", "rell:DEF_NAMESPACE;-;-", "test:DEF_NAMESPACE;-;-", "tx:DEF_TYPE;-;-")
        chkKdlsExpr("rell.test.op('foo')", "rell:DEF_NAMESPACE;-;-", "test:DEF_NAMESPACE;-;-", "op:DEF_TYPE;-;-")
    }

    @Test fun testTestKeypairs() {
        tst.testLib = true
        file("module.rell", "function kp() = rell.test.keypair(pub = x'', priv = x'');")

        val rellTest = arrayOf("rell:DEF_NAMESPACE;-;-", "test:DEF_NAMESPACE;-;-")

        chkKdlsExprErr("rell.test", "expr_novalue:namespace:[rell.test]", *rellTest)
        chkKdlsExprErr("rell.test.keypair()", "attr_missing:pub,priv", *rellTest, "keypair:DEF_STRUCT;-;-")
        chkKdlsExpr("kp().pub", "kp:DEF_FUNCTION;-;module.rell/function[kp]", "pub:MEM_STRUCT_ATTR;-;-")
        chkKdlsExpr("kp().priv", "kp:DEF_FUNCTION;-;module.rell/function[kp]", "priv:MEM_STRUCT_ATTR;-;-")

        chkKdlsExprErr("rell.test.keypairs", "expr_novalue:namespace:[rell.test.keypairs]", *rellTest, "keypairs:DEF_NAMESPACE;-;-")
        chkKdlsExpr("rell.test.keypairs.bob", *rellTest, "keypairs:DEF_NAMESPACE;-;-", "bob:DEF_CONSTANT;-;-")
        chkKdlsExpr("rell.test.keypairs.alice", *rellTest, "keypairs:DEF_NAMESPACE;-;-", "alice:DEF_CONSTANT;-;-")
        chkKdlsExpr("rell.test.keypairs.trudy", *rellTest, "keypairs:DEF_NAMESPACE;-;-", "trudy:DEF_CONSTANT;-;-")

        chkKdlsExprErr("rell.test.privkeys", "expr_novalue:namespace:[rell.test.privkeys]", *rellTest, "privkeys:DEF_NAMESPACE;-;-")
        chkKdlsExpr("rell.test.privkeys.bob", *rellTest, "privkeys:DEF_NAMESPACE;-;-", "bob:DEF_CONSTANT;-;-")
        chkKdlsExpr("rell.test.privkeys.alice", *rellTest, "privkeys:DEF_NAMESPACE;-;-", "alice:DEF_CONSTANT;-;-")
        chkKdlsExpr("rell.test.privkeys.trudy", *rellTest, "privkeys:DEF_NAMESPACE;-;-", "trudy:DEF_CONSTANT;-;-")

        chkKdlsExprErr("rell.test.pubkeys", "expr_novalue:namespace:[rell.test.pubkeys]", *rellTest, "pubkeys:DEF_NAMESPACE;-;-")
        chkKdlsExpr("rell.test.pubkeys.bob", *rellTest, "pubkeys:DEF_NAMESPACE;-;-", "bob:DEF_CONSTANT;-;-")
        chkKdlsExpr("rell.test.pubkeys.alice", *rellTest, "pubkeys:DEF_NAMESPACE;-;-", "alice:DEF_CONSTANT;-;-")
        chkKdlsExpr("rell.test.pubkeys.trudy", *rellTest, "pubkeys:DEF_NAMESPACE;-;-", "trudy:DEF_CONSTANT;-;-")
    }

    @Test fun testStruct() {
        file("module.rell", "struct rec { x: integer; } function g() = gtv.from_json('');")
        chkKdlsExpr("rec.from_gtv(g())",
            "rec:DEF_STRUCT;-;module.rell/struct[rec]",
            "from_gtv:DEF_FUNCTION_SYSTEM;-;-",
            "g:DEF_FUNCTION;-;module.rell/function[g]"
        )
    }

    @Test fun testCollectionType() {
        chkKdlsType("list<integer>", "list:DEF_TYPE;-;-", "integer:DEF_TYPE;-;-")
        chkKdlsType("set<integer>", "set:DEF_TYPE;-;-", "integer:DEF_TYPE;-;-")
        chkKdlsType("map<integer,text>", "map:DEF_TYPE;-;-", "integer:DEF_TYPE;-;-", "text:DEF_TYPE;-;-")
    }

    @Test fun testCollectionConstructor() {
        chkKdlsExpr("list<integer>()", "list:DEF_TYPE;-;-", "integer:DEF_TYPE;-;-")
        chkKdlsExpr("set<integer>()", "set:DEF_TYPE;-;-", "integer:DEF_TYPE;-;-")
        chkKdlsExpr("map<integer,text>()", "map:DEF_TYPE;-;-", "integer:DEF_TYPE;-;-", "text:DEF_TYPE;-;-")
        chkKdlsExpr("list([123])", "list:DEF_TYPE;-;-")
        chkKdlsExpr("set([123])", "set:DEF_TYPE;-;-")
        chkKdlsExpr("map([123:'ABC'])", "map:DEF_TYPE;-;-")
    }

    @Test fun testCollectionStaticMethod() {
        chkKdlsExpr("list<integer>.from_gtv(null.to_gtv())", "list:DEF_TYPE;-;-", "integer:DEF_TYPE;-;-",
            "from_gtv:DEF_FUNCTION_SYSTEM;-;-", "to_gtv:DEF_FUNCTION_SYSTEM;-;-")
        chkKdlsExpr("set<integer>.from_gtv(null.to_gtv())", "set:DEF_TYPE;-;-", "integer:DEF_TYPE;-;-",
            "from_gtv:DEF_FUNCTION_SYSTEM;-;-", "to_gtv:DEF_FUNCTION_SYSTEM;-;-")
        chkKdlsExpr("map<integer,text>.from_gtv(null.to_gtv())", "map:DEF_TYPE;-;-", "integer:DEF_TYPE;-;-", "text:DEF_TYPE;-;-",
            "from_gtv:DEF_FUNCTION_SYSTEM;-;-", "to_gtv:DEF_FUNCTION_SYSTEM;-;-")
    }
}
