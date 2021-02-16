package net.postchain.rell

import org.junit.Test

abstract class AtExprCommonBaseTest: AtExprBaseTest() {
    protected val fromUser = impFrom("user")
    protected val fromCompany = impFrom("company")

    protected fun initDataUserCompany() {
        tst.strictToString = false
        def("$impDefKw company { name; }")
        def("$impDefKw user { id: integer; name; pos: text; employer: company; }")
        impCreateObjs("company",
                "name = 'Apple'",
                "name = 'Google'"
        )
        impCreateObjs("user",
                "id = 501, name = 'Bob', pos = 'Dev', employer = $fromCompany@{.name=='Apple'}",
                "id = 502, name = 'Alice', pos = 'Tester', employer = $fromCompany@{.name=='Google'}"
        )
    }

    protected fun initDataUserCompanyCity() {
        tst.strictToString = false
        def("$impDefKw company { name; city: text; }")
        def("$impDefKw user { id: integer; name; city: text; }")
        impCreateObjs("company",
                "name = 'Adidas', city = 'London'",
                "name = 'Reebok', city = 'Paris'",
                "name = 'Puma', city = 'London'"
        )
        impCreateObjs("user",
                "id = 501, name = 'Bob', city = 'Paris'",
                "id = 502, name = 'Alice', city = 'London'",
                "id = 503, name = 'Trudy', city = 'Berlin'"
        )
    }

    protected fun initDataTypes() {
        tst.strictToString = false
        def("$impDefKw id { s: text; }")
        def("$impDefKw single { id; i: integer; t: text; b: boolean; }")
        def("$impDefKw double { id; i1: integer; i2: integer; t1: text; t2: text; b1: boolean; b2: boolean; }")
        impCreateObjs("single",
                "id = $impNew id('Bob'), i = 123, t = 'A', b = false",
                "id = $impNew id('Alice'), i = 456, t = 'B', b = true"
        )
        impCreateObjs("double",
                "id = $impNew id('Bob'), i1 = 123, i2 = 456, t1 = 'A', t2 = 'B', b1 = false, b2 = true",
                "id = $impNew id('Alice'), i1 = 654, i2 = 321, t1 = 'C', t2 = 'D', b1 = true, b2 = false"
        )
    }

    @Test fun testNameResolutionAliasVsAttr() {
        initDataUserCompany()
        chk("$fromUser @* { .pos == 'Dev' } ( .name )", "[Bob]")
        chk("(pos: $fromUser) @* { pos == 'Dev' } ( .name )", "ct_err:binop_operand_type:==:[user]:[text]")
        chk("(pos: $fromUser) @* { .pos == 'Dev' } ( .name )", "[Bob]")
        chk("(pos: $fromUser) @* { pos.pos == 'Dev' } ( .name )", "[Bob]")
    }

    @Test fun testNameResolutionAliasVsLocal() {
        initDataUserCompany()
        chkEx("{ val u = 'Dev'; return $fromUser @* { .pos == u } ( .name ); }", "[Bob]")
        chkEx("{ val u = 'Dev'; return (u: $fromUser) @* { .pos == u } ( .name ); }", "ct_err:block:name_conflict:u")
        chkEx("{ val u = 'Dev'; return (u: $fromUser) @* { .pos == 'Dev' } ( .name ); }", "ct_err:block:name_conflict:u")
        chkEx("{ val u = 'Dev'; return (u: $fromUser) @* { u.pos == 'Dev' } ( .name ); }",
                "ct_err:[block:name_conflict:u][unknown_member:[text]:pos]")
    }

    @Test fun testAliasMatchByLocal() {
        initDataUserCompany()

        chkEx("{ val u = $fromUser@*{}[0]; return $fromUser@*{ u } ( .name ); }", "ct_err:at_where:var_noattrs:0:u:user")
        //chkEx("{ val user = $fromUser@*{}[0]; return $fromUser@*{ user } ( .name ); }", "ct_err:at_where:var_noattrs:0:user:user")
        chkEx("{ val u = $fromUser@*{}[0]; return (user:$fromUser)@*{ u } ( .name ); }", "ct_err:at_where:var_noattrs:0:u:user")
        chkEx("{ val u = $fromUser@*{}[0]; return (user:$fromUser)@*{ user } ( .name ); }", "ct_err:at_where:var_noattrs:0:user:user")
        chkEx("{ val u = $fromUser@*{}[0]; return $fromUser@*{ $ == u } ( .name ); }", "[Bob]")
        chkEx("{ val u = $fromUser@*{}[0]; return (user:$fromUser)@*{ user == u } ( .name ); }", "[Bob]")

        //chkEx("{ val employer = $fromCompany@*{}[0]; return $fromUser@*{ employer } ( .name ); }", "[Bob]")
        //chkEx("{ val c = $fromCompany@*{}[0]; return $fromUser@*{ c } ( .name ); }", "[Bob]")
    }

    @Test fun testImplicitTupleFieldName() {
        initDataUserCompany()
        chk("$fromUser @* {} ( .name, .pos )", "[(name=Bob,pos=Dev), (name=Alice,pos=Tester)]")
        chk("$fromUser @* {} ( $.name, $.pos )", "[(name=Bob,pos=Dev), (name=Alice,pos=Tester)]")
        chk("(u: $fromUser) @* {} ( .name, .pos )", "[(name=Bob,pos=Dev), (name=Alice,pos=Tester)]")
        chk("(u: $fromUser) @* {} ( u.name, u.pos )", "[(name=Bob,pos=Dev), (name=Alice,pos=Tester)]")
    }

    @Test fun testPlaceholder() {
        initDataUserCompany()
        chk("$fromUser @* {} ( $.name, @omit @sort .id )", "[Bob, Alice]")
        chk("(u: $fromUser) @* {} ( $.name, @omit @sort .id )", "ct_err:expr:placeholder:none")
        chk("(u: $fromUser) @* {} ( u.name, @omit @sort .id )", "[Bob, Alice]")
    }

    @Test fun testPlaceholderNested() {
        initDataUserCompany()
        chk("[7] @* {} ( $fromUser @* {} ( $.name, @omit @sort .id ) )", "ct_err:name:ambiguous:$")
        chk("[7] @* {} ( $fromUser @* {} ( $, .name, @omit @sort .id ) )", "ct_err:name:ambiguous:$")
        chk("[7] @* {} ( $, $fromUser @* {} ( .name, @omit @sort .id ) )", "[(7,[Bob, Alice])]")
        chk("[7] @* {} ( (u: $fromUser) @* {} ( $.name, @omit @sort .id ) )", "ct_err:unknown_member:[integer]:name")
        chk("[7] @* {} ( (u: $fromUser) @* {} ( $, _=u.name, @omit @sort .id ) )", "[[(7,Bob), (7,Alice)]]")
        chk("(x: [7]) @* {} ( (u: $fromUser) @* {} ( $, _=u.name, @omit @sort .id ) )", "ct_err:expr:placeholder:none")
        chk("(x: [7]) @* {} ( (u: $fromUser) @* {} ( x, _=u.name, @omit @sort .id ) )", "[[(7,Bob), (7,Alice)]]")
    }

    @Test fun testPlaceholderNestedExists() {
        initDataUserCompanyCity()
        chk("$fromUser @* {exists( $fromCompany @* {} )} ( .name )", "[Bob, Alice, Trudy]")
        chk("$fromUser @* {exists( $fromCompany @* { $.name != '*' } )} ( .name )", "ct_err:name:ambiguous:$")
        chk("(u: $fromUser) @* {exists( (c: $fromCompany) @* { c.city == u.city } )} ( .name )", "[Bob, Alice]")
        chk("(u: $fromUser) @* {exists( (c: $fromCompany) @* { $.city == u.city } )} ( .name )", "ct_err:expr:placeholder:none")
        chk("(u: $fromUser) @* {exists( (c: $fromCompany) @* { c.city == $.city } )} ( .name )", "ct_err:expr:placeholder:none")
        chk("$fromUser @* {exists( (c: $fromCompany) @* { c.city == $.city } )} ( .name )", "[Bob, Alice]")
        chk("(u: $fromUser) @* {exists( $fromCompany @* { $.city == u.city } )} ( .name )", "[Bob, Alice]")
    }

    @Test fun testAliasLocalVarConflict() {
        initDataUserCompany()
        chkEx("{ val user = $fromUser@*{}[0]; return $fromUser @* {} ( $.id ); }", "[501, 502]")
        chkEx("{ val user = 'Bob'; return $fromUser @* {} ( $.id ); }", "[501, 502]")
        chkEx("{ val u = $fromUser@*{}[0]; return (u: $fromUser) @* {} ( u.id ); }", "ct_err:block:name_conflict:u")
        chkEx("{ val u = 'Bob'; return (u: $fromUser) @* {} ( u.id ); }", "ct_err:[block:name_conflict:u][unknown_member:[text]:id]")
    }
}
