/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib

import net.postchain.rell.base.runtime.utils.RellInterpreterCrashException
import net.postchain.rell.base.testutils.BaseRellTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class LibTest: BaseRellTest(false) {
    @Test fun testAbs() {
        chk("abs(a)", 0, "int[0]")
        chk("abs(a)", -123, "int[123]")
        chk("abs(a)", 123, "int[123]")
        chk("abs('Hello')", "ct_err:expr_call_argtypes:[abs]:text")
        chk("abs()", "ct_err:expr_call_argtypes:[abs]:")
        chk("abs(1, 2)", "ct_err:expr_call_argtypes:[abs]:integer,integer")
    }

    @Test fun testMinMax() {
        chk("min(a, b)", 100, 200, "int[100]")
        chk("min(a, b)", 200, 100, "int[100]")
        chk("max(a, b)", 100, 200, "int[200]")
        chk("max(a, b)", 200, 100, "int[200]")
    }

    @Test fun testPrint() {
        chkEx("{ print('Hello'); return 123; }", "int[123]")
        chkOut("Hello")
        chkLog()

        chkEx("{ print(12345); return 123; }", "int[123]")
        chkOut("12345")
        chkLog()

        chkEx("{ print(1, 2, 3, 4, 5); return 123; }", "int[123]")
        chkOut("1 2 3 4 5")
        chkLog()

        chkEx("{ print(); return 123; }", "int[123]")
        chkOut("")
        chkLog()
    }

    @Test fun testLog() {
        def("function f() { log('this is f()'); }")

        chkEx("{ log('Hello'); return 123; }", "int[123]")
        chkLog("[:q(main.rell:2)] Hello")
        chkOut()

        chkEx("{ log(12345); return 123; }", "int[123]")
        chkLog("[:q(main.rell:2)] 12345")
        chkOut()

        chkEx("{ log(1, 2, 3, 4, 5); return 123; }", "int[123]")
        chkLog("[:q(main.rell:2)] 1 2 3 4 5")
        chkOut()

        chkEx("{ log(); return 123; }", "int[123]")
        chkOut()
        chkLog("[:q(main.rell:2)]")

        chkEx("{\n    log('Hello'); log('World');\n    log('Bye');\n    return 123;\n}", "int[123]")
        chkOut()
        chkLog("[:q(main.rell:3)] Hello", "[:q(main.rell:3)] World", "[:q(main.rell:4)] Bye")

        chkEx("{ f(); return 0; }", "int[0]")
        chkOut()
        chkLog("[:f(main.rell:1)] this is f()")
    }

    @Test fun testTypeOf() {
        tst.strictToString = false
        def("entity user { name: text; }")

        chk("_type_of(null)", "null")
        chk("_type_of(true)", "boolean")
        chk("_type_of(123)", "integer")
        chk("_type_of('Hello')", "text")
        chk("_type_of(range(10))", "range")
        chk("_type_of((123,'Hello'))", "(integer,text)")
        chk("_type_of(list<integer>())", "list<integer>")
        chk("_type_of(set<integer>())", "set<integer>")
        chk("_type_of(map<integer,text>())", "map<integer,text>")

        chk("1/0", "rt_err:expr:/:div0:1")
        chk("_type_of(1/0)", "integer")

        chk("_type_of(user @ {})", "user")
        chk("_type_of(user @? {})", "user?")
        chk("_type_of(user @* {})", "list<user>")
        chk("_type_of(user @+ {})", "list<user>")

        // Side effect.
        chkEx("{ val s = set([123]); val t = _type_of(s.add(456)); return (s, t); }", "([123],boolean)")
    }

    @Test fun testTypeOfInAtExpr() {
        tstCtx.useSql = true
        def("entity user { name: text; }")
        insert("c0.user", "name", "1,'Bob'")
        chk("user @{} ( _type_of(.name) )", "text[text]")
        chk("user @{} ( _type_of(.name).matches('text') )", "boolean[true]")
        chk("user @{} ( .name.matches('Bob') )", "boolean[true]")
    }

    @Test fun testStrictStr() {
        tst.strictToString = false
        chk("123", "123")
        chk("_strict_str(123)", "int[123]")
        chk("_strict_str('Hello')", "text[Hello]")
        chk("_strict_str(true)", "boolean[true]")
        chk("_strict_str((123,'Hello'))", "(int[123],text[Hello])")
    }

    @Test fun testNullable() {
        tst.strictToString = false
        chk("_type_of(123)", "integer")
        chk("_type_of(_nullable(123))", "integer?")
    }

    @Test fun testJsonStr() {
        chkEx("""{ val s = json('{  "x":5, "y" : 10  }'); return s.str(); }""", """text[{"x":5,"y":10}]""")
    }

    @Test fun testGtv() {
        chk("""gtv.from_json('{"x":123,"y":[4,5,6]}')""", """gtv[{"x":123,"y":[4,5,6]}]""")
        chk("""gtv.from_json(json('{"x":123,"y":[4,5,6]}'))""", """gtv[{"x":123,"y":[4,5,6]}]""")
        chk("gtv.from_bytes(x'a424302230080c0178a30302017b30160c0179a511300fa303020104a303020105a303020106')",
                """gtv[{"x":123,"y":[4,5,6]}]""")
        chk("gtv.from_bytes(x'a424302230080c0178a30302017b30160c0179a511300fa303020104a303020105a303020106').to_json()",
                """json[{"x":123,"y":[4,5,6]}]""")
        chk("''+gtv.from_bytes(x'a424302230080c0178a30302017b30160c0179a511300fa303020104a303020105a303020106').to_json()",
                """text[{"x":123,"y":[4,5,6]}]""")
        chk("""gtv.from_json('{"x":123,"y":[4,5,6]}').to_bytes()""",
                "byte_array[a424302230080c0178a30302017b30160c0179a511300fa303020104a303020105a303020106]")
    }

    @Test fun testStruct() {
        def("struct foo { a: integer; b: text; }")
        def("struct bar { a: (x: integer, text); }")
        def("struct qaz { m: map<integer,text>; }")

        chk("foo(123,'Hello').to_gtv()", """gtv[[123,"Hello"]]""")
        chk("foo(123,'Hello').to_gtv_pretty()", """gtv[{"a":123,"b":"Hello"}]""")
        chk("foo(123,'Hello').to_bytes()", "byte_array[a510300ea30302017ba2070c0548656c6c6f]")
        chk("foo.from_gtv(gtv.from_bytes(x'a510300ea30302017ba2070c0548656c6c6f'))", "foo[a=int[123],b=text[Hello]]")
        chk("foo.from_gtv_pretty(gtv.from_bytes(x'a41a301830080c0161a30302017b300c0c0162a2070c0548656c6c6f'))",
                "foo[a=int[123],b=text[Hello]]")
        chk("foo.from_bytes(x'a510300ea30302017ba2070c0548656c6c6f')", "foo[a=int[123],b=text[Hello]]")

        chk("bar((x=123,'Hello')).to_gtv()", """gtv[[[123,"Hello"]]]""")
        chk("bar((x=123,'Hello')).to_gtv_pretty()", """gtv[{"a":[123,"Hello"]}]""")
        chk("bar((x=123,'Hello')).to_bytes()", "byte_array[a5143012a510300ea30302017ba2070c0548656c6c6f]")
        chk("bar.from_gtv(gtv.from_bytes(x'a5143012a510300ea30302017ba2070c0548656c6c6f'))",
                "bar[a=(x=int[123],text[Hello])]")
        chk("bar((x=123,'Hello')).to_gtv_pretty().to_bytes()", "byte_array[a419301730150c0161a510300ea30302017ba2070c0548656c6c6f]")
        chk("bar.from_gtv_pretty(gtv.from_bytes(x'a419301730150c0161a510300ea30302017ba2070c0548656c6c6f'))",
                "bar[a=(x=int[123],text[Hello])]")
        chk("bar.from_bytes(x'a5143012a510300ea30302017ba2070c0548656c6c6f')", "bar[a=(x=int[123],text[Hello])]")

        chk("qaz([123:'Hello']).to_gtv()", """gtv[[[[123,"Hello"]]]]""")
        chk("qaz([123:'Hello']).to_gtv_pretty()", """gtv[{"m":[[123,"Hello"]]}]""")
        chk("qaz([123:'Hello']).to_bytes()", "byte_array[a5183016a5143012a510300ea30302017ba2070c0548656c6c6f]")
        chk("qaz([123:'Hello']).to_gtv_pretty().to_bytes()",
                "byte_array[a41d301b30190c016da5143012a510300ea30302017ba2070c0548656c6c6f]")
        chk("qaz.from_gtv(gtv.from_bytes(x'a5183016a5143012a510300ea30302017ba2070c0548656c6c6f'))",
                "qaz[m=map<integer,text>[int[123]=text[Hello]]]")
        chk("qaz.from_gtv_pretty(gtv.from_bytes(x'a41d301b30190c016da5143012a510300ea30302017ba2070c0548656c6c6f'))",
                "qaz[m=map<integer,text>[int[123]=text[Hello]]]")
        chk("qaz.from_bytes(x'a5183016a5143012a510300ea30302017ba2070c0548656c6c6f')",
                "qaz[m=map<integer,text>[int[123]=text[Hello]]]")
    }

    @Test fun testExists() {
        chkEmptyExists("exists", true)
    }

    @Test fun testEmpty() {
        chkEmptyExists("empty", false)
    }

    private fun chkEmptyExists(f: String, exists: Boolean) {
        def("function zlist(l: list<integer>?): list<integer>? = l;")
        def("function zmap(m: map<integer, text>?): map<integer, text>? = m;")

        chkEx("{ var x: integer? = _nullable(123); return $f(x); }", "boolean[${exists}]")
        chkEx("{ var x: integer? = null; return $f(x); }", "boolean[${!exists}]")

        chk("$f([123])", "boolean[${exists}]")
        chk("$f(zlist([123]))", "boolean[${exists}]")
        chk("$f(list<integer>())", "boolean[${!exists}]")
        chk("$f(zlist(list<integer>()))", "boolean[${!exists}]")
        chk("$f(zlist(null))", "boolean[${!exists}]")

        chk("$f([123 : 'Hello'])", "boolean[${exists}]")
        chk("$f(zmap([123 : 'Hello']))", "boolean[${exists}]")
        chk("$f(map<integer,text>())", "boolean[${!exists}]")
        chk("$f(zmap(map<integer,text>()))", "boolean[${!exists}]")
        chk("$f(zmap(null))", "boolean[${!exists}]")

        chk("$f(123)", "ct_err:expr_call_argtypes:[$f]:integer")
        chk("$f(false)", "ct_err:expr_call_argtypes:[$f]:boolean")
        chk("$f('Hello')", "ct_err:expr_call_argtypes:[$f]:text")
        chk("$f(null)", "ct_err:expr_call_argtypes:[$f]:null")
    }

    @Test fun testDeprecatedError() {
        tst.deprecatedError = true

        chkCompile("function f(v: GTXValue){}", "ct_err:deprecated:TYPE:GTXValue:gtv")
        chkCompile("function f(v: list<GTXValue>){}", "ct_err:deprecated:TYPE:GTXValue:gtv")
        chkCompile("struct rec { v: GTXValue; }", "ct_err:deprecated:TYPE:GTXValue:gtv")
        chkCompile("struct rec { v: list<GTXValue>; }", "ct_err:deprecated:TYPE:GTXValue:gtv")
        chkCompile("struct rec { v: map<text,GTXValue>; }", "ct_err:deprecated:TYPE:GTXValue:gtv")
        chkCompile("struct rec { v: map<text,list<GTXValue?>>?; }", "ct_err:deprecated:TYPE:GTXValue:gtv")

        chkCompile("function f() { GTXValue.from_bytes(x''); }", "ct_err:deprecated:TYPE:GTXValue:gtv")
        chkCompile("function f() { GTXValue.from_json(''); }", "ct_err:deprecated:TYPE:GTXValue:gtv")
    }

    @Test fun testDeprecatedDefaultMode() {
        chkCompile("function f(v: GTXValue){}", "ct_err:deprecated:TYPE:GTXValue:gtv")
        chkCompile("struct rec { v: list<GTXValue>; }", "ct_err:deprecated:TYPE:GTXValue:gtv")
        chkCompile("function f() { GTXValue.from_bytes(x''); }", "ct_err:deprecated:TYPE:GTXValue:gtv")

        chkWarn()
        chkFn("= is_signer(x'1234');", "boolean[false]")
        chkWarn("deprecated:FUNCTION:is_signer:op_context.is_signer")

        chkFn("= op_context.is_signer(x'1234');", "boolean[false]")
        chkWarn()
    }

    @Test fun testDeprecatedFunctions() {
        tst.deprecatedError = true

        chkCompile("function f(x: integer?) { requireNotEmpty(x); }",
                "ct_err:deprecated:FUNCTION:requireNotEmpty:require_not_empty")

        chk("empty(_nullable_int(123))", "boolean[false]")

        chk("byte_array([1,2,3,4])", "ct_err:deprecated:FUNCTION:byte_array:byte_array.from_list")
        chk("byte_array('1234')", "byte_array[1234]")
        chk("x'1234'.len()", "ct_err:deprecated:FUNCTION:byte_array.len:size")
        chk("x'1234'.decode()", "ct_err:deprecated:FUNCTION:byte_array.decode:text.from_bytes")
        chk("x'1234'.toList()", "ct_err:deprecated:FUNCTION:byte_array.toList:to_list")

        chk("(123).hex()", "ct_err:deprecated:FUNCTION:integer.hex:to_hex")
        chk("integer.parseHex('1234')", "ct_err:deprecated:FUNCTION:integer.parseHex:from_hex")

        chk("'Hello'.len()", "ct_err:deprecated:FUNCTION:text.len:size")
        chk("'Hello'.upperCase()", "ct_err:deprecated:FUNCTION:text.upperCase:upper_case")
        chk("'Hello'.lowerCase()", "ct_err:deprecated:FUNCTION:text.lowerCase:lower_case")
        chk("'Hello'.compareTo('Bye')", "ct_err:deprecated:FUNCTION:text.compareTo:compare_to")
        chk("'Hello'.startsWith('Hell')", "ct_err:deprecated:FUNCTION:text.startsWith:starts_with")
        chk("'Hello'.endsWith('Hell')", "ct_err:deprecated:FUNCTION:text.endsWith:ends_with")
        chk("'Hello'.charAt(3)", "ct_err:deprecated:FUNCTION:text.charAt:char_at")
        chk("'Hello'.indexOf('ll')", "ct_err:deprecated:FUNCTION:text.indexOf:index_of")
        chk("'Hello'.lastIndexOf('ll')", "ct_err:deprecated:FUNCTION:text.lastIndexOf:last_index_of")
        chk("'Hello'.encode()", "ct_err:deprecated:FUNCTION:text.encode:to_bytes")

        chk("[1,2,3].indexOf(1)", "ct_err:deprecated:FUNCTION:list<integer>.indexOf:index_of")
        chk("[1,2,3].removeAt(1)", "ct_err:deprecated:FUNCTION:list<integer>.removeAt:remove_at")
        chk("[1,2,3].containsAll([1,3])", "ct_err:deprecated:FUNCTION:list<integer>.containsAll:contains_all")
        chk("[1,2,3].removeAll([1,2])", "ct_err:deprecated:FUNCTION:list<integer>.removeAll:remove_all")
        chk("[1,2,3].addAll([4,5,6])", "ct_err:deprecated:FUNCTION:list<integer>.addAll:add_all")
        chk("[1,2,3].len()", "ct_err:deprecated:FUNCTION:list<integer>.len:size")
        chk("[1,2,3]._set(0, 1)", "ct_err:deprecated:FUNCTION:list<integer>._set:set")

        chk("set([1,2,3]).containsAll([1,3])", "ct_err:deprecated:FUNCTION:set<integer>.containsAll:contains_all")
        chk("set([1,2,3]).removeAll([1,2])", "ct_err:deprecated:FUNCTION:set<integer>.removeAll:remove_all")
        chk("set([1,2,3]).addAll([4,5,6])", "ct_err:deprecated:FUNCTION:set<integer>.addAll:add_all")
        chk("set([1,2,3]).len()", "ct_err:deprecated:FUNCTION:set<integer>.len:size")

        chk("[123:'Hello'].len()", "ct_err:deprecated:FUNCTION:map<integer,text>.len:size")
        chkEx("{ [123:'Hello'].putAll([456:'Bye']); return 0; }", "ct_err:deprecated:FUNCTION:map<integer,text>.putAll:put_all")

        chk("(123).signum()", "ct_err:deprecated:FUNCTION:integer.signum:sign")
        chk("(123.0).signum()", "ct_err:deprecated:FUNCTION:decimal.signum:sign")
    }

    @Test fun testDeprecatedFunctionsGtv() {
        tst.deprecatedError = true
        def("struct rec { x: integer; }")

        chk("gtv.fromBytes(x'1234')", "ct_err:deprecated:FUNCTION:gtv.fromBytes:from_bytes")
        chk("gtv.fromJSON('{}')", "ct_err:deprecated:FUNCTION:gtv.fromJSON:from_json")
        chk("gtv.fromJSON(json('{}'))", "ct_err:deprecated:FUNCTION:gtv.fromJSON:from_json")
        chk("rec(5).to_gtv().toBytes()", "ct_err:deprecated:FUNCTION:gtv.toBytes:to_bytes")
        chk("rec(5).to_gtv().toJSON()", "ct_err:deprecated:FUNCTION:gtv.toJSON:to_json")

        chk("rec.fromBytes(x'1234')", "ct_err:deprecated:FUNCTION:rec.fromBytes:from_bytes")
        chk("rec.fromGTXValue(gtv.from_bytes(x'1234'))", "ct_err:deprecated:FUNCTION:rec.fromGTXValue:from_gtv")
        chk("rec.fromPrettyGTXValue(gtv.from_bytes(x'1234'))", "ct_err:deprecated:FUNCTION:rec.fromPrettyGTXValue:from_gtv_pretty")
        chk("rec(5).toBytes()", "ct_err:deprecated:FUNCTION:rec.toBytes:to_bytes")
        chk("rec(5).toGTXValue()", "ct_err:deprecated:FUNCTION:rec.toGTXValue:to_gtv")
        chk("rec(5).toPrettyGTXValue()", "ct_err:deprecated:FUNCTION:rec.toPrettyGTXValue:to_gtv_pretty")
    }

    @Test fun testRellNamespaceConflict() {
        chkCompile("function rell() = 0;", "ct_err:name_conflict:sys:rell:NAMESPACE")
        chkCompile("namespace rell {}", "ct_err:name_conflict:sys:rell:NAMESPACE")
    }

    @Test fun testSysQueries() {
        chk("rell.get_rell_version()", "ct_err:unknown_name:[rell]:get_rell_version")
        chk("rell.get_app_structure()", "ct_err:unknown_name:[rell]:get_app_structure")
    }

    @Test fun testGtxOperationType() {
        chk("gtx_operation(name = 'foo', args = list<gtv>())", "gtx_operation[name=text[foo],args=list<gtv>[]]")
        chk("gtx_operation()", "ct_err:attr_missing:name,args")
        chkEx("{ var o: gtx_operation; o = gtx_operation('foo', ['Bob'.to_gtv()]); return o; }",
                """gtx_operation[name=text[foo],args=list<gtv>[gtv["Bob"]]]""")
    }

    @Test fun testGtxTransactionBodyType() {
        chk("gtx_transaction_body(blockchain_rid = x'1234', operations = list<gtx_operation>(), signers = list<gtv>())",
                "gtx_transaction_body[blockchain_rid=byte_array[1234],operations=list<gtx_operation>[],signers=list<gtv>[]]")
        chk("gtx_transaction_body()", "ct_err:attr_missing:blockchain_rid,operations,signers")
    }

    @Test fun testGtxTransactionType() {
        val expBody = "gtx_transaction_body[blockchain_rid=byte_array[1234],operations=list<gtx_operation>[],signers=list<gtv>[]]"
        chk("gtx_transaction(body = gtx_transaction_body(blockchain_rid = x'1234', operations = [], signers = []), signatures = [])",
                "gtx_transaction[body=$expBody,signatures=list<gtv>[]]")
        chk("gtx_transaction()", "ct_err:attr_missing:body,signatures")
    }

    @Test fun testHiddenLib() {
        chk("_type_of(123)", "text[integer]")
        chk("_nullable(123)", "int[123]")
        chk("_nullable_int(123)", "int[123]")
        chk("_crash('hello')", "ct_err:query_exprtype_unit")

        tst.hiddenLib = false
        chk("_type_of(123)", "ct_err:unknown_name:_type_of")
        chk("_nullable(123)", "ct_err:unknown_name:_nullable")
        chk("_nullable_int(123)", "ct_err:unknown_name:_nullable_int")
        chk("_crash('hello')", "ct_err:unknown_name:_crash")
    }

    @Test fun testCrash() {
        try {
            chkEx("{ _crash('hello'); return 0; }", "...")
            fail()
        } catch (e: RellInterpreterCrashException) {
            assertEquals("hello", e.message)
        }
    }
}
