package net.postchain.rell.lib

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LibGtxTxBlockTest: BaseRellTest(false) {
    @Test fun testTx() {
        def("operation foo(x: integer, y: text){}")
        def("operation bar(p: text, q: integer){}")

        chk("rell.gtx.tx()", "rell.gtx.tx[]")
        chk("rell.gtx.tx(list<operation>())", "rell.gtx.tx[]")
        chk("rell.gtx.tx(foo(123,'Hello'))", "rell.gtx.tx[op[foo(int[123],text[Hello])]]")
        chk("rell.gtx.tx(foo(123,'Hello').to_operation())", "rell.gtx.tx[op[foo(int[123],text[Hello])]]")
        chk("rell.gtx.tx([foo(123,'Hello')])", "rell.gtx.tx[op[foo(int[123],text[Hello])]]")
        chk("rell.gtx.tx([foo(123,'Hello').to_operation()])", "rell.gtx.tx[op[foo(int[123],text[Hello])]]")
        chk("rell.gtx.tx([foo(123,'Hello').to_operation(),bar('Bye',456).to_operation()])",
                "rell.gtx.tx[op[foo(int[123],text[Hello])],op[bar(text[Bye],int[456])]]")
        chk("rell.gtx.tx(foo(123,'Hello'),bar('Bye',456))",
                "rell.gtx.tx[op[foo(int[123],text[Hello])],op[bar(text[Bye],int[456])]]")
        chk("rell.gtx.tx(foo(123,'Hello').to_operation(),bar('Bye',456).to_operation())",
                "rell.gtx.tx[op[foo(int[123],text[Hello])],op[bar(text[Bye],int[456])]]")

        chk("_type_of(rell.gtx.tx())", "text[rell.gtx.tx]")
        chkEx("{ val x: rell.gtx.tx = rell.gtx.tx(); return x; }", "rell.gtx.tx[]")
    }

    @Test fun testBlock() {
        def("operation foo(x: integer, y: text){}")

        chk("rell.gtx.block()", "rell.gtx.block[]")
        chk("rell.gtx.block(list<rell.gtx.tx>())", "rell.gtx.block[]")
        chk("rell.gtx.block(rell.gtx.tx(foo(123,'Hello')))", "rell.gtx.block[rell.gtx.tx[op[foo(int[123],text[Hello])]]]")
        chk("rell.gtx.block(rell.gtx.tx(foo(123,'Hello').to_operation()))", "rell.gtx.block[rell.gtx.tx[op[foo(int[123],text[Hello])]]]")
        chk("rell.gtx.block([rell.gtx.tx(foo(123,'Hello'))])", "rell.gtx.block[rell.gtx.tx[op[foo(int[123],text[Hello])]]]")
        chk("rell.gtx.block([rell.gtx.tx(foo(123,'Hello').to_operation())])", "rell.gtx.block[rell.gtx.tx[op[foo(int[123],text[Hello])]]]")
        chk("rell.gtx.block(foo(123,'Hello'))", "ct_err:expr_call_argtypes:block:struct<foo>")
        chk("rell.gtx.block(foo(123,'Hello').to_operation())", "ct_err:expr_call_argtypes:block:operation")

        chk("_type_of(rell.gtx.block())", "text[rell.gtx.block]")
        chkEx("{ val x: rell.gtx.block = rell.gtx.block(); return x; }", "rell.gtx.block[]")
    }
}
