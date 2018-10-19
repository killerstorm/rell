package net.postchain.rell

import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals

class CreateTest {
    private val classDefs = listOf(
            "class city { name: text; }",
            "class person { name: text; city; street: text; house: integer; score: integer; }"
    )

    private val tst = RellSqlTester(classDefs = classDefs)

    @After fun after() = tst.destroy()

    @Test fun testCreateCity() {
        chkAll()

        execute("create city ( 'New York' );")
        chkNew("city(1,New York)")

        execute("create city ( 'San Francisco' );")
        chkNew("city(2,San Francisco)")

        execute("create city ( 'Los Angeles' );")
        chkNew("city(3,Los Angeles)")
    }

    @Test fun testCreatePerson() {
        chkAll()

        execute("""
            create city ( 'New York' );
            create city ( 'San Francisco' );
            create city ( 'Los Angeles' );
        """.trimIndent())
        chkNew("city(1,New York)", "city(2,San Francisco)", "city(3,Los Angeles)")

        execute("create person ( name = 'James', city @ { 'Los Angeles' }, street = 'Evergreen Ave', house = 5, score = 100 );")
        chkNew("person(4,James,3,Evergreen Ave,5,100)")

        execute("create person ( name = 'Mike', city @ { 'New York' }, street =  'Grand St', house = 7, score = 250 );")
        chkNew("person(5,Mike,1,Grand St,7,250)")
    }

    @Test fun testDefaultValues() {
        tst.classDefs = listOf("class person { name: text; year: integer; score: integer = 777; status: text = 'Unknown'; }")
        chkAll()

        executeErr("create person();", "ct_err:attr_missing:name,year")
        executeErr("create person(name = 'Bob');", "ct_err:attr_missing:year")
        executeErr("create person(year = 1980);", "ct_err:attr_missing:name")
        chkNew()

        execute("create person(name = 'Bob', year = 1980);")
        chkNew("person(1,Bob,1980,777,Unknown)")

        execute("create person(name = 'Alice', year = 1985, score = 55555);")
        chkNew("person(2,Alice,1985,55555,Unknown)")

        execute("create person(name = 'Trudy', year = 2000, status = 'Ready');")
        chkNew("person(3,Trudy,2000,777,Ready)")

        execute("create person(name = 'Will', year = 1990, status = 'Busy', score = 1000);")
        chkNew("person(4,Will,1990,1000,Busy)")
    }

    @Test fun testDefaultValueTypeErr() {
        classDefErr("class person { name: text; year: integer = 'Hello'; }", "ct_err:attr_type:year:integer:text")
        classDefErr("class person { name: text = 12345; year: integer; }", "ct_err:attr_type:name:text:integer")
    }

    @Test fun testDefaultValueVariable() {
        tst.classDefs = listOf(
                "class default_score { mutable value: integer; }",
                "class person { name: text; score: integer = default_score@{}.value; }"
        )
        chkAll()

        execute("create default_score( 100 );")
        chkNew("default_score(1,100)")

        execute("create person('Bob');")
        chkNew("person(2,Bob,100)")

        execute("update default_score @ {} ( value = 555 );")
        chkAll("default_score(1,555)", "person(2,Bob,100)")

        execute("create person('Alice');")
        chkNew("person(3,Alice,555)")
    }

    private fun execute(code: String) = tst.execOp(code)
    private fun executeErr(code: String, expected: String) = tst.chkOp(code, expected)

    private fun chkAll(vararg expected: String) = tst.chkData(expected.toList())
    private fun chkNew(vararg expected: String) = tst.chkDataNew(expected.toList())

    private fun classDefErr(code: String, expected: String) {
        val actual = RellTestUtils.processModule(code, { "OK" })
        assertEquals(expected, actual)
    }
}
