/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.lib

import net.postchain.rell.base.runtime.utils.RellInterpreterCrashException
import net.postchain.rell.base.testutils.BaseRellTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class LibHiddenTest: BaseRellTest(false) {
    @Test fun testBasic() {
        chk("_type_of(123)", "text[integer]")
        chk("_nullable(123)", "int[123]")
        chk("_nullable_int(123)", "int[123]")
        chk("_test.crash('hello')", "ct_err:query_exprtype_unit")

        tst.hiddenLib = false
        chk("_type_of(123)", "ct_err:unknown_name:_type_of")
        chk("_nullable(123)", "ct_err:unknown_name:_nullable")
        chk("_nullable_int(123)", "ct_err:unknown_name:_nullable_int")
        chk("_test.crash('hello')", "ct_err:unknown_name:_test")
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

    @Test fun testCrash() {
        try {
            chkEx("{ _test.crash('hello'); return 0; }", "...")
            fail()
        } catch (e: RellInterpreterCrashException) {
            assertEquals("hello", e.message)
        }
    }

    @Test fun testNop() {
        chk("_type_of(_nop(123))", "text[integer]")
        chk("_type_of(_nop(null))", "text[null]")

        chk("_nop(123)", "int[123]")
        chkOut()

        chk("_nop(true)", "boolean[true]")
        chkOut()
        chk("_nop('Hello')", "text[Hello]")
        chkOut()
        chk("_nop(null)", "null")
        chkOut()
    }

    @Test fun testNopPrint() {
        chk("_type_of(_nop_print(123))", "text[integer]")
        chk("_type_of(_nop_print(null))", "text[null]")

        chk("_nop_print(123)", "int[123]")
        chkOut("123")

        chk("_nop_print(true)", "boolean[true]")
        chkOut("true")

        chk("_nop_print('Hello')", "text[Hello]")
        chkOut("Hello")

        chk("_nop_print(null)", "null")
        chkOut("null")
    }

    @Test fun testNullable() {
        chk("_type_of(123)", "text[integer]")
        chk("_type_of(_nullable(123))", "text[integer?]")
        chk("_type_of(_nullable('Hello'))", "text[text?]")
        chk("_type_of(_nullable(true))", "text[boolean?]")
        chk("_type_of(_nullable(null))", "text[null]")
        chk("_type_of(_nullable(_nullable(123)))", "text[integer?]")

        chk("_nullable(123)", "int[123]")
        chk("_nullable('Hello')", "text[Hello]")
        chk("_nullable(true)", "boolean[true]")
        chk("_nullable(null)", "null")
    }

    @Test fun testNullableInt() {
        chk("_type_of(_nullable_int(123))", "text[integer?]")
        chk("_type_of(_nullable_int(null))", "text[integer?]")
        chk("_nullable_int(123)", "int[123]")
        chk("_nullable_int(null)", "null")
    }

    @Test fun testNullableText() {
        chk("_type_of(_nullable_text('Hello'))", "text[text?]")
        chk("_type_of(_nullable_text(null))", "text[text?]")
        chk("_nullable_text('Hello')", "text[Hello]")
        chk("_nullable_text(null)", "null")
    }
}
