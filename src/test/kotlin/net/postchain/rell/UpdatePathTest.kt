package net.postchain.rell

import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals

// Purpose: make sure that complex combinations of path expressions work (i. e. translated into proper JOINs).

class UpdatePathTest {
    private val classDefs = """
        class country { name: text; }
        class city { name: text; country; }
        class person { name: text; homeCity: city; workCity: city; score: integer; }
    """.trimIndent()

    private val ctx = SqlTestCtx(classDefs)

    @After
    fun after() {
        ctx.destroy()
    }

    @Test fun testSimplePathUpdate() {
        createObjects()

        execute("update person @ { homeCity.name = 'New York' } ( score = 999 );")
        check("person(7,John,4,5,999)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execute("update person @ { workCity.name = 'Berlin' } ( score = 777 );")
        check("person(7,John,4,5,999)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,300)")
    }

    @Test fun testSimplePathDelete() {
        createObjects()

        execute("delete person @ { homeCity.name = 'New York' };")
        check("person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execute("delete person @ { workCity.name = 'Berlin' };")
        check("person(9,Hans,6,4,300)")
    }

    @Test fun testLongerPathUpdate() {
        createObjects()

        execute("update person @ { homeCity.country.name = 'USA' } ( score = 999 );")
        check("person(7,John,4,5,999)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execute("update person @ { workCity.country.name = 'Germany' } ( score = 777 );")
        check("person(7,John,4,5,999)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,300)")
    }

    @Test fun testLongerPathDelete() {
        createObjects()

        execute("delete person @ { homeCity.country.name = 'USA' };")
        check("person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execute("delete person @ { workCity.country.name = 'Germany' };")
        check("person(9,Hans,6,4,300)")
    }

    @Test fun testMultiplePathsUpdate() {
        createObjects()

        execute("update person @ { homeCity.name = 'New York', workCity.name = 'Berlin' } ( score = 999 );") // None
        check("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execute("update person @ { homeCity.name = 'Berlin', workCity.name = 'New York' } ( score = 999 );")
        check("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,999)")

        execute("update person @ { homeCity.name = 'London', workCity.country.name = 'USA' } ( score = 999 );") // None
        check("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,999)")

        execute("update person @ { homeCity.name = 'London', workCity.country.name = 'Germany' } ( score = 777 );")
        check("person(7,John,4,5,100)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,999)")

        execute("update person @ { homeCity.country.name = 'USA', workCity.country.name = 'Germany' } ( score = 333 );") // None
        check("person(7,John,4,5,100)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,999)")

        execute("update person @ { homeCity.country.name = 'USA', workCity.country.name = 'England' } ( score = 333 );")
        check("person(7,John,4,5,333)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,999)")
    }

    @Test fun testMultiplePathsDelete() {
        createObjects()

        execute("delete person @ { homeCity.name = 'New York', workCity.name = 'Berlin' };") // None
        check("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execute("delete person @ { homeCity.name = 'Berlin', workCity.name = 'New York' };")
        check("person(7,John,4,5,100)", "person(8,Mike,5,6,200)")

        execute("delete person @ { homeCity.name = 'London', workCity.country.name = 'USA' };") // None
        check("person(7,John,4,5,100)", "person(8,Mike,5,6,200)")

        execute("delete person @ { homeCity.name = 'London', workCity.country.name = 'Germany' };")
        check("person(7,John,4,5,100)")

        execute("delete person @ { homeCity.country.name = 'USA', workCity.country.name = 'Germany' };") // None
        check("person(7,John,4,5,100)")

        execute("delete person @ { homeCity.country.name = 'USA', workCity.country.name = 'England' };")
        check()
    }

    @Test fun testExtraClassesUpdate() {
        createObjects()

        execute("""
            update p: person (c1: city, c2: city) @ {
                p.homeCity.name = c1.name,
                p.workCity.name = c2.name,
                c1.country.name = 'USA',
                c2.country.name = 'Germany'
            } ( score = 999 );
        """) // None

        check("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execute("""
            update p: person (c1: city, c2: city) @ {
                p.homeCity.name = c1.name,
                p.workCity.name = c2.name,
                c1.country.name = 'Germany',
                c2.country.name = 'USA'
            } ( score = 999 );
        """) // None

        check("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,999)")
    }

    @Test fun testExtraClassesDelete() {
        createObjects()

        execute("""
            delete p: person (c1: city, c2: city) @ {
                p.homeCity.name = c1.name,
                p.workCity.name = c2.name,
                c1.country.name = 'USA',
                c2.country.name = 'Germany'
            };
        """) // None

        check("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execute("""
            delete p: person (c1: city, c2: city) @ {
                p.homeCity.name = c1.name,
                p.workCity.name = c2.name,
                c1.country.name = 'Germany',
                c2.country.name = 'USA'
            };
        """) // None

        check("person(7,John,4,5,100)", "person(8,Mike,5,6,200)")
    }

    @Test fun testExtraClassesMixUpdate() {
        createObjects()
        execute("update p1: person (p2: person) @ { p1.homeCity = p2.workCity } ( score = p1.score * 3 + p2.score );")
        check("person(7,John,4,5,600)", "person(8,Mike,5,6,700)", "person(9,Hans,6,4,1100)")
    }

    private fun createObjects() {
        execute("create country('USA');")
        execute("create country('England');")
        execute("create country('Germany');")
        execute("create city('New York', country @ { 'USA' });")
        execute("create city('London', country @ { 'England' });")
        execute("create city('Berlin', country @ { 'Germany' });")
        execute("create person('John', homeCity = city @ { 'New York' }, workCity = city @ { 'London' }, score = 100);")
        execute("create person('Mike', homeCity = city @ { 'London' }, workCity = city @ { 'Berlin' }, score = 200);")
        execute("create person('Hans', homeCity = city @ { 'Berlin' }, workCity = city @ { 'New York' }, score = 300);")

        check("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")
    }

    private fun execute(code: String) {
        ctx.executeOperation(code)
    }

    private fun check(vararg expectedArray: String) {
        val implicitlyExpected = arrayOf(
                "country(1,USA)",
                "country(2,England)",
                "country(3,Germany)",
                "city(4,New York,1)",
                "city(5,London,2)",
                "city(6,Berlin,3)"
        )
        val expected = (implicitlyExpected + expectedArray).toList()
        val actual = ctx.dumpDatabase()
        assertEquals(expected, actual)
    }
}
