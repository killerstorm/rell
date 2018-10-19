package net.postchain.rell

import org.junit.After
import org.junit.Test

// Purpose: make sure that complex combinations of path expressions work (i. e. translated into proper JOINs).

class UpdateDeletePathTest {
    private val classDefs = listOf(
            "class country { name: text; }",
            "class city { name: text; country; }",
            "class person { name: text; homeCity: city; workCity: city; mutable score: integer; }"
    )

    private val tst = RellSqlTester(classDefs = classDefs)

    @After fun after() = tst.destroy()

    @Test fun testSimplePathUpdate() {
        createObjects()

        execute("update person @ { homeCity.name = 'New York' } ( score = 999 );")
        chk("person(7,John,4,5,999)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execute("update person @ { workCity.name = 'Berlin' } ( score = 777 );")
        chk("person(7,John,4,5,999)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,300)")
    }

    @Test fun testSimplePathDelete() {
        createObjects()

        execute("delete person @ { homeCity.name = 'New York' };")
        chk("person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execute("delete person @ { workCity.name = 'Berlin' };")
        chk("person(9,Hans,6,4,300)")
    }

    @Test fun testLongerPathUpdate() {
        createObjects()

        execute("update person @ { homeCity.country.name = 'USA' } ( score = 999 );")
        chk("person(7,John,4,5,999)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execute("update person @ { workCity.country.name = 'Germany' } ( score = 777 );")
        chk("person(7,John,4,5,999)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,300)")
    }

    @Test fun testLongerPathDelete() {
        createObjects()

        execute("delete person @ { homeCity.country.name = 'USA' };")
        chk("person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execute("delete person @ { workCity.country.name = 'Germany' };")
        chk("person(9,Hans,6,4,300)")
    }

    @Test fun testMultiplePathsUpdate() {
        createObjects()

        execute("update person @ { homeCity.name = 'New York', workCity.name = 'Berlin' } ( score = 999 );") // None
        chk("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execute("update person @ { homeCity.name = 'Berlin', workCity.name = 'New York' } ( score = 999 );")
        chk("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,999)")

        execute("update person @ { homeCity.name = 'London', workCity.country.name = 'USA' } ( score = 999 );") // None
        chk("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,999)")

        execute("update person @ { homeCity.name = 'London', workCity.country.name = 'Germany' } ( score = 777 );")
        chk("person(7,John,4,5,100)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,999)")

        execute("update person @ { homeCity.country.name = 'USA', workCity.country.name = 'Germany' } ( score = 333 );") // None
        chk("person(7,John,4,5,100)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,999)")

        execute("update person @ { homeCity.country.name = 'USA', workCity.country.name = 'England' } ( score = 333 );")
        chk("person(7,John,4,5,333)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,999)")
    }

    @Test fun testMultiplePathsDelete() {
        createObjects()

        execute("delete person @ { homeCity.name = 'New York', workCity.name = 'Berlin' };") // None
        chk("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execute("delete person @ { homeCity.name = 'Berlin', workCity.name = 'New York' };")
        chk("person(7,John,4,5,100)", "person(8,Mike,5,6,200)")

        execute("delete person @ { homeCity.name = 'London', workCity.country.name = 'USA' };") // None
        chk("person(7,John,4,5,100)", "person(8,Mike,5,6,200)")

        execute("delete person @ { homeCity.name = 'London', workCity.country.name = 'Germany' };")
        chk("person(7,John,4,5,100)")

        execute("delete person @ { homeCity.country.name = 'USA', workCity.country.name = 'Germany' };") // None
        chk("person(7,John,4,5,100)")

        execute("delete person @ { homeCity.country.name = 'USA', workCity.country.name = 'England' };")
        chk()
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

        chk("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execute("""
            update p: person (c1: city, c2: city) @ {
                p.homeCity.name = c1.name,
                p.workCity.name = c2.name,
                c1.country.name = 'Germany',
                c2.country.name = 'USA'
            } ( score = 999 );
        """) // None

        chk("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,999)")
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

        chk("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execute("""
            delete p: person (c1: city, c2: city) @ {
                p.homeCity.name = c1.name,
                p.workCity.name = c2.name,
                c1.country.name = 'Germany',
                c2.country.name = 'USA'
            };
        """) // None

        chk("person(7,John,4,5,100)", "person(8,Mike,5,6,200)")
    }

    @Test fun testExtraClassesMixUpdate() {
        createObjects()
        execute("update p1: person (p2: person) @ { p1.homeCity = p2.workCity } ( score = p1.score * 3 + p2.score );")
        chk("person(7,John,4,5,600)", "person(8,Mike,5,6,700)", "person(9,Hans,6,4,1100)")
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

        chk("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")
    }

    private fun execute(code: String) = tst.execOp(code)

    private fun chk(vararg expectedArray: String) {
        val implicitlyExpected = arrayOf(
                "country(1,USA)",
                "country(2,England)",
                "country(3,Germany)",
                "city(4,New York,1)",
                "city(5,London,2)",
                "city(6,Berlin,3)"
        )
        val expected = (implicitlyExpected + expectedArray).toList()
        tst.chkData(*expected.toTypedArray())
    }
}
