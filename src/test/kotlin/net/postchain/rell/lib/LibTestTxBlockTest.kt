package net.postchain.rell.lib

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LibTestTxBlockTest: BaseRellTest(false) {
    @Test fun testTx() {
        def("operation foo(x: integer, y: text){}")
        def("operation bar(p: text, q: integer){}")

        chk("rell.test.tx()", "rell.test.tx[]")
        chk("rell.test.tx(list<operation>())", "rell.test.tx[]")
        chk("rell.test.tx(foo(123,'Hello'))", "rell.test.tx[op[foo(int[123],text[Hello])]]")
        chk("rell.test.tx([foo(123,'Hello')])", "rell.test.tx[op[foo(int[123],text[Hello])]]")
        chk("rell.test.tx([foo(123,'Hello'),bar('Bye',456)])", "rell.test.tx[op[foo(int[123],text[Hello])],op[bar(text[Bye],int[456])]]")
        chk("rell.test.tx(foo(123,'Hello'),bar('Bye',456))", "ct_err:expr_call_argtypes:tx:operation,operation")

        chk("_type_of(rell.test.tx())", "text[rell.test.tx]")
        chkEx("{ val x: rell.test.tx = rell.test.tx(); return x; }", "rell.test.tx[]")
    }

    @Test fun testBlock() {
        def("operation foo(x: integer, y: text){}")

        chk("rell.test.block()", "rell.test.block[]")
        chk("rell.test.block(list<rell.test.tx>())", "rell.test.block[]")
        chk("rell.test.block(rell.test.tx(foo(123,'Hello')))", "rell.test.block[rell.test.tx[op[foo(int[123],text[Hello])]]]")
        chk("rell.test.block([rell.test.tx(foo(123,'Hello'))])", "rell.test.block[rell.test.tx[op[foo(int[123],text[Hello])]]]")
        chk("rell.test.block(foo(123,'Hello'))", "ct_err:expr_call_argtypes:block:operation")

        chk("_type_of(rell.test.block())", "text[rell.test.block]")
        chkEx("{ val x: rell.test.block = rell.test.block(); return x; }", "rell.test.block[]")
    }
}
