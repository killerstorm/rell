package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class NullableTest: BaseRellTest(false) {
    @Test fun testNullableTypes() {
        chkFn(": boolean = null;", "ct_err:entity_rettype:boolean:null")
        chkFn(": boolean? = null;", "null")
        chkFn(": boolean? = true;", "boolean[true]")

        chkFn(": integer = null;", "ct_err:entity_rettype:integer:null")
        chkFn(": integer? = null;", "null")
        chkFn(": integer? = 123;", "int[123]")
        chkFn(": integer? = 'Hello';", "ct_err:entity_rettype:integer?:text")

        chkFn(": text = null;", "ct_err:entity_rettype:text:null")
        chkFn(": text? = null;", "null")
        chkFn(": text? = 'Hello';", "text[Hello]")
        chkFn(": text? = 123;", "ct_err:entity_rettype:text?:integer")

        chkFn(": byte_array? = null;", "null")
        chkFn(": json? = null;", "null")

        chkFn(": list<text> = null;", "ct_err:entity_rettype:list<text>:null")
        chkFn(": list<text>? = null;", "null")
        chkFn(": list<text>? = ['Hello', 'World'];", "list<text>[text[Hello],text[World]]")
        chkFn(": list<text>? = [123, 456];", "ct_err:entity_rettype:list<text>?:list<integer>")

        chkFn(": set<text> = null;", "ct_err:entity_rettype:set<text>:null")
        chkFn(": set<text>? = null;", "null")
        chkFn(": set<text>? = set(['Hello', 'World']);", "set<text>[text[Hello],text[World]]")
        chkFn(": set<text>? = ['Hello', 'World'];", "ct_err:entity_rettype:set<text>?:list<text>")

        chkFn(": map<integer,text> = null;", "ct_err:entity_rettype:map<integer,text>:null")
        chkFn(": map<integer,text>? = null;", "null")
        chkFn(": map<integer,text>? = [123:'Hello',456:'World'];", "map<integer,text>[int[123]=text[Hello],int[456]=text[World]]")
        chkFn(": map<integer,text>? = [123, 456];", "ct_err:entity_rettype:map<integer,text>?:list<integer>")
        chkFn(": map<integer,text>? = ['Hello', 'World'];", "ct_err:entity_rettype:map<integer,text>?:list<text>")
        chkFn(": map<integer,text>? = ['Hello':123,'World':456];", "ct_err:entity_rettype:map<integer,text>?:map<text,integer>")

        chkFn(": range = null;", "ct_err:entity_rettype:range:null")
        chkFn(": range? = null;", "null")
        chkFn(": range? = range(10);", "range[0,10,1]")
        chkFn(": range? = 123;", "ct_err:entity_rettype:range?:integer")
    }

    @Test fun testNullableNullable() {
        chkFn(": integer?? = 123;", "ct_err:type_nullable_nullable")
        chkFn("{ val x: integer?? = 123; }", "ct_err:type_nullable_nullable")
    }

    @Test fun testAssignment() {
        chkEx("{ val x: integer? = null; val y: integer = x; return y; }", "ct_err:stmt_var_type:y:integer:integer?")
        chkEx("{ val x: integer? = _nullable(123); val y: integer = x; return y; }", "ct_err:stmt_var_type:y:integer:integer?")
        chkEx("{ val x: integer = 123; val y: integer = x; return y; }", "int[123]")
        chkEx("{ val x: integer? = null; val y: integer? = x; return y; }", "null")
        chkEx("{ val x: integer? = _nullable(123); val y: integer? = x; return y; }", "int[123]")

        chkEx("{ var x: integer = 123; x = null; return x; }", "ct_err:stmt_assign_type:integer:null")
        chkEx("{ var x: integer? = _nullable(123); x = null; return x; }", "null")
    }

    @Test fun testNullComparison() {
        chkEx("{ val x: integer = 123; return x == null; }", "ct_err:binop_operand_type:==:integer:null")
        chkEx("{ val x: integer = 123; return x != null; }", "ct_err:binop_operand_type:!=:integer:null")

        chkEx("{ val x: integer? = _nullable(123); return x == null; }", "boolean[false]")
        chkEx("{ val x: integer? = null; return x == null; }", "boolean[true]")
        chkEx("{ val x: integer? = _nullable(123); return x != null; }", "boolean[true]")
        chkEx("{ val x: integer? = null; return x != null; }", "boolean[false]")

        chkEx("{ val x: integer? = null; return x < null; }", "ct_err:binop_operand_type:<:integer?:null")
        chkEx("{ val x: integer? = null; return x > null; }", "ct_err:binop_operand_type:>:integer?:null")
        chkEx("{ val x: integer? = null; return x <= null; }", "ct_err:binop_operand_type:<=:integer?:null")
        chkEx("{ val x: integer? = null; return x >= null; }", "ct_err:binop_operand_type:>=:integer?:null")

        chkEx("{ return null == null; }", "boolean[true]")
        chkEx("{ return null != null; }", "boolean[false]")
    }

    @Test fun testNullableClass() {
        tstCtx.useSql = true
        tst.defs = listOf("class user { name: text; }")
        chkOp("create user (name = 'Bob');")

        chkFn(": user = null;", "ct_err:entity_rettype:user:null")
        chkFn(": user = user @? { .name == 'Bob' };", "ct_err:entity_rettype:user:user?")
        chkFn(": user = user @ { .name == 'Bob' };", "user[1]")
        chkFn(": user = user @ { .name == 'Alice' };", "rt_err:at:wrong_count:0")

        chkFn(": user? = null;", "null")
        chkFn(": user? = user @? { .name == 'Bob' };", "user[1]")
        chkFn(": user? = user @? { .name == 'Alice' };", "null")
        chkFn(": user? = user @ { .name == 'Bob' };", "user[1]")
        chkFn(": user? = user @ { .name == 'Alice' };", "rt_err:at:wrong_count:0")
    }

    @Test fun testNullableAttribute() {
        tst.chkCompile("class user { name: text?; }", "ct_err:class_attr_type:name:text?")
    }

    @Test fun testTupleOfNullable() {
        chkFn(": (integer,text) = null;", "ct_err:entity_rettype:(integer,text):null")
        chkFn(": (integer,text) = (null, 'Hello');", "ct_err:entity_rettype:(integer,text):(null,text)")
        chkFn(": (integer,text) = (123, null);", "ct_err:entity_rettype:(integer,text):(integer,null)")
        chkFn(": (integer,text) = (123, 'Hello');", "(int[123],text[Hello])")

        chkFn(": (integer?,text) = null;", "ct_err:entity_rettype:(integer?,text):null")
        chkFn(": (integer?,text) = (null, 'Hello');", "(null,text[Hello])")
        chkFn(": (integer?,text) = (123, null);", "ct_err:entity_rettype:(integer?,text):(integer,null)")
        chkFn(": (integer?,text) = (123, 'Hello');", "(int[123],text[Hello])")

        chkFn(": (integer,text?) = null;", "ct_err:entity_rettype:(integer,text?):null")
        chkFn(": (integer,text?) = (null, 'Hello');", "ct_err:entity_rettype:(integer,text?):(null,text)")
        chkFn(": (integer,text?) = (123, null);", "(int[123],null)")
        chkFn(": (integer,text?) = (123, 'Hello');", "(int[123],text[Hello])")

        chkFn(": (integer?,text?) = null;", "ct_err:entity_rettype:(integer?,text?):null")
        chkFn(": (integer?,text?) = (null, 'Hello');", "(null,text[Hello])")
        chkFn(": (integer?,text?) = (123, null);", "(int[123],null)")
        chkFn(": (integer?,text?) = (123, 'Hello');", "(int[123],text[Hello])")
    }

    @Test fun testNullableTuple() {
        chkFn(": (integer,text)? = null;", "null")
        chkFn(": (integer,text)? = (null, 'Hello');", "ct_err:entity_rettype:(integer,text)?:(null,text)")
        chkFn(": (integer,text)? = (123, null);", "ct_err:entity_rettype:(integer,text)?:(integer,null)")
        chkFn(": (integer,text)? = (123, 'Hello');", "(int[123],text[Hello])")

        chkFn(": (integer?,text)? = null;", "null")
        chkFn(": (integer?,text)? = (null, 'Hello');", "(null,text[Hello])")
        chkFn(": (integer?,text)? = (123, null);", "ct_err:entity_rettype:(integer?,text)?:(integer,null)")
        chkFn(": (integer?,text)? = (123, 'Hello');", "(int[123],text[Hello])")

        chkFn(": (integer,text?)? = null;", "null")
        chkFn(": (integer,text?)? = (null, 'Hello');", "ct_err:entity_rettype:(integer,text?)?:(null,text)")
        chkFn(": (integer,text?)? = (123, null);", "(int[123],null)")
        chkFn(": (integer,text?)? = (123, 'Hello');", "(int[123],text[Hello])")

        chkFn(": (integer?,text?)? = null;", "null")
        chkFn(": (integer?,text?)? = (null, 'Hello');", "(null,text[Hello])")
        chkFn(": (integer?,text?)? = (123, null);", "(int[123],null)")
        chkFn(": (integer?,text?)? = (123, 'Hello');", "(int[123],text[Hello])")
    }

    @Test fun testNullableTuple2() {
        chkEx("{ val a = (123,'Hello'); val b: (integer?,text?)? = a; return b; }", "(int[123],text[Hello])")
        chkEx("{ val a = (123,'Hello'); val b: (integer?,text?)? = _nullable(a); return b; }", "(int[123],text[Hello])")
    }

    @Test fun testNullableTupleAssignment() {
        chkEx("{ val x: (integer,text) = (123,'Hello'); val y: (integer,text) = x; return y; }", "(int[123],text[Hello])")
        chkEx("{ val x: (integer?,text) = (123,'Hello'); val y: (integer,text) = x; return y; }",
                "ct_err:stmt_var_type:y:(integer,text):(integer?,text)")
        chkEx("{ val x: (integer,text?) = (123,'Hello'); val y: (integer,text) = x; return y; }",
                "ct_err:stmt_var_type:y:(integer,text):(integer,text?)")
        chkEx("{ val x: (integer?,text?) = (123,'Hello'); val y: (integer,text) = x; return y; }",
                "ct_err:stmt_var_type:y:(integer,text):(integer?,text?)")

        chkEx("{ val x: (integer,text) = (123,'Hello'); val y: (integer?,text) = x; return y; }", "(int[123],text[Hello])")
        chkEx("{ val x: (integer?,text) = (123,'Hello'); val y: (integer?,text) = x; return y; }", "(int[123],text[Hello])")
        chkEx("{ val x: (integer?,text) = (null,'Hello'); val y: (integer?,text) = x; return y; }", "(null,text[Hello])")
        chkEx("{ val x: (integer,text?) = (123,'Hello'); val y: (integer?,text) = x; return y; }",
                "ct_err:stmt_var_type:y:(integer?,text):(integer,text?)")
        chkEx("{ val x: (integer?,text?) = (123,'Hello'); val y: (integer?,text) = x; return y; }",
                "ct_err:stmt_var_type:y:(integer?,text):(integer?,text?)")

        chkEx("{ val x: (integer,text) = (123,'Hello'); val y: (integer,text?) = x; return y; }", "(int[123],text[Hello])")
        chkEx("{ val x: (integer?,text) = (123,'Hello'); val y: (integer,text?) = x; return y; }",
                "ct_err:stmt_var_type:y:(integer,text?):(integer?,text)")
        chkEx("{ val x: (integer,text?) = (123,'Hello'); val y: (integer,text?) = x; return y; }", "(int[123],text[Hello])")
        chkEx("{ val x: (integer,text?) = (123,null); val y: (integer,text?) = x; return y; }", "(int[123],null)")
        chkEx("{ val x: (integer?,text?) = (123,'Hello'); val y: (integer,text?) = x; return y; }",
                "ct_err:stmt_var_type:y:(integer,text?):(integer?,text?)")

        chkEx("{ val x: (integer,text) = (123,'Hello'); val y: (integer?,text?) = x; return y; }", "(int[123],text[Hello])")
        chkEx("{ val x: (integer?,text) = (123,'Hello'); val y: (integer?,text?) = x; return y; }", "(int[123],text[Hello])")
        chkEx("{ val x: (integer?,text) = (null,'Hello'); val y: (integer?,text?) = x; return y; }", "(null,text[Hello])")
        chkEx("{ val x: (integer,text?) = (123,'Hello'); val y: (integer?,text?) = x; return y; }", "(int[123],text[Hello])")
        chkEx("{ val x: (integer,text?) = (123,null); val y: (integer?,text?) = x; return y; }", "(int[123],null)")
        chkEx("{ val x: (integer?,text?) = (123,'Hello'); val y: (integer?,text?) = x; return y; }", "(int[123],text[Hello])")
        chkEx("{ val x: (integer?,text?) = (null,'Hello'); val y: (integer?,text?) = x; return y; }", "(null,text[Hello])")
        chkEx("{ val x: (integer?,text?) = (123,null); val y: (integer?,text?) = x; return y; }", "(int[123],null)")
        chkEx("{ val x: (integer?,text?) = (null,null); val y: (integer?,text?) = x; return y; }", "(null,null)")
    }

    @Test fun testListLiteral() {
        tst.strictToString = false
        chk("_type_of([null])", "list<null>")
        chk("_type_of([123])", "list<integer>")
        chk("_type_of([123,null])", "list<integer?>")
        chk("_type_of([null,123])", "list<integer?>")
        chk("_type_of(['Hello',null])", "list<text?>")
        chk("_type_of([null,'Hello'])", "list<text?>")
        chk("_type_of([123,null,456])", "list<integer?>")
        chk("_type_of([null,123,456])", "list<integer?>")
        chk("_type_of(['Hello',null,123])", "ct_err:expr_list_itemtype:text?:integer")
        chk("_type_of([123,null,'Hello'])", "ct_err:expr_list_itemtype:integer?:text")
    }

    @Test fun testMapLiteral() {
        tst.strictToString = false
        chk("_type_of([null:null])", "map<null,null>")
        chk("_type_of([123:null])", "map<integer,null>")
        chk("_type_of([null:'Hello'])", "map<null,text>")

        chk("_type_of([123:'Hello',null:'World'])", "map<integer?,text>")
        chk("_type_of([null:'Hello',123:'World'])", "map<integer?,text>")
        chk("_type_of([123:'Hello',456:null])", "map<integer,text?>")
        chk("_type_of([123:null,456:'Hello'])", "map<integer,text?>")

        chk("_type_of([123:null,null:'Hello'])", "map<integer?,text?>")
        chk("_type_of([null:'Hello',123:null])", "map<integer?,text?>")
        chk("_type_of([123:'Hello',null:null])", "map<integer?,text?>")

        chk("_type_of([123:'Hello',null:null,456:'World'])", "map<integer?,text?>")
        chk("_type_of([null:null,123:'Hello',456:'World'])", "map<integer?,text?>")

        chk("_type_of([123:'Hello',null:null,'World':456])", "ct_err:expr_map_keytype:integer?:text")
        chk("_type_of([123:null,456:789,null:'World'])", "ct_err:expr_map_valuetype:integer?:text")
        chk("_type_of([null:'Hello','Hi':'World',123:'Bye'])", "ct_err:expr_map_keytype:text?:integer")
    }

    @Test fun testListLiteralOfTuples() {
        tst.strictToString = false
        chkEx("{ return _type_of([(1,'A')]); }", "list<(integer,text)>")

        chkEx("{ return _type_of([(1,'A'), (null,'B')]); }", "list<(integer?,text)>")
        chkEx("{ return _type_of([(1,'A'), (2,null)]); }", "list<(integer,text?)>")
        chkEx("{ return _type_of([(1,'A'), (null,null)]); }", "list<(integer?,text?)>")
        chkEx("{ return _type_of([(null,'A'), (2,'B')]); }", "list<(integer?,text)>")
        chkEx("{ return _type_of([(1,null), (2,'B')]); }", "list<(integer,text?)>")
        chkEx("{ return _type_of([(null,null), (2,'B')]); }", "list<(integer?,text?)>")

        chkEx("{ return _type_of([(1,'A'), (null,'B'), null]); }", "list<(integer?,text)?>")
        chkEx("{ return _type_of([(1,'A'), (2,null), null]); }", "list<(integer,text?)?>")
        chkEx("{ return _type_of([(1,'A'), (null,null), null]); }", "list<(integer?,text?)?>")
        chkEx("{ return _type_of([(null,'A'), (2,'B'), null]); }", "list<(integer?,text)?>")
        chkEx("{ return _type_of([(1,null), (2,'B'), null]); }", "list<(integer,text?)?>")
        chkEx("{ return _type_of([(null,null), (2,'B'), null]); }", "list<(integer?,text?)?>")
    }

    @Test fun testListBasics() {
        tst.strictToString = false
        chkEx("{ val x = list<integer>(); x.add(null); return ''+x; }", "ct_err:expr_call_argtypes:list<integer>.add:null")
        chkEx("{ val x = list<integer>(); x.add(123); return ''+x; }", "[123]")
        chkEx("{ val x = list<integer?>(); x.add(null); return ''+x; }", "[null]")
        chkEx("{ val x = list<integer?>(); x.add(123); return ''+x; }", "[123]")

        chkEx("{ val x = list<integer>([123]); x[0] = null; return ''+x; }", "ct_err:stmt_assign_type:integer:null")
        chkEx("{ val x = list<integer>([123]); x[0] = 456; return ''+x; }", "[456]")
        chkEx("{ val x = list<integer?>([123]); x[0] = null; return ''+x; }", "[null]")
        chkEx("{ val x = list<integer?>([123]); x[0] = 456; return ''+x; }", "[456]")

        chkEx("{ val x = list<integer>([123]); return x.contains(null); }", "ct_err:expr_call_argtypes:list<integer>.contains:null")
        chkEx("{ val x = list<integer>([123]); return x.contains(123); }", "true")
        chkEx("{ val x = list<integer?>([123]); return x.contains(null); }", "false")
        chkEx("{ val x = list<integer?>([123]); x.add(null); return x.contains(null); }", "true")
        chkEx("{ val x = list<integer?>([123]); return x.contains(123); }", "true")

        chkEx("{ val x = list<integer>([123]); val y: list<integer?> = x; return ''+y; }",
                "ct_err:stmt_var_type:y:list<integer?>:list<integer>")
        chkEx("{ val x = list<integer?>([123]); val y: list<integer> = x; return ''+y; }",
                "ct_err:stmt_var_type:y:list<integer>:list<integer?>")
        chkEx("{ val x = list<integer?>([123]); val y: list<integer?> = x; return ''+y; }", "[123]")
    }

    @Test fun testListAssignment() {
        tst.strictToString = false
        chkEx("{ val x: list<integer> = list<integer>(); return ''+x; }", "[]")
        chkEx("{ val x: list<integer> = list<integer?>(); return ''+x; }",
                "ct_err:stmt_var_type:x:list<integer>:list<integer?>")
        chkEx("{ val x: list<integer?> = list<integer>(); return ''+x; }",
                "ct_err:stmt_var_type:x:list<integer?>:list<integer>")
        chkEx("{ val x: list<integer?> = list<integer?>(); return ''+x; }", "[]")
    }

    @Test fun testSetBasics() {
        tst.strictToString = false
        chkEx("{ val x = set<integer>(); x.add(null); return ''+x; }", "ct_err:expr_call_argtypes:set<integer>.add:null")
        chkEx("{ val x = set<integer>(); x.add(123); return ''+x; }", "[123]")
        chkEx("{ val x = set<integer?>(); x.add(null); return ''+x; }", "[null]")
        chkEx("{ val x = set<integer?>(); x.add(123); return ''+x; }", "[123]")

        chkEx("{ val x = set<integer>([123]); return x.contains(null); }", "ct_err:expr_call_argtypes:set<integer>.contains:null")
        chkEx("{ val x = set<integer>([123]); return x.contains(123); }", "true")
        chkEx("{ val x = set<integer?>([123]); return x.contains(null); }", "false")
        chkEx("{ val x = set<integer?>([123]); x.add(null); return x.contains(null); }", "true")
        chkEx("{ val x = set<integer?>([123]); return x.contains(123); }", "true")
    }

    @Test fun testListOpAll() {
        tst.strictToString = false
        chkEx("{ val x = list<integer?>(); x.add_all(list<integer>([123])); return ''+x; }", "[123]")
        chkEx("{ val x = list<integer>(); x.add_all(list<integer?>([123])); return ''+x; }",
                "ct_err:expr_call_argtypes:list<integer>.add_all:list<integer?>")
        chkEx("{ val x = list<integer?>(); x.add_all(list<integer?>([123,null])); return ''+x; }", "[123, null]")

        chkEx("{ val x = list<integer>([123,456]); x.remove_all(list<integer>([123])); return ''+x; }", "[456]")
        chkEx("{ val x = list<integer>([123,456]); x.remove_all(list<integer?>([123])); return ''+x; }",
                "ct_err:expr_call_argtypes:list<integer>.remove_all:list<integer?>")
        chkEx("{ val x = list<integer?>([123,456,null]); x.remove_all(list<integer>([123])); return ''+x; }", "[456, null]")
        chkEx("{ val x = list<integer?>([123,456,null]); x.remove_all(list<integer?>([123,null])); return ''+x; }", "[456]")

        chkEx("{ val x = list<integer>([123,456]); return x.contains_all(list<integer>([123])); }", "true")
        chkEx("{ val x = list<integer>([123,456]); return x.contains_all(list<integer?>([123])); }",
                "ct_err:expr_call_argtypes:list<integer>.contains_all:list<integer?>")
        chkEx("{ val x = list<integer?>([123,456,null]); return x.contains_all(list<integer>([123])); }", "true")
        chkEx("{ val x = list<integer?>([123,456,null]); return x.contains_all(list<integer?>([123,null])); }", "true")
    }

    @Test fun testSetOpAll() {
        tst.strictToString = false
        chkEx("{ val x = set<integer>(); x.add_all(set<integer>([123])); return ''+x; }", "[123]")
        chkEx("{ val x = set<integer>(); x.add_all(set<integer?>([123])); return ''+x; }",
                "ct_err:expr_call_argtypes:set<integer>.add_all:set<integer?>")
        chkEx("{ val x = set<integer?>(); x.add_all(set<integer>([123])); return ''+x; }", "[123]")
        chkEx("{ val x = set<integer?>(); x.add_all(set<integer?>([123,null])); return ''+x; }", "[123, null]")

        chkEx("{ val x = set<integer>([123,456]); x.remove_all(set<integer>([123])); return ''+x; }", "[456]")
        chkEx("{ val x = set<integer>([123,456]); x.remove_all(set<integer?>([123])); return ''+x; }",
                "ct_err:expr_call_argtypes:set<integer>.remove_all:set<integer?>")
        chkEx("{ val x = set<integer?>([123,456,null]); x.remove_all(set<integer>([123])); return ''+x; }", "[456, null]")
        chkEx("{ val x = set<integer?>([123,456,null]); x.remove_all(set<integer?>([123,null])); return ''+x; }", "[456]")

        chkEx("{ val x = set<integer>([123,456]); return x.contains_all(set<integer>([123])); }", "true")
        chkEx("{ val x = set<integer>([123,456]); return x.contains_all(set<integer?>([123])); }",
                "ct_err:expr_call_argtypes:set<integer>.contains_all:set<integer?>")
        chkEx("{ val x = set<integer?>([123,456,null]); return x.contains_all(set<integer>([123])); }", "true")
        chkEx("{ val x = set<integer?>([123,456,null]); return x.contains_all(set<integer?>([123,null])); }", "true")
    }

    @Test fun testMapGet() {
        tst.strictToString = false
        chkEx("{ val x = map<integer,text>([123:'Hello']); return x[null];}", "ct_err:expr_lookup_keytype:integer:null")
        chkEx("{ val x = map<integer?,text>([123:'Hello']); return x[null];}", "rt_err:fn_map_get_novalue:null")
        chkEx("{ val x = map<integer?,text>([123:'Hello']); x.put(null,'World'); return x[null];}", "World")
        chkEx("{ val x = map<integer,text?>([123:'Hello']); return x[null];}", "ct_err:expr_lookup_keytype:integer:null")
        chkEx("{ val x = map<integer?,text?>([123:'Hello']); return x[null];}", "rt_err:fn_map_get_novalue:null")
        chkEx("{ val x = map<integer?,text?>([123:'Hello']); x.put(null,'World'); return x[null];}", "World")
    }

    @Test fun testMapPut() {
        tst.strictToString = false
        chkEx("{ val x = map<integer,text>(); x.put(123,'Hello'); return ''+x;}", "{123=Hello}")
        chkEx("{ val x = map<integer,text>(); x.put(null,'Hello'); return ''+x;}",
                "ct_err:expr_call_argtypes:map<integer,text>.put:null,text")
        chkEx("{ val x = map<integer,text>(); x.put(123,null); return ''+x;}",
                "ct_err:expr_call_argtypes:map<integer,text>.put:integer,null")
        chkEx("{ val x = map<integer,text>(); x.put(null,null); return ''+x;}",
                "ct_err:expr_call_argtypes:map<integer,text>.put:null,null")
        chkEx("{ val x = map<integer,text>(); x[123]='Hello'; return ''+x;}", "{123=Hello}")
        chkEx("{ val x = map<integer,text>(); x[null]='Hello'; return ''+x;}", "ct_err:expr_lookup_keytype:integer:null")
        chkEx("{ val x = map<integer,text>(); x[123]=null; return ''+x;}", "ct_err:stmt_assign_type:text:null")
        chkEx("{ val x = map<integer,text>(); x[null]=null; return ''+x;}", "ct_err:expr_lookup_keytype:integer:null")

        chkEx("{ val x = map<integer?,text>(); x.put(123,'Hello'); return ''+x;}", "{123=Hello}")
        chkEx("{ val x = map<integer?,text>(); x.put(null,'Hello'); return ''+x;}", "{null=Hello}")
        chkEx("{ val x = map<integer?,text>(); x.put(123,null); return ''+x;}",
                "ct_err:expr_call_argtypes:map<integer?,text>.put:integer,null")
        chkEx("{ val x = map<integer?,text>(); x.put(null,null); return ''+x;}",
                "ct_err:expr_call_argtypes:map<integer?,text>.put:null,null")
        chkEx("{ val x = map<integer?,text>(); x[123]='Hello'; return ''+x;}", "{123=Hello}")
        chkEx("{ val x = map<integer?,text>(); x[null]='Hello'; return ''+x;}", "{null=Hello}")
        chkEx("{ val x = map<integer?,text>(); x[123]=null; return ''+x;}", "ct_err:stmt_assign_type:text:null")
        chkEx("{ val x = map<integer?,text>(); x[null]=null; return ''+x;}", "ct_err:stmt_assign_type:text:null")

        chkEx("{ val x = map<integer,text?>(); x.put(123,'Hello'); return ''+x;}", "{123=Hello}")
        chkEx("{ val x = map<integer,text?>(); x.put(null,'Hello'); return ''+x;}",
                "ct_err:expr_call_argtypes:map<integer,text?>.put:null,text")
        chkEx("{ val x = map<integer,text?>(); x.put(123,null); return ''+x;}", "{123=null}")
        chkEx("{ val x = map<integer,text?>(); x.put(null,null); return ''+x;}",
                "ct_err:expr_call_argtypes:map<integer,text?>.put:null,null")
        chkEx("{ val x = map<integer,text?>(); x[123]='Hello'; return ''+x;}", "{123=Hello}")
        chkEx("{ val x = map<integer,text?>(); x[null]='Hello'; return ''+x;}", "ct_err:expr_lookup_keytype:integer:null")
        chkEx("{ val x = map<integer,text?>(); x[123]=null; return ''+x;}", "{123=null}")
        chkEx("{ val x = map<integer,text?>(); x[null]=null; return ''+x;}", "ct_err:expr_lookup_keytype:integer:null")

        chkEx("{ val x = map<integer?,text?>(); x.put(123,'Hello'); return ''+x;}", "{123=Hello}")
        chkEx("{ val x = map<integer?,text?>(); x.put(null,'Hello'); return ''+x;}", "{null=Hello}")
        chkEx("{ val x = map<integer?,text?>(); x.put(123,null); return ''+x;}", "{123=null}")
        chkEx("{ val x = map<integer?,text?>(); x.put(null,null); return ''+x;}", "{null=null}")
        chkEx("{ val x = map<integer?,text?>(); x[123]='Hello'; return ''+x;}", "{123=Hello}")
        chkEx("{ val x = map<integer?,text?>(); x[null]='Hello'; return ''+x;}", "{null=Hello}")
        chkEx("{ val x = map<integer?,text?>(); x[123]=null; return ''+x;}", "{123=null}")
        chkEx("{ val x = map<integer?,text?>(); x[null]=null; return ''+x;}", "{null=null}")
    }

    @Test fun testMapPutAll() {
        tst.strictToString = false
        chkEx("{ val x = map<integer,text>(); x.put_all(map<integer,text>([123:'Hello'])); return ''+x;}", "{123=Hello}")
        chkEx("{ val x = map<integer,text>(); x.put_all(map<integer?,text>([123:'Hello'])); return ''+x;}",
                "ct_err:expr_call_argtypes:map<integer,text>.put_all:map<integer?,text>")
        chkEx("{ val x = map<integer,text>(); x.put_all(map<integer,text?>([123:'Hello'])); return ''+x;}",
                "ct_err:expr_call_argtypes:map<integer,text>.put_all:map<integer,text?>")
        chkEx("{ val x = map<integer,text>(); x.put_all(map<integer?,text?>([123:'Hello'])); return ''+x;}",
                "ct_err:expr_call_argtypes:map<integer,text>.put_all:map<integer?,text?>")

        chkEx("{ val x = map<integer?,text>(); x.put_all(map<integer,text>([123:'Hello'])); return ''+x;}", "{123=Hello}")
        chkEx("{ val x = map<integer?,text>(); x.put_all(map<integer?,text>([123:'Hello'])); return ''+x;}", "{123=Hello}")
        chkEx("{ val x = map<integer?,text>(); x.put_all(map<integer,text?>([123:'Hello'])); return ''+x;}",
                "ct_err:expr_call_argtypes:map<integer?,text>.put_all:map<integer,text?>")
        chkEx("{ val x = map<integer?,text>(); x.put_all(map<integer?,text?>([123:'Hello'])); return ''+x;}",
                "ct_err:expr_call_argtypes:map<integer?,text>.put_all:map<integer?,text?>")

        chkEx("{ val x = map<integer,text?>(); x.put_all(map<integer,text>([123:'Hello'])); return ''+x;}", "{123=Hello}")
        chkEx("{ val x = map<integer,text?>(); x.put_all(map<integer?,text>([123:'Hello'])); return ''+x;}",
                "ct_err:expr_call_argtypes:map<integer,text?>.put_all:map<integer?,text>")
        chkEx("{ val x = map<integer,text?>(); x.put_all(map<integer,text?>([123:'Hello'])); return ''+x;}", "{123=Hello}")
        chkEx("{ val x = map<integer,text?>(); x.put_all(map<integer?,text?>([123:'Hello'])); return ''+x;}",
                "ct_err:expr_call_argtypes:map<integer,text?>.put_all:map<integer?,text?>")

        chkEx("{ val x = map<integer?,text?>(); x.put_all(map<integer,text>([123:'Hello'])); return ''+x;}", "{123=Hello}")
        chkEx("{ val x = map<integer?,text?>(); x.put_all(map<integer?,text>([123:'Hello'])); return ''+x;}", "{123=Hello}")
        chkEx("{ val x = map<integer?,text?>(); x.put_all(map<integer,text?>([123:'Hello'])); return ''+x;}", "{123=Hello}")
        chkEx("{ val x = map<integer?,text?>(); x.put_all(map<integer?,text?>([123:'Hello'])); return ''+x;}", "{123=Hello}")
    }

    @Test fun testCollectionConstructor() {
        chk("list<integer>(list<integer>())", "list<integer>[]")
        chk("list<integer>(list<integer?>())", "ct_err:expr_list_typemiss:integer:integer?")
        chk("list<integer>(set<integer>())", "list<integer>[]")
        chk("list<integer>(set<integer?>())", "ct_err:expr_list_typemiss:integer:integer?")

        chk("list<integer?>(list<integer>())", "list<integer?>[]")
        chk("list<integer?>(list<integer?>())", "list<integer?>[]")
        chk("list<integer?>(set<integer>())", "list<integer?>[]")
        chk("list<integer?>(set<integer?>())", "list<integer?>[]")

        chk("set<integer>(list<integer>())", "set<integer>[]")
        chk("set<integer>(list<integer?>())", "ct_err:expr_set_typemiss:integer:integer?")
        chk("set<integer>(set<integer>())", "set<integer>[]")
        chk("set<integer>(set<integer?>())", "ct_err:expr_set_typemiss:integer:integer?")

        chk("set<integer?>(list<integer>())", "set<integer?>[]")
        chk("set<integer?>(list<integer?>())", "set<integer?>[]")
        chk("set<integer?>(set<integer>())", "set<integer?>[]")
        chk("set<integer?>(set<integer?>())", "set<integer?>[]")

        tst.strictToString = false

        chkEx("{ val x = list<integer?>([123]); val y = list<integer>(x); return ''+y; }", "ct_err:expr_list_typemiss:integer:integer?")
        chkEx("{ val x = list<integer>([123]); val y = list<integer?>(x); return ''+y; }", "[123]")
        chkEx("{ val x = list<integer?>([123]); val y = list<integer?>(x); return ''+y; }", "[123]")
        chkEx("{ val x = set<integer?>([123]); val y = list<integer>(x); return ''+y; }", "ct_err:expr_list_typemiss:integer:integer?")
        chkEx("{ val x = set<integer>([123]); val y = list<integer?>(x); return ''+y; }", "[123]")
        chkEx("{ val x = set<integer?>([123]); val y = list<integer?>(x); return ''+y; }", "[123]")

        chkEx("{ val x = list<integer?>([123]); val y = set<integer>(x); return ''+y; }", "ct_err:expr_set_typemiss:integer:integer?")
        chkEx("{ val x = list<integer>([123]); val y = set<integer?>(x); return ''+y; }", "[123]")
        chkEx("{ val x = list<integer?>([123]); val y = set<integer?>(x); return ''+y; }", "[123]")
        chkEx("{ val x = set<integer?>([123]); val y = set<integer>(x); return ''+y; }", "ct_err:expr_set_typemiss:integer:integer?")
        chkEx("{ val x = set<integer>([123]); val y = set<integer?>(x); return ''+y; }", "[123]")
        chkEx("{ val x = set<integer?>([123]); val y = set<integer?>(x); return ''+y; }", "[123]")
    }

    @Test fun testMapConstructor() {
        chk("map<integer,text>(map<integer,text>())", "map<integer,text>[]")
        chk("map<integer,text>(map<integer?,text>())", "ct_err:expr_map_key_typemiss:integer:integer?")
        chk("map<integer,text>(map<integer,text?>())", "ct_err:expr_map_value_typemiss:text:text?")
        chk("map<integer,text>(map<integer?,text?>())", "ct_err:expr_map_key_typemiss:integer:integer?")

        chk("map<integer?,text>(map<integer,text>())", "map<integer?,text>[]")
        chk("map<integer?,text>(map<integer?,text>())", "map<integer?,text>[]")
        chk("map<integer?,text>(map<integer,text?>())", "ct_err:expr_map_value_typemiss:text:text?")
        chk("map<integer?,text>(map<integer?,text?>())", "ct_err:expr_map_value_typemiss:text:text?")

        chk("map<integer,text?>(map<integer,text>())", "map<integer,text?>[]")
        chk("map<integer,text?>(map<integer?,text>())", "ct_err:expr_map_key_typemiss:integer:integer?")
        chk("map<integer,text?>(map<integer,text?>())", "map<integer,text?>[]")
        chk("map<integer,text?>(map<integer?,text?>())", "ct_err:expr_map_key_typemiss:integer:integer?")

        chk("map<integer?,text?>(map<integer,text>())", "map<integer?,text?>[]")
        chk("map<integer?,text?>(map<integer?,text>())", "map<integer?,text?>[]")
        chk("map<integer?,text?>(map<integer,text?>())", "map<integer?,text?>[]")
        chk("map<integer?,text?>(map<integer?,text?>())", "map<integer?,text?>[]")

        tst.strictToString = false

        chkEx("{ val x = map<integer,text>([123:'Hello']); val y = map<integer,text>(x); return ''+y; }", "{123=Hello}")
        chkEx("{ val x = map<integer?,text>([123:'Hello']); val y = map<integer,text>(x); return ''+y; }",
                "ct_err:expr_map_key_typemiss:integer:integer?")
        chkEx("{ val x = map<integer,text?>([123:'Hello']); val y = map<integer,text>(x); return ''+y; }",
                "ct_err:expr_map_value_typemiss:text:text?")
        chkEx("{ val x = map<integer?,text?>([123:'Hello']); val y = map<integer,text>(x); return ''+y; }",
                "ct_err:expr_map_key_typemiss:integer:integer?")

        chkEx("{ val x = map<integer,text>([123:'Hello']); val y = map<integer?,text>(x); return ''+y; }", "{123=Hello}")
        chkEx("{ val x = map<integer?,text>([123:'Hello']); val y = map<integer?,text>(x); return ''+y; }", "{123=Hello}")
        chkEx("{ val x = map<integer,text?>([123:'Hello']); val y = map<integer?,text>(x); return ''+y; }",
                "ct_err:expr_map_value_typemiss:text:text?")
        chkEx("{ val x = map<integer?,text?>([123:'Hello']); val y = map<integer?,text>(x); return ''+y; }",
                "ct_err:expr_map_value_typemiss:text:text?")

        chkEx("{ val x = map<integer,text>([123:'Hello']); val y = map<integer,text?>(x); return ''+y; }", "{123=Hello}")
        chkEx("{ val x = map<integer?,text>([123:'Hello']); val y = map<integer,text?>(x); return ''+y; }",
                "ct_err:expr_map_key_typemiss:integer:integer?")
        chkEx("{ val x = map<integer,text?>([123:'Hello']); val y = map<integer,text?>(x); return ''+y; }", "{123=Hello}")
        chkEx("{ val x = map<integer?,text?>([123:'Hello']); val y = map<integer,text?>(x); return ''+y; }",
                "ct_err:expr_map_key_typemiss:integer:integer?")

        chkEx("{ val x = map<integer,text>([123:'Hello']); val y = map<integer?,text?>(x); return ''+y; }", "{123=Hello}")
        chkEx("{ val x = map<integer?,text>([123:'Hello']); val y = map<integer?,text?>(x); return ''+y; }", "{123=Hello}")
        chkEx("{ val x = map<integer,text?>([123:'Hello']); val y = map<integer?,text?>(x); return ''+y; }", "{123=Hello}")
        chkEx("{ val x = map<integer?,text?>([123:'Hello']); val y = map<integer?,text?>(x); return ''+y; }", "{123=Hello}")
    }

    @Test fun testCollectionAssignment() {
        tst.strictToString = false
        chkEx("{ val x: list<integer?> = list<integer>([123]); return ''+x; }", "ct_err:stmt_var_type:x:list<integer?>:list<integer>")
        chkEx("{ val x: list<integer?> = list<integer?>([123]); return ''+x; }", "[123]")

        chkEx("{ val x: set<integer?> = set<integer>([123]); return ''+x; }", "ct_err:stmt_var_type:x:set<integer?>:set<integer>")
        chkEx("{ val x: set<integer?> = set<integer?>([123]); return ''+x; }", "[123]")

        chkEx("{ val x: map<integer,text> = map<integer,text>([123:'Hello']); return ''+x; }", "{123=Hello}")
        chkEx("{ val x: map<integer,text> = map<integer?,text>([123:'Hello']); return ''+x; }",
                "ct_err:stmt_var_type:x:map<integer,text>:map<integer?,text>")
        chkEx("{ val x: map<integer,text> = map<integer,text?>([123:'Hello']); return ''+x; }",
                "ct_err:stmt_var_type:x:map<integer,text>:map<integer,text?>")
        chkEx("{ val x: map<integer,text> = map<integer?,text?>([123:'Hello']); return ''+x; }",
                "ct_err:stmt_var_type:x:map<integer,text>:map<integer?,text?>")
        chkEx("{ val x: map<integer?,text> = map<integer,text>([123:'Hello']); return ''+x; }",
                "ct_err:stmt_var_type:x:map<integer?,text>:map<integer,text>")
        chkEx("{ val x: map<integer,text?> = map<integer,text>([123:'Hello']); return ''+x; }",
                "ct_err:stmt_var_type:x:map<integer,text?>:map<integer,text>")
        chkEx("{ val x: map<integer?,text?> = map<integer,text>([123:'Hello']); return ''+x; }",
                "ct_err:stmt_var_type:x:map<integer?,text?>:map<integer,text>")
    }

    @Test fun testBasicOperators() {
        tstOperErr("integer", "==")
        tstOperErr("integer", "!=")
        tstOperErr("integer", "<")
        tstOperErr("integer", ">")
        tstOperErr("integer", "<=")
        tstOperErr("integer", ">=")

        tstOperErr("integer", "+")
        tstOperErr("integer", "-")
        tstOperErr("integer", "/")
        tstOperErr("integer", "*")
        tstOperErr("integer", "%")

        tstOperErr("boolean", "and")
        tstOperErr("boolean", "or")

        chkEx("{ var x: boolean?; return not x; }", "ct_err:unop_operand_type:not:boolean?")
        chkEx("{ var x: integer?; return +x; }", "ct_err:unop_operand_type:+:integer?")
        chkEx("{ var x: integer?; return -x; }", "ct_err:unop_operand_type:-:integer?")

        chkEx("{ var x: integer? = _nullable(123); return x in [123, 456]; }",
                "ct_err:binop_operand_type:in:integer?:list<integer>")
        chkEx("{ var x: list<integer>? = _nullable([123]); return 123 in x; }",
                "ct_err:binop_operand_type:in:integer:list<integer>?")
    }

    @Test fun testEq() {
        chkEx("{ val a: integer? = _nullable(123); return a == null; }", "boolean[false]")
        chkEx("{ val a: integer? = _nullable(123); return a != null; }", "boolean[true]")
        chkEx("{ val a: integer? = _nullable(123); return a == 123; }", "ct_err:binop_operand_type:==:integer?:integer")
        chkEx("{ val a: integer? = _nullable(123); return a != 123; }", "ct_err:binop_operand_type:!=:integer?:integer")
        chkEx("{ val a: integer? = null; return a == null; }", "boolean[true]")
        chkEx("{ val a: integer? = null; return a != null; }", "boolean[false]")
        chkEx("{ val a: integer? = null; return a == 123; }", "ct_err:binop_operand_type:==:integer?:integer")
        chkEx("{ val a: integer? = null; return a != 123; }", "ct_err:binop_operand_type:!=:integer?:integer")

        chkEx("{ val a: integer? = _nullable(123); val b: integer? = _nullable(123); return a == b; }", "boolean[true]")
        chkEx("{ val a: integer? = _nullable(123); val b: integer? = _nullable(123); return a != b; }", "boolean[false]")
        chkEx("{ val a: integer? = _nullable(123); val b: integer? = _nullable(456); return a == b; }", "boolean[false]")
        chkEx("{ val a: integer? = _nullable(123); val b: integer? = _nullable(456); return a != b; }", "boolean[true]")
        chkEx("{ val a: integer? = _nullable(123); val b: integer? = null; return a == b; }", "boolean[false]")
        chkEx("{ val a: integer? = _nullable(123); val b: integer? = null; return a != b; }", "boolean[true]")
    }

    @Test fun testMemberAccess() {
        chkEx("{ var x: text?; return x.size(); }", "ct_err:expr_mem_null:size")
        chkEx("{ var x: (a:integer)?; return x.a; }", "ct_err:expr_mem_null:a")
        chkEx("{ var x: (a:integer)?; return x.b; }", "ct_err:unknown_member:(a:integer):b")
        chkEx("{ var x: (a:integer); return x.a; }", "ct_err:expr_var_uninit:x")
        chkEx("{ var x: list<integer>? = _nullable([1]); return x[0]; }", "ct_err:expr_lookup_null")
        chkEx("{ var x: integer? = _nullable(123); val y = [1, 2, 3]; return y[x]; }",
                "ct_err:expr_lookup_keytype:integer:integer?")
        chkEx("{ val x: integer? = _nullable(123); return x.to_hex(); }", "ct_err:expr_mem_null:to_hex")
    }

    private fun tstOperErr(type: String, op: String) {
        chkQueryEx("query q(x: $type?, y: $type) = x $op y;", "ct_err:binop_operand_type:$op:$type?:$type")
        chkQueryEx("query q(x: $type?, y: $type) = y $op x;", "ct_err:binop_operand_type:$op:$type:$type?")
    }

    @Test fun testSpecOpElvis() {
        chkEx("{ val x: integer? = _nullable(123); return x ?: 456; }", "int[123]")
        chkEx("{ val x: integer? = null; return x ?: 456; }", "int[456]")
        chkEx("{ val x: integer? = _nullable(123); return x ?: null; }", "int[123]")
        chkEx("{ val x: integer? = null; return x ?: null; }", "null")
        chkEx("{ val x: integer? = _nullable(123); val y: integer? = _nullable(456); return x ?: y ?: 789; }", "int[123]")
        chkEx("{ val x: integer? = null; val y: integer? = _nullable(456); return x ?: y ?: 789; }", "int[456]")
        chkEx("{ val x: integer? = null; val y: integer? = null; return x ?: y ?: 789; }", "int[789]")
        chkEx("{ return null ?: 123; }", "int[123]")

        chkEx("{ val x: integer = 123; return x ?: 456; }", "ct_err:binop_operand_type:?::integer:integer")
        chkEx("{ val x: integer? = _nullable(123); return x ?: 'Hello'; }", "ct_err:binop_operand_type:?::integer?:text")
        chkEx("{ val x: integer? = _nullable(123); return x ?: 'Hello'; }", "ct_err:binop_operand_type:?::integer?:text")

        // Short-circuit evaluation
        chkEx("{ val x: integer? = _nullable(123); return x ?: 1/0; }", "int[123]")
        chkEx("{ val x: integer? = null; return x ?: 1/0; }", "rt_err:expr_div_by_zero")
    }

    @Test fun testSpecOpElvisUnderAt() {
        tstCtx.useSql = true
        tst.defs = listOf("class user { name: text; }")
        chkOp("create user(name = 'Bob'); create user(name = 'Alice');")

        chkEx("{ val s: text? = _nullable('Bob'); return user @ { .name == s ?: 'Alice' }; }", "user[1]")
        chkEx("{ val s: text? = null; return user @ { .name == s ?: 'Alice' }; }", "user[2]")

        chkEx("{ val s: text = 'Bob'; return user @ { .name ?: 'Alice' == s }; }", "ct_err:binop_operand_type:?::text:text")
    }

    @Test fun testSpecOpNotNull() {
        tst.defs = listOf("function nop(x: integer?): integer? = x;")

        chkEx("{ val x: integer? = nop(123); return _type_of(x); }", "text[integer?]")
        chkEx("{ val x: integer? = nop(123); return _type_of(x!!); }", "text[integer]")

        chkEx("{ val x: integer? = nop(123); return x!!; }", "int[123]")
        chkEx("{ val x: integer? = nop(null); return x!!; }", "rt_err:null_value")
        chkEx("{ val x: integer? = nop(123); return x.to_hex(); }", "ct_err:expr_mem_null:to_hex")
        chkEx("{ val x: integer? = nop(123); return x!!.to_hex(); }", "text[7b]")
        chkEx("{ val x: integer? = nop(null); return x!!.to_hex(); }", "rt_err:null_value")
        chkEx("{ val x: integer = 123; return x!!; }", "ct_err:unop_operand_type:!!:integer")
        chkEx("{ return null!!; }", "ct_err:unop_operand_type:!!:null")
    }

    @Test fun testSpecOpSafeField() {
        chkEx("{ val x: (a:integer)? = _nullable((a=123)); return x.a; }", "ct_err:expr_mem_null:a")
        chkEx("{ val x: (a:integer)? = _nullable((a=123)); return x?.a; }", "int[123]")
        chkEx("{ val x: (a:integer)? = null; return x?.a; }", "null")

        chkEx("{ val x: (a:(b:(c:integer)?)?)? = _nullable((a=(b=(c=123)))); return x?.a?.b?.c; }", "int[123]")
        chkEx("{ val x: (a:(b:(c:integer)?)?)? = _nullable((a=(b=null))); return x?.a?.b?.c; }", "null")
        chkEx("{ val x: (a:(b:(c:integer)?)?)? = _nullable((a=null)); return x?.a?.b?.c; }", "null")
        chkEx("{ val x: (a:(b:(c:integer)?)?)? = null; return x?.a?.b?.c; }", "null")

        chkEx("{ val x: (a:(b:(c:integer)?)?)? = _nullable((a=(b=(c=123)))); return x.a.b.c; }", "ct_err:expr_mem_null:a")
        chkEx("{ val x: (a:(b:(c:integer)?)?)? = _nullable((a=(b=(c=123)))); return x?.a.b.c; }", "ct_err:expr_mem_null:b")
        chkEx("{ val x: (a:(b:(c:integer)?)?)? = _nullable((a=(b=(c=123)))); return x?.a?.b.c; }", "ct_err:expr_mem_null:c")

        chkEx("{ val x: (a:(b:(c:integer)))? = _nullable((a=(b=(c=123)))); return x.a.b.c; }", "ct_err:expr_mem_null:a")
        chkEx("{ val x: (a:(b:(c:integer)))? = _nullable((a=(b=(c=123)))); return x?.a.b.c; }", "ct_err:expr_mem_null:b")
        chkEx("{ val x: (a:(b:(c:integer)))? = _nullable((a=(b=(c=123)))); return x?.a?.b.c; }", "ct_err:expr_mem_null:c")
        chkEx("{ val x: (a:(b:(c:integer)))? = _nullable((a=(b=(c=123)))); return x?.a?.b?.c; }", "int[123]")

        chkEx("{ val x: (a:(b:(c:integer)))? = _nullable((a=(b=(c=123)))); return _type_of(x?.a); }", "text[(b:(c:integer))?]")
        chkEx("{ val x: (a:(b:(c:integer)))? = _nullable((a=(b=(c=123)))); return _type_of(x?.a?.b); }", "text[(c:integer)?]")
        chkEx("{ val x: (a:(b:(c:integer)))? = _nullable((a=(b=(c=123)))); return _type_of(x?.a?.b?.c); }", "text[integer?]")

        chkEx("{ return integer.MAX_VALUE; }", "int[9223372036854775807]")
        chkEx("{ return integer?.MAX_VALUE; }", "ct_err:expr_novalue:namespace")
    }

    @Test fun testSpecOpSafeField2() {
        tst.defs = listOf("record rec { mutable x: integer; }")
        chkEx("{ val r: rec? = _nullable(rec(123)); return r?.x; }", "int[123]")
        chkEx("{ val r: rec? = _nullable(rec(123)); return _type_of(r?.x); }", "text[integer?]")
        chkEx("{ val r: rec? = _nullable(rec(123)); r?.x = 456; return r; }", "rec[x=int[456]]")
        chkEx("{ val r: rec? = null; r?.x = 456; return r; }", "null")
        chkEx("{ val r: rec? = _nullable(rec(123)); r?.x = null; return r; }", "ct_err:stmt_assign_type:integer:null")
        chkEx("{ val r: rec? = null; r?.x = null; return r; }", "ct_err:stmt_assign_type:integer:null")
    }

    @Test fun testSpecOpSafeCall() {
        chkEx("{ val x: integer? = _nullable(123); return x.to_hex(); }", "ct_err:expr_mem_null:to_hex")
        chkEx("{ val x: integer? = _nullable(123); return x?.to_hex(); }", "text[7b]")
        chkEx("{ val x: integer? = null; return x?.to_hex(); }", "null")
        chkEx("{ val x: integer = 123; return x?.to_hex(); }", "ct_err:expr_safemem_type:integer")

        chkEx("{ val x: text? = _nullable('Hello'); return x.upper_case(); }", "ct_err:expr_mem_null:upper_case")
        chkEx("{ val x: text? = _nullable('Hello'); return x?.upper_case(); }", "text[HELLO]")
        chkEx("{ val x: text? = null; return x?.upper_case(); }", "null")
        chkEx("{ val x: text? = _nullable('Hello'); return x?.upper_case().lower_case(); }", "ct_err:expr_mem_null:lower_case")
        chkEx("{ val x: text? = _nullable('Hello'); return x?.upper_case()?.lower_case(); }", "text[hello]")
        chkEx("{ val x: text? = null; return x?.upper_case()?.lower_case(); }", "null")
        chkEx("{ val x: text? = _nullable('Hello'); return x?.upper_case()?.lower_case().size(); }", "ct_err:expr_mem_null:size")
        chkEx("{ val x: text? = _nullable('Hello'); return x?.upper_case()?.lower_case()?.size(); }", "int[5]")

        chkEx("{ val x: (a:integer?)? = _nullable((a=123)); return x?.a?.to_hex(); }", "text[7b]")
        chkEx("{ val x: (a:integer?)? = _nullable((a=null)); return x?.a?.to_hex(); }", "null")
        chkEx("{ val x: (a:integer?)? = null; return x?.a?.to_hex(); }", "null")

        chkEx("{ null?.str(); }", "ct_err:expr_safemem_type:null")

        chkEx("{ return integer.from_hex('7b'); }", "int[123]")
        chkEx("{ return integer?.from_hex('7b'); }", "ct_err:expr_novalue:namespace")
    }

    @Test fun testSpecOpSafeDataField() {
        tstCtx.useSql = true
        tst.defs = listOf("class company { name: text; }", "class user { name: text; company; }")

        chkOp("""
            val ms = create company(name = 'Microsoft');
            val ap = create company(name = 'Apple');
            create user(name = 'Bob', ms);
            create user(name = 'Alice', ap);
        """.trimIndent())

        chkEx("{ val u = user@{.name=='Bob'}; return u.company.name; }", "text[Microsoft]")
        chkEx("{ val u = user@?{.name=='Bob'}; return u.company.name; }", "ct_err:expr_mem_null:company")
        chkEx("{ val u = user@?{.name=='Bob'}; return u?.company.name; }", "ct_err:expr_mem_null:name")
        chkEx("{ val u = user@?{.name=='Bob'}; return u?.company?.name; }", "text[Microsoft]")
        chkEx("{ val u = user@?{.name=='Alice'}; return u?.company?.name; }", "text[Apple]")
        chkEx("{ val u = user@?{.name=='Trudy'}; return u?.company?.name; }", "null")

        chkEx("{ val u = user@{.name=='Bob'}; return u?.company.name; }", "ct_err:expr_safemem_type:user")
        chkEx("{ val u = user@{.name=='Bob'}; return u.company?.name; }", "ct_err:expr_safemem_type:company")
        chkEx("{ val u = user@{.name=='Bob'}; return u.company.name; }", "text[Microsoft]")

        chkEx("{ return user@{.company.name=='Microsoft'}(.name); }", "text[Bob]")
        chkEx("{ return user@{.company?.name=='Microsoft'}(.name); }", "ct_err:expr_safemem_type:company")
    }
}
