/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.ide

import org.junit.Test

class IdeSymbolInfoImportTest: BaseIdeSymbolInfoTest() {
    @Test fun testImportModuleDef() {
        file("lib.rell", "module; namespace a { struct s {} }")
        file("dir/module.rell", "module;")
        file("dir/sub.rell", "module;")

        chkSyms("import lib; struct z { x: lib.a.s; }",
                "lib:DEF_IMPORT_MODULE",
                "z:DEF_STRUCT",
                "x:MEM_STRUCT_ATTR",
                "lib:DEF_IMPORT_MODULE",
                "a:DEF_NAMESPACE",
                "s:DEF_STRUCT"
        )

        chkSyms("import bil: lib; struct z { x: bil.a.s; }",
                "bil:DEF_IMPORT_ALIAS",
                "lib:DEF_IMPORT_MODULE",
                "z:DEF_STRUCT",
                "x:MEM_STRUCT_ATTR",
                "bil:EXPR_IMPORT_ALIAS",
                "a:DEF_NAMESPACE",
                "s:DEF_STRUCT"
        )

        chkSymsErr("import foo;", "import:not_found:foo", "foo:UNKNOWN")
        chkSymsErr("import foo.bar;", "import:not_found:foo.bar", "foo:UNKNOWN", "bar:UNKNOWN")

        chkSyms("import dir;", "dir:DEF_IMPORT_MODULE")
        chkSyms("import dir.sub;", "dir:DEF_IMPORT_MODULE", "sub:DEF_IMPORT_MODULE")
        chkSymsErr("import dir.foo;", "import:not_found:dir.foo", "dir:UNKNOWN", "foo:UNKNOWN")
        chkSymsErr("import dir.sub.foo;", "import:not_found:dir.sub.foo", "dir:UNKNOWN", "sub:UNKNOWN", "foo:UNKNOWN")
    }

    @Test fun testImportModuleExpr() {
        file("lib.rell", "module; namespace a { struct s {} }")
        file("dir/module.rell", "module;")
        file("dir/sub.rell", "module; namespace b { struct p {} }")
        file("module.rell", """
            import lib;
            import bil: lib;
            import dir.sub;
            import bus: dir.sub;
        """)

        chkExpr("lib.a.s()", "lib:DEF_IMPORT_MODULE", "a:DEF_NAMESPACE", "s:DEF_STRUCT")
        chkExpr("bil.a.s()", "bil:EXPR_IMPORT_ALIAS", "a:DEF_NAMESPACE", "s:DEF_STRUCT")
        chkExpr("sub.b.p()", "sub:DEF_IMPORT_MODULE", "b:DEF_NAMESPACE", "p:DEF_STRUCT")
        chkExpr("bus.b.p()", "bus:EXPR_IMPORT_ALIAS", "b:DEF_NAMESPACE", "p:DEF_STRUCT")
    }

    @Test fun testImportExactDef() {
        initImportExact()

        chkSyms("import k: lib.{data, state, f};",
                "k:DEF_NAMESPACE",
                "lib:DEF_IMPORT_MODULE",
                "data:DEF_ENTITY",
                "state:DEF_OBJECT",
                "f:DEF_FUNCTION_REGULAR"
        )

        chkSyms("import lib.{p: data, q: state, r: f};",
                "lib:DEF_IMPORT_MODULE",
                "p:DEF_ENTITY",
                "data:DEF_ENTITY",
                "q:DEF_OBJECT",
                "state:DEF_OBJECT",
                "r:DEF_FUNCTION_REGULAR",
                "f:DEF_FUNCTION_REGULAR"
        )

        chkSyms("import lib.{ns.p};", "lib:DEF_IMPORT_MODULE", "ns:DEF_NAMESPACE", "p:DEF_STRUCT")
    }

    @Test fun testImportExactExpr() {
        initImportExact()
        file("module.rell", """
            import lib.{data1:data, state1:state, f1:f};
            import lib.{data2:data};
            import lib.{state2:state};
            import lib.{f2:f};
            import a: lib.{data, state, f};
            import b: lib.{data3:data, state3:state, f3:f};
        """)

        chkExpr("create data1('bob')", "data1:DEF_ENTITY")
        chkExpr("state1.x", "state1:DEF_OBJECT", "x:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("f1()", "f1:DEF_FUNCTION_REGULAR")

        chkExpr("create data2('bob')", "data2:DEF_ENTITY")
        chkExpr("state2.x", "state2:DEF_OBJECT", "x:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("f2()", "f2:DEF_FUNCTION_REGULAR")

        chkExpr("create a.data('bob')", "a:DEF_NAMESPACE", "data:DEF_ENTITY")
        chkExpr("a.state.x", "a:DEF_NAMESPACE", "state:DEF_OBJECT", "x:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("a.f()", "a:DEF_NAMESPACE", "f:DEF_FUNCTION_REGULAR")

        chkExpr("create b.data3('bob')", "b:DEF_NAMESPACE", "data3:DEF_ENTITY")
        chkExpr("b.state3.x", "b:DEF_NAMESPACE", "state3:DEF_OBJECT", "x:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("b.f3()", "b:DEF_NAMESPACE", "f3:DEF_FUNCTION_REGULAR")
    }

    private fun initImportExact() {
        file("lib.rell", """
            module;
            entity data { name; }
            object state { x: integer = 1; }
            function f() = 123;
            namespace ns { struct p {} }
        """)
    }

    @Test fun testImportExactConflict() {
        file("lib.rell", "module; function f(): integer = 123;")

        chkSymsErr(
                "function f(): integer = 123; import lib.{f};",
                "name_conflict:user:f:IMPORT:main.rell(1:42),name_conflict:user:f:FUNCTION:main.rell(1:10)",
                "f:DEF_FUNCTION_REGULAR",
                "integer:DEF_TYPE",
                "lib:DEF_IMPORT_MODULE",
                "f:UNKNOWN"
        )
    }

    @Test fun testImportWildcardDef() {
        file("lib.rell", """
            module;
            entity data { name; }
            object state { x: integer = 1; }
            function f() = 123;
            namespace ns { struct rec {} }
        """)

        chkSyms("import k: lib.*;", "k:DEF_NAMESPACE", "lib:DEF_IMPORT_MODULE")
        chkSyms("import k: lib.{ns.*};", "k:DEF_NAMESPACE", "lib:DEF_IMPORT_MODULE", "ns:DEF_NAMESPACE")
    }

    @Test fun testImportWildcardExpr() {
        file("lib.rell", """
            module;
            entity data { name; }
            object state { x: integer = 1; }
            function f() = 123;
        """)
        file("module.rell", """
            namespace a { import lib.*; }
            import b: lib.*;
        """)

        chkExpr("create a.data('bob')", "a:DEF_NAMESPACE", "data:DEF_ENTITY")
        chkExpr("a.state.x", "a:DEF_NAMESPACE", "state:DEF_OBJECT", "x:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("a.f()", "a:DEF_NAMESPACE", "f:DEF_FUNCTION_REGULAR")

        chkExpr("create b.data('bob')", "b:DEF_NAMESPACE", "data:DEF_ENTITY")
        chkExpr("b.state.x", "b:DEF_NAMESPACE", "state:DEF_OBJECT", "x:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("b.f()", "b:DEF_NAMESPACE", "f:DEF_FUNCTION_REGULAR")
    }

    @Test fun testImportComplexExact() {
        initImportComplex()

        val ns = "ns:DEF_NAMESPACE"

        chkImportComplex("sub", "sub:DEF_IMPORT_MODULE")
        chkImportComplex("bus", "bus:EXPR_IMPORT_ALIAS")
        chkImportComplex("sub.s1", "sub:DEF_IMPORT_MODULE", "s1:DEF_STRUCT")
        chkImportComplex("bus.s1", "bus:EXPR_IMPORT_ALIAS", "s1:DEF_STRUCT")
        chkImportComplex("sub.subns", "sub:DEF_IMPORT_MODULE", "subns:DEF_NAMESPACE")
        chkImportComplex("bus.subns", "bus:EXPR_IMPORT_ALIAS", "subns:DEF_NAMESPACE")
        chkImportComplex("sub.subns.s2", "sub:DEF_IMPORT_MODULE", "subns:DEF_NAMESPACE", "s2:DEF_STRUCT")
        chkImportComplex("bus.subns.s2", "bus:EXPR_IMPORT_ALIAS", "subns:DEF_NAMESPACE", "s2:DEF_STRUCT")

        chkImportComplex("data", "data:DEF_ENTITY")
        chkImportComplex("f", "f:DEF_FUNCTION_REGULAR")
        chkImportComplex("ns", ns)
        chkImportComplex("ns.p", ns, "p:DEF_STRUCT")

        chkImportComplex("ns.sub", ns, "sub:DEF_IMPORT_MODULE")
        chkImportComplex("ns.bus", ns, "bus:EXPR_IMPORT_ALIAS")
        chkImportComplex("ns.sub.s1", ns, "sub:DEF_IMPORT_MODULE", "s1:DEF_STRUCT")
        chkImportComplex("ns.bus.s1", ns, "bus:EXPR_IMPORT_ALIAS", "s1:DEF_STRUCT")
        chkImportComplex("ns.sub.subns", ns, "sub:DEF_IMPORT_MODULE", "subns:DEF_NAMESPACE")
        chkImportComplex("ns.bus.subns", ns, "bus:EXPR_IMPORT_ALIAS", "subns:DEF_NAMESPACE")
        chkImportComplex("ns.sub.subns.s2", ns, "sub:DEF_IMPORT_MODULE", "subns:DEF_NAMESPACE", "s2:DEF_STRUCT")
        chkImportComplex("ns.bus.subns.s2", ns, "bus:EXPR_IMPORT_ALIAS", "subns:DEF_NAMESPACE", "s2:DEF_STRUCT")
    }

    @Test fun testImportComplexExactUnknown() {
        initImportComplex()

        val ns = "ns:DEF_NAMESPACE"

        chkImportComplexErr("Z", "Z:UNKNOWN")
        chkImportComplexErr("ns.Z", ns, "Z:UNKNOWN")
        chkImportComplexErr("sub.Z", "sub:DEF_IMPORT_MODULE", "Z:UNKNOWN")
        chkImportComplexErr("bus.Z", "bus:EXPR_IMPORT_ALIAS", "Z:UNKNOWN")
        chkImportComplexErr("sub.subns.Z", "sub:DEF_IMPORT_MODULE", "subns:DEF_NAMESPACE", "Z:UNKNOWN")
        chkImportComplexErr("bus.subns.Z", "bus:EXPR_IMPORT_ALIAS", "subns:DEF_NAMESPACE", "Z:UNKNOWN")

        chkImportComplexErr("ns.sub.Z", ns, "sub:DEF_IMPORT_MODULE", "Z:UNKNOWN")
        chkImportComplexErr("ns.bus.Z", ns, "bus:EXPR_IMPORT_ALIAS", "Z:UNKNOWN")
        chkImportComplexErr("ns.sub.subns.Z", ns, "sub:DEF_IMPORT_MODULE", "subns:DEF_NAMESPACE", "Z:UNKNOWN")
        chkImportComplexErr("ns.bus.subns.Z", ns, "bus:EXPR_IMPORT_ALIAS", "subns:DEF_NAMESPACE", "Z:UNKNOWN")

        val zyx = arrayOf("Z:UNKNOWN", "Y:UNKNOWN", "X:UNKNOWN")
        chkImportComplexErr("Z.Y.X", *zyx)
        chkImportComplexErr("sub.Z.Y.X", "sub:DEF_IMPORT_MODULE", *zyx)
        chkImportComplexErr("sub.subns.Z.Y.X", "sub:DEF_IMPORT_MODULE", "subns:DEF_NAMESPACE", *zyx)
        chkImportComplexErr("ns.sub.Z.Y.X", ns, "sub:DEF_IMPORT_MODULE", *zyx)
        chkImportComplexErr("ns.sub.subns.Z.Y.X", ns, "sub:DEF_IMPORT_MODULE", "subns:DEF_NAMESPACE", *zyx)
    }

    private fun initImportComplex() {
        file("lib.rell", """
            module;
            import sub;
            import bus: sub;
            entity data { name; }
            function f() = 123;
            namespace ns {
                import sub;
                import bus: sub;
                struct p {}
            }
        """)
        file("sub.rell", """
            module;
            struct s1 {}
            namespace subns {
                struct s2 {}
            }
        """)
    }

    private fun chkImportComplex(name: String, vararg expected: String) {
        val expAlias = expected.last().split(':')[1]
        chkSyms("import lib.{$name};", "lib:DEF_IMPORT_MODULE", *expected)
        chkSyms("import lib.{x: $name};", "lib:DEF_IMPORT_MODULE", "x:$expAlias", *expected)
    }

    private fun chkImportComplexErr(name: String, vararg expected: String) {
        val expAlias = expected.last().split(':')[1]
        chkSymsErr("import lib.{$name};", "import:name_unknown:Z", "lib:DEF_IMPORT_MODULE", *expected)
        chkSymsErr("import lib.{x: $name};", "import:name_unknown:Z", "lib:DEF_IMPORT_MODULE", "x:$expAlias", *expected)
    }

    @Test fun testImportAllKindsModule() {
        initImportAllKinds()
        file("module.rell", "import lib;")

        val lib = "lib:DEF_IMPORT_MODULE"
        chkExpr("create lib.data('Bob')", lib, "data:DEF_ENTITY")
        chkExpr("lib.state.x", lib, "state:DEF_OBJECT", "x:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("lib.rec(123)", lib, "rec:DEF_STRUCT")
        chkExpr("lib.colors.red", lib, "colors:DEF_ENUM", "red:MEM_ENUM_VALUE")
        chkExpr("lib.op()", lib, "op:DEF_OPERATION")
        chkExpr("lib.q()", lib, "q:DEF_QUERY")
        chkExpr("lib.f()", lib, "f:DEF_FUNCTION_REGULAR")
        chkExpr("lib.MAGIC", lib, "MAGIC:DEF_CONSTANT")
        chkExpr("lib.ns.p()", lib, "ns:DEF_NAMESPACE", "p:DEF_STRUCT")
    }

    @Test fun testImportAllKindsExact() {
        initImportAllKinds()

        fun c(name: String, expImport: String, expr: String, vararg expExpr: String) {
            val code = "import lib.{$name}; function test() = $expr;"
            chkSyms(code, "lib:DEF_IMPORT_MODULE", expImport, "test:DEF_FUNCTION_REGULAR", *expExpr)
        }

        c("data", "data:DEF_ENTITY", "create data('Bob')", "data:DEF_ENTITY")
        c("state", "state:DEF_OBJECT", "state.x", "state:DEF_OBJECT", "x:MEM_ENTITY_ATTR_NORMAL")
        c("rec", "rec:DEF_STRUCT", "rec(123)", "rec:DEF_STRUCT")
        c("colors", "colors:DEF_ENUM", "colors.red", "colors:DEF_ENUM", "red:MEM_ENUM_VALUE")
        c("op", "op:DEF_OPERATION", "op()", "op:DEF_OPERATION")
        c("q", "q:DEF_QUERY", "q()", "q:DEF_QUERY")
        c("f", "f:DEF_FUNCTION_REGULAR", "f()", "f:DEF_FUNCTION_REGULAR")
        c("MAGIC", "MAGIC:DEF_CONSTANT", "MAGIC", "MAGIC:DEF_CONSTANT")
        c("ns", "ns:DEF_NAMESPACE", "ns.p()", "ns:DEF_NAMESPACE", "p:DEF_STRUCT")
    }

    @Test fun testImportAllKindsWildcard() {
        initImportAllKinds()
        file("module.rell", "import lib.*;")

        chkExpr("create data('Bob')", "data:DEF_ENTITY")
        chkExpr("state.x", "state:DEF_OBJECT", "x:MEM_ENTITY_ATTR_NORMAL")
        chkExpr("rec(123)", "rec:DEF_STRUCT")
        chkExpr("colors.red", "colors:DEF_ENUM", "red:MEM_ENUM_VALUE")
        chkExpr("op()", "op:DEF_OPERATION")
        chkExpr("q()", "q:DEF_QUERY")
        chkExpr("f()", "f:DEF_FUNCTION_REGULAR")
        chkExpr("MAGIC", "MAGIC:DEF_CONSTANT")
        chkExpr("ns.p()", "ns:DEF_NAMESPACE", "p:DEF_STRUCT")
    }

    private fun initImportAllKinds() {
        tst.testLib = true
        file("lib.rell", """
            module;
            entity data { name; }
            object state { x: integer = 1; }
            struct rec { x: integer; }
            enum colors { red, green, blue }
            operation op() {}
            query q() = 123;
            function f() = 123;
            val MAGIC = 123;
            namespace ns { struct p {} }
        """)
    }
}
