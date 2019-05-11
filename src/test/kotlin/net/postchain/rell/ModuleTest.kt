package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class ModuleTest: BaseRellTest() {
    @Test fun testForwardTypeReferenceFunction() {
        val code = """
            function f(x: foo): bar = bar(x);
            record foo { p: integer; }
            record bar { x: foo; }
            query q() = f(foo(123));
        """.trimIndent()
        chkQueryEx(code, "bar[x=foo[p=int[123]]]")
    }

    @Test fun testForwardTypeReferenceOperation() {
        val code = """
            operation o(x: foo) { print(_strict_str(bar(x))); }
            record foo { p: integer; }
            record bar { x: foo; }
        """.trimIndent()
        tst.chkOpGtvEx(code, listOf("""[123]"""), "OK")
        chkStdout("bar[x=foo[p=int[123]]]")
    }

    @Test fun testForwardTypeReferenceQuery() {
        val code = """
            query q(x: foo): bar = bar(x);
            record foo { p: integer; }
            record bar { x: foo; }
        """.trimIndent()
        tst.chkQueryGtvEx(code, listOf("""{"p":123}"""), "bar[x=foo[p=int[123]]]")
    }
}
