/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.SqlTestUtils
import org.junit.Test

class AtExprTest: BaseRellTest() {
    override fun entityDefs() = listOf(
            "entity company { name: text; }",
            "entity user { firstName: text; lastName: text; company; }"
    )

    override fun objInserts() = listOf(
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
            Ins.user(41, 400, "Paul", "Allen"),
            Ins.user(50, 500, "Sergey", "Brin"),
            Ins.user(51, 500, "Larry", "Page")
    )

    @Test fun testEmptyWhere() {
        chk("user @* {}", "list<user>[user[10],user[20],user[21],user[30],user[40],user[41],user[50],user[51]]")
        chk("company @* {}", "list<company>[company[100],company[200],company[300],company[400],company[500]]")
    }

    @Test fun testNoRecordsFound() {
        chk("user @ { .lastName == 'Socrates' }", "rt_err:at:wrong_count:0")
    }

    @Test fun testManyRecordsFound() {
        chk("user @ { .firstName == 'Steve' }", "rt_err:at:wrong_count:2")
    }

    @Test fun testFindByObjectReference() {
        chkEx("{ val corp = company @ { .name == 'Facebook' }; return user @ { .company == corp }; }", "user[10]")
    }

    @Test fun testFindUserWithSameName() {
        chk("(u1: user, u2: user) @* { u1.firstName == u2.firstName, u1 != u2 }",
                "list<(u1:user,u2:user)>[(user[20],user[21]),(user[21],user[20])]")
    }

    @Test fun testAttributeByVariableName() {
        chkEx("{ val firstName = 'Bill'; return user @ { firstName }; }", "user[40]")
        chkEx("{ val lastName = 'Gates'; return user @ { lastName }; }", "user[40]")
        chkEx("{ val name = 'Microsoft'; return company @ { name }; }", "company[400]")
        chkEx("{ val name = 'Bill'; return user @ { name }; }",
                "ct_err:at_where:var_manyattrs_name:0:name:text:user.firstName,user.lastName")
        chkEx("{ val name = 12345; return company @ { name }; }", "ct_err:at_where:var_noattrs:0:name:integer")
        chkEx("{ val company = company @ { .name == 'Facebook' }; return user @ { company }; }", "user[10]")
    }

    @Test fun testAttributeByExpressionType() {
        chkEx("{ val corp = company @ { .name == 'Facebook' }; return user @ { corp }; }", "user[10]")
        chkEx("{ val corp = company @ { .name == 'Microsoft' }; return user @* { corp }; }", "list<user>[user[40],user[41]]")
    }

    @Test fun testAttributeByExpressionTypeNullable() {
        chkEx("{ val c = _nullable(company @ { .name == 'Facebook' }); if (c == null) return null; return user @* { c }; }",
                "list<user>[user[10]]")
    }

    @Test fun testAttributeByNameAndType() {
        def("entity foo { name: text; }")
        def("entity bar { name: text; }")
        def("entity foo_owner { name: text; stuff: foo; foo: foo; bar: bar; }")
        def("entity bar_owner { name: text; stuff: bar; foo: foo; bar: bar; }")

        tst.inserts = listOf()
        insert("c0.foo", "name", "0,'Foo-1'")
        insert("c0.foo", "name", "1,'Foo-2'")
        insert("c0.bar", "name", "2,'Bar-1'")
        insert("c0.bar", "name", "3,'Bar-2'")
        insert("c0.foo_owner", "name,stuff,foo,bar", "4,'Bob',0,1,2")
        insert("c0.foo_owner", "name,stuff,foo,bar", "5,'Alice',1,0,3")
        insert("c0.bar_owner", "name,stuff,foo,bar", "6,'Trudy',2,1,3")
        insert("c0.bar_owner", "name,stuff,foo,bar", "7,'Andrew',3,0,2")

        val base = """
            val foo1 = foo @ { .name == 'Foo-1' };
            val foo2 = foo @ { .name == 'Foo-2' };
            val bar1 = bar @ { .name == 'Bar-1' };
            val bar2 = bar @ { .name == 'Bar-2' };
        """.trimIndent()

        chkEx("{ $base val name = 'Bob'; return (foo_owner, bar_owner) @* { name }; }",
                "ct_err:at_where:var_manyattrs_nametype:0:name:text:foo_owner.name,bar_owner.name")
        chkEx("{ $base val garbage = foo1; return (foo_owner, bar_owner) @* { garbage }; }",
                "ct_err:at_where:var_manyattrs_name:0:garbage:foo:foo_owner.stuff,foo_owner.foo,bar_owner.foo")
        chkEx("{ $base val garbage = bar1; return (foo_owner, bar_owner) @* { garbage }; }",
                "ct_err:at_where:var_manyattrs_name:0:garbage:bar:foo_owner.bar,bar_owner.stuff,bar_owner.bar")

        chkEx("{ $base val stuff = foo1; return (foo_owner, bar_owner) @* { stuff }; }",
                "list<(foo_owner:foo_owner,bar_owner:bar_owner)>[(foo_owner[4],bar_owner[6]),(foo_owner[4],bar_owner[7])]")
        chkEx("{ $base val stuff = foo2; return (foo_owner, bar_owner) @* { stuff }; }",
                "list<(foo_owner:foo_owner,bar_owner:bar_owner)>[(foo_owner[5],bar_owner[6]),(foo_owner[5],bar_owner[7])]")
        chkEx("{ $base val stuff = bar1; return (foo_owner, bar_owner) @* { stuff }; }",
                "list<(foo_owner:foo_owner,bar_owner:bar_owner)>[(foo_owner[4],bar_owner[6]),(foo_owner[5],bar_owner[6])]")
        chkEx("{ $base val stuff = bar2; return (foo_owner, bar_owner) @* { stuff }; }",
                "list<(foo_owner:foo_owner,bar_owner:bar_owner)>[(foo_owner[4],bar_owner[7]),(foo_owner[5],bar_owner[7])]")
    }

    @Test fun testSingleEntityAlias() {
        chk("(u: user) @ { u.firstName == 'Bill' }", "user[40]")
        chk("(u: user) @ { user.firstName == 'Bill' }", "ct_err:unknown_name:user.firstName")
    }

    @Test fun testMultipleEntitiesBadAlias() {
        chk("(user: company, user) @ {}", "ct_err:at_dup_alias:user")
        chk("(user, user) @ {}", "ct_err:at_dup_alias:user")
        chk("(u: user, u: user) @ {}", "ct_err:at_dup_alias:u")
        chk("(user, u: user, user) @ {}", "ct_err:at_dup_alias:user")
    }

    @Test fun testMultipleEntitiesSimple() {
        chk("(user, company) @ { user.firstName == 'Mark', company.name == 'Microsoft' }",
                "(user=user[10],company=company[400])")

        chk("(u: user, c: company) @ { u.firstName == 'Mark', c.name == 'Microsoft' }", "(u=user[10],c=company[400])")

        chk("(company: user, user: company) @ { company.firstName == 'Mark', user.name == 'Microsoft' }",
                "(company=user[10],user=company[400])")

        chk("(u1: user, u2: user, user) @ { u1.firstName == 'Mark', u2.lastName == 'Gates', user.firstName == 'Sergey' }",
                "(u1=user[10],u2=user[40],user=user[50])")
    }

    @Test fun testNameResolutionEntityVsAlias() {
        chk("(user) @ { user.firstName == 'Bill' }", "user[40]")
        chk("(u: user) @ { u.firstName == 'Bill' }", "user[40]")
        chk("(u: user) @ { user.firstName == 'Bill' }", "ct_err:unknown_name:user.firstName")
    }

    @Test fun testNameResolutionLocalVsAttr() {
        chkEx("{ return user @* { .firstName == 'Mark' }; }", "list<user>[user[10]]")
        chkEx("{ val firstName = 'Bill'; return user @* { firstName == 'Mark' }; }", "list<user>[]")
        chkEx("{ val firstName = 'Bill'; return user @* { firstName == firstName }; }",
                "list<user>[user[10],user[20],user[21],user[30],user[40],user[41],user[50],user[51]]")
        chkEx("{ val firstName = 'Bill'; return user @ { firstName }; }", "user[40]")
        chkEx("{ val firstName = 'Bill'; return user @ { .firstName == firstName }; }", "user[40]")
        chkEx("{ val firstName = 'Bill'; return user @* {} ( firstName ); }",
                "list<text>[text[Bill],text[Bill],text[Bill],text[Bill],text[Bill],text[Bill],text[Bill],text[Bill]]")
        chkEx("{ val firstName = 'Bill'; return user @* {} ( .firstName ); }",
                "list<text>[text[Mark],text[Steve],text[Steve],text[Jeff],text[Bill],text[Paul],text[Sergey],text[Larry]]")
    }

    @Test fun testNameResolutionAliasVsLocalAttr() {
        // Alias vs. attr: no conflict.
        chk("user @* { .firstName == 'Mark' }", "list<user>[user[10]]")
        chk("(firstName: user) @* { firstName == 'Mark' }", "ct_err:binop_operand_type:==:[user]:[text]")
        chk("(firstName: user) @* { .firstName == 'Mark' }", "list<user>[user[10]]")
        chk("(firstName: user) @* { firstName.firstName == 'Mark' }", "list<user>[user[10]]")

        // Alias vs. local: error.
        chkEx("{ val u = 'Bill'; return user @ { .firstName == u }; }", "user[40]")
        chkEx("{ val u = 'Bill'; return (u: user) @ { .firstName == u }; }", "ct_err:expr_at_conflict_alias:u")
        chkEx("{ val u = 'Bill'; return (u: user) @ { .firstName == 'Bill' }; }", "ct_err:expr_at_conflict_alias:u")
        chkEx("{ val u = 'Bill'; return (u: user) @ { u.firstName == 'Mark' }; }", "ct_err:expr_at_conflict_alias:u")
    }

    @Test fun testNameResolutionEntityVsLocal() {
        chkEx("{ val user = 'Bill'; return user @ { .firstName == 'Bill' }; }", "user[40]")
        chkEx("{ val user = 'Bill'; return user @ { .firstName == user }; }", "ct_err:expr_name_entity_local:user")
    }

    @Test fun testAttributeAmbiguityName() {
        def("entity user { name: text; }")
        def("entity company { name: text; }")

        tst.inserts = listOf()
        insert("c0.user", "name", "0,'Bob'")
        insert("c0.user", "name", "1,'Alice'")
        insert("c0.company", "name", "2,'Xerox'")
        insert("c0.company", "name", "3,'Bell'")

        chk("(user, company) @ { .name == 'Bob' }", "ct_err:at_attr_name_ambig:name:user.name,company.name")
        chk("(user, company) @ { .name == 'Xerox' }", "ct_err:at_attr_name_ambig:name:user.name,company.name")
        chk("(u: user, c: company) @ { .name == 'Xerox' }", "ct_err:at_attr_name_ambig:name:u.name,c.name")
        chk("(user, company) @ { user.name == 'Bob', company.name == 'Xerox' }", "(user=user[0],company=company[2])")
        chk("(u1: user, u2: user) @ { .name == 'Bob' }", "ct_err:at_attr_name_ambig:name:u1.name,u2.name")
        chk("(c1: company, c2: company) @ { .name == 'Bob' }", "ct_err:at_attr_name_ambig:name:c1.name,c2.name")
    }

    @Test fun testAttributeAmbiguityType() {
        def("entity target { name: text; }")
        def("entity single { t: target; }")
        def("entity double { t1: target; t2: target; }")

        tst.inserts = listOf()
        insert("c0.target", "name", "0,'A'")
        insert("c0.target", "name", "1,'B'")
        insert("c0.target", "name", "2,'C'")
        insert("c0.single", "t", "0,0")
        insert("c0.single", "t", "1,1")
        insert("c0.double", "t1,t2", "0,0,1")
        insert("c0.double", "t1,t2", "1,1,2")
        insert("c0.double", "t1,t2", "2,2,0")

        val base = """
            val tgt1 = target @ { .name == 'A' };
            val tgt2 = target @ { .name == 'B' };
            val tgt3 = target @ { .name == 'C' };
        """.trimIndent()

        // Correct code.
        chkEx("{ $base return single @ { tgt1 }; }", "single[0]")
        chkEx("{ $base return single @ { tgt2 }; }", "single[1]")

        // Ambiguity between attributes of the same entity.
        chkEx("{ $base return double @ { tgt1 }; }", "ct_err:at_where:var_manyattrs_name:0:tgt1:target:double.t1,double.t2")
        chkEx("{ $base return double @ { .t1 == tgt1, tgt2 }; }", "ct_err:at_where:var_manyattrs_name:1:tgt2:target:double.t1,double.t2")
        chkEx("{ $base return double @ { .t1 == tgt1, .t2 == tgt2 }; }", "double[0]")
        chkEx("{ $base return double @ { .t1 == tgt3, .t2 == tgt1 }; }", "double[2]")

        // Ambiguity between attributes of different entities.
        chkEx("{ $base return (s1: single, s2: single) @ { tgt1 }; }", "ct_err:at_where:var_manyattrs_name:0:tgt1:target:s1.t,s2.t")
        chkEx("{ $base return (s1: single, s2: single) @ { tgt1, tgt2 }; }", "ct_err:at_where:var_manyattrs_name:0:tgt1:target:s1.t,s2.t")
        chkEx("{ $base return (s1: single, s2: single) @ { s1.t == tgt1, tgt2 }; }", "ct_err:at_where:var_manyattrs_name:1:tgt2:target:s1.t,s2.t")
        chkEx("{ $base return (s1: single, s2: single) @ { s1.t == tgt1, s2.t == tgt2 }; }", "(s1=single[0],s2=single[1])")
    }

    @Test fun testMultipleEntitiesCrossReference() {
        def("entity person { name: text; cityName: text; }")
        def("entity city { name: text; countryName: text; }")
        def("entity country { name: text; }")

        tst.inserts = listOf()

        insert("c0.person", "name,cityName", "100,'James','New York'")
        insert("c0.person", "name,cityName", "101,'Phil','London'")
        insert("c0.person", "name,cityName", "102,'Roman','Kyiv'")

        insert("c0.city", "name,countryName", "200,'New York','USA'")
        insert("c0.city", "name,countryName", "201,'Kyiv','Ukraine'")
        insert("c0.city", "name,countryName", "202,'London','England'")

        insert("c0.country", "name", "300,'England'")
        insert("c0.country", "name", "301,'Ukraine'")
        insert("c0.country", "name", "302,'USA'")

        chk("(person, city, country) @* { city.name == person.cityName, country.name == city.countryName }",
                "list<(person:person,city:city,country:country)>[" +
                "(person[100],city[200],country[302])," +
                "(person[101],city[202],country[300])," +
                "(person[102],city[201],country[301])]")
    }

    @Test fun testAllFieldKinds() {
        def("""entity testee {
                key k1: integer, k2: integer;
                key k3: integer;
                index i1: integer;
                index i2: integer, i3: integer;
                f1: integer;
                f2: integer;
        }""".trimIndent())
        def("entity proxy { ref: testee; }")

        tst.inserts = listOf()
        insert("c0.testee", "k1,k2,k3,i1,i2,i3,f1,f2", "1,100,101,102,103,104,105,106,107")
        insert("c0.testee", "k1,k2,k3,i1,i2,i3,f1,f2", "2,200,201,202,203,204,205,206,207")
        insert("c0.testee", "k1,k2,k3,i1,i2,i3,f1,f2", "3,300,301,302,303,304,305,306,307")
        insert("c0.proxy", "ref", "1,1")
        insert("c0.proxy", "ref", "2,2")
        insert("c0.proxy", "ref", "3,3")

        // Direct fields of the query entity.
        chk("testee @* { .k1 == 100 }", "list<testee>[testee[1]]")
        chk("testee @* { .k2 == 201 }", "list<testee>[testee[2]]")
        chk("testee @* { .k3 == 302 }", "list<testee>[testee[3]]")
        chk("testee @* { .i1 == 103 }", "list<testee>[testee[1]]")
        chk("testee @* { .i2 == 204 }", "list<testee>[testee[2]]")
        chk("testee @* { .i3 == 305 }", "list<testee>[testee[3]]")
        chk("testee @* { .f1 == 106 }", "list<testee>[testee[1]]")
        chk("testee @* { .f2 == 207 }", "list<testee>[testee[2]]")
        chk("testee @* { .k1 == 300 }", "list<testee>[testee[3]]")
        chk("testee @* { .k2 == 101 }", "list<testee>[testee[1]]")
        chk("testee @* { .k3 == 202 }", "list<testee>[testee[2]]")
        chk("testee @* { .i1 == 303 }", "list<testee>[testee[3]]")
        chk("testee @* { .i2 == 104 }", "list<testee>[testee[1]]")
        chk("testee @* { .i3 == 205 }", "list<testee>[testee[2]]")
        chk("testee @* { .f1 == 306 }", "list<testee>[testee[3]]")
        chk("testee @* { .f2 == 107 }", "list<testee>[testee[1]]")

        // Fields of a referenced entity.
        chk("proxy @* { .ref.k1 == 100 }", "list<proxy>[proxy[1]]")
        chk("proxy @* { .ref.k2 == 201 }", "list<proxy>[proxy[2]]")
        chk("proxy @* { .ref.k3 == 302 }", "list<proxy>[proxy[3]]")
        chk("proxy @* { .ref.i1 == 103 }", "list<proxy>[proxy[1]]")
        chk("proxy @* { .ref.i2 == 204 }", "list<proxy>[proxy[2]]")
        chk("proxy @* { .ref.i3 == 305 }", "list<proxy>[proxy[3]]")
        chk("proxy @* { .ref.f1 == 106 }", "list<proxy>[proxy[1]]")
        chk("proxy @* { .ref.f2 == 207 }", "list<proxy>[proxy[2]]")
        chk("proxy @* { .ref.k1 == 300 }", "list<proxy>[proxy[3]]")
        chk("proxy @* { .ref.k2 == 101 }", "list<proxy>[proxy[1]]")
        chk("proxy @* { .ref.k3 == 202 }", "list<proxy>[proxy[2]]")
        chk("proxy @* { .ref.i1 == 303 }", "list<proxy>[proxy[3]]")
        chk("proxy @* { .ref.i2 == 104 }", "list<proxy>[proxy[1]]")
        chk("proxy @* { .ref.i3 == 205 }", "list<proxy>[proxy[2]]")
        chk("proxy @* { .ref.f1 == 306 }", "list<proxy>[proxy[3]]")
        chk("proxy @* { .ref.f2 == 107 }", "list<proxy>[proxy[1]]")
    }

    @Test fun testNestedAtExpression() {
        chk("user @* { .company == company @ { .name == 'Facebook' } }", "list<user>[user[10]]")
        chk("user @* { .company == company @ { .name == 'Apple' } }", "list<user>[user[20],user[21]]")
        chk("user @* { .company == company @ { .name == 'Amazon' } }", "list<user>[user[30]]")
        chk("user @* { .company == company @ { .name == 'Microsoft' } }", "list<user>[user[40],user[41]]")
        chk("user @* { .company == company @ { .name == 'Google' } }", "list<user>[user[50],user[51]]")
        chk("user @* { company @ { .name == 'Facebook' } }", "list<user>[user[10]]")
        chk("user @* { company @ { .name == 'Apple' } }", "list<user>[user[20],user[21]]")
        chk("user @* { company @ { .name == 'Amazon' } }", "list<user>[user[30]]")
        chk("user @* { company @ { .name == 'Microsoft' } }", "list<user>[user[40],user[41]]")
        chk("user @* { company @ { .name == 'Google' } }", "list<user>[user[50],user[51]]")
    }

    @Test fun testFieldSelectionSimple() {
        chk("user @ { .firstName == 'Bill' }.lastName", "text[Gates]")
        chk("user @ { .firstName == 'Mark' }.lastName", "text[Zuckerberg]")
        chk("user @ { .firstName == 'Bill' }.company.name", "text[Microsoft]")
        chk("user @ { .firstName == 'Mark' }.company.name", "text[Facebook]")
        chk("user @ { .firstName == 'Mark' }.user.lastName", "ct_err:expr_attr_unknown:user")
        chk("(u: user) @ { .firstName == 'Mark' }.user.lastName", "ct_err:expr_attr_unknown:user")
        chk("(u: user) @ { .firstName == 'Mark' }.u.lastName", "ct_err:expr_attr_unknown:u")
    }

    @Test fun testFieldSelectionComplex() {
        chk("user @ { .firstName == 'Bill' } ( .lastName )", "text[Gates]")
        chk("(u: user) @ { .firstName == 'Bill' } ( u.lastName )", "text[Gates]")
        chk("(u1: user, u2: user) @ { u1.firstName == 'Bill', u2.firstName == 'Mark' } ( .lastName )",
                "ct_err:at_attr_name_ambig:lastName:u1.lastName,u2.lastName")

        chk("(u1: user, u2: user) @ { u1.firstName == 'Bill', u2.firstName == 'Mark' } ( _=u1.lastName, _=u2.lastName )",
                "(text[Gates],text[Zuckerberg])")
        chk("(u1: user, u2: user) @ { u1.firstName == 'Bill', u2.firstName == 'Mark' } ( u1.company.name, u2.company.name )",
                "(text[Microsoft],text[Facebook])")
    }

    @Test fun testFieldSelectionNaming() {
        chk("user @ { .firstName == 'Bill' } ( .lastName )", "text[Gates]")
        chk("user @ { .firstName == 'Bill' } ( x = .lastName )", "(x=text[Gates])")
        chk("user @ { .firstName == 'Bill' } ( lastName = .lastName )", "(lastName=text[Gates])")

        chk("user @ { .firstName == 'Bill' } ( .firstName, .lastName, .company.name )",
                "(firstName=text[Bill],lastName=text[Gates],text[Microsoft])")
        chk("user @ { .firstName == 'Bill' } ( user.firstName, user.lastName, user.company.name )",
                "(firstName=text[Bill],lastName=text[Gates],text[Microsoft])")
        chk("(u: user) @ { .firstName == 'Bill' } ( u.firstName, u.lastName, u.company.name )",
                "(firstName=text[Bill],lastName=text[Gates],text[Microsoft])")
        chk("user @ { .firstName == 'Bill' } ( .firstName, .lastName, companyName = .company.name )",
                "(firstName=text[Bill],lastName=text[Gates],companyName=text[Microsoft])")

        chk("(user, company) @ { user.firstName == 'Bill', company.name == 'Facebook' }",
                "(user=user[40],company=company[100])")
        chk("(u: user, c: company) @ { u.firstName == 'Bill', c.name == 'Facebook' }", "(u=user[40],c=company[100])")
        chk("(u: user, company) @ { u.firstName == 'Bill', company.name == 'Facebook' }",
                "(u=user[40],company=company[100])")

        chk("user @ { .firstName == 'Bill' } ( x = .firstName, x = .lastName )", "ct_err:at:dup_field_name:x")
        chk("user @ { .firstName == 'Bill' } ( x = .firstName, y = .lastName )", "(x=text[Bill],y=text[Gates])")
    }

    @Test fun testTupleFieldAccess() {
        val base = "val t = user @ { .firstName == 'Bill' } ( .firstName, .lastName, companyName = .company.name );"
        chkEx("{ $base return t.firstName; }", "text[Bill]")
        chkEx("{ $base return t.lastName; }", "text[Gates]")
        chkEx("{ $base return t.companyName; }", "text[Microsoft]")
        chkEx("{ $base return t.foo; }", "ct_err:unknown_member:[(firstName:text,lastName:text,companyName:text)]:foo")
    }

    @Test fun testLimit() {
        chk("user @* {} limit 0", "list<user>[]")
        chk("user @* {} limit 1", "list<user>[user[10]]")
        chk("user @* {} limit 2", "list<user>[user[10],user[20]]")
        chk("user @* {} limit 3", "list<user>[user[10],user[20],user[21]]")
        chk("user @* {} limit 4", "list<user>[user[10],user[20],user[21],user[30]]")
        chk("user @* {} limit 5", "list<user>[user[10],user[20],user[21],user[30],user[40]]")
        chk("user @* {} limit 6", "list<user>[user[10],user[20],user[21],user[30],user[40],user[41]]")
        chk("user @* {} limit 7", "list<user>[user[10],user[20],user[21],user[30],user[40],user[41],user[50]]")

        chk("user @* {} ( .lastName ) limit 0", "list<text>[]")
        chk("user @* {} ( .lastName ) limit 1", "list<text>[text[Zuckerberg]]")
        chk("user @* {} ( .lastName ) limit 2", "list<text>[text[Zuckerberg],text[Jobs]]")
        chk("user @* {} ( .lastName ) limit 3", "list<text>[text[Zuckerberg],text[Jobs],text[Wozniak]]")
        chk("user @* {} ( .lastName ) limit 4", "list<text>[text[Zuckerberg],text[Jobs],text[Wozniak],text[Bezos]]")

        chk("user @ {} limit 0", "rt_err:at:wrong_count:0")
        chk("user @ {} limit 1", "user[10]")
        chk("user @ {} limit 2", "rt_err:at:wrong_count:2")
        chk("user @? {} limit 0", "null")
        chk("user @? {} limit 1", "user[10]")
        chk("user @? {} limit 2", "rt_err:at:wrong_count:2")

        chk("user @ {} limit 'Hello'", "ct_err:expr_at_limit_type:text")
    }

    @Test fun testCardinalityOne() {
        chk("user @ { .firstName == 'Chuck' }", "rt_err:at:wrong_count:0")
        chk("user @ { .firstName == 'Bill' }", "user[40]")
        chk("user @? { .firstName == 'Chuck' }", "null")
        chk("user @? { .firstName == 'Bill' }", "user[40]")
        chk("user @? { .firstName == 'Steve' }", "rt_err:at:wrong_count:2")
    }

    @Test fun testCardinalityMany() {
        chk("user @* { .firstName == 'Chuck' }", "list<user>[]")
        chk("user @* { .firstName == 'Bill' }", "list<user>[user[40]]")
        chk("user @+ { .firstName == 'Chuck' }", "rt_err:at:wrong_count:0")
        chk("user @+ { .firstName == 'Bill' }", "list<user>[user[40]]")
        chk("user @+ { .firstName == 'Steve' }", "list<user>[user[20],user[21]]")
    }

    @Test fun testSort() {
        tst.strictToString = false

        chk("'' + user @* {} ( .firstName )", "[Mark, Steve, Steve, Jeff, Bill, Paul, Sergey, Larry]")
        chk("'' + user @* {} ( @sort .firstName )", "[Bill, Jeff, Larry, Mark, Paul, Sergey, Steve, Steve]")
        chk("'' + user @* {} ( @sort_desc .firstName )", "[Steve, Steve, Sergey, Paul, Mark, Larry, Jeff, Bill]")

        chk("'' + user @* { .company.name == 'Apple' } ( @sort _=.firstName, sort _=.lastName )", "[(Steve,Jobs), (Steve,Wozniak)]")
        chk("'' + user @* { .company.name == 'Apple' } ( @sort _=.firstName, -sort _=.lastName )", "[(Steve,Wozniak), (Steve,Jobs)]")

        chk("'' + user @* {} ( @sort _=.company.name, _=.lastName )",
                "[(Amazon,Bezos), (Apple,Jobs), (Apple,Wozniak), (Facebook,Zuckerberg), (Google,Brin), (Google,Page), " +
                        "(Microsoft,Gates), (Microsoft,Allen)]")

        chk("'' + user @* {} ( @sort _=.company.name, sort _=.lastName )",
                "[(Amazon,Bezos), (Apple,Jobs), (Apple,Wozniak), (Facebook,Zuckerberg), (Google,Brin), (Google,Page), " +
                        "(Microsoft,Allen), (Microsoft,Gates)]")

        chk("'' + user @* {} ( @sort_desc user )",
                "[user[51], user[50], user[41], user[40], user[30], user[21], user[20], user[10]]")

        chk("'' + user @* {} ( @sort_desc _=.company, _=user )",
                "[(company[500],user[50]), (company[500],user[51]), (company[400],user[40]), (company[400],user[41]), " +
                "(company[300],user[30]), (company[200],user[20]), (company[200],user[21]), (company[100],user[10])]")
    }

    @Test fun testSortAnnotation() {
        tst.strictToString = false

        chk("user @* {} ( @sort @sort .firstName )", "ct_err:ann:sort:dup")
        chk("user @* {} ( @sort_desc @sort_desc .firstName )", "ct_err:ann:sort_desc:dup")
        chk("user @* {} ( @sort @sort_desc .firstName )", "ct_err:ann:sort_desc:dup")
        chk("user @* {} ( @sort_desc @sort .firstName )", "ct_err:ann:sort:dup")

        chk("'' + user @* {} ( @sort() .firstName )", "[Bill, Jeff, Larry, Mark, Paul, Sergey, Steve, Steve]")
        chk("user @* {} ( @sort(123) .firstName )", "ct_err:ann:sort:args:1")
        chk("user @* {} ( @sort('desc') .firstName )", "ct_err:ann:sort:args:1")

        chk("user @* {} ( @sort sort .firstName )", "ct_err:ann:sort:dup")
        chk("user @* {} ( @sort_desc sort .firstName )", "ct_err:ann:sort_desc:dup")
        chk("user @* {} ( @sort -sort .firstName )", "ct_err:ann:sort:dup")
        chk("user @* {} ( @sort_desc -sort .firstName )", "ct_err:ann:sort_desc:dup")
    }

    @Test fun testSortOld() {
        tst.strictToString = false

        chk("user @* {} ( .firstName )", "[Mark, Steve, Steve, Jeff, Bill, Paul, Sergey, Larry]")

        chk("user @* {} ( sort .firstName )", "[Bill, Jeff, Larry, Mark, Paul, Sergey, Steve, Steve]")
        chkWarn("at:what:sort:deprecated:sort")

        chk("user @* {} ( -sort .firstName )", "[Steve, Steve, Sergey, Paul, Mark, Larry, Jeff, Bill]")
        chkWarn("at:what:sort:deprecated:sort_desc")
    }

    @Test fun testNullLiteral() {
        chk("user @ { .firstName == 'Bill' } (_=.lastName, ''+null)", "(text[Gates],text[null])")
        //chk("user @ { firstName = 'Bill' } (lastName, null)", "(text[Gates],null)")
    }

    @Test fun testLookupExpr() {
        chk("user @ { .firstName == 'Bill' } (_=.lastName, 'Hello'[1])", "(text[Gates],text[e])")
        chk("user @ { .firstName == 'Bill' } (.lastName[2])", "ct_err:expr_sqlnotallowed")
        chk("user @ { .firstName == 'Bill' } ('HelloWorld'[.lastName.size()])", "ct_err:expr_sqlnotallowed")
    }

    @Test fun testTupleExpr() {
        chk("user @ { .firstName == 'Bill' } (_=.lastName, '' + (123,'Hello'))", "(text[Gates],text[(123,Hello)])")
        chk("user @ { .firstName == 'Bill' } (_=.lastName, '' + (123,.firstName))", "ct_err:expr_sqlnotallowed")
    }

    @Test fun testCollectionLiteralExpr() {
        chk("user @ { .firstName == 'Bill' } (_=.lastName, '' + [1,2,3])", "(text[Gates],text[[1, 2, 3]])")
        chk("user @ { .firstName == 'Bill' } (_=.lastName, '' + [.firstName,.lastName])", "ct_err:expr_sqlnotallowed")

        chk("user @ { .firstName == 'Bill' } (_=.lastName, '' + [123:'Hello'])", "(text[Gates],text[{123=Hello}])")
        chk("user @ { .firstName == 'Bill' } (_=.lastName, '' + [.firstName:.lastName])", "ct_err:expr_sqlnotallowed")
    }

    @Test fun testCollectionConstructorExpr() {
        chk("user @ { .firstName == 'Bill' } (_=.lastName, '' + list([1,2,3]))", "(text[Gates],text[[1, 2, 3]])")
        chk("user @ { .firstName == 'Bill' } (_=.lastName, '' + set([1,2,3]))", "(text[Gates],text[[1, 2, 3]])")
        chk("user @ { .firstName == 'Bill' } (_=.lastName, '' + map([123:'Hello']))", "(text[Gates],text[{123=Hello}])")

        chk("user @ { .firstName == 'Bill' } (_=.lastName, '' + list(.firstName))", "ct_err:expr_sqlnotallowed")
        chk("user @ { .firstName == 'Bill' } (_=.lastName, '' + set(.firstName))", "ct_err:expr_sqlnotallowed")
        chk("user @ { .firstName == 'Bill' } (_=.lastName, '' + map(.firstName))", "ct_err:expr_sqlnotallowed")
    }

    @Test fun testMultiLocalWhere() {
        chkEx("{ val x = 123; return user @ { .firstName == 'Bill', x > 10, x > 20, x > 30 }; }", "user[40]")
    }

    @Test fun testOrBooleanCondition() {
        chkEx("{ return user @* { .firstName == 'Bill' }; }", "list<user>[user[40]]")
        chkEx("{ return user @* { .firstName == 'Bob' }; }", "list<user>[]")

        chkEx("{ val anyUser = 'no'; return user @* { .firstName == 'Bob' or anyUser == 'yes' }; }", "list<user>[]")
        chkEx("{ val anyUser = 'yes'; return user @* { .firstName == 'Bob' or anyUser == 'yes' }; }",
                "list<user>[user[10],user[20],user[21],user[30],user[40],user[41],user[50],user[51]]")
        chkEx("{ val anyUser = 'yes'; return user @* { .firstName == 'Bob' or anyUser == 'yes', .company.name != 'Foo' }; }",
                "list<user>[user[10],user[20],user[21],user[30],user[40],user[41],user[50],user[51]]")

        chkEx("{ val userName = 'Bill'; return user @* { .firstName == userName or userName == '' }; }", "list<user>[user[40]]")
        chkEx("{ val userName = 'Bob'; return user @* { .firstName == userName or userName == '' }; }", "list<user>[]")
        chkEx("{ val userName = ''; return user @* { .firstName == userName or userName == '' }; }",
                "list<user>[user[10],user[20],user[21],user[30],user[40],user[41],user[50],user[51]]")
    }

    @Test fun testOrConditionBug() {
        def("""
            entity user_account {
                key tuid;
                mutable name;
                mutable login: text;
                key pubkey;
                index created_by: pubkey;
                role: text;
                mutable password_hash: text;
                mutable deleted: boolean;
                mutable aux_data: json;
            }
        """.trimIndent())

        tst.inserts = listOf("""
            INSERT INTO "c0.user_account"(rowid, name, login, role, password_hash, deleted, pubkey, aux_data, tuid, created_by)
            VALUES (0, 'name1', 'test1@mail.io', 'issuer', 'test', false, '123', '{}', '12345', '1231234'),
            (1, 'name2', 'test2@mail.io', 'validator', '2test', false, '2123', '{}', '212345', '21231234');
        """.trimIndent())

        val ret = """
            val res = user_account @* {
                .role == search_role or search_role == '',
                .deleted == false
            } (
                .tuid,
                .name,
                .login,
                .role,
                .deleted,
                .aux_data
            );
            return res.size();
        """.trimIndent()

        chkEx("{ val search_role = ''; $ret }", "int[2]")
        chkEx("{ val search_role = 'validator'; $ret }", "int[1]")
        chkEx("{ val search_role = 'issuer'; $ret }", "int[1]")
        chkEx("{ val search_role = 'foo'; $ret }", "int[0]")
    }

    @Test fun testSubscript() {
        chk("user @* { .firstName == 'Bill' }[0]", "user[40]")
    }

    @Test fun testNoSqlWhatExpr() {
        chk("user @* { .firstName == 'Bill' } ( x = (123, 'Hello') )", "ct_err:expr_nosql:(integer,text)")
    }

    @Test fun testIndependentEntityFieldExpression() {
        val code = """
            { val u = user @ { .firstName == 'Paul' };
              return user @ { .company == u.company, .firstName != 'Paul' } ( .firstName );
            }
        """.trimIndent()
        chkEx(code, "text[Bill]")
    }

    @Test fun testWhereDbExpr() {
        tst.defs = listOf()
        tst.inserts = listOf()
        def("entity company { name; }")
        def("entity user { name; company; flag: boolean; }")
        insert("c0.company", "name", "33,'Apple'")
        insert("c0.user", "name,company,flag", "100,'Jobs',33,true")
        insert("c0.user", "name,company,flag", "101,'Wozniak',33,false")

        chk("user @* { .company }", "ct_err:at_where:type:0:[boolean]:[company]")
        chk("user @* { .name }", "ct_err:at_where:type:0:[boolean]:[text]")
        chk("user @* { .name + .company.name }", "ct_err:at_where:type:0:[boolean]:[text]")
        chk("user @* { 'Steve ' + .name }", "ct_err:at_where:type:0:[boolean]:[text]")

        chk("user @* { 'Jobs' }", "list<user>[user[100]]")
        chk("user @* { 'Wozniak' }", "list<user>[user[101]]")
        chk("user @* { .flag }", "list<user>[user[100]]")
        chk("user @* { not .flag }", "list<user>[user[101]]")
    }

    @Test fun testFieldNaming() {
        tst.defs = listOf()
        tst.inserts = listOf()
        tst.strictToString = false

        def("entity user { name; }")
        def("entity company { name; }")
        insert("c0.user", "name", "1,'Bob'")
        insert("c0.company", "name", "1,'Apple'")

        chk("(u: user, c: company) @ {} ( u.name, c.name )", "ct_err:at:dup_field_name:name")
        chk("(u: user, c: company) @ {} ( name = u.name, c.name )", "ct_err:at:dup_field_name:name")
        chk("(u: user, c: company) @ {} ( u.name, name = c.name )", "ct_err:at:dup_field_name:name")
        chk("(u: user, c: company) @ {} ( name1 = u.name, name2 = c.name )", "(name1=Bob,name2=Apple)")

        chk("(u: user, c: company) @ {} ( _ = u.name, c.name )", "(Bob,name=Apple)")
        chk("(u: user, c: company) @ {} ( u.name, _ = c.name )", "(name=Bob,Apple)")
        chk("(u: user, c: company) @ {} ( _ = u.name, _ = c.name )", "(Bob,Apple)")

        chk("(u: user, c: company) @ {} ( @omit u.name, c.name )", "Apple")
        chk("(u: user, c: company) @ {} ( u.name, @omit c.name )", "Bob")
        chk("(u: user, c: company) @ {} ( @omit u.name, name = c.name )", "(name=Apple)")
        chk("(u: user, c: company) @ {} ( name = u.name, @omit c.name )", "(name=Bob)")

        chk("(u: user, c: company) @ {} ( @omit u.name, _ = c.name )", "Apple")
        chk("(u: user, c: company) @ {} ( _ = u.name, @omit c.name )", "Bob")

        chk("(u: user, c: company) @ {} ( @omit u.name, @omit u.name )", "ct_err:at:no_fields")

        chk("(u: user, c: company) @ {} ( @omit @sort u.name, c.name )", "Apple")
        chk("(u: user, c: company) @ {} ( u.name, @omit @sort c.name )", "Bob")
        chk("(u: user, c: company) @ {} ( @omit @sort u.name, name = c.name )", "(name=Apple)")
        chk("(u: user, c: company) @ {} ( name = u.name, @omit @sort c.name )", "(name=Bob)")
        chk("(u: user, c: company) @ {} ( @omit @sort u.name, _ = c.name )", "Apple")
        chk("(u: user, c: company) @ {} ( _ = u.name, @omit @sort c.name )", "Bob")

        chk("(u: user, c: company) @ {} ( @omit name = u.name, c.name )", "ct_err:at:dup_field_name:name")
        chk("(u: user, c: company) @ {} ( @omit name = u.name, name = c.name )", "ct_err:at:dup_field_name:name")
        chk("(u: user, c: company) @ {} ( @omit u.name, c.name )", "Apple")
        chk("(u: user, c: company) @ {} ( @omit u.name, name = c.name )", "(name=Apple)")
        chk("(u: user, c: company) @ {} ( @omit u.name, c.name, c )", "(name=Apple,company[1])")
    }

    private object Ins {
        fun company(id: Int, name: String): String = SqlTestUtils.mkins("c0.company", "name", "$id, '$name'")

        fun user(id: Int, companyId: Int, firstName: String, lastName: String): String =
                SqlTestUtils.mkins("c0.user", "firstName,lastName,company", "$id, '$firstName', '$lastName', $companyId")
    }
}
