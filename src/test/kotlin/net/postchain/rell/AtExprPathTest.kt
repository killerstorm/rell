package net.postchain.rell

import org.junit.Test

class AtExprPathTest {
    private val classDefs = arrayOf(
            "class country { name: text; }",
            "class state { name: text; country; }",
            "class city { name: text; state; }",
            "class company { name: text; hq: city; }",
            "class department { name: text; company; }",
            "class person { name: text; city; department; }"
    )

    private val inserts = arrayOf(
            Ins.country(100, "USA"),
            Ins.country(101, "Germany"),

            Ins.state(200, "CA", 100),
            Ins.state(201, "WA", 100),
            Ins.state(202, "NV", 100),
            Ins.state(203, "NRW", 101),
            Ins.state(204, "BW", 101),
            Ins.state(205, "BY", 101),

            Ins.city(300, "San Francisco", 200),
            Ins.city(301, "Los Angeles", 200),
            Ins.city(302, "Seattle", 201),
            Ins.city(303, "Las Vegas", 202),
            Ins.city(304, "Cologne", 203),
            Ins.city(305, "Dusseldorf", 203),
            Ins.city(306, "Stuttgart", 204),
            Ins.city(307, "Munich", 205),

            Ins.company(400, "Twitter", 300),
            Ins.company(401, "Uber", 300),
            Ins.company(402, "Netflix", 301),
            Ins.company(403, "Amazon", 302),
            Ins.company(404, "Westwood", 303),
            Ins.company(405, "Hosteurope", 304),
            Ins.company(406, "Metro", 305),
            Ins.company(407, "Mercedes", 306),
            Ins.company(408, "BMW", 307),

            Ins.department(500, "Research", 400),
            Ins.department(501, "Development", 400),
            Ins.department(502, "Legal", 401),
            Ins.department(503, "Accounting", 402),
            Ins.department(504, "Delivery", 403),
            Ins.department(505, "Security", 404),
            Ins.department(506, "Systems", 405),
            Ins.department(507, "Shipping", 406),
            Ins.department(508, "Design", 407),
            Ins.department(509, "Manufacturing", 408),

            Ins.person(600, "Bob", 305, 503),
            Ins.person(601, "Jack", 303, 506),
            Ins.person(602, "Donald", 306, 509),
            Ins.person(603, "George", 304, 508),
            Ins.person(604, "Jim", 307, 505),
            Ins.person(605, "John", 301, 502),
            Ins.person(606, "Markus", 302, 501),
            Ins.person(607, "Otto", 300, 504),
            Ins.person(608, "Victor", 303, 507),
            Ins.person(609, "Roman", 306, 505),
            Ins.person(610, "Andrew", 302, 504),
            Ins.person(611, "Bill", 300, 506)
    )

    @Test fun testSimpleAttr() {
        check("all person @ { city.name = 'San Francisco' }", "list<person>[person[607],person[611]]")
        check("all person @ { city.name = 'Las Vegas' }", "list<person>[person[601],person[608]]")
        check("all person @ { city.name = 'Munich' }", "list<person>[person[604]]")

        check("all person @ { city.state.name = 'CA' }", "list<person>[person[605],person[607],person[611]]")
        check("all person @ { city.state.name = 'WA' }", "list<person>[person[606],person[610]]")
        check("all person @ { city.state.name = 'NRW' }", "list<person>[person[600],person[603]]")

        check("all person @ { city.state.country.name = 'USA' }",
                "list<person>[person[601],person[605],person[606],person[607],person[608],person[610],person[611]]")
        check("all person @ { city.state.country.name = 'Germany' }",
                "list<person>[person[600],person[602],person[603],person[604],person[609]]")

        check("all company @ { hq.state.country.name = 'USA' }",
                "list<company>[company[400],company[401],company[402],company[403],company[404]]")
        check("all company @ { hq.state.country.name = 'Germany' }",
                "list<company>[company[405],company[406],company[407],company[408]]")
    }

    @Test fun testSimpleReference() {
        checkEx("val x = city @ { name = 'Seattle' }; return all person @ { city = x };",
                "list<person>[person[606],person[610]]")
        checkEx("val x = city @ { name = 'Cologne' }; return all person @ { city = x };",
                "list<person>[person[603]]")
        checkEx("val x = city @ { name = 'Stuttgart' }; return all person @ { city = x };",
                "list<person>[person[602],person[609]]")

        checkEx("val x = state @ { name = 'NV' }; return all person @ { city.state = x };",
                "list<person>[person[601],person[608]]")
        checkEx("val x = state @ { name = 'BY' }; return all person @ { city.state = x };",
                "list<person>[person[604]]")
        checkEx("val x = state @ { name = 'CA' }; return all person @ { city.state = x };",
                "list<person>[person[605],person[607],person[611]]")

        checkEx("val x = country @ { name = 'USA' }; return all person @ { city.state.country = x };",
                "list<person>[person[601],person[605],person[606],person[607],person[608],person[610],person[611]]")
        checkEx("val x = country @ { name = 'Germany' }; return all person @ { city.state.country = x };",
                "list<person>[person[600],person[602],person[603],person[604],person[609]]")
    }

    @Test fun testCompanyLocation() {
        check("all person @ { department.company.hq.name = 'Seattle' }", "list<person>[person[607],person[610]]")
        check("all person @ { department.company.hq.name = 'Dusseldorf' }", "list<person>[person[608]]")
        check("all person @ { department.company.hq.name = 'Los Angeles' }", "list<person>[person[600]]")

        check("all person @ { department.company.hq.state.country.name = 'USA' }",
                "list<person>[person[600],person[604],person[605],person[606],person[607],person[609],person[610]]")
        check("all person @ { department.company.hq.state.country.name = 'Germany' }",
                "list<person>[person[601],person[602],person[603],person[608],person[611]]")
    }

    @Test fun testHomeAndCompanyLocation() {
        check("all person @ { city.name = 'Las Vegas', department.company.hq.name = 'Cologne' }",
                "list<person>[person[601]]")
        check("all person @ { city.name = 'Stuttgart', department.company.hq.name = 'Las Vegas' }",
                "list<person>[person[609]]")
        check("all person @ { city.name = 'Seattle', department.company.hq.name = 'San Francisco' }",
                "list<person>[person[606]]")

        check("all person @ { city.state.name = 'CA', department.company.hq.state.name = 'NRW' }",
                "list<person>[person[611]]")
        check("all person @ { city.state.name = 'BY', department.company.hq.state.name = 'CA' }",
                "list<person>[]")
        check("all person @ { city.state.name = 'NV', department.company.hq.state.name = 'NRW' }",
                "list<person>[person[601],person[608]]")
    }

    @Test fun testHomeCompanyCrossReference() {
        check("all person @ { city = department.company.hq }", "list<person>[person[610]]")
        check("all person @ { city.state = department.company.hq.state }", "list<person>[person[605],person[610]]")
        check("all person @ { city.state.country = department.company.hq.state.country }",
                "list<person>[person[602],person[603],person[605],person[606],person[607],person[610]]")
    }

    @Test fun testMultiClass() {
        check("all (p1: person, p2: person) @ { p1.city.name = 'San Francisco', p2.department.company.name = 'Amazon' }",
                "list<(person,person)>[(person[607],person[607]),(person[607],person[610]),(person[611],person[607]),(person[611],person[610])]")
        check("all (p1: person, p2: person) @ { p1.city.name = 'Munich', p2.department.company.name = 'Mercedes' }",
                "list<(person,person)>[(person[604],person[603])]")
        check("all (p1: person, p2: person) @ { p1.city.name = 'Las Vegas', p2.department.company.name = 'Twitter' }",
                "list<(person,person)>[(person[601],person[606]),(person[608],person[606])]")
    }

    @Test fun testInvalidPath() {
        check("person @ { foo }", "ct_err:unknown_name:foo")
        check("(p: person) @ { p.foo = 123 }", "ct_err:bad_path_expr:p.foo")
        check("(p: person) @ { p.city.foo = 123 }", "ct_err:bad_path_expr:p.city.foo")
        check("(p: person) @ { p.city.foo.bar = 123 }", "ct_err:bad_path_expr:p.city.foo")
        check("(p: person) @ { p.city.state.foo = 123 }", "ct_err:bad_path_expr:p.city.state.foo")
        check("(p: person) @ { p.city.state.country.foo = 123 }", "ct_err:bad_path_expr:p.city.state.country.foo")
        check("(p: person) @ { p.city.state.country.foo.bar = 123 }", "ct_err:bad_path_expr:p.city.state.country.foo")
        check("person @ { city.state.country.foo.bar = 123 }", "ct_err:bad_path_expr:city.state.country.foo")
        check("person @ { person.city.state.country.foo.bar = 123 }", "ct_err:bad_path_expr:person.city.state.country.foo")
    }

    private fun check(code: String, expectedResult: String) {
        val queryCode = "return " + code + ";";
        checkEx(queryCode, expectedResult)
    }

    private fun checkEx(code: String, expectedResult: String) {
        AtExprTest.checkEx(classDefs, inserts, code, expectedResult)
    }

    private object Ins {
        fun country(id: Int, name: String): String = mkins("country", "name", "$id, '$name'")
        fun state(id: Int, name: String, country: Int): String = mkins("state", "name,country", "$id, '$name', $country")
        fun city(id: Int, name: String, state: Int): String = mkins("city", "name,state", "$id, '$name', $state")
        fun company(id: Int, name: String, hq: Int): String = mkins("company", "name,hq", "$id, '$name', $hq")

        fun department(id: Int, name: String, company: Int): String =
                mkins("department", "name,company", "$id, '$name', $company")

        fun person(id: Int, name: String, city: Int, department: Int): String =
                mkins("person", "name,city,department", "$id, '$name', $city, $department")

        val mkins = TestUtils::mkins
    }
}
