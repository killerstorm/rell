package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.RellCodeTester
import org.junit.After
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

        execOp("update person @ { .homeCity.name == 'New York' } ( score = 999 );")
        chkDataCommon("person(7,John,4,5,999)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execOp("update person @ { .workCity.name == 'Berlin' } ( score = 777 );")
        chkDataCommon("person(7,John,4,5,999)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,300)")
    }

    @Test fun testSimplePathDelete() {
        createObjects()

        execOp("delete person @ { .homeCity.name == 'New York' };")
        chkDataCommon("person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execOp("delete person @ { .workCity.name == 'Berlin' };")
        chkDataCommon("person(9,Hans,6,4,300)")
    }

    @Test fun testLongerPathUpdate() {
        createObjects()

        execOp("update person @ { .homeCity.country.name == 'USA' } ( score = 999 );")
        chkDataCommon("person(7,John,4,5,999)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execOp("update person @ { .workCity.country.name == 'Germany' } ( score = 777 );")
        chkDataCommon("person(7,John,4,5,999)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,300)")
    }

    @Test fun testLongerPathDelete() {
        createObjects()

        execOp("delete person @ { .homeCity.country.name == 'USA' };")
        chkDataCommon("person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execOp("delete person @ { .workCity.country.name == 'Germany' };")
        chkDataCommon("person(9,Hans,6,4,300)")
    }

    @Test fun testMultiplePathsUpdate() {
        createObjects()

        execOp("update person @ { .homeCity.name == 'New York', .workCity.name == 'Berlin' } ( score = 999 );") // None
        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execOp("update person @ { .homeCity.name == 'Berlin', .workCity.name == 'New York' } ( score = 999 );")
        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,999)")

        execOp("update person @ { .homeCity.name == 'London', .workCity.country.name == 'USA' } ( score = 999 );") // None
        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,999)")

        execOp("update person @ { .homeCity.name == 'London', .workCity.country.name == 'Germany' } ( score = 777 );")
        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,999)")

        execOp("update person @ { .homeCity.country.name == 'USA', .workCity.country.name == 'Germany' } ( score = 333 );") // None
        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,999)")

        execOp("update person @ { .homeCity.country.name == 'USA', .workCity.country.name == 'England' } ( score = 333 );")
        chkDataCommon("person(7,John,4,5,333)", "person(8,Mike,5,6,777)", "person(9,Hans,6,4,999)")
    }

    @Test fun testMultiplePathsDelete() {
        createObjects()

        execOp("delete person @ { .homeCity.name == 'New York', .workCity.name == 'Berlin' };") // None
        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execOp("delete person @ { .homeCity.name == 'Berlin', .workCity.name == 'New York' };")
        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)")

        execOp("delete person @ { .homeCity.name == 'London', .workCity.country.name == 'USA' };") // None
        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)")

        execOp("delete person @ { .homeCity.name == 'London', .workCity.country.name == 'Germany' };")
        chkDataCommon("person(7,John,4,5,100)")

        execOp("delete person @ { .homeCity.country.name == 'USA', .workCity.country.name == 'Germany' };") // None
        chkDataCommon("person(7,John,4,5,100)")

        execOp("delete person @ { .homeCity.country.name == 'USA', .workCity.country.name == 'England' };")
        chkDataCommon()
    }

    @Test fun testExtraClassesUpdate() {
        createObjects()

        execOp("""
            update p: person (c1: city, c2: city) @ {
                p.homeCity.name == c1.name,
                p.workCity.name == c2.name,
                c1.country.name == 'USA',
                c2.country.name == 'Germany'
            } ( score = 999 );
        """) // None

        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execOp("""
            update p: person (c1: city, c2: city) @ {
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

        execOp("""
            delete p: person (c1: city, c2: city) @ {
                p.homeCity.name == c1.name,
                p.workCity.name == c2.name,
                c1.country.name == 'USA',
                c2.country.name == 'Germany'
            };
        """) // None

        chkDataCommon("person(7,John,4,5,100)", "person(8,Mike,5,6,200)", "person(9,Hans,6,4,300)")

        execOp("""
            delete p: person (c1: city, c2: city) @ {
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
        execOp("update p1: person (p2: person) @ { p1.homeCity == p2.workCity } ( score = p1.score * 3 + p2.score );")
        chkDataCommon("person(7,John,4,5,600)", "person(8,Mike,5,6,700)", "person(9,Hans,6,4,1100)")
    }

    private fun createObjects() {
        execOp("create country('USA');")
        execOp("create country('England');")
        execOp("create country('Germany');")
        execOp("create city('New York', country @ { 'USA' });")
        execOp("create city('London', country @ { 'England' });")
        execOp("create city('Berlin', country @ { 'Germany' });")
        execOp("create person('John', homeCity = city @ { 'New York' }, workCity = city @ { 'London' }, score = 100);")
        execOp("create person('Mike', homeCity = city @ { 'London' }, workCity = city @ { 'Berlin' }, score = 200);")
        execOp("create person('Hans', homeCity = city @ { 'Berlin' }, workCity = city @ { 'New York' }, score = 300);")

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
