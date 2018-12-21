package net.postchain.rell

import org.junit.Test
import kotlin.test.assertEquals

class RecordTest: BaseRellTest(false) {
    @Test fun testGeneral() {
        tst.defs = listOf("record foo { x: integer; s: text; }")
        chkEx("{ val r = foo(x = 123, s = 'Hello'); return r.x; }", "int[123]")
        chkEx("{ val r = foo(x = 123, s = 'Hello'); return r.s; }", "text[Hello]")
        chkEx("{ return foo(x = 123, s = 'Hello'); }", "foo[x=int[123],s=text[Hello]]")
    }

    @Test fun testConstruct() {
        tst.defs = listOf("record foo { i: integer; s: text; q: text = 'Unknown'; t: (integer, text); }")

        chkEx("{ return foo(i = 123, s = 'Hello', q = 'Foo', t = (456, 'Bye')); }",
                "foo[i=int[123],s=text[Hello],q=text[Foo],t=(int[456],text[Bye])]")

        chkEx("{ return foo(i = 123, s = 'Hello', t = (456, 'Bye')); }",
                "foo[i=int[123],s=text[Hello],q=text[Unknown],t=(int[456],text[Bye])]")

        chkEx("{ return foo(i = 123, s = 'Hello'); }", "ct_err:attr_missing:t")

        chkEx("{ return foo(i = 123, s = 'Hello', t = 'WrongType'); }",
                "ct_err:attr_bad_type:2:t:(integer,text):text")
    }

    @Test fun testConstructResolveAttrByName() {
        tst.defs = listOf("record foo { i: integer; s: text; q: text = 'Unknown'; }")

        chkEx("{ val s = 'Hello'; val q = 'Bye'; return foo(i = 123, q, s); }",
                "foo[i=int[123],s=text[Hello],q=text[Bye]]")

        chkEx("{ val s = 'Hello'; return foo(i = 123, s); }", "foo[i=int[123],s=text[Hello],q=text[Unknown]]")
        chkEx("{ val q = 'Bye'; return foo(i = 123, q); }", "ct_err:attr_missing:s")

        chkEx("{ val s = 123; return foo(i = 123, s); }", "ct_err:attr_bad_type:1:s:text:integer")
    }

    @Test fun testConstructResolveAttrByType() {
        tst.defs = listOf("record foo { i: integer; s: text; q: text = 'Unknown'; }")
        chkEx("{ val v = 123; return foo(v, s = 'Hello'); }", "foo[i=int[123],s=text[Hello],q=text[Unknown]]")
        chkEx("{ val v = 'Hello'; return foo(i = 123, v); }", "ct_err:attr_implic_multi:1:s,q")
    }

    @Test fun testConstructNoArgs() {
        tst.defs = listOf("record foo { x: integer = 123; } record bar { f: foo; }")
        chk("foo()", "foo[x=int[123]]")
        chk("bar(foo())", "bar[f=foo[x=int[123]]]")
    }

    @Test fun testRecordVsFunctionNameConflict() {
        chkCompile("function foo(x: integer){} record foo {s:text;}", "ct_err:name_conflict:function:foo")
        chkCompile("record foo {s:text;} function foo(x: integer){}", "ct_err:name_conflict:record:foo")
    }

    @Test fun testMutableAttributes() {
        tst.defs = listOf("record foo { mutable a: integer; b: integer; }")
        chkEx("{ val r = foo(a = 123, b = 456); return r; }", "foo[a=int[123],b=int[456]]")
        chkEx("{ val r = foo(a = 123, b = 456); r.a = 789; return r; }", "foo[a=int[789],b=int[456]]")
        chkEx("{ val r = foo(a = 123, b = 456); r.b = 789; return r; }", "ct_err:update_attr_not_mutable:b")
        chkEx("{ val r = foo(a = 123, b = 456); r.b += 789; return r; }", "ct_err:update_attr_not_mutable:b")
    }

    @Test fun testAttributeTypeNullable() {
        tst.defs = listOf("record foo { a: integer?; b: text?; }")
        chk("foo(a = 123, b = 'Hello')", "foo[a=int[123],b=text[Hello]]")
        chk("foo(a = null, b = 'Hello')", "foo[a=null,b=text[Hello]]")
        chk("foo(a = 123, b = null)", "foo[a=int[123],b=null]")
        chk("foo(a = null, b = null)", "foo[a=null,b=null]")
    }

    @Test fun testAttributeTypeTuple() {
        tst.defs = listOf("record foo { a: (integer, text); }")
        chk("foo((123, 'Hello'))", "foo[a=(int[123],text[Hello])]")
    }

    @Test fun testAttributeTypeCollection() {
        tst.defs = listOf("record foo { l: list<integer>; s: set<text>; m: map<integer, text>; }")
        chk("foo([123], set(['Hello']), [123:'Hello'])",
                "foo[l=list<integer>[int[123]],s=set<text>[text[Hello]],m=map<integer,text>[int[123]=text[Hello]]]")
    }

    @Test fun testTypeCompatibility() {
        tst.defs = listOf("record foo { x: integer; } record bar { x: integer; }")
        chkEx("{ val r: foo = foo(123); return r; }", "foo[x=int[123]]")
        chkEx("{ val r: bar = bar(123); return r; }", "bar[x=int[123]]")
        chkEx("{ val r: foo = bar(123); return r; }", "ct_err:stmt_val_type:r:foo:bar")
        chkEx("{ val r: bar = foo(123); return r; }", "ct_err:stmt_val_type:r:bar:foo")
    }

    @Test fun testRecordAsClassAttributeType() {
        chkCompile("record foo { x: integer; } class bar { foo; }", "ct_err:class_attr_type:foo:foo")
    }

    @Test fun testAttributeOfNullableRecord() {
        tst.defs = listOf("record foo { mutable x: integer; }")

        chkEx("{ val r: foo? = foo(123); return r.x; }", "ct_err:expr_mem_null:x")
        chkEx("{ val r: foo? = foo(123); return r!!.x; }", "int[123]")
        chkEx("{ val r: foo? = null; return r!!.x; }", "rt_err:null_value")
        chkEx("{ val r: foo? = foo(123); return r?.x; }", "int[123]")
        chkEx("{ val r: foo? = null; return r?.x; }", "null")

        chkEx("{ val r: foo? = foo(123); r.x = 456; return r; }", "ct_err:expr_mem_null:x")

        chkEx("{ val r: foo? = foo(123); r!!.x = 456; return r; }", "foo[x=int[456]]")
        chkEx("{ val r: foo? = null; r!!.x = 456; return r; }", "rt_err:null_value")
        chkEx("{ val r: foo? = foo(123); r!!.x += 456; return r; }", "foo[x=int[579]]")
        chkEx("{ val r: foo? = null; r!!.x += 456; return r; }", "rt_err:null_value")

        chkEx("{ val r: foo? = foo(123); r?.x = 456; return r; }", "foo[x=int[456]]")
        chkEx("{ val r: foo? = null; r?.x = 456; return r; }", "null")
        chkEx("{ val r: foo? = foo(123); r?.x += 456; return r; }", "foo[x=int[579]]")
        chkEx("{ val r: foo? = null; r?.x += 456; return r; }", "null")
    }

    @Test fun testAttributeOfNullableRecord2() {
        tst.defs = listOf("record foo { b: bar?; } record bar { mutable x: integer; }")
        chkEx("{ val r: foo? = foo(bar(123)); return r?.b?.x; }", "int[123]")
        chkEx("{ val r: foo? = foo(null); return r?.b?.x; }", "null")
        chkEx("{ val r: foo? = null; return r?.b?.x; }", "null")

        chkEx("{ val r: foo? = foo(bar(123)); r?.b?.x = 456; return r; }", "foo[b=bar[x=int[456]]]")
        chkEx("{ val r: foo? = foo(null); r?.b?.x = 456; return r; }", "foo[b=null]")
        chkEx("{ val r: foo? = null; r?.b?.x = 456; return r; }", "null")

        chkEx("{ val r: foo? = foo(bar(123)); r?.b?.x += 456; return r; }", "foo[b=bar[x=int[579]]]")
        chkEx("{ val r: foo? = foo(null); r?.b?.x += 456; return r; }", "foo[b=null]")
        chkEx("{ val r: foo? = null; r?.b?.x += 456; return r; }", "null")
    }

    @Test fun testConstructUnderAt() {
        tst.useSql = true
        tst.defs = listOf("class user { name; value: integer; } record foo { x: integer; }")
        execOp("create user('Bob', 123); create user('Alice', 456);")
        chk("user @ { .value == foo(x = 123).x }(.name)", "text[Bob]")
        chk("user @ { .value == foo(x = 456).x }(.name)", "text[Alice]")
    }

    @Test fun testAccessUnderAt() {
        tst.useSql = true
        tst.defs = listOf("class user { name; value: integer; } record foo { x: integer; }")
        execOp("create user('Bob', 123); create user('Alice', 456);")
        chkEx("{ var r = foo(123); return user @ { .value == r.x }(.name); }", "text[Bob]")
        chkEx("{ var r = foo(456); return user @ { .value == r.x }(.name); }", "text[Alice]")
    }

    @Test fun testRecordFlags() {
        chkFlags("record foo { x: integer; }", "foo[hum,com]")
        chkFlags("record foo { mutable x: integer; }", "foo[mut,hum,com]")
        chkFlags("record foo { x: integer; next: foo?; }", "foo[hum,com,cyc,inf]")
        chkFlags("record foo { mutable x: integer; next: foo?; }", "foo[mut,hum,com,cyc,inf]")

        chkFlags("record foo { x: integer; }", "foo[hum,com]")
        chkFlags("record foo { x: integer?; }", "foo[hum,com]")
        chkFlags("record foo { x: text; }", "foo[hum,com]")
        chkFlags("record foo { x: byte_array; }", "foo[hum,com]")
        chkFlags("record foo { x: boolean; }", "foo[hum,com]")
        chkFlags("record foo { x: (x: integer, text); }", "foo[com]")
        chkFlags("record foo { x: range; }", "foo[]")
        chkFlags("class user { name; } record foo { x: user; }", "foo[hum,com]")
        chkFlags("record foo { x: list<integer>; }", "foo[mut,hum,com]")
        chkFlags("record foo { x: set<integer>; }", "foo[mut,hum,com]")
        chkFlags("record foo { x: map<integer,text>; }", "foo[mut]")

        chkFlags("record bar { x: integer; } record foo { y: bar; }", "bar[hum,com],foo[hum,com]")
        chkFlags("record bar { x: integer; } record foo { mutable y: bar; }", "bar[hum,com],foo[mut,hum,com]")
        chkFlags("record bar { mutable x: integer; } record foo { y: bar; }", "bar[mut,hum,com],foo[mut,hum,com]")
        chkFlags("record bar { mutable x: integer?; } record foo { y: bar; }", "bar[mut,hum,com],foo[mut,hum,com]")

        chkFlags("record bar { x: foo?; } record foo { y: bar?; }", "bar[hum,com,cyc,inf],foo[hum,com,cyc,inf]")
        chkFlags("record bar { mutable x: foo?; } record foo { y: bar?; }", "bar[mut,hum,com,cyc,inf],foo[mut,hum,com,cyc,inf]")
        chkFlags("record bar { x: foo?; } record foo { mutable y: bar?; }", "bar[mut,hum,com,cyc,inf],foo[mut,hum,com,cyc,inf]")

        chkFlags("record bar { x: bar?; } record foo { y: bar?; }", "bar[hum,com,cyc,inf],foo[hum,com,inf]")
        chkFlags("record bar { mutable x: bar?; } record foo { y: bar?; }", "bar[mut,hum,com,cyc,inf],foo[mut,hum,com,inf]")
        chkFlags("record bar { x: bar?; } record foo { mutable y: bar?; }", "bar[hum,com,cyc,inf],foo[mut,hum,com,inf]")

        chkFlags("record bar { x: foo?; } record foo { y: (q: boolean, list<map<text,bar>>); }",
                "bar[mut,com,cyc,inf],foo[mut,com,cyc,inf]")
        chkFlags("record bar { x: integer; } record foo { y: (q: boolean, list<map<text,bar>>); }", "bar[hum,com],foo[mut,com]")
    }

    @Test fun testRecordFlagsGtx() {
        chkFlags("record foo { x: integer; }", "foo[hum,com]")
        chkFlags("record foo { x: (a: text, b: integer); }", "foo[hum,com]")
        chkFlags("record foo { x: (a: integer, b: text); }", "foo[hum,com]")
        chkFlags("record foo { x: (x: text, integer); }", "foo[com]")

        chkFlags("record foo { x: map<text,integer>; }", "foo[mut,hum,com]")
        chkFlags("record foo { x: map<integer,text>; }", "foo[mut]")

        chkFlags("record foo { x: list<set<(a: text, b: integer)>>; }", "foo[mut,hum,com]")
        chkFlags("record foo { x: list<set<(q: text, integer)>>; }", "foo[mut,com]")
        chkFlags("record foo { x: list<map<text,integer>>; }", "foo[mut,hum,com]")
        chkFlags("record foo { x: list<map<integer,text>>; }", "foo[mut]")

        chkFlags("record foo { x: (a:text,b:integer); } record bar { y: map<integer,text>; }", "bar[mut],foo[hum,com]")
        chkFlags("record foo { x: (a:text,b:integer); p: bar; } record bar { y: map<integer,text>; }", "bar[mut],foo[mut]")
        chkFlags("record foo { x: (a:text,b:integer); } record bar { y: map<integer,text>; q: foo; }", "bar[mut],foo[hum,com]")
        chkFlags("record foo { x: (a:text,b:integer); p: bar; } record bar { y: map<integer,text>; q: foo; }",
                "bar[mut,cyc,inf],foo[mut,cyc,inf]")

        chkFlags("record foo { x: (t:text,integer); } record bar { y: map<integer,text>; }", "bar[mut],foo[com]")
        chkFlags("record foo { x: (t:text,integer); p: bar; } record bar { y: map<integer,text>; }", "bar[mut],foo[mut]")
        chkFlags("record foo { x: (t:text,integer); } record bar { y: map<integer,text>; q: foo; }", "bar[mut],foo[com]")
        chkFlags("record foo { x: (t:text,integer); p: bar; } record bar { y: map<integer,text>; q: foo; }",
                "bar[mut,cyc,inf],foo[mut,cyc,inf]")
    }

    private fun chkFlags(code: String, expected: String) {
        val actual = tst.processModule(code) { module ->
            val lst = mutableListOf<String>()
            for (rec in module.records.values.sortedBy { it.name }) {
                val flags = mutableListOf<String>()
                if (rec.flags.typeFlags.mutable) flags.add("mut")
                if (rec.flags.typeFlags.gtxHuman) flags.add("hum")
                if (rec.flags.typeFlags.gtxCompact) flags.add("com")
                if (rec.flags.cyclic) flags.add("cyc")
                if (rec.flags.infinite) flags.add("inf")
                lst.add("${rec.name}[${flags.joinToString(",")}]")
            }
            lst.joinToString(",")
        }
        assertEquals(expected, actual)
    }

    @Test fun testMutableRecordAsMapSetKey() {
        tst.defs = listOf("record foo { mutable x: integer; }")
        chk("set<foo>()", "ct_err:expr_set_type:foo")
        chk("map<foo,text>()", "ct_err:expr_map_keytype:foo")
        chk("map<text,foo>()", "map<text,foo>[]")
        chk("[ foo(123) : 'Hello' ]", "ct_err:expr_map_keytype:foo")
        chk("[ 'Hello' : foo(123) ]", "map<text,foo>[text[Hello]=foo[x=int[123]]]")
        chk("set([foo(123)])", "ct_err:expr_set_type:foo")
        chk("[foo(123)]", "list<foo>[foo[x=int[123]]]")
    }

    @Test fun testRecordAsMapSetKeySimple() {
        tst.defs = listOf("record foo { x: integer; }")

        chkEx("{ var s = set([foo(123)]); return s; }", "set<foo>[foo[x=int[123]]]")
        chkEx("{ var s = set([foo(123)]); return s.contains(foo(123)); }", "boolean[true]")
        chkEx("{ var s = set([foo(123)]); return s.contains(foo(456)); }", "boolean[false]")

        chkEx("{ var m = [foo(123) : 'Hello']; return m; }", "map<foo,text>[foo[x=int[123]]=text[Hello]]")
        chkEx("{ var m = [foo(123) : 'Hello']; return m[foo(123)]; }", "text[Hello]")
        chkEx("{ var m = [foo(123) : 'Hello']; return m[foo(456)]; }", "rt_err:fn_map_get_novalue:foo[x=int[456]]")
    }

    @Test fun testRecordAsMapSetKeyComplex() {
        tst.defs = listOf("record foo { x: text; b: bar; } record bar { p: integer; q: integer; }")

        chkEx("{ var s = set([foo('ABC', bar(p=123,q=456))]); return s; }",
                "set<foo>[foo[x=text[ABC],b=bar[p=int[123],q=int[456]]]]")
        chkEx("{ var s = set([foo('ABC', bar(p=123,q=456))]); return s.contains(foo('ABC',bar(p=123,q=456))); }",
                "boolean[true]")
        chkEx("{ var s = set([foo('ABC', bar(p=123,q=456))]); return s.contains(foo('ABC',bar(p=123,q=789))); }",
                "boolean[false]")
        chkEx("{ var s = set([foo('ABC', bar(p=123,q=456))]); return s.contains(foo('DEF',bar(p=123,q=456))); }",
                "boolean[false]")

        chkEx("{ var m = [foo('ABC', bar(p=123,q=456)) : 'X']; return m; }",
                "map<foo,text>[foo[x=text[ABC],b=bar[p=int[123],q=int[456]]]=text[X]]")
        chkEx("{ var m = [foo('ABC', bar(p=123,q=456)) : 'X']; return m[foo('ABC', bar(p=123,q=456))]; }",
                "text[X]")
        chkEx("{ var m = [foo('ABC', bar(p=123,q=456)) : 'X']; return m[foo('ABC', bar(p=123,q=789))]; }",
                "rt_err:fn_map_get_novalue:foo[x=text[ABC],b=bar[p=int[123],q=int[789]]]")
        chkEx("{ var m = [foo('ABC', bar(p=123,q=456)) : 'X']; return m[foo('DEF', bar(p=123,q=456))]; }",
                "rt_err:fn_map_get_novalue:foo[x=text[DEF],b=bar[p=int[123],q=int[456]]]")
    }

    @Test fun testEqSimple() {
        tst.defs = listOf("record foo { x: integer; y: text; } record bar { x: integer; y: text; }")

        chkEx("{ val f = foo(123, 'Hello'); return f == foo(123, 'Hello'); }", "boolean[true]")
        chkEx("{ val f = foo(123, 'Hello'); return f != foo(123, 'Hello'); }", "boolean[false]")
        chkEx("{ val f = foo(123, 'Hello'); return f == foo(456, 'Hello'); }", "boolean[false]")
        chkEx("{ val f = foo(123, 'Hello'); return f != foo(456, 'Hello'); }", "boolean[true]")
        chkEx("{ val f = foo(123, 'Hello'); return f == foo(123, 'Bye'); }", "boolean[false]")
        chkEx("{ val f = foo(123, 'Hello'); return f != foo(123, 'Bye'); }", "boolean[true]")

        chkEx("{ val f = foo(123, 'Hello'); return f == bar(123, 'Hello'); }", "ct_err:binop_operand_type:==:foo:bar")
        chkEx("{ val f = foo(123, 'Hello'); return f != bar(123, 'Hello'); }", "ct_err:binop_operand_type:!=:foo:bar")
    }

    @Test fun testEqComplex() {
        tst.defs = listOf("record foo { x: integer; b: list<bar>; } record bar { s: list<text>; q: boolean; }")

        chkEx("{ val f = foo(123, [bar(['Hello'], true)]); return f == foo(123, [bar(['Hello'], true)]); }",
                "boolean[true]")

        chkEx("""{ val l = ['Hello']; val f = foo(123, [bar(l, true)]);
                l.add('Bye');
                return f == foo(123, [bar(['Hello'], true)]); }""".trimIndent(),
                "boolean[false]")

        chkEx("""{ val l = ['Hello']; val f = foo(123, [bar(l, true)]);
                l.add('Bye');
                return f == foo(123, [bar(['Hello', 'Bye'], true)]); }""".trimIndent(),
                "boolean[true]")
    }

    @Test fun testEqTree() {
        tst.defs = listOf("record node { left: node?; right: node?; value: integer; }")

        chkEx("""{
            val p = node(
                left = node(left = null, right = null, 123),
                456,
                right = node(left = null, right = null, 789)
            );
            val q = node(
                right = node(left = null, right = null, 789),
                456,
                left = node(left = null, right = null, 123)
            );
            return p == q;
        }""".trimIndent(), "boolean[true]")

        chkEx("""{
            val p = node(
                left = node(left = null, right = null, 123),
                456,
                right = node(left = null, right = null, 789)
            );
            val q = node(
                right = node(left = null, right = null, 123),
                456,
                left = node(left = null, right = null, 789)
            );
            return p == q;
        }""".trimIndent(), "boolean[false]")
    }

    @Test fun testRefEq() {
        tst.defs = listOf("record foo { a: integer; b: text; }")

        chkEx("{ val x = foo(123, 'Hello'); val y = foo(123, 'Hello'); return x == y; }", "boolean[true]")
        chkEx("{ val x = foo(123, 'Hello'); val y = foo(123, 'Hello'); return x != y; }", "boolean[false]")
        chkEx("{ val x = foo(123, 'Hello'); val y = foo(123, 'Hello'); return x === y; }", "boolean[false]")
        chkEx("{ val x = foo(123, 'Hello'); val y = foo(123, 'Hello'); return x !== y; }", "boolean[true]")
        chkEx("{ val x = foo(123, 'Hello'); val y = x; return x === y; }", "boolean[true]")
        chkEx("{ val x = foo(123, 'Hello'); val y = x; return x !== y; }", "boolean[false]")
    }

    @Test fun testToString() {
        tst.defs = listOf("record foo { a: integer; b: bar; } record bar { s: text; }")
        chk("'' + foo(123, bar('Hello'))", "text[foo{a=123,b=bar{s=Hello}}]")
    }

    @Test fun testFunctionNamedArgument() {
        tst.defs = listOf("function foo(x: integer): integer = x * x;")
        chk("foo(x = 123)", "ct_err:expr_call_namedarg:x")
    }

    @Test fun testCircularReference() {
        val code = """
            record node { value: integer; mutable next: node?; }

            function list_to_chain(values: list<integer>): node? {
                var first: node? = null;
                var last: node? = null;
                for (value in values) {
                    val node = node(value, next = null);
                    if (last == null) {
                        first = node;
                        last = node;
                    } else {
                        last!!.next = node;
                        last = node;
                    }
                }
                return first;
            }

            function chain_to_list(chain: node?): list<integer> {
                val res = list<integer>();
                var ptr = chain;
                while (ptr != null) {
                    res.add(ptr!!.value);
                    ptr = ptr!!.next;
                }
                return res;
            }

            query q() {
                val chain = list_to_chain([123, 456, 789]);
                return chain_to_list(chain);
            }
        """.trimIndent()

        chkQueryEx(code, "list<integer>[int[123],int[456],int[789]]")
    }
}
