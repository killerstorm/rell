/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib

import net.postchain.rell.model.R_LangVersion
import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class LibMapTest: BaseRellTest(false) {
    @Test fun testLiteral() {
        chk("[]", "ct_err:expr_list_no_type")
        chk("['Bob':123]", "map<text,integer>[text[Bob]=int[123]]")
        chk("['Bob':123,'Alice':456,'Trudy':789]", "map<text,integer>[text[Bob]=int[123],text[Alice]=int[456],text[Trudy]=int[789]]")
        chk("[123:456]", "map<integer,integer>[int[123]=int[456]]")
        chk("['Bob':123,'Alice':'Hello']", "ct_err:expr_map_valuetype:[integer]:[text]")
        chk("[123:456,'Bob':789]", "ct_err:expr_map_keytype:[integer]:[text]")
        chk("['Bob':123,'Bob':456]", "rt_err:expr_map_dupkey:text[Bob]")
        chk("['Bob':123,'Bob':123]", "rt_err:expr_map_dupkey:text[Bob]")
    }

    @Test fun testConstructor() {
        chk("map()", "ct_err:expr_map_notype")
        chk("map<text,integer>()", "map<text,integer>[]")
        chk("map<integer,integer>([123])", "ct_err:expr_map_badtype:list<integer>")
        chk("map<integer,integer>(['Bob':123])", "ct_err:expr_map_key_typemiss:integer:text")
        chk("map<text,integer>(['Bob':123])", "map<text,integer>[text[Bob]=int[123]]")
        chk("map(['Bob':123])", "map<text,integer>[text[Bob]=int[123]]")

        val exp = "map<text,integer>[text[Bob]=int[123],text[Alice]=int[456],text[Trudy]=int[789]]"
        chk("map(['Bob':123,'Alice':456,'Trudy':789])", exp)
        chk("map<text,integer>(['Bob':123,'Alice':456,'Trudy':789])", exp)
        chk("map<integer,text>(['Bob':123,'Alice':456,'Trudy':789])", "ct_err:expr_map_key_typemiss:integer:text")
        chk("map<integer,integer>(['Bob':123,'Alice':456,'Trudy':789])", "ct_err:expr_map_key_typemiss:integer:text")
        chk("map<text,text>(['Bob':123,'Alice':456,'Trudy':789])", "ct_err:expr_map_value_typemiss:text:integer")

        chk("map(range(5))", "ct_err:expr_map_badtype:range")
        chk("map([('Bob',123),('Alice',456),('Trudy',789)])", exp)
        chk("map([(x='Bob',y=123),(x='Alice',y=456),(x='Trudy',y=789)])", exp)
        chk("map([('Bob',y=123),('Alice',y=456),('Trudy',y=789)])", exp)
        chk("map([('Bob',123,true),('Alice',456,false)])", "ct_err:expr_map_badtype:list<(text,integer,boolean)>")

        chk("map([('Bob',123),('Bob',456)])", "rt_err:map:new:iterator:dupkey:text[Bob]")
        chk("map([('Bob',123),('Bob',123)])", "rt_err:map:new:iterator:dupkey:text[Bob]")

        chk("map(x=[123:'Bob'])", "ct_err:expr:call:named_args_not_allowed:map:x")
        chk("map<integer,text>(x=[123:'Bob'])", "ct_err:expr:call:named_args_not_allowed:map:x")
    }

    @Test fun testConstructorPartial() {
        chk("map(*)", "ct_err:expr:call:partial_not_supported:map")
        chk("map<integer,text>(*)", "ct_err:expr:call:partial_not_supported:map")
        chkEx("{ val f: () -> map<integer,text> = map(*); return f; }", "ct_err:expr:call:partial_not_supported:map")
        chkEx("{ val f: () -> map<integer,text> = map<integer,text>(*); return f; }", "ct_err:expr:call:partial_not_supported:map")
        chkEx("{ val f: (map<integer,text>) -> map<integer,text> = map(*); return f; }", "ct_err:expr:call:partial_not_supported:map")
        chkEx("{ val f: (map<integer,text>) -> map<integer,text> = map<integer,text>(*); return f; }", "ct_err:expr:call:partial_not_supported:map")
    }

    @Test fun testEmpty() {
        chk("map<text,integer>().empty()", "boolean[true]")
        chk("['Bob':123].empty()", "boolean[false]")
        chk("['Bob':123,'Alice':456].empty()", "boolean[false]")
    }

    @Test fun testSize() {
        chk("map<text,integer>().size()", "int[0]")
        chk("['Bob':123].size()", "int[1]")
        chk("['Bob':123,'Alice':456].size()", "int[2]")
        chk("map<text,integer>().len()", "ct_err:deprecated:FUNCTION:map<text,integer>.len:size")
    }

    @Test fun testContains() {
        chk("map<text,integer>().contains('Bob')", "boolean[false]")
        chk("map<text,integer>().contains(123)", "ct_err:expr_call_argtypes:map<text,integer>.contains:integer")
        chk("['Bob':123].contains('Bob')", "boolean[true]")
        chk("['Bob':123].contains('Alice')", "boolean[false]")
        chk("['Bob':123,'Alice':456].contains('Bob')", "boolean[true]")
        chk("['Bob':123,'Alice':456].contains('Alice')", "boolean[true]")
        chk("['Bob':123,'Alice':456].contains('Trudy')", "boolean[false]")
    }

    @Test fun testIn() {
        chk("'Bob' in map<text,integer>()", "boolean[false]")
        chk("123 in map<text,integer>()", "ct_err:binop_operand_type:in:[integer]:[map<text,integer>]")
        chk("'Bob' in ['Bob':123]", "boolean[true]")
        chk("'Alice' in ['Bob':123]", "boolean[false]")
        chk("'Bob' in ['Bob':123,'Alice':456]", "boolean[true]")
        chk("'Alice' in ['Bob':123,'Alice':456]", "boolean[true]")
        chk("'Trudy' in ['Bob':123,'Alice':456]", "boolean[false]")
    }

    @Test fun testGet() {
        chk("map<text,integer>().get('Bob')", "rt_err:fn:map.get:novalue:text[Bob]")
        chk("map<text,integer>().get(123)", "ct_err:expr_call_argtypes:map<text,integer>.get:integer")
        chk("['Bob':123].get('Bob')", "int[123]")
        chk("['Bob':123].get('Alice')", "rt_err:fn:map.get:novalue:text[Alice]")
        chk("['Bob':123,'Alice':456].get('Bob')", "int[123]")
        chk("['Bob':123,'Alice':456].get('Alice')", "int[456]")
        chk("['Bob':123,'Alice':456].get('Trudy')", "rt_err:fn:map.get:novalue:text[Trudy]")
    }

    @Test fun testSubscriptGet() {
        chk("['Bob':123]['Bob']", "int[123]")
        chk("['Bob':123][123]", "ct_err:expr_subscript_keytype:[text]:[integer]")
        chk("['Bob':123]['Alice']", "rt_err:fn_map_get_novalue:text[Alice]")
        chk("['Bob':123,'Alice':456]['Bob']", "int[123]")
        chk("['Bob':123,'Alice':456]['Alice']", "int[456]")
        chk("['Bob':123,'Alice':456]['Trudy']", "rt_err:fn_map_get_novalue:text[Trudy]")

        chk("map(['Bob':123])['Bob']", "int[123]")
        chk("map(['Bob':123])[123]", "ct_err:expr_subscript_keytype:[text]:[integer]")
        chk("map(['Bob':123])['Alice']", "rt_err:fn_map_get_novalue:text[Alice]")
        chk("map(['Bob':123,'Alice':456])['Bob']", "int[123]")
        chk("map(['Bob':123,'Alice':456])['Alice']", "int[456]")
        chk("map(['Bob':123,'Alice':456])['Trudy']", "rt_err:fn_map_get_novalue:text[Trudy]")

        chkEx("{ val m: map<text,integer>? = if (1>0) ['Bob':123] else null; return m['Bob']; }", "ct_err:expr_subscript_null")
        chkEx("{ val m: map<text,integer>? = if (1>0) ['Bob':123] else null; return m!!['Bob']; }", "int[123]")
    }

    @Test fun testEquals() {
        val map = "['Bob':123,'Alice':456,'Trudy':789]"
        chk("$map == ['Bob':123,'Alice':456,'Trudy':789]", "boolean[true]")
        chk("$map == ['Bob':321,'Alice':654,'Trudy':987]", "boolean[false]")
        chk("$map == ['Bob':123,'Alice':456]", "boolean[false]")
        chk("$map == ['Bob':123,'Alice':456,'Trudy':789,'Satoshi':555]", "boolean[false]")
        chk("$map == map<text,integer>()", "boolean[false]")
        chk("$map == map<integer,text>()", "ct_err:binop_operand_type:==:[map<text,integer>]:[map<integer,text>]")
        chk("$map == map<text,text>()", "ct_err:binop_operand_type:==:[map<text,integer>]:[map<text,text>]")
        chk("$map == map<integer,integer>()", "ct_err:binop_operand_type:==:[map<text,integer>]:[map<integer,integer>]")
        chk("$map == [123:'Bob']", "ct_err:binop_operand_type:==:[map<text,integer>]:[map<integer,text>]")
        chk("$map == [123:456]", "ct_err:binop_operand_type:==:[map<text,integer>]:[map<integer,integer>]")
        chk("$map == ['Bob':'Alice']", "ct_err:binop_operand_type:==:[map<text,integer>]:[map<text,text>]")
        chk("$map == [123]", "ct_err:binop_operand_type:==:[map<text,integer>]:[list<integer>]")
        chk("$map == ['Bob']", "ct_err:binop_operand_type:==:[map<text,integer>]:[list<text>]")
        chk("$map == set<integer>()", "ct_err:binop_operand_type:==:[map<text,integer>]:[set<integer>]")
        chk("$map == set([1, 2, 3])", "ct_err:binop_operand_type:==:[map<text,integer>]:[set<integer>]")
    }

    @Test fun testStr() {
        chk("map<text,integer>().str()", "text[{}]")
        chk("['Bob':123].str()", "text[{Bob=123}]")
        chk("['Bob':123,'Alice':456,'Trudy':789].str()", "text[{Bob=123, Alice=456, Trudy=789}]")
    }

    @Test fun testClear() {
        chkEx("{ val x = ['Bob':123,'Alice':567,'Trudy':789]; x.clear(); return x; }", "map<text,integer>[]")
    }

    @Test fun testPut() {
        tst.strictToString = false
        chkEx("{ val x = map<text,integer>(); x.put('Bob',123); return ''+x; }", "{Bob=123}")
        chkEx("{ val x = ['Bob':123]; x.put('Bob',456); return ''+x; }", "{Bob=456}")
        chkEx("{ val x = ['Bob':123,'Alice':456]; x.put('Bob',555); return ''+x; }", "{Bob=555, Alice=456}")
        chkEx("{ val x = ['Bob':123,'Alice':456]; x.put('Alice',555); return ''+x; }", "{Bob=123, Alice=555}")
        chkEx("{ val x = ['Bob':123,'Alice':456]; x.put('Trudy',555); return ''+x; }", "{Bob=123, Alice=456, Trudy=555}")
        chkEx("{ val x = ['Bob':123]; x.put('Alice','Hello'); return ''+x; }", "ct_err:expr_call_argtypes:map<text,integer>.put:text,text")
        chkEx("{ val x = ['Bob':123]; x.put(123,456); return ''+x; }", "ct_err:expr_call_argtypes:map<text,integer>.put:integer,integer")
        chkEx("{ val x = ['Bob':123]; x.put(123,'Bob'); return ''+x; }", "ct_err:expr_call_argtypes:map<text,integer>.put:integer,text")
    }

    @Test fun testSubscriptSet() {
        tst.strictToString = false
        chkEx("{ val x = map<text,integer>(); x['Bob'] = 123; return ''+x; }", "{Bob=123}")
        chkEx("{ val x = ['Bob':123]; x['Bob'] = 456; return ''+x; }", "{Bob=456}")
        chkEx("{ val x = ['Bob':123,'Alice':456]; x['Bob'] = 555; return ''+x; }", "{Bob=555, Alice=456}")
        chkEx("{ val x = ['Bob':123,'Alice':456]; x['Alice'] = 555; return ''+x; }", "{Bob=123, Alice=555}")
        chkEx("{ val x = ['Bob':123,'Alice':456]; x['Trudy'] = 555; return ''+x; }", "{Bob=123, Alice=456, Trudy=555}")
        chkEx("{ val x = ['Bob':123]; x['Alice'] = 'Hello'; return ''+x; }", "ct_err:stmt_assign_type:[integer]:[text]")
        chkEx("{ val x = ['Bob':123]; x[123] = 456; return ''+x; }", "ct_err:expr_subscript_keytype:[text]:[integer]")
        chkEx("{ val x = ['Bob':123]; x[123] = 'Bob'; return ''+x; }", "ct_err:expr_subscript_keytype:[text]:[integer]")
        chkEx("{ val x = ['Bob':123,'Alice':456]; x['Bob'] += 500; return ''+x; }", "{Bob=623, Alice=456}")

        chkEx("{ val m: map<text,integer>? = if (1>0) ['Bob':123] else null; m['Bob'] = 456; return m; }", "ct_err:expr_subscript_null")
        chkEx("{ val m: map<text,integer>? = if (1>0) ['Bob':123] else null; m!!['Bob'] = 456; return m; }", "{Bob=456}")
    }

    @Test fun testPutAll() {
        tst.strictToString = false

        chkEx("{ val x = map<text,integer>(); x.put_all(map<text,integer>()); return ''+x; }", "{}")
        chkEx("{ val x = map<text,integer>(); x.put_all(map<text,text>()); return ''+x; }",
                "ct_err:expr_call_argtypes:map<text,integer>.put_all:map<text,text>")
        chkEx("{ val x = map<text,integer>(); x.put_all(['Bob':123]); return ''+x; }", "{Bob=123}")

        chkEx("{ val x = ['Bob':123,'Alice':456]; x.put_all(map<text,integer>()); return ''+x; }", "{Bob=123, Alice=456}")
        chkEx("{ val x = ['Bob':123,'Alice':456]; x.put_all(['Trudy':789]); return ''+x; }", "{Bob=123, Alice=456, Trudy=789}")
        chkEx("{ val x = ['Bob':123,'Alice':456]; x.put_all(['Trudy':'Hello']); return ''+x; }",
                "ct_err:expr_call_argtypes:map<text,integer>.put_all:map<text,text>")
        chkEx("{ val x = ['Bob':123,'Alice':456]; x.put_all([123:456]); return ''+x; }",
                "ct_err:expr_call_argtypes:map<text,integer>.put_all:map<integer,integer>")
        chkEx("{ val x = ['Bob':123,'Alice':456]; x.put_all(['Bob':555,'Trudy':777]); return ''+x; }",
                "{Bob=555, Alice=456, Trudy=777}")
    }

    @Test fun testRemove() {
        tst.strictToString = false
        chkEx("{ val x = ['Bob':123,'Alice':456]; val r = x.remove('Bob'); return ''+r+ ' ' + x; }", "123 {Alice=456}")
        chkEx("{ val x = ['Bob':123,'Alice':456]; val r = x.remove('Alice'); return ''+r+ ' ' + x; }", "456 {Bob=123}")
        chkEx("{ val x = ['Bob':123,'Alice':456]; val r = x.remove('Trudy'); return ''+r+ ' ' + x; }",
                "rt_err:fn:map.remove:novalue:text[Trudy]")
        chkEx("{ val x = ['Bob':123,'Alice':456]; val r = x.remove(123); return 0; }",
                "ct_err:expr_call_argtypes:map<text,integer>.remove:integer")
    }

    @Test fun testKeys() {
        chk("map<text,integer>().keys()", "set<text>[]")
        chk("['Bob':123].keys()", "set<text>[text[Bob]]")
        chk("['Bob':123,'Alice':456,'Trudy':789].keys()", "set<text>[text[Bob],text[Alice],text[Trudy]]")
    }

    @Test fun testKeysModification() {
        tst.strictToString = false
        chkEx("{ val x = ['Bob':123,'Alice':456]; x.keys().remove('Bob'); return ''+x; }", "{Bob=123, Alice=456}")
        chkEx("{ val x = ['Bob':123,'Alice':456]; x.keys().clear(); return ''+x; }", "{Bob=123, Alice=456}")
        chkEx("{ val x = ['Bob':123,'Alice':456]; x.keys().add('Trudy'); return ''+x; }", "{Bob=123, Alice=456}")
        chkEx("{ val x = ['Bob':123,'Alice':456]; val k = x.keys(); x.clear(); return ''+x+' '+k; }", "{} [Bob, Alice]")
    }

    @Test fun testValues() {
        chk("map<text,integer>().values()", "list<integer>[]")
        chk("['Bob':123].values()", "list<integer>[int[123]]")
        chk("['Bob':123,'Alice':456,'Trudy':789].values()", "list<integer>[int[123],int[456],int[789]]")
    }

    @Test fun testValuesModification() {
        tst.strictToString = false
        chkEx("{ val x = ['Bob':123,'Alice':456]; x.values().clear(); return ''+x; }", "{Bob=123, Alice=456}")
        chkEx("{ val x = ['Bob':123,'Alice':456]; val v = x.values(); x.clear(); return ''+x+' '+v; }", "{} [123, 456]")
    }

    @Test fun testFor() {
        chkOp("for (k in ['Bob':123,'Alice':456,'Trudy':789]) print(k);")
        chkOut("(Bob,123)", "(Alice,456)", "(Trudy,789)")
    }

    @Test fun testMutableKey() {
        chkEx("{ return [[123] : 'Hello']; }", "ct_err:expr_map_keytype:list<integer>")
        chkEx("{ var x: map<list<integer>,text>; return 0; }", "ct_err:expr_map_keytype:list<integer>")
        chkEx("{ return map<list<integer>,text>(); }", "ct_err:expr_map_keytype:list<integer>")
    }

    @Test fun testAsForAndAtItem() {
        chkEx("{ for (x in [123:'hello']) return _type_of(x); return null; }", "text[(integer,text)]")
        chkEx("{ for (x in [123:'hello']) return x; return null; }", "(int[123],text[hello])")

        chk("[123:'hello'] @{} ( _type_of($) )", "text[(integer,text)]")
        chk("[123:'hello'] @{} ( $ )", "(int[123],text[hello])")

        tst.compatibilityVer = R_LangVersion.of("0.10.5")

        chkEx("{ for (x in [123:'hello']) return _type_of(x); return null; }", "text[(integer,text)]")
        chkEx("{ for (x in [123:'hello']) return x; return null; }", "(int[123],text[hello])")

        chk("[123:'hello'] @{} ( _type_of($) )", "text[(k:integer,v:text)]")
        chk("[123:'hello'] @{} ( $ )", "(k=int[123],v=text[hello])")
    }
}
