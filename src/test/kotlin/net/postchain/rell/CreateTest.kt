/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class CreateTest: BaseRellTest() {
    override fun entityDefs() = listOf(
            "entity city { name: text; }",
            "entity person { name: text; city; street: text; house: integer; score: integer; }"
    )

    @Test fun testCreateCity() {
        chkData()

        chkOp("create city ( 'New York' );")
        chkDataNew("city(1,New York)")

        chkOp("create city ( 'San Francisco' );")
        chkDataNew("city(2,San Francisco)")

        chkOp("create city ( 'Los Angeles' );")
        chkDataNew("city(3,Los Angeles)")
    }

    @Test fun testCreatePerson() {
        chkData()

        chkOp("""
            create city ( 'New York' );
            create city ( 'San Francisco' );
            create city ( 'Los Angeles' );
        """.trimIndent())
        chkDataNew("city(1,New York)", "city(2,San Francisco)", "city(3,Los Angeles)")

        chkOp("create person ( name = 'James', city @ { 'Los Angeles' }, street = 'Evergreen Ave', house = 5, score = 100 );")
        chkDataNew("person(4,James,3,Evergreen Ave,5,100)")

        chkOp("create person ( name = 'Mike', city @ { 'New York' }, street = 'Grand St', house = 7, score = 250 );")
        chkDataNew("person(5,Mike,1,Grand St,7,250)")
    }

    @Test fun testDefaultValues() {
        def("entity person { name: text; year: integer; score: integer = 777; status: text = 'Unknown'; }")
        chkData()

        chkOp("create person();", "ct_err:attr_missing:name,year")
        chkOp("create person(name = 'Bob');", "ct_err:attr_missing:year")
        chkOp("create person(year = 1980);", "ct_err:attr_missing:name")
        chkDataNew()

        chkOp("create person(name = 'Bob', year = 1980);")
        chkDataNew("person(1,Bob,1980,777,Unknown)")

        chkOp("create person(name = 'Alice', year = 1985, score = 55555);")
        chkDataNew("person(2,Alice,1985,55555,Unknown)")

        chkOp("create person(name = 'Trudy', year = 2000, status = 'Ready');")
        chkDataNew("person(3,Trudy,2000,777,Ready)")

        chkOp("create person(name = 'Will', year = 1990, status = 'Busy', score = 1000);")
        chkDataNew("person(4,Will,1990,1000,Busy)")
    }

    @Test fun testDefaultValueTypeErr() {
        tst.defs = listOf()
        chkCompile("entity person { name: text; year: integer = 'Hello'; }", "ct_err:attr_type:year:[integer]:[text]")
        chkCompile("entity person { name: text = 12345; year: integer; }", "ct_err:attr_type:name:[text]:[integer]")
    }

    @Test fun testDefaultValueVariable() {
        def("entity default_score { mutable value: integer; }")
        def("entity person { name: text; score: integer = default_score@{}.value; }")
        chkData()

        chkOp("create default_score( 100 );")
        chkDataNew("default_score(1,100)")

        chkOp("create person('Bob');")
        chkDataNew("person(2,Bob,100)")

        chkOp("update default_score @ {} ( value = 555 );")
        chkData("default_score(1,555)", "person(2,Bob,100)")

        chkOp("create person('Alice');")
        chkDataNew("person(3,Alice,555)")
    }

    @Test fun testErr() {
        chkOp("create foo(x = 123);", "ct_err:unknown_entity:foo")

        chkOp("create city(foo = 123);", "ct_err:attr_unknown_name:foo")

        chkOp("create city(name = 'New York', name = 'New York');", "ct_err:attr_dup_name:name")
        chkOp("create city(name = 'New York', name = 'New Orlean');", "ct_err:attr_dup_name:name")
    }

    @Test fun testDotAttribute() {
        def("entity person { name: text; year: integer; score: integer = 777; status: text = 'Unknown'; }")
        chkData()

        chkOp("create person(.name = 'Bob', .year = 1980);")
        chkDataNew("person(1,Bob,1980,777,Unknown)")
    }

    @Test fun testNoAttributes() {
        def("entity person {}")
        chkData()

        chkOp("create person();")
        chkData("person(1)")
    }

    @Test fun testBugAttrExpr() {
        tst.def("operation o(){ create user(); }")
        tst.def("entity user { name = 'Bob'; }")
        chkOpFull("")
        chkData("user(1,Bob)")
    }
}
