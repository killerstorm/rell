package net.postchain.rell.module

import net.postchain.core.UserMistake
import net.postchain.rell.test.BaseGtxTest
import net.postchain.rell.test.SqlTestUtils
import org.junit.Test
import kotlin.test.assertFailsWith

class GtxModuleTest : BaseGtxTest() {
    private val tablePrefix = "c995511_"

    private val classDefs = listOf(
            "class city { key name: text; }",
            "class person { name: text; city; street: text; house: integer; score: integer; }"
    )

    private val inserts = listOf(
            SqlTestUtils.mkins("${tablePrefix}city", "name", "1,'New York'"),
            SqlTestUtils.mkins("${tablePrefix}city", "name", "2,'Los Angeles'"),
            SqlTestUtils.mkins("${tablePrefix}city", "name", "3,'Seattle'"),
            SqlTestUtils.mkins("${tablePrefix}person", "name,city,street,house,score", "4,'Bob',2,'Main St',5,100"),
            SqlTestUtils.mkins("${tablePrefix}person", "name,city,street,house,score", "5,'Alice',1,'Evergreen Ave',11,250"),
            SqlTestUtils.mkins("${tablePrefix}person", "name,city,street,house,score", "6,'Trudy',2,'Mulholland Dr',3,500")
    )

    @Test fun testQueryNoObjects() {
        tst.defs = classDefs
        tst.chkQuery("city @* {}", "[]")
        tst.chkQuery("city @* {}.name", "[]")
    }

    @Test fun testQuerySimple() {
        tst.defs = classDefs
        tst.inserts = inserts

        tst.chkQuery("city @* {}", "[1,2,3]")
        tst.chkQuery("city @* {}.name", "['New York','Los Angeles','Seattle']")

        tst.chkQueryEx("query q(city) = person @* { city };", "city:1", "[5]")
        tst.chkQueryEx("query q(city) = person @* { city };", "city:2", "[4,6]")
        tst.chkQueryEx("query q(city) = person @* { city };", "city:3", "[]")

        tst.chkQueryEx("query q(name) = city @ { name };", "name:'New York'", "1")
        tst.chkQueryEx("query q(name) = city @ { name };", "name:'Los Angeles'", "2")
        tst.chkQueryEx("query q(name) = city @ { name };", "name:'Seattle'", "3")
    }

    @Test fun testQueryGetPersonsNamesByCityName() {
        tst.defs = classDefs
        tst.inserts = inserts

        val query = "query q(cityName: text) = person @* { .city.name == cityName }.name;"
        tst.chkQueryEx(query, "cityName:'Los Angeles'", "['Bob','Trudy']")
        tst.chkQueryEx(query, "cityName:'New York'", "['Alice']")
        tst.chkQueryEx(query, "cityName:'Seattle'", "[]")
    }

    @Test fun testQueryGetPersonAddressByName() {
        tst.defs = classDefs
        tst.inserts = inserts

        val query = "query q(name: text) = person @ { name } ( city_name = .city.name, .street, .house );"
        tst.chkQueryEx(query, "name:'Bob'", "{'city_name':'Los Angeles','street':'Main St','house':5}")
        tst.chkQueryEx(query, "name:'Alice'", "{'city_name':'New York','street':'Evergreen Ave','house':11}")
        tst.chkQueryEx(query, "name:'Trudy'", "{'city_name':'Los Angeles','street':'Mulholland Dr','house':3}")
    }

    @Test fun testQueryGetPersonsByCitySet() {
        tst.defs = classDefs
        tst.inserts = inserts

        val query = """
            query q(cities: set<text>): list<text> {
                val persons = list<text>();
                for (locCity in cities) persons.addAll(person @* { person.city.name == locCity }.name);
                return persons;
            }
        """.trimIndent()

        tst.chkQueryEx(query, "cities:[]", "[]")
        tst.chkQueryEx(query, "cities:['New York']", "['Alice']")
        tst.chkQueryEx(query, "cities:['Los Angeles']", "['Bob','Trudy']")
        tst.chkQueryEx(query, "cities:['New York','Los Angeles']", "['Alice','Bob','Trudy']")
        tst.chkQueryEx(query, "cities:['Seattle','New York']", "['Alice']")
    }

    @Test fun testQueryErrArgMissing() {
        tst.defs = classDefs
        assertFailsWith<UserMistake> {
            tst.chkQueryEx("query q(name: text) = city @ { name };", "", "")
        }
    }

    @Test fun testQueryErrArgExtra() {
        tst.defs = classDefs
        assertFailsWith<UserMistake> {
            tst.chkQueryEx("query q(name: text) = city @ { name };", "name:'New York',foo:12345", "")
        }
    }

    @Test fun testQueryErrArgWrongType() {
        tst.defs = classDefs
        assertFailsWith<UserMistake> {
            tst.chkQueryEx("query q(name: text) = city @ { name };", "name:12345", "")
        }
    }

    @Test fun testQueryErrArgSetDuplicate() {
        tst.defs = classDefs
        tst.inserts = inserts
        val query = """
            query q(cities: set<text>): list<text> {
                val persons = list<text>();
                for (locCity in cities) persons.addAll(person @* { person.city.name == locCity }.name);
                return persons;
            }
        """.trimIndent()
        tst.chkQueryEx(query, "cities:['New York']", "['Alice']")
        assertFailsWith<UserMistake> {
            tst.chkQueryEx(query, "cities:['New York','New York']", "")
        }
    }

    @Test fun testQueryErrArgNonexistentObjectId() {
        tst.defs = classDefs
        tst.inserts = inserts
        assertFailsWith<UserMistake> {
            tst.chkQueryEx("query q(city) = person @* { city };", "city:999", "")
        }
    }

    @Test fun testQueryErrArgObjectIdOfWrongClass() {
        tst.defs = classDefs
        assertFailsWith<UserMistake> {
            tst.chkQueryEx("query q(city) = person @* { city };", "city:5", "")
        }
    }

    @Test fun testQueryErrRuntimeErrorNoObjects() {
        tst.defs = classDefs
        tst.inserts = inserts
        assertFailsWith<UserMistake> {
            tst.chkQueryEx("query q(name: text) = city @ { name };", "city:'Den Helder'", "")
        }
    }

    @Test fun testQueryErrRuntimeErrorOther() {
        tst.defs = classDefs
        assertFailsWith<UserMistake> {
            tst.chkQueryEx("query q(a: integer, b: integer) = a / b;", "a:123,b:0", "")
        }
    }
}
