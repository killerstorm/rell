/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lang.expr.atexpr

import org.junit.Test

class AtExprCommonDbTest: AtExprCommonBaseTest() {
    override fun impKind() = AtExprTestKind_Db()

    @Test fun testImplicitTupleFieldNameDb() {
        initDataUserCompany()
        chk("user @* {} ( user.name, user.pos )", "[(name=Bob,pos=Dev), (name=Alice,pos=Tester)]")
    }

    @Test fun testAliasMatchByLocal_Db() {
        initDataUserCompany()
        chkEx("{ val user = $fromUser@*{}[0]; return $fromUser@*{ user } ( .name ); }",
                "ct_err:[at_where:var_noattrs:0:user:user][name:ambiguous:user]")
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
        chk("(company: user) @* { (company @? { company.city != '?' } limit 1) != null } ( .name )", "ct_err:name:ambiguous:company")
    }

    @Test fun testNestedAttributesDirect_Db() {
        initDataUserCompanyCity()

        chk("$fromUser @* {} ( _=.name, $fromCompany @ {} (.user_attr) limit 1 )",
                "ct_err:[at:entity:outer:user][at_expr:attr:belongs_to_outer:user_attr:user]")

        chk("(u:$fromUser) @* {} ( $fromCompany @ {} (u.name) limit 1 )", "ct_err:at:entity:outer:u")

        chkNestedAttributes("$fromUser @* {} ( _=.name, $fromCompany @ {} (.user_attr) limit 1 )",
                "ct_err:[at:entity:outer:user][at_expr:attr:belongs_to_outer:user_attr:user]",
                "ct_err:[at:entity:outer:user][at_expr:attr:belongs_to_outer:user_attr:user]",
                "ct_err:[at:entity:outer:user][at_expr:attr:belongs_to_outer:user_attr:user]"
        )
    }
}
