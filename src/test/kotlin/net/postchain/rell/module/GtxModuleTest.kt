package net.postchain.rell.module

import net.postchain.core.UserMistake
import net.postchain.rell.test.BaseGtxTest
import net.postchain.rell.test.RellGtxTester
import net.postchain.rell.test.SqlTestUtils
import org.junit.Test
import kotlin.test.assertFailsWith

class GtxModuleTest : BaseGtxTest() {
    private val tablePrefix = "c995511."

    private val entityDefs = listOf(
            "entity city { key name: text; }",
            "entity person { name: text; city; street: text; house: integer; score: integer; }"
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
        tst.defs = entityDefs
        chk("city @* {}", "[]")
        chk("city @* {}.name", "[]")
    }

    @Test fun testQuerySimple() {
        tst.defs = entityDefs
        tst.inserts = inserts

        chk("city @* {}", "[1,2,3]")
        chk("city @* {}.name", "['New York','Los Angeles','Seattle']")

        chkFull("query q(city) = person @* { city };", "city:1", "[5]")
        chkFull("query q(city) = person @* { city };", "city:2", "[4,6]")
        chkFull("query q(city) = person @* { city };", "city:3", "[]")

        chkFull("query q(name) = city @ { name };", "name:'New York'", "1")
        chkFull("query q(name) = city @ { name };", "name:'Los Angeles'", "2")
        chkFull("query q(name) = city @ { name };", "name:'Seattle'", "3")
    }

    @Test fun testQueryGetPersonsNamesByCityName() {
        tst.defs = entityDefs
        tst.inserts = inserts

        val query = "query q(cityName: text) = person @* { .city.name == cityName }.name;"
        chkFull(query, "cityName:'Los Angeles'", "['Bob','Trudy']")
        chkFull(query, "cityName:'New York'", "['Alice']")
        chkFull(query, "cityName:'Seattle'", "[]")
    }

    @Test fun testQueryGetPersonAddressByName() {
        tst.defs = entityDefs
        tst.inserts = inserts

        val query = "query q(name: text) = person @ { name } ( city_name = .city.name, .street, .house );"
        chkFull(query, "name:'Bob'", "{'city_name':'Los Angeles','house':5,'street':'Main St'}")
        chkFull(query, "name:'Alice'", "{'city_name':'New York','house':11,'street':'Evergreen Ave'}")
        chkFull(query, "name:'Trudy'", "{'city_name':'Los Angeles','house':3,'street':'Mulholland Dr'}")
    }

    @Test fun testQueryGetPersonsByCitySet() {
        tst.defs = entityDefs
        tst.inserts = inserts

        val query = """
            query q(cities: set<text>): list<text> {
                val persons = list<text>();
                for (locCity in cities) persons.add_all(person @* { person.city.name == locCity }.name);
                return persons;
            }
        """.trimIndent()

        chkFull(query, "cities:[]", "[]")
        chkFull(query, "cities:['New York']", "['Alice']")
        chkFull(query, "cities:['Los Angeles']", "['Bob','Trudy']")
        chkFull(query, "cities:['New York','Los Angeles']", "['Alice','Bob','Trudy']")
        chkFull(query, "cities:['Seattle','New York']", "['Alice']")
    }

    @Test fun testQueryErrArgMissing() {
        tst.defs = entityDefs
        assertFailsWith<UserMistake> {
            chkFull("query q(name: text) = city @ { name };", "", "")
        }
    }

    @Test fun testQueryErrArgExtra() {
        tst.defs = entityDefs
        assertFailsWith<UserMistake> {
            chkFull("query q(name: text) = city @ { name };", "name:'New York',foo:12345", "")
        }
    }

    @Test fun testQueryErrArgWrongType() {
        tst.defs = entityDefs
        assertFailsWith<UserMistake> {
            chkFull("query q(name: text) = city @ { name };", "name:12345", "")
        }
    }

    @Test fun testQueryErrArgSetDuplicate() {
        tst.defs = entityDefs
        tst.inserts = inserts
        val query = """
            query q(cities: set<text>): list<text> {
                val persons = list<text>();
                for (locCity in cities) persons.add_all(person @* { person.city.name == locCity }.name);
                return persons;
            }
        """.trimIndent()
        chkFull(query, "cities:['New York']", "['Alice']")
        assertFailsWith<UserMistake> {
            chkFull(query, "cities:['New York','New York']", "")
        }
    }

    @Test fun testQueryErrArgNonexistentObjectId() {
        tst.defs = entityDefs
        tst.inserts = inserts
        assertFailsWith<UserMistake> {
            chkFull("query q(city) = person @* { city };", "city:999", "")
        }
    }

    @Test fun testQueryErrArgObjectIdOfWrongEntity() {
        tst.defs = entityDefs
        assertFailsWith<UserMistake> {
            chkFull("query q(city) = person @* { city };", "city:5", "")
        }
    }

    @Test fun testQueryErrRuntimeErrorNoObjects() {
        tst.defs = entityDefs
        tst.inserts = inserts
        assertFailsWith<UserMistake> {
            chkFull("query q(name: text) = city @ { name };", "city:'Den Helder'", "")
        }
    }

    @Test fun testQueryErrRuntimeErrorOther() {
        tst.defs = entityDefs
        assertFailsWith<UserMistake> {
            chkFull("query q(a: integer, b: integer) = a / b;", "a:123,b:0", "")
        }
    }

    @Test fun testTableStructureUpdate() {
        run {
            val t = RellGtxTester(tstCtx, chainId = 0)
            t.def("entity user { name; }")
            t.insert("c0.user", "name", "100,'Bob'")
            t.insert("c0.user", "name", "101,'Alice'")
            t.chkData("user(100,Bob)", "user(101,Alice)")
        }

        run {
            val t = RellGtxTester(tstCtx, chainId = 0)
            t.def("entity user { name; mutable score: integer = 123; }")
            t.chkData("user(100,Bob,123)", "user(101,Alice,123)")
        }
    }
}
