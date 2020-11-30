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
        chk("[1,2,3] @* {} ([4,5,6] @* {} ( $ ))", "ct_err:expr:placeholder:ambiguous")
        chk("[1,2,3] @* {exists([4,5,6] @* { $ > 0 })}", "ct_err:expr:placeholder:ambiguous")
    }

    @Test fun testPlaceholderMixed() {
        tstCtx.useSql = true
        tst.strictToString = false
        def("entity user { name; }")
        insert("c0.user", "name", "100,'Bob'", "101,'Alice'")

        chk("user @* {} ([123] @ {} ( $ ))", "ct_err:expr:placeholder:ambiguous")
        chk("[123] @ {} (user @* {} ( $ ))", "ct_err:expr:placeholder:ambiguous")
        chk("(x: user) @* {} ([123] @ {} ( $ ))", "[123, 123]")
        chk("(x: [123]) @ {} (user @* {} ( $ ))", "[user[100], user[101]]")
        chk("user @* {} ((x: [123]) @ {} ( $ ))", "ct_err:expr:placeholder:none")
        chk("[123] @ {} ((x: user) @* {} ( $ ))", "ct_err:expr:placeholder:alias")
    }

    @Test fun testCardinality() {
        chk("_type_of([0] @ {})", "text[integer]")
        chk("_type_of([0] @? {})", "text[integer?]")
        chk("_type_of([0] @* {})", "text[list<integer>]")
        chk("_type_of([0] @+ {})", "text[list<integer>]")

        chk("list<integer>() @ {}", "rt_err:at:wrong_count:0")
        chk("[123] @ {}", "int[123]")
        chk("[1,2] @ {}", "rt_err:at:wrong_count:2")
        chk("[1,2,3] @ {}", "rt_err:at:wrong_count:3")

        chk("list<integer>() @? {}", "null")
        chk("[123] @? {}", "int[123]")
        chk("[1,2] @? {}", "rt_err:at:wrong_count:2")
        chk("[1,2,3] @? {}", "rt_err:at:wrong_count:3")

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
        chk("(x: [1,2,3,4,5]) @* { $ % 2 != 0 }", "ct_err:expr:placeholder:none")
        chk("(x: [1,2,3,4,5]) @* {} ( $ )", "ct_err:expr:placeholder:none")
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
        chkEx("{ val v = s_foo(); return v.bar.user @* {} (_=.name, _=.score); }", "[(Alice,456)]")
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
        tst.strictToString = false

        chk("_type_of(['Bob':123] @ {})", "(k:text,v:integer)")
        chk("_type_of(['Bob':123] @ {} ( $) )", "(k:text,v:integer)")
        chk("_type_of(['Bob':123] @ {} ( .k ))", "text")
        chk("_type_of(['Bob':123] @ {} ( .v ))", "integer")
        chk("_type_of(['Bob':123] @* {})", "list<(k:text,v:integer)>")
        chk("_type_of(['Bob':123] @* {} ( $ ))", "list<(k:text,v:integer)>")
        chk("_type_of(['Bob':123] @* {} ( .k ))", "list<text>")
        chk("_type_of(['Bob':123] @* {} ( .v ))", "list<integer>")

        chk("['Bob':123, 'Alice':456] @* {}", "[(k=Bob,v=123), (k=Alice,v=456)]")
        chk("map<text,integer>() @* {}", "[]")

        chk("['Bob':123, 'Alice':456] @* {} ( .k )", "[Bob, Alice]")
        chk("['Bob':123, 'Alice':456] @* {} ( .v )", "[123, 456]")
        chk("['Bob':123, 'Alice':456] @* {} ( .k, .v )", "[(k=Bob,v=123), (k=Alice,v=456)]")
        chk("['Bob':123, 'Alice':456] @* {} ( .k + .v )", "[Bob123, Alice456]")
        chk("['Bob':123, 'Alice':456] @* {} ( _=.k, _=.v )", "[(Bob,123), (Alice,456)]")
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

        chkEx("{ val first_name = 'Bob'; return from() @* { first_name }; }", "[user{first_name=Bob,last_name=Meyer,score=123}]")
        chkEx("{ val last_name = 'Bob'; return from() @* { last_name }; }", "[]")
        chkEx("{ val last_name = 'Alice'; return from() @* { last_name }; }", "[]")
        chkEx("{ val last_name = 'Jones'; return from() @* { last_name }; }", "[user{first_name=Alice,last_name=Jones,score=456}]")
        chkEx("{ val first_name = 123; return from() @* { first_name }; }", "ct_err:at_where:var_noattrs:0:first_name:integer")
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
        chkWhatAttributes("from()")
    }

    @Test fun testWhatAttributesTuple() {
        tst.strictToString = false
        def("function from() = [(name='Bob',score=123), (name='Alice',score=456)];")
        chkWhatAttributes("from()")
    }

    @Test fun testWhatAttributesEntity() {
        tst.strictToString = false
        tstCtx.useSql = true
        def("entity user { name; score: integer; }")
        def("function from() = user @* {};")
        insert("c0.user", "name,score", "1001,'Bob',123", "1002,'Alice',456")

        chkWhatAttributesEntity("user")
        chkWhatAttributesEntity("from()")
    }

    private fun chkWhatAttributesEntity(from: String) {
        chkWhatAttributes(from)
        chk("$from @*{} ( .rowid )", "[1001, 1002]")
        chk("$from @*{} ( $.rowid )", "[1001, 1002]")
    }

    private fun chkWhatAttributes(from: String) {
        chk("$from @*{} ( .name )", "[Bob, Alice]")
        chk("$from @*{} ( $.name )", "[Bob, Alice]")
        chk("$from @*{} ( .score )", "[123, 456]")
        chk("$from @*{} ( $.score )", "[123, 456]")
        chk("$from @*{} ( .name, .score )", "[(name=Bob,score=123), (name=Alice,score=456)]")
        chk("$from @*{} ( $.name, $.score )", "[(name=Bob,score=123), (name=Alice,score=456)]")
    }

    @Test fun testWhatAttributesEnum() {
        tst.strictToString = false
        def("enum color { red, green, blue }")

        val from = "color.values()"
        chk("$from @*{} ( .name )", "[red, green, blue]")
        chk("$from @*{} ( $.name )", "[red, green, blue]")
        chk("$from @*{} ( .value )", "[0, 1, 2]")
        chk("$from @*{} ( $.value )", "[0, 1, 2]")
    }

    @Test fun testWhatAttributesFunction() {
        tst.strictToString = false
        val from = "['Bob', 'Alice']"
        chk("$from @*{} ( .size() )", "ct_err:expr_attr_unknown:size")
        chk("$from @*{} ( $.size() )", "[3, 5]")
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

        chk("[1,2,3,4,5] @* {}", "[1, 2, 3, 4, 5]")
        chk("[5,4,3,2,1] @* {}", "[5, 4, 3, 2, 1]")

        chk("[1,2,3,4,5] @*{} ( @sort $ )", "[1, 2, 3, 4, 5]")
        chk("[1,2,3,4,5] @*{} ( @sort_desc $ )", "[5, 4, 3, 2, 1]")
        chk("[5,4,3,2,1] @*{} ( @sort $ )", "[1, 2, 3, 4, 5]")
        chk("[5,4,3,2,1] @*{} ( @sort_desc $ )", "[5, 4, 3, 2, 1]")

        chk("[1,2,3,4,5] @*{} ( $, @omit @sort -$ )", "[5, 4, 3, 2, 1]")
        chk("[1,2,3,4,5] @*{} ( $, @omit @sort_desc -$ )", "[1, 2, 3, 4, 5]")
        chk("[5,4,3,2,1] @*{} ( $, @omit @sort -$ )", "[5, 4, 3, 2, 1]")
        chk("[5,4,3,2,1] @*{} ( $, @omit @sort_desc -$ )", "[1, 2, 3, 4, 5]")
    }

    @Test fun testWhatSortComplex() {
        initDataCountries()

        chk("countries() @* {} ( .name )", "[Germany, Austria, United Kingdom, USA, Mexico, China]")
        chk("countries() @* {} ( @sort .name )", "[Austria, China, Germany, Mexico, USA, United Kingdom]")
        chk("countries() @* {} ( @sort_desc .name )", "[United Kingdom, USA, Mexico, Germany, China, Austria]")

        chk("countries() @* {} ( .name, @omit @sort .region )", "[USA, Mexico, China, Germany, Austria, United Kingdom]")
        chk("countries() @* {} ( .name, @omit @sort_desc .region )", "[Germany, Austria, United Kingdom, China, USA, Mexico]")
        chk("countries() @* {} ( .name, @omit @sort .language )", "[China, United Kingdom, USA, Germany, Austria, Mexico]")
        chk("countries() @* {} ( .name, @omit @sort_desc .language )", "[Mexico, Germany, Austria, United Kingdom, USA, China]")
        chk("countries() @* {} ( .name, @omit @sort .gdp )", "[Austria, Mexico, United Kingdom, Germany, China, USA]")
        chk("countries() @* {} ( .name, @omit @sort_desc .gdp )", "[USA, China, Germany, United Kingdom, Mexico, Austria]")

        chk("countries() @* {} ( .region )", "[EMEA, EMEA, EMEA, AMER, AMER, APAC]")
        chk("countries() @* {} ( @sort .region )", "[AMER, AMER, APAC, EMEA, EMEA, EMEA]")
        chk("countries() @* {} ( @sort_desc .region )", "[EMEA, EMEA, EMEA, APAC, AMER, AMER]")

        chk("countries() @* {} ( .language )", "[German, German, English, English, Spanish, Chinese]")
        chk("countries() @* {} ( @sort .language )", "[Chinese, English, English, German, German, Spanish]")
        chk("countries() @* {} ( @sort_desc .language )", "[Spanish, German, German, English, English, Chinese]")

        chk("countries() @* {} ( .gdp )", "[3863, 447, 2743, 21439, 1274, 14140]")
        chk("countries() @* {} ( @sort .gdp )", "[447, 1274, 2743, 3863, 14140, 21439]")
        chk("countries() @* {} ( @sort_desc .gdp )", "[21439, 14140, 3863, 2743, 1274, 447]")

        chk("countries() @* {} ( .name, @omit @sort .region, @omit @sort .gdp )", "[Mexico, USA, China, Austria, United Kingdom, Germany]")
        chk("countries() @* {} ( .name, @omit @sort .region, @omit @sort_desc .gdp )", "[USA, Mexico, China, Germany, United Kingdom, Austria]")
        chk("countries() @* {} ( .name, @omit @sort .language, @omit @sort .gdp )", "[China, United Kingdom, USA, Austria, Germany, Mexico]")
        chk("countries() @* {} ( .name, @omit @sort .language, @omit @sort_desc .gdp )", "[China, USA, United Kingdom, Germany, Austria, Mexico]")

        chk("countries() @* {} ( @sort _=.region, _=.name )",
                "[(AMER,USA), (AMER,Mexico), (APAC,China), (EMEA,Germany), (EMEA,Austria), (EMEA,United Kingdom)]")
        chk("countries() @* {} ( @sort _=.region, @sort _=.name )",
                "[(AMER,Mexico), (AMER,USA), (APAC,China), (EMEA,Austria), (EMEA,Germany), (EMEA,United Kingdom)]")
        chk("countries() @* {} ( @sort _=.region, @sort_desc _=.name )",
                "[(AMER,USA), (AMER,Mexico), (APAC,China), (EMEA,United Kingdom), (EMEA,Germany), (EMEA,Austria)]")

        chk("countries() @* {} ( @sort _=.language, _=.name )",
                "[(Chinese,China), (English,United Kingdom), (English,USA), (German,Germany), (German,Austria), (Spanish,Mexico)]")
        chk("countries() @* {} ( @sort _=.language, @sort _=.name )",
                "[(Chinese,China), (English,USA), (English,United Kingdom), (German,Austria), (German,Germany), (Spanish,Mexico)]")
        chk("countries() @* {} ( @sort _=.language, @sort_desc _=.name )",
                "[(Chinese,China), (English,United Kingdom), (English,USA), (German,Germany), (German,Austria), (Spanish,Mexico)]")
    }

    @Test fun testWhatSortType() {
        tstCtx.useSql = true
        initDataCountries()
        def("enum color { red, green, blue }")
        def("entity user { name; }")
        insert("c0.user", "name", "101,'Bob'", "102,'Alice'", "103,'Trudy'")

        chkWhatSortTypeOK("[false,true,false]", "false", "false", "true")
        chkWhatSortTypeOK("[111,222,333]", "111", "222", "333")
        chkWhatSortTypeOK("[12.3,45.6,78.9]", "12.3", "45.6", "78.9")
        chkWhatSortTypeOK("['A','B','C']", "A", "B", "C")
        chkWhatSortTypeOK("[x'1234',x'5678',x'abcd']", "0x1234", "0x5678", "0xabcd")
        chkWhatSortTypeOK("[color.red,color.green,color.blue]", "red", "green", "blue")
        chkWhatSortTypeOK("((v:[123,456,789])@*{}(_int_to_rowid(v)))", "123", "456", "789")
        chkWhatSortTypeOK("(user @* {})", "user[101]", "user[102]", "user[103]")
        chkWhatSortTypeOK("[(123,'Bob'),(456,'Alice'),(789,'Trudy')]", "(123,Bob)", "(456,Alice)", "(789,Trudy)")
        chkWhatSortTypeOK("[[123],[456],[789]]", "[123]", "[456]", "[789]")
        chkWhatSortTypeOK("[range(123),range(456),range(789)]", "range(0,123,1)", "range(0,456,1)", "range(0,789,1)")

        chkWhatSortTypeErr("countries()", "ct_err:at:expr:sort:type:country")
        chkWhatSortTypeErr("[set([123])]", "ct_err:at:expr:sort:type:set<integer>")
        chkWhatSortTypeErr("[[123:'Hello']]", "ct_err:at:expr:sort:type:map<integer,text>")
    }

    private fun chkWhatSortTypeOK(values: String, vararg sorted: String) {
        val expAsc = sorted.joinToString(", ")
        val expDesc = sorted.reversed().joinToString(", ")
        chk("[0,1,2] @* {} ( @sort $values[$] )", "[$expAsc]")
        chk("[0,1,2] @* {} ( @sort_desc $values[$] )", "[$expDesc]")
    }

    private fun chkWhatSortTypeErr(values: String, exp: String) {
        chk("[0,1,2] @* {} ( @sort $values[$] )", exp)
        chk("[0,1,2] @* {} ( @sort_desc $values[$] )", exp)
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

    @Test fun testNestedAttributes() {
        tst.strictToString = false
        def("struct user { name; pos: text; }")
        def("struct company { name; city: text; }")
        def("function users() = [user(name = 'Bob', pos = 'Dev'), user(name = 'Alice', pos = 'QA')];")
        def("function companies() = [company(name = 'Apple', city = 'Cupertino'), company(name = 'Amazon', city = 'Seattle')];")

        chk("users() @* {} ( companies() @* {} ( .name ) )", "[[Apple, Amazon], [Apple, Amazon]]")
        chk("companies() @* {} ( users() @* {} ( .name ) )", "[[Bob, Alice], [Bob, Alice]]")
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
        chk("user @*{} @+{} ( .name, .score )", "[(name=Bob,score=123), (name=Alice,score=456)]")
        chk("user @*{} @+{} ( .name, .score ) @* { .score >= 300 }", "[(name=Alice,score=456)]")
        chk("user @*{} @+{} ( .name, .score ) @* { .score >= 300 } @?{} ( .name )", "Alice")
    }

    @Test fun testEvaluationOmit() {
        tst.strictToString = false
        def("struct ctr { mutable v: integer = 0; }")
        def("function f(c: ctr): integer = c.v++;")
        chkEx("{ val c = ctr(); val t = [5,6,7] @*{} ( $, f(c) ); return (t,c.v); }", "([(5,0), (6,1), (7,2)],3)")
        chkEx("{ val c = ctr(); val t = [5,6,7] @*{} ( $, @omit f(c) ); return (t,c.v); }", "([5, 6, 7],3)")
        chkEx("{ val c = ctr(); val t = [5,6,7] @*{} ( f(c), 100 + f(c) ); return (t,c.v); }", "([(0,101), (2,103), (4,105)],6)")
        chkEx("{ val c = ctr(); val t = [5,6,7] @*{} ( f(c), @omit 100 + f(c) ); return (t,c.v); }", "([0, 2, 4],6)")
        chkEx("{ val c = ctr(); val t = [5,6,7] @*{} ( @omit f(c), 100 + f(c) ); return (t,c.v); }", "([101, 103, 105],6)")
    }

    @Test fun testEvaluationLimitOffset() {
        tst.strictToString = false
        def("struct ctr { mutable v: integer = 0; }")
        def("function f(c: ctr): integer { val k = (c.v++)+1; return k*k; }")

        val init = "val c = ctr(); val l = [11,22,33,44,55];"
        chkEx("{ $init; val t = l @* {} ( f(c) ); return (t, c.v); }", "([1, 4, 9, 16, 25],5)")
        chkEx("{ $init; val t = l @* {} ( f(c) ) limit 3; return (t, c.v); }", "([1, 4, 9],3)")
        chkEx("{ $init; val t = l @* {} ( f(c) ) offset 2; return (t, c.v); }", "([1, 4, 9],3)")
        chkEx("{ $init; val t = l @* {} ( f(c) ) offset 2 limit 2; return (t, c.v); }", "([1, 4],2)")

        chkEx("{ $init; val t = l @* {} ( $, f(c) ) limit 3; return (t, c.v); }", "([(11,1), (22,4), (33,9)],3)")
        chkEx("{ $init; val t = l @* {} ( @sort $, f(c) ) limit 3; return (t, c.v); }", "([(11,1), (22,4), (33,9)],5)")
        chkEx("{ $init; val t = l @* {} ( @sort_desc $, f(c) ) limit 3; return (t, c.v); }", "([(55,25), (44,16), (33,9)],5)")
        chkEx("{ $init; val t = l @* {} ( $, f(c) ) offset 2; return (t, c.v); }", "([(33,1), (44,4), (55,9)],3)")
        chkEx("{ $init; val t = l @* {} ( @sort $, f(c) ) offset 2; return (t, c.v); }", "([(33,9), (44,16), (55,25)],5)")
        chkEx("{ $init; val t = l @* {} ( @sort_desc $, f(c) ) offset 2; return (t, c.v); }", "([(33,9), (22,4), (11,1)],5)")
    }

    @Test fun testEvaluationLimitOffsetGroup() {
        tst.strictToString = false
        def("struct ctr { mutable v: integer = 0; }")
        def("function f(c: ctr): integer { val k = (c.v++)+1; return k*k; }")

        val init = "val c = ctr(); val l = [11,22,33,44,55];"
        chkEx("{ $init; val t = l @* {} ( @group 0, @sum f(c) ); return (t, c.v); }", "([(0,55)],5)")
        chkEx("{ $init; val t = l @* {} ( @sum f(c) ); return (t, c.v); }", "([55],5)")
        chkEx("{ $init; val t = l @* {} ( @sum f(c) ) limit 0; return (t, c.v); }", "([],0)")
        chkEx("{ $init; val t = l @* {} ( @sum f(c) ) offset 1; return (t, c.v); }", "([],5)")
        chkEx("{ $init; val t = l @* {} ( @group f(c) ); return (t, c.v); }", "([1, 4, 9, 16, 25],5)")
        chkEx("{ $init; val t = l @* {} ( @group f(c) ) limit 3; return (t, c.v); }", "([1, 4, 9],5)")
        chkEx("{ $init; val t = l @* {} ( @group f(c) ) offset 2; return (t, c.v); }", "([9, 16, 25],5)")
        chkEx("{ $init; val t = l @* {} ( @group f(c) ) offset 2 limit 2; return (t, c.v); }", "([9, 16],5)")
    }

    private fun initDataCountries() {
        tst.strictToString = false

        def("struct country { name; region: text; language: text; gdp: integer; }")
        def("function make_country(name, region: text, language: text, gdp: integer) = country(name, region, language, gdp);")

        def("""
            function countries() = [
                make_country('Germany','EMEA','German',3863),
                make_country('Austria','EMEA','German',447),
                make_country('United Kingdom','EMEA','English',2743),
                make_country('USA','AMER','English',21439),
                make_country('Mexico','AMER','Spanish',1274),
                make_country('China','APAC','Chinese',14140)
            ];
        """)
    }
}
