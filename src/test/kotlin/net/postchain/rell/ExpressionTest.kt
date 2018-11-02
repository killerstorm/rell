package net.postchain.rell

import org.junit.Test

class ExpressionTest: BaseRellTest(false) {
    @Test fun testPrecedence() {
        chk("2 * 3 + 4", "int[10]")
        chk("4 + 2 * 3", "int[10]")

        chk("5 * 4 / 3", "int[6]")
        chk("5 * (4 / 3)", "int[5]")
        chk("555 / 13 % 5", "int[2]")
        chk("7 - 555 / 13 % 5", "int[5]")

        chk("1 == 1 and 2 == 2 and 3 == 3", "boolean[true]")
        chk("1 == 1 and 2 == 0 and 3 == 3", "boolean[false]")
        chk("1 == 2 or 3 == 4 or 5 == 6", "boolean[false]")
        chk("1 == 2 or 3 == 3 or 5 == 6", "boolean[true]")

        chk("false or false and false", "boolean[false]")
        chk("false or false and true", "boolean[false]")
        chk("false or true and false", "boolean[false]")
        chk("false or true and true", "boolean[true]")
        chk("true or false and false", "boolean[true]")
        chk("true or false and true", "boolean[true]")
        chk("true or true and false", "boolean[true]")
        chk("true or true and true", "boolean[true]")

        chk("false and false or false", "boolean[false]")
        chk("false and false or true", "boolean[true]")
        chk("false and true or false", "boolean[false]")
        chk("false and true or true", "boolean[true]")
        chk("true and false or false", "boolean[false]")
        chk("true and false or true", "boolean[true]")
        chk("true and true or false", "boolean[true]")
        chk("true and true or true", "boolean[true]")

        chk("5 > 3 and 8 == 2 or 1 < 9", "boolean[true]")
        chk("5 > 3 and 8 == 2 or 9 == 6", "boolean[false]")
        chk("3 > 5 or 0 < 8 and 5 == 5", "boolean[true]")
        chk("5 == 5 or 3 > 5 and 8 < 0", "boolean[true]")

        chk("2 + 3 * 4 == 114 % 100", "boolean[true]")

        chk("2 * 3 + 4 * 5 + 6 * 7 + 8 * 9 + 10 * 11 + 12 * 13 + 14 * 15 + 16 * 17 + 18 * 19", "int[1230]")
        chk("2 * 3 + 4 * 5 - 6 * 7 + 8 * 9 - 10 * 11 + 12 * 13 - 14 * 15 + 16 * 17 - 18 * 19", "int[-178]")
    }

    @Test fun testShortCircuitEvaluation() {
        chk("1 / 0", "rt_err:expr_div_by_zero")
        chk("a != 0 and 15 / a > 3", 0, "boolean[false]")
        chk("a != 0 and 15 / a > 3", 5, "boolean[false]")
        chk("a != 0 and 15 / a > 3", 3, "boolean[true]")
        chk("a == 0 or 15 / a > 3", 0, "boolean[true]")
        chk("a == 0 or 15 / a > 3", 5, "boolean[false]")
        chk("a == 0 or 15 / a > 3", 3, "boolean[true]")
    }

    @Test fun testFunctionVsVariable() {
        chkEx("{ val abs = a; return abs(abs); }", 123, "int[123]")
        chkEx("{ val abs = a; return abs(abs); }", -123, "int[123]")
        chkEx("{ val abs = a; return abs; }", -123, "int[-123]")
    }

    @Test fun testListItems() {
        tst.useSql = true
        tst.classDefs = listOf("class user { name: text; }")

        tst.execOp("create user('Bob');")
        tst.execOp("create user('Alice');")
        tst.execOp("create user('Trudy');")

        chkEx("{ val s = user @* {}; return s[0]; }", "user[1]")
        chkEx("{ val s = user @* {}; return s[1]; }", "user[2]")
        chkEx("{ val s = user @* {}; return s[2]; }", "user[3]")

        chkEx("{ val s = user @* {}; return s[-1]; }", "rt_err:expr_list_lookup_index:3:-1")
        chkEx("{ val s = user @* {}; return s[3]; }", "rt_err:expr_list_lookup_index:3:3")
    }

    @Test fun testFunctionsUnderAt() {
        tst.useSql = true
        tst.classDefs = listOf("class user { name: text; score: integer; }")
        tst.execOp("create user('Bob',-5678);")

        chkEx("{ val s = 'Hello'; return user @ {} ( name.len() + s.len() ); }", "int[8]")
        chkEx("{ val x = -1234; return user @ {} ( abs(x), abs(score) ); }", "(int[1234],int[5678])")
    }

    @Test fun testUnitType() {
        chk("+ unit()", "ct_err:expr_operand_unit")
        chk("- unit()", "ct_err:expr_operand_unit")
        chk("not unit()", "ct_err:expr_operand_unit")
        chk("unit() + 5", "ct_err:expr_operand_unit")
        chk("abs(unit())", "ct_err:expr_arg_unit")
        chkEx("{ print(unit()); return 123; }", "ct_err:expr_arg_unit")
        chkEx("{ return unit(); }", "ct_err:stmt_return_unit")
    }

    @Test fun testDataClassPathExpr() {
        tst.useSql = true
        tst.classDefs = listOf("class company { name: text; }", "class user { name: text; company; }")

        tst.execOp("""
            val facebook = create company('Facebook');
            val amazon = create company('Amazon');
            val microsoft = create company('Microsoft');
            create user('Mark', facebook);
            create user('Jeff', amazon);
            create user('Bill', microsoft);
        """.trimIndent())

        chkEx("{ val u = user @ { 'Mark' }; return u.company.name; }", "text[Facebook]")
        chkEx("{ val u = user @ { 'Jeff' }; return u.company.name; }", "text[Amazon]")
        chkEx("{ val u = user @ { 'Bill' }; return u.company.name; }", "text[Microsoft]")

        chk("((u: user) @ { 'Mark' } ( u )).company.name", "text[Facebook]")
        chk("((u: user) @ { 'Jeff' } ( u )).company.name", "text[Amazon]")
        chk("((u: user) @ { 'Bill' } ( u )).company.name", "text[Microsoft]")
    }

    @Test fun testDataClassPathExprComplex() {
        tst.useSql = true
        tst.classDefs = listOf(
                "class c1 { name: text; }",
                "class c2 { name: text; c1; }",
                "class c3 { name: text; c2; }",
                "class c4 { name: text; c3; }"
        )

        tst.execOp("""
            val c1_1 = create c1('c1_1');
            val c1_2 = create c1('c1_2');
            val c2_1 = create c2('c2_1', c1_1);
            val c2_2 = create c2('c2_2', c1_2);
            val c3_1 = create c3('c3_1', c2_1);
            val c3_2 = create c3('c3_2', c2_2);
            val c4_1 = create c4('c4_1', c3_1);
            val c4_2 = create c4('c4_2', c3_2);
        """.trimIndent())

        chk("((c: c4) @ { 'c4_1' } ( c )).c3.c2.c1.name", "text[c1_1]")
        chk("((c: c4) @ { 'c4_2' } ( c )).c3.c2.c1.name", "text[c1_2]")

        chk("((c: c4) @ { 'c4_1' } ( t3 = c3, t2 = c3.c2 )).t3.c2.c1.name", "text[c1_1]")
        chk("((c: c4) @ { 'c4_1' } ( t3 = c3, t2 = c3.c2 )).t2.c1.name", "text[c1_1]")
        chk("((c: c4) @ { 'c4_2' } ( t3 = c3, t2 = c3.c2 )).t3.c2.c1.name", "text[c1_2]")
        chk("((c: c4) @ { 'c4_2' } ( t3 = c3, t2 = c3.c2 )).t2.c1.name", "text[c1_2]")
    }

    @Test fun testUninitializedVariableAccess() {
        chkEx("{ var x: integer; return x; }", "rt_err:expr_var_uninit:x")
        chkEx("{ var x: integer; x = 123; return x; }", "int[123]")
    }

    @Test fun testTuple() {
        chk("(123)", "int[123]")
        chk("(((123)))", "int[123]")
        chk("(123,)", "(int[123])")
        chk("(123,'Hello',false)", "(int[123],text[Hello],boolean[false])")
        chk("(123,('Hello',('World',456)),false)", "(int[123],(text[Hello],(text[World],int[456])),boolean[false])")
    }

    @Test fun testTupleAt() {
        tst.classDefs = listOf("class user { name: text; street: text; house: integer; }")

        chk("user @ {} ( x = (name,) ) ", "ct_err:expr_tuple_at")
        chk("user @ {} ( x = (name, street, house) ) ", "ct_err:expr_tuple_at")
    }

    @Test fun testList() {
        chk("list([1,2,3,4,5])", "list<integer>[int[1],int[2],int[3],int[4],int[5]]")
        chk("list()", "ct_err:expr_list_notype")
        chk("list<integer>()", "list<integer>[]")
        chk("list<integer>([1,2,3])", "list<integer>[int[1],int[2],int[3]]")
        chk("list<integer>(['Hello'])", "ct_err:expr_list_typemiss:integer:text")
        chk("list<text>([12345])", "ct_err:expr_list_typemiss:text:integer")
        chk("['Hello', 'World']", "list<text>[text[Hello],text[World]]")
        chk("['Hello', 'World', 12345]", "ct_err:expr_list_itemtype:text:integer")
        chk("[unit()]", "ct_err:expr_list_unit")
        chk("[print('Hello')]", "ct_err:expr_list_unit")
    }

    @Test fun testUnknownFunction() {
        chkEx("{ val s = 'Hello'; return s.badfunc(); }", "ct_err:expr_call_unknown:text:badfunc")
    }

    @Test fun testIn() {
        chk("123 in [123, 456]", "boolean[true]")
        chk("456 in [123, 456]", "boolean[true]")
        chk("789 in [123, 456]", "boolean[false]")
        chk("123 in list<integer>()", "boolean[false]")
        chk("123 in list<text>()", "ct_err:binop_operand_type:in:integer:list<text>")
        chk("'Hello' in list<integer>()", "ct_err:binop_operand_type:in:text:list<integer>")

        chk("123 in set([123, 456])", "boolean[true]")
        chk("456 in set([123, 456])", "boolean[true]")
        chk("789 in set([123, 456])", "boolean[false]")
        chk("123 in set<integer>()", "boolean[false]")
        chk("123 in set<text>()", "ct_err:binop_operand_type:in:integer:set<text>")
        chk("'Hello' in set<integer>()", "ct_err:binop_operand_type:in:text:set<integer>")

        chk("123 in [123:'Bob',456:'Alice']", "boolean[true]")
        chk("456 in [123:'Bob',456:'Alice']", "boolean[true]")
        chk("789 in [123:'Bob',456:'Alice']", "boolean[false]")
        chk("123 in map<integer,text>()", "boolean[false]")
        chk("123 in map<text,integer>()", "ct_err:binop_operand_type:in:integer:map<text,integer>")
        chk("'Hello' in map<integer,text>()", "ct_err:binop_operand_type:in:text:map<integer,text>")
    }

    /*@Test*/ fun testNamespace() {
        chk("integer", "ct_err:unknown_name:integer")
        chk("integer.parse('123')", "int[123]")
    }
}
