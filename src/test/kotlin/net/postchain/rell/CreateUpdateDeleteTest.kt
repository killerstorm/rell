package net.postchain.rell

import org.junit.*
import kotlin.test.assertEquals

class CreateUpdateDeleteTest {
    private val classDefs = """
        class city { name: text; }
        class person { name: text; city; street: text; house: integer; score: integer; }
    """.trimIndent()

    private val ctx = SqlTestCtx(classDefs)

    @After fun after() {
        ctx.destroy()
    }

    @Test fun testCreateCity() {
        createCities()
    }

    @Test fun testCreatePerson() {
        createCitiesAndPersons()
    }

    @Test fun testUpdatePersonSetScore() {
        createCitiesAndPersons()

        execute("update person @ { name = 'James' } ( score = 125 );")
        check("person(4,James,3,Evergreen Ave,5,125)", "person(5,Mike,1,Grand St,7,250)")
    }

    @Test fun testUpdatePersonAddScore() {
        createCitiesAndPersons()

        execute("update person @ { name = 'James' } ( score = score + 50 );")
        check("person(4,James,3,Evergreen Ave,5,150)", "person(5,Mike,1,Grand St,7,250)")
    }

    @Test fun testUpdatePersonMultiplyScore() {
        createCitiesAndPersons()

        execute("update person @ { name = 'James' } ( score = score * 3 );")
        check("person(4,James,3,Evergreen Ave,5,300)", "person(5,Mike,1,Grand St,7,250)")
    }

    @Test fun testUpdatePersonAll() {
        createCitiesAndPersons()

        execute("update person @ {} ( score = score + 75 );")
        check("person(4,James,3,Evergreen Ave,5,175)", "person(5,Mike,1,Grand St,7,325)")
    }

    @Test fun testUpdatePersonSetFullAddress() {
        createCitiesAndPersons()

        execute("update person @ { name = 'Mike' } ( city @ { 'San Francisco' }, street = 'Lawton St', house = 13 );")
        check("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,2,Lawton St,13,250)")
    }

    @Test fun testDeletePerson() {
        createCitiesAndPersons()

        execute("delete person @ { name = 'James' };")
        check("person(5,Mike,1,Grand St,7,250)")

        execute("delete person @ { name = 'Mike' };")
        check()
    }

    @Test fun testDeleteCity() {
        createCities()

        execute("delete city @ { name = 'San Francisco' };")
        checkAll(
                "city(1,New York)",
                "city(3,Los Angeles)"
        )

        execute("delete city @ {};")
        checkAll()
    }

    @Test fun testUpdateClassAlias() {
        createCitiesAndPersons()

        execute("update p: person @ { p.name = 'Mike' } ( score = 999 );")
        check("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,999)")

        executeErr("update p: person @ { person.name = 'Mike' } ( score = 777 );", "ct_err:unknown_name:person")
        check("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,999)")

        execute("update person @ { person.name = 'Mike' } ( score = 777 );")
        check("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,777)")
    }

    @Test fun testDeleteClassAlias() {
        createCitiesAndPersons()

        execute("delete p: person @ { p.name = 'Mike' };")
        check("person(4,James,3,Evergreen Ave,5,100)")

        executeErr("delete p: person @ { person.name = 'James' };", "ct_err:unknown_name:person")
        check("person(4,James,3,Evergreen Ave,5,100)")

        execute("delete person @ { person.name = 'James' };")
        check()
    }

    @Test fun testUpdateExtraClass() {
        createCitiesAndPersons()

        execute("update p: person (c: city) @ { p.city = c, c.name = 'New York' } ( score = 999 );")
        check("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,999)")
    }

    @Test fun testDeleteExtraClass() {
        createCitiesAndPersons()

        execute("delete p: person (c: city) @ { p.city = c, c.name = 'New York' };")
        check("person(4,James,3,Evergreen Ave,5,100)")
    }

    private fun createCities() {
        checkAll()

        execute("create city ( 'New York' );")
        checkAll("city(1,New York)")

        execute("create city ( 'San Francisco' );")
        checkAll("city(1,New York)", "city(2,San Francisco)")

        execute("create city ( 'Los Angeles' );")
        checkAll("city(1,New York)", "city(2,San Francisco)", "city(3,Los Angeles)")
    }

    private fun createCitiesAndPersons() {
        createCities()

        execute("create person ( name = 'James', city @ { 'Los Angeles' }, street = 'Evergreen Ave', house = 5, score = 100 );")
        check("person(4,James,3,Evergreen Ave,5,100)")

        execute("create person ( name = 'Mike', city @ { 'New York' }, street =  'Grand St', house = 7, score = 250 );")
        check("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,250)")
    }

    private fun execute(code: String) {
        assertEquals("", ctx.executeOperation(code))
    }

    private fun executeErr(code: String, expected: String) {
        assertEquals(expected, ctx.executeOperation(code))
    }

    private fun check(vararg expectedArray: String) {
        val cities = arrayOf(
                "city(1,New York)",
                "city(2,San Francisco)",
                "city(3,Los Angeles)"
        )
        checkAll(*(cities + expectedArray))
    }

    private fun checkAll(vararg expectedArray: String) {
        val expected = expectedArray.toList()
        val actual = ctx.dumpDatabase()
        assertEquals(expected, actual)
    }

    // bad class name
    // operator += and similar
    // not all attributes specified for create
    // bad attribute specified for create/update
    // attribute specified twice for create/update
    // attribute match name/type ambiguity
    // implicit attribute match by name/type
    // rollback
    // delete referenced record
}
