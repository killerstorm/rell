package net.postchain.rell

import net.postchain.rell.test.RellCodeTester
import org.junit.Test

class AtExprGroupColTest: AtExprGroupBaseTest() {
    override fun impDefKw() = "struct"

    override fun impCreateObjs(t: RellCodeTester, name: String, vararg objs: String) {
        val values = objs.joinToString(", ") { "$name($it)" }
        t.def("function get_$name(): list<$name> = [$values];")
    }

    override fun impFrom(name: String) = "get_$name()"

    override fun impPlaceholder() = "$"

    @Test fun testTypeGroupCol() {
        def("struct const_struct { q: integer; }")

        chkTypeGroup("(integer,text)", "(123,'hi')", "(int[123],text[hi])")
        chkTypeGroup("const_struct", "const_struct(123)", "const_struct[q=int[123]]")

        chkTypeGroupNullable("boolean", "true", "boolean[true]")
        chkTypeGroupNullable("integer", "123", "int[123]")
        chkTypeGroupNullable("decimal", "123.456", "dec[123.456]")
        chkTypeGroupNullable("text", "'hi'", "text[hi]")
        chkTypeGroupNullable("byte_array", "x'beef'", "byte_array[beef]")
        chkTypeGroupNullable("rowid", "rowid.from_gtv(gtv.from_json('123'))", "rowid[123]")
        chkTypeGroupNullable("color", "color.green", "color[green]")
        chkTypeGroupNullable("const_struct", "const_struct(123)", "const_struct[q=int[123]]")
    }

    private fun chkTypeGroupNullable(type: String, value: String, exp: String) {
        chkTypeGroup("$type?", value, exp)
        chkTypeGroup("$type?", "null", "null")
    }

    @Test fun testTypeGroupFormalCol() {
        def("struct const_struct { q: integer; }")
        def("struct mut_struct { mutable q: integer; }")

        chkTypeGroupFormal("boolean?", "text[boolean?]")
        chkTypeGroupFormal("integer?", "text[integer?]")
        chkTypeGroupFormal("decimal?", "text[decimal?]")
        chkTypeGroupFormal("text?", "text[text?]")
        chkTypeGroupFormal("byte_array?", "text[byte_array?]")
        chkTypeGroupFormal("rowid?", "text[rowid?]")
        chkTypeGroupFormal("color?", "text[color?]")
        chkTypeGroupFormal("const_struct?", "text[const_struct?]")

        chkTypeGroupFormal("(integer,text)", "text[(integer,text)]")

        chkTypeGroupFormal("mut_struct", "ct_err:expr_at_group_type:mut_struct")
        chkTypeGroupFormal("list<integer>", "ct_err:expr_at_group_type:list<integer>")
        chkTypeGroupFormal("set<integer>", "ct_err:expr_at_group_type:set<integer>")
        chkTypeGroupFormal("map<integer,text>", "ct_err:expr_at_group_type:map<integer,text>")
    }

    @Test fun testTypeMinMaxCol() {
        initDataAllTypes()
        val from = impFrom("data")
        chk("$from @ {} ( @min $ph.b )", "boolean[false]")
        chk("$from @ {} ( @max $ph.b )", "boolean[true]")
        chk("$from @ {} ( @min $ph.ba )", "byte_array[beef]")
        chk("$from @ {} ( @max $ph.ba )", "byte_array[dead]")
    }
}
