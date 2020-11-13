package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.RellCodeTester
import org.junit.Test

abstract class AtExprGroupBaseTest: BaseRellTest() {
    protected val ph = impPlaceholder()

    protected abstract fun impDefKw(): String
    protected abstract fun impCreateObjs(t: RellCodeTester, name: String, vararg objs: String)
    protected abstract fun impFrom(name: String): String
    protected abstract fun impPlaceholder(): String

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
        chk("$from @* {} ( @group 123, $ph.region )", "ct_err:at:what:no_aggr:1")
        chk("$from @* {} ( @group 123, @sum 1 )", "[(123,6)]")
    }

    @Test fun testNoGroup() {
        initDataCountries()
        val from = impFrom("data")
        chk("$from @* {} ( @sum 0 )", "[0]")
        chk("$from @* {} ( @sum 1 )", "[6]")
        chk("$from @* {} ( @sum $ph.gdp )", "[43906]")
        chk("$from @* {} ( @min 0, @max 0 )", "[(0,0)]")
        chk("$from @* {} ( @min $ph.gdp, @max $ph.gdp )", "[(447,21439)]")
    }

    @Test fun testNoRecords() {
        initDataCountries()
        val from = impFrom("data")
        chk("$from @* {} ( @sum $ph.gdp, @min $ph.gdp, @max $ph.gdp )", "[(43906,447,21439)]")
        chk("$from @* { $ph.region == 'NONE' } ( @group 123, @sum $ph.gdp, @min $ph.gdp, @max $ph.gdp )", "[]")
        chk("$from @* { $ph.region == 'NONE' } ( @sum $ph.gdp, @min $ph.gdp, @max $ph.gdp )", "[(null,null,null)]")
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
        t.chkQuery("${impFrom("data")} @? {} ( @group $ph.v )", exp)
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
        t.chkQuery("_type_of(${impFrom("data")} @ {} ( @group $ph.v ))", exp)
    }

    @Test fun testTypeSum() {
        initDataAllTypes()
        val from = impFrom("data")

        chk("$from @ {} ( @sum $ph.i )", "int[333]")
        chk("$from @ {} ( @sum $ph.d )", "dec[166.65]")

        chk("$from @ {} ( @sum $ph.b )", "ct_err:at:what:aggr:bad_type:SUM:boolean")
        chk("$from @ {} ( @sum $ph.t )", "ct_err:at:what:aggr:bad_type:SUM:text")
        chk("$from @ {} ( @sum $ph.ba )", "ct_err:at:what:aggr:bad_type:SUM:byte_array")
        chk("$from @ {} ( @sum $ph.r )", "ct_err:at:what:aggr:bad_type:SUM:rowid")
        chk("$from @ {} ( @sum $ph.e )", "ct_err:at:what:aggr:bad_type:SUM:color")
        chk("$from @ {} ( @sum $ph.w )", "ct_err:at:what:aggr:bad_type:SUM:user")
    }

    @Test fun testTypeMinMax() {
        initDataAllTypes()
        val from = impFrom("data")

        chk("$from @ {} ( @min $ph.i, @max $ph.i )", "(int[111],int[222])")
        chk("$from @ {} ( @min $ph.d, @max $ph.d )", "(dec[67.89],dec[98.76])")
        chk("$from @ {} ( @min $ph.t, @max $ph.t )", "(text[abc],text[def])")
        chk("$from @ {} ( @min $ph.r, @max $ph.r )", "(rowid[777],rowid[888])")
        chk("$from @ {} ( @min $ph.e, @max $ph.e )", "(color[green],color[blue])")
        chk("$from @ {} ( @min $ph.w, @max $ph.w )", "(user[500],user[501])")
    }

    @Test fun testTypeSumFormal() {
        initDataAllTypes()
        val from = impFrom("data")
        chk("_type_of($from @ {} ( @sum $ph.i ))", "text[integer?]")
        chk("_type_of($from @ {} ( @sum $ph.d ))", "text[decimal?]")
        chk("_type_of($from @ {} ( @omit @group 0, @sum $ph.i ))", "text[integer]")
        chk("_type_of($from @ {} ( @omit @group 0, @sum $ph.d ))", "text[decimal]")
    }

    @Test fun testTypeMinMaxFormal() {
        initDataAllTypes()
        chkTypeMinMaxFormal("min")
        chkTypeMinMaxFormal("max")
    }

    private fun chkTypeMinMaxFormal(ann: String) {
        val from = impFrom("data")

        chk("_type_of($from @ {} ( @$ann $ph.i ))", "text[integer?]")
        chk("_type_of($from @ {} ( @$ann $ph.d ))", "text[decimal?]")
        chk("_type_of($from @ {} ( @$ann $ph.t ))", "text[text?]")
        chk("_type_of($from @ {} ( @$ann $ph.r ))", "text[rowid?]")
        chk("_type_of($from @ {} ( @$ann $ph.e ))", "text[color?]")
        chk("_type_of($from @ {} ( @$ann $ph.w ))", "text[user?]")

        chk("_type_of($from @ {} ( @omit @group 0, @$ann $ph.i ))", "text[integer]")
        chk("_type_of($from @ {} ( @omit @group 0, @$ann $ph.d ))", "text[decimal]")
        chk("_type_of($from @ {} ( @omit @group 0, @$ann $ph.t ))", "text[text]")
        chk("_type_of($from @ {} ( @omit @group 0, @$ann $ph.r ))", "text[rowid]")
        chk("_type_of($from @ {} ( @omit @group 0, @$ann $ph.e ))", "text[color]")
        chk("_type_of($from @ {} ( @omit @group 0, @$ann $ph.w ))", "text[user]")
    }
}
