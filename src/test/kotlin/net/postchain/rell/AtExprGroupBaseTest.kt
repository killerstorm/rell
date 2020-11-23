package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.RellCodeTester
import org.junit.Test

abstract class AtExprGroupBaseTest: BaseRellTest() {
    protected abstract fun impDefKw(): String
    protected abstract fun impCreateObjs(t: RellCodeTester, name: String, vararg objs: String)
    protected abstract fun impFrom(name: String): String

    private fun impCreateObjs(name: String, vararg objs: String) = impCreateObjs(tst, name, *objs)

    private fun initDataCountries() {
        tst.strictToString = false

        def("${impDefKw()} data { name; region: text; language: text; gdp: integer; }")

        impCreateObjs("data",
                "name = 'Germany', region = 'EMEA', language = 'German', gdp = 3863",
                "name = 'Austria', region = 'EMEA', language = 'German', gdp = 447",
                "name = 'United Kingdom', region = 'EMEA', language = 'English', gdp = 2743",
                "name = 'USA', region = 'AMER', language = 'English', gdp = 21439",
                "name = 'Mexico', region = 'AMER', language = 'Spanish', gdp = 1274",
                "name = 'China', region = 'APAC', language = 'Chinese', gdp = 14140"
        )
    }

    protected fun initDataAllTypes() {
        def("entity user { name; }")
        def("enum color { red, green, blue }")
        def("function int_to_rowid(i: integer) = rowid.from_gtv(gtv.from_json('' + i));")
        def("function int_to_user(i: integer) = user @ { .rowid == int_to_rowid(i) };")
        def("${impDefKw()} data { b: boolean; i: integer; d: decimal; t: text; ba: byte_array; r: rowid; e: color; w: user; }")

        insert("c0.user", "name", "500,'Bob'", "501,'Alice'")

        impCreateObjs("data",
                "b = false, i = 111, d = 67.89, t = 'abc', ba = x'beef', r = int_to_rowid(888), e = color.blue, w = int_to_user(500)",
                "b = true, i = 222, d = 98.76, t = 'def', ba = x'dead', r = int_to_rowid(777), e = color.green, w = int_to_user(501)"
        )
    }

    @Test fun testGroupConstant() {
        initDataCountries()
        val from = impFrom("data")
        chk("$from @* {} ( @group 123 )", "[123]")
        chk("$from @* {} ( @group 123, .region )", "ct_err:at:what:no_aggr:1")
        chk("$from @* {} ( @group 123, @sum 1 )", "[(123,6)]")
    }

    @Test fun testNoGroup() {
        initDataCountries()
        val from = impFrom("data")
        chk("$from @* {} ( @sum 0 )", "[0]")
        chk("$from @* {} ( @sum 1 )", "[6]")
        chk("$from @* {} ( @sum .gdp )", "[43906]")
        chk("$from @* {} ( @min 0, @max 0 )", "[(0,0)]")
        chk("$from @* {} ( @min .gdp, @max .gdp )", "[(447,21439)]")
    }

    @Test fun testNoRecords() {
        initDataCountries()
        val from = impFrom("data")
        chk("$from @* {} ( @sum .gdp, @min .gdp, @max .gdp )", "[(43906,447,21439)]")
        chk("$from @* { .region == 'NONE' } ( @group 123, @sum .gdp, @min .gdp, @max .gdp )", "[]")
        chk("$from @* { .region == 'NONE' } ( @sum .gdp, @min .gdp, @max .gdp )", "[(null,null,null)]")
    }

    @Test fun testTypeGroup() {
        chkTypeGroup("boolean", "true", "boolean[true]")
        chkTypeGroup("integer", "123", "int[123]")
        chkTypeGroup("decimal", "123.456", "dec[123.456]")
        chkTypeGroup("text", "'Hi'", "text[Hi]")
        chkTypeGroup("byte_array", "x'1c'", "byte_array[1c]")
        chkTypeGroup("rowid", "rowid.from_gtv(gtv.from_json('123'))", "rowid[123]")
        chkTypeGroup("color", "color.red", "color[red]")
    }

    protected fun chkTypeGroup(type: String, value: String, exp: String) {
        val t = RellCodeTester(tstCtx)
        tst.defs.forEach { t.def(it) }
        t.insert(tst.inserts)
        t.def("enum color { red, green, blue }")
        t.def("${impDefKw()} data { v: $type; }")
        impCreateObjs(t, "data", "v = $value")
        t.chkQuery("${impFrom("data")} @? {} ( @group .v )", exp)
    }

    @Test fun testTypeGroupFormal() {
        chkTypeGroupFormal("boolean", "text[boolean]")
        chkTypeGroupFormal("integer", "text[integer]")
        chkTypeGroupFormal("decimal", "text[decimal]")
        chkTypeGroupFormal("text", "text[text]")
        chkTypeGroupFormal("byte_array", "text[byte_array]")
        chkTypeGroupFormal("rowid", "text[rowid]")
        chkTypeGroupFormal("color", "text[color]")
    }

    protected fun chkTypeGroupFormal(type: String, exp: String) {
        val t = RellCodeTester(tstCtx)
        tst.defs.forEach { t.def(it) }
        t.def("${impDefKw()} data { v: $type; }")
        t.def("enum color { red, green, blue }")
        impCreateObjs(t, "data")
        t.chkQuery("_type_of(${impFrom("data")} @ {} ( @group .v ))", exp)
    }

    @Test fun testTypeSum() {
        initDataAllTypes()
        val from = impFrom("data")

        chk("$from @ {} ( @sum .i )", "int[333]")
        chk("$from @ {} ( @sum .d )", "dec[166.65]")

        chk("$from @ {} ( @sum .b )", "ct_err:at:what:aggr:bad_type:SUM:boolean")
        chk("$from @ {} ( @sum .t )", "ct_err:at:what:aggr:bad_type:SUM:text")
        chk("$from @ {} ( @sum .ba )", "ct_err:at:what:aggr:bad_type:SUM:byte_array")
        chk("$from @ {} ( @sum .r )", "ct_err:at:what:aggr:bad_type:SUM:rowid")
        chk("$from @ {} ( @sum .e )", "ct_err:at:what:aggr:bad_type:SUM:color")
        chk("$from @ {} ( @sum .w )", "ct_err:at:what:aggr:bad_type:SUM:user")
    }

    @Test fun testTypeMinMax() {
        initDataAllTypes()
        val from = impFrom("data")

        chk("$from @ {} ( @min .i, @max .i )", "(int[111],int[222])")
        chk("$from @ {} ( @min .d, @max .d )", "(dec[67.89],dec[98.76])")
        chk("$from @ {} ( @min .t, @max .t )", "(text[abc],text[def])")
        chk("$from @ {} ( @min .r, @max .r )", "(rowid[777],rowid[888])")
        chk("$from @ {} ( @min .e, @max .e )", "(color[green],color[blue])")
        chk("$from @ {} ( @min .w, @max .w )", "(user[500],user[501])")
    }

    @Test fun testTypeSumFormal() {
        initDataAllTypes()
        val from = impFrom("data")
        chk("_type_of($from @ {} ( @sum .i ))", "text[integer?]")
        chk("_type_of($from @ {} ( @sum .d ))", "text[decimal?]")
        chk("_type_of($from @ {} ( @omit @group 0, @sum .i ))", "text[integer]")
        chk("_type_of($from @ {} ( @omit @group 0, @sum .d ))", "text[decimal]")
    }

    @Test fun testTypeMinMaxFormal() {
        initDataAllTypes()
        chkTypeMinMaxFormal("min")
        chkTypeMinMaxFormal("max")
    }

    private fun chkTypeMinMaxFormal(ann: String) {
        val from = impFrom("data")

        chk("_type_of($from @ {} ( @$ann .i ))", "text[integer?]")
        chk("_type_of($from @ {} ( @$ann .d ))", "text[decimal?]")
        chk("_type_of($from @ {} ( @$ann .t ))", "text[text?]")
        chk("_type_of($from @ {} ( @$ann .r ))", "text[rowid?]")
        chk("_type_of($from @ {} ( @$ann .e ))", "text[color?]")
        chk("_type_of($from @ {} ( @$ann .w ))", "text[user?]")

        chk("_type_of($from @ {} ( @omit @group 0, @$ann .i ))", "text[integer]")
        chk("_type_of($from @ {} ( @omit @group 0, @$ann .d ))", "text[decimal]")
        chk("_type_of($from @ {} ( @omit @group 0, @$ann .t ))", "text[text]")
        chk("_type_of($from @ {} ( @omit @group 0, @$ann .r ))", "text[rowid]")
        chk("_type_of($from @ {} ( @omit @group 0, @$ann .e ))", "text[color]")
        chk("_type_of($from @ {} ( @omit @group 0, @$ann .w ))", "text[user]")
    }
}
