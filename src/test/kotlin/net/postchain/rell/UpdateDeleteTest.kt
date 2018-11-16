package net.postchain.rell

import org.junit.*

class UpdateDeleteTest {
    private val classDefs = listOf(
            "class city { name: text; }",
            "class person { name: text; mutable city; mutable street: text; mutable house: integer; mutable score: integer; }"
    )

    private val tst = RellSqlTester(classDefs = classDefs)

    @After fun after() = tst.destroy()

    @Test fun testUpdatePersonSetScore() {
        createCitiesAndPersons()
        execute("update person @ { name = 'James' } ( score = 125 );")
        chk("person(4,James,3,Evergreen Ave,5,125)", "person(5,Mike,1,Grand St,7,250)")
    }

    @Test fun testUpdatePersonAddScore() {
        createCitiesAndPersons()
        execute("update person @ { name = 'James' } ( score = score + 50 );")
        chk("person(4,James,3,Evergreen Ave,5,150)", "person(5,Mike,1,Grand St,7,250)")
    }

    @Test fun testUpdatePersonMultiplyScore() {
        createCitiesAndPersons()
        execute("update person @ { name = 'James' } ( score = score * 3 );")
        chk("person(4,James,3,Evergreen Ave,5,300)", "person(5,Mike,1,Grand St,7,250)")
    }

    @Test fun testUpdatePersonAll() {
        createCitiesAndPersons()
        execute("update person @ {} ( score = score + 75 );")
        chk("person(4,James,3,Evergreen Ave,5,175)", "person(5,Mike,1,Grand St,7,325)")
    }

    @Test fun testUpdatePersonSetFullAddress() {
        createCitiesAndPersons()
        execute("update person @ { name = 'Mike' } ( city @ { 'San Francisco' }, street = 'Lawton St', house = 13 );")
        chk("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,2,Lawton St,13,250)")
    }

    @Test fun testUpdateMutable() {
        tst.classDefs = listOf(
                "class city { name: text; }",
                "class person { name: text; home: city; mutable work: city; base: integer; mutable score: integer; }"
        )

        execute("""
                val boston = create city('Boston');
                val seattle = create city('Seattle');
                val dallas = create city('Dallas');
                create person(name = 'Mike', home = boston, work = seattle, base = 100, score = 300);
                create person(name = 'Bob', home = seattle, work = dallas, base = 200, score = 500);
        """.trimIndent())

        chkAll(
                "city(1,Boston)",
                "city(2,Seattle)",
                "city(3,Dallas)",
                "person(4,Mike,1,2,100,300)",
                "person(5,Bob,2,3,200,500)"
        )

        executeErr("update person @ { name = 'Mike' } ( name = 'Bob' );", "ct_err:update_attr_not_mutable:name")
        executeErr("update person @ { name = 'Bob' } ( home = city @ { name = 'Boston' } );", "ct_err:update_attr_not_mutable:home")
        executeErr("update person @ { name = 'Mike' } ( base = 999 );", "ct_err:update_attr_not_mutable:base")

        chkAll(
                "city(1,Boston)",
                "city(2,Seattle)",
                "city(3,Dallas)",
                "person(4,Mike,1,2,100,300)",
                "person(5,Bob,2,3,200,500)"
        )

        execute("update person @ { name = 'Bob' } ( city @ { name = 'Dallas' } );")
        execute("update person @ { name = 'Mike' } ( 777 );")

        chkAll(
                "city(1,Boston)",
                "city(2,Seattle)",
                "city(3,Dallas)",
                "person(4,Mike,1,2,100,777)",
                "person(5,Bob,2,3,200,500)"
        )
    }

    @Test fun testDeletePerson() {
        createCitiesAndPersons()

        execute("delete person @ { name = 'James' };")
        chk("person(5,Mike,1,Grand St,7,250)")

        execute("delete person @ { name = 'Mike' };")
        chk()
    }

    @Test fun testDeleteCity() {
        createCities()

        execute("delete city @ { name = 'San Francisco' };")
        chkAll(
                "city(1,New York)",
                "city(3,Los Angeles)"
        )

        execute("delete city @ {};")
        chkAll()
    }

    @Test fun testUpdateClassAlias() {
        createCitiesAndPersons()

        execute("update p: person @ { p.name = 'Mike' } ( score = 999 );")
        chk("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,999)")

        executeErr("update p: person @ { person.name = 'Mike' } ( score = 777 );", "ct_err:unknown_name:person")
        chk("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,999)")

        execute("update person @ { person.name = 'Mike' } ( score = 777 );")
        chk("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,777)")
    }

    @Test fun testDeleteClassAlias() {
        createCitiesAndPersons()

        execute("delete p: person @ { p.name = 'Mike' };")
        chk("person(4,James,3,Evergreen Ave,5,100)")

        executeErr("delete p: person @ { person.name = 'James' };", "ct_err:unknown_name:person")
        chk("person(4,James,3,Evergreen Ave,5,100)")

        execute("delete person @ { person.name = 'James' };")
        chk()
    }

    @Test fun testUpdateExtraClass() {
        createCitiesAndPersons()

        execute("update p: person (c: city) @ { p.city = c, c.name = 'New York' } ( score = 999 );")
        chk("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,999)")
    }

    @Test fun testDeleteExtraClass() {
        createCitiesAndPersons()

        execute("delete p: person (c: city) @ { p.city = c, c.name = 'New York' };")
        chk("person(4,James,3,Evergreen Ave,5,100)")
    }

    @Test fun testErr() {
        executeErr("update foo @ {} ( x = 0 );", "ct_err:unknown_class:foo")
        executeErr("delete foo @ {};", "ct_err:unknown_class:foo")

        executeErr("update person @ {} ( foo = 123 );", "ct_err:attr_unknown_name:foo")

        executeErr("update person @ {} ( score = 123, score = 456 );", "ct_err:attr_dup_name:score")
        executeErr("update person @ {} ( score = 123, score = 123 );", "ct_err:attr_dup_name:score")
    }

    @Test fun testCompoundAssignmentInt() {
        createCitiesAndPersons()

        execute("update person @ {} ( score += 555 );")
        chk("person(4,James,3,Evergreen Ave,5,655)", "person(5,Mike,1,Grand St,7,805)")

        execute("update person @ {} ( score -= 123 );")
        chk("person(4,James,3,Evergreen Ave,5,532)", "person(5,Mike,1,Grand St,7,682)")

        execute("update person @ {} ( score *= 123 );")
        chk("person(4,James,3,Evergreen Ave,5,65436)", "person(5,Mike,1,Grand St,7,83886)")

        execute("update person @ {} ( score /= 55 );")
        chk("person(4,James,3,Evergreen Ave,5,1189)", "person(5,Mike,1,Grand St,7,1525)")

        execute("update person @ {} ( score %= 33 );")
        chk("person(4,James,3,Evergreen Ave,5,1)", "person(5,Mike,1,Grand St,7,7)")
    }

    @Test fun testCompoundAssignmentText() {
        createCitiesAndPersons()

        execute("update person @ {} ( street += ' Foo ' );")
        chk("person(4,James,3,Evergreen Ave Foo ,5,100)", "person(5,Mike,1,Grand St Foo ,7,250)")

        execute("update person @ {} ( street += 123 );")
        chk("person(4,James,3,Evergreen Ave Foo 123,5,100)", "person(5,Mike,1,Grand St Foo 123,7,250)")
        execute("update person @ {} ( street += ' ' );")
        chk("person(4,James,3,Evergreen Ave Foo 123 ,5,100)", "person(5,Mike,1,Grand St Foo 123 ,7,250)")

        execute("update person @ {} ( street += score > 200 );")
        chk("person(4,James,3,Evergreen Ave Foo 123 false,5,100)", "person(5,Mike,1,Grand St Foo 123 true,7,250)")
    }

    @Test fun testCompoundAssignmentErr() {
        executeErr("update person @ {} ( score += false );", "ct_err:binop_operand_type:+=:integer:boolean")
        executeErr("update person @ {} ( score += 'Hello' );", "ct_err:binop_operand_type:+=:integer:text")

        chkAssignmentErr("-=")
        chkAssignmentErr("*=")
        chkAssignmentErr("/=")
        chkAssignmentErr("%=")
    }

    private fun chkAssignmentErr(op: String) {
        executeErr("update person @ {} ( score $op false );", "ct_err:binop_operand_type:$op:integer:boolean")
        executeErr("update person @ {} ( score $op 'Hello' );", "ct_err:binop_operand_type:$op:integer:text")
        executeErr("update person @ {} ( street $op false );", "ct_err:binop_operand_type:$op:text:boolean")
        executeErr("update person @ {} ( street $op 123 );", "ct_err:binop_operand_type:$op:text:integer")
        executeErr("update person @ {} ( street $op 'Hello' );", "ct_err:binop_operand_type:$op:text:text")
    }

    @Test fun testRollback() {
        createCitiesAndPersons()

        chk("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,250)")

        executeErr("""
            update person @ { name = 'James' } ( score += 1000 );
            val x = 1 / 0;
            update person @ { name = 'Mike' } ( score += 500 );
        """.trimIndent(), "rt_err:expr_div_by_zero")

        chk("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,250)")

        executeErr("""
            update person @ { name = 'James' } ( score += 1000 );
            update person @ { name = 'Mike' } ( score += 500 );
            val x = 1 / 0;
        """.trimIndent(), "rt_err:expr_div_by_zero")

        chk("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,250)")

        execute("""
            update person @ { name = 'James' } ( score += 1000 );
            update person @ { name = 'Mike' } ( score += 500 );
        """.trimIndent())

        chk("person(4,James,3,Evergreen Ave,5,1100)", "person(5,Mike,1,Grand St,7,750)")
    }

    @Test fun testAccessChangedRecords() {
        createCitiesAndPersons()

        execute("""
            print(person @* {} ( name, score ));
            update person @ { name = 'James' } ( score += 100 );
            print(person @* {} ( name, score ));
            update person @ { name = 'Mike' } ( score += 200 );
            print(person @* {} ( name, score ));
            update person @ {} ( score /= 2 );
            print(person @* {} ( name, score ));
        """.trimIndent())

        tst.chkStdout(
                "[(name=James,score=100), (name=Mike,score=250)]",
                "[(name=James,score=200), (name=Mike,score=250)]",
                "[(name=James,score=200), (name=Mike,score=450)]",
                "[(name=James,score=100), (name=Mike,score=225)]"
        )
    }

    @Test fun testUpdateNameResolution() {
        createCitiesAndPersons()
        execute("val score = 10; update person @ { name = 'Mike' } ( score );")
        chk("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,10)")
    }

    private fun createCities() {
        chkAll()

        execute("create city ( 'New York' );")
        chkAll("city(1,New York)")

        execute("create city ( 'San Francisco' );")
        chkAll("city(1,New York)", "city(2,San Francisco)")

        execute("create city ( 'Los Angeles' );")
        chkAll("city(1,New York)", "city(2,San Francisco)", "city(3,Los Angeles)")
    }

    private fun createCitiesAndPersons() {
        createCities()

        execute("create person ( name = 'James', city @ { 'Los Angeles' }, street = 'Evergreen Ave', house = 5, score = 100 );")
        chk("person(4,James,3,Evergreen Ave,5,100)")

        execute("create person ( name = 'Mike', city @ { 'New York' }, street =  'Grand St', house = 7, score = 250 );")
        chk("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,250)")
    }

    private fun execute(code: String) = tst.execOp(code)
    private fun executeErr(code: String, expected: String) = tst.chkOp(code, expected)

    private fun chk(vararg expectedArray: String) {
        val cities = arrayOf(
                "city(1,New York)",
                "city(2,San Francisco)",
                "city(3,Los Angeles)"
        )
        chkAll(*(cities + expectedArray))
    }

    private fun chkAll(vararg expectedArray: String) = tst.chkData(expectedArray.toList())
}
