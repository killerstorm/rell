package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.*

class UpdateDeleteTest: BaseRellTest() {
    override fun classDefs() = listOf(
            "class city { name: text; }",
            "class person { name: text; mutable city; mutable street: text; mutable house: integer; mutable score: integer; }"
    )

    @Test fun testUpdatePersonSetScore() {
        createCitiesAndPersons()
        chkOp("update person @ { .name == 'James' } ( score = 125 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,125)", "person(5,Mike,1,Grand St,7,250)")
    }

    @Test fun testUpdatePersonAddScore() {
        createCitiesAndPersons()
        chkOp("update person @ { .name == 'James' } ( score = .score + 50 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,150)", "person(5,Mike,1,Grand St,7,250)")
    }

    @Test fun testUpdatePersonMultiplyScore() {
        createCitiesAndPersons()
        chkOp("update person @ { .name == 'James' } ( score = .score * 3 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,300)", "person(5,Mike,1,Grand St,7,250)")
    }

    @Test fun testUpdatePersonAll() {
        createCitiesAndPersons()
        chkOp("update person @* {} ( score = .score + 75 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,175)", "person(5,Mike,1,Grand St,7,325)")
    }

    @Test fun testUpdatePersonSetFullAddress() {
        createCitiesAndPersons()
        chkOp("update person @ { .name == 'Mike' } ( city @ { 'San Francisco' }, street = 'Lawton St', house = 13 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,2,Lawton St,13,250)")
    }

    @Test fun testUpdateMutable() {
        tst.defs = listOf(
                "class city { name: text; }",
                "class person { name: text; home: city; mutable work: city; base: integer; mutable score: integer; }"
        )

        chkOp("""
                val boston = create city('Boston');
                val seattle = create city('Seattle');
                val dallas = create city('Dallas');
                create person(name = 'Mike', home = boston, work = seattle, base = 100, score = 300);
                create person(name = 'Bob', home = seattle, work = dallas, base = 200, score = 500);
        """.trimIndent())

        chkData(
                "city(1,Boston)",
                "city(2,Seattle)",
                "city(3,Dallas)",
                "person(4,Mike,1,2,100,300)",
                "person(5,Bob,2,3,200,500)"
        )

        chkOp("update person @ { .name == 'Mike' } ( name = 'Bob' );", "ct_err:update_attr_not_mutable:name")
        chkOp("update person @ { .name == 'Bob' } ( home = city @ { .name == 'Boston' } );", "ct_err:update_attr_not_mutable:home")
        chkOp("update person @ { .name == 'Mike' } ( base = 999 );", "ct_err:update_attr_not_mutable:base")

        chkData(
                "city(1,Boston)",
                "city(2,Seattle)",
                "city(3,Dallas)",
                "person(4,Mike,1,2,100,300)",
                "person(5,Bob,2,3,200,500)"
        )

        chkOp("update person @ { .name == 'Bob' } ( city @ { .name == 'Dallas' } );")
        chkOp("update person @ { .name == 'Mike' } ( 777 );")

        chkData(
                "city(1,Boston)",
                "city(2,Seattle)",
                "city(3,Dallas)",
                "person(4,Mike,1,2,100,777)",
                "person(5,Bob,2,3,200,500)"
        )
    }

    @Test fun testDeletePerson() {
        createCitiesAndPersons()

        chkOp("delete person @ { .name == 'James' };")
        chkDataCommon("person(5,Mike,1,Grand St,7,250)")

        chkOp("delete person @ { .name == 'Mike' };")
        chkDataCommon()
    }

    @Test fun testDeleteCity() {
        createCities()

        chkOp("delete city @ { .name == 'San Francisco' };")
        chkData(
                "city(1,New York)",
                "city(3,Los Angeles)"
        )

        chkOp("delete city @* {};")
        chkData()
    }

    @Test fun testUpdateClassAlias() {
        createCitiesAndPersons()

        chkOp("update (p: person) @ { p.name == 'Mike' } ( score = 999 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,999)")

        chkOp("update (p: person) @ { person.name == 'Mike' } ( score = 777 );", "ct_err:unknown_name:person")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,999)")

        chkOp("update person @ { person.name == 'Mike' } ( score = 777 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,777)")
    }

    @Test fun testDeleteClassAlias() {
        createCitiesAndPersons()

        chkOp("delete (p: person) @ { p.name == 'Mike' };")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,100)")

        chkOp("delete (p: person) @ { person.name == 'James' };", "ct_err:unknown_name:person")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,100)")

        chkOp("delete person @ { person.name == 'James' };")
        chkDataCommon()
    }

    @Test fun testUpdateExtraClass() {
        createCitiesAndPersons()

        chkOp("update (p: person, c: city) @ { p.city == c, c.name == 'New York' } ( score = 999 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,999)")
    }

    @Test fun testDeleteExtraClass() {
        createCitiesAndPersons()

        chkOp("delete (p: person, c: city) @ { p.city == c, c.name == 'New York' };")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,100)")
    }

    @Test fun testErr() {
        chkOp("update foo @ {} ( x = 0 );", "ct_err:unknown_class:foo")
        chkOp("delete foo @ {};", "ct_err:unknown_class:foo")

        chkOp("update person @ {} ( foo = 123 );", "ct_err:attr_unknown_name:foo")

        chkOp("update person @ {} ( score = 123, score = 456 );", "ct_err:attr_dup_name:score")
        chkOp("update person @ {} ( score = 123, score = 123 );", "ct_err:attr_dup_name:score")
    }

    @Test fun testCompoundAssignmentInt() {
        createCitiesAndPersons()

        chkOp("update person @* {} ( score += 555 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,655)", "person(5,Mike,1,Grand St,7,805)")

        chkOp("update person @* {} ( score -= 123 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,532)", "person(5,Mike,1,Grand St,7,682)")

        chkOp("update person @* {} ( score *= 123 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,65436)", "person(5,Mike,1,Grand St,7,83886)")

        chkOp("update person @* {} ( score /= 55 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,1189)", "person(5,Mike,1,Grand St,7,1525)")

        chkOp("update person @* {} ( score %= 33 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,1)", "person(5,Mike,1,Grand St,7,7)")
    }

    @Test fun testCompoundAssignmentText() {
        createCitiesAndPersons()

        chkOp("update person @* {} ( street += ' Foo ' );")
        chkDataCommon("person(4,James,3,Evergreen Ave Foo ,5,100)", "person(5,Mike,1,Grand St Foo ,7,250)")

        chkOp("update person @* {} ( street += 123 );")
        chkDataCommon("person(4,James,3,Evergreen Ave Foo 123,5,100)", "person(5,Mike,1,Grand St Foo 123,7,250)")
        chkOp("update person @* {} ( street += ' ' );")
        chkDataCommon("person(4,James,3,Evergreen Ave Foo 123 ,5,100)", "person(5,Mike,1,Grand St Foo 123 ,7,250)")

        chkOp("update person @* {} ( street += .score > 200 );")
        chkDataCommon("person(4,James,3,Evergreen Ave Foo 123 false,5,100)", "person(5,Mike,1,Grand St Foo 123 true,7,250)")
    }

    @Test fun testCompoundAssignmentErr() {
        chkOp("update person @ {} ( score += false );", "ct_err:binop_operand_type:+=:integer:boolean")
        chkOp("update person @ {} ( score += 'Hello' );", "ct_err:binop_operand_type:+=:integer:text")

        chkAssignmentErr("-=")
        chkAssignmentErr("*=")
        chkAssignmentErr("/=")
        chkAssignmentErr("%=")
    }

    private fun chkAssignmentErr(op: String) {
        chkOp("update person @ {} ( score $op false );", "ct_err:binop_operand_type:$op:integer:boolean")
        chkOp("update person @ {} ( score $op 'Hello' );", "ct_err:binop_operand_type:$op:integer:text")
        chkOp("update person @ {} ( street $op false );", "ct_err:binop_operand_type:$op:text:boolean")
        chkOp("update person @ {} ( street $op 123 );", "ct_err:binop_operand_type:$op:text:integer")
        chkOp("update person @ {} ( street $op 'Hello' );", "ct_err:binop_operand_type:$op:text:text")
    }

    @Test fun testUpdateDotAttribute() {
        createCitiesAndPersons()

        chkOp("update person @ { .name == 'James' } ( .score = 123 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,123)", "person(5,Mike,1,Grand St,7,250)")

        chkOp("update person @* {} ( .score += 456 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,579)", "person(5,Mike,1,Grand St,7,706)")

        chkOp("update person @* {} ( .score = .score - 789 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,-210)", "person(5,Mike,1,Grand St,7,-83)")
    }

    @Test fun testRollback() {
        createCitiesAndPersons()

        chkDataCommon("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,250)")

        chkOp("""
            update person @ { .name == 'James' } ( score += 1000 );
            val x = 1 / 0;
            update person @ { .name == 'Mike' } ( score += 500 );
        """.trimIndent(), "rt_err:expr_div_by_zero")

        chkDataCommon("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,250)")

        chkOp("""
            update person @ { .name == 'James' } ( score += 1000 );
            update person @ { .name == 'Mike' } ( score += 500 );
            val x = 1 / 0;
        """.trimIndent(), "rt_err:expr_div_by_zero")

        chkDataCommon("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,250)")

        chkOp("""
            update person @ { .name == 'James' } ( score += 1000 );
            update person @ { .name == 'Mike' } ( score += 500 );
        """.trimIndent())

        chkDataCommon("person(4,James,3,Evergreen Ave,5,1100)", "person(5,Mike,1,Grand St,7,750)")
    }

    @Test fun testAccessChangedRecords() {
        createCitiesAndPersons()

        chkOp("""
            print(person @* {} ( .name, .score ));
            update person @ { .name == 'James' } ( score += 100 );
            print(person @* {} ( .name, .score ));
            update person @ { .name == 'Mike' } ( score += 200 );
            print(person @* {} ( .name, .score ));
            update person @* {} ( score /= 2 );
            print(person @* {} ( .name, .score ));
        """.trimIndent())

        chkStdout(
                "[(name=James,score=100), (name=Mike,score=250)]",
                "[(name=James,score=200), (name=Mike,score=250)]",
                "[(name=James,score=200), (name=Mike,score=450)]",
                "[(name=James,score=100), (name=Mike,score=225)]"
        )
    }

    @Test fun testUpdateNameResolution() {
        createCitiesAndPersons()

        chkOp("val score = 10; update person @ { .name == 'Mike' } ( score );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,10)")

        chkOp("val score = 'Hello'; update person @ { .name == 'Mike' } ( score );",
                "ct_err:attr_bad_type:0:score:integer:text")
    }

    @Test fun testUpdateNameConflictAliasVsLocal() {
        createCitiesAndPersons()

        chkOp("update (p: person) @ { .name == 'James' } ( .score = 123 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,123)", "person(5,Mike,1,Grand St,7,250)")

        chkOp("val p = 123; update (p: person) @ { .name == 'James' } ( .score = 123 );",
                "ct_err:expr_at_conflict_alias:p")

        chkOp("update (person, p: person) @ { person.name == 'James' } ( .score = 456 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,456)", "person(5,Mike,1,Grand St,7,250)")

        chkOp("val p = 123; update (person, p: person) @ { person.name == 'James' } ( .score = 789 );",
                "ct_err:expr_at_conflict_alias:p")
    }

    @Test fun testDeleteNameConflictAliasVsLocal() {
        createCitiesAndPersons()
        chkOp("val p = 123; delete (p: person) @ { .name == 'James' };", "ct_err:expr_at_conflict_alias:p")
        chkOp("val p = 123; delete (person, p: person) @ { person.name == 'James' };", "ct_err:expr_at_conflict_alias:p")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,250)")
    }

    @Test fun testUpdateCardinality() {
        fun james(score: Int) = "person(4,James,3,Evergreen Ave,5,$score)"
        fun mike(score: Int) = "person(5,Mike,1,Grand St,7,$score)"

        createCitiesAndPersons()
        chkDataCommon(james(100), mike(250))

        // @
        chkOp("update person @ { .name == 'Unknown' } ( score = 10 );", "rt_err:at:wrong_count:0")
        chkDataCommon(james(100), mike(250))
        chkOp("update person @ { .name == 'James' } ( score = 11 );")
        chkDataCommon(james(11), mike(250))
        chkOp("update person @ {} ( score = 12 );", "rt_err:at:wrong_count:2")
        chkDataCommon(james(11), mike(250))

        // @?
        chkOp("update person @? { .name == 'Unknown' } ( score = 20 );")
        chkDataCommon(james(11), mike(250))
        chkOp("update person @? { .name == 'James' } ( score = 21 );")
        chkDataCommon(james(21), mike(250))
        chkOp("update person @? {} ( score = 22 );", "rt_err:at:wrong_count:2")
        chkDataCommon(james(21), mike(250))

        // @+
        chkOp("update person @+ { .name == 'Unknown' } ( score = 30 );", "rt_err:at:wrong_count:0")
        chkDataCommon(james(21), mike(250))
        chkOp("update person @+ { .name == 'James' } ( score = 31 );")
        chkDataCommon(james(31), mike(250))
        chkOp("update person @+ {} ( score = 32 );")
        chkDataCommon(james(32), mike(32))

        // @*
        chkOp("update person @* { .name == 'Unknown' } ( score = 40 );")
        chkDataCommon(james(32), mike(32))
        chkOp("update person @* { .name == 'James' } ( score = 41 );")
        chkDataCommon(james(41), mike(32))
        chkOp("update person @* {} ( score = 42 );")
        chkDataCommon(james(42), mike(42))
    }

    @Test fun testDeleteCardinality() {
        val james = "person(4,James,3,Evergreen Ave,5,100)"
        val mike = "person(5,Mike,1,Grand St,7,250)"

        // @
        resetChkOp("delete person @ { .name == 'Unknown' };", "rt_err:at:wrong_count:0")
        chkDataCommon(james, mike)
        resetChkOp("delete person @ { .name == 'James' };")
        chkDataCommon(mike)
        resetChkOp("delete person @ {};", "rt_err:at:wrong_count:2")
        chkDataCommon(james, mike)

        // @?
        resetChkOp("delete person @? { .name == 'Unknown' };")
        chkDataCommon(james, mike)
        resetChkOp("delete person @? { .name == 'James' };")
        chkDataCommon(mike)
        resetChkOp("delete person @? {};", "rt_err:at:wrong_count:2")
        chkDataCommon(james, mike)

        // @+
        resetChkOp("delete person @+ { .name == 'Unknown' };", "rt_err:at:wrong_count:0")
        chkDataCommon(james, mike)
        resetChkOp("delete person @+ { .name == 'James' };")
        chkDataCommon(mike)
        resetChkOp("delete person @+ {};")
        chkDataCommon()

        // @*
        resetChkOp("delete person @* { .name == 'Unknown' };")
        chkDataCommon(james, mike)
        resetChkOp("delete person @* { .name == 'James' };")
        chkDataCommon(mike)
        resetChkOp("delete person @* {};")
        chkDataCommon()
    }

    @Test fun testDeleteNoAttributes() {
        tst.defs = listOf("class person {}")
        chkOp("create person();")
        chkData("person(1)")
        chkOp("delete person@*{};")
        chkData()
    }

    private fun resetChkOp(code: String, expected: String = "OK") {
        tst.resetRowid()
        chkOp("delete person @* {}; delete city @* {};")
        createCitiesAndPersons()
        chkOp(code, expected)
    }

    private fun createCities() {
        chkData()

        chkOp("create city ( 'New York' );")
        chkData("city(1,New York)")

        chkOp("create city ( 'San Francisco' );")
        chkData("city(1,New York)", "city(2,San Francisco)")

        chkOp("create city ( 'Los Angeles' );")
        chkData("city(1,New York)", "city(2,San Francisco)", "city(3,Los Angeles)")
    }

    private fun createCitiesAndPersons() {
        createCities()

        chkOp("create person ( name = 'James', city @ { 'Los Angeles' }, street = 'Evergreen Ave', house = 5, score = 100 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,100)")

        chkOp("create person ( name = 'Mike', city @ { 'New York' }, street =  'Grand St', house = 7, score = 250 );")
        chkDataCommon("person(4,James,3,Evergreen Ave,5,100)", "person(5,Mike,1,Grand St,7,250)")
    }

    private fun chkDataCommon(vararg expectedArray: String) {
        val cities = arrayOf(
                "city(1,New York)",
                "city(2,San Francisco)",
                "city(3,Los Angeles)"
        )
        chkData(*(cities + expectedArray))
    }
}
