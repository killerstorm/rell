package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class AtExprCollectionTest: BaseRellTest(false) {
    @Test fun testConstants() {
        chk("[1,2,3,4,5] @* {} ( 123 )", "list<integer>[int[123],int[123],int[123],int[123],int[123]]")
        chk("[1,2,3] @* {} ( 123 )", "list<integer>[int[123],int[123],int[123]]")
        chk("[1] @* {} ( 123 )", "list<integer>[int[123]]")
        chk("list<integer>() @* {} ( 123 )", "list<integer>[]")
        chk("[1,2,3,4,5] @* {} ( 'A' )", "list<text>[text[A],text[A],text[A],text[A],text[A]]")

        chk("[1,2,3,4,5] @* { true } ( 123 )", "list<integer>[int[123],int[123],int[123],int[123],int[123]]")
        chk("[1,2,3,4,5] @* { false } ( 123 )", "list<integer>[]")
        chk("[1,2,3,4,5] @* {} ( 123 )", "list<integer>[int[123],int[123],int[123],int[123],int[123]]")

        chkEx("{ val f = [1,2,3,4,5]; val x = 123; return f @* {} ( x ); }", "list<integer>[int[123],int[123],int[123],int[123],int[123]]")
        chkEx("{ val f = [1,2,3,4,5]; val x = 'A'; return f @* {} ( x ); }", "list<text>[text[A],text[A],text[A],text[A],text[A]]")
        chkEx("{ val f = [1,2,3,4,5]; val x = 123; val y = true; return f @* { y } ( x ); }", "list<integer>[int[123],int[123],int[123],int[123],int[123]]")
        chkEx("{ val f = [1,2,3,4,5]; val x = 123; val y = false; return f @* { y } ( x ); }", "list<integer>[]")
    }

    @Test fun testFunctions() {
        def("struct ctr { mutable v: integer = 0; }")
        def("function fi(c: ctr) = c.v++;")
        def("function ft(c: ctr) = text.from_bytes(byte_array.from_list(['A'.char_at(0) + (c.v++)]));")
        def("function fb(c: ctr) = (c.v++) % 3 == 0;")

        chkEx("{ val c = ctr(); return [9,8,7,6,5] @* {} ( fi(c) ); }", "list<integer>[int[0],int[1],int[2],int[3],int[4]]")
        chkEx("{ val c = ctr(); return [9,8,7,6,5] @* {} ( ft(c) ); }", "list<text>[text[A],text[B],text[C],text[D],text[E]]")
        chkEx("{ val c = ctr(); return [9,8,7,6,5] @* { fb(c) } ( fi(c) ); }", "list<integer>[int[1],int[4],int[7]]")
    }

    @Test fun testPlaceholder() {
        chk("[1,2,3,4,5] @* {} ( $ )", "list<integer>[int[1],int[2],int[3],int[4],int[5]]")
        chk("[1,2,3,4,5] @* {} ( $ * $ )", "list<integer>[int[1],int[4],int[9],int[16],int[25]]")
        chk("[1,2,3,4,5] @* { $ % 2 != 0 } ( $ )", "list<integer>[int[1],int[3],int[5]]")
        chk("[1,2,3,4,5,6,7] @* { $ % 2 != 0 } ( $ )", "list<integer>[int[1],int[3],int[5],int[7]]")
        chk("['A','B','C','D','E'] @* {} ( $ )", "list<text>[text[A],text[B],text[C],text[D],text[E]]")
        chk("[true, false, true, false, true] @* { $ }", "list<boolean>[boolean[true],boolean[true],boolean[true]]")
        chk("[false, true, false, true, false] @* { $ }", "list<boolean>[boolean[true],boolean[true]]")
    }

    @Test fun testPlaceholderError() {
        tst.strictToString = false

        chk("[1,2,3,4,5] @* { $++ }", "ct_err:expr_bad_dst")
        chk("[1,2,3,4,5] @* {} ( $++ )", "ct_err:expr_bad_dst")

        chk("[1,2,3] @* {} ([4,5,6] @* {})", "[[4, 5, 6], [4, 5, 6], [4, 5, 6]]")
        chk("[1,2,3] @* {} ([4,5,6] @* {} ( $ ))", "ct_err:expr:at:placeholder_ambiguous")
        chk("[1,2,3] @* {exists([4,5,6] @* { $ > 0 })}", "ct_err:expr:at:placeholder_ambiguous")
    }

    @Test fun testCardinality() {
        chk("_type_of([0] @ {})", "text[integer]")
        chk("_type_of([0] @? {})", "text[integer?]")
        chk("_type_of([0] @* {})", "text[list<integer>]")
        chk("_type_of([0] @+ {})", "text[list<integer>]")

        chk("list<integer>() @ {}", "rt_err:at:wrong_count:0")
        chk("[123] @ {}", "int[123]")
        chk("[1,2] @ {}", "rt_err:at:wrong_count:2+")
        chk("[1,2,3] @ {}", "rt_err:at:wrong_count:2+")

        chk("list<integer>() @? {}", "null")
        chk("[123] @? {}", "int[123]")
        chk("[1,2] @? {}", "rt_err:at:wrong_count:2+")
        chk("[1,2,3] @? {}", "rt_err:at:wrong_count:2+")

        chk("list<integer>() @+ {}", "rt_err:at:wrong_count:0")
        chk("[123] @+ {}", "list<integer>[int[123]]")
        chk("[1,2] @+ {}", "list<integer>[int[1],int[2]]")
        chk("[1,2,3] @+ {}", "list<integer>[int[1],int[2],int[3]]")

        chk("list<integer>() @* {}", "list<integer>[]")
        chk("[123] @* {}", "list<integer>[int[123]]")
        chk("[1,2] @* {}", "list<integer>[int[1],int[2]]")
        chk("[1,2,3] @* {}", "list<integer>[int[1],int[2],int[3]]")
    }

    @Test fun testFromAlias() {
        chk("(x: [1,2,3,4,5]) @* {}", "list<integer>[int[1],int[2],int[3],int[4],int[5]]")
        chk("(x: [1,2,3,4,5]) @* { x % 2 != 0 }", "list<integer>[int[1],int[3],int[5]]")
        chk("(x: [1,2,3,4,5]) @* {} (x * x)", "list<integer>[int[1],int[4],int[9],int[16],int[25]]")
        chk("(x: [1,2,3,4,5]) @* { $ % 2 != 0 }", "ct_err:expr:at:placeholder_none")
        chk("(x: [1,2,3,4,5]) @* {} ( $ )", "ct_err:expr:at:placeholder_none")
    }

    @Test fun testFromAliasConflict() {
        chk("(x: [1,2,3], x: [4,5,6]) @* {}", "ct_err:expr_tuple_dupname:x")
        chk("(x: [1,2,3]) @* {} ((x: [4,5,6]) @* {})", "ct_err:expr_at_conflict_alias:x")
        chk("(x: [1,2,3]) @* {(x: [4,5,6]) @* {}}", "ct_err:expr_at_conflict_alias:x")
        chkEx("{ val x = 'Hello'; return (x: [1,2,3]) @* {}; }", "ct_err:expr_at_conflict_alias:x")
    }

    @Test fun testFromQualifiedName() {
        tst.strictToString = false
        tstCtx.useSql = true
        def("namespace foo.bar { entity user { name; score: integer; } }")
        def("struct s_user { name; score: integer; }")
        def("struct s_bar { user: list<s_user> = [s_user('Alice',456)]; }")
        def("struct s_foo { bar: s_bar = s_bar(); }")
        insert("c0.foo.bar.user", "name,score", "1,'Bob',123")

        chk("foo.bar.user @* {} (_=.name, _=.score)", "[(Bob,123)]")
        chk("(z:foo.bar.user) @* {} (_=z.name, _=z.score)", "[(Bob,123)]")
        chkEx("{ val v = s_foo(); return v.bar.user @* {} (_=$.name, _=$.score); }", "[(Alice,456)]")
        chkEx("{ val v = s_foo(); return (z:v.bar.user) @* {} (_=z.name, _=z.score); }", "[(Alice,456)]")
        chkEx("{ val foo = s_foo(); return foo.bar.user @* {} (_=.name, _=.score); }", "[(Bob,123)]")
        chkEx("{ val foo = s_foo(); return (z:foo.bar.user) @* {} (_=z.name, _=z.score); }", "[(Bob,123)]")
    }

    @Test fun testFromMultipleSources() {
        def("entity user { name; score: integer; }")
        chk("([1,2,3], [4,5,6]) @* {}", "ct_err:at:from:many_iterables:2")
        chk("(x:[1,2,3], y:[4,5,6]) @* {}", "ct_err:at:from:many_iterables:2")
        chk("([1,2,3], user) @* {}", "ct_err:at:from:mix_entity_iterable")
        chk("(x:[1,2,3], y:user) @* {}", "ct_err:at:from:mix_entity_iterable")
        chk("(x:user, y:[4,5,6]) @* {}", "ct_err:at:from:mix_entity_iterable")
    }

    @Test fun testFromTupleExpr() {
        tst.strictToString = false
        chk("(a : [1,2,3,4,5]) @* {}", "[1, 2, 3, 4, 5]")
        chk("(a : [1,2,3,4,5],) @* {}", "[1, 2, 3, 4, 5]")
        chk("(a = [1,2,3,4,5]) @* {}", "ct_err:expr:at:from:tuple_name_eq_expr:a")
        chk("(a = [1,2,3,4,5],) @* {}", "ct_err:expr:at:from:tuple_name_eq_expr:a")
        chk("(a : [1,2,3], b = [4,5,6]) @* {}", "ct_err:expr:at:from:tuple_name_eq_expr:b")
        chk("(a = [1,2,3], b : [4,5,6]) @* {}", "ct_err:expr:at:from:tuple_name_eq_expr:a")
    }

    @Test fun testFromListSet() {
        chk("list<integer>() @* {}", "list<integer>[]")
        chk("list<integer>([1,2,3]) @* {}", "list<integer>[int[1],int[2],int[3]]")
        chk("set<integer>() @* {}", "list<integer>[]")
        chk("set<integer>([1,2,3]) @* {}", "list<integer>[int[1],int[2],int[3]]")
    }

    @Test fun testFromMap() {
        chk("['Bob':123] @* {}", "ct_err:at:from:bad_type:map<text,integer>")
        chk("map<text,integer>() @* {}", "ct_err:at:from:bad_type:map<text,integer>")
    }

    @Test fun testFromForms() {
        tst.strictToString = false
        chk("[1,2,3,4,5] @* {}", "[1, 2, 3, 4, 5]")
        chk("list<integer>([1,2,3,4,5]) @* {}", "[1, 2, 3, 4, 5]")
        chk("_nop([1,2,3,4,5]) @* {}", "[1, 2, 3, 4, 5]")
        chkEx("{ val x = [1,2,3,4,5]; return x @* {}; }", "[1, 2, 3, 4, 5]")
        chkEx("{ val x = [1,2,3,4,5]; return (x) @* {}; }", "[1, 2, 3, 4, 5]")
        chkEx("{ val x = [1,2,3,4,5]; return (x,) @* {}; }", "[1, 2, 3, 4, 5]")
        chkEx("{ val x = [1,2,3,4,5]; return (x,x) @* {}; }", "ct_err:at:from:many_iterables:2")
    }

    @Test fun testWhereMatchByName() {
        tst.strictToString = false
        def("struct user { first_name: text; last_name: text; score: integer; }")
        def("function from() = [user(first_name='Bob',last_name='Meyer',123), user(first_name='Alice',last_name='Jones',score=456)];")

        chkEx("{ val first_name = 'Bob'; return from() @* { first_name } (); }", "ct_err:at_where:var_noattrs:0:first_name:text")
        chkEx("{ val last_name = 'Alice'; return from() @* { last_name } (); }", "ct_err:at_where:var_noattrs:0:last_name:text")
        chkEx("{ val last_name = 'Jones'; return from() @* { last_name } (); }", "ct_err:at_where:var_noattrs:0:last_name:text")
        chkEx("{ val first_name = 123; return from() @* { first_name } (); }", "ct_err:at_where:var_noattrs:0:first_name:integer")
    }

    @Test fun testWhereMatchByType() {
        tst.strictToString = false
        def("struct user { name: text; score: integer; }")
        def("function from() = [user('Bob',123), user('Alice',score=456)];")

        chk("from() @* { 'Bob' } ()", "ct_err:at_where_type:0:text")
        chk("from() @* { 'Alice' } ()", "ct_err:at_where_type:0:text")
        chk("from() @* { 123 } ()", "ct_err:at_where_type:0:integer")
        chk("from() @* { 456 } ()", "ct_err:at_where_type:0:integer")
    }

    @Test fun testWhatDefault() {
        chk("[1,2,3,4,5] @* {}", "list<integer>[int[1],int[2],int[3],int[4],int[5]]")
    }

    @Test fun testWhatAttributesStruct() {
        tst.strictToString = false
        def("struct user { name; score: integer; }")
        def("function from() = [user('Bob',123), user('Alice',456)];")
        chkWhatAttributes("from()", "$")
    }

    @Test fun testWhatAttributesEntity() {
        tst.strictToString = false
        tstCtx.useSql = true
        def("entity user { name; score: integer; }")
        insert("c0.user", "name,score", "1,'Bob',123", "2,'Alice',456")
        def("function from() = user @* {};")

        chkWhatAttributes("user", "")
        chkWhatAttributes("from()", "$")
    }

    private fun chkWhatAttributes(from: String, ph: String) {
        chk("$from @*{} ( $ph.name )", "[Bob, Alice]")
        chk("$from @*{} ( $ph.score )", "[123, 456]")
        chk("$from @*{} ( $ph.name, $ph.score )", "[(name=Bob,score=123), (name=Alice,score=456)]")
    }

    @Test fun testWhatOmit() {
        tst.strictToString = false
        chk("[1,2,3,4,5] @*{} ( $, $*$, $*$*$ )", "[(1,1,1), (2,4,8), (3,9,27), (4,16,64), (5,25,125)]")
        chk("[1,2,3,4,5] @*{} ( @omit $, $*$, $*$*$ )", "[(1,1), (4,8), (9,27), (16,64), (25,125)]")
        chk("[1,2,3,4,5] @*{} ( $, @omit $*$, $*$*$ )", "[(1,1), (2,8), (3,27), (4,64), (5,125)]")
        chk("[1,2,3,4,5] @*{} ( $, $*$, @omit $*$*$ )", "[(1,1), (2,4), (3,9), (4,16), (5,25)]")
        chk("[1,2,3,4,5] @*{} ( @omit $, @omit $*$, $*$*$ )", "[1, 8, 27, 64, 125]")
    }

    @Test fun testWhatSort() {
        tst.strictToString = false
        chk("[1,2,3,4,5] @*{} ( @sort $ )", "ct_err:expr:at:sort:col")
        chk("[1,2,3,4,5] @*{} ( @sort_desc $ )", "ct_err:expr:at:sort:col")
    }

    @Test fun testWhatAggregation() {
        chk("[1,2,3,4,5] @*{} ( @group $ )", "ct_err:expr:at:group:col")
        chk("[1,2,3,4,5] @*{} ( @sum $ )", "ct_err:expr:at:group:col")
        chk("[1,2,3,4,5] @*{} ( @min $ )", "ct_err:expr:at:group:col")
        chk("[1,2,3,4,5] @*{} ( @max $ )", "ct_err:expr:at:group:col")
    }

    @Test fun testLimit() {
        tst.strictToString = false

        chk("list<integer>() @*{} limit 0", "[]")
        chk("[1] @*{} limit 0", "[]")
        chk("[1,2,3,4,5] @*{} limit 0", "[]")

        chk("list<integer>() @*{} limit 1", "[]")
        chk("[1] @*{} limit 1", "[1]")
        chk("[1,2,3,4,5] @*{} limit 1", "[1]")

        chk("list<integer>() @*{} limit 3", "[]")
        chk("[1] @*{} limit 3", "[1]")
        chk("[1,2,3,4,5] @*{} limit 3", "[1, 2, 3]")

        chk("[1,2,3,4,5] @*{} limit -1", "rt_err:expr:at:limit:negative:-1")
    }

    @Test fun testOffset() {
        tst.strictToString = false

        chk("list<integer>() @*{} offset 0", "[]")
        chk("[1] @*{} offset 0", "[1]")
        chk("[1,2,3,4,5] @*{} offset 0", "[1, 2, 3, 4, 5]")

        chk("list<integer>() @*{} offset 1", "[]")
        chk("[1] @*{} offset 1", "[]")
        chk("[1,2,3,4,5] @*{} offset 1", "[2, 3, 4, 5]")

        chk("list<integer>() @*{} offset 3", "[]")
        chk("[1] @*{} offset 3", "[]")
        chk("[1,2,3,4,5] @*{} offset 3", "[4, 5]")

        chk("[1,2,3,4,5] @*{} offset -1", "rt_err:expr:at:offset:negative:-1")
    }

    @Test fun testLimitOffset() {
        tst.strictToString = false

        chk("list<integer>() @*{} offset 0 limit 3", "[]")
        chk("[1] @*{} offset 0 limit 3", "[1]")
        chk("[1,2,3,4,5] @*{} offset 0 limit 3", "[1, 2, 3]")

        chk("list<integer>() @*{} offset 3 limit 0", "[]")
        chk("list<integer>() @*{} offset 3 limit 1", "[]")
        chk("list<integer>() @*{} offset 3 limit 3", "[]")

        chk("[1] @*{} offset 3 limit 0", "[]")
        chk("[1] @*{} offset 3 limit 1", "[]")
        chk("[1] @*{} offset 3 limit 3", "[]")

        chk("[1,2,3,4,5] @*{} offset 3 limit 0", "[]")
        chk("[1,2,3,4,5] @*{} offset 3 limit 1", "[4]")
        chk("[1,2,3,4,5] @*{} offset 3 limit 2", "[4, 5]")
        chk("[1,2,3,4,5] @*{} offset 3 limit 3", "[4, 5]")
    }

    @Test fun testNested() {
        tst.strictToString = false
        chk("(x:[1,2,3]) @* {} ( (y:[4,5,6]) @* {} ( x, y ) )", "[[(1,4), (1,5), (1,6)], [(2,4), (2,5), (2,6)], [(3,4), (3,5), (3,6)]]")
        chk("(x:[1,2,3]) @* {} ( [4,5,6] @* {} ( x, $ ) )", "[[(1,4), (1,5), (1,6)], [(2,4), (2,5), (2,6)], [(3,4), (3,5), (3,6)]]")
        chk("(x:[1,2,3]) @* {} ( [4,5,6] @* {} ( $, x ) )", "[[(4,1), (5,1), (6,1)], [(4,2), (5,2), (6,2)], [(4,3), (5,3), (6,3)]]")
        chk("[1,2,3] @* {} ( (y:[4,5,6]) @* {} ( $, y ) )", "[[(1,4), (1,5), (1,6)], [(2,4), (2,5), (2,6)], [(3,4), (3,5), (3,6)]]")
        chk("[1,2,3] @* {} ( (y:[4,5,6]) @* {} ( y, $ ) )", "[[(4,1), (5,1), (6,1)], [(4,2), (5,2), (6,2)], [(4,3), (5,3), (6,3)]]")
    }

    @Test fun testUseAsParamDefaultValue() {
        tst.strictToString = false
        def("function f1(x: list<integer> = [1,2,3] @* {}) = list(x);")
        def("function f2(x: list<integer> = [1,2,3] @* {} ($*$)) = list(x);")
        def("function f3(x: list<integer> = (a:[1,2,3]) @* {} (a*a)) = list(x);")
        chkCompile("", "ct_err:[expr:at:bad_context][expr:at:bad_context][expr:at:bad_context]")
        //chk("f1()", "...")
        //chk("f2()", "...")
        //chk("f3()", "...")
    }

    @Test fun testUseAsStructAttrDefaultValue() {
        def("""
            struct foo {
                x: list<integer> = [1,2,3] @* {};
                y: list<integer> = [1,2,3] @* {} ($*$);
                z: list<integer> = (a:[1,2,3]) @* {} (a*a);
            }
        """)
        chkCompile("", "ct_err:[expr:at:bad_context][expr:at:bad_context][expr:at:bad_context]")
        //chk("foo().x", "")
        //chk("foo().y", "")
        //chk("foo().z", "")
    }

    @Test fun testUseAsEntityAttrDefaultValue() {
        tstCtx.useSql = true
        def("""
            entity foo {
                x: integer = ([1,2,3] @* {})[2];
                y: integer = ([1,2,3] @* {} ($*$))[2];
                z: integer = ((a:[1,2,3]) @* {} (a*a))[2];
            }
        """)
        chkCompile("", "ct_err:[expr:at:bad_context][expr:at:bad_context][expr:at:bad_context]")
        //chkOp("create foo();")
        //chk("(foo @ {}).x", "")
        //chk("(foo @ {}).y", "")
        //chk("(foo @ {}).z", "")
    }

    @Test fun testUseAsObjectAttrDefaultValue() {
        tstCtx.useSql = true
        def("""
            object foo {
                x: integer = ([1,2,3] @* {})[2];
                y: integer = ([1,2,3] @* {} ($*$))[2];
                z: integer = ((a:[1,2,3]) @* {} (a*a))[2];
            }
        """)
        chkCompile("", "ct_err:[expr:at:bad_context][expr:at:bad_context][expr:at:bad_context]")
        //chk("foo.x", "")
        //chk("foo.y", "")
        //chk("foo.z", "")
    }

    @Test fun testChainCollection() {
        tst.strictToString = false

        chk("[1,2,3,4,5] @*{} ( $ * $ )", "[1, 4, 9, 16, 25]")
        chk("[1,2,3,4,5] @*{} ( $ * $ ) @*{} ( $ + 3 )", "[4, 7, 12, 19, 28]")
        chk("[1,2,3,4,5] @*{} ( $ * $ ) @*{} ( $ + 3 ) @*{} ( $ / 2 )", "[2, 3, 6, 9, 14]")

        chk("[1,2,3,4,5] @*{} ( $ * $ )", "[1, 4, 9, 16, 25]")
        chk("[1,2,3,4,5] @*{} ( $ * $ ) @+ { $ % 2 != 0 }", "[1, 9, 25]")
        chk("[1,2,3,4,5] @*{} ( $ * $ ) @+ { $ % 2 != 0 } @*{} ( $*2 )", "[2, 18, 50]")
        chk("[1,2,3,4,5] @*{} ( $ * $ ) @+ { $ % 2 != 0 } @*{} ( $*2 ) @ { $ >= 20 }", "50")
    }

    @Test fun testChainDatabase() {
        tst.strictToString = false
        tstCtx.useSql = true
        def("entity user { name; score: integer; }")
        insert("c0.user", "name,score", "1,'Bob',123", "2,'Alice',456")

        chk("user @*{}", "[user[1], user[2]]")
        chk("user @*{} @+{} ( $.name, $.score )", "[(name=Bob,score=123), (name=Alice,score=456)]")
        chk("user @*{} @+{} ( $.name, $.score ) @* { $.score >= 300 }", "[(name=Alice,score=456)]")
        chk("user @*{} @+{} ( $.name, $.score ) @* { $.score >= 300 } @?{} ( $.name )", "Alice")
    }
}
