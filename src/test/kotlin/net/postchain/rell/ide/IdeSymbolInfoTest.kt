/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.ide

import org.junit.Test

class IdeSymbolInfoTest: BaseIdeSymbolInfoTest() {
    @Test fun testDefName() {
        chkSyms("entity user {}", "user:DEF_ENTITY")
        chkSyms("object state {}", "state:DEF_OBJECT")
        chkSyms("struct s {}", "s:DEF_STRUCT")
        chkSyms("query q() = 0;", "q:DEF_QUERY")
        chkSyms("operation op() {}", "op:DEF_OPERATION")
        chkSyms("function f() {}", "f:DEF_FUNCTION_REGULAR")
        chkSyms("val C = 0;", "C:DEF_CONSTANT")
        chkSyms("namespace ns {}", "ns:DEF_NAMESPACE")
        chkSyms("namespace a.b.c {}", "a:DEF_NAMESPACE", "b:DEF_NAMESPACE", "c:DEF_NAMESPACE")
        chkSyms("enum colors {}", "colors:DEF_ENUM")
    }

    @Test fun testDefConstant() {
        file("module.rell", """
            struct rec { x: integer; }
            val A = 123;
            val B = rec(456);
        """)

        chkExpr("A", "A:DEF_CONSTANT")
        chkExpr("B", "B:DEF_CONSTANT")
        chkExpr("B.x", "B:DEF_CONSTANT", "x:MEM_STRUCT_ATTR")
        chkExpr("B.x.to_hex()", "B:DEF_CONSTANT", "x:MEM_STRUCT_ATTR", "to_hex:DEF_FUNCTION_SYSTEM")
    }

    @Test fun testDefFunctionAbstract() {
        file("module.rell", "abstract module;")
        chkSyms("abstract function f();", "f:DEF_FUNCTION_ABSTRACT", ide = true)
    }

    @Test fun testDefFunctionOverride() {
        file("lib.rell", "abstract module; abstract function g();")
        chkSyms("import lib; override function lib.g() {}",
                "lib:DEF_IMPORT_MODULE", "lib:DEF_IMPORT_MODULE", "g:DEF_FUNCTION_ABSTRACT")
    }

    @Test fun testDefFunctionExtendable() {
        file("lib.rell", "module; @extendable function g(){}")
        file("module.rell", "import lib;")

        chkSyms("@extendable function f() {}", "extendable:MOD_ANNOTATION", "f:DEF_FUNCTION_EXTENDABLE")

        chkSyms("@extend(lib.g) function f() {}",
                "extend:MOD_ANNOTATION",
                "lib:DEF_IMPORT_MODULE",
                "g:DEF_FUNCTION_EXTENDABLE",
                "f:DEF_FUNCTION_EXTEND"
        )

        chkSymsErr("@extend(lib.foo) function f() {}", "unknown_name:lib.foo",
                "extend:MOD_ANNOTATION",
                "lib:DEF_IMPORT_MODULE",
                "foo:UNKNOWN",
                "f:DEF_FUNCTION_EXTEND"
        )

        chkSymsErr("@extend(foo.bar) function f() {}", "unknown_name:foo",
                "extend:MOD_ANNOTATION",
                "foo:UNKNOWN",
                "bar:UNKNOWN",
                "f:DEF_FUNCTION_EXTEND"
        )
    }

    @Test fun testDefFunctionMisc() {
        chkSymsErr("function a.b.c() {}", "fn:qname_no_override:a.b.c", "a:UNKNOWN", "b:UNKNOWN", "c:UNKNOWN")

        chkSymsErr("@extendable function a.b.c() {}", "fn:qname_no_override:a.b.c",
                "extendable:MOD_ANNOTATION",
                "a:UNKNOWN",
                "b:UNKNOWN",
                "c:UNKNOWN"
        )

        chkSymsErr("abstract function a.b.c();", "fn:qname_no_override:a.b.c,fn:abstract:non_abstract_module::a.b.c",
                "a:UNKNOWN",
                "b:UNKNOWN",
                "c:UNKNOWN",
                ide = true
        )
    }

    @Test fun testDefFunctionRef() {
        tst.testLib = true
        file("lib.rell", "abstract module; abstract function p();")
        file("module.rell", """
            import lib;
            function f() {}
            @extendable function g() {}
            @extend(g) function h() {}
            override function lib.p() {}
            operation op() {}
            query q() = 123;
            struct rec { x: integer = 123; }
        """)

        chkDefFnRef("f()", "f:DEF_FUNCTION_REGULAR")
        chkDefFnRef("g()", "g:DEF_FUNCTION_EXTENDABLE")
        chkDefFnRef("h()", "h:DEF_FUNCTION_EXTEND")
        chkDefFnRef("lib.p()", "lib:DEF_IMPORT_MODULE", "p:DEF_FUNCTION_ABSTRACT")
        chkDefFnRef("op()", "op:DEF_OPERATION")
        chkDefFnRef("q()", "q:DEF_QUERY")
        chkDefFnRef("rec()", "rec:DEF_STRUCT")
    }

    @Test fun testDefFunctionRefSys() {
        chkDefFnRef("print()", "print:DEF_FUNCTION_SYSTEM")
        chkDefFnRef("log()", "log:DEF_FUNCTION_SYSTEM")
        chkDefFnRef("crypto.sha256(x'1234')", "crypto:DEF_NAMESPACE", "sha256:DEF_FUNCTION_SYSTEM")
        chkDefFnRef("'hello'.size()", "size:DEF_FUNCTION_SYSTEM")
        chkDefFnRef("'hello'.index_of('world')", "index_of:DEF_FUNCTION_SYSTEM")
    }

    private fun chkDefFnRef(expr: String, vararg expected: String) {
        chkSyms("function t(){ $expr; }", "t:DEF_FUNCTION_REGULAR", *expected)
    }

    @Test fun testDefAttrHeader() {
        file("lib.rell", "module; namespace x { namespace y { struct z {} } }")

        chkSyms("function f(x: text) {}", "f:DEF_FUNCTION_REGULAR", "x:LOC_PARAMETER", "text:DEF_TYPE")

        chkSyms("function f(text) {}", "f:DEF_FUNCTION_REGULAR", "text:DEF_TYPE")

        chkSyms("import lib; function f(a: lib.x.y.z) {}",
                "lib:DEF_IMPORT_MODULE",
                "f:DEF_FUNCTION_REGULAR",
                "a:LOC_PARAMETER",
                "lib:DEF_IMPORT_MODULE",
                "x:DEF_NAMESPACE",
                "y:DEF_NAMESPACE",
                "z:DEF_STRUCT"
        )

        chkSyms("import lib; function f(lib.x.y.z) {}",
                "lib:DEF_IMPORT_MODULE",
                "f:DEF_FUNCTION_REGULAR",
                "lib:DEF_IMPORT_MODULE",
                "x:DEF_NAMESPACE",
                "y:DEF_NAMESPACE",
                "z:DEF_STRUCT"
        )
    }

    @Test fun testDefAttrEntity() {
        file("module.rell", "namespace ns { entity rec {} }")

        chkSyms("entity data { x: text; }", "data:DEF_ENTITY", "x:MEM_ENTITY_ATTR_NORMAL", "text:DEF_TYPE")
        chkSyms("entity data { x: text = 'Hello'; }", "data:DEF_ENTITY", "x:MEM_ENTITY_ATTR_NORMAL", "text:DEF_TYPE")
        chkSyms("entity data { text; }", "data:DEF_ENTITY", "text:DEF_TYPE")
        chkSyms("entity data { ns.rec; }", "data:DEF_ENTITY", "ns:DEF_NAMESPACE", "rec:DEF_ENTITY")
        chkSyms("entity data { integer = 123; }", "data:DEF_ENTITY", "integer:DEF_TYPE")

        chkSyms("entity data { ns.rec = ns.rec @ {}; }",
                "data:DEF_ENTITY",
                "ns:DEF_NAMESPACE",
                "rec:DEF_ENTITY",
                "ns:DEF_NAMESPACE",
                "rec:DEF_ENTITY"
        )

        chkSyms("entity data { x: integer; }", "data:DEF_ENTITY", "x:MEM_ENTITY_ATTR_NORMAL", "integer:DEF_TYPE")
        chkSyms("entity data { mutable x: integer; }", "data:DEF_ENTITY", "x:MEM_ENTITY_ATTR_NORMAL_VAR", "integer:DEF_TYPE")
    }

    @Test fun testDefAttrEntityKeyIndex() {
        chkSyms("entity data { x: integer; }", "data:DEF_ENTITY", "x:MEM_ENTITY_ATTR_NORMAL", "integer:DEF_TYPE")
        chkSyms("entity data { key x: integer; }", "data:DEF_ENTITY", "x:MEM_ENTITY_ATTR_KEY", "integer:DEF_TYPE")
        chkSyms("entity data { index x: integer; }", "data:DEF_ENTITY", "x:MEM_ENTITY_ATTR_INDEX", "integer:DEF_TYPE")

        chkSyms("entity data { x: integer; key x; }",
                "data:DEF_ENTITY",
                "x:MEM_ENTITY_ATTR_KEY",
                "integer:DEF_TYPE",
                "x:MEM_ENTITY_ATTR_KEY"
        )

        chkSyms("entity data { x: integer; index x; }",
                "data:DEF_ENTITY",
                "x:MEM_ENTITY_ATTR_INDEX",
                "integer:DEF_TYPE",
                "x:MEM_ENTITY_ATTR_INDEX"
        )

        chkSyms("entity data { mutable x: integer; key x; }",
                "data:DEF_ENTITY",
                "x:MEM_ENTITY_ATTR_KEY_VAR",
                "integer:DEF_TYPE",
                "x:MEM_ENTITY_ATTR_KEY_VAR"
        )

        chkSyms("entity data { mutable x: integer; index x; }",
                "data:DEF_ENTITY",
                "x:MEM_ENTITY_ATTR_INDEX_VAR",
                "integer:DEF_TYPE",
                "x:MEM_ENTITY_ATTR_INDEX_VAR"
        )

        chkSyms("entity data { x: integer; y: text; key x; index y; }",
                "data:DEF_ENTITY",
                "x:MEM_ENTITY_ATTR_KEY",
                "integer:DEF_TYPE",
                "y:MEM_ENTITY_ATTR_INDEX",
                "text:DEF_TYPE",
                "x:MEM_ENTITY_ATTR_KEY",
                "y:MEM_ENTITY_ATTR_INDEX"
        )

        chkSyms("entity data { x: integer; y: text; key x; index y; key x, y; }",
                "data:DEF_ENTITY",
                "x:MEM_ENTITY_ATTR_KEY",
                "integer:DEF_TYPE",
                "y:MEM_ENTITY_ATTR_KEY",
                "text:DEF_TYPE",
                "x:MEM_ENTITY_ATTR_KEY",
                "y:MEM_ENTITY_ATTR_KEY",
                "x:MEM_ENTITY_ATTR_KEY",
                "y:MEM_ENTITY_ATTR_KEY"
        )

        chkSyms("entity data { x: integer; y: text; key x; index y; index x, y; }",
                "data:DEF_ENTITY",
                "x:MEM_ENTITY_ATTR_KEY",
                "integer:DEF_TYPE",
                "y:MEM_ENTITY_ATTR_INDEX",
                "text:DEF_TYPE",
                "x:MEM_ENTITY_ATTR_KEY",
                "y:MEM_ENTITY_ATTR_INDEX",
                "x:MEM_ENTITY_ATTR_KEY",
                "y:MEM_ENTITY_ATTR_INDEX"
        )
    }

    @Test fun testDefAttrStruct() {
        file("module.rell", "namespace ns { struct rec {} }")

        chkSyms("struct data { x: text; }", "data:DEF_STRUCT", "x:MEM_STRUCT_ATTR", "text:DEF_TYPE")
        chkSyms("struct data { x: text = 'Hello'; }", "data:DEF_STRUCT", "x:MEM_STRUCT_ATTR", "text:DEF_TYPE")
        chkSyms("struct data { text; }", "data:DEF_STRUCT", "text:DEF_TYPE")
        chkSyms("struct data { ns.rec; }", "data:DEF_STRUCT", "ns:DEF_NAMESPACE", "rec:DEF_STRUCT")
        chkSyms("struct data { integer = 123; }", "data:DEF_STRUCT", "integer:DEF_TYPE")

        chkSyms("struct data { text?; }", "data:DEF_STRUCT", "text:DEF_TYPE")
        chkSyms("struct data { ns.rec?; }", "data:DEF_STRUCT", "ns:DEF_NAMESPACE", "rec:DEF_STRUCT")
        chkSyms("struct data { text? = 'Hello'; }", "data:DEF_STRUCT", "text:DEF_TYPE")
        chkSyms("struct data { ns.rec? = null; }", "data:DEF_STRUCT", "ns:DEF_NAMESPACE", "rec:DEF_STRUCT")

        chkSyms("struct data { x: integer; }", "data:DEF_STRUCT", "x:MEM_STRUCT_ATTR", "integer:DEF_TYPE")
        chkSyms("struct data { mutable x: integer; }", "data:DEF_STRUCT", "x:MEM_STRUCT_ATTR_VAR", "integer:DEF_TYPE")
    }

    @Test fun testDefAttrVar() {
        file("module.rell", "namespace ns { struct rec {} }")

        chkSyms("function f() { var x: text; }", "f:DEF_FUNCTION_REGULAR", "x:LOC_VAR", "text:DEF_TYPE")
        chkSyms("function f() { var x: text = 'Hello'; }", "f:DEF_FUNCTION_REGULAR", "x:LOC_VAR", "text:DEF_TYPE")
        chkSyms("function f() { var text; }", "f:DEF_FUNCTION_REGULAR", "text:DEF_TYPE")
        chkSyms("function f() { var text = 123; }", "f:DEF_FUNCTION_REGULAR", "text:LOC_VAR")
        chkSyms("function f() { var ns.rec; }", "f:DEF_FUNCTION_REGULAR", "ns:DEF_NAMESPACE", "rec:DEF_STRUCT")
    }

    @Test fun testDefAnnotation() {
        chkSyms("@log entity data {}", "log:MOD_ANNOTATION", "data:DEF_ENTITY")

        chkSymsErr("@external('') entity data {}", "ann:external:invalid:", "external:MOD_ANNOTATION", "data:DEF_ENTITY")
        chkSymsErr("@test entity data {}", "modifier:invalid:ann:test", "test:UNKNOWN", "data:DEF_ENTITY")

        chkSyms("@mount('foo') entity data {}", "mount:MOD_ANNOTATION", "data:DEF_ENTITY")
        chkSymsErr("@mount('') entity data {}", "ann:mount:empty:ENTITY", "mount:MOD_ANNOTATION", "data:DEF_ENTITY")
        chkSymsErr("@mount() entity data {}", "ann:mount:arg_count:0", "mount:MOD_ANNOTATION", "data:DEF_ENTITY")
        chkSymsErr("@mount entity data {}", "ann:mount:arg_count:0", "mount:MOD_ANNOTATION", "data:DEF_ENTITY")
        chkSymsErr("@mount(123) entity data {}", "ann:mount:arg_type:integer", "mount:MOD_ANNOTATION", "data:DEF_ENTITY")

        chkSymsErr("entity data(log) {}", "ann:legacy:log", "data:DEF_ENTITY", "log:MOD_ANNOTATION_LEGACY")
        chkSymsErr("entity data(foo) {}", "ann:legacy:foo,entity_ann_bad:foo", "data:DEF_ENTITY", "foo:UNKNOWN")
    }

    @Test fun testTypeName() {
        val defs = """
            entity data { name; }
            struct rec { x: integer; }
            enum colors { red, green, blue }
        """
        file("lib.rell", "@mount('lib') module; $defs")
        file("module.rell", "import lib; $defs")

        chkType("data", "data:DEF_ENTITY")
        chkType("rec", "rec:DEF_STRUCT")
        chkType("colors", "colors:DEF_ENUM")

        chkType("lib.data", "lib:DEF_IMPORT_MODULE", "data:DEF_ENTITY")
        chkType("lib.rec", "lib:DEF_IMPORT_MODULE", "rec:DEF_STRUCT")
        chkType("lib.colors", "lib:DEF_IMPORT_MODULE", "colors:DEF_ENUM")
    }

    @Test fun testTypeMirrorStruct() {
        val defs = """
            entity data { name; }
            object state { mutable x: integer = 0; }
            operation op() {}
        """
        file("lib.rell", "@mount('lib') module; $defs")
        file("module.rell", "import lib; $defs")

        chkType("struct<data>", "data:DEF_ENTITY")
        chkType("struct<state>", "state:DEF_OBJECT")
        chkType("struct<op>", "op:DEF_OPERATION")

        chkType("struct<lib.data>", "lib:DEF_IMPORT_MODULE", "data:DEF_ENTITY")
        chkType("struct<lib.state>", "lib:DEF_IMPORT_MODULE", "state:DEF_OBJECT")
        chkType("struct<lib.op>", "lib:DEF_IMPORT_MODULE", "op:DEF_OPERATION")
    }

    @Test fun testExprAtFrom() {
        file("lib.rell", """
            module;
            entity data2 { name; }
            function fdata2() = [4,5,6];
            namespace a { namespace b {
                entity data3 { name; }
                function fdata3() = [7,8,9];
            }}
        """)
        file("module.rell", "import lib; entity data1 { name; } function fdata1() = [1,2,3];")

        chkExpr("data1 @* {}", "data1:DEF_ENTITY")
        chkExpr("fdata1() @* {}", "fdata1:DEF_FUNCTION_REGULAR")
        chkExpr("lib.data2 @* {}", "lib:DEF_IMPORT_MODULE", "data2:DEF_ENTITY")
        chkExpr("lib.fdata2() @* {}", "lib:DEF_IMPORT_MODULE", "fdata2:DEF_FUNCTION_REGULAR")
        chkExpr("lib.a.b.data3 @* {}", "lib:DEF_IMPORT_MODULE", "a:DEF_NAMESPACE", "b:DEF_NAMESPACE", "data3:DEF_ENTITY")
        chkExpr("lib.a.b.fdata3() @* {}", "lib:DEF_IMPORT_MODULE", "a:DEF_NAMESPACE", "b:DEF_NAMESPACE", "fdata3:DEF_FUNCTION_REGULAR")
    }

    @Test fun testExprAtItem() {
        file("module.rell", """
            entity data { name; }
            function fdata() = [1,2,3];
            namespace ns { entity data2 { name; } }
        """)

        chkExpr("data @* {} ( data )", "data:DEF_ENTITY", "data:LOC_AT_ALIAS")
        chkExpr("data @* {} ( $ )", "data:DEF_ENTITY", "$:LOC_AT_ALIAS")
        chkExpr("(x: data) @* {} ( x )", "x:LOC_AT_ALIAS", "data:DEF_ENTITY", "x:LOC_AT_ALIAS")

        chkExpr("ns.data2 @* {} ( data2 )", "ns:DEF_NAMESPACE", "data2:DEF_ENTITY", "data2:LOC_AT_ALIAS")
        chkExpr("ns.data2 @* {} ( $ )", "ns:DEF_NAMESPACE", "data2:DEF_ENTITY", "$:LOC_AT_ALIAS")
        chkExpr("(x: ns.data2) @* {} ( x )", "x:LOC_AT_ALIAS", "ns:DEF_NAMESPACE", "data2:DEF_ENTITY", "x:LOC_AT_ALIAS")

        chkExpr("fdata() @* {} ( $ )", "fdata:DEF_FUNCTION_REGULAR", "$:LOC_AT_ALIAS")
        chkExpr("(x: fdata()) @* {} ( x )", "x:LOC_AT_ALIAS", "fdata:DEF_FUNCTION_REGULAR", "x:LOC_AT_ALIAS")
    }

    @Test fun testExprAtAttrDb() {
        file("module.rell", """
            entity ref { p: integer; q: text; }
            entity data { ref; i: integer; t: text; }
            entity spec {
                n1: integer; mutable n2: integer;
                key k1: integer; key mutable k2: integer;
                index i1: integer; index mutable i2: integer;
            }
        """)

        chkExpr("data @* {} ( .i )", "data:DEF_ENTITY", "i:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @* {} ( .t )", "data:DEF_ENTITY", "t:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @* {} ( .ref )", "data:DEF_ENTITY", "ref:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @* {} ( .ref.p )", "data:DEF_ENTITY", "ref:MEM_ENTITY_ATTR_NORMAL", "p:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @* {} ( .ref.q )", "data:DEF_ENTITY", "ref:MEM_ENTITY_ATTR_NORMAL", "q:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @* {} ( .rowid )", "data:DEF_ENTITY", "rowid:MEM_ENTITY_ATTR_ROWID")
        chkExpr("data @* {} ( .ref.rowid )", "data:DEF_ENTITY", "ref:MEM_ENTITY_ATTR_NORMAL", "rowid:MEM_ENTITY_ATTR_ROWID")

        chkExpr("spec @* {} ( .n1 )", "spec:DEF_ENTITY", "n1:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("spec @* {} ( .n2 )", "spec:DEF_ENTITY", "n2:MEM_ENTITY_ATTR_NORMAL_VAR")
        chkExpr("spec @* {} ( .k1 )", "spec:DEF_ENTITY", "k1:MEM_ENTITY_ATTR_KEY")
        chkExpr("spec @* {} ( .k2 )", "spec:DEF_ENTITY", "k2:MEM_ENTITY_ATTR_KEY_VAR")
        chkExpr("spec @* {} ( .i1 )", "spec:DEF_ENTITY", "i1:MEM_ENTITY_ATTR_INDEX")
        chkExpr("spec @* {} ( .i2 )", "spec:DEF_ENTITY", "i2:MEM_ENTITY_ATTR_INDEX_VAR")

        chkExprErr("data @* {} ( .bad )", "expr_attr_unknown:bad", "data:DEF_ENTITY", "bad:UNKNOWN")
        chkExprErr("data @* {} ( .ref.bad )", "unknown_member:[ref]:bad", "data:DEF_ENTITY", "ref:MEM_ENTITY_ATTR_NORMAL", "bad:UNKNOWN")

        val alias = "data:LOC_AT_ALIAS"
        chkExpr("data @* {} ( data.i )", "data:DEF_ENTITY", alias, "i:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @* {} ( data.ref )", "data:DEF_ENTITY", alias, "ref:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @* {} ( data.ref.p )", "data:DEF_ENTITY", alias, "ref:MEM_ENTITY_ATTR_NORMAL", "p:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @* {} ( data.rowid )", "data:DEF_ENTITY", alias, "rowid:MEM_ENTITY_ATTR_ROWID")
        chkExpr("data @* {} ( data.ref.rowid )", "data:DEF_ENTITY", alias, "ref:MEM_ENTITY_ATTR_NORMAL", "rowid:MEM_ENTITY_ATTR_ROWID")

        chkExpr("(d:data) @* {} ( d.i )", "d:LOC_AT_ALIAS", "data:DEF_ENTITY", "d:LOC_AT_ALIAS", "i:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("(d:data) @* {} ( d.ref )", "d:LOC_AT_ALIAS", "data:DEF_ENTITY", "d:LOC_AT_ALIAS", "ref:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("(d:data) @* {} ( d.rowid )", "d:LOC_AT_ALIAS", "data:DEF_ENTITY", "d:LOC_AT_ALIAS", "rowid:MEM_ENTITY_ATTR_ROWID")

        chkExprErr("(a:data,b:data) @* {} ( .i )",
                "at_attr_name_ambig:i:[a:data:i,b:data:i]",
                "a:LOC_AT_ALIAS",
                "data:DEF_ENTITY",
                "b:LOC_AT_ALIAS",
                "data:DEF_ENTITY",
                "i:MEM_ENTITY_ATTR_NORMAL"
        )
    }

    @Test fun testExprAtAttrCol() {
        file("module.rell", """
            struct ref { p: integer; mutable q: text; }
            struct data { ref; i: integer; mutable t: text; }
            function datas() = list<data>();
        """)

        val datas = "datas:DEF_FUNCTION_REGULAR"

        chkExpr("datas() @* {} ( .i )", datas, "i:MEM_STRUCT_ATTR")
        chkExpr("datas() @* {} ( .t )", datas, "t:MEM_STRUCT_ATTR_VAR")
        chkExpr("datas() @* {} ( .ref )", datas, "ref:MEM_STRUCT_ATTR")
        chkExpr("datas() @* {} ( .ref.p )", datas, "ref:MEM_STRUCT_ATTR", "p:MEM_STRUCT_ATTR")
        chkExpr("datas() @* {} ( .ref.q )", datas, "ref:MEM_STRUCT_ATTR", "q:MEM_STRUCT_ATTR_VAR")

        val alias = "d:LOC_AT_ALIAS"
        chkExpr("(d:datas()) @* {} ( d.i )", alias, datas, alias, "i:MEM_STRUCT_ATTR")
        chkExpr("(d:datas()) @* {} ( d.ref )", alias, datas, alias, "ref:MEM_STRUCT_ATTR")
        chkExpr("(d:datas()) @* {} ( d.ref.p )", alias, datas, alias, "ref:MEM_STRUCT_ATTR", "p:MEM_STRUCT_ATTR")

        chkExprErr("datas() @* {} ( .bad )", "expr_attr_unknown:bad", datas, "bad:UNKNOWN")
        chkExprErr("datas() @* {} ( .ref.bad )", "unknown_member:[ref]:bad", datas, "ref:MEM_STRUCT_ATTR", "bad:UNKNOWN")
    }

    @Test fun testExprAtWhat() {
        file("module.rell", """
            entity data {
                n1: integer = 0; mutable n2: integer = 0;
                key k1: integer = 0; key mutable k2: integer = 0;
                index i1: integer = 0; index mutable i2: integer = 0;
            }
        """)

        chkExpr("data @ {} ( .n1 )", "data:DEF_ENTITY", "n1:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @ {} ( .n2 )", "data:DEF_ENTITY", "n2:MEM_ENTITY_ATTR_NORMAL_VAR")
        chkExpr("data @ {} ( .k1 )", "data:DEF_ENTITY", "k1:MEM_ENTITY_ATTR_KEY")
        chkExpr("data @ {} ( .k2 )", "data:DEF_ENTITY", "k2:MEM_ENTITY_ATTR_KEY_VAR")
        chkExpr("data @ {} ( .i1 )", "data:DEF_ENTITY", "i1:MEM_ENTITY_ATTR_INDEX")
        chkExpr("data @ {} ( .i2 )", "data:DEF_ENTITY", "i2:MEM_ENTITY_ATTR_INDEX_VAR")

        chkExpr("data @ {} ( $.n1 )", "data:DEF_ENTITY", "$:LOC_AT_ALIAS", "n1:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @ {} ( $.n2 )", "data:DEF_ENTITY", "$:LOC_AT_ALIAS", "n2:MEM_ENTITY_ATTR_NORMAL_VAR")
        chkExpr("data @ {} ( $.k1 )", "data:DEF_ENTITY", "$:LOC_AT_ALIAS", "k1:MEM_ENTITY_ATTR_KEY")
        chkExpr("data @ {} ( $.k2 )", "data:DEF_ENTITY", "$:LOC_AT_ALIAS", "k2:MEM_ENTITY_ATTR_KEY_VAR")
        chkExpr("data @ {} ( $.i1 )", "data:DEF_ENTITY", "$:LOC_AT_ALIAS", "i1:MEM_ENTITY_ATTR_INDEX")
        chkExpr("data @ {} ( $.i2 )", "data:DEF_ENTITY", "$:LOC_AT_ALIAS", "i2:MEM_ENTITY_ATTR_INDEX_VAR")

        chkExpr("data @ {} ( data.n1 )", "data:DEF_ENTITY", "data:LOC_AT_ALIAS", "n1:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @ {} ( data.n2 )", "data:DEF_ENTITY", "data:LOC_AT_ALIAS", "n2:MEM_ENTITY_ATTR_NORMAL_VAR")
        chkExpr("data @ {} ( data.k1 )", "data:DEF_ENTITY", "data:LOC_AT_ALIAS", "k1:MEM_ENTITY_ATTR_KEY")
        chkExpr("data @ {} ( data.k2 )", "data:DEF_ENTITY", "data:LOC_AT_ALIAS", "k2:MEM_ENTITY_ATTR_KEY_VAR")
        chkExpr("data @ {} ( data.i1 )", "data:DEF_ENTITY", "data:LOC_AT_ALIAS", "i1:MEM_ENTITY_ATTR_INDEX")
        chkExpr("data @ {} ( data.i2 )", "data:DEF_ENTITY", "data:LOC_AT_ALIAS", "i2:MEM_ENTITY_ATTR_INDEX_VAR")

        chkExpr("data @ {}.n1", "data:DEF_ENTITY", "n1:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @ {}.n2", "data:DEF_ENTITY", "n2:MEM_ENTITY_ATTR_NORMAL_VAR")
        chkExpr("data @ {}.k1", "data:DEF_ENTITY", "k1:MEM_ENTITY_ATTR_KEY")
        chkExpr("data @ {}.k2", "data:DEF_ENTITY", "k2:MEM_ENTITY_ATTR_KEY_VAR")
        chkExpr("data @ {}.i1", "data:DEF_ENTITY", "i1:MEM_ENTITY_ATTR_INDEX")
        chkExpr("data @ {}.i2", "data:DEF_ENTITY", "i2:MEM_ENTITY_ATTR_INDEX_VAR")

        chkExpr("data @ {} ( a = $ )", "data:DEF_ENTITY", "a:MEM_TUPLE_ATTR", "$:LOC_AT_ALIAS")
        chkExpr("data @ {} ( x = .n1 )", "data:DEF_ENTITY", "x:MEM_TUPLE_ATTR", "n1:MEM_ENTITY_ATTR_NORMAL")
    }

    @Test fun testExprAtWhatAnnotations() {
        file("module.rell", "entity data { x: integer; }")
        chkExpr("data @* {} ( .x )", "data:DEF_ENTITY", "x:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @* {} ( @sort .x )", "data:DEF_ENTITY", "sort:MOD_ANNOTATION", "x:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @* {} ( @sort_desc .x )", "data:DEF_ENTITY", "sort_desc:MOD_ANNOTATION", "x:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @* {} ( @group .x )", "data:DEF_ENTITY", "group:MOD_ANNOTATION", "x:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @* {} ( @min .x )", "data:DEF_ENTITY", "min:MOD_ANNOTATION", "x:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @* {} ( @max .x )", "data:DEF_ENTITY", "max:MOD_ANNOTATION", "x:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("data @* {} ( @sum .x )", "data:DEF_ENTITY", "sum:MOD_ANNOTATION", "x:MEM_ENTITY_ATTR_NORMAL")
    }

    @Test fun testExprTypeSysMembers() {
        file("module.rell", """
            entity data { name; }
            struct rec { x: integer = 123; }
            function g() = gtv.from_json('');
        """)

        chkExpr("data.from_gtv(g())", "data:DEF_ENTITY", "from_gtv:DEF_FUNCTION_SYSTEM", "g:DEF_FUNCTION_REGULAR")
        chkExpr("rec.from_gtv(g())", "rec:DEF_STRUCT", "from_gtv:DEF_FUNCTION_SYSTEM", "g:DEF_FUNCTION_REGULAR")
        chkExpr("(data@{}).to_gtv()", "data:DEF_ENTITY", "to_gtv:DEF_FUNCTION_SYSTEM")
        chkExpr("rec().to_gtv()", "rec:DEF_STRUCT", "to_gtv:DEF_FUNCTION_SYSTEM")

        chkExpr("gtv.from_json('')", "gtv:DEF_TYPE", "from_json:DEF_FUNCTION_SYSTEM")
        chkExpr("g().to_bytes()", "g:DEF_FUNCTION_REGULAR", "to_bytes:DEF_FUNCTION_SYSTEM")

        chkExpr("(123).to_gtv()", "to_gtv:DEF_FUNCTION_SYSTEM")
        chkExpr("(123).to_hex()", "to_hex:DEF_FUNCTION_SYSTEM")
    }

    @Test fun testExprEnumMembers() {
        file("module.rell", "enum colors { red, green, blue }")

        chkExpr("colors.red", "colors:DEF_ENUM", "red:MEM_ENUM_VALUE")
        chkExpr("colors.green", "colors:DEF_ENUM", "green:MEM_ENUM_VALUE")
        chkExpr("colors.blue", "colors:DEF_ENUM", "blue:MEM_ENUM_VALUE")

        chkExpr("colors.red.name", "colors:DEF_ENUM", "red:MEM_ENUM_VALUE", "name:MEM_STRUCT_ATTR")
        chkExpr("colors.red.value", "colors:DEF_ENUM", "red:MEM_ENUM_VALUE", "value:MEM_STRUCT_ATTR")

        chkExpr("colors.values()", "colors:DEF_ENUM", "values:DEF_FUNCTION_SYSTEM")
        chkExpr("colors.value('red')", "colors:DEF_ENUM", "value:DEF_FUNCTION_SYSTEM")
    }

    @Test fun testExprGlobalDef() {
        file("module.rell", """
            entity data { name; }
            struct rec { x: integer; }
            enum colors { red, green, blue }
            object state { mutable x: integer = 0; }
            val MAGIC = 123;
            function f() {}
            operation op() {}
            query qu() = 123;
        """)

        chkSyms("function g() { var v: data; }", "g:DEF_FUNCTION_REGULAR", "v:LOC_VAR", "data:DEF_ENTITY")
        chkSyms("function g() { var v: data; }", "g:DEF_FUNCTION_REGULAR", "v:LOC_VAR", "data:DEF_ENTITY")
        chkSyms("function g() { var v: data; }", "g:DEF_FUNCTION_REGULAR", "v:LOC_VAR", "data:DEF_ENTITY")
        chkSyms("function g() { var v: data; }", "g:DEF_FUNCTION_REGULAR", "v:LOC_VAR", "data:DEF_ENTITY")
        chkSyms("function g() { var v: data; }", "g:DEF_FUNCTION_REGULAR", "v:LOC_VAR", "data:DEF_ENTITY")
    }

    @Test fun testExprCall() {
        tst.testLib = true
        file("module.rell", """
            function f(x: integer, y: text) = 0;
            function p(): (integer) -> integer = f(y = '', *);
            operation o(x: integer) {}
        """)

        chkExpr("f(x = 123, y = 'Hello')", "f:DEF_FUNCTION_REGULAR", "x:EXPR_CALL_ARG", "y:EXPR_CALL_ARG")
        chkExpr("f(y = 'Hello', x = 123)", "f:DEF_FUNCTION_REGULAR", "y:EXPR_CALL_ARG", "x:EXPR_CALL_ARG")

        chkExprErr("f(x = 123, y = 'Hello', foo = 0)", "expr:call:unknown_named_arg:[f]:foo",
                "f:DEF_FUNCTION_REGULAR",
                "x:EXPR_CALL_ARG",
                "y:EXPR_CALL_ARG",
                "foo:UNKNOWN"
        )

        chkExprErr("f()", "expr:call:missing_args:[f]:0:x,1:y", "f:DEF_FUNCTION_REGULAR")
        chkExprErr("f(123)", "expr:call:missing_args:[f]:1:y", "f:DEF_FUNCTION_REGULAR")
        chkExprErr("f('hello', 123)", "expr_call_argtype:[f]:0:x:integer:text,expr_call_argtype:[f]:1:y:text:integer", "f:DEF_FUNCTION_REGULAR")
        chkExprErr("f(123, 'hello', true)", "expr:call:too_many_args:[f]:2:3", "f:DEF_FUNCTION_REGULAR")

        chkExpr("o(x = 123)", "o:DEF_OPERATION", "x:EXPR_CALL_ARG")

        chkExprErr("o(x = 123, foo = 456)", "expr:call:unknown_named_arg:[o]:foo",
                "o:DEF_OPERATION",
                "x:EXPR_CALL_ARG",
                "foo:UNKNOWN"
        )

        chkExprErr("p()(x = 123)", "expr:call:missing_args:[?]:0,expr:call:unknown_named_arg:[?]:x",
                "p:DEF_FUNCTION_REGULAR",
                "x:UNKNOWN"
        )

        chkExprErr("integer.from_text(s = '123')", "expr:call:named_args_not_allowed:[integer.from_text]:s",
                "integer:DEF_TYPE",
                "from_text:DEF_FUNCTION_SYSTEM",
                "s:UNKNOWN"
        )

        chkExprErr("'Hello'.size(x = 123)", "expr_call_argtypes:[text.size]:integer,expr:call:named_args_not_allowed:[text.size]:x",
                "size:DEF_FUNCTION_SYSTEM",
                "x:UNKNOWN"
        )

        chkExprErr("'Hello'.char_at(i = 123)", "expr:call:named_args_not_allowed:[text.char_at]:i",
                "char_at:DEF_FUNCTION_SYSTEM",
                "i:UNKNOWN"
        )

        chkExprErr("33(x = 1)", "expr_call_nofn:integer", "x:UNKNOWN")
    }

    @Test fun testExprCreate() {
        file("module.rell", """
            entity data {
                n1: integer = 0; mutable n2: integer = 0;
                key k1: integer = 0; key mutable k2: integer = 0;
                index i1: integer = 0; index mutable i2: integer = 0;
            }
        """)

        chkExpr("create data()", "data:DEF_ENTITY")
        chkExpr("create data(n1 = 1)", "data:DEF_ENTITY", "n1:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("create data(n2 = 1)", "data:DEF_ENTITY", "n2:MEM_ENTITY_ATTR_NORMAL_VAR")
        chkExpr("create data(k1 = 1)", "data:DEF_ENTITY", "k1:MEM_ENTITY_ATTR_KEY")
        chkExpr("create data(k2 = 1)", "data:DEF_ENTITY", "k2:MEM_ENTITY_ATTR_KEY_VAR")
        chkExpr("create data(i1 = 1)", "data:DEF_ENTITY", "i1:MEM_ENTITY_ATTR_INDEX")
        chkExpr("create data(i2 = 1)", "data:DEF_ENTITY", "i2:MEM_ENTITY_ATTR_INDEX_VAR")
        chkExprErr("create data(foo = 123)", "attr_unknown_name:foo", "data:DEF_ENTITY", "foo:UNKNOWN")
    }

    @Test fun testExprStruct() {
        file("module.rell", "struct data { x: integer = 0; mutable y: integer = 0; }")
        chkExpr("data()", "data:DEF_STRUCT")
        chkExpr("data(x = 1)", "data:DEF_STRUCT", "x:MEM_STRUCT_ATTR")
        chkExpr("data(y = 1)", "data:DEF_STRUCT", "y:MEM_STRUCT_ATTR_VAR")
        chkExprErr("data(foo = 1)", "attr_unknown_name:foo", "data:DEF_STRUCT", "foo:UNKNOWN")
    }

    @Test fun testExprMirrorStructCreate() {
        initMirrorStruct()

        chkExpr("struct<data>(n1 = 1)", "data:DEF_ENTITY", "n1:MEM_STRUCT_ATTR")
        chkExpr("struct<data>(n2 = 1)", "data:DEF_ENTITY", "n2:MEM_STRUCT_ATTR")
        chkExpr("struct<data>(k1 = 1)", "data:DEF_ENTITY", "k1:MEM_STRUCT_ATTR")
        chkExpr("struct<data>(k2 = 1)", "data:DEF_ENTITY", "k2:MEM_STRUCT_ATTR")
        chkExpr("struct<data>(i1 = 1)", "data:DEF_ENTITY", "i1:MEM_STRUCT_ATTR")
        chkExpr("struct<data>(i2 = 1)", "data:DEF_ENTITY", "i2:MEM_STRUCT_ATTR")
        chkExprErr("struct<data>(foo = 1)", "attr_unknown_name:foo", "data:DEF_ENTITY", "foo:UNKNOWN")

        chkExpr("struct<mutable data>(n1 = 1)", "data:DEF_ENTITY", "n1:MEM_STRUCT_ATTR_VAR")
        chkExpr("struct<mutable data>(n2 = 1)", "data:DEF_ENTITY", "n2:MEM_STRUCT_ATTR_VAR")
        chkExpr("struct<mutable data>(k1 = 1)", "data:DEF_ENTITY", "k1:MEM_STRUCT_ATTR_VAR")
        chkExpr("struct<mutable data>(k2 = 1)", "data:DEF_ENTITY", "k2:MEM_STRUCT_ATTR_VAR")
        chkExpr("struct<mutable data>(i1 = 1)", "data:DEF_ENTITY", "i1:MEM_STRUCT_ATTR_VAR")
        chkExpr("struct<mutable data>(i2 = 1)", "data:DEF_ENTITY", "i2:MEM_STRUCT_ATTR_VAR")
        chkExprErr("struct<mutable data>(foo = 1)", "attr_unknown_name:foo", "data:DEF_ENTITY", "foo:UNKNOWN")

        chkExpr("struct<state>(x = 1)", "state:DEF_OBJECT", "x:MEM_STRUCT_ATTR")
        chkExpr("struct<state>(y = 1)", "state:DEF_OBJECT", "y:MEM_STRUCT_ATTR")
        chkExprErr("struct<state>(foo = 1)", "attr_unknown_name:foo", "state:DEF_OBJECT", "foo:UNKNOWN")
        chkExpr("struct<mutable state>(x = 1)", "state:DEF_OBJECT", "x:MEM_STRUCT_ATTR_VAR")
        chkExpr("struct<mutable state>(y = 1)", "state:DEF_OBJECT", "y:MEM_STRUCT_ATTR_VAR")
        chkExprErr("struct<mutable state>(foo = 1)", "attr_unknown_name:foo", "state:DEF_OBJECT", "foo:UNKNOWN")

        chkExpr("struct<op>(x = 1)", "op:DEF_OPERATION", "x:MEM_STRUCT_ATTR")
        chkExprErr("struct<op>(foo = 1)", "attr_unknown_name:foo", "op:DEF_OPERATION", "foo:UNKNOWN")
        chkExpr("struct<mutable op>(x = 1)", "op:DEF_OPERATION", "x:MEM_STRUCT_ATTR_VAR")
        chkExprErr("struct<mutable op>(foo = 1)", "attr_unknown_name:foo", "op:DEF_OPERATION", "foo:UNKNOWN")
    }

    @Test fun testExprMirrorStructAttrs() {
        initMirrorStruct()

        chkExpr("struct<data>().n1", "data:DEF_ENTITY", "n1:MEM_STRUCT_ATTR")
        chkExpr("struct<data>().n2", "data:DEF_ENTITY", "n2:MEM_STRUCT_ATTR")
        chkExpr("struct<data>().k1", "data:DEF_ENTITY", "k1:MEM_STRUCT_ATTR")
        chkExpr("struct<data>().k2", "data:DEF_ENTITY", "k2:MEM_STRUCT_ATTR")
        chkExpr("struct<data>().i1", "data:DEF_ENTITY", "i1:MEM_STRUCT_ATTR")
        chkExpr("struct<data>().i2", "data:DEF_ENTITY", "i2:MEM_STRUCT_ATTR")
        chkExprErr("struct<data>().foo", "unknown_member:[struct<data>]:foo", "data:DEF_ENTITY", "foo:UNKNOWN")

        chkExpr("struct<mutable data>().n1", "data:DEF_ENTITY", "n1:MEM_STRUCT_ATTR_VAR")
        chkExpr("struct<mutable data>().n2", "data:DEF_ENTITY", "n2:MEM_STRUCT_ATTR_VAR")
        chkExpr("struct<mutable data>().k1", "data:DEF_ENTITY", "k1:MEM_STRUCT_ATTR_VAR")
        chkExpr("struct<mutable data>().k2", "data:DEF_ENTITY", "k2:MEM_STRUCT_ATTR_VAR")
        chkExpr("struct<mutable data>().i1", "data:DEF_ENTITY", "i1:MEM_STRUCT_ATTR_VAR")
        chkExpr("struct<mutable data>().i2", "data:DEF_ENTITY", "i2:MEM_STRUCT_ATTR_VAR")
        chkExprErr("struct<mutable data>().foo", "unknown_member:[struct<mutable data>]:foo", "data:DEF_ENTITY", "foo:UNKNOWN")

        chkExpr("struct<state>().x", "state:DEF_OBJECT", "x:MEM_STRUCT_ATTR")
        chkExpr("struct<state>().y", "state:DEF_OBJECT", "y:MEM_STRUCT_ATTR")
        chkExprErr("struct<state>().foo", "unknown_member:[struct<state>]:foo", "state:DEF_OBJECT", "foo:UNKNOWN")
        chkExpr("struct<mutable state>().x", "state:DEF_OBJECT", "x:MEM_STRUCT_ATTR_VAR")
        chkExpr("struct<mutable state>().y", "state:DEF_OBJECT", "y:MEM_STRUCT_ATTR_VAR")
        chkExprErr("struct<mutable state>().foo", "unknown_member:[struct<mutable state>]:foo", "state:DEF_OBJECT", "foo:UNKNOWN")

        chkExpr("struct<op>().x", "op:DEF_OPERATION", "x:MEM_STRUCT_ATTR")
        chkExprErr("struct<op>().foo", "unknown_member:[struct<op>]:foo", "op:DEF_OPERATION", "foo:UNKNOWN")
        chkExpr("struct<mutable op>().x", "op:DEF_OPERATION", "x:MEM_STRUCT_ATTR_VAR")
        chkExprErr("struct<mutable op>().foo", "unknown_member:[struct<mutable op>]:foo", "op:DEF_OPERATION", "foo:UNKNOWN")
    }

    private fun initMirrorStruct() {
        file("module.rell", """
            entity data {
                n1: integer = 0; mutable n2: integer = 0;
                key k1: integer = 0; key mutable k2: integer = 0;
                index i1: integer = 0; index mutable i2: integer = 0;
            }
            object state {
                x: integer = 0;
                mutable y: integer = 0;
            }
            operation op(x: integer = 0) {}
        """)
    }

    @Test fun testExprLocalVar() {
        val def = "f:DEF_FUNCTION_REGULAR"
        chkSyms("function f() { val x = 123; return x; }", def, "x:LOC_VAL", "x:LOC_VAL")
        chkSyms("function f() { var x = 123; return x; }", def, "x:LOC_VAR", "x:LOC_VAR")
        chkSyms("function f() { val x: integer; x = 123; }", def, "x:LOC_VAL", "integer:DEF_TYPE", "x:LOC_VAL")
        chkSyms("function f() { var x: integer; x = 123; }", def, "x:LOC_VAR", "integer:DEF_TYPE", "x:LOC_VAR")
        chkSyms("function f(x: integer) = x;", def, "x:LOC_PARAMETER", "integer:DEF_TYPE", "x:LOC_PARAMETER")
        chkSyms("function f(x: integer) { return x; }", def, "x:LOC_PARAMETER", "integer:DEF_TYPE", "x:LOC_PARAMETER")
    }

    @Test fun testExprMember() {
        file("module.rell", "struct p { y: integer; } struct s { x: integer; p = p(456); }")

        chkExpr("s(123)", "s:DEF_STRUCT")
        chkExpr("s(123).x", "s:DEF_STRUCT", "x:MEM_STRUCT_ATTR")
        chkExpr("s(123).p.y", "s:DEF_STRUCT", "p:MEM_STRUCT_ATTR", "y:MEM_STRUCT_ATTR")
        chkExprErr("s()", "attr_missing:x", "s:DEF_STRUCT")
        chkExprErr("s().x", "attr_missing:x", "s:DEF_STRUCT", "x:UNKNOWN")
        chkExprErr("s().p.x", "attr_missing:x", "s:DEF_STRUCT", "p:UNKNOWN", "x:UNKNOWN")

        chkExprErr("abs().a", "expr_call_argtypes:[abs]:", "abs:DEF_FUNCTION_SYSTEM", "a:UNKNOWN")
        chkExprErr("abs().f()", "expr_call_argtypes:[abs]:", "abs:DEF_FUNCTION_SYSTEM", "f:UNKNOWN")
        chkExprErr("abs().a.b", "expr_call_argtypes:[abs]:", "abs:DEF_FUNCTION_SYSTEM", "a:UNKNOWN", "b:UNKNOWN")
        chkExprErr("abs().a.b()", "expr_call_argtypes:[abs]:", "abs:DEF_FUNCTION_SYSTEM", "a:UNKNOWN", "b:UNKNOWN")
    }

    @Test fun testExprObjectAttr() {
        file("module.rell", "object state { x: integer = 123; mutable y: integer = 456; }")
        chkExpr("state.x", "state:DEF_OBJECT", "x:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("state.y", "state:DEF_OBJECT", "y:MEM_ENTITY_ATTR_NORMAL_VAR")
        chkSyms("function f() { state.y = 789; }", "f:DEF_FUNCTION_REGULAR", "state:DEF_OBJECT", "y:MEM_ENTITY_ATTR_NORMAL_VAR")
    }

    @Test fun testExprTuple() {
        file("module.rell", "function f() = (a = 123, b = 'hello');")

        chkExpr("(a = 123, b = 'hello')", "a:MEM_TUPLE_ATTR", "b:MEM_TUPLE_ATTR")
        chkExpr("f().a", "f:DEF_FUNCTION_REGULAR", "a:MEM_TUPLE_ATTR")
        chkExpr("f().b", "f:DEF_FUNCTION_REGULAR", "b:MEM_TUPLE_ATTR")

        chkSyms("function g() { var x: (a:integer, b:text); }",
                "g:DEF_FUNCTION_REGULAR",
                "x:LOC_VAR",
                "a:MEM_TUPLE_ATTR",
                "integer:DEF_TYPE",
                "b:MEM_TUPLE_ATTR",
                "text:DEF_TYPE"
        )
    }

    @Test fun testExprModule() {
        file("lib.rell", "module; namespace ns { function f() = 123; }")
        file("module.rell", "import lib; import dup: lib;")
        chkExpr("lib.ns.f()", "lib:DEF_IMPORT_MODULE", "ns:DEF_NAMESPACE", "f:DEF_FUNCTION_REGULAR")
        chkExpr("dup.ns.f()", "dup:EXPR_IMPORT_ALIAS", "ns:DEF_NAMESPACE", "f:DEF_FUNCTION_REGULAR")
    }

    @Test fun testMisc() {
        chkSyms("function f(x: integer) = x * x;",
                "f:DEF_FUNCTION_REGULAR",
                "x:LOC_PARAMETER",
                "integer:DEF_TYPE",
                "x:LOC_PARAMETER",
                "x:LOC_PARAMETER"
        )
    }

    @Test fun testUnknownType() {
        file("lib.rell", "module; namespace ns { entity data { name; } }")
        file("module.rell", "import lib;")

        chkSyms("struct s { x: lib.ns.data; }",
                "s:DEF_STRUCT",
                "x:MEM_STRUCT_ATTR",
                "lib:DEF_IMPORT_MODULE",
                "ns:DEF_NAMESPACE",
                "data:DEF_ENTITY"
        )

        chkUnknownType("lib.ns.c", "lib.ns.c", "lib:DEF_IMPORT_MODULE", "ns:DEF_NAMESPACE", "c:UNKNOWN")
        chkUnknownType("lib.b.c", "lib.b", "lib:DEF_IMPORT_MODULE", "b:UNKNOWN", "c:UNKNOWN")
        chkUnknownType("a.b.c", "a", "a:UNKNOWN", "b:UNKNOWN", "c:UNKNOWN")
    }

    private fun chkUnknownType(type: String, unknown: String, vararg expected: String) {
        chkSymsErr("struct s { x: $type; }", "unknown_name:$unknown", "s:DEF_STRUCT", "x:MEM_STRUCT_ATTR", *expected)
    }

    @Test fun testUnknownEntity() {
        file("lib.rell", "module; namespace ns { entity data { name = 'Bob'; } }")
        file("module.rell", "import lib;")
        chkExpr("create lib.ns.data()", "lib:DEF_IMPORT_MODULE", "ns:DEF_NAMESPACE", "data:DEF_ENTITY")
        chkUnknownEntity("lib.ns.c", "lib.ns.c", "lib:DEF_IMPORT_MODULE", "ns:DEF_NAMESPACE", "c:UNKNOWN")
        chkUnknownEntity("lib.b.c", "lib.b", "lib:DEF_IMPORT_MODULE", "b:UNKNOWN", "c:UNKNOWN")
        chkUnknownEntity("a.b.c", "a", "a:UNKNOWN", "b:UNKNOWN", "c:UNKNOWN")
    }

    @Test fun testUnknownMirrorStruct() {
        chkExprErr("struct<foo>()", "unknown_name:foo", "foo:UNKNOWN")
        chkExprErr("struct<foo.bar>()", "unknown_name:foo", "foo:UNKNOWN", "bar:UNKNOWN")
    }

    @Test fun testUnknownAnonAttr() {
        chkSymsErr("function f(foo) {}", "unknown_name:foo", "f:DEF_FUNCTION_REGULAR", "foo:UNKNOWN")
        chkSymsErr("function f(foo?) {}", "unknown_name:foo", "f:DEF_FUNCTION_REGULAR", "foo:UNKNOWN")
        chkSymsErr("function f(foo.bar) {}", "unknown_name:foo", "f:DEF_FUNCTION_REGULAR", "foo:UNKNOWN", "bar:UNKNOWN")
    }

    private fun chkUnknownEntity(type: String, unknown: String, vararg expected: String) {
        chkExprErr("create $type()", "unknown_name:$unknown", *expected)
    }
}
