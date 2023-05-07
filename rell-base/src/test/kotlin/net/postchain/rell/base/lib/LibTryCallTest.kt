/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib

import net.postchain.rell.base.testutils.BaseRellTest
import org.junit.Test

class LibTryCallTest: BaseRellTest(false) {
    @Test fun testOneArgUnit() {
        def("function f(x: integer) { require(x > 0); }")
        chk("_type_of(try_call(f(1, *)))", "text[boolean]")
        chk("try_call(f(1, *))", "boolean[true]")
        chk("try_call(f(-1, *))", "boolean[false]")
    }

    @Test fun testOneArgValue() {
        def("function f(x: integer) { require(x > 0); return x * x; }")
        chk("_type_of(try_call(f(1, *)))", "text[integer?]")
        chk("try_call(f(5, *))", "int[25]")
        chk("try_call(f(-5, *))", "null")
    }

    @Test fun testTwoArgsUnit() {
        def("function f(x: integer) { require(x > 0); }")
        chk("try_call(f(1, *), true)", "ct_err:expr_call_argtypes:[try_call]:()->unit,boolean")
        chk("try_call(f(1, *), 0)", "ct_err:expr_call_argtypes:[try_call]:()->unit,integer")
        chk("try_call(f(1, *), null)", "ct_err:expr_call_argtypes:[try_call]:()->unit,null")
    }

    @Test fun testTwoArgsValue() {
        def("function f(x: integer) { require(x > 0); return x * x; }")

        chk("_type_of(try_call(f(1, *), 123))", "text[integer]")
        chk("try_call(f(5, *), 123)", "int[25]")
        chk("try_call(f(-5, *), 123)", "int[123]")

        chk("_type_of(try_call(f(1, *), null))", "text[integer?]")
        chk("try_call(f(5, *), null)", "int[25]")
        chk("try_call(f(-5, *), null)", "null")

        chk("try_call(f(5, *), 123L)", "ct_err:expr_call_argtypes:[try_call]:()->integer,big_integer")
    }

    @Test fun testTwoArgsEvaluation() {
        def("function f(x: integer) { print('f:' + x); require(x > 0); return x * x; }")
        def("function g(x: integer) { print('g:' + x); return x; }")

        chk("try_call(f(5, *), g(123))", "int[25]")
        chkOut("f:5")

        chk("try_call(f(-5, *), g(123))", "int[123]")
        chkOut("f:-5", "g:123")
    }

    @Test fun testNullableValue() {
        def("function f(x: integer): integer? { require(x > 0); return null; }")

        chk("_type_of(try_call(f(1, *)))", "text[integer?]")
        chk("try_call(f(5, *))", "null")
        chk("try_call(f(-5, *))", "null")

        chk("_type_of(try_call(f(1, *), 123))", "text[integer?]")
        chk("try_call(f(5, *), 123)", "null")
        chk("try_call(f(-5, *), 123)", "int[123]")

        chk("_type_of(try_call(f(1, *), null))", "text[integer?]")
        chk("try_call(f(5, *), null)", "null")
        chk("try_call(f(-5, *), null)", "null")
    }

    @Test fun testWrongArity() {
        def("function f(x: integer, y: integer, z: integer): integer { require(x + y + z > 0); return x * y * z; }")
        chk("try_call(f(*))", "ct_err:expr_call_argtypes:[try_call]:(integer,integer,integer)->integer")
        chk("try_call(f(1, *))", "ct_err:expr_call_argtypes:[try_call]:(integer,integer)->integer")
        chk("try_call(f(1, 2, *))", "ct_err:expr_call_argtypes:[try_call]:(integer)->integer")
        chk("try_call(f(1, 2, 3, *))", "int[6]")
        chk("try_call(f(*), -5)", "ct_err:expr_call_argtypes:[try_call]:(integer,integer,integer)->integer,integer")
        chk("try_call(f(1, *), -5)", "ct_err:expr_call_argtypes:[try_call]:(integer,integer)->integer,integer")
        chk("try_call(f(1, 2, *), -5)", "ct_err:expr_call_argtypes:[try_call]:(integer)->integer,integer")
        chk("try_call(f(1, 2, 3, *), -5)", "int[6]")
    }

    @Test fun testPopularFunctions() {
        chk("_type_of(try_call(decimal.from_text('hello', *)))", "text[decimal?]")
        chk("try_call(decimal.from_text('hello', *))", "null")
        chk("try_call(decimal.from_text('12345', *))", "dec[12345]")

        chk("_type_of(try_call(gtv.from_bytes(x'', *)))", "text[gtv?]")
        chk("try_call(gtv.from_bytes(x'', *))", "null")
        chk("try_call(gtv.from_bytes(x'a30302017b', *))", "gtv[123]")

        chk("_type_of(try_call(byte_array.from_hex('xyz', *)))", "text[byte_array?]")
        chk("try_call(byte_array.from_hex('xyz', *))", "null")
        chk("try_call(byte_array.from_hex('1234', *))", "byte_array[1234]")

        chk("_type_of(try_call(integer.from_gtv(gtv.from_json('123'), *)))", "text[integer?]")
        chk("try_call(integer.from_gtv(gtv.from_json('123'), *))", "int[123]")
        chk("try_call(integer.from_gtv(gtv.from_json('[]'), *))", "null")
    }

    @Test fun testAssertFunction() {
        tst.testLib = true
        def("function f(x: integer): integer { assert_true(x > 0); return x * x; }")
        chk("try_call(f(5, *))", "int[25]")
        chk("try_call(f(-5, *))", "null")
    }

    @Test fun testComplexWhat() {
        tstCtx.useSql = true
        def("function f(x: integer) { require(x > 0); return x * x; }")
        def("entity data { value: integer; }")
        insert("c0.data", "value", "1,123")

        //TODO support this
        chk("data @ {} ( try_call(f(5, *), .value) )", "ct_err:[expr_call_nosql:try_call][expr_nosql:()->integer]")
        chk("data @ {} ( try_call(f(-5, *), .value) )", "ct_err:[expr_call_nosql:try_call][expr_nosql:()->integer]")
    }
}
