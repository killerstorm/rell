/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class AtExprGroupTest: BaseRellTest() {
    private fun initDataCountries() {
        tst.strictToString = false

        def("entity country { name; region: text; language: text; gdp: integer; }")

        insert("c0.country", "name,region,language,gdp",
                "100,'Germany','EMEA','German',3863",
                "101,'Austria','EMEA','German',447",
                "102,'United Kingdom','EMEA','English',2743",
                "103,'USA','AMER','English',21439",
                "104,'Mexico','AMER','Spanish',1274",
                "105,'China','APAC','Chinese',14140"
        )
    }

    @Test fun testGroupSimple() {
        initDataCountries()
        chk("country @* {} ( .region )", "[EMEA, EMEA, EMEA, AMER, AMER, APAC]")
        chk("country @* {} ( @group .region )", "[AMER, APAC, EMEA]")
        chk("country @* {} ( .language )", "[German, German, English, English, Spanish, Chinese]")
        chk("country @* {} ( @group .language )", "[Chinese, English, German, Spanish]")
    }

    @Test fun testGroupMulti() {
        initDataCountries()

        chk("country @* {} ( @group _=.region, @group _=.language )",
                "[(AMER,English), (AMER,Spanish), (APAC,Chinese), (EMEA,English), (EMEA,German)]")

        chk("country @* {} ( @group _=.language, @group _=.region )",
                "[(Chinese,APAC), (English,AMER), (English,EMEA), (German,EMEA), (Spanish,AMER)]")
    }

    @Test fun testGroupSort() {
        initDataCountries()

        chk("country @* {} ( @group .region )", "[AMER, APAC, EMEA]")
        chk("country @* {} ( @sort @group .region )", "[AMER, APAC, EMEA]")
        chk("country @* {} ( @sort_desc @group .region )", "[EMEA, APAC, AMER]")

        chk("country @* {} ( @group .language )", "[Chinese, English, German, Spanish]")
        chk("country @* {} ( @sort @group .language )", "[Chinese, English, German, Spanish]")
        chk("country @* {} ( @sort_desc @group .language )", "[Spanish, German, English, Chinese]")
    }

    @Test fun testGroupSortMulti() {
        initDataCountries()

        chk("country @* {} ( @group _=.region, @group _=.language )",
                "[(AMER,English), (AMER,Spanish), (APAC,Chinese), (EMEA,English), (EMEA,German)]")

        chk("country @* {} ( @sort_desc @group _=.region, @group _=.language )",
                "[(EMEA,English), (EMEA,German), (APAC,Chinese), (AMER,English), (AMER,Spanish)]")

        chk("country @* {} ( @group _=.region, @sort_desc @group _=.language )",
                "[(AMER,Spanish), (EMEA,German), (AMER,English), (EMEA,English), (APAC,Chinese)]")

        chk("country @* {} ( @sort_desc @group _=.region, @sort_desc @group _=.language )",
                "[(EMEA,German), (EMEA,English), (APAC,Chinese), (AMER,Spanish), (AMER,English)]")

        chk("country @* {} ( @sort_desc @group _=.language, @group _=.region )",
                "[(Spanish,AMER), (German,EMEA), (English,AMER), (English,EMEA), (Chinese,APAC)]")

        chk("country @* {} ( @sort_desc @group _=.language, @sort_desc @group _=.region )",
                "[(Spanish,AMER), (German,EMEA), (English,EMEA), (English,AMER), (Chinese,APAC)]")
    }

    @Test fun testCount() {
        initDataCountries()
        chk("country @* {} ( @group _=.region, @sum 1 )", "[(AMER,2), (APAC,1), (EMEA,3)]")
        chk("country @* {} ( @group _=.region, @sort @sum 1 )", "[(APAC,1), (AMER,2), (EMEA,3)]")
        chk("country @* {} ( @group _=.region, @sort_desc @sum 1 )", "[(EMEA,3), (AMER,2), (APAC,1)]")
        chk("country @* { .gdp > 2000 } ( @group _=.region, @sum 1 )", "[(AMER,1), (APAC,1), (EMEA,2)]")
    }

    @Test fun testSum() {
        initDataCountries()

        chk("country @* {} ( @group _=.region, @sum 0 )", "[(AMER,0), (APAC,0), (EMEA,0)]")
        chk("country @* {} ( @group _=.region, @sum 1 )", "[(AMER,2), (APAC,1), (EMEA,3)]")
        chk("country @* {} ( @group _=.region, @sum .gdp )", "[(AMER,22713), (APAC,14140), (EMEA,7053)]")
        chk("country @* {} ( @group _=.region, @sum .name.size() )", "[(AMER,9), (APAC,5), (EMEA,28)]")

        chk("country @* {} ( @group _=.region, @sort @sum .gdp )", "[(EMEA,7053), (APAC,14140), (AMER,22713)]")
        chk("country @* {} ( @group _=.region, @sort_desc @sum .gdp )", "[(AMER,22713), (APAC,14140), (EMEA,7053)]")

        chk("country @* { .gdp > 2000 } ( @group _=.region, @sum .gdp )", "[(AMER,21439), (APAC,14140), (EMEA,6606)]")
    }

    @Test fun testMinMax() {
        initDataCountries()

        chk("country @* {} ( @group _=.region, @min 1 )", "[(AMER,1), (APAC,1), (EMEA,1)]")
        chk("country @* {} ( @group _=.region, @max 1 )", "[(AMER,1), (APAC,1), (EMEA,1)]")

        chk("country @* {} ( @group _=.region, @min .gdp )", "[(AMER,1274), (APAC,14140), (EMEA,447)]")
        chk("country @* {} ( @group _=.region, @max .gdp )", "[(AMER,21439), (APAC,14140), (EMEA,3863)]")

        chk("country @* {} ( @group _=.region, @min .name.size() )", "[(AMER,3), (APAC,5), (EMEA,7)]")
        chk("country @* {} ( @group _=.region, @max .name.size() )", "[(AMER,6), (APAC,5), (EMEA,14)]")

        chk("country @* {} ( @group _=.region, @sort @min .gdp )", "[(EMEA,447), (AMER,1274), (APAC,14140)]")
        chk("country @* {} ( @group _=.region, @sort @max .gdp )", "[(EMEA,3863), (APAC,14140), (AMER,21439)]")

        chk("country @* {} ( @group _=.region, @sort_desc @min .gdp )", "[(APAC,14140), (AMER,1274), (EMEA,447)]")
        chk("country @* {} ( @group _=.region, @sort_desc @max .gdp )", "[(AMER,21439), (APAC,14140), (EMEA,3863)]")

        chk("country @* { .gdp > 2000 } ( @group _=.region, @min .gdp )", "[(AMER,21439), (APAC,14140), (EMEA,2743)]")
        chk("country @* { .gdp < 2000 } ( @group _=.region, @max .gdp )", "[(AMER,1274), (EMEA,447)]")
    }

    @Test fun testCombined() {
        initDataCountries()
        chk("country @* {} ( @group _=.region, @sum 1, @sum .gdp, @min .gdp, @max .gdp )",
                "[(AMER,2,22713,1274,21439), (APAC,1,14140,14140,14140), (EMEA,3,7053,447,3863)]")
    }

    @Test fun testNoGroup() {
        initDataCountries()

        chk("country @ {} ( @sum .gdp, @min .gdp, @max .gdp )", "(43906,447,21439)")
        chk("country @* {} ( @sum .gdp, @min .gdp, @max .gdp )", "[(43906,447,21439)]")
        chk("country @* {} ( @group _=.region, @sum .gdp )", "[(AMER,22713), (APAC,14140), (EMEA,7053)]")

        chk("country @ {} ( @sum 1 )", "6")
        chk("country @ {} ( @sum .gdp )", "43906")

        chk("country @* {} ( _=.name, @sum .gdp )", "ct_err:at:what:no_aggr:0")
        chk("country @* {} ( @sum .gdp, _=.name )", "ct_err:at:what:no_aggr:1")
        chk("country @* {} ( _=.region, @sum .gdp )", "ct_err:at:what:no_aggr:0")
        chk("country @* {} ( @sum .gdp, _=.region )", "ct_err:at:what:no_aggr:1")
        chk("country @* {} ( 123, @sum .gdp )", "ct_err:at:what:no_aggr:0")
        chk("country @* {} ( @sum .gdp, 123 )", "ct_err:at:what:no_aggr:1")

        chkEx("{ val (country_count, total_gdp) = country @{} ( @sum 1, @sum .gdp ); return (country_count, total_gdp); }",
                "(6,43906)")
    }

    @Test fun testOmit() {
        initDataCountries()

        chk("country @* {} ( @group _=.region, @sum .gdp, @min .gdp, @max .gdp )",
                "[(AMER,22713,1274,21439), (APAC,14140,14140,14140), (EMEA,7053,447,3863)]")

        chk("country @* {} ( @omit @group _=.region, @sum .gdp, @min .gdp, @max .gdp )",
                "[(22713,1274,21439), (14140,14140,14140), (7053,447,3863)]")

        chk("country @* {} ( @group _=.region, @omit @sum .gdp, @min .gdp, @max .gdp )",
                "[(AMER,1274,21439), (APAC,14140,14140), (EMEA,447,3863)]")

        chk("country @* {} ( @omit @group _=.region, @omit @sum .gdp, @min .gdp, @max .gdp )",
                "[(1274,21439), (14140,14140), (447,3863)]")

        chk("country @* {} ( @group _=.region, @omit @sum .gdp, @omit @min .gdp, @omit @max .gdp )", "[AMER, APAC, EMEA]")

        chk("country @* {} ( @omit @group _=.region, @omit @sum .gdp, @omit @min .gdp, @omit @max .gdp )", "ct_err:at:no_fields")
    }

    @Test fun testAnnotationConflicts() {
        initDataCountries()
        chk("country @* {} ( @group @group _=.region )", "ct_err:ann:group:dup")
        chk("country @* {} ( @group @max _=.region )", "ct_err:ann:max:dup")
        chk("country @* {} ( @min @max _=.region )", "ct_err:ann:max:dup")
        chk("country @* {} ( @group _=.region )", "[AMER, APAC, EMEA]")
        chk("country @* {} ( @min _=.region )", "[AMER]")
    }

    @Test fun testTypeSum() {
        initDataAllTypes()

        chk("data @ {} ( @sum .i )", "int[333]")
        chk("data @ {} ( @sum .d )", "dec[166.65]")

        chk("data @ {} ( @sum .b )", "ct_err:at:what:aggr:bad_type:SUM:boolean")
        chk("data @ {} ( @sum .t )", "ct_err:at:what:aggr:bad_type:SUM:text")
        chk("data @ {} ( @sum .ba )", "ct_err:at:what:aggr:bad_type:SUM:byte_array")
        chk("data @ {} ( @sum .r )", "ct_err:at:what:aggr:bad_type:SUM:rowid")
        chk("data @ {} ( @sum .e )", "ct_err:at:what:aggr:bad_type:SUM:color")
        chk("data @ {} ( @sum .w )", "ct_err:at:what:aggr:bad_type:SUM:user")
    }

    @Test fun testTypeMinMax() {
        initDataAllTypes()

        chk("data @ {} ( @min .i, @max .i )", "(int[111],int[222])")
        chk("data @ {} ( @min .d, @max .d )", "(dec[67.89],dec[98.76])")
        chk("data @ {} ( @min .t, @max .t )", "(text[abc],text[def])")
        chk("data @ {} ( @min .r, @max .r )", "(rowid[777],rowid[888])")
        chk("data @ {} ( @min .e, @max .e )", "(color[green],color[blue])")
        chk("data @ {} ( @min .w, @max .w )", "(user[500],user[501])")

        chk("data @ {} ( @min .b )", "ct_err:at:what:aggr:bad_type:MIN:boolean")
        chk("data @ {} ( @max .b )", "ct_err:at:what:aggr:bad_type:MAX:boolean")
        chk("data @ {} ( @min .ba )", "ct_err:at:what:aggr:bad_type:MIN:byte_array")
        chk("data @ {} ( @max .ba )", "ct_err:at:what:aggr:bad_type:MAX:byte_array")
    }

    private fun initDataAllTypes() {
        def("entity user { name; }")
        def("enum color { red, green, blue }")
        def("entity data { b: boolean; i: integer; d: decimal; t: text; ba: byte_array; r: rowid; e: color; w: user; }")

        insert("c0.user", "name", "500,'Bob'", "501,'Alice'")
        insert("c0.data", "b,i,d,t,ba,r,e,w",
                "601,false,111,67.89,'abc','\\xbeef',888,2,500",
                "602,false,222,98.76,'def','\\xdead',777,1,501"
        )
    }

    @Test fun testAnnotationsOnWrongTargets() {
        chkCompile("@group entity user {}", "ct_err:ann:group:target_type:ENTITY")
        chkCompile("@min entity user {}", "ct_err:ann:min:target_type:ENTITY")
        chkCompile("@max entity user {}", "ct_err:ann:max:target_type:ENTITY")
        chkCompile("@sum entity user {}", "ct_err:ann:sum:target_type:ENTITY")

        chkCompile("@group namespace ns {}", "ct_err:ann:group:target_type:NAMESPACE")
        chkCompile("@min namespace ns {}", "ct_err:ann:min:target_type:NAMESPACE")
        chkCompile("@max namespace ns {}", "ct_err:ann:max:target_type:NAMESPACE")
        chkCompile("@sum namespace ns {}", "ct_err:ann:sum:target_type:NAMESPACE")
    }

    @Test fun testTupleFieldNames() {
        initDataCountries()

        chk("country @* {} ( @group .region, @sum country_count = 1, @sum total_gdp = .gdp )", "[" +
                "(region=AMER,country_count=2,total_gdp=22713), " +
                "(region=APAC,country_count=1,total_gdp=14140), " +
                "(region=EMEA,country_count=3,total_gdp=7053)" +
        "]")

        chk("country @* {} ( @group .region, @sum .gdp )", "[" +
                "(region=AMER,22713), " +
                "(region=APAC,14140), " +
                "(region=EMEA,7053)" +
        "]")

        chk("country @ {} ( @sum 1 )", "6")
        chk("country @ {} ( @sum .gdp )", "43906")
        chk("country @ {} ( @sum 1, @sum .gdp )", "(6,43906)")
        chk("country @ {} ( @sum .gdp, @min .gdp, @max .gdp )", "(43906,447,21439)")
    }
}
