package net.postchain.rell

import net.postchain.rell.lib.LibBlockTransactionTest
import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.RellCodeTester
import org.junit.Test

class MirrorStructTest: BaseRellTest(false) {
    @Test fun testValidParameterKinds() {
        def("entity my_entity {}")
        def("object my_object {}")

        chkType("struct<my_entity>", "OK")
        chkType("struct<my_object>", "OK")
    }

    @Test fun testInvalidParameterKinds() {
        def("entity my_entity {}")
        def("struct my_struct {}")
        def("function my_function() {}")
        def("operation my_operation() {}")
        def("query my_query() = 0;")
        def("enum my_enum { red, green, blue }")

        chkType("struct<boolean>", "ct_err:type:struct:bad_type:boolean")
        chkType("struct<integer>", "ct_err:type:struct:bad_type:integer")

        chkType("struct<my_struct>", "ct_err:type:struct:bad_type:my_struct")
        chkType("struct<my_function>", "ct_err:unknown_type:my_function")
        chkType("struct<my_operation>", "ct_err:unknown_type:my_operation")
        chkType("struct<my_query>", "ct_err:unknown_type:my_query")
        chkType("struct<my_enum>", "ct_err:type:struct:bad_type:my_enum")
        chkType("struct<my_entity?>", "ct_err:type:struct:bad_type:my_entity?")

        chkType("struct<list<integer>>", "ct_err:type:struct:bad_type:list<integer>")
        chkType("struct<set<integer>>", "ct_err:type:struct:bad_type:set<integer>")
        chkType("struct<map<integer, text>>", "ct_err:type:struct:bad_type:map<integer,text>")
    }

    @Test fun testEntityConstructor() {
        def("entity user { name; rating: integer; }")

        chk("struct<user>('Bob', 123)", "struct<user>[name=text[Bob],rating=int[123]]")
        chk("struct<user>(rating = 123, name = 'Bob')", "struct<user>[name=text[Bob],rating=int[123]]")
        chk("struct<user>()", "ct_err:attr_missing:name,rating")
        chk("struct<user>(name = 'Bob')", "ct_err:attr_missing:rating")
        chk("struct<user>(name = 'Bob', rating = null)", "ct_err:attr_bad_type:1:rating:integer:null")
    }

    @Test fun testEntityConstructorDefaultValues() {
        def("entity data1 { x: integer = 123; y: text = 'abc'; }")
        def("entity data2 { x: integer = 123; y: text; }")

        chk("struct<data1>()", "struct<data1>[x=int[123],y=text[abc]]")
        chk("struct<data2>()", "ct_err:attr_missing:y")
        chk("struct<data2>(x = 456)", "ct_err:attr_missing:y")
        chk("struct<data2>(y = 'hello')", "struct<data2>[x=int[123],y=text[hello]]")
    }

    @Test fun testObjectConstructor() {
        def("object state { x: integer = 123; y: text = 'abc'; }")

        chk("struct<state>()", "struct<state>[x=int[123],y=text[abc]]")
        chk("struct<state>(x = 456)", "struct<state>[x=int[456],y=text[abc]]")
        chk("struct<state>(y = 'xyz')", "struct<state>[x=int[123],y=text[xyz]]")
        chk("struct<state>(456, 'xyz')", "struct<state>[x=int[456],y=text[xyz]]")
    }

    @Test fun testAttributeRead() {
        chkAttributeRead("entity data { x: integer; y: text; }")
        chkAttributeRead("object data { x: integer = 0; y: text = '?'; }")
    }

    private fun chkAttributeRead(def: String) {
        val t = RellCodeTester(tstCtx)
        t.def(def)
        t.chkQueryEx("{ val s = struct<data>(x = 123, y = 'abc'); return s.x; }", "int[123]")
        t.chkQueryEx("{ val s = struct<data>(x = 123, y = 'abc'); return s.y; }", "text[abc]")
    }

    @Test fun testMutability() {
        chkMutability("entity data { x: integer; mutable y: text; }")
        chkMutability("object data { x: integer = 123; mutable y: text = 'abc'; }")
    }

    private fun chkMutability(def: String) {
        val t = RellCodeTester(tstCtx)
        t.def(def)
        val init = "val s = struct<data>(x = 123, y = 'abc');"
        t.chkQueryEx("{ $init s.x = 456; return 0; }", "ct_err:update_attr_not_mutable:x")
        t.chkQueryEx("{ $init s.y = 'xyz'; return 0; }", "ct_err:update_attr_not_mutable:y")
    }

    @Test fun testInstanceMemberFunctions() {
        chkInstanceMemberFunctions("entity data { x: integer; y: text; }")
        chkInstanceMemberFunctions("object data { x: integer = 0; y: text = ''; }")
    }

    private fun chkInstanceMemberFunctions(def: String) {
        val t = RellCodeTester(tstCtx)
        t.def(def)
        val expr = "struct<data>(x = 123, y = 'abc')"
        t.chkQuery("$expr.to_gtv()", """gtv[[123,"abc"]]""")
        t.chkQuery("$expr.to_gtv_pretty()", """gtv[{"x":123,"y":"abc"}]""")
        t.chkQuery("$expr.to_bytes()", "byte_array[a50e300ca30302017ba2050c03616263]")
        t.chkQuery("$expr.bad_name()", "ct_err:unknown_member:[struct<data>]:bad_name")
    }

    @Test fun testStaticMemberFunctions() {
        chkStaticMemberFunctions("entity data { x: integer; y: text; }")
        chkStaticMemberFunctions("object data { x: integer = 0; y: text = ''; }")
    }

    private fun chkStaticMemberFunctions(def: String) {
        val t = RellCodeTester(tstCtx)
        t.def(def)
        t.chkQuery("""struct<data>.from_gtv(gtv.from_json('[123,"abc"]'))""", "struct<data>[x=int[123],y=text[abc]]")
        t.chkQuery("""struct<data>.from_gtv_pretty(gtv.from_json('{"x":123,"y":"abc"}'))""", "struct<data>[x=int[123],y=text[abc]]")
        t.chkQuery("struct<data>.from_bytes(x'a50e300ca30302017ba2050c03616263')", "struct<data>[x=int[123],y=text[abc]]")
        t.chkQuery("struct<data>.bad_name()", "ct_err:unknown_name:struct<data>.bad_name")
    }

    @Test fun testSystemEntity() {
        tstCtx.useSql = true
        tst.insert(LibBlockTransactionTest.BLOCK_INSERTS_0)

        chk("struct<block>()", "ct_err:attr_missing:block_height,block_rid,timestamp")
        chk("struct<block>(block_height = 123, block_rid = x'beef', timestamp = 456)",
                "struct<block>[block_height=int[123],block_rid=byte_array[beef],timestamp=int[456]]")

        chk("struct<transaction>()", "ct_err:attr_missing:block,tx_data,tx_hash,tx_rid")
        chk("struct<transaction>(block = block@{.block_height==10}, tx_data = x'dead', tx_hash = x'beef', tx_rid = x'cafe')",
                "struct<transaction>[tx_rid=byte_array[cafe],tx_hash=byte_array[beef],tx_data=byte_array[dead],block=block[710]]")
    }

    @Test fun testMirrorStructAtStructAttribute() {
        chkStructAttr("user", "operation op(d: data) {}", "0", "int[0]")
        chkStructAttr("user", "query w(d: data) = 0;", "0", "int[0]")
        chkStructAttr("user", "query w(): data? = null;", "0", "int[0]")
        chkStructAttr("user", "", "data(a = null)", "data[a=null]")

        chkStructAttr("block", "operation op(d: data) {}", "0", "int[0]")
        chkStructAttr("block", "query w(d: data) = 0;", "0", "int[0]")
        chkStructAttr("block", "query w(): data? = null;", "0", "int[0]")
        chkStructAttr("block", "", "data(a = null)", "data[a=null]")

        chkStructAttr("transaction", "operation op(d: data) {}", "0", "ct_err:param_nogtv:d:data")
        chkStructAttr("transaction", "query w(d: data) = 0;", "0", "ct_err:param_nogtv:d:data")
        chkStructAttr("transaction", "query w(): data? = null;", "0", "ct_err:result_nogtv:w:data?")
        chkStructAttr("transaction", "", "data(a = null)", "ct_err:result_nogtv:q:data")
    }

    private fun chkStructAttr(innerType: String, def: String, query: String, exp: String) {
        val t = RellCodeTester(tstCtx)
        t.gtv = true
        t.def("entity user { name; }")
        t.def("struct data { a: struct<$innerType>?; }")
        t.chkQueryEx("$def query q() = $query;", "q", listOf(), exp)
    }

    @Test fun testToStructEntityRtExpr() {
        initToStructEntity()

        chk("_type_of((user@{}).to_struct())", "text[struct<user>]")
        chk("(user@{}).to_struct()", "struct<user>[name=text[Bob],rating=int[123]]")

        chk("_type_of((user@?{}).to_struct())", "ct_err:expr_mem_null:to_struct")
        chk("_type_of((user@?{})?.to_struct())", "text[struct<user>?]")
        chk("(user@?{'Bob'}).to_struct()", "ct_err:expr_mem_null:to_struct")
        chk("(user@?{'Bob'})?.to_struct()", "struct<user>[name=text[Bob],rating=int[123]]")
        chk("(user@?{'Alice'})?.to_struct()", "null")

        chk("(user@{}).to_struct(123)", "ct_err:expr_call_argtypes:user.to_struct:integer")
        chk("(user@{}).to_error()", "ct_err:unknown_member:[user]:to_error")
    }

    @Test fun testToStructEntityDbExpr() {
        initToStructEntity()

        chk("_type_of(user@{} (user.to_struct()))", "text[struct<user>]")

        chk("user@{} (user.to_struct())", "struct<user>[name=text[Bob],rating=int[123]]")
        chk("(a:user)@{} (a.to_struct())", "struct<user>[name=text[Bob],rating=int[123]]")
        chk("user@{} (.to_struct())", "ct_err:expr_attr_unknown:to_struct")

        chk("user@{} (.name, user.to_struct(), .rating)",
                "(name=text[Bob],struct<user>[name=text[Bob],rating=int[123]],rating=int[123])")

        chk("user@{} (s = user.to_struct())", "(s=struct<user>[name=text[Bob],rating=int[123]])")

        chk("user@{} (user.to_struct().name)", "ct_err:expr_sqlnotallowed")
        chk("user@{} ('' + user.to_struct())", "ct_err:expr:to_text:nosql:struct<user>")
        chk("user@{} (user.to_struct() == struct<user>('Bob',123))", "ct_err:expr_sqlnotallowed")

        chk("user @ { user.to_struct() }", "ct_err:at_where:type:0:[boolean]:[struct<user>]")
        chk("user @ { user.to_struct() == struct<user>('Bob',123) }", "ct_err:expr_sqlnotallowed")
        chk("user @ { user.to_struct().name == 'Bob' }", "ct_err:expr_sqlnotallowed")

        chk("user@{} (user.to_struct(123))", "ct_err:expr_call_argtypes:user.to_struct:integer")
        chk("user@{} (user.to_error())", "ct_err:unknown_member:[user]:to_error")
    }

    @Test fun testToStructEntityDbExprMultiple() {
        initToStructEntity()

        val code = """
            (u:user, a:admin, c:company)
            @{}
            ( _ = a.name, u.to_struct(), _ = c.name, a.to_struct(), _ = u.name, c.to_struct(), _ = a.root )
        """

        val exp = listOf(
                "text[Alice]",
                "struct<user>[name=text[Bob],rating=int[123]]",
                "text[ChromaWay]",
                "struct<admin>[name=text[Alice],root=boolean[true]]",
                "text[Bob]",
                "struct<company>[name=text[ChromaWay],city=text[Stockholm]]",
                "boolean[true]"
        ).joinToString(",")

        chk(code, "($exp)")
    }

    @Test fun testToStructEntityDbExprAnnotations() {
        initToStructEntity()

        chk("user@{} ( .name, @omit user.to_struct() )", "text[Bob]")
        chk("user@{} ( @sort user.to_struct() )", "ct_err:to_struct:sort")
        chk("user@{} ( @sort_desc user.to_struct() )", "ct_err:to_struct:sort")

        chk("user@{} ( @group user.to_struct() )", "ct_err:to_struct:group")
        chk("user@{} ( @min user.to_struct() )", "ct_err:[to_struct:aggregate][at:what:aggr:bad_type:MIN:struct<user>]")
        chk("user@{} ( @max user.to_struct() )", "ct_err:[to_struct:aggregate][at:what:aggr:bad_type:MAX:struct<user>]")
        chk("user@{} ( @sum user.to_struct() )", "ct_err:[to_struct:aggregate][at:what:aggr:bad_type:SUM:struct<user>]")
        chk("user@{} ( @group .name, @min user.to_struct() )", "ct_err:[to_struct:aggregate][at:what:aggr:bad_type:MIN:struct<user>]")
        chk("user@{} ( @group .name, @max user.to_struct() )", "ct_err:[to_struct:aggregate][at:what:aggr:bad_type:MAX:struct<user>]")
        chk("user@{} ( @group .name, @sum user.to_struct() )", "ct_err:[to_struct:aggregate][at:what:aggr:bad_type:SUM:struct<user>]")
    }

    @Test fun testToStructEntityCollectionAt() {
        initToStructEntity()
        def("function users() = user @* {};")

        chk("users() @ {} ( $.to_struct() )", "struct<user>[name=text[Bob],rating=int[123]]")
        chk("users() @ {} ( $.to_struct().name )", "text[Bob]")
        chk("users() @ {} ( $.to_struct() == struct<user>('Bob',123) )", "boolean[true]")
        chk("users() @ {} ( $.to_struct() == struct<user>('Alice',456) )", "boolean[false]")
        chk("users() @? { $.to_struct().name == 'Bob' }", "user[33]")
        chk("users() @? { $.to_struct().name == 'Alice' }", "null")
        chk("users() @? { $.to_struct() == struct<user>('Bob',123) }", "user[33]")
        chk("users() @? { $.to_struct() == struct<user>('Alice',456) }", "null")

        chk("users() @* {} ( @omit @sort $.to_struct(), $.name )", "ct_err:at:expr:sort:type:struct<user>")
        chk("users() @* {} ( @omit @sort_desc $.to_struct(), $.name )", "ct_err:at:expr:sort:type:struct<user>")
        chk("users() @* {} ( @min $.to_struct() )", "ct_err:at:what:aggr:bad_type:MIN:struct<user>")
        chk("users() @* {} ( @max $.to_struct() )", "ct_err:at:what:aggr:bad_type:MAX:struct<user>")
        chk("users() @* {} ( @sum $.to_struct() )", "ct_err:at:what:aggr:bad_type:SUM:struct<user>")
    }

    private fun initToStructEntity() {
        tstCtx.useSql = true
        def("entity user { name; rating: integer; }")
        def("entity admin { name; root: boolean; }")
        def("entity company { name; city: text; }")
        insert("c0.user", "name,rating", "33,'Bob',123")
        insert("c0.admin", "name,root", "44,'Alice',true")
        insert("c0.company", "name,city", "55,'ChromaWay','Stockholm'")
    }

    @Test fun testToStructObject() {
        initToStructEntity()
        def("object state { x: integer = 123; y: text = 'abc'; }")

        chk("_type_of(state.to_struct())", "text[struct<state>]")
        chk("state.to_struct()", "struct<state>[x=int[123],y=text[abc]]")

        chk("user @ {} ( state.to_struct() )", "ct_err:expr_nosql:struct<state>")
        chk("(user@*{}) @ {} ( state.to_struct() )", "struct<state>[x=int[123],y=text[abc]]")

        chk("_type_of(state.to_struct(123))", "ct_err:expr_call_argtypes:state.to_struct:integer")
        chk("_type_of(state.to_error())", "ct_err:unknown_member:[state]:to_error")
    }

    @Test fun testCreateEntityFromStruct() {
        initToStructEntity()
        tst.strictToString = false

        val expr = "struct<user>('Alice',456)"
        chk("user @* {} ( $, _=.name, _=.rating )", "[(user[33],Bob,123)]")
        chkOp("val s = $expr; print(create user(s));")
        chkOut("user[1]")
        chk("user @* {} ( $, _=.name, _=.rating )", "[(user[1],Alice,456), (user[33],Bob,123)]")

        chkOp("val s: struct<user>? = _nullable($expr); create user(s);",
                "ct_err:[attr_missing:name,rating][attr_implic_unknown:0:struct<user>?]")
        chkOp("val s = $expr; create user(s, 'Alice');", "ct_err:[attr_missing:rating][attr_implic_unknown:0:struct<user>]")
        chkOp("val s = $expr; create user('Alice', s);", "ct_err:[attr_missing:rating][attr_implic_unknown:1:struct<user>]")
        chkOp("val s = $expr; create user(s, name = 'Alice');", "ct_err:[attr_missing:rating][attr_implic_unknown:0:struct<user>]")
        chkOp("val s = $expr; create user(name = 'Alice', s);", "ct_err:[attr_missing:rating][attr_implic_unknown:1:struct<user>]")
        chkOp("val s = $expr; create user(s = s);", "ct_err:attr_unknown_name:s")
        chkOp("val s = $expr; create admin(s);", "ct_err:[attr_missing:name,root][attr_implic_unknown:0:struct<user>]")
    }

    private fun chkType(typeCode: String, expected: String) {
        val code = "function f(x: $typeCode) {}"
        chkCompile(code, expected)
    }
}
