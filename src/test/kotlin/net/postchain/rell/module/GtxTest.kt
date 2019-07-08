package net.postchain.rell.module

import net.postchain.rell.test.BaseGtxTest
import org.junit.Test

class GtxTest : BaseGtxTest() {
    @Test fun testObject() {
        def("object foo { x: integer = 123; s: text = 'Hello'; }")
        chk("foo.x", "123")
        chk("foo.s", "'Hello'")
    }

    @Test fun testInclude() {
        tst.file("foo.rell", "function f(): integer = 123;")
        def("namespace foo { include 'foo'; }")
        chk("foo.f()", "123")
    }

    @Test fun testNamespaceOperation() {
        def("namespace foo { operation bar() {print('Hello');} }")
        chkCallOperation("foo.bar", listOf())
        chkStdout("Hello")
    }

    @Test fun testNamespaceQuery() {
        def("namespace foo { query bar() = 123; }")
        chkCallQuery("foo.bar", "", "123")
    }
}
