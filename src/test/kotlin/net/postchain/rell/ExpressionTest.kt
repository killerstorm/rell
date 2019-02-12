package net.postchain.rell

import net.postchain.rell.runtime.Rt_BooleanValue
import net.postchain.rell.test.BaseRellTest
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
        chkEx("{ val abs = a; return abs.str(); }", -123, "text[-123]")
    }

    @Test fun testMemberFunctionVsField() {
        tst.defs = listOf("record foo { toGTXValue: integer; }")
        chkEx("{ val v = foo(123); return v.toGTXValue; }", "int[123]")
        chkEx("{ val v = foo(123); return v.toGTXValue.str(); }", "text[123]")
        chkEx("{ val v = foo(123); return v.toGTXValue().toJSON().str(); }", "text[[123]]")
    }

    @Test fun testListItems() {
        tst.useSql = true
        tst.defs = listOf("class user { name: text; }")

        chkOp("create user('Bob');")
        chkOp("create user('Alice');")
        chkOp("create user('Trudy');")

        chkEx("{ val s = user @* {}; return s[0]; }", "user[1]")
        chkEx("{ val s = user @* {}; return s[1]; }", "user[2]")
        chkEx("{ val s = user @* {}; return s[2]; }", "user[3]")

        chkEx("{ val s = user @* {}; return s[-1]; }", "rt_err:expr_list_lookup_index:3:-1")
        chkEx("{ val s = user @* {}; return s[3]; }", "rt_err:expr_list_lookup_index:3:3")
    }

    @Test fun testFunctionsUnderAt() {
        tst.useSql = true
        tst.defs = listOf("class user { name: text; score: integer; }")
        chkOp("create user('Bob',-5678);")

        chkEx("{ val s = 'Hello'; return user @ {} ( .name.len() + s.len() ); }", "int[8]")
        chkEx("{ val x = -1234; return user @ {} ( abs(x), abs(.score) ); }", "(int[1234],int[5678])")
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
        tst.defs = listOf("class company { name: text; }", "class user { name: text; company; }")

        chkOp("""
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

    @Test fun testDataClassPathExprMix() {
        tst.useSql = true
        tst.defs = listOf("class company { name: text; }", "class user { name: text; company; }")

        chkOp("""
            val microsoft = create company('Microsoft');
            create user('Bill', microsoft);
        """.trimIndent())

        chk("((u: user) @ { 'Bill' } ( u )).company.name.size()", "int[9]")
        chk("((u: user) @ { 'Bill' } ( u )).company.name.upperCase()", "text[MICROSOFT]")
        chk("((u: user) @ { 'Bill' } ( u )).company.name.replace('soft', 'hard')", "text[Microhard]")
        chk("((u: user) @ { 'Bill' } ( u )).company.name.split('o')", "list<text>[text[Micr],text[s],text[ft]]")
        chk("((u: user) @ { 'Bill' } ( u )).company.name.matches('[A-Za-z]+')", "boolean[true]")
        chk("((u: user) @ { 'Bill' } ( u )).company.name.matches('[0-9]+')", "boolean[false]")
        chk("((u: user) @ { 'Bill' } ( u )).company.name.upperCase().replace('SOFT','HARD').lowerCase()", "text[microhard]")

        chkEx("{ val x = user@{'Bill'}(u=user); return x.u.company.name.size(); }", "int[9]")
        chkEx("{ val x = user@{'Bill'}(u=user); return x.u.company.name.upperCase(); }", "text[MICROSOFT]")
        chkEx("{ val x = user@{'Bill'}(u=user); return x.u.company.name.replace('soft', 'hard'); }", "text[Microhard]")
        chkEx("{ val x = user@{'Bill'}(u=user); return x.u.company.name.split('o'); }", "list<text>[text[Micr],text[s],text[ft]]")
        chkEx("{ val x = user@{'Bill'}(u=user); return x.u.company.name.matches('[A-Za-z]+'); }", "boolean[true]")
        chkEx("{ val x = user@{'Bill'}(u=user); return x.u.company.name.matches('[0-9]+'); }", "boolean[false]")
        chkEx("{ val x = user@{'Bill'}(u=user); return x.u.company.name.encode().decode().upperCase().replace('SOFT','HARD').lowerCase(); }",
                "text[microhard]")
    }

    @Test fun testDataClassPathExprComplex() {
        tst.useSql = true
        tst.defs = listOf(
                "class c1 { name: text; }",
                "class c2 { name: text; c1; }",
                "class c3 { name: text; c2; }",
                "class c4 { name: text; c3; }"
        )

        chkOp("""
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

        chk("((c: c4) @ { 'c4_1' } ( t3 = .c3, t2 = .c3.c2 )).t3.c2.c1.name", "text[c1_1]")
        chk("((c: c4) @ { 'c4_1' } ( t3 = .c3, t2 = .c3.c2 )).t2.c1.name", "text[c1_1]")
        chk("((c: c4) @ { 'c4_2' } ( t3 = .c3, t2 = .c3.c2 )).t3.c2.c1.name", "text[c1_2]")
        chk("((c: c4) @ { 'c4_2' } ( t3 = .c3, t2 = .c3.c2 )).t2.c1.name", "text[c1_2]")
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
        chk("(123,456)", "(int[123],int[456])")
        chk("(123,456,)", "ct_err:syntax")
        chk("(a=123)", "(a=int[123])")
        chk("(a=123,)", "(a=int[123])")
        chk("(a=123,b='Hello')", "(a=int[123],b=text[Hello])")
        chk("(a=123,b='Hello',)", "ct_err:syntax")
        chk("(a=123,'Hello')", "(a=int[123],text[Hello])")
        chk("(123,b='Hello')", "(int[123],b=text[Hello])")
        chk("(a='Hello',b=(x=123,y=456))", "(a=text[Hello],b=(x=int[123],y=int[456]))")
        chk("(a=123,a=456)", "ct_err:expr_tuple_dupname:a")
        chk("(a=123,a=123)", "ct_err:expr_tuple_dupname:a")
    }

    @Test fun testTupleAt() {
        tst.defs = listOf("class user { name: text; street: text; house: integer; }")

        chk("user @ {} ( x = (.name,) ) ", "ct_err:expr_sqlnotallowed")
        chk("user @ {} ( x = (.name, .street, .house) ) ", "ct_err:expr_sqlnotallowed")
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
        chkEx("{ val s = 'Hello'; return s.badfunc(); }", "ct_err:unknown_member:text:badfunc")
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

    @Test fun testNamespace() {
        chk("integer", "ct_err:unknown_name:integer")
        chk("integer('123')", "int[123]")
        chk("integer.parseHex('1234')", "int[4660]")
        chk("integer.MAX_VALUE", "int[9223372036854775807]")
    }

    @Test fun testNamespaceUnderAt() {
        tst.useSql = true
        tst.defs = listOf("class user { name: text; score: integer; }")
        chkOp("create user('Bob',-5678);")

        chk("user @ { .score == integer }", "ct_err:unknown_name:integer")
        chk("user @ { .score == integer('-5678') } ( .name )", "text[Bob]")
        chk("user @ { .score == -5678 } ( .name, integer('1234') )", "(name=text[Bob],int[1234])")
        chk("user @ { .score == -integer.parseHex('162e') } ( .name )", "text[Bob]")
        chk("user @ { .name == 'Bob' } ( .name, .score + integer.parseHex('1234') )", "(name=text[Bob],int[-1018])")

        chk("user @ { .score < integer.MAX_VALUE } ( .name )", "text[Bob]")
        chk("user @* { .score < integer.MIN_VALUE } ( .name )", "list<text>[]")
        chk("user @ { integer.MAX_VALUE + .score == 9223372036854770129 } ( .name )", "text[Bob]")
        chk("user @ {} ( .name, .score + integer.MAX_VALUE )", "(name=text[Bob],int[9223372036854770129])")
    }

    @Test fun testMoreFunctionsUnderAt() {
        tst.useSql = true
        tst.strictToString = false
        tst.defs = listOf("class user { id: integer; name1: text; name2: text; v1: integer; v2: integer; }")
        chkOp("""
            create user(id = 1, name1 = 'Bill', name2 = 'Gates', v1 = 111, v2 = 222);
            create user(id = 2, name1 = 'Mark', name2 = 'Zuckerberg', v1 = 333, v2 = 444);
            create user(id = 3, name1 = 'Steve', name2 = 'Wozniak', v1 = 555, v2 = 666);
        """.trimIndent())

        chkEx("{ return user @* {} (.id+0, (.name1 + .name2).size()); }", "[(1,9), (2,14), (3,12)]")
        chkEx("{ return user @* {} (.id+0, (.name1 + .name2).upperCase().lowerCase().size()); }", "[(1,9), (2,14), (3,12)]")
        chkEx("{ return user @* {} (.id+0, (.v1 * (.v2 + 101)).str()); }", "[(1,35853), (2,181485), (3,425685)]")
        chkEx("{ return user @* {} (.id+0, (.v1 * (.v2 + 101)).str().size()); }", "[(1,5), (2,6), (3,6)]")
        chkEx("{ return user @* {} (.id+0, (.name1 + .name2).foo); }", "ct_err:unknown_member:text:foo")
        chkEx("{ return user @* {} (.id+0, (.v1 * (.v2 + 101)).foo); }", "ct_err:unknown_member:integer:foo")
        chkEx("{ return user @* {} (.id+0, (.name1 + .name2).foo()); }", "ct_err:unknown_member:text:foo")
        chkEx("{ return user @* {} (.id+0, (.v1 * (.v2 + 101)).foo()); }", "ct_err:unknown_member:integer:foo")

        val c = "val str1 = 'Hello'; val k1 = 777;"
        chkEx("{ $c return user @* {} (.id+0, (str1 + .name2).size()); }", "[(1,10), (2,15), (3,12)]")
        chkEx("{ $c return user @* {} (.id+0, (str1 + .name2).upperCase().lowerCase().size()); }", "[(1,10), (2,15), (3,12)]")
        chkEx("{ $c return user @* {} (.id+0, (k1 * (.v2 + 101)).str()); }", "[(1,250971), (2,423465), (3,595959)]")
        chkEx("{ $c return user @* {} (.id+0, (k1 * (.v2 + 101)).str().size()); }", "[(1,6), (2,6), (3,6)]")
        chkEx("{ $c return user @* {} (.id+0, (str1 + .name2).foo); }", "ct_err:unknown_member:text:foo")
        chkEx("{ $c return user @* {} (.id+0, (k1 * (.v2 + 101)).foo); }", "ct_err:unknown_member:integer:foo")
        chkEx("{ $c return user @* {} (.id+0, (str1 + .name2).foo()); }", "ct_err:unknown_member:text:foo")
        chkEx("{ $c return user @* {} (.id+0, (k1 * (.v2 + 101)).foo()); }", "ct_err:unknown_member:integer:foo")
    }

    @Test fun testPathError() {
        chkEx("{ val s = 'Hello'; return s.foo.bar; }", "ct_err:unknown_member:text:foo")
        chkEx("{ val s = 'Hello'; return s.foo.bar(); }", "ct_err:unknown_member:text:foo")
        chkEx("{ val s = 'Hello'; return s.foo(); }", "ct_err:unknown_member:text:foo")
    }

    @Test fun testCallNotCallable() {
        chk("123()", "ct_err:expr_call_nofn:integer")
        chk("123(456)", "ct_err:expr_call_nofn:integer")
        chk("'Hello'()", "ct_err:expr_call_nofn:text")
        chk("'Hello'(123)", "ct_err:expr_call_nofn:text")
    }

    @Test fun testRefEq() {
        chkEx("{ val a = [1, 2, 3]; val b = [1, 2, 3]; return a == b; }", "boolean[true]")
        chkEx("{ val a = [1, 2, 3]; val b = [1, 2, 3]; return a != b; }", "boolean[false]")
        chkEx("{ val a = [1, 2, 3]; val b = [1, 2, 3]; return a === b; }", "boolean[false]")
        chkEx("{ val a = [1, 2, 3]; val b = [1, 2, 3]; return a !== b; }", "boolean[true]")
        chkEx("{ val a = [1, 2, 3]; val b = a; return a === b; }", "boolean[true]")
        chkEx("{ val a = [1, 2, 3]; val b = a; return a !== b; }", "boolean[false]")

        chkEx("{ val a = set([1, 2, 3]); val b = set([1, 2, 3]); return a == b; }", "boolean[true]")
        chkEx("{ val a = set([1, 2, 3]); val b = set([1, 2, 3]); return a != b; }", "boolean[false]")
        chkEx("{ val a = set([1, 2, 3]); val b = set([1, 2, 3]); return a === b; }", "boolean[false]")
        chkEx("{ val a = set([1, 2, 3]); val b = set([1, 2, 3]); return a !== b; }", "boolean[true]")
        chkEx("{ val a = set([1, 2, 3]); val b = a; return a === b; }", "boolean[true]")
        chkEx("{ val a = set([1, 2, 3]); val b = a; return a !== b; }", "boolean[false]")

        chkEx("{ val a = [1:'A',2:'B',3:'C']; val b = [1:'A',2:'B',3:'C']; return a == b; }", "boolean[true]")
        chkEx("{ val a = [1:'A',2:'B',3:'C']; val b = [1:'A',2:'B',3:'C']; return a != b; }", "boolean[false]")
        chkEx("{ val a = [1:'A',2:'B',3:'C']; val b = [1:'A',2:'B',3:'C']; return a === b; }", "boolean[false]")
        chkEx("{ val a = [1:'A',2:'B',3:'C']; val b = [1:'A',2:'B',3:'C']; return a !== b; }", "boolean[true]")
        chkEx("{ val a = [1:'A',2:'B',3:'C']; val b = a; return a === b; }", "boolean[true]")
        chkEx("{ val a = [1:'A',2:'B',3:'C']; val b = a; return a !== b; }", "boolean[false]")

        chkEx("{ val a = (123, 'Hello'); val b = (123, 'Hello'); return a == b; }", "boolean[true]")
        chkEx("{ val a = (123, 'Hello'); val b = (123, 'Hello'); return a != b; }", "boolean[false]")
        chkEx("{ val a = (123, 'Hello'); val b = (123, 'Hello'); return a === b; }", "boolean[false]")
        chkEx("{ val a = (123, 'Hello'); val b = (123, 'Hello'); return a !== b; }", "boolean[true]")
        chkEx("{ val a = (123, 'Hello'); val b = a; return a === b; }", "boolean[true]")
        chkEx("{ val a = (123, 'Hello'); val b = a; return a !== b; }", "boolean[false]")
        chkEx("{ val a = (123, 'Hello'); val b = ('Hello', 123); return a == b; }",
                "ct_err:binop_operand_type:==:(integer,text):(text,integer)")

        chkEx("{ val a = range(123); val b = range(123); return a == b; }", "boolean[true]")
        chkEx("{ val a = range(123); val b = range(123); return a != b; }", "boolean[false]")
        chkEx("{ val a = range(123); val b = range(123); return a === b; }", "boolean[false]")
        chkEx("{ val a = range(123); val b = range(123); return a !== b; }", "boolean[true]")
        chkEx("{ val a = range(123); val b = a; return a === b; }", "boolean[true]")
        chkEx("{ val a = range(123); val b = a; return a !== b; }", "boolean[false]")

        chkEx("{ return 123 === 123; }", "ct_err:binop_operand_type:===:integer:integer")
        chkEx("{ return 123 !== 123; }", "ct_err:binop_operand_type:!==:integer:integer")
        chkEx("{ return true === true; }", "ct_err:binop_operand_type:===:boolean:boolean")
        chkEx("{ return true !== true; }", "ct_err:binop_operand_type:!==:boolean:boolean")
        chkEx("{ return 'Hello' === 'Hello'; }", "ct_err:binop_operand_type:===:text:text")
        chkEx("{ return 'Hello' !== 'Hello'; }", "ct_err:binop_operand_type:!==:text:text")
        chkEx("{ return x'12AB' === x'12AB'; }", "ct_err:binop_operand_type:===:byte_array:byte_array")
        chkEx("{ return x'12AB' !== x'12AB'; }", "ct_err:binop_operand_type:!==:byte_array:byte_array")
        chkEx("{ return null === null; }", "ct_err:binop_operand_type:===:null:null")
        chkEx("{ return null !== null; }", "ct_err:binop_operand_type:!==:null:null")
    }

    @Test fun testEqRefNullable() {
        chkEx("{ val a: integer? = 123; return a === null; }", "ct_err:binop_operand_type:===:integer?:null")
        chkEx("{ val a: integer? = 123; return a !== null; }", "ct_err:binop_operand_type:!==:integer?:null")
        chkEx("{ val a: integer? = 123; return a === 123; }", "ct_err:binop_operand_type:===:integer?:integer")
        chkEx("{ val a: integer? = 123; return a !== 123; }", "ct_err:binop_operand_type:!==:integer?:integer")

        chkEx("{ val a: list<integer>? = [1,2,3]; return a === null; }", "boolean[false]")
        chkEx("{ val a: list<integer>? = [1,2,3]; return a !== null; }", "boolean[true]")
        chkEx("{ val a: list<integer>? = [1,2,3]; return a === [1,2,3]; }", "boolean[false]")
        chkEx("{ val a: list<integer>? = [1,2,3]; return a !== [1,2,3]; }", "boolean[true]")
        chkEx("{ val a: list<integer>? = [1,2,3]; val b = a; return a === b; }", "boolean[true]")
        chkEx("{ val a: list<integer>? = [1,2,3]; val b = a; return a !== b; }", "boolean[false]")
        chkEx("{ val a: list<integer>? = null; return a === null; }", "boolean[true]")
        chkEx("{ val a: list<integer>? = null; return a !== null; }", "boolean[false]")
        chkEx("{ val a: list<integer>? = null; return a === [1,2,3]; }", "boolean[false]")
        chkEx("{ val a: list<integer>? = null; return a !== [1,2,3]; }", "boolean[true]")
    }

    @Test fun testIf() {
        chkEx("= if (a) 1 else 2;", true, "int[1]")
        chkEx("= if (a) 1 else 2;", false, "int[2]")
        chkEx("= if (a) 'Hello' else 123;", true, "ct_err:expr_if_restype:text:integer")
        chkEx("= if (a) 123 else null;", true, "int[123]")
        chkEx("= if (a) 123 else null;", false, "null")
        chkEx("= if (a) (null, 'Hello') else (123, null);", true, "(null,text[Hello])")
        chkEx("= if (a) (null, 'Hello') else (123, null);", false, "(int[123],null)")
        chkEx("= if (a) (null, 'Hello') else (null, 123);", true, "ct_err:expr_if_restype:(null,text):(null,integer)")

        chk("if (123) 'A' else 'B'", "ct_err:expr_if_cond_type:boolean:integer")
        chk("if ('Hello') 'A' else 'B'", "ct_err:expr_if_cond_type:boolean:text")
        chk("if (null) 'A' else 'B'", "ct_err:expr_if_cond_type:boolean:null")
        chk("if (unit()) 'A' else 'B'", "ct_err:expr_if_cond_type:boolean:unit")
        chkEx("{ val x: boolean? = true; return if (x) 'A' else 'B'; }", "ct_err:expr_if_cond_type:boolean:boolean?")
    }

    @Test fun testIfShortCircuit() {
        val code = """
            function f(s: text, v: integer): integer {
               print(s);
               return v;
            }
            query q(a: boolean) = if (a) f('Yes', 123) else f('No', 456);
        """.trimIndent()

        chkFull(code, listOf(Rt_BooleanValue(true)), "int[123]")
        chkStdout("Yes")
        chkFull(code, listOf(Rt_BooleanValue(false)), "int[456]")
        chkStdout("No")
    }

    @Test fun testIfPrecedence() {
        chk("if (true) 'A' else 'B' + 'C'", "text[A]")
        chk("if (false) 'A' else 'B' + 'C'", "text[BC]")
        chk("if (true) 'A' else if (true) 'B' else 'C' + 'D'", "text[A]")
        chk("if (false) 'A' else if (true) 'B' else 'C' + 'D'", "text[B]")
        chk("if (false) 'A' else if (false) 'B' else 'C' + 'D'", "text[CD]")
    }
}
