package net.postchain.rell.lib

import net.postchain.rell.BaseRellTest
import net.postchain.rell.hexStringToByteArray
import net.postchain.rell.runtime.RtOpContext
import org.junit.Test

class LibTest: BaseRellTest(false) {
    @Test fun testAbs() {
        chk("abs(a)", 0, "int[0]")
        chk("abs(a)", -123, "int[123]")
        chk("abs(a)", 123, "int[123]")
        chk("abs('Hello')", "ct_err:expr_call_argtypes:abs:text")
        chk("abs()", "ct_err:expr_call_argtypes:abs:")
        chk("abs(1, 2)", "ct_err:expr_call_argtypes:abs:integer,integer")
    }

    @Test fun testMinMax() {
        chk("min(a, b)", 100, 200, "int[100]")
        chk("min(a, b)", 200, 100, "int[100]")
        chk("max(a, b)", 100, 200, "int[200]")
        chk("max(a, b)", 200, 100, "int[200]")
    }

    @Test fun testPrint() {
        chkEx("{ print('Hello'); return 123; }", "int[123]")
        tst.chkStdout("Hello")
        tst.chkLog()

        chkEx("{ print(12345); return 123; }", "int[123]")
        tst.chkStdout("12345")
        tst.chkLog()

        chkEx("{ print(1, 2, 3, 4, 5); return 123; }", "int[123]")
        tst.chkStdout("1 2 3 4 5")
        tst.chkLog()

        chkEx("{ print(); return 123; }", "int[123]")
        tst.chkStdout("")
        tst.chkLog()
    }

    @Test fun testLog() {
        chkEx("{ log('Hello'); return 123; }", "int[123]")
        tst.chkLog("Hello")
        tst.chkStdout()

        chkEx("{ log(12345); return 123; }", "int[123]")
        tst.chkLog("12345")
        tst.chkStdout()

        chkEx("{ log(1, 2, 3, 4, 5); return 123; }", "int[123]")
        tst.chkLog("1 2 3 4 5")
        tst.chkStdout()

        chkEx("{ log(); return 123; }", "int[123]")
        tst.chkStdout()
        tst.chkLog("")
    }

    @Test fun testIsSigner() {
        tst.opContext = RtOpContext(-1, listOf("1234".hexStringToByteArray(), "abcd".hexStringToByteArray()))

        chk("is_signer(x'1234')", "boolean[true]")
        chk("is_signer(x'abcd')", "boolean[true]")
        chk("is_signer(x'1234abcd')", "boolean[false]")
        chk("is_signer(x'')", "boolean[false]")

        chk("is_signer()", "ct_err:expr_call_argtypes:is_signer:")
        chk("is_signer(123)", "ct_err:expr_call_argtypes:is_signer:integer")
        chk("is_signer('1234')", "ct_err:expr_call_argtypes:is_signer:text")
        chk("is_signer(x'12', x'34')", "ct_err:expr_call_argtypes:is_signer:byte_array,byte_array")
    }

    @Test fun testTypeOf() {
        tst.strictToString = false
        tst.defs = listOf("class user { name: text; }")

        chk("_typeOf(null)", "null")
        chk("_typeOf(true)", "boolean")
        chk("_typeOf(123)", "integer")
        chk("_typeOf('Hello')", "text")
        chk("_typeOf(range(10))", "range")
        chk("_typeOf((123,'Hello'))", "(integer,text)")
        chk("_typeOf(list<integer>())", "list<integer>")
        chk("_typeOf(set<integer>())", "set<integer>")
        chk("_typeOf(map<integer,text>())", "map<integer,text>")

        chk("1/0", "rt_err:expr_div_by_zero")
        chk("_typeOf(1/0)", "integer")

        chk("_typeOf(user @ {})", "user")
        chk("_typeOf(user @? {})", "user?")
        chk("_typeOf(user @* {})", "list<user>")
        chk("_typeOf(user @+ {})", "list<user>")
    }

    @Test fun testStrictStr() {
        tst.strictToString = false
        chk("123", "123")
        chk("_strictStr(123)", "int[123]")
        chk("_strictStr('Hello')", "text[Hello]")
        chk("_strictStr(true)", "boolean[true]")
        chk("_strictStr((123,'Hello'))", "(int[123],text[Hello])")
    }

    @Test fun testJsonStr() {
        chkEx("""{ val s = json('{  "x":5, "y" : 10  }'); return s.str(); }""", """text[{"x":5,"y":10}]""")
    }

    @Test fun testByteArrayConstructorText() {
        chk("byte_array('0123abcd')", "byte_array[0123abcd]")
        chk("byte_array('0123ABCD')", "byte_array[0123abcd]")
        chk("byte_array('')", "byte_array[]")
        chk("byte_array('0')", "rt_err:fn_bytearray_new_text:0")
        chk("byte_array('0g')", "rt_err:fn_bytearray_new_text:0g")
        chk("byte_array(123)", "ct_err:expr_call_argtypes:byte_array:integer")
    }

    @Test fun testByteArrayConstructorList() {
        chk("byte_array(list<integer>())", "byte_array[]")
        chk("byte_array([123])", "byte_array[7b]")
        chk("byte_array([18, 52, 171, 205])", "byte_array[1234abcd]")
        chk("byte_array([0, 255])", "byte_array[00ff]")

        chk("byte_array()", "ct_err:expr_call_argtypes:byte_array:")
        chk("byte_array(list<text>())", "ct_err:expr_call_argtypes:byte_array:list<text>")
        chk("byte_array(['Hello'])", "ct_err:expr_call_argtypes:byte_array:list<text>")
        chk("byte_array(set<integer>())", "ct_err:expr_call_argtypes:byte_array:set<integer>")
        chk("byte_array([-1])", "rt_err:fn_bytearray_new_list:-1")
        chk("byte_array([256])", "rt_err:fn_bytearray_new_list:256")
    }

    @Test fun testByteArrayEmpty() {
        chk("x''.empty()", "boolean[true]")
        chk("x'01'.empty()", "boolean[false]")
        chk("x'01234567'.empty()", "boolean[false]")
    }

    @Test fun testByteArraySize() {
        chk("x''.size()", "int[0]")
        chk("x'01'.size()", "int[1]")
        chk("x'ABCD'.size()", "int[2]")
        chk("x'0123ABCD'.size()", "int[4]")
    }

    @Test fun testByteArrayConcat() {
        chk("x'0123' + x'ABCD'", "byte_array[0123abcd]")
    }

    @Test fun testByteArraySubscript() {
        chk("x'0123ABCD'[0]", "int[1]")
        chk("x'0123ABCD'[1]", "int[35]")
        chk("x'0123ABCD'[2]", "int[171]")
        chk("x'0123ABCD'[3]", "int[205]")
        chk("x'0123ABCD'[4]", "rt_err:expr_bytearray_subscript_index:4:4")
        chk("x'0123ABCD'[-1]", "rt_err:expr_bytearray_subscript_index:4:-1")

        chkEx("{ val x = x'0123ABCD'; x[1] = 123; return x; }", "ct_err:expr_lookup_unmodifiable:byte_array")
    }

    @Test fun testByteArrayDecode() {
        chk("x''.decode()", "text[]")
        chk("x'48656c6c6f'.decode()", "text[Hello]")
        chk("x'd09fd180d0b8d0b2d0b5d182'.decode()", """text[\u041f\u0440\u0438\u0432\u0435\u0442]""")
        chk("x'fefeffff'.decode()", """text[\ufffd\ufffd\ufffd\ufffd]""")
    }

    @Test fun testByteArraySub() {
        chk("x'0123ABCD'.sub(0)", "byte_array[0123abcd]")
        chk("x'0123ABCD'.sub(2)", "byte_array[abcd]")
        chk("x'0123ABCD'.sub(3)", "byte_array[cd]")
        chk("x'0123ABCD'.sub(4)", "byte_array[]")
        chk("x'0123ABCD'.sub(5)", "rt_err:fn_bytearray_sub_range:4:5:4")
        chk("x'0123ABCD'.sub(-1)", "rt_err:fn_bytearray_sub_range:4:-1:4")
        chk("x'0123ABCD'.sub(1, 3)", "byte_array[23ab]")
        chk("x'0123ABCD'.sub(0, 4)", "byte_array[0123abcd]")
        chk("x'0123ABCD'.sub(1, 0)", "rt_err:fn_bytearray_sub_range:4:1:0")
        chk("x'0123ABCD'.sub(1, 5)", "rt_err:fn_bytearray_sub_range:4:1:5")
    }

    @Test fun testByteArrayToList() {
        chk("x''.toList()", "list<integer>[]")
        chk("x'1234abcd'.toList()", "list<integer>[int[18],int[52],int[171],int[205]]")
    }

    @Test fun testOpContextLastBlockTime() {
        tst.opContext = RtOpContext(12345, listOf())
        chkOp("print(op_context.last_block_time);", "")
        tst.chkStdout("12345")

        chkOp("val t: timestamp = op_context.last_block_time; print(t);", "") // Will fail when timestamp type becomes intependent.
        tst.chkStdout("12345")
        chkOp("val t: integer = op_context.last_block_time; print(t);", "")
        tst.chkStdout("12345")

        chkOpFull("function f(): timestamp = op_context.last_block_time; operation o() { print(f()); }", "")
        tst.chkStdout("12345")

        tst.opContext = null
        chk("op_context.last_block_time", "ct_err:op_ctx_noop")
        chkFull("function f(): timestamp = op_context.last_block_time; query q() = f();", listOf(),
                "rt_err:fn_last_block_time_noop")

        chk("op_context", "ct_err:unknown_name:op_context")
    }

    @Test fun testGtxValue() {
        chk("""GTXValue.fromJSON('{"x":123,"y":[4,5,6]}')""", """gtx[{"x":123,"y":[4,5,6]}]""")
        chk("""GTXValue.fromJSON(json('{"x":123,"y":[4,5,6]}'))""", """gtx[{"x":123,"y":[4,5,6]}]""")
        chk("GTXValue.fromBytes(x'a424302230080c0178a30302017b30160c0179a511300fa303020104a303020105a303020106')",
                """gtx[{"x":123,"y":[4,5,6]}]""")
        chk("GTXValue.fromBytes(x'a424302230080c0178a30302017b30160c0179a511300fa303020104a303020105a303020106').toJSON()",
                """json[{"x":123,"y":[4,5,6]}]""")
        chk("''+GTXValue.fromBytes(x'a424302230080c0178a30302017b30160c0179a511300fa303020104a303020105a303020106').toJSON()",
                """text[{"x":123,"y":[4,5,6]}]""")
        chk("""GTXValue.fromJSON('{"x":123,"y":[4,5,6]}').toBytes()""",
                "byte_array[a424302230080c0178a30302017b30160c0179a511300fa303020104a303020105a303020106]")
    }

    @Test fun testRecord() {
        tst.defs = listOf(
                "record foo { a: integer; b: text; }",
                "record bar { a: (x: integer, text); }",
                "record qaz { m: map<integer,text>; }"
        )

        chk("foo(123,'Hello').toGTXValue()", """gtx[[123,"Hello"]]""")
        chk("foo(123,'Hello').toPrettyGTXValue()", """gtx[{"a":123,"b":"Hello"}]""")
        chk("foo(123,'Hello').toBytes()", "byte_array[a510300ea30302017ba2070c0548656c6c6f]")
        chk("foo.fromGTXValue(GTXValue.fromBytes(x'a510300ea30302017ba2070c0548656c6c6f'))", "foo[a=int[123],b=text[Hello]]")
        chk("foo.fromPrettyGTXValue(GTXValue.fromBytes(x'a41a301830080c0161a30302017b300c0c0162a2070c0548656c6c6f'))",
                "foo[a=int[123],b=text[Hello]]")
        chk("foo.fromBytes(x'a510300ea30302017ba2070c0548656c6c6f')", "foo[a=int[123],b=text[Hello]]")

        chk("bar((x=123,'Hello')).toGTXValue()", """gtx[[[123,"Hello"]]]""")
        chk("bar((x=123,'Hello')).toPrettyGTXValue()", "ct_err:fn_record_invalid:bar:bar.toPrettyGTXValue")
        chk("bar((x=123,'Hello')).toBytes()", "byte_array[a5143012a510300ea30302017ba2070c0548656c6c6f]")
        chk("bar.fromGTXValue(GTXValue.fromBytes(x'a5143012a510300ea30302017ba2070c0548656c6c6f'))",
                "bar[a=(x=int[123],text[Hello])]")
        chk("bar.fromPrettyGTXValue(GTXValue.fromBytes(x''))", "ct_err:fn_record_invalid:bar:fromPrettyGTXValue")
        chk("bar.fromBytes(x'a5143012a510300ea30302017ba2070c0548656c6c6f')", "bar[a=(x=int[123],text[Hello])]")

        chk("qaz([123:'Hello']).toGTXValue()", "ct_err:fn_record_invalid:qaz:qaz.toGTXValue")
        chk("qaz([123:'Hello']).toPrettyGTXValue()", "ct_err:fn_record_invalid:qaz:qaz.toPrettyGTXValue")
        chk("qaz([123:'Hello']).toBytes()", "ct_err:fn_record_invalid:qaz:qaz.toBytes")
        chk("qaz.fromGTXValue(GTXValue.fromBytes(x''))", "ct_err:fn_record_invalid:qaz:fromGTXValue")
        chk("qaz.fromPrettyGTXValue(GTXValue.fromBytes(x''))", "ct_err:fn_record_invalid:qaz:fromPrettyGTXValue")
        chk("qaz.fromBytes(x'')", "ct_err:fn_record_invalid:qaz:fromBytes")
    }
}
