package net.postchain.rell

import net.postchain.rell.sql.ROWID_COLUMN
import org.junit.Test
import kotlin.test.assertEquals

class AtExprTest {
    private var testDataClassDefs = arrayOf(
            "class company { name: text; }",
            "class user { firstName: text; lastName: text; company; }"
    )

    private var testDataInserts = arrayOf(
            Ins.company(100, "Facebook"),
            Ins.company(200, "Apple"),
            Ins.company(300, "Amazon"),
            Ins.company(400, "Microsoft"),
            Ins.company(500, "Google"),

            Ins.user(10, 100, "Mark", "Zuckerberg"),
            Ins.user(20, 200, "Steve", "Jobs"),
            Ins.user(21, 200, "Steve", "Wozniak"),
            Ins.user(30, 300, "Jeff", "Bezos"),
            Ins.user(40, 400, "Bill", "Gates"),
            Ins.user(41, 400, "Paul", "Allan"),
            Ins.user(50, 500, "Sergey", "Brin"),
            Ins.user(51, 500, "Larry", "Page")
    )

    private var testDataBaseCode = ""

    @Test fun testEmptyWhere() {
        check("all user @ {}", "list<user>[user[10],user[20],user[21],user[30],user[40],user[41],user[50],user[51]]")
        check("all company @ {}", "list<company>[company[100],company[200],company[300],company[400],company[500]]")
    }

    @Test fun testNoRecordsFound() {
        check("user @ { lastName = 'Socrates' }", "rt_err:at:wrong_count:0")
    }

    @Test fun testManyRecordsFound() {
        check("user @ { firstName = 'Steve' }", "rt_err:at:wrong_count:2")
    }

    @Test fun testFindByObjectReference() {
        checkEx("val corp = company @ { name = 'Facebook' }; return user @ { company = corp };", "user[10]")
    }

    @Test fun testFindUserWithSameName() {
        check("all (u1: user, u2: user) @ { u1.firstName = u2.firstName, u1 != u2 }",
                "list<(user,user)>[(user[20],user[21]),(user[21],user[20])]")
    }

    @Test fun testAttributeByVariableName() {
        checkEx("val firstName = 'Bill'; return user @ { firstName };", "user[40]")
        checkEx("val lastName = 'Gates'; return user @ { lastName };", "user[40]")
        checkEx("val name = 'Microsoft'; return company @ { name };", "company[400]")
        checkEx("val name = 'Bill'; return user @ { name };", "ct_err:at_attr_type_ambig:0:text:2")
        checkEx("val name = 12345; return company @ { name };", "ct_err:at_attr_type:0:name:integer")
        checkEx("val company = company @ { name = 'Facebook' }; return user @ { company };", "user[10]")
    }

    @Test fun testAttributeByExpressionType() {
        checkEx("val corp = company @ { name = 'Facebook' }; return user @ { corp };", "user[10]")
        checkEx("val corp = company @ { name = 'Microsoft' }; return all user @ { corp };", "list<user>[user[40],user[41]]")
    }

    @Test fun testAttributeByNameAndType() {
        testDataClassDefs = arrayOf(
                "class foo { name: text; }",
                "class bar { name: text; }",
                "class foo_owner { name: text; stuff: foo; foo: foo; bar: bar; }",
                "class bar_owner { name: text; stuff: bar; foo: foo; bar: bar; }"
        )
        testDataInserts = arrayOf(
                mkins("foo", "name", "0,'Foo-1'"),
                mkins("foo", "name", "1,'Foo-2'"),
                mkins("bar", "name", "2,'Bar-1'"),
                mkins("bar", "name", "3,'Bar-2'"),
                mkins("foo_owner", "name,stuff,foo,bar", "4,'Bob',0,1,2"),
                mkins("foo_owner", "name,stuff,foo,bar", "5,'Alice',1,0,3"),
                mkins("bar_owner", "name,stuff,foo,bar", "6,'Trudy',2,1,3"),
                mkins("bar_owner", "name,stuff,foo,bar", "7,'Andrew',3,0,2")
        )
        testDataBaseCode = """
            val foo1 = foo @ { name = 'Foo-1' };
            val foo2 = foo @ { name = 'Foo-2' };
            val bar1 = bar @ { name = 'Bar-1' };
            val bar2 = bar @ { name = 'Bar-2' };
        """.trimIndent()

        checkEx("val name = 'Bob'; return all (foo_owner, bar_owner) @ { name };", "ct_err:at_attr_name_ambig:0:name:2")
        checkEx("val garbage = foo1; return all (foo_owner, bar_owner) @ { garbage };", "ct_err:at_attr_type_ambig:0:foo:3")
        checkEx("val garbage = bar1; return all (foo_owner, bar_owner) @ { garbage };", "ct_err:at_attr_type_ambig:0:bar:3")

        checkEx("val stuff = foo1; return all (foo_owner, bar_owner) @ { stuff };",
                "list<(foo_owner,bar_owner)>[(foo_owner[4],bar_owner[6]),(foo_owner[4],bar_owner[7])]")
        checkEx("val stuff = foo2; return all (foo_owner, bar_owner) @ { stuff };",
                "list<(foo_owner,bar_owner)>[(foo_owner[5],bar_owner[6]),(foo_owner[5],bar_owner[7])]")
        checkEx("val stuff = bar1; return all (foo_owner, bar_owner) @ { stuff };",
                "list<(foo_owner,bar_owner)>[(foo_owner[4],bar_owner[6]),(foo_owner[5],bar_owner[6])]")
        checkEx("val stuff = bar2; return all (foo_owner, bar_owner) @ { stuff };",
                "list<(foo_owner,bar_owner)>[(foo_owner[4],bar_owner[7]),(foo_owner[5],bar_owner[7])]")
    }

    @Test fun testSingleClassAlias() {
        check("(u: user) @ { u.firstName = 'Bill' }", "user[40]")
        check("(u: user) @ { user.firstName = 'Bill' }", "ct_err:unknown_name:user")
    }

    @Test fun testMultipleClassesBadAlias() {
        check("(user: company, user) @ {}", "ct_err:at_dup_alias:user")
        check("(user, user) @ {}", "ct_err:at_dup_alias:user")
        check("(u: user, u: user) @ {}", "ct_err:at_dup_alias:u")
        check("(user, u: user, user) @ {}", "ct_err:at_dup_alias:user")
    }

    @Test fun testMultipleClassesSimple() {
        check("(user, company) @ { user.firstName = 'Mark', company.name = 'Microsoft' }", "(user[10],company[400])")

        check("(u: user, c: company) @ { u.firstName = 'Mark', c.name = 'Microsoft' }", "(user[10],company[400])")

        check("(company: user, user: company) @ { company.firstName = 'Mark', user.name = 'Microsoft' }",
                "(user[10],company[400])")

        check("(u1: user, u2: user, user) @ { u1.firstName = 'Mark', u2.lastName = 'Gates', user.firstName = 'Sergey' }",
                "(user[10],user[40],user[50])")
    }

    @Test fun testNameResolutionClassVsAlias() {
        check("(user) @ { user.firstName = 'Bill' }", "user[40]")
        check("(u: user) @ { u.firstName = 'Bill' }", "user[40]")
        check("(u: user) @ { user.firstName = 'Bill' }", "ct_err:unknown_name:user")
    }

    @Test fun testNameResolutionLocalVsAttr() {
        // Local vs. attr: local wins.
        checkEx("return all user @ { firstName = 'Mark' };", "list<user>[user[10]]")
        checkEx("val firstName = 'Bill'; return all user @ { firstName = 'Mark' };", "list<user>[]")
        checkEx("val firstName = 'Bill'; return all user @ { firstName = firstName };",
                "list<user>[user[10],user[20],user[21],user[30],user[40],user[41],user[50],user[51]]")
    }

    @Test fun testNameResolutionAliasVsLocalAttr() {
        // Alias vs. attr: alias wins.
        check("all user @ { firstName = 'Mark' }", "list<user>[user[10]]")
        check("all (firstName: user) @ { firstName = 'Mark' }", "ct_err:binop_operand_type:==:user:text")
        check("all (firstName: user) @ { firstName.firstName = 'Mark' }", "list<user>[user[10]]")

        // Alias vs. local: alias wins.
        checkEx("val u = 'Bill'; return user @ { firstName = u };", "user[40]")
        checkEx("val u = 'Bill'; return (u: user) @ { firstName = u };", "ct_err:binop_operand_type:==:text:user")
        checkEx("val u = 'Bill'; return (u: user) @ { u.firstName = 'Mark' };", "user[10]")
    }

    @Test fun testAttributeAmbiguityName() {
        testDataClassDefs = arrayOf(
                "class user { name: text; }",
                "class company { name: text; }"
        )
        testDataInserts = arrayOf(
                mkins("user", "name", "0,'Bob'"),
                mkins("user", "name", "1,'Alice'"),
                mkins("company", "name", "2,'Xerox'"),
                mkins("company", "name", "3,'Bell'")
        )

        check("(user, company) @ { name = 'Bob' }", "ct_err:at_attr_name_ambig:name:2")
        check("(user, company) @ { name = 'Xerox' }", "ct_err:at_attr_name_ambig:name:2")
        check("(u: user, c: company) @ { name = 'Xerox' }", "ct_err:at_attr_name_ambig:name:2")
        check("(user, company) @ { user.name = 'Bob', company.name = 'Xerox' }", "(user[0],company[2])")
        check("(u1: user, u2: user) @ { name = 'Bob' }", "ct_err:at_attr_name_ambig:name:2")
        check("(c1: company, c2: company) @ { name = 'Bob' }", "ct_err:at_attr_name_ambig:name:2")
    }

    @Test fun testAttributeAmbiguityType() {
        testDataClassDefs = arrayOf(
                "class target { name: text; }",
                "class single { t: target; }",
                "class double { t1: target; t2: target; }"
        )
        testDataInserts = arrayOf(
                mkins("target", "name", "0,'A'"),
                mkins("target", "name", "1,'B'"),
                mkins("target", "name", "2,'C'"),
                mkins("single", "t", "0,0"),
                mkins("single", "t", "1,1"),
                mkins("double", "t1,t2", "0,0,1"),
                mkins("double", "t1,t2", "1,1,2"),
                mkins("double", "t1,t2", "2,2,0")
        )
        testDataBaseCode = """
            val tgt1 = target @ { name = 'A' };
            val tgt2 = target @ { name = 'B' };
            val tgt3 = target @ { name = 'C' };
        """.trimIndent()

        // Correct code.
        check("single @ { tgt1 }", "single[0]")
        check("single @ { tgt2 }", "single[1]")

        // Ambiguity between attributes of the same class.
        check("double @ { tgt1 }", "ct_err:at_attr_type_ambig:0:target:2")
        check("double @ { t1 = tgt1, tgt2 }", "ct_err:at_attr_type_ambig:1:target:2")
        check("double @ { t1 = tgt1, t2 = tgt2 }", "double[0]")
        check("double @ { t1 = tgt3, t2 = tgt1 }", "double[2]")

        // Ambiguity between attributes of different classes.
        check("(s1: single, s2: single) @ { tgt1 }", "ct_err:at_attr_type_ambig:0:target:2")
        check("(s1: single, s2: single) @ { tgt1, tgt2 }", "ct_err:at_attr_type_ambig:0:target:2")
        check("(s1: single, s2: single) @ { s1.t = tgt1, tgt2 }", "ct_err:at_attr_type_ambig:1:target:2")
        check("(s1: single, s2: single) @ { s1.t = tgt1, s2.t = tgt2 }", "(single[0],single[1])")
    }

    @Test fun testMultipleClassesCrossReference() {
        testDataClassDefs = arrayOf(
                "class person { name: text; cityName: text; }",
                "class city { name: text; countryName: text; }",
                "class country { name: text; }"
        )
        testDataInserts = arrayOf(
                mkins("person", "name,cityName", "100,'James','New York'"),
                mkins("person", "name,cityName", "101,'Phil','London'"),
                mkins("person", "name,cityName", "102,'Roman','Kyiv'"),

                mkins("city", "name,countryName", "200,'New York','USA'"),
                mkins("city", "name,countryName", "201,'Kyiv','Ukraine'"),
                mkins("city", "name,countryName", "202,'London','England'"),

                mkins("country", "name", "300,'England'"),
                mkins("country", "name", "301,'Ukraine'"),
                mkins("country", "name", "302,'USA'")
        )

        check("all (person, city, country) @ { city.name = person.cityName, country.name = city.countryName }",
                "list<(person,city,country)>[" +
                "(person[100],city[200],country[302])," +
                "(person[101],city[202],country[300])," +
                "(person[102],city[201],country[301])]")
    }

    @Test fun testAllFieldKinds() {
        testDataClassDefs = arrayOf(
                """class testee {
                        key k1: integer, k2: integer;
                        key k3: integer;
                        index i1: integer;
                        index i2: integer, i3: integer;
                        f1: integer;
                        f2: integer;
                }""".trimIndent(),
                "class proxy { ref: testee; }"
        )
        testDataInserts = arrayOf(
                mkins("testee", "k1,k2,k3,i1,i2,i3,f1,f2", "1,100,101,102,103,104,105,106,107"),
                mkins("testee", "k1,k2,k3,i1,i2,i3,f1,f2", "2,200,201,202,203,204,205,206,207"),
                mkins("testee", "k1,k2,k3,i1,i2,i3,f1,f2", "3,300,301,302,303,304,305,306,307"),
                mkins("proxy", "ref", "1,1"),
                mkins("proxy", "ref", "2,2"),
                mkins("proxy", "ref", "3,3")
        )

        // Direct fields of the query class.
        check("all testee @ { k1 = 100 }", "list<testee>[testee[1]]")
        check("all testee @ { k2 = 201 }", "list<testee>[testee[2]]")
        check("all testee @ { k3 = 302 }", "list<testee>[testee[3]]")
        check("all testee @ { i1 = 103 }", "list<testee>[testee[1]]")
        check("all testee @ { i2 = 204 }", "list<testee>[testee[2]]")
        check("all testee @ { i3 = 305 }", "list<testee>[testee[3]]")
        check("all testee @ { f1 = 106 }", "list<testee>[testee[1]]")
        check("all testee @ { f2 = 207 }", "list<testee>[testee[2]]")
        check("all testee @ { k1 = 300 }", "list<testee>[testee[3]]")
        check("all testee @ { k2 = 101 }", "list<testee>[testee[1]]")
        check("all testee @ { k3 = 202 }", "list<testee>[testee[2]]")
        check("all testee @ { i1 = 303 }", "list<testee>[testee[3]]")
        check("all testee @ { i2 = 104 }", "list<testee>[testee[1]]")
        check("all testee @ { i3 = 205 }", "list<testee>[testee[2]]")
        check("all testee @ { f1 = 306 }", "list<testee>[testee[3]]")
        check("all testee @ { f2 = 107 }", "list<testee>[testee[1]]")

        // Fields of a referenced class.
        check("all proxy @ { ref.k1 = 100 }", "list<proxy>[proxy[1]]")
        check("all proxy @ { ref.k2 = 201 }", "list<proxy>[proxy[2]]")
        check("all proxy @ { ref.k3 = 302 }", "list<proxy>[proxy[3]]")
        check("all proxy @ { ref.i1 = 103 }", "list<proxy>[proxy[1]]")
        check("all proxy @ { ref.i2 = 204 }", "list<proxy>[proxy[2]]")
        check("all proxy @ { ref.i3 = 305 }", "list<proxy>[proxy[3]]")
        check("all proxy @ { ref.f1 = 106 }", "list<proxy>[proxy[1]]")
        check("all proxy @ { ref.f2 = 207 }", "list<proxy>[proxy[2]]")
        check("all proxy @ { ref.k1 = 300 }", "list<proxy>[proxy[3]]")
        check("all proxy @ { ref.k2 = 101 }", "list<proxy>[proxy[1]]")
        check("all proxy @ { ref.k3 = 202 }", "list<proxy>[proxy[2]]")
        check("all proxy @ { ref.i1 = 303 }", "list<proxy>[proxy[3]]")
        check("all proxy @ { ref.i2 = 104 }", "list<proxy>[proxy[1]]")
        check("all proxy @ { ref.i3 = 205 }", "list<proxy>[proxy[2]]")
        check("all proxy @ { ref.f1 = 306 }", "list<proxy>[proxy[3]]")
        check("all proxy @ { ref.f2 = 107 }", "list<proxy>[proxy[1]]")
    }

    /*@Test*/ fun testNestedAtExpression() {
        check("all user @ { company = company @ { name = 'Facebook' } }", "list<user>[user[10]]")
        check("all user @ { company = company @ { name = 'Apple' } }", "list<user>[user[20],user[21]]")
        check("all user @ { company = company @ { name = 'Amazon' } }", "list<user>[user[30]]")
        check("all user @ { company = company @ { name = 'Microsoft' } }", "list<user>[user[40],user[41]]")
        check("all user @ { company = company @ { name = 'Google' } }", "list<user>[user[50],user[51]]")
        check("all user @ { company @ { name = 'Facebook' } }", "list<user>[user[10]]")
        check("all user @ { company @ { name = 'Apple' } }", "list<user>[user[20],user[21]]")
        check("all user @ { company @ { name = 'Amazon' } }", "list<user>[user[30]]")
        check("all user @ { company @ { name = 'Microsoft' } }", "list<user>[user[40],user[41]]")
        check("all user @ { company @ { name = 'Google' } }", "list<user>[user[50],user[51]]")
    }

    // TODO Later:

    // user @ { firstName = "Bill" or lastName = "Brin" }

    // query companyByUser(user): company = user.company

    // query usersByAge(yearsOld: integer): list<user> = user @ { currentYear() - yearOfBirth >= yearsOld }

    // query userFirstName(lastName: string): string = user @ { lastName } . firstName
    // query userPhoneAndEmail(lastName: string): (string, string) = user @ { lastName } . ( phone, eMail )
    // query userAge(lastName: string): integer = user @ { lastName } . ( currentYear() - yearOfBirth )
    // query userFirstName(user): string = user . firstName
    // query userPhoneAndEmail(user): (string, string) = user . ( phone, eMail )
    // query userAge(user): integer = user . ( currentYear() - yearOfBirth )

    // query userAndCompany(lastName: string): (user, company) = (user, company) @ { user.lastName = lastName, user.company_id = company.company_id }
    // query userAndCompanyNames(user_code: integer): (string, string, string) = (u: user, c:company) @ { u.code = user_code, u.company_id = c.company_id } . ( u.firstName, u.lastName, c.name )

    private fun check(code: String, expectedResult: String) {
        val queryCode = "return " + code + ";";
        checkEx(queryCode, expectedResult)
    }

    private fun checkEx(code: String, expectedResult: String) {
        checkEx(testDataClassDefs, testDataInserts, testDataBaseCode + code, expectedResult)
    }

    companion object {
        fun checkEx(classDefs: Array<String>, inserts: Array<String>, code: String, expectedResult: String) {
            val classDefsCode = classDefs.joinToString(" ")
            val fullCode = classDefsCode + " query q() { " + code + " }"
            checkFull(fullCode, inserts, expectedResult)
        }

        private fun checkFull(fullCode: String, inserts: Array<String>, expectedResult: String) {
            val actualResult = TestUtils.invoke(fullCode, inserts, arrayOf())
            assertEquals(expectedResult, actualResult)
        }

        val mkins = TestUtils::mkins
    }

    private object Ins {
        fun company(id: Int, name: String): String = mkins("company", "name", "$id, '$name'")

        fun user(id: Int, companyId: Int, firstName: String, lastName: String): String =
                mkins("user", "firstName,lastName,company", "$id, '$firstName', '$lastName', $companyId")
    }

    private class TestData(val classDefs: Array<String>, val inserts: Array<String>)
}
