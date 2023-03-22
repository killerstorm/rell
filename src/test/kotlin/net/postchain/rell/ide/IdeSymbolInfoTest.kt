/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.ide

import org.junit.Test

class IdeSymbolInfoTest: BaseIdeSymbolTest() {
    @Test fun testDefName() {
        chkKdls("entity user {}", "user:DEF_ENTITY;entity[user];-")
        chkKdls("object state {}", "state:DEF_OBJECT;object[state];-")
        chkKdls("struct s {}", "s:DEF_STRUCT;struct[s];-")
        chkKdls("query q() = 0;", "q:DEF_QUERY;query[q];-")
        chkKdls("operation op() {}", "op:DEF_OPERATION;operation[op];-")
        chkKdls("function f() {}", "f:DEF_FUNCTION;function[f];-")
        chkKdls("val C = 0;", "C:DEF_CONSTANT;constant[C];-")
        chkKdls("namespace ns {}", "ns:DEF_NAMESPACE;namespace[ns];-")
        chkKdls("namespace a.b.c {}", "a:DEF_NAMESPACE;namespace[a];-", "b:DEF_NAMESPACE;namespace[a.b];-", "c:DEF_NAMESPACE;namespace[a.b.c];-")
        chkKdls("enum colors {}", "colors:DEF_ENUM;enum[colors];-")
    }

    @Test fun testDefConstant() {
        file("module.rell", """
            struct rec { x: integer; }
            val A = 123;
            val B = rec(456);
        """)

        chkKdlsExpr("A", "A:DEF_CONSTANT;-;module.rell/constant[A]")
        chkKdlsExpr("B", "B:DEF_CONSTANT;-;module.rell/constant[B]")

        val attr = "x:MEM_STRUCT_ATTR;-;module.rell/struct[rec].attr[x]"
        chkKdlsExpr("B.x", "B:DEF_CONSTANT;-;module.rell/constant[B]", attr)
        chkKdlsExpr("B.x.to_hex()","B:DEF_CONSTANT;-;module.rell/constant[B]", attr, "to_hex:DEF_FUNCTION_SYSTEM;-;-")
    }

    @Test fun testDefFunctionAbstract() {
        file("module.rell", "abstract module;")
        chkKdls("abstract function f();", "f:DEF_FUNCTION_ABSTRACT;function[f];-", ide = true)
    }

    @Test fun testDefFunctionOverride() {
        file("lib.rell", "abstract module; abstract function g();")
        chkKdls("import lib; override function lib.g() {}",
                "lib:DEF_IMPORT_MODULE;*;*", "lib:DEF_IMPORT_MODULE;-;*", "g:DEF_FUNCTION_ABSTRACT;-;lib.rell/function[g]")
    }

    @Test fun testDefFunctionExtendable() {
        file("lib.rell", "module; @extendable function g(){}")
        file("module.rell", "import lib;")

        chkKdls("@extendable function f() {}", "extendable:MOD_ANNOTATION;-;-", "f:DEF_FUNCTION_EXTENDABLE;function[f];-")

        chkKdls("@extend(lib.g) function f() {}",
                "extend:MOD_ANNOTATION;-;-",
                "lib:DEF_IMPORT_MODULE;-;*",
                "g:DEF_FUNCTION_EXTENDABLE;-;lib.rell/function[g]",
                "f:DEF_FUNCTION_EXTEND;function[f];-"
        )

        chkKdlsErr("@extend(lib.foo) function f() {}", "unknown_name:lib.foo",
                "extend:MOD_ANNOTATION;-;-",
                "lib:DEF_IMPORT_MODULE;-;*",
                "foo:UNKNOWN;-;-",
                "f:DEF_FUNCTION_EXTEND;function[f];-"
        )

        chkKdlsErr("@extend(foo.bar) function f() {}", "unknown_name:foo",
                "extend:MOD_ANNOTATION;-;-",
                "foo:UNKNOWN;-;-",
                "bar:UNKNOWN;-;-",
                "f:DEF_FUNCTION_EXTEND;function[f];-"
        )
    }

    @Test fun testDefFunctionMisc() {
        chkKdlsErr("function a.b.c() {}", "fn:qname_no_override:a.b.c", "a:UNKNOWN;-;-", "b:UNKNOWN;-;-", "c:UNKNOWN;-;-")

        chkKdlsErr("@extendable function a.b.c() {}", "fn:qname_no_override:a.b.c",
                "extendable:MOD_ANNOTATION;-;-",
                "a:UNKNOWN;-;-",
                "b:UNKNOWN;-;-",
                "c:UNKNOWN;-;-"
        )

        chkKdlsErr("abstract function a.b.c();", "fn:qname_no_override:a.b.c,fn:abstract:non_abstract_module::a.b.c",
                "a:UNKNOWN;-;-",
                "b:UNKNOWN;-;-",
                "c:UNKNOWN;-;-",
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

        chkDefFnRef("f()", "f:DEF_FUNCTION;-;module.rell/function[f]")
        chkDefFnRef("g()", "g:DEF_FUNCTION_EXTENDABLE;-;module.rell/function[g]")
        chkDefFnRef("h()", "h:DEF_FUNCTION_EXTEND;-;module.rell/function[h]")
        chkDefFnRef("lib.p()", "lib:DEF_IMPORT_MODULE;-;*", "p:DEF_FUNCTION_ABSTRACT;-;lib.rell/function[p]")
        chkDefFnRef("op()", "op:DEF_OPERATION;-;module.rell/operation[op]")
        chkDefFnRef("q()", "q:DEF_QUERY;-;module.rell/query[q]")
        chkDefFnRef("rec()", "rec:DEF_STRUCT;-;module.rell/struct[rec]")
    }

    @Test fun testDefFunctionRefSys() {
        chkDefFnRef("print()", "print:DEF_FUNCTION_SYSTEM;-;-")
        chkDefFnRef("log()", "log:DEF_FUNCTION_SYSTEM;-;-")
        chkDefFnRef("crypto.sha256(x'1234')", "crypto:DEF_NAMESPACE;-;-", "sha256:DEF_FUNCTION_SYSTEM;-;-")
        chkDefFnRef("'hello'.size()", "size:DEF_FUNCTION_SYSTEM;-;-")
        chkDefFnRef("'hello'.index_of('world')", "index_of:DEF_FUNCTION_SYSTEM;-;-")
    }

    private fun chkDefFnRef(expr: String, vararg expected: String) {
        chkKdls("function t(){ $expr; }", "t:DEF_FUNCTION;function[t];-", *expected)
    }

    @Test fun testDefAttrHeader() {
        file("lib.rell", "module; namespace x { namespace y { struct z {} } }")

        chkKdls("function f(x: text) {}", "f:DEF_FUNCTION;function[f];-", "x:LOC_PARAMETER;function[f].param[x];-", "text:DEF_TYPE;-;-")

        chkKdls("function f(text) {}", "f:DEF_FUNCTION;function[f];-", "text:DEF_TYPE;function[f].param[text];-")

        chkKdls("import lib; function f(a: lib.x.y.z) {}",
                "lib:DEF_IMPORT_MODULE;*;*",
                "f:DEF_FUNCTION;function[f];-",
                "a:LOC_PARAMETER;function[f].param[a];-",
                "lib:DEF_IMPORT_MODULE;-;*",
                "x:DEF_NAMESPACE;-;lib.rell/namespace[x]",
                "y:DEF_NAMESPACE;-;lib.rell/namespace[x.y]",
                "z:DEF_STRUCT;-;lib.rell/struct[x.y.z]"
        )

        chkKdls("import lib; function f(lib.x.y.z) {}",
                "lib:DEF_IMPORT_MODULE;*;*",
                "f:DEF_FUNCTION;function[f];-",
                "lib:DEF_IMPORT_MODULE;-;*",
                "x:DEF_NAMESPACE;-;lib.rell/namespace[x]",
                "y:DEF_NAMESPACE;-;lib.rell/namespace[x.y]",
                "z:DEF_STRUCT;function[f].param[z];lib.rell/struct[x.y.z]"
        )
    }

    @Test fun testDefAttrEntity() {
        file("module.rell", "namespace ns { entity rec {} }")

        val entityDef = "data:DEF_ENTITY;entity[data];-"
        val ns = "ns:DEF_NAMESPACE;-;module.rell/namespace[ns]"
        chkKdls("entity data { x: text; }", entityDef, "x:MEM_ENTITY_ATTR_NORMAL;entity[data].attr[x];-", "text:DEF_TYPE;-;-")
        chkKdls("entity data { x: text = 'Hello'; }", entityDef, "x:MEM_ENTITY_ATTR_NORMAL;entity[data].attr[x];-", "text:DEF_TYPE;-;-")
        chkKdls("entity data { text; }", entityDef, "text:DEF_TYPE;entity[data].attr[text];-")
        chkKdls("entity data { ns.rec; }", entityDef, ns, "rec:DEF_ENTITY;entity[data].attr[rec];module.rell/entity[ns.rec]")
        chkKdls("entity data { integer = 123; }", entityDef, "integer:DEF_TYPE;entity[data].attr[integer];-")

        chkKdls("entity data { ns.rec = ns.rec @ {}; }",
                entityDef,
                ns,
                "rec:DEF_ENTITY;entity[data].attr[rec];module.rell/entity[ns.rec]",
                ns,
                "rec:DEF_ENTITY;-;module.rell/entity[ns.rec]"
        )

        chkKdls("entity data { x: integer; }", entityDef, "x:MEM_ENTITY_ATTR_NORMAL;entity[data].attr[x];-", "integer:DEF_TYPE;-;-")
        chkKdls("entity data { mutable x: integer; }", entityDef, "x:MEM_ENTITY_ATTR_NORMAL_VAR;entity[data].attr[x];-", "integer:DEF_TYPE;-;-")
    }

    @Test fun testDefAttrEntityKeyIndex() {
        val entityDef = "data:DEF_ENTITY;entity[data];-"
        chkKdls("entity data { x: integer; }", entityDef, "x:MEM_ENTITY_ATTR_NORMAL;entity[data].attr[x];-", "integer:DEF_TYPE;-;-")
        chkKdls("entity data { key x: integer; }", entityDef, "x:MEM_ENTITY_ATTR_KEY;entity[data].attr[x];-", "integer:DEF_TYPE;-;-")
        chkKdls("entity data { index x: integer; }", entityDef, "x:MEM_ENTITY_ATTR_INDEX;entity[data].attr[x];-", "integer:DEF_TYPE;-;-")

        chkKdls("entity data { x: integer; key x; }",
                entityDef,
                "x:MEM_ENTITY_ATTR_KEY;entity[data].attr[x];-",
                "integer:DEF_TYPE;-;-",
                "x:MEM_ENTITY_ATTR_KEY;-;main.rell/entity[data].attr[x]"
        )

        chkKdls("entity data { x: integer; index x; }",
                entityDef,
                "x:MEM_ENTITY_ATTR_INDEX;entity[data].attr[x];-",
                "integer:DEF_TYPE;-;-",
                "x:MEM_ENTITY_ATTR_INDEX;-;main.rell/entity[data].attr[x]"
        )

        chkKdls("entity data { mutable x: integer; key x; }",
                entityDef,
                "x:MEM_ENTITY_ATTR_KEY_VAR;entity[data].attr[x];-",
                "integer:DEF_TYPE;-;-",
                "x:MEM_ENTITY_ATTR_KEY_VAR;-;main.rell/entity[data].attr[x]"
        )

        chkKdls("entity data { mutable x: integer; index x; }",
                entityDef,
                "x:MEM_ENTITY_ATTR_INDEX_VAR;entity[data].attr[x];-",
                "integer:DEF_TYPE;-;-",
                "x:MEM_ENTITY_ATTR_INDEX_VAR;-;main.rell/entity[data].attr[x]"
        )

        chkKdls("entity data { x: integer; y: text; key x; index y; }",
                entityDef,
                "x:MEM_ENTITY_ATTR_KEY;entity[data].attr[x];-",
                "integer:DEF_TYPE;-;-",
                "y:MEM_ENTITY_ATTR_INDEX;entity[data].attr[y];-",
                "text:DEF_TYPE;-;-",
                "x:MEM_ENTITY_ATTR_KEY;-;main.rell/entity[data].attr[x]",
                "y:MEM_ENTITY_ATTR_INDEX;-;main.rell/entity[data].attr[y]"
        )

        chkKdls("entity data { x: integer; y: text; key x; index y; key x, y; }",
                entityDef,
                "x:MEM_ENTITY_ATTR_KEY;entity[data].attr[x];-",
                "integer:DEF_TYPE;-;-",
                "y:MEM_ENTITY_ATTR_KEY;entity[data].attr[y];-",
                "text:DEF_TYPE;-;-",
                "x:MEM_ENTITY_ATTR_KEY;-;main.rell/entity[data].attr[x]",
                "y:MEM_ENTITY_ATTR_KEY;-;main.rell/entity[data].attr[y]",
                "x:MEM_ENTITY_ATTR_KEY;-;main.rell/entity[data].attr[x]",
                "y:MEM_ENTITY_ATTR_KEY;-;main.rell/entity[data].attr[y]"
        )

        chkKdls("entity data { x: integer; y: text; key x; index y; index x, y; }",
                entityDef,
                "x:MEM_ENTITY_ATTR_KEY;entity[data].attr[x];-",
                "integer:DEF_TYPE;-;-",
                "y:MEM_ENTITY_ATTR_INDEX;entity[data].attr[y];-",
                "text:DEF_TYPE;-;-",
                "x:MEM_ENTITY_ATTR_KEY;-;main.rell/entity[data].attr[x]",
                "y:MEM_ENTITY_ATTR_INDEX;-;main.rell/entity[data].attr[y]",
                "x:MEM_ENTITY_ATTR_KEY;-;main.rell/entity[data].attr[x]",
                "y:MEM_ENTITY_ATTR_INDEX;-;main.rell/entity[data].attr[y]"
        )
    }

    @Test fun testDefAttrStruct() {
        file("module.rell", "namespace ns { struct rec {} }")

        val structDef = "data:DEF_STRUCT;struct[data];-"
        val ns = "ns:DEF_NAMESPACE;-;module.rell/namespace[ns]"
        val recRef = "rec:DEF_STRUCT;-;module.rell/struct[ns.rec]"
        chkKdls("struct data { x: text; }", structDef, "x:MEM_STRUCT_ATTR;struct[data].attr[x];-", "text:DEF_TYPE;-;-")
        chkKdls("struct data { x: text = 'Hello'; }", structDef, "x:MEM_STRUCT_ATTR;struct[data].attr[x];-", "text:DEF_TYPE;-;-")
        chkKdls("struct data { text; }", structDef, "text:DEF_TYPE;struct[data].attr[text];-")
        chkKdls("struct data { ns.rec; }", structDef, ns, "rec:DEF_STRUCT;struct[data].attr[rec];module.rell/struct[ns.rec]")
        chkKdls("struct data { integer = 123; }", structDef, "integer:DEF_TYPE;struct[data].attr[integer];-")

        chkKdls("struct data { text?; }", structDef, "text:DEF_TYPE;struct[data].attr[text];-")
        chkKdls("struct data { ns.rec?; }", structDef, ns, "rec:DEF_STRUCT;struct[data].attr[rec];module.rell/struct[ns.rec]")
        chkKdls("struct data { text? = 'Hello'; }", structDef, "text:DEF_TYPE;struct[data].attr[text];-")
        chkKdls("struct data { ns.rec? = null; }", structDef, ns, "rec:DEF_STRUCT;struct[data].attr[rec];module.rell/struct[ns.rec]")

        chkKdls("struct data { x: integer; }", structDef, "x:MEM_STRUCT_ATTR;struct[data].attr[x];-", "integer:DEF_TYPE;-;-")
        chkKdls("struct data { mutable x: integer; }", structDef, "x:MEM_STRUCT_ATTR_VAR;struct[data].attr[x];-", "integer:DEF_TYPE;-;-")
    }

    @Test fun testDefAttrVar() {
        file("module.rell", "namespace ns { struct rec {} }")

        val fn = "f:DEF_FUNCTION;function[f];-"
        chkKdls("function f() { var x: text; }", fn, "x:LOC_VAR;-;-", "text:DEF_TYPE;-;-")
        chkKdls("function f() { var x: text = 'Hello'; }", fn, "x:LOC_VAR;-;-", "text:DEF_TYPE;-;-")
        chkKdls("function f() { var text; }", fn, "text:DEF_TYPE;-;-")
        chkKdls("function f() { var text = 123; }", fn, "text:LOC_VAR;-;-")
        chkKdls("function f() { var ns.rec; }", fn, "ns:DEF_NAMESPACE;-;module.rell/namespace[ns]", "rec:DEF_STRUCT;-;module.rell/struct[ns.rec]")
    }

    @Test fun testDefAnnotation() {
        chkKdls("@log entity data {}", "log:MOD_ANNOTATION;-;-", "data:DEF_ENTITY;entity[data];-")

        chkKdlsErr("@external('') entity data {}", "ann:external:invalid:", "external:MOD_ANNOTATION;-;-", "data:DEF_ENTITY;entity[data];-")
        chkKdlsErr("@test entity data {}", "modifier:invalid:ann:test", "test:UNKNOWN;-;-", "data:DEF_ENTITY;entity[data];-")

        chkKdls("@mount('foo') entity data {}", "mount:MOD_ANNOTATION;-;-", "data:DEF_ENTITY;entity[data];-")
        chkKdlsErr("@mount('') entity data {}", "ann:mount:empty:ENTITY", "mount:MOD_ANNOTATION;-;-", "data:DEF_ENTITY;entity[data];-")
        chkKdlsErr("@mount() entity data {}", "ann:mount:arg_count:0", "mount:MOD_ANNOTATION;-;-", "data:DEF_ENTITY;entity[data];-")
        chkKdlsErr("@mount entity data {}", "ann:mount:arg_count:0", "mount:MOD_ANNOTATION;-;-", "data:DEF_ENTITY;entity[data];-")
        chkKdlsErr("@mount(123) entity data {}", "ann:mount:arg_type:integer", "mount:MOD_ANNOTATION;-;-", "data:DEF_ENTITY;entity[data];-")

        chkKdlsErr("entity data(log) {}", "ann:legacy:log", "data:DEF_ENTITY;entity[data];-", "log:MOD_ANNOTATION_LEGACY;-;-")
        chkKdlsErr("entity data(foo) {}", "ann:legacy:foo,entity_ann_bad:foo", "data:DEF_ENTITY;entity[data];-", "foo:UNKNOWN;-;-")
    }

    @Test fun testTypeName() {
        val defs = """
            entity data { name; }
            struct rec { x: integer; }
            enum colors { red, green, blue }
        """
        file("lib.rell", "@mount('lib') module; $defs")
        file("module.rell", "import lib; $defs")

        chkKdlsType("data", "data:DEF_ENTITY;-;module.rell/entity[data]")
        chkKdlsType("rec", "rec:DEF_STRUCT;-;module.rell/struct[rec]")
        chkKdlsType("colors", "colors:DEF_ENUM;-;module.rell/enum[colors]")

        chkKdlsType("lib.data", "lib:DEF_IMPORT_MODULE;-;module.rell/import[lib]", "data:DEF_ENTITY;-;lib.rell/entity[data]")
        chkKdlsType("lib.rec", "lib:DEF_IMPORT_MODULE;-;module.rell/import[lib]", "rec:DEF_STRUCT;-;lib.rell/struct[rec]")
        chkKdlsType("lib.colors", "lib:DEF_IMPORT_MODULE;-;module.rell/import[lib]", "colors:DEF_ENUM;-;lib.rell/enum[colors]")
    }

    @Test fun testTypeMirrorStruct() {
        val defs = """
            entity data { name; }
            object state { mutable x: integer = 0; }
            operation op() {}
        """
        file("lib.rell", "@mount('lib') module; $defs")
        file("module.rell", "import lib; $defs")

        chkKdlsType("struct<data>", "data:DEF_ENTITY;-;module.rell/entity[data]")
        chkKdlsType("struct<state>", "state:DEF_OBJECT;-;module.rell/object[state]")
        chkKdlsType("struct<op>", "op:DEF_OPERATION;-;module.rell/operation[op]")

        chkKdlsType("struct<lib.data>", "lib:DEF_IMPORT_MODULE;-;module.rell/import[lib]", "data:DEF_ENTITY;-;lib.rell/entity[data]")
        chkKdlsType("struct<lib.state>", "lib:DEF_IMPORT_MODULE;-;module.rell/import[lib]", "state:DEF_OBJECT;-;lib.rell/object[state]")
        chkKdlsType("struct<lib.op>", "lib:DEF_IMPORT_MODULE;-;module.rell/import[lib]", "op:DEF_OPERATION;-;lib.rell/operation[op]")
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

        val libRef = "lib:DEF_IMPORT_MODULE;-;module.rell/import[lib]"
        val ab = arrayOf("a:DEF_NAMESPACE;-;lib.rell/namespace[a]", "b:DEF_NAMESPACE;-;lib.rell/namespace[a.b]")
        chkKdlsExpr("data1 @* {}", "data1:DEF_ENTITY;-;module.rell/entity[data1]")
        chkKdlsExpr("fdata1() @* {}", "fdata1:DEF_FUNCTION;-;module.rell/function[fdata1]")
        chkKdlsExpr("lib.data2 @* {}", libRef, "data2:DEF_ENTITY;-;lib.rell/entity[data2]")
        chkKdlsExpr("lib.fdata2() @* {}", libRef, "fdata2:DEF_FUNCTION;-;lib.rell/function[fdata2]")
        chkKdlsExpr("lib.a.b.data3 @* {}", libRef, *ab, "data3:DEF_ENTITY;-;lib.rell/entity[a.b.data3]")
        chkKdlsExpr("lib.a.b.fdata3() @* {}", libRef, *ab, "fdata3:DEF_FUNCTION;-;lib.rell/function[a.b.fdata3]")
    }

    @Test fun testExprAtItem() {
        file("module.rell", """
            entity data { name; }
            function fdata() = [1,2,3];
            namespace ns { entity data2 { name; } }
        """)

        chkKdlsExpr("data @* {} ( data )", "data:DEF_ENTITY;-;module.rell/entity[data]", "data:LOC_AT_ALIAS;-;local[data:0]")
        chkKdlsExpr("data @* {} ( $ )", "data:DEF_ENTITY;-;module.rell/entity[data]", "$:LOC_AT_ALIAS;-;local[data:0]")
        chkKdlsExpr("(x: data) @* {} ( x )", "x:LOC_AT_ALIAS;-;-", "data:DEF_ENTITY;-;module.rell/entity[data]", "x:LOC_AT_ALIAS;-;local[x:0]")

        val ns = "ns:DEF_NAMESPACE;-;module.rell/namespace[ns]"
        val data2 = "data2:DEF_ENTITY;-;module.rell/entity[ns.data2]"
        chkKdlsExpr("ns.data2 @* {} ( data2 )", ns, data2, "data2:LOC_AT_ALIAS;-;local[data2:0]")
        chkKdlsExpr("ns.data2 @* {} ( $ )", ns, data2, "$:LOC_AT_ALIAS;-;local[data2:0]")
        chkKdlsExpr("(x: ns.data2) @* {} ( x )", "x:LOC_AT_ALIAS;-;-", ns, data2, "x:LOC_AT_ALIAS;-;local[x:0]")

        val fdata = "fdata:DEF_FUNCTION;-;module.rell/function[fdata]"
        chkKdlsExpr("fdata() @* {} ( $ )", fdata, "$:LOC_AT_ALIAS;-;local[fdata:0]")
        chkKdlsExpr("(x: fdata()) @* {} ( x )", "x:LOC_AT_ALIAS;-;-", fdata, "x:LOC_AT_ALIAS;-;local[x:0]")
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

        val attrId = "function[main].tuple[_0].attr"
        val attrLink = "module.rell/entity[data].attr"

        val data = "data:DEF_ENTITY;-;module.rell/entity[data]"
        val ref = "ref:MEM_ENTITY_ATTR_NORMAL;-;module.rell/entity[data].attr[ref]"
        chkKdlsExpr("data @* {} ( .i )", data, "i:MEM_ENTITY_ATTR_NORMAL;$attrId[i];module.rell/entity[data].attr[i]")
        chkKdlsExpr("data @* {} ( .t )", data, "t:MEM_ENTITY_ATTR_NORMAL;$attrId[t];module.rell/entity[data].attr[t]")
        chkKdlsExpr("data @* {} ( .ref )", data, "ref:MEM_ENTITY_ATTR_NORMAL;$attrId[ref];module.rell/entity[data].attr[ref]")
        chkKdlsExpr("data @* {} ( .ref.p )", data, ref, "p:MEM_ENTITY_ATTR_NORMAL;-;module.rell/entity[ref].attr[p]")
        chkKdlsExpr("data @* {} ( .ref.q )", data, ref, "q:MEM_ENTITY_ATTR_NORMAL;-;module.rell/entity[ref].attr[q]")
        chkKdlsExpr("data @* {} ( .rowid )", data, "rowid:MEM_ENTITY_ATTR_ROWID;$attrId[rowid];-")
        chkKdlsExpr("data @* {} ( .ref.rowid )", data, ref, "rowid:MEM_ENTITY_ATTR_ROWID;-;-")

        val spec = "spec:DEF_ENTITY;-;module.rell/entity[spec]"
        chkKdlsExpr("spec @* {} ( .n1 )", spec, "n1:MEM_ENTITY_ATTR_NORMAL;$attrId[n1];module.rell/entity[spec].attr[n1]")
        chkKdlsExpr("spec @* {} ( .n2 )", spec, "n2:MEM_ENTITY_ATTR_NORMAL_VAR;$attrId[n2];module.rell/entity[spec].attr[n2]")
        chkKdlsExpr("spec @* {} ( .k1 )", spec, "k1:MEM_ENTITY_ATTR_KEY;$attrId[k1];module.rell/entity[spec].attr[k1]")
        chkKdlsExpr("spec @* {} ( .k2 )", spec, "k2:MEM_ENTITY_ATTR_KEY_VAR;$attrId[k2];module.rell/entity[spec].attr[k2]")
        chkKdlsExpr("spec @* {} ( .i1 )", spec, "i1:MEM_ENTITY_ATTR_INDEX;$attrId[i1];module.rell/entity[spec].attr[i1]")
        chkKdlsExpr("spec @* {} ( .i2 )", spec, "i2:MEM_ENTITY_ATTR_INDEX_VAR;$attrId[i2];module.rell/entity[spec].attr[i2]")

        chkKdlsExprErr("data @* {} ( .bad )", "expr_attr_unknown:bad", data, "bad:UNKNOWN;-;-")
        chkKdlsExprErr("data @* {} ( .ref.bad )", "unknown_member:[ref]:bad", data, ref, "bad:UNKNOWN;-;-")

        val aliasData = "data:LOC_AT_ALIAS;-;local[data:0]"
        chkKdlsExpr("data @* {} ( data.i )", data, aliasData, "i:MEM_ENTITY_ATTR_NORMAL;$attrId[i];module.rell/entity[data].attr[i]")
        chkKdlsExpr("data @* {} ( data.ref )", data, aliasData, "ref:MEM_ENTITY_ATTR_NORMAL;$attrId[ref];module.rell/entity[data].attr[ref]")
        chkKdlsExpr("data @* {} ( data.ref.p )", data, aliasData, ref, "p:MEM_ENTITY_ATTR_NORMAL;-;module.rell/entity[ref].attr[p]")
        chkKdlsExpr("data @* {} ( data.rowid )", data, aliasData, "rowid:MEM_ENTITY_ATTR_ROWID;$attrId[rowid];-")
        chkKdlsExpr("data @* {} ( data.ref.rowid )", data, aliasData, ref, "rowid:MEM_ENTITY_ATTR_ROWID;-;-")

        val aliasD = "d:LOC_AT_ALIAS;-;local[d:0]"
        chkKdlsExpr("(d:data) @* {} ( d.i )", "d:LOC_AT_ALIAS;-;-", data, aliasD, "i:MEM_ENTITY_ATTR_NORMAL;$attrId[i];$attrLink[i]")
        chkKdlsExpr("(d:data) @* {} ( d.ref )", "d:LOC_AT_ALIAS;-;-", data, aliasD, "ref:MEM_ENTITY_ATTR_NORMAL;$attrId[ref];$attrLink[ref]")
        chkKdlsExpr("(d:data) @* {} ( d.rowid )", "d:LOC_AT_ALIAS;-;-", data, aliasD, "rowid:MEM_ENTITY_ATTR_ROWID;$attrId[rowid];-")

        chkKdlsExprErr("(a:data,b:data) @* {} ( .i )", "at_attr_name_ambig:i:[a:data:i,b:data:i]",
                "a:LOC_AT_ALIAS;-;-",
                data,
                "b:LOC_AT_ALIAS;-;-",
                data,
                "i:MEM_ENTITY_ATTR_NORMAL;$attrId[i];module.rell/entity[data].attr[i]"
        )
    }

    @Test fun testExprAtAttrCol() {
        file("module.rell", """
            struct ref { p: integer; mutable q: text; }
            struct data { ref; i: integer; mutable t: text; }
            function datas() = list<data>();
        """)

        val datas = "datas:DEF_FUNCTION;-;module.rell/function[datas]"
        val ref = "ref:MEM_STRUCT_ATTR;-;module.rell/struct[data].attr[ref]"
        val attrId = "function[main].tuple[_0].attr"
        val attrLink = "module.rell/struct[data].attr"

        chkKdlsExpr("datas() @* {} ( .i )", datas, "i:MEM_STRUCT_ATTR;$attrId[i];$attrLink[i]")
        chkKdlsExpr("datas() @* {} ( .t )", datas, "t:MEM_STRUCT_ATTR_VAR;$attrId[t];$attrLink[t]")
        chkKdlsExpr("datas() @* {} ( .ref )", datas, "ref:MEM_STRUCT_ATTR;$attrId[ref];$attrLink[ref]")
        chkKdlsExpr("datas() @* {} ( .ref.p )", datas, ref, "p:MEM_STRUCT_ATTR;-;module.rell/struct[ref].attr[p]")
        chkKdlsExpr("datas() @* {} ( .ref.q )", datas, ref, "q:MEM_STRUCT_ATTR_VAR;-;module.rell/struct[ref].attr[q]")

        val (aliasDef, aliasRef) = "d:LOC_AT_ALIAS;-;-" to "d:LOC_AT_ALIAS;-;local[d:0]"
        chkKdlsExpr("(d:datas()) @* {} ( d.i )", aliasDef, datas, aliasRef, "i:MEM_STRUCT_ATTR;$attrId[i];$attrLink[i]")
        chkKdlsExpr("(d:datas()) @* {} ( d.ref )", aliasDef, datas, aliasRef, "ref:MEM_STRUCT_ATTR;$attrId[ref];$attrLink[ref]")
        chkKdlsExpr("(d:datas()) @* {} ( d.ref.p )", aliasDef, datas, aliasRef, ref, "p:MEM_STRUCT_ATTR;-;module.rell/struct[ref].attr[p]")

        chkKdlsExprErr("datas() @* {} ( .bad )", "expr_attr_unknown:bad", datas, "bad:UNKNOWN;-;-")
        chkKdlsExprErr("datas() @* {} ( .ref.bad )", "unknown_member:[ref]:bad", datas, ref, "bad:UNKNOWN;-;-")
    }

    @Test fun testExprAtWhat() {
        file("module.rell", """
            entity data {
                n1: integer = 0; mutable n2: integer = 0;
                key k1: integer = 0; key mutable k2: integer = 0;
                index i1: integer = 0; index mutable i2: integer = 0;
            }
        """)

        val data = "data:DEF_ENTITY;-;module.rell/entity[data]"
        val attrId = "function[main].tuple[_0].attr"
        val attrLink = "module.rell/entity[data].attr"
        chkKdlsExpr("data @ {} ( .n1 )", data, "n1:MEM_ENTITY_ATTR_NORMAL;$attrId[n1];$attrLink[n1]")
        chkKdlsExpr("data @ {} ( .n2 )", data, "n2:MEM_ENTITY_ATTR_NORMAL_VAR;$attrId[n2];$attrLink[n2]")
        chkKdlsExpr("data @ {} ( .k1 )", data, "k1:MEM_ENTITY_ATTR_KEY;$attrId[k1];$attrLink[k1]")
        chkKdlsExpr("data @ {} ( .k2 )", data, "k2:MEM_ENTITY_ATTR_KEY_VAR;$attrId[k2];$attrLink[k2]")
        chkKdlsExpr("data @ {} ( .i1 )", data, "i1:MEM_ENTITY_ATTR_INDEX;$attrId[i1];$attrLink[i1]")
        chkKdlsExpr("data @ {} ( .i2 )", data, "i2:MEM_ENTITY_ATTR_INDEX_VAR;$attrId[i2];$attrLink[i2]")

        val aliasDol = "$:LOC_AT_ALIAS;-;local[data:0]"
        chkKdlsExpr("data @ {} ( $.n1 )", data, aliasDol, "n1:MEM_ENTITY_ATTR_NORMAL;$attrId[n1];$attrLink[n1]")
        chkKdlsExpr("data @ {} ( $.n2 )", data, aliasDol, "n2:MEM_ENTITY_ATTR_NORMAL_VAR;$attrId[n2];$attrLink[n2]")
        chkKdlsExpr("data @ {} ( $.k1 )", data, aliasDol, "k1:MEM_ENTITY_ATTR_KEY;$attrId[k1];$attrLink[k1]")
        chkKdlsExpr("data @ {} ( $.k2 )", data, aliasDol, "k2:MEM_ENTITY_ATTR_KEY_VAR;$attrId[k2];$attrLink[k2]")
        chkKdlsExpr("data @ {} ( $.i1 )", data, aliasDol, "i1:MEM_ENTITY_ATTR_INDEX;$attrId[i1];$attrLink[i1]")
        chkKdlsExpr("data @ {} ( $.i2 )", data, aliasDol, "i2:MEM_ENTITY_ATTR_INDEX_VAR;$attrId[i2];$attrLink[i2]")

        val aliasData = "data:LOC_AT_ALIAS;-;local[data:0]"
        chkKdlsExpr("data @ {} ( data.n1 )", data, aliasData, "n1:MEM_ENTITY_ATTR_NORMAL;$attrId[n1];$attrLink[n1]")
        chkKdlsExpr("data @ {} ( data.n2 )", data, aliasData, "n2:MEM_ENTITY_ATTR_NORMAL_VAR;$attrId[n2];$attrLink[n2]")
        chkKdlsExpr("data @ {} ( data.k1 )", data, aliasData, "k1:MEM_ENTITY_ATTR_KEY;$attrId[k1];$attrLink[k1]")
        chkKdlsExpr("data @ {} ( data.k2 )", data, aliasData, "k2:MEM_ENTITY_ATTR_KEY_VAR;$attrId[k2];$attrLink[k2]")
        chkKdlsExpr("data @ {} ( data.i1 )", data, aliasData, "i1:MEM_ENTITY_ATTR_INDEX;$attrId[i1];$attrLink[i1]")
        chkKdlsExpr("data @ {} ( data.i2 )", data, aliasData, "i2:MEM_ENTITY_ATTR_INDEX_VAR;$attrId[i2];$attrLink[i2]")

        chkKdlsExpr("data @ {}.n1", data, "n1:MEM_ENTITY_ATTR_NORMAL;-;$attrLink[n1]")
        chkKdlsExpr("data @ {}.n2", data, "n2:MEM_ENTITY_ATTR_NORMAL_VAR;-;$attrLink[n2]")
        chkKdlsExpr("data @ {}.k1", data, "k1:MEM_ENTITY_ATTR_KEY;-;$attrLink[k1]")
        chkKdlsExpr("data @ {}.k2", data, "k2:MEM_ENTITY_ATTR_KEY_VAR;-;$attrLink[k2]")
        chkKdlsExpr("data @ {}.i1", data, "i1:MEM_ENTITY_ATTR_INDEX;-;$attrLink[i1]")
        chkKdlsExpr("data @ {}.i2", data, "i2:MEM_ENTITY_ATTR_INDEX_VAR;-;$attrLink[i2]")

        val tupleAttrBase = "MEM_TUPLE_ATTR;function[main].tuple[_0]"
        chkKdlsExpr("data @ {} ( a = $ )", data, "a:$tupleAttrBase.attr[a];-", "$:LOC_AT_ALIAS;-;local[data:0]")
        chkKdlsExpr("data @ {} ( x = .n1 )", data, "x:$tupleAttrBase.attr[x];-", "n1:MEM_ENTITY_ATTR_NORMAL;-;$attrLink[n1]")
    }

    @Test fun testExprAtWhatAnnotations() {
        file("module.rell", "entity data { x: integer; }")

        val data = "data:DEF_ENTITY;-;module.rell/entity[data]"
        val x = "x:MEM_ENTITY_ATTR_NORMAL;function[main].tuple[_0].attr[x];module.rell/entity[data].attr[x]"
        chkKdlsExpr("data @* {} ( .x )", data, x)
        chkKdlsExpr("data @* {} ( @sort .x )", data, "sort:MOD_ANNOTATION;-;-", x)
        chkKdlsExpr("data @* {} ( @sort_desc .x )", data, "sort_desc:MOD_ANNOTATION;-;-", x)
        chkKdlsExpr("data @* {} ( @group .x )", data, "group:MOD_ANNOTATION;-;-", x)
        chkKdlsExpr("data @* {} ( @min .x )", data, "min:MOD_ANNOTATION;-;-", "x:MEM_ENTITY_ATTR_NORMAL;-;module.rell/entity[data].attr[x]")
        chkKdlsExpr("data @* {} ( @max .x )", data, "max:MOD_ANNOTATION;-;-", "x:MEM_ENTITY_ATTR_NORMAL;-;module.rell/entity[data].attr[x]")
        chkKdlsExpr("data @* {} ( @sum .x )", data, "sum:MOD_ANNOTATION;-;-", "x:MEM_ENTITY_ATTR_NORMAL;-;module.rell/entity[data].attr[x]")
    }

    @Test fun testExprTypeSysMembers() {
        file("module.rell", """
            entity data { name; }
            struct rec { x: integer = 123; }
            function g() = gtv.from_json('');
        """)

        val g = "g:DEF_FUNCTION;-;module.rell/function[g]"
        chkKdlsExpr("data.from_gtv(g())", "data:DEF_ENTITY;-;module.rell/entity[data]", "from_gtv:DEF_FUNCTION_SYSTEM;-;-", g)
        chkKdlsExpr("rec.from_gtv(g())", "rec:DEF_STRUCT;-;module.rell/struct[rec]", "from_gtv:DEF_FUNCTION_SYSTEM;-;-", g)
        chkKdlsExpr("(data@{}).to_gtv()", "data:DEF_ENTITY;-;module.rell/entity[data]", "to_gtv:DEF_FUNCTION_SYSTEM;-;-")
        chkKdlsExpr("rec().to_gtv()", "rec:DEF_STRUCT;-;module.rell/struct[rec]", "to_gtv:DEF_FUNCTION_SYSTEM;-;-")

        chkKdlsExpr("gtv.from_json('')", "gtv:DEF_TYPE;-;-", "from_json:DEF_FUNCTION_SYSTEM;-;-")
        chkKdlsExpr("g().to_bytes()", g, "to_bytes:DEF_FUNCTION_SYSTEM;-;-")

        chkKdlsExpr("(123).to_gtv()", "to_gtv:DEF_FUNCTION_SYSTEM;-;-")
        chkKdlsExpr("(123).to_hex()", "to_hex:DEF_FUNCTION_SYSTEM;-;-")
    }

    @Test fun testExprEnumMembers() {
        file("module.rell", "enum colors { red, green, blue }")

        val colors = "colors:DEF_ENUM;-;module.rell/enum[colors]"
        chkKdlsExpr("colors.red", colors, "red:MEM_ENUM_VALUE;-;module.rell/enum[colors].value[red]")
        chkKdlsExpr("colors.green", colors, "green:MEM_ENUM_VALUE;-;module.rell/enum[colors].value[green]")
        chkKdlsExpr("colors.blue", colors, "blue:MEM_ENUM_VALUE;-;module.rell/enum[colors].value[blue]")

        chkKdlsExpr("colors.red.name", colors, "red:MEM_ENUM_VALUE;-;module.rell/enum[colors].value[red]", "name:MEM_STRUCT_ATTR;-;-")
        chkKdlsExpr("colors.red.value", colors, "red:MEM_ENUM_VALUE;-;module.rell/enum[colors].value[red]", "value:MEM_STRUCT_ATTR;-;-")

        chkKdlsExpr("colors.values()", colors, "values:DEF_FUNCTION_SYSTEM;-;-")
        chkKdlsExpr("colors.value('red')", colors, "value:DEF_FUNCTION_SYSTEM;-;-")
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

        val g = "g:DEF_FUNCTION;function[g];-"
        chkKdls("function g() { var v: data; }", g, "v:LOC_VAR;-;-", "data:DEF_ENTITY;-;module.rell/entity[data]")
        chkKdls("function g() { var v: rec; }", g, "v:LOC_VAR;-;-", "rec:DEF_STRUCT;-;module.rell/struct[rec]")
        chkKdls("function g() { var v: colors; }", g, "v:LOC_VAR;-;-", "colors:DEF_ENUM;-;module.rell/enum[colors]")
        chkKdls("function g() { var v: struct<state>; }", g, "v:LOC_VAR;-;-", "state:DEF_OBJECT;-;module.rell/object[state]")
        chkKdls("function g() { var v: struct<op>; }", g, "v:LOC_VAR;-;-", "op:DEF_OPERATION;-;module.rell/operation[op]")
        chkKdls("function g() { return MAGIC; }", g, "MAGIC:DEF_CONSTANT;-;module.rell/constant[MAGIC]")
        chkKdls("function g() { return qu(); }", g, "qu:DEF_QUERY;-;module.rell/query[qu]")
        chkKdls("function g() { f(); }", g, "f:DEF_FUNCTION;-;module.rell/function[f]")
    }

    @Test fun testExprCall() {
        tst.testLib = true
        file("module.rell", """
            function f(x: integer, y: text) = 0;
            function p(): (integer) -> integer = f(y = '', *);
            operation o(x: integer) {}
        """)

        val f = "f:DEF_FUNCTION;-;module.rell/function[f]"
        val argBase = "EXPR_CALL_ARG;-;module.rell/function[f].param"
        chkKdlsExpr("f(x = 123, y = 'Hello')", f, "x:$argBase[x]", "y:$argBase[y]")
        chkKdlsExpr("f(y = 'Hello', x = 123)", f, "y:$argBase[y]", "x:$argBase[x]")

        chkKdlsExprErr("f(x = 123, y = 'Hello', foo = 0)", "expr:call:unknown_named_arg:[f]:foo",
                f,
                "x:$argBase[x]",
                "y:$argBase[y]",
                "foo:UNKNOWN;-;-"
        )

        chkKdlsExprErr("f()", "expr:call:missing_args:[f]:0:x,1:y", f)
        chkKdlsExprErr("f(123)", "expr:call:missing_args:[f]:1:y", f)
        chkKdlsExprErr("f('hello', 123)", "expr_call_argtype:[f]:0:x:integer:text,expr_call_argtype:[f]:1:y:text:integer", f)
        chkKdlsExprErr("f(123, 'hello', true)", "expr:call:too_many_args:[f]:2:3", f)

        chkKdlsExpr("o(x = 123)", "o:DEF_OPERATION;-;module.rell/operation[o]", "x:EXPR_CALL_ARG;-;module.rell/operation[o].param[x]")

        chkKdlsExprErr("o(x = 123, foo = 456)", "expr:call:unknown_named_arg:[o]:foo",
                "o:DEF_OPERATION;-;module.rell/operation[o]",
                "x:EXPR_CALL_ARG;-;module.rell/operation[o].param[x]",
                "foo:UNKNOWN;-;-"
        )

        chkKdlsExprErr("p()(x = 123)", "expr:call:missing_args:[?]:0,expr:call:unknown_named_arg:[?]:x",
                "p:DEF_FUNCTION;-;module.rell/function[p]",
                "x:UNKNOWN;-;-"
        )

        chkKdlsExprErr("integer.from_text(s = '123')", "expr:call:named_args_not_allowed:[integer.from_text]:s",
                "integer:DEF_TYPE;-;-",
                "from_text:DEF_FUNCTION_SYSTEM;-;-",
                "s:UNKNOWN;-;-"
        )

        chkKdlsExprErr("'Hello'.size(x = 123)", "expr_call_argtypes:[text.size]:integer,expr:call:named_args_not_allowed:[text.size]:x",
                "size:DEF_FUNCTION_SYSTEM;-;-",
                "x:UNKNOWN;-;-"
        )

        chkKdlsExprErr("'Hello'.char_at(i = 123)", "expr:call:named_args_not_allowed:[text.char_at]:i",
                "char_at:DEF_FUNCTION_SYSTEM;-;-",
                "i:UNKNOWN;-;-"
        )

        chkKdlsExprErr("33(x = 1)", "expr_call_nofn:integer", "x:UNKNOWN;-;-")
    }

    @Test fun testExprCreate() {
        file("module.rell", """
            entity data {
                n1: integer = 0; mutable n2: integer = 0;
                key k1: integer = 0; key mutable k2: integer = 0;
                index i1: integer = 0; index mutable i2: integer = 0;
            }
        """)

        val data = "data:DEF_ENTITY;-;module.rell/entity[data]"
        chkKdlsExpr("create data()", data)
        chkKdlsExpr("create data(n1 = 1)", data, "n1:MEM_ENTITY_ATTR_NORMAL;-;module.rell/entity[data].attr[n1]")
        chkKdlsExpr("create data(n2 = 1)", data, "n2:MEM_ENTITY_ATTR_NORMAL_VAR;-;module.rell/entity[data].attr[n2]")
        chkKdlsExpr("create data(k1 = 1)", data, "k1:MEM_ENTITY_ATTR_KEY;-;module.rell/entity[data].attr[k1]")
        chkKdlsExpr("create data(k2 = 1)", data, "k2:MEM_ENTITY_ATTR_KEY_VAR;-;module.rell/entity[data].attr[k2]")
        chkKdlsExpr("create data(i1 = 1)", data, "i1:MEM_ENTITY_ATTR_INDEX;-;module.rell/entity[data].attr[i1]")
        chkKdlsExpr("create data(i2 = 1)", data, "i2:MEM_ENTITY_ATTR_INDEX_VAR;-;module.rell/entity[data].attr[i2]")
        chkKdlsExprErr("create data(foo = 123)", "attr_unknown_name:foo", data, "foo:UNKNOWN;-;-")
    }

    @Test fun testExprStruct() {
        file("module.rell", "struct data { x: integer = 0; mutable y: integer = 0; }")

        val data ="data:DEF_STRUCT;-;module.rell/struct[data]"
        chkKdlsExpr("data()", data)
        chkKdlsExpr("data(x = 1)", data, "x:MEM_STRUCT_ATTR;-;module.rell/struct[data].attr[x]")
        chkKdlsExpr("data(y = 1)", data, "y:MEM_STRUCT_ATTR_VAR;-;module.rell/struct[data].attr[y]")
        chkKdlsExprErr("data(foo = 1)", "attr_unknown_name:foo", data, "foo:UNKNOWN;-;-")
    }

    @Test fun testExprMirrorStructCreate() {
        initMirrorStruct()

        val data = "data:DEF_ENTITY;-;module.rell/entity[data]"
        chkKdlsExpr("struct<data>(n1 = 1)", data, "n1:MEM_STRUCT_ATTR;-;module.rell/entity[data].attr[n1]")
        chkKdlsExpr("struct<data>(n2 = 1)", data, "n2:MEM_STRUCT_ATTR;-;module.rell/entity[data].attr[n2]")
        chkKdlsExpr("struct<data>(k1 = 1)", data, "k1:MEM_STRUCT_ATTR;-;module.rell/entity[data].attr[k1]")
        chkKdlsExpr("struct<data>(k2 = 1)", data, "k2:MEM_STRUCT_ATTR;-;module.rell/entity[data].attr[k2]")
        chkKdlsExpr("struct<data>(i1 = 1)", data, "i1:MEM_STRUCT_ATTR;-;module.rell/entity[data].attr[i1]")
        chkKdlsExpr("struct<data>(i2 = 1)", data, "i2:MEM_STRUCT_ATTR;-;module.rell/entity[data].attr[i2]")
        chkKdlsExprErr("struct<data>(foo = 1)", "attr_unknown_name:foo", data, "foo:UNKNOWN;-;-")

        chkKdlsExpr("struct<mutable data>(n1 = 1)", data, "n1:MEM_STRUCT_ATTR_VAR;-;module.rell/entity[data].attr[n1]")
        chkKdlsExpr("struct<mutable data>(n2 = 1)", data, "n2:MEM_STRUCT_ATTR_VAR;-;module.rell/entity[data].attr[n2]")
        chkKdlsExpr("struct<mutable data>(k1 = 1)", data, "k1:MEM_STRUCT_ATTR_VAR;-;module.rell/entity[data].attr[k1]")
        chkKdlsExpr("struct<mutable data>(k2 = 1)", data, "k2:MEM_STRUCT_ATTR_VAR;-;module.rell/entity[data].attr[k2]")
        chkKdlsExpr("struct<mutable data>(i1 = 1)", data, "i1:MEM_STRUCT_ATTR_VAR;-;module.rell/entity[data].attr[i1]")
        chkKdlsExpr("struct<mutable data>(i2 = 1)", data, "i2:MEM_STRUCT_ATTR_VAR;-;module.rell/entity[data].attr[i2]")
        chkKdlsExprErr("struct<mutable data>(foo = 1)", "attr_unknown_name:foo", data, "foo:UNKNOWN;-;-")

        val state = "state:DEF_OBJECT;-;module.rell/object[state]"
        chkKdlsExpr("struct<state>(x = 1)", state, "x:MEM_STRUCT_ATTR;-;module.rell/object[state].attr[x]")
        chkKdlsExpr("struct<state>(y = 1)", state, "y:MEM_STRUCT_ATTR;-;module.rell/object[state].attr[y]")
        chkKdlsExprErr("struct<state>(foo = 1)", "attr_unknown_name:foo", state, "foo:UNKNOWN;-;-")
        chkKdlsExpr("struct<mutable state>(x = 1)", state, "x:MEM_STRUCT_ATTR_VAR;-;module.rell/object[state].attr[x]")
        chkKdlsExpr("struct<mutable state>(y = 1)", state, "y:MEM_STRUCT_ATTR_VAR;-;module.rell/object[state].attr[y]")
        chkKdlsExprErr("struct<mutable state>(foo = 1)", "attr_unknown_name:foo", state, "foo:UNKNOWN;-;-")

        val op = "op:DEF_OPERATION;-;module.rell/operation[op]"
        chkKdlsExpr("struct<op>(x = 1)", op, "x:MEM_STRUCT_ATTR;-;module.rell/operation[op].param[x]")
        chkKdlsExprErr("struct<op>(foo = 1)", "attr_unknown_name:foo", op, "foo:UNKNOWN;-;-")
        chkKdlsExpr("struct<mutable op>(x = 1)", op, "x:MEM_STRUCT_ATTR_VAR;-;module.rell/operation[op].param[x]")
        chkKdlsExprErr("struct<mutable op>(foo = 1)", "attr_unknown_name:foo", op, "foo:UNKNOWN;-;-")
    }

    @Test fun testExprMirrorStructAttrs() {
        initMirrorStruct()

        val data = "data:DEF_ENTITY;-;module.rell/entity[data]"
        chkKdlsExpr("struct<data>().n1", data, "n1:MEM_STRUCT_ATTR;-;module.rell/entity[data].attr[n1]")
        chkKdlsExpr("struct<data>().n2", data, "n2:MEM_STRUCT_ATTR;-;module.rell/entity[data].attr[n2]")
        chkKdlsExpr("struct<data>().k1", data, "k1:MEM_STRUCT_ATTR;-;module.rell/entity[data].attr[k1]")
        chkKdlsExpr("struct<data>().k2", data, "k2:MEM_STRUCT_ATTR;-;module.rell/entity[data].attr[k2]")
        chkKdlsExpr("struct<data>().i1", data, "i1:MEM_STRUCT_ATTR;-;module.rell/entity[data].attr[i1]")
        chkKdlsExpr("struct<data>().i2", data, "i2:MEM_STRUCT_ATTR;-;module.rell/entity[data].attr[i2]")
        chkKdlsExprErr("struct<data>().foo", "unknown_member:[struct<data>]:foo", data, "foo:UNKNOWN;-;-")

        chkKdlsExpr("struct<mutable data>().n1", data, "n1:MEM_STRUCT_ATTR_VAR;-;module.rell/entity[data].attr[n1]")
        chkKdlsExpr("struct<mutable data>().n2", data, "n2:MEM_STRUCT_ATTR_VAR;-;module.rell/entity[data].attr[n2]")
        chkKdlsExpr("struct<mutable data>().k1", data, "k1:MEM_STRUCT_ATTR_VAR;-;module.rell/entity[data].attr[k1]")
        chkKdlsExpr("struct<mutable data>().k2", data, "k2:MEM_STRUCT_ATTR_VAR;-;module.rell/entity[data].attr[k2]")
        chkKdlsExpr("struct<mutable data>().i1", data, "i1:MEM_STRUCT_ATTR_VAR;-;module.rell/entity[data].attr[i1]")
        chkKdlsExpr("struct<mutable data>().i2", data, "i2:MEM_STRUCT_ATTR_VAR;-;module.rell/entity[data].attr[i2]")
        chkKdlsExprErr("struct<mutable data>().foo", "unknown_member:[struct<mutable data>]:foo", data, "foo:UNKNOWN;-;-")

        val state = "state:DEF_OBJECT;-;module.rell/object[state]"
        chkKdlsExpr("struct<state>().x", state, "x:MEM_STRUCT_ATTR;-;module.rell/object[state].attr[x]")
        chkKdlsExpr("struct<state>().y", state, "y:MEM_STRUCT_ATTR;-;module.rell/object[state].attr[y]")
        chkKdlsExprErr("struct<state>().foo", "unknown_member:[struct<state>]:foo", state, "foo:UNKNOWN;-;-")
        chkKdlsExpr("struct<mutable state>().x", state, "x:MEM_STRUCT_ATTR_VAR;-;module.rell/object[state].attr[x]")
        chkKdlsExpr("struct<mutable state>().y", state, "y:MEM_STRUCT_ATTR_VAR;-;module.rell/object[state].attr[y]")
        chkKdlsExprErr("struct<mutable state>().foo", "unknown_member:[struct<mutable state>]:foo", state, "foo:UNKNOWN;-;-")

        val op = "op:DEF_OPERATION;-;module.rell/operation[op]"
        chkKdlsExpr("struct<op>().x", op, "x:MEM_STRUCT_ATTR;-;module.rell/operation[op].param[x]")
        chkKdlsExprErr("struct<op>().foo", "unknown_member:[struct<op>]:foo", op, "foo:UNKNOWN;-;-")
        chkKdlsExpr("struct<mutable op>().x", op, "x:MEM_STRUCT_ATTR_VAR;-;module.rell/operation[op].param[x]")
        chkKdlsExprErr("struct<mutable op>().foo", "unknown_member:[struct<mutable op>]:foo", op, "foo:UNKNOWN;-;-")
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
        val def = "f:DEF_FUNCTION;function[f];-"
        val integer = "integer:DEF_TYPE;-;-"
        chkKdls("function f() { val x = 123; return x; }", def, "x:LOC_VAL;-;-", "x:LOC_VAL;-;local[x:0]")
        chkKdls("function f() { var x = 123; return x; }", def, "x:LOC_VAR;-;-", "x:LOC_VAR;-;local[x:0]")
        chkKdls("function f() { val x: integer; x = 123; }", def, "x:LOC_VAL;-;-", integer, "x:LOC_VAL;-;local[x:0]")
        chkKdls("function f() { var x: integer; x = 123; }", def, "x:LOC_VAR;-;-", integer, "x:LOC_VAR;-;local[x:0]")

        val paramId = "function[f].param[x]"
        chkKdls("function f(x: integer) = x;", def, "x:LOC_PARAMETER;$paramId;-", integer, "x:LOC_PARAMETER;-;main.rell/$paramId")
        chkKdls("function f(x: integer) { return x; }", def, "x:LOC_PARAMETER;$paramId;-", integer, "x:LOC_PARAMETER;-;main.rell/$paramId")
    }

    @Test fun testExprMember() {
        file("module.rell", "struct p { y: integer; } struct s { x: integer; p = p(456); }")

        val s = "s:DEF_STRUCT;-;module.rell/struct[s]"
        chkKdlsExpr("s(123)", s)
        chkKdlsExpr("s(123).x", s, "x:MEM_STRUCT_ATTR;-;module.rell/struct[s].attr[x]")
        chkKdlsExpr("s(123).p.y", s, "p:MEM_STRUCT_ATTR;-;module.rell/struct[s].attr[p]", "y:MEM_STRUCT_ATTR;-;module.rell/struct[p].attr[y]")
        chkKdlsExprErr("s()", "attr_missing:x", s)
        chkKdlsExprErr("s().x", "attr_missing:x", s, "x:UNKNOWN;-;-")
        chkKdlsExprErr("s().p.x", "attr_missing:x", s, "p:UNKNOWN;-;-", "x:UNKNOWN;-;-")

        chkKdlsExprErr("abs().a", "expr_call_argtypes:[abs]:", "abs:DEF_FUNCTION_SYSTEM;-;-", "a:UNKNOWN;-;-")
        chkKdlsExprErr("abs().f()", "expr_call_argtypes:[abs]:", "abs:DEF_FUNCTION_SYSTEM;-;-", "f:UNKNOWN;-;-")
        chkKdlsExprErr("abs().a.b", "expr_call_argtypes:[abs]:", "abs:DEF_FUNCTION_SYSTEM;-;-", "a:UNKNOWN;-;-", "b:UNKNOWN;-;-")
        chkKdlsExprErr("abs().a.b()", "expr_call_argtypes:[abs]:", "abs:DEF_FUNCTION_SYSTEM;-;-", "a:UNKNOWN;-;-", "b:UNKNOWN;-;-")
    }

    @Test fun testExprObjectAttr() {
        file("module.rell", "object state { x: integer = 123; mutable y: integer = 456; }")

        val state = "state:DEF_OBJECT;-;module.rell/object[state]"
        chkKdlsExpr("state.x", state, "x:MEM_ENTITY_ATTR_NORMAL;-;module.rell/object[state].attr[x]")
        chkKdlsExpr("state.y", state, "y:MEM_ENTITY_ATTR_NORMAL_VAR;-;module.rell/object[state].attr[y]")

        chkKdls("function f() { state.y = 789; }",
            "f:DEF_FUNCTION;*;-",
            state,
            "y:MEM_ENTITY_ATTR_NORMAL_VAR;-;module.rell/object[state].attr[y]"
        )
    }

    @Test fun testExprTuple() {
        file("module.rell", "function f() = (a = 123, b = 'hello');")

        chkKdlsExpr("(a = 123, b = 'hello')",
            "a:MEM_TUPLE_ATTR;function[main].tuple[_0].attr[a];-",
            "b:MEM_TUPLE_ATTR;function[main].tuple[_0].attr[b];-"
        )

        chkKdlsExpr("f().a", "f:DEF_FUNCTION;-;*", "a:MEM_TUPLE_ATTR;-;module.rell/function[f].tuple[_0].attr[a]")
        chkKdlsExpr("f().b", "f:DEF_FUNCTION;-;*", "b:MEM_TUPLE_ATTR;-;module.rell/function[f].tuple[_0].attr[b]")

        chkKdls("function g() { var x: (a:integer, b:text); }",
                "g:DEF_FUNCTION;*;-",
                "x:LOC_VAR;-;-",
                "a:MEM_TUPLE_ATTR;function[g].tuple[_0].attr[a];-",
                "integer:DEF_TYPE;-;-",
                "b:MEM_TUPLE_ATTR;function[g].tuple[_0].attr[b];-",
                "text:DEF_TYPE;-;-"
        )
    }

    @Test fun testExprModule() {
        file("lib.rell", "module; namespace ns { function f() = 123; }")
        file("module.rell", "import lib; import dup: lib;")

        chkKdlsExpr("lib.ns.f()",
            "lib:DEF_IMPORT_MODULE;-;module.rell/import[lib]",
            "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]",
            "f:DEF_FUNCTION;-;lib.rell/function[ns.f]"
        )

        chkKdlsExpr("dup.ns.f()",
            "dup:EXPR_IMPORT_ALIAS;-;module.rell/import[dup]",
            "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]",
            "f:DEF_FUNCTION;-;lib.rell/function[ns.f]"
        )
    }

    @Test fun testMisc() {
        chkKdls("function f(x: integer) = x * x;",
                "f:DEF_FUNCTION;function[f];-",
                "x:LOC_PARAMETER;function[f].param[x];-",
                "integer:DEF_TYPE;-;-",
                "x:LOC_PARAMETER;-;main.rell/function[f].param[x]",
                "x:LOC_PARAMETER;-;main.rell/function[f].param[x]"
        )
    }

    @Test fun testUnknownType() {
        file("lib.rell", "module; namespace ns { entity data { name; } }")
        file("module.rell", "import lib;")

        val lib = "lib:DEF_IMPORT_MODULE;-;module.rell/import[lib]"
        val ns = "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]"

        chkKdls("struct s { x: lib.ns.data; }",
                "s:DEF_STRUCT;struct[s];-",
                "x:MEM_STRUCT_ATTR;struct[s].attr[x];-",
                lib, ns,
                "data:DEF_ENTITY;-;lib.rell/entity[ns.data]"
        )

        chkUnknownType("lib.ns.c", "lib.ns.c", lib, ns, "c:UNKNOWN;-;-")
        chkUnknownType("lib.b.c", "lib.b", lib, "b:UNKNOWN;-;-", "c:UNKNOWN;-;-")
        chkUnknownType("a.b.c", "a", "a:UNKNOWN;-;-", "b:UNKNOWN;-;-", "c:UNKNOWN;-;-")
    }

    private fun chkUnknownType(type: String, unknown: String, vararg expected: String) {
        chkKdlsErr("struct s { x: $type; }", "unknown_name:$unknown",
            "s:DEF_STRUCT;struct[s];-",
            "x:MEM_STRUCT_ATTR;struct[s].attr[x];-",
            *expected
        )
    }

    @Test fun testUnknownEntity() {
        file("lib.rell", "module; namespace ns { entity data { name = 'Bob'; } }")
        file("module.rell", "import lib;")

        val lib = "lib:DEF_IMPORT_MODULE;-;module.rell/import[lib]"
        chkKdlsExpr("create lib.ns.data()", lib, "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]", "data:DEF_ENTITY;-;lib.rell/entity[ns.data]")
        chkUnknownEntity("lib.ns.c", "lib.ns.c", lib, "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]", "c:UNKNOWN;-;-")
        chkUnknownEntity("lib.b.c", "lib.b", lib, "b:UNKNOWN;-;-", "c:UNKNOWN;-;-")
        chkUnknownEntity("a.b.c", "a", "a:UNKNOWN;-;-", "b:UNKNOWN;-;-", "c:UNKNOWN;-;-")
    }

    @Test fun testUnknownMirrorStruct() {
        chkKdlsExprErr("struct<foo>()", "unknown_name:foo", "foo:UNKNOWN;-;-")
        chkKdlsExprErr("struct<foo.bar>()", "unknown_name:foo", "foo:UNKNOWN;-;-", "bar:UNKNOWN;-;-")
    }

    @Test fun testUnknownAnonAttr() {
        val f = "f:DEF_FUNCTION;function[f];-"
        chkKdlsErr("function f(foo) {}", "unknown_name:foo", f, "foo:UNKNOWN;function[f].param[foo];-")
        chkKdlsErr("function f(foo?) {}", "unknown_name:foo", f, "foo:UNKNOWN;function[f].param[foo];-")
        chkKdlsErr("function f(foo.bar) {}", "unknown_name:foo", f, "foo:UNKNOWN;-;-", "bar:UNKNOWN;function[f].param[bar];-")
    }

    private fun chkUnknownEntity(type: String, unknown: String, vararg expected: String) {
        chkKdlsExprErr("create $type()", "unknown_name:$unknown", *expected)
    }
}
