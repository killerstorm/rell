/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LibGtvTest: BaseRellTest(false) {
    @Test fun testToFromGtvBoolean() {
        chk("false.to_gtv()", "gtv[0]")
        chk("true.to_gtv()", "gtv[1]")
        chk("false.to_gtv_pretty()", "gtv[0]")

        chkFromGtv("0", "boolean.from_gtv(g)", "boolean[false]")
        chkFromGtv("1", "boolean.from_gtv(g)", "boolean[true]")
        chkFromGtv("-1", "boolean.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("2", "boolean.from_gtv(g)", "rt_err:from_gtv")

        chkFromGtv("0", "boolean.from_gtv_pretty(g)", "boolean[false]")
        chkFromGtv("1", "boolean.from_gtv_pretty(g)", "boolean[true]")
        chkFromGtv("-1", "boolean.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
        chkFromGtv("2", "boolean.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
    }

    @Test fun testToFromGtvInteger() {
        chk("(0).to_gtv()", "gtv[0]")
        chk("(123).to_gtv()", "gtv[123]")
        chk("(-456).to_gtv()", "gtv[-456]")
        chk("(0).to_gtv_pretty()", "gtv[0]")

        chkFromGtv("0", "integer.from_gtv(g)", "int[0]")
        chkFromGtv("123", "integer.from_gtv(g)", "int[123]")
        chkFromGtv("-456", "integer.from_gtv(g)", "int[-456]")
        chkFromGtv("123", "integer.from_gtv_pretty(g)", "int[123]")
        chkFromGtv("-456", "integer.from_gtv_pretty(g)", "int[-456]")

        chkFromGtv("'Hello'", "integer.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("'Hello'", "integer.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
        chkFromGtv("[]", "integer.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("[]", "integer.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
        chkFromGtv("[123]", "integer.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("[123]", "integer.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
    }

    @Test fun testToFromGtvDecimal() {
        chk("(0.0).to_gtv()", """gtv["0"]""")
        chk("(123.456).to_gtv()", """gtv["123.456"]""")
        chk("(-456.789).to_gtv()", """gtv["-456.789"]""")
        chk("(0.0).to_gtv_pretty()", """gtv["0"]""")
        chk("(123.456e+53).to_gtv_pretty()", """gtv["12345600000000000000000000000000000000000000000000000000"]""")
        chk("(123.456e-17).to_gtv_pretty()", """gtv["0.00000000000000123456"]""")

        chkFromGtv("'0'", "decimal.from_gtv(g)", "dec[0]")
        chkFromGtv("'0.0000'", "decimal.from_gtv(g)", "dec[0]")
        chkFromGtv("'123.456'", "decimal.from_gtv(g)", "dec[123.456]")
        chkFromGtv("'-456.789'", "decimal.from_gtv(g)", "dec[-456.789]")
        chkFromGtv("'123.456'", "decimal.from_gtv_pretty(g)", "dec[123.456]")
        chkFromGtv("'-456.789'", "decimal.from_gtv_pretty(g)", "dec[-456.789]")

        chkFromGtv("'123.456E10'", "decimal.from_gtv(g)", "dec[1234560000000]")
        chkFromGtv("'123.456E+10'", "decimal.from_gtv(g)", "dec[1234560000000]")
        chkFromGtv("'123.456E-10'", "decimal.from_gtv(g)", "dec[0.0000000123456]")
        chkFromGtv("'123.456e10'", "decimal.from_gtv(g)", "dec[1234560000000]")
        chkFromGtv("'123.456e+10'", "decimal.from_gtv(g)", "dec[1234560000000]")
        chkFromGtv("'123.456e-10'", "decimal.from_gtv(g)", "dec[0.0000000123456]")
        chkFromGtv("'-123.456E10'", "decimal.from_gtv(g)", "dec[-1234560000000]")
        chkFromGtv("'-123.456E+10'", "decimal.from_gtv(g)", "dec[-1234560000000]")
        chkFromGtv("'-123.456E-10'", "decimal.from_gtv(g)", "dec[-0.0000000123456]")
        chkFromGtv("'123E10'", "decimal.from_gtv(g)", "dec[1230000000000]")
        chkFromGtv("'123E+10'", "decimal.from_gtv(g)", "dec[1230000000000]")
        chkFromGtv("'123E-10'", "decimal.from_gtv(g)", "dec[0.0000000123]")
        chkFromGtv("'-123E10'", "decimal.from_gtv(g)", "dec[-1230000000000]")
        chkFromGtv("'-123E+10'", "decimal.from_gtv(g)", "dec[-1230000000000]")
        chkFromGtv("'-123E-10'", "decimal.from_gtv(g)", "dec[-0.0000000123]")

        chkFromGtv("0", "decimal.from_gtv(g)", "dec[0]")
        chkFromGtv("123", "decimal.from_gtv(g)", "dec[123]")
        chkFromGtv("-456", "decimal.from_gtv(g)", "dec[-456]")

        chkFromGtv("'Hello'", "decimal.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("'Hello'", "decimal.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
        chkFromGtv("[]", "decimal.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("[]", "decimal.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
        chkFromGtv("[123]", "decimal.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("[123]", "decimal.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
    }

    @Test fun testToFromGtvText() {
        chk("''.to_gtv()", """gtv[""]""")
        chk("'Hello'.to_gtv()", """gtv["Hello"]""")
        chk("''.to_gtv_pretty()", """gtv[""]""")
        chk("'Hello'.to_gtv_pretty()", """gtv["Hello"]""")

        chkFromGtv("''", "text.from_gtv(g)", "text[]")
        chkFromGtv("'Hello'", "text.from_gtv(g)", "text[Hello]")
        chkFromGtv("''", "text.from_gtv_pretty(g)", "text[]")
        chkFromGtv("'Hello'", "text.from_gtv_pretty(g)", "text[Hello]")

        chkFromGtv("123", "text.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("123", "text.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
    }

    @Test fun testToFromGtvByteArray() {
        chk("x''.to_gtv()", """gtv[""]""")
        chk("x'0123abcd'.to_gtv()", """gtv["0123ABCD"]""")
        chk("x''.to_gtv_pretty()", """gtv[""]""")
        chk("x'0123abcd'.to_gtv_pretty()", """gtv["0123ABCD"]""")

        chkFromGtv("''", "byte_array.from_gtv(g)", "byte_array[]")
        chkFromGtv("'0123abcd'", "byte_array.from_gtv(g)", "byte_array[0123abcd]")
        chkFromGtv("'0123ABCD'", "byte_array.from_gtv(g)", "byte_array[0123abcd]")
        chkFromGtv("''", "byte_array.from_gtv_pretty(g)", "byte_array[]")
        chkFromGtv("'0123abcd'", "byte_array.from_gtv_pretty(g)", "byte_array[0123abcd]")

        chkFromGtv("'hello'", "byte_array.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("123", "byte_array.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("123", "byte_array.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
    }

    @Test fun testToFromGtvRowid() {
        tstCtx.useSql = true
        def("entity user { name; }")
        insert("c0.user", "name", "0,'Bob'")
        insert("c0.user", "name", "123,'Alice'")

        chk("(user@{'Bob'}).to_gtv()", "gtv[0]")
        chk("(user@{'Alice'}).to_gtv()", "gtv[123]")
        chk("(user@{'Bob'}).to_gtv_pretty()", "gtv[0]")

        chkFromGtv("0", "rowid.from_gtv(g)", "rowid[0]")
        chkFromGtv("123", "rowid.from_gtv(g)", "rowid[123]")
        chkFromGtv("-456", "rowid.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("123", "rowid.from_gtv_pretty(g)", "rowid[123]")
        chkFromGtv("-456", "rowid.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")

        chkFromGtv("'Hello'", "rowid.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("'Hello'", "rowid.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
        chkFromGtv("[]", "rowid.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("[]", "rowid.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
        chkFromGtv("[123]", "rowid.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("[123]", "rowid.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
    }

    @Test fun testToFromGtvJson() {
        chk("json('123').to_gtv()", """gtv["123"]""")
        chk("json('{}').to_gtv()", """gtv["{}"]""")
        chk("json('[]').to_gtv()", """gtv["[]"]""")
        chk("json('[123]').to_gtv()", """gtv["[123]"]""")
        chk("json('[123]').to_gtv_pretty()", """gtv["[123]"]""")

        chkFromGtv("'123'", "json.from_gtv(g)", "json[123]")
        chkFromGtv("'{}'", "json.from_gtv(g)", "json[{}]")
        chkFromGtv("'[]'", "json.from_gtv(g)", "json[[]]")
        chkFromGtv("'[123]'", "json.from_gtv(g)", "json[[123]]")
        chkFromGtv("'123'", "json.from_gtv_pretty(g)", "json[123]")
        chkFromGtv("'{}'", "json.from_gtv_pretty(g)", "json[{}]")
        chkFromGtv("'[]'", "json.from_gtv_pretty(g)", "json[[]]")
        chkFromGtv("'[123]'", "json.from_gtv_pretty(g)", "json[[123]]")

        chkFromGtv("{}", "json.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("[]", "json.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("[123]", "json.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("'Hello'", "json.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("{}", "json.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
        chkFromGtv("[]", "json.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
    }

    @Test fun testToFromGtvEnum() {
        def("enum E {A,B,C}")

        chk("E.A.to_gtv()", "gtv[0]")
        chk("E.B.to_gtv()", "gtv[1]")
        chk("E.C.to_gtv()", "gtv[2]")

        chk("E.A.to_gtv_pretty()", """gtv["A"]""")
        chk("E.B.to_gtv_pretty()", """gtv["B"]""")
        chk("E.C.to_gtv_pretty()", """gtv["C"]""")

        chkFromGtv("0", "E.from_gtv(g)", "E[A]")
        chkFromGtv("1", "E.from_gtv(g)", "E[B]")
        chkFromGtv("2", "E.from_gtv(g)", "E[C]")
        chkFromGtv("-1", "E.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("3", "E.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("'A'", "E.from_gtv(g)", "rt_err:from_gtv")

        chkFromGtv("'A'", "E.from_gtv_pretty(g)", "E[A]")
        chkFromGtv("'B'", "E.from_gtv_pretty(g)", "E[B]")
        chkFromGtv("'C'", "E.from_gtv_pretty(g)", "E[C]")
        chkFromGtv("'D'", "E.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
        chkFromGtv("0", "E.from_gtv_pretty(g)", "E[A]")
    }

    @Test fun testToFromGtvOther() {
        chk("gtv.from_json('{}').to_gtv()", "gtv[{}]")
        chk("gtv.from_json('[]').to_gtv()", "gtv[[]]")
        chk("gtv.from_json('[123]').to_gtv()", "gtv[[123]]")
        chk("gtv.from_json('[123]').to_gtv_pretty()", "gtv[[123]]")

        chk("range(10).to_gtv()", "ct_err:unknown_member:[range]:to_gtv")
        chk("range(10).to_gtv_pretty()", "ct_err:unknown_member:[range]:to_gtv_pretty")
        chkFromGtv("''", "range.from_gtv(g)", "ct_err:fn:invalid:range:from_gtv")
        chkFromGtv("''", "range.from_gtv_pretty(g)", "ct_err:fn:invalid:range:from_gtv_pretty")
    }

    @Test fun testToFromGtvList() {
        chk("list<integer>().to_gtv()", "gtv[[]]")
        chk("[123].to_gtv()", "gtv[[123]]")
        chk("[123, 456].to_gtv()", "gtv[[123,456]]")
        chk("[123].to_gtv_pretty()", "gtv[[123]]")

        chkFromGtv("[]", "list<integer>.from_gtv(g)", "list<integer>[]")
        chkFromGtv("[123]", "list<integer>.from_gtv(g)", "list<integer>[int[123]]")
        chkFromGtv("['Hello']", "list<integer>.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("123", "list<integer>.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("[]", "list<range>.from_gtv(g)", "ct_err:fn:invalid:list<range>:from_gtv")

        chkFromGtv("[]", "list<integer>.from_gtv_pretty(g)", "list<integer>[]")
        chkFromGtv("[123]", "list<integer>.from_gtv_pretty(g)", "list<integer>[int[123]]")
        chkFromGtv("['Hello']", "list<integer>.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
        chkFromGtv("123", "list<integer>.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
        chkFromGtv("[]", "list<range>.from_gtv_pretty(g)", "ct_err:fn:invalid:list<range>:from_gtv_pretty")
    }

    @Test fun testToFromGtvSet() {
        chk("set<integer>().to_gtv()", "gtv[[]]")
        chk("set([123]).to_gtv()", "gtv[[123]]")
        chk("set([123, 456]).to_gtv()", "gtv[[123,456]]")
        chk("set([123]).to_gtv_pretty()", "gtv[[123]]")

        chkFromGtv("[]", "set<integer>.from_gtv(g)", "set<integer>[]")
        chkFromGtv("[123]", "set<integer>.from_gtv(g)", "set<integer>[int[123]]")
        chkFromGtv("[123,'Hello']", "set<integer>.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("['Hello']", "set<integer>.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("123", "set<integer>.from_gtv(g)", "rt_err:from_gtv")

        chkFromGtv("[]", "set<integer>.from_gtv_pretty(g)", "set<integer>[]")
        chkFromGtv("[123,456]", "set<integer>.from_gtv_pretty(g)", "set<integer>[int[123],int[456]]")
        chkFromGtv("['Hello']", "set<integer>.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
        chkFromGtv("123", "set<integer>.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")
    }

    @Test fun testToFromGtvMap() {
        chk("map<text,integer>().to_gtv()", "gtv[{}]")
        chk("['Hello':123].to_gtv()", """gtv[{"Hello":123}]""")
        chk("['Hello':123,'Bye':456].to_gtv()", """gtv[{"Bye":456,"Hello":123}]""")
        chk("['Hello':123].to_gtv_pretty()", """gtv[{"Hello":123}]""")

        chk("map<integer,text>().to_gtv()", "gtv[[]]")
        chk("[123:'Hello'].to_gtv()", """gtv[[[123,"Hello"]]]""")
        chk("[123:'Hello',456:'Bye'].to_gtv()", """gtv[[[123,"Hello"],[456,"Bye"]]]""")
        chk("[123:'Hello',456:'Bye'].to_gtv_pretty()", """gtv[[[123,"Hello"],[456,"Bye"]]]""")

        chkFromGtv("{}", "map<text,integer>.from_gtv(g)", "map<text,integer>[]")
        chkFromGtv("[]", "map<text,integer>.from_gtv(g)", "map<text,integer>[]")
        chkFromGtv("{'Hello':123}", "map<text,integer>.from_gtv(g)", "map<text,integer>[text[Hello]=int[123]]")
        chkFromGtv("[['Hello',123]]", "map<text,integer>.from_gtv(g)", "map<text,integer>[text[Hello]=int[123]]")
        chkFromGtv("123", "map<text,integer>.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("'Hello'", "map<text,integer>.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("{}", "map<text,integer>.from_gtv_pretty(g)", "map<text,integer>[]")
        chkFromGtv("[]", "map<text,integer>.from_gtv_pretty(g)", "map<text,integer>[]")
        chkFromGtv("{'Hello':123}", "map<text,integer>.from_gtv_pretty(g)", "map<text,integer>[text[Hello]=int[123]]")

        chkFromGtv("[]", "map<integer,text>.from_gtv(g)", "map<integer,text>[]")
        chkFromGtv("[[123,'Hello']]", "map<integer,text>.from_gtv(g)", "map<integer,text>[int[123]=text[Hello]]")
        chkFromGtv("[[123,'Hello'],[456,'Bye']]", "map<integer,text>.from_gtv(g)",
                "map<integer,text>[int[123]=text[Hello],int[456]=text[Bye]]")
        chkFromGtv("{}", "map<integer,text>.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("[123]", "map<integer,text>.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("[[]]", "map<integer,text>.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("[[123]]", "map<integer,text>.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("[['Hello',123]]", "map<integer,text>.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("[[123,'Hello','Bye']]", "map<integer,text>.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("[[123,'Hello'],[123,'Bye']]", "map<integer,text>.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("[['Hello',123],['Bye',456]]", "map<text,integer>.from_gtv(g)",
                "map<text,integer>[text[Hello]=int[123],text[Bye]=int[456]]")
        chkFromGtv("[['Hello',123],['Bye',456]]", "map<text,integer>.from_gtv_pretty(g)",
                "map<text,integer>[text[Hello]=int[123],text[Bye]=int[456]]")
        chkFromGtv("{'Hello':123,'Bye':456}", "map<text,integer>.from_gtv(g)",
                "map<text,integer>[text[Bye]=int[456],text[Hello]=int[123]]")
        chkFromGtv("{'Hello':123,'Bye':456}", "map<text,integer>.from_gtv_pretty(g)",
                "map<text,integer>[text[Bye]=int[456],text[Hello]=int[123]]")

        chkFromGtv("[]", "map<integer,text>.from_gtv_pretty(g)", "map<integer,text>[]")
        chkFromGtv("[[123,'Hello']]", "map<integer,text>.from_gtv_pretty(g)", "map<integer,text>[int[123]=text[Hello]]")
    }

    @Test fun testToGtvTuple() {
        def("struct A { t: (x: integer, y: text); }")
        def("struct B { t: (x: integer, text); }")
        def("struct C { t: (s: (x: boolean, y: text), k: integer); }")

        chk("(123,).to_gtv()", "gtv[[123]]")
        chk("(123,'Hello').to_gtv()", """gtv[[123,"Hello"]]""")
        chk("(x=123,y='Hello').to_gtv()", """gtv[[123,"Hello"]]""")
        chk("(x=123,'Hello').to_gtv()", """gtv[[123,"Hello"]]""")
        chk("(123,y='Hello').to_gtv()", """gtv[[123,"Hello"]]""")

        chk("(123,'Hello').to_gtv_pretty()", """gtv[[123,"Hello"]]""")
        chk("(x=123,y='Hello').to_gtv_pretty()", """gtv[{"x":123,"y":"Hello"}]""")
        chk("(x=123,'Hello').to_gtv_pretty()", """gtv[[123,"Hello"]]""")
        chk("(123,y='Hello').to_gtv_pretty()", """gtv[[123,"Hello"]]""")

        chkFromGtv("[[123,'Hello']]", "A.from_gtv(g)", "A[t=(x=int[123],y=text[Hello])]")
        chkFromGtv("[{'x':123,'y':'Hello'}]", "A.from_gtv(g)", "gtv_err:type:array:DICT")
        chkFromGtv("{'t':[123,'Hello']}", "A.from_gtv(g)", "gtv_err:type:array:DICT")
        chkFromGtv("[[[1,'A'],123]]", "C.from_gtv(g)", "C[t=(s=(x=boolean[true],y=text[A]),k=int[123])]")
        chkFromGtv("{'t':[[1,'A'],123]}", "C.from_gtv(g)", "gtv_err:type:array:DICT")
        chkFromGtv("{'t':{'s':[1,'A'],'k':123}}", "C.from_gtv(g)", "gtv_err:type:array:DICT")
        chkFromGtv("{'t':{'s':{'x':1,'y':'A'},'k':123}}", "C.from_gtv(g)", "gtv_err:type:array:DICT")
        chkFromGtv("[{'s':[1,'A'],'k':123}]", "C.from_gtv(g)", "gtv_err:type:array:DICT")
        chkFromGtv("[{'s':{'x':1,'y':'A'},'k':123}]", "C.from_gtv(g)", "gtv_err:type:array:DICT")
        chkFromGtv("[[{'x':1,'y':'A'},123]]", "C.from_gtv(g)", "gtv_err:type:array:DICT")

        chkFromGtv("[[123,'Hello']]", "A.from_gtv_pretty(g)", "A[t=(x=int[123],y=text[Hello])]")
        chkFromGtv("[{'x':123,'y':'Hello'}]", "A.from_gtv_pretty(g)", "A[t=(x=int[123],y=text[Hello])]")
        chkFromGtv("{'t':[123,'Hello']}", "A.from_gtv_pretty(g)", "A[t=(x=int[123],y=text[Hello])]")
        chkFromGtv("{'t':{'x':123,'y':'Hello'}}", "A.from_gtv_pretty(g)", "A[t=(x=int[123],y=text[Hello])]")
        chkFromGtv("[[[1,'A'],123]]", "C.from_gtv_pretty(g)", "C[t=(s=(x=boolean[true],y=text[A]),k=int[123])]")
        chkFromGtv("{'t':[[1,'A'],123]}", "C.from_gtv_pretty(g)", "C[t=(s=(x=boolean[true],y=text[A]),k=int[123])]")
        chkFromGtv("{'t':{'s':[1,'A'],'k':123}}", "C.from_gtv_pretty(g)", "C[t=(s=(x=boolean[true],y=text[A]),k=int[123])]")
        chkFromGtv("{'t':{'s':{'x':1,'y':'A'},'k':123}}", "C.from_gtv_pretty(g)", "C[t=(s=(x=boolean[true],y=text[A]),k=int[123])]")
        chkFromGtv("[{'s':[1,'A'],'k':123}]", "C.from_gtv_pretty(g)", "C[t=(s=(x=boolean[true],y=text[A]),k=int[123])]")
        chkFromGtv("[{'s':{'x':1,'y':'A'},'k':123}]", "C.from_gtv_pretty(g)", "C[t=(s=(x=boolean[true],y=text[A]),k=int[123])]")
        chkFromGtv("[[{'x':1,'y':'A'},123]]", "C.from_gtv_pretty(g)", "C[t=(s=(x=boolean[true],y=text[A]),k=int[123])]")
    }

    @Test fun testToFromGtvStruct() {
        def("struct rec { x: integer; y: text; }")
        def("struct no_gtv { r: range; }")

        chk("rec(123,'Hello').to_gtv()", """gtv[[123,"Hello"]]""")
        chk("no_gtv(range(10)).to_gtv()", "ct_err:fn:invalid:no_gtv:no_gtv.to_gtv")
        chk("rec(123,'Hello').to_gtv_pretty()", """gtv[{"x":123,"y":"Hello"}]""")
        chk("no_gtv(range(10)).to_gtv_pretty()", "ct_err:fn:invalid:no_gtv:no_gtv.to_gtv_pretty")

        chkFromGtv("[123,'Hello']", "rec.from_gtv(g)", "rec[x=int[123],y=text[Hello]]")
        chkFromGtv("[]", "rec.from_gtv(g)", "gtv_err:struct_size:rec:2:0")
        chkFromGtv("[123]", "rec.from_gtv(g)", "gtv_err:struct_size:rec:2:1")
        chkFromGtv("['Hello',123]", "rec.from_gtv(g)", "gtv_err:type:integer:STRING")
        chkFromGtv("{'x':123,'y':'Hello'}", "rec.from_gtv(g)", "gtv_err:type:array:DICT")

        chkFromGtv("{'x':123,'y':'Hello'}", "rec.from_gtv_pretty(g)", "rec[x=int[123],y=text[Hello]]")
        chkFromGtv("{'y':'Hello','x':123}", "rec.from_gtv_pretty(g)", "rec[x=int[123],y=text[Hello]]")
        chkFromGtv("{}", "rec.from_gtv_pretty(g)", "gtv_err:struct_size:rec:2:0")
        chkFromGtv("{'x':123}", "rec.from_gtv_pretty(g)", "gtv_err:struct_size:rec:2:1")
        chkFromGtv("{'y':'Hello'}", "rec.from_gtv_pretty(g)", "gtv_err:struct_size:rec:2:1")
        chkFromGtv("{'y':123,'x':'Hello'}", "rec.from_gtv_pretty(g)", "gtv_err:type:integer:STRING")
        chkFromGtv("[]", "rec.from_gtv(g)", "gtv_err:struct_size:rec:2:0")
        chkFromGtv("[123,'Hello']", "rec.from_gtv_pretty(g)", "rec[x=int[123],y=text[Hello]]")
        chkFromGtv("[]", "rec.from_gtv_pretty(g)", "gtv_err:struct_size:rec:2:0")

        chkFromGtv("[]", "no_gtv.from_gtv(g)", "ct_err:fn:invalid:no_gtv:from_gtv")
        chkFromGtv("[]", "no_gtv.from_gtv_pretty(g)", "ct_err:fn:invalid:no_gtv:from_gtv_pretty")
    }

    @Test fun testToFromGtvEntity() {
        tstCtx.useSql = true
        def("entity user { name; }")
        def("object state { mutable value: integer = 0; }")
        insert("c0.user", "name", "5,'Bob'")

        chk("(user@{}).to_gtv()", "gtv[5]")
        chk("(user@{}).to_gtv_pretty()", "gtv[5]")

        chkFromGtv("5", "user.from_gtv(g)", "user[5]")
        chkFromGtv("'X'", "user.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("4", "user.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("5", "user.from_gtv_pretty(g)", "user[5]")
        chkFromGtv("'X'", "user.from_gtv_pretty(g)", "rt_err:from_gtv_pretty")

        chk("state.to_gtv()", "ct_err:unknown_member:[state]:to_gtv")
        chk("state.to_gtv_pretty()", "ct_err:unknown_member:[state]:to_gtv_pretty")
    }

    @Test fun testToFromGtvOperation() {
        def("operation foo(x: integer, y: text) {}")

        chk("foo(123,'Hello').to_operation().to_gtv()", """gtv[["foo",[123,"Hello"]]]""")
        chk("foo(123,'Hello').to_operation().to_gtv_pretty()", """gtv[["foo",[123,"Hello"]]]""")

        chkFromGtv("['foo',[123,'Hello']]", "operation.from_gtv(g)", """op[foo(gtv[123],gtv["Hello"])]""")
        chkFromGtv("[123,'Hello']", "operation.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("['foo',123,'Hello']", "operation.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("['',[123,'Hello']]", "operation.from_gtv(g)", "rt_err:from_gtv")
        chkFromGtv("['987',[123,'Hello']]", "operation.from_gtv(g)", "rt_err:from_gtv")
    }

    @Test fun testToFromGtvGtxTx() {
        def("operation foo(x: integer, y: text) {}")

        val brid = "00".repeat(32)
        chk("rell.gtx.tx().to_gtv()", """gtv[[["$brid",[],[]],[]]]""")
        chk("rell.gtx.tx(foo(123,'Hello')).to_gtv()", """gtv[[["$brid",[["foo",[123,"Hello"]]],[]],[]]]""")

        chkFromGtv("[['$brid',[],[]],[]]", "rell.gtx.tx.from_gtv(g)", """rell.gtx.tx[]""")
        chkFromGtv("[['$brid',[['foo',[123,'Hello']]],[]],[]]", "rell.gtx.tx.from_gtv(g)",
                """rell.gtx.tx[op[foo(gtv[123],gtv["Hello"])]]""")
    }

    @Test fun testToFromGtvGtxBlock() {
        def("operation foo(x: integer, y: text) {}")

        val brid = "00".repeat(32)
        chk("rell.gtx.block().to_gtv()", """gtv[[]]""")
        chk("rell.gtx.block(rell.gtx.tx(foo(123,'Hello'))).to_gtv()",
                """gtv[[[["$brid",[["foo",[123,"Hello"]]],[]],[]]]]""")

        chkFromGtv("[]", "rell.gtx.block.from_gtv(g)", """rell.gtx.block[]""")
        chkFromGtv("[[['$brid',[['foo',[123,'Hello']]],[]],[]]]", "rell.gtx.block.from_gtv(g)",
                """rell.gtx.block[rell.gtx.tx[op[foo(gtv[123],gtv["Hello"])]]]""")
    }

    private fun chkFromGtv(gtv: String, expr: String, expected: String) {
        val gtv2 = gtv.replace('\'', '"')
        val code = """{ val g = gtv.from_json('$gtv2'); return $expr; }"""
        chkEx(code, expected)
    }
}
