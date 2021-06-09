package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class AtExprComplexWhatTest: BaseRellTest() {
    private fun initData() {
        def("entity city { name; country: text; }")
        def("entity user { name; score: integer; }")
        def("entity data { i: integer; d: decimal; t: text; ba: byte_array; u: user; c: city; }")

        insert("c0.city", "name,country", "100,'Berlin','Germany'", "101,'Paris','France'", "102,'Madrid','Spain'")
        insert("c0.user", "name,score", "200,'Bob',123", "201,'Alice',456", "202,'Trudy',789")

        insert("c0.data", "i,d,t,ba,c,u",
                "900,111,1.1,'A',E'\\\\xBEEF',100,200",
                "901,222,2.2,'B',E'\\\\xFEED',101,201",
                "902,333,3.3,'C',E'\\\\xCAFE',102,202"
        )
    }

    @Test fun testTuple() {
        initData()

        chkSel("data, ( .i, .t )", "(data,(integer,text))",
                "(data[900],(int[111],text[A]))",
                "(data[901],(int[222],text[B]))",
                "(data[902],(int[333],text[C]))"
        )

        chkSel("data, ( p = .i, q = .t )", "(data,(p:integer,q:text))",
                "(data[900],(p=int[111],q=text[A]))",
                "(data[901],(p=int[222],q=text[B]))",
                "(data[902],(p=int[333],q=text[C]))"
        )

        chkSel("data, ( .i, 'Hello', 777, .t )", "(data,(integer,text,integer,text))",
                "(data[900],(int[111],text[Hello],int[777],text[A]))",
                "(data[901],(int[222],text[Hello],int[777],text[B]))",
                "(data[902],(int[333],text[Hello],int[777],text[C]))"
        )

        chkSel("data, ( 777, 'Hello' )", "(data,(integer,text))",
                "(data[900],(int[777],text[Hello]))",
                "(data[901],(int[777],text[Hello]))",
                "(data[902],(int[777],text[Hello]))"
        )
    }

    @Test fun testListLiteral() {
        initData()

        chkSel("data, [ .t, .u.name, .c.name ]", "(data,list<text>)",
                "(data[900],list<text>[text[A],text[Bob],text[Berlin]])",
                "(data[901],list<text>[text[B],text[Alice],text[Paris]])",
                "(data[902],list<text>[text[C],text[Trudy],text[Madrid]])"
        )

        chkSel("data, [ .t, 'Hello', .c.name ]", "(data,list<text>)",
                "(data[900],list<text>[text[A],text[Hello],text[Berlin]])",
                "(data[901],list<text>[text[B],text[Hello],text[Paris]])",
                "(data[902],list<text>[text[C],text[Hello],text[Madrid]])"
        )

        chkSel("data, [ 'Hello', 'World' ]", "(data,list<text>)",
                "(data[900],list<text>[text[Hello],text[World]])",
                "(data[901],list<text>[text[Hello],text[World]])",
                "(data[902],list<text>[text[Hello],text[World]])"
        )
    }

    @Test fun testMapLiteral() {
        initData()

        chkSel("data, [ .t : .i, .u.name : .u.score ]", "(data,map<text,integer>)",
                "(data[900],map<text,integer>[text[A]=int[111],text[Bob]=int[123]])",
                "(data[901],map<text,integer>[text[B]=int[222],text[Alice]=int[456]])",
                "(data[902],map<text,integer>[text[C]=int[333],text[Trudy]=int[789]])"
        )

        chkSel("data, [ .t : .i, .u.name : 777 ]", "(data,map<text,integer>)",
                "(data[900],map<text,integer>[text[A]=int[111],text[Bob]=int[777]])",
                "(data[901],map<text,integer>[text[B]=int[222],text[Alice]=int[777]])",
                "(data[902],map<text,integer>[text[C]=int[333],text[Trudy]=int[777]])"
        )
    }

    @Test fun testStruct() {
        def("struct rec { i: integer; t: text; u: text; c: text; }")
        def("struct rec2 { i: integer = -1; t: text = '?'; }")
        initData()

        chkSel("data, rec(i = .i, t = .t, u = .u.name, c = .c.name)", "(data,rec)",
                "(data[900],rec[i=int[111],t=text[A],u=text[Bob],c=text[Berlin]])",
                "(data[901],rec[i=int[222],t=text[B],u=text[Alice],c=text[Paris]])",
                "(data[902],rec[i=int[333],t=text[C],u=text[Trudy],c=text[Madrid]])"
        )

        chkSel("data, rec(i = 777, t = .t, u = .u.name, c = 'Hello')", "(data,rec)",
                "(data[900],rec[i=int[777],t=text[A],u=text[Bob],c=text[Hello]])",
                "(data[901],rec[i=int[777],t=text[B],u=text[Alice],c=text[Hello]])",
                "(data[902],rec[i=int[777],t=text[C],u=text[Trudy],c=text[Hello]])"
        )

        chkSel("data, rec(i = 777, t = 'Hello', u = 'U', c = 'C')", "(data,rec)",
                "(data[900],rec[i=int[777],t=text[Hello],u=text[U],c=text[C]])",
                "(data[901],rec[i=int[777],t=text[Hello],u=text[U],c=text[C]])",
                "(data[902],rec[i=int[777],t=text[Hello],u=text[U],c=text[C]])"
        )

        chkSel("data, rec2(i = .i)", "(data,rec2)",
                "(data[900],rec2[i=int[111],t=text[?]])",
                "(data[901],rec2[i=int[222],t=text[?]])",
                "(data[902],rec2[i=int[333],t=text[?]])"
        )

        chkSel("data, rec2()", "(data,rec2)",
                "(data[900],rec2[i=int[-1],t=text[?]])",
                "(data[901],rec2[i=int[-1],t=text[?]])",
                "(data[902],rec2[i=int[-1],t=text[?]])"
        )
    }

    @Test fun testFunction() {
        def("""function f(i: integer, t: text, u: user, c: city): text {
            print(i);
            return i + ':' + t + ':' + u.name + ':' + c.name;
        }""")
        initData()

        chkSel("data, f(.i, .t, .u, .c)", "(data,text)",
                "(data[900],text[111:A:Bob:Berlin])",
                "(data[901],text[222:B:Alice:Paris])",
                "(data[902],text[333:C:Trudy:Madrid])"
        )
        chkOut("111", "222", "333")

        chkSel("data, f(777, 'Hello', .u, .c)", "(data,text)",
                "(data[900],text[777:Hello:Bob:Berlin])",
                "(data[901],text[777:Hello:Alice:Paris])",
                "(data[902],text[777:Hello:Trudy:Madrid])"
        )
        chkOut("777", "777", "777")

        chkSel("data, f(777, 'Hello', user@{'Bob'}, city@{.name=='Berlin'})", "(data,text)",
                "(data[900],text[777:Hello:Bob:Berlin])",
                "(data[901],text[777:Hello:Bob:Berlin])",
                "(data[902],text[777:Hello:Bob:Berlin])"
        )
        chkOut("777")
    }

    @Test fun testSetFromList() {
        initData()
        chkSel("data, set([ .t, .u.name, .c.name ])", "(data,set<text>)",
                "(data[900],set<text>[text[A],text[Bob],text[Berlin]])",
                "(data[901],set<text>[text[B],text[Alice],text[Paris]])",
                "(data[902],set<text>[text[C],text[Trudy],text[Madrid]])"
        )
    }

    @Test fun testNestedToStruct() {
        initData()
        chkSel("data, ( .u.to_struct(), .c.to_struct() )", "(data,(struct<user>,struct<city>))",
                "(data[900],(struct<user>[name=text[Bob],score=int[123]],struct<city>[name=text[Berlin],country=text[Germany]]))",
                "(data[901],(struct<user>[name=text[Alice],score=int[456]],struct<city>[name=text[Paris],country=text[France]]))",
                "(data[902],(struct<user>[name=text[Trudy],score=int[789]],struct<city>[name=text[Madrid],country=text[Spain]]))"
        )
    }

    @Test fun testCombinationListTupleFunction() {
        def("function f(i: integer, t: text): text = i + ':' + t;")
        def("function g(i: integer): integer = i * i;")
        initData()
        chkSel("data, [(.i, f(.u.score, .u.name)), (g(.i), .c.name)]", "(data,list<(integer,text)>)",
                "(data[900],list<(integer,text)>[(int[111],text[123:Bob]),(int[12321],text[Berlin])])",
                "(data[901],list<(integer,text)>[(int[222],text[456:Alice]),(int[49284],text[Paris])])",
                "(data[902],list<(integer,text)>[(int[333],text[789:Trudy]),(int[110889],text[Madrid])])"
        )
    }

    @Test fun testCombinationListFunctionTuple() {
        def("function f(i: integer, t: text): text = i + ':' + t;")
        def("function h(z: (integer, text)): text = f(z[0], z[1]);")
        initData()
        chkSel("data, [h((.i, .t)), h((.u.score, .u.name))]", "(data,list<text>)",
                "(data[900],list<text>[text[111:A],text[123:Bob]])",
                "(data[901],list<text>[text[222:B],text[456:Alice]])",
                "(data[902],list<text>[text[333:C],text[789:Trudy]])"
        )
    }

    @Test fun testCombinationTupleListFunction() {
        def("function g(i: integer): integer = i * i;")
        initData()
        chkSel("data, (.t, [g(.i), g(.u.score)])", "(data,(text,list<integer>))",
                "(data[900],(text[A],list<integer>[int[12321],int[15129]]))",
                "(data[901],(text[B],list<integer>[int[49284],int[207936]]))",
                "(data[902],(text[C],list<integer>[int[110889],int[622521]]))"
        )
    }

    @Test fun testCombinationTupleFunctionList() {
        def("function si(l: list<integer>): integer { var s = 0; for (v in l) s += v; return s; }")
        def("function st(l: list<text>): text { var s = ''; for (v in l) s += v; return s; }")
        initData()
        chkSel("data, (si([.i, .u.score]), st([.t, .u.name]))", "(data,(integer,text))",
                "(data[900],(int[234],text[ABob]))",
                "(data[901],(int[678],text[BAlice]))",
                "(data[902],(int[1122],text[CTrudy]))"
        )
    }

    @Test fun testCombinationFunctionTupleList() {
        def("function si(l: list<integer>): integer { var s = 0; for (v in l) s += v; return s; }")
        def("function st(l: list<text>): text { var s = ''; for (v in l) s += v; return s; }")
        def("function tl(t: (list<integer>, list<text>)): text = si(t[0]) + st(t[1]);")
        initData()
        chkSel("data, tl(([.i, .u.score], [.t, .u.name]))", "(data,text)",
                "(data[900],text[234ABob])",
                "(data[901],text[678BAlice])",
                "(data[902],text[1122CTrudy])"
        )
    }

    @Test fun testCombinationFunctionListTuple() {
        def("function f(l: list<(integer,text)>): list<text> = l @* {} ( '' + $ );")
        initData()
        chkSel("data, f([(.i, .t), (.u.score, .u.name)])", "(data,list<text>)",
                "(data[900],list<text>[text[(111,A)],text[(123,Bob)]])",
                "(data[901],list<text>[text[(222,B)],text[(456,Alice)]])",
                "(data[902],list<text>[text[(333,C)],text[(789,Trudy)]])"
        )
    }

    @Test fun testCombinationStruct() {
        def("function f(i: integer, t: text): text = i + ':' + t;")
        def("struct rec { a: text; t: (integer, text); l: list<text>; }")
        initData()
        chkSel("data, rec(a = f(.i, .t), t = (.u.score, .u.name), l = [.c.name])", "(data,rec)",
                "(data[900],rec[a=text[111:A],t=(int[123],text[Bob]),l=list<text>[text[Berlin]]])",
                "(data[901],rec[a=text[222:B],t=(int[456],text[Alice]),l=list<text>[text[Paris]]])",
                "(data[902],rec[a=text[333:C],t=(int[789],text[Trudy]),l=list<text>[text[Madrid]]])"
        )
    }

    @Test fun testWherePart() {
        def("function f(x: integer): boolean = x > 0;")
        def("function g(x: integer): integer = x * x;")
        initData()

        chk("data @* { [.t].empty() }", "ct_err:expr_call_nosql:list<text>.empty")
        chk("data @* { [123].empty() }", "list<data>[]")
        chk("data @* { f(.i) }", "ct_err:expr_sqlnotallowed")
        chk("data @* { g(.i) > 0 }", "ct_err:expr_sqlnotallowed")
        chk("data @* { f(123) }", "list<data>[data[900],data[901],data[902]]")
    }

    @Test fun testModifiers() {
        def("function f(i: integer, t: text) = (i, t);")
        def("struct rec { i: integer; t: text; }")
        initData()

        chkModifiers("(.i, .t)", "(integer,text)", "ct_err:expr:at:aggregate")
        chkModifiers("[.i, .u.score]", "list<integer>", "ct_err:expr:at:aggregate")
        chkModifiers("f(.i, .t)", "(integer,text)", "ct_err:expr:at:aggregate")
        chkModifiers("rec(.i, .t)", "rec", "ct_err:[expr:at:aggregate][at:what:aggr:bad_type:MINMAX:rec]")
    }

    private fun chkModifiers(expr: String, type: String, minMaxErr: String) {
        chk("data @* {} ( @group $expr )", "ct_err:expr:at:group")
        chk("data @* {} ( @min $expr )", minMaxErr.replace("MINMAX", "MIN"))
        chk("data @* {} ( @max $expr )", minMaxErr.replace("MINMAX", "MAX"))
        chk("data @* {} ( @sum $expr )", "ct_err:[expr:at:aggregate][at:what:aggr:bad_type:SUM:$type]")
        chk("data @* {} ( @sort $expr )", "ct_err:expr:at:sort")
        chk("data @* {} ( data, @omit $expr )", "list<data>[data[900],data[901],data[902]]")
    }

    @Test fun testNoSqlExpr() {
        initData()
        chkSel("_=.i, range(123)", "(integer,range)",
                "(int[111],range[0,123,1])",
                "(int[222],range[0,123,1])",
                "(int[333],range[0,123,1])"
        )
    }

    private fun chkSel(what: String, type: String, vararg values: String) {
        val values2 = values.joinToString(",")
        val exp = "list<$type>[$values2]"
        chk("data @* {} ( $what )", exp)
    }
}
