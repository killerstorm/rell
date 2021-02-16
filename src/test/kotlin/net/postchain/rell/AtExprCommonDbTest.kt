package net.postchain.rell

import org.junit.Test

class AtExprCommonDbTest: AtExprCommonBaseTest() {
    override fun impKind() = AtExprTestKind_Db

    @Test fun testImplicitTupleFieldNameDb() {
        initDataUserCompany()
        chk("user @* {} ( user.name, user.pos )", "[(name=Bob,pos=Dev), (name=Alice,pos=Tester)]")
    }

    @Test fun testAttrMatchByTypeConstExpr() {
        initDataTypes()

        val fromSingle = impFrom("single")
        chk("$fromSingle @* { 123 } ( .id.s )", "[Bob]")
        chk("$fromSingle @* { 456 } ( .id.s )", "[Alice]")
        chk("$fromSingle @* { 'A' } ( .id.s )", "[Bob]")
        chk("$fromSingle @* { 'B' } ( .id.s )", "[Alice]")
        chk("$fromSingle @* { false } ( .id.s )", "[]")
        chk("$fromSingle @* { true } ( .id.s )", "[Bob, Alice]")

        val fromDouble = impFrom("double")
        chk("$fromDouble @* { 123 } ( .id.s )", "ct_err:at_attr_type_ambig:0:integer:double.i1,double.i2")
        chk("$fromDouble @* { 'A' } ( .id.s )", "ct_err:at_attr_type_ambig:0:text:double.t1,double.t2")
        chk("$fromDouble @* { false } ( .id.s )", "[]")
        chk("$fromDouble @* { true } ( .id.s )", "[Bob, Alice]")
    }

    @Test fun testAttrMatchByTypeComplexExpr() {
        def("function fi(x: integer) = _nop(x);")
        def("function ft(x: text) = _nop(x);")
        def("function fb(x: boolean) = _nop(x);")
        initDataTypes()

        val fromSingle = impFrom("single")
        chk("$fromSingle @* { fi(123) } ( .id.s )", "[Bob]")
        chk("$fromSingle @* { fi(456) } ( .id.s )", "[Alice]")
        chk("$fromSingle @* { ft('A') } ( .id.s )", "[Bob]")
        chk("$fromSingle @* { ft('B') } ( .id.s )", "[Alice]")
        chk("$fromSingle @* { fb(false) } ( .id.s )", "[]")
        chk("$fromSingle @* { fb(true) } ( .id.s )", "[Bob, Alice]")

        val fromDouble = impFrom("double")
        chk("$fromDouble @* { fi(123) } ( .id.s )", "ct_err:at_attr_type_ambig:0:integer:double.i1,double.i2")
        chk("$fromDouble @* { ft('A') } ( .id.s )", "ct_err:at_attr_type_ambig:0:text:double.t1,double.t2")
        chk("$fromDouble @* { fb(false) } ( .id.s )", "[]")
        chk("$fromDouble @* { fb(true) } ( .id.s )", "[Bob, Alice]")
    }

    @Test fun testAliasLocalVarConflictDb() {
        initDataUserCompany()
        chkEx("{ return $fromUser @* {} ( user.id ); }", "[501, 502]")
        chkEx("{ val user = $fromUser@*{}[0]; return $fromUser @* {} ( user.id ); }", "ct_err:name:ambiguous:user")
        chkEx("{ val user = 'Bob'; return $fromUser @* {} ( user.id ); }", "ct_err:[name:ambiguous:user][unknown_member:[text]:id]")
    }

    @Test fun testAliasConflictImplicitVsExplicit() {
        initDataUserCompanyCity()
        chk("user @* { ((user: company) @? {} limit 1) != null } ( .name )", "[Bob, Alice, Trudy]")
        chk("user @* { ((user: company) @? { user.city != '?' } limit 1) != null } ( .name )", "ct_err:name:ambiguous:user")
        chk("(company: user) @* { (company @? {} limit 1) != null } ( .name )", "[Bob, Alice, Trudy]")
        chk("(company: user) @* { (company @? { company.city != '?' } limit 1) != null } ( .name )",
                "ct_err:[at:entity:outer:company][name:ambiguous:company]")
    }
}
