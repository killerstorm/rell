package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class MirrorStructOperationTest: BaseRellTest(false) {
    @Test fun testConstructor() {
        def("operation new_user(name, rating: integer) {}")

        chk("_type_of(struct<new_user>('Bob', 123))", "text[struct<new_user>]")
        chk("struct<new_user>('Bob', 123)", "struct<new_user>[name=text[Bob],rating=int[123]]")
        chk("struct<new_user>(123, 'Bob')", "struct<new_user>[name=text[Bob],rating=int[123]]")

        chk("struct<new_user>()", "ct_err:attr_missing:name,rating")
        chk("struct<new_user>('Bob')", "ct_err:attr_missing:rating")
        chk("struct<new_user>(123)", "ct_err:attr_missing:name")
        chk("struct<new_user>(rating = 'Bob')", "ct_err:attr_bad_type:0:rating:integer:text")
        chk("struct<new_user>(name = 123)", "ct_err:attr_bad_type:0:name:text:integer")
    }

    @Test fun testConstructorDefaultValues() {
        def("operation new_user_1(name = 'Bob', rating: integer = 123) {}")
        def("operation new_user_2(name = 'Bob', rating: integer) {}")
        def("operation new_user_3(name, rating: integer = 123) {}")

        chk("struct<new_user_1>()", "struct<new_user_1>[name=text[Bob],rating=int[123]]")
        chk("struct<new_user_1>('Alice')", "struct<new_user_1>[name=text[Alice],rating=int[123]]")
        chk("struct<new_user_1>(456)", "struct<new_user_1>[name=text[Bob],rating=int[456]]")
        chk("struct<new_user_1>('Alice',456)", "struct<new_user_1>[name=text[Alice],rating=int[456]]")

        chk("struct<new_user_2>()", "ct_err:attr_missing:rating")
        chk("struct<new_user_2>('Alice')", "ct_err:attr_missing:rating")
        chk("struct<new_user_2>(123)", "struct<new_user_2>[name=text[Bob],rating=int[123]]")
        chk("struct<new_user_2>('Alice',123)", "struct<new_user_2>[name=text[Alice],rating=int[123]]")

        chk("struct<new_user_3>()", "ct_err:attr_missing:name")
        chk("struct<new_user_3>(456)", "ct_err:attr_missing:name")
        chk("struct<new_user_3>('Alice')", "struct<new_user_3>[name=text[Alice],rating=int[123]]")
        chk("struct<new_user_3>('Alice',456)", "struct<new_user_3>[name=text[Alice],rating=int[456]]")
    }

    @Test fun testAttributeRead() {
        def("operation new_user(name, rating: integer) {}")
        chk("struct<new_user>('Bob',123).name", "text[Bob]")
        chk("struct<new_user>('Bob',123).rating", "int[123]")
        chk("struct<new_user>('Bob',123).bad_name", "ct_err:unknown_member:[struct<new_user>]:bad_name")
    }

    @Test fun testAttributeWrite() {
        def("operation new_user(name, rating: integer) {}")
        val init = "val s = struct<new_user>('Bob',123);"
        chkEx("{ $init s.name = 'Alice'; return s; }", "ct_err:update_attr_not_mutable:name")
        chkEx("{ $init s.rating = 456; return s; }", "ct_err:update_attr_not_mutable:rating")
    }

    @Test fun testInstanceMemberFunctions() {
        def("operation new_user(name, rating: integer) {}")
        val expr = "struct<new_user>('Bob',123)"
        chk("$expr.to_gtv()", """gtv[["Bob",123]]""")
        chk("$expr.to_gtv_pretty()", """gtv[{"name":"Bob","rating":123}]""")
        chk("$expr.to_bytes()", "byte_array[a50e300ca2050c03426f62a30302017b]")
        chk("$expr.bad_name()", "ct_err:unknown_member:[struct<new_user>]:bad_name")
    }

    @Test fun testStaticMemberFunctions() {
        def("operation new_user(name, rating: integer) {}")
        val type = "struct<new_user>"
        chk("""$type.from_gtv(gtv.from_json('["Bob",123]'))""", "$type[name=text[Bob],rating=int[123]]")
        chk("""$type.from_gtv_pretty(gtv.from_json('{"name":"Bob","rating":123}'))""", "$type[name=text[Bob],rating=int[123]]")
        chk("$type.from_bytes(x'a50e300ca2050c03426f62a30302017b')", "$type[name=text[Bob],rating=int[123]]")
        chk("$type.bad_name()", "ct_err:unknown_name:$type.bad_name")
    }

    @Test fun testCycle() {
        def("operation op(a: integer, b: struct<op>?) {}")

        chk("struct<op>()", "ct_err:attr_missing:a,b")
        chk("struct<op>(a = 123, b = null)", "struct<op>[a=int[123],b=null]")
        chk("struct<op>(a = 123, b = struct<op>(a = 456, b = null))", "struct<op>[a=int[123],b=struct<op>[a=int[456],b=null]]")

        chkCompile("operation op2(a: integer, b: struct<op2>) {}", "OK")
    }

    @Test fun testToOperation() {
        def("operation new_user(name, rating: integer) {}")
        chk("struct<new_user>('Bob',123).to_operation()", "op[new_user(text[Bob],int[123])]")
    }
}
