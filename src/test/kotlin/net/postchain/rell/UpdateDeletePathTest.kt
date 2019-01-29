package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

// Purpose: make sure that complex combinations of path expressions work (i. e. translated into proper JOINs).

class UpdateDeletePathTest: BaseRellTest() {
    override fun classDefs() = listOf(
            "class country { name: text; }",
            "class city { name: text; country; }",
            "class person { name: text; homeCity: city; workCity: city; mutable score: integer; }"
    )

    @Test fun testSimplePathUpdate() {
        createObjects()

        chkOp("update person @ { .homeCity.name == 'New York' } ( score = 999 );")
        chkDataCommon("person(7,John,4,5,999)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        chkOp("update person @ { .workCity.name == 'Berlin' } ( score = 777 );")
        chkDataCommon("person(7,John,4,5,999)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,300)")
    }

    @Test fun testSimplePathDelete() {
        createObjects()

        chkOp("delete person @ { .homeCity.name == 'New York' };")
        chkDataCommon("person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        chkOp("delete person @ { .workCity.name == 'Berlin' };")
        chkDataCommon("person(9,Hans,6,4,300)")
    }

    @Test fun testLongerPathUpdate() {
        createObjects()

        chkOp("update person @ { .homeCity.country.name == 'USA' } ( score = 999 );")
        chkDataCommon("person(7,John,4,5,999)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        chkOp("update person @ { .workCity.country.name == 'Germany' } ( score = 777 );")
        chkDataCommon("person(7,John,4,5,999)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,300)")
    }

    @Test fun testLongerPathDelete() {
        createObjects()

        chkOp("delete person @ { .homeCity.country.name == 'USA' };")
        chkDataCommon("person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        chkOp("delete person @ { .workCity.country.name == 'Germany' };")
        chkDataCommon("person(9,Hans,6,4,300)")
    }

    @Test fun testMultiplePathsUpdate() {
        createObjects()

        chkOp("update person @? { .homeCity.name == 'New York', .workCity.name == 'Berlin' } ( score = 999 );") // None
        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        chkOp("update person @? { .homeCity.name == 'Berlin', .workCity.name == 'New York' } ( score = 999 );")
        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,999)")

        chkOp("update person @? { .homeCity.name == 'London', .workCity.country.name == 'USA' } ( score = 999 );") // None
        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,999)")

        chkOp("update person @? { .homeCity.name == 'London', .workCity.country.name == 'Germany' } ( score = 777 );")
        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,999)")

        chkOp("update person @? { .homeCity.country.name == 'USA', .workCity.country.name == 'Germany' } ( score = 333 );") // None
        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,999)")

        chkOp("update person @? { .homeCity.country.name == 'USA', .workCity.country.name == 'England' } ( score = 333 );")
        chkDataCommon("person(7,John,4,5,333)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,999)")
    }

    @Test fun testMultiplePathsDelete() {
        createObjects()

        chkOp("delete person @? { .homeCity.name == 'New York', .workCity.name == 'Berlin' };") // None
        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        chkOp("delete person @? { .homeCity.name == 'Berlin', .workCity.name == 'New York' };")
        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)")

        chkOp("delete person @? { .homeCity.name == 'London', .workCity.country.name == 'USA' };") // None
        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)")

        chkOp("delete person @? { .homeCity.name == 'London', .workCity.country.name == 'Germany' };")
        chkDataCommon("person(7,John,4,5,100)")

        chkOp("delete person @? { .homeCity.country.name == 'USA', .workCity.country.name == 'Germany' };") // None
        chkDataCommon("person(7,John,4,5,100)")

        chkOp("delete person @? { .homeCity.country.name == 'USA', .workCity.country.name == 'England' };")
        chkDataCommon()
    }

    @Test fun testExtraClassesUpdate() {
        createObjects()

        chkOp("""
            update (p: person, c1: city, c2: city) @? {
                p.homeCity.name == c1.name,
                p.workCity.name == c2.name,
                c1.country.name == 'USA',
                c2.country.name == 'Germany'
            } ( score = 999 );
        """) // None

        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        chkOp("""
            update (p: person, c1: city, c2: city) @? {
                p.homeCity.name == c1.name,
                p.workCity.name == c2.name,
                c1.country.name == 'Germany',
                c2.country.name == 'USA'
            } ( score = 999 );
        """) // None

        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,999)")
    }

    @Test fun testExtraClassesDelete() {
        createObjects()

        chkOp("""
            delete (p: person, c1: city, c2: city) @? {
                p.homeCity.name == c1.name,
                p.workCity.name == c2.name,
                c1.country.name == 'USA',
                c2.country.name == 'Germany'
            };
        """) // None

        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        chkOp("""
            delete (p: person, c1: city, c2: city) @? {
                p.homeCity.name == c1.name,
                p.workCity.name == c2.name,
                c1.country.name == 'Germany',
                c2.country.name == 'USA'
            };
        """) // None

        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)")
    }

    @Test fun testExtraClassesMixUpdate() {
        createObjects()
        chkOp("update (p1: person, p2: person) @* { p1.homeCity == p2.workCity } ( score = p1.score * 3 + p2.score );")
        chkDataCommon("person(7,John,4,5,600)", "person(8,Mike,5,6,700)", "person(9,Hans,6,4,1100)")
    }

    private fun createObjects() {
        chkOp("create country('USA');")
        chkOp("create country('England');")
        chkOp("create country('Germany');")
        chkOp("create city('New York', country @ { 'USA' });")
        chkOp("create city('London', country @ { 'England' });")
        chkOp("create city('Berlin', country @ { 'Germany' });")
        chkOp("create person('John', homeCity = city @ { 'New York' }, workCity = city @ { 'London' }, score = 100);")
        chkOp("create person('Mike', homeCity = city @ { 'London' }, workCity = city @ { 'Berlin' }, score = 200);")
        chkOp("create person('Hans', homeCity = city @ { 'Berlin' }, workCity = city @ { 'New York' }, score = 300);")

        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")
    }

    private fun chkDataCommon(vararg expectedArray: String) {
        val implicitlyExpected = arrayOf(
                "country(1,USA)",
                "country(2,England)",
                "country(3,Germany)",
                "city(4,New York,1)",
                "city(5,London,2)",
                "city(6,Berlin,3)"
        )
        val expected = (implicitlyExpected + expectedArray).toList()
        chkData(*expected.toTypedArray())
    }
}
