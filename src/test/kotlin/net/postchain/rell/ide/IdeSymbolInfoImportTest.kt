/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.ide

import org.junit.Test

class IdeSymbolInfoImportTest: BaseIdeSymbolTest() {
    @Test fun testImportExactBasic() {
        file("lib/f1.rell", "val k = 123;")
        file("lib/f2.rell", "function f() = 456;")

        val kRef = "k:DEF_CONSTANT;-;lib/f1.rell/constant[k]"
        val fRef = "f:DEF_FUNCTION;-;lib/f2.rell/function[f]"
        chkKdls("import lib.{k}; query q() = k;", "lib:DEF_IMPORT_MODULE;-;lib/f1.rell", kRef, "q:*", kRef)
        chkKdls("import lib.{f}; query q() = f();", "lib:DEF_IMPORT_MODULE;-;lib/f1.rell", fRef, "q:*;*;-", fRef)
        chkKdls("import lib.{k,f}; query q() = k;", "lib:DEF_IMPORT_MODULE;-;lib/f1.rell", kRef, fRef, "q:*;*;-", kRef)
        chkKdls("import lib.{k,f}; query q() = f();", "lib:DEF_IMPORT_MODULE;-;lib/f1.rell", kRef, fRef, "q:*;*;-", fRef)
    }

    @Test fun testImportExactOuterAlias() {
        file("lib/f1.rell", "val k = 123;")
        file("lib/f2.rell", "function f() = 456;")

        val lib = "lib:DEF_IMPORT_MODULE;-;lib/f1.rell"
        val aRef = "a:DEF_NAMESPACE;-;main.rell/namespace[a]"
        val kRef = "k:DEF_CONSTANT;-;lib/f1.rell/constant[k]"
        val fRef = "f:DEF_FUNCTION;-;lib/f2.rell/function[f]"
        chkKdls("import a:lib.{k}; query q() = a.k;", "a:DEF_NAMESPACE;namespace[a];-", lib, kRef, "q:*;*;-", aRef, kRef)
        chkKdls("import a:lib.{f}; query q() = a.f();", "a:DEF_NAMESPACE;namespace[a];-", lib, fRef, "q:*;*;-", aRef, fRef)
        chkKdls("import a:lib.{k,f}; query q() = a.k;", "a:DEF_NAMESPACE;namespace[a];-", lib, kRef, fRef, "q:*;*;-", aRef, kRef)
        chkKdls("import a:lib.{k,f}; query q() = a.f();", "a:DEF_NAMESPACE;namespace[a];-", lib, kRef, fRef, "q:*;*;-", aRef, fRef)
    }

    @Test fun testImportExactInnerAlias() {
        file("lib/f1.rell", "val k = 123;")
        file("lib/f2.rell", "function f() = 456;")

        val lib = "lib:DEF_IMPORT_MODULE;-;lib/f1.rell"
        val kRef = "k:DEF_CONSTANT;-;lib/f1.rell/constant[k]"
        val fRef = "f:DEF_FUNCTION;-;lib/f2.rell/function[f]"

        val x = "x:DEF_CONSTANT;import[x];-" to "x:DEF_CONSTANT;-;main.rell/import[x]"
        val y = "y:DEF_FUNCTION;import[y];-" to "y:DEF_FUNCTION;-;main.rell/import[y]"
        chkKdls("import lib.{x:k}; query q() = x;", lib, x.first, kRef, "q:*;*;-", x.second)
        chkKdls("import lib.{y:f}; query q() = y();", lib, y.first, fRef, "q:*;*;-", y.second)
        chkKdls("import lib.{x:k,y:f}; query q() = x;", lib, x.first, kRef, y.first, fRef, "q:*;*;-", x.second)
        chkKdls("import lib.{x:k,y:f}; query q() = y();", lib, x.first, kRef, y.first, fRef, "q:*;*;-", y.second)

        val a = "a:DEF_NAMESPACE;namespace[a];-" to "a:DEF_NAMESPACE;-;main.rell/namespace[a]"
        val ax = "x:DEF_CONSTANT;import[a.x];-" to "x:DEF_CONSTANT;-;main.rell/import[a.x]"
        val ay = "y:DEF_FUNCTION;import[a.y];-" to "y:DEF_FUNCTION;-;main.rell/import[a.y]"
        chkKdls("import a:lib.{x:k}; query q() = a.x;", a.first, lib, ax.first, kRef, "q:*;*;-", a.second, ax.second)
        chkKdls("import a:lib.{y:f}; query q() = a.y();", a.first, lib, ay.first, fRef, "q:*;*;-", a.second, ay.second)
        chkKdls("import a:lib.{x:k,y:f}; query q() = a.x;", a.first, lib, ax.first, kRef, ay.first, fRef, "q:*;*;-", a.second, ax.second)
        chkKdls("import a:lib.{x:k,y:f}; query q() = a.y();", a.first, lib, ax.first, kRef, ay.first, fRef, "q:*;*;-", a.second, ay.second)
    }

    @Test fun testImportExactNamespace() {
        file("lib.rell", "module; namespace a { namespace b { val k = 123; } }")
        chkFileKdls("lib.rell", "a:DEF_NAMESPACE;namespace[a];-", "b:DEF_NAMESPACE;namespace[a.b];-", "k:DEF_CONSTANT;constant[a.b.k];-")

        val aRef = "a:DEF_NAMESPACE;-;lib.rell/namespace[a]"
        val bRef = "b:DEF_NAMESPACE;-;lib.rell/namespace[a.b]"
        val kRef = "k:DEF_CONSTANT;-;lib.rell/constant[a.b.k]"
        chkKdls("import lib.{a}; query q() = a.b.k;", "lib:DEF_IMPORT_MODULE;-;lib.rell", aRef, "q:*;*;*", aRef, bRef, kRef)
        chkKdls("import lib.{a.b}; query q() = b.k;", "lib:DEF_IMPORT_MODULE;-;lib.rell", aRef, bRef, "q:*;*;*", bRef, kRef)
        chkKdls("import lib.{a.b.k}; query q() = k;", "lib:DEF_IMPORT_MODULE;-;lib.rell", aRef, bRef, kRef, "q:*;*;*", kRef)
    }

    @Test fun testImportExactWildcard() {
        file("lib.rell", "module; namespace a { namespace b { val k = 123; } }")
        chkFileKdls("lib.rell", "a:DEF_NAMESPACE;namespace[a];-", "b:DEF_NAMESPACE;namespace[a.b];-", "k:DEF_CONSTANT;constant[a.b.k];-")

        val aRef = "a:DEF_NAMESPACE;-;lib.rell/namespace[a]"
        val bRef = "b:DEF_NAMESPACE;-;lib.rell/namespace[a.b]"
        val kRef = "k:DEF_CONSTANT;-;lib.rell/constant[a.b.k]"
        chkKdls("import lib.{a.*}; query q() = b.k;", "lib:DEF_IMPORT_MODULE;-;lib.rell", aRef, "q:*;*;*", bRef, kRef)
        chkKdls("import lib.{a.b.*}; query q() = k;", "lib:DEF_IMPORT_MODULE;-;lib.rell", aRef, bRef, "q:*;*;*", kRef)
    }

    @Test fun testImportExactWildcardAlias() {
        file("lib.rell", "module; namespace a { namespace b { val k = 123; } }")
        chkFileKdls("lib.rell", "a:DEF_NAMESPACE;namespace[a];-", "b:DEF_NAMESPACE;namespace[a.b];-", "k:DEF_CONSTANT;constant[a.b.k];-")

        val lib = "lib:DEF_IMPORT_MODULE;-;lib.rell"
        val aRef = "a:DEF_NAMESPACE;-;lib.rell/namespace[a]"
        val abRef = "b:DEF_NAMESPACE;-;lib.rell/namespace[a.b]"
        val kRef = "k:DEF_CONSTANT;-;lib.rell/constant[a.b.k]"
        val ns = "ns:DEF_NAMESPACE;namespace[ns];-" to "ns:DEF_NAMESPACE;-;main.rell/namespace[ns]"
        val foo = "foo:DEF_NAMESPACE;namespace[foo];-" to "foo:DEF_NAMESPACE;-;main.rell/namespace[foo]"
        val fooNs = "ns:DEF_NAMESPACE;namespace[foo.ns];-" to "ns:DEF_NAMESPACE;-;main.rell/namespace[foo.ns]"

        chkKdls("import lib.{ns:a.*}; query q() = ns.b.k;", lib, ns.first, aRef, "q:*;*;*", ns.second, abRef, kRef)
        chkKdls("import foo:lib.{ns:a.*}; query q() = foo.ns.b.k;", foo.first, lib, fooNs.first, aRef, "q:*;*;*", foo.second, fooNs.second, abRef, kRef)
        chkKdls("import lib.{ns:a.b.*}; query q() = ns.k;", lib, ns.first, aRef, abRef, "q:*;*;*", ns.second, kRef)
        chkKdls("import foo:lib.{ns:a.b.*}; query q() = foo.ns.k;", foo.first, lib, fooNs.first, aRef, abRef, "q:*;*;*", foo.second, fooNs.second, kRef)
    }

    @Test fun testImportExactReimport() {
        file("sub.rell", "module; import lib.{f}; val k = 123;")
        file("lib.rell", "module; import sub.{k}; function f() = 456;")

        chkFileKdls("sub.rell", "lib:DEF_IMPORT_MODULE;-;lib.rell", "f:DEF_FUNCTION;-;lib.rell/function[f]", "k:DEF_CONSTANT;constant[k];-")
        chkFileKdls("lib.rell", "sub:DEF_IMPORT_MODULE;-;sub.rell", "k:DEF_CONSTANT;-;sub.rell/constant[k]", "f:DEF_FUNCTION;function[f];-")

        chkKdls("import lib.{k}; query q() = k;",
            "lib:DEF_IMPORT_MODULE;-;lib.rell",
            "k:DEF_CONSTANT;-;sub.rell/constant[k]",
            "q:*;*;*",
            "k:DEF_CONSTANT;-;sub.rell/constant[k]"
        )

        chkKdls("import sub.{f}; query q() = f();",
            "sub:DEF_IMPORT_MODULE;-;sub.rell",
            "f:DEF_FUNCTION;-;lib.rell/function[f]",
            "q:*;*;*",
            "f:DEF_FUNCTION;-;lib.rell/function[f]"
        )
    }

    @Test fun testImportExactPath() {
        file("lib.rell", "module; namespace foo { namespace bar { val k = 123; } }")

        chkFileKdls("lib.rell",
            "foo:DEF_NAMESPACE;namespace[foo];-",
            "bar:DEF_NAMESPACE;namespace[foo.bar];-",
            "k:DEF_CONSTANT;constant[foo.bar.k];-"
        )

        val lib = "lib:DEF_IMPORT_MODULE;-;lib.rell"
        val fooBar = "foo:DEF_NAMESPACE;-;lib.rell/namespace[foo]" to "bar:DEF_NAMESPACE;-;lib.rell/namespace[foo.bar]"
        val kRef = "k:DEF_CONSTANT;-;lib.rell/constant[foo.bar.k]"

        chkKdls("import lib.{foo.bar.k}; query q() = k;", lib, fooBar.first, fooBar.second, kRef, "q:*;*;-", kRef)

        chkKdls("import lib.{a : foo.bar.k}; query q() = a;",
            lib, "a:DEF_CONSTANT;import[a];-", fooBar.first, fooBar.second, kRef,
            "q:*;*;-",
            "a:DEF_CONSTANT;-;main.rell/import[a]",
        )
    }

    @Test fun testImportExactChain() {
        file("m1.rell", "module; namespace ns1 { import m2; }")
        file("m2.rell", "module; namespace ns2 { import m3.{ns3}; }")
        file("m3.rell", "module; namespace ns3 { val k = 123; }")

        chkFileKdls("m1.rell", "ns1:DEF_NAMESPACE;namespace[ns1];-", "m2:DEF_IMPORT_MODULE;import[ns1.m2];m2.rell")
        chkFileKdls("m2.rell", "ns2:DEF_NAMESPACE;namespace[ns2];-", "m3:DEF_IMPORT_MODULE;-;m3.rell", "ns3:DEF_NAMESPACE;-;m3.rell/namespace[ns3]")
        chkFileKdls("m3.rell", "ns3:DEF_NAMESPACE;namespace[ns3];-", "k:DEF_CONSTANT;constant[ns3.k];-")

        chkKdls("import m1.{ns1.m2.ns2.ns3.k}; query q() = k;",
            "m1:DEF_IMPORT_MODULE;-;m1.rell",
            "ns1:DEF_NAMESPACE;-;m1.rell/namespace[ns1]",
            "m2:DEF_IMPORT_MODULE;-;m1.rell/import[ns1.m2]",
            "ns2:DEF_NAMESPACE;-;m2.rell/namespace[ns2]",
            "ns3:DEF_NAMESPACE;-;m3.rell/namespace[ns3]",
            "k:DEF_CONSTANT;-;m3.rell/constant[ns3.k]",
            "q:*;*;-",
            "k:DEF_CONSTANT;-;m3.rell/constant[ns3.k]",
        )
    }

    @Test fun testImportExactDef() {
        initImportExact()

        chkKdls("import k: lib.{data, state, f};",
                "k:DEF_NAMESPACE;namespace[k];-",
                "lib:DEF_IMPORT_MODULE;-;lib.rell",
                "data:DEF_ENTITY;-;lib.rell/entity[data]",
                "state:DEF_OBJECT;-;lib.rell/object[state]",
                "f:DEF_FUNCTION;-;lib.rell/function[f]"
        )

        chkKdls("import lib.{p: data, q: state, r: f};",
                "lib:DEF_IMPORT_MODULE;-;lib.rell",
                "p:DEF_ENTITY;import[p];-",
                "data:DEF_ENTITY;-;lib.rell/entity[data]",
                "q:DEF_OBJECT;import[q];-",
                "state:DEF_OBJECT;-;lib.rell/object[state]",
                "r:DEF_FUNCTION;import[r];-",
                "f:DEF_FUNCTION;-;lib.rell/function[f]"
        )

        chkKdls("import lib.{ns.p};",
            "lib:DEF_IMPORT_MODULE;-;lib.rell",
            "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]",
            "p:DEF_STRUCT;-;lib.rell/struct[ns.p]"
        )
    }

    @Test fun testImportExactRef() {
        initImportExact()
        file("module.rell", """
            import lib.{data1:data, state1:state, f1:f};
            import lib.{data2:data};
            import lib.{state2:state};
            import lib.{f2:f};
            import a: lib.{data, state, f};
            import b: lib.{data3:data, state3:state, f3:f};
        """)

        chkKdlsExpr("create data1('bob')", "data1:DEF_ENTITY;-;module.rell/import[data1]")
        chkKdlsExpr("state1.x", "state1:DEF_OBJECT;-;module.rell/import[state1]", "x:MEM_ENTITY_ATTR_NORMAL;-;lib.rell/object[state].attr[x]")
        chkKdlsExpr("f1()", "f1:DEF_FUNCTION;-;module.rell/import[f1]")

        chkKdlsExpr("create data2('bob')", "data2:DEF_ENTITY;-;module.rell/import[data2]")
        chkKdlsExpr("state2.x", "state2:DEF_OBJECT;-;module.rell/import[state2]", "x:MEM_ENTITY_ATTR_NORMAL;-;lib.rell/object[state].attr[x]")
        chkKdlsExpr("f2()", "f2:DEF_FUNCTION;-;module.rell/import[f2]")

        val aRef = "a:DEF_NAMESPACE;-;module.rell/namespace[a]"
        chkKdlsExpr("create a.data('bob')", aRef, "data:DEF_ENTITY;-;lib.rell/entity[data]")
        chkKdlsExpr("a.state.x", aRef, "state:DEF_OBJECT;-;lib.rell/object[state]", "x:MEM_ENTITY_ATTR_NORMAL;-;lib.rell/object[state].attr[x]")
        chkKdlsExpr("a.f()", aRef, "f:DEF_FUNCTION;-;lib.rell/function[f]")

        val bRef = "b:DEF_NAMESPACE;-;module.rell/namespace[b]"
        chkKdlsExpr("create b.data3('bob')", bRef, "data3:DEF_ENTITY;-;module.rell/import[b.data3]")
        chkKdlsExpr("b.state3.x", bRef, "state3:DEF_OBJECT;-;module.rell/import[b.state3]", "x:MEM_ENTITY_ATTR_NORMAL;-;lib.rell/object[state].attr[x]")
        chkKdlsExpr("b.f3()", bRef, "f3:DEF_FUNCTION;-;module.rell/import[b.f3]")
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

        chkKdlsErr("function f(): integer = 123; import lib.{f};",
            "name_conflict:user:f:IMPORT:main.rell(1:42),name_conflict:user:f:FUNCTION:main.rell(1:10)",
            "f:DEF_FUNCTION;function[f];-",
            "integer:DEF_TYPE;-;*",
            "lib:DEF_IMPORT_MODULE;-;lib.rell",
            "f:UNKNOWN;-;-"
        )
    }

    @Test fun testImportModuleDef() {
        file("lib.rell", "module; namespace a { struct s {} }")
        file("dir/module.rell", "module;")
        file("dir/sub.rell", "module;")

        chkKdls("import lib; struct z { x: lib.a.s; }",
            "lib:DEF_IMPORT_MODULE;import[lib];lib.rell",
            "z:DEF_STRUCT;struct[z];-",
            "x:MEM_STRUCT_ATTR;struct[z].attr[x];-",
            "lib:DEF_IMPORT_MODULE;-;main.rell/import[lib]",
            "a:DEF_NAMESPACE;-;lib.rell/namespace[a]",
            "s:DEF_STRUCT;-;lib.rell/struct[a.s]"
        )

        chkKdls("import bil: lib; struct z { x: bil.a.s; }",
            "bil:DEF_IMPORT_ALIAS;import[bil];-",
            "lib:DEF_IMPORT_MODULE;-;lib.rell",
            "z:DEF_STRUCT;struct[z];-",
            "x:MEM_STRUCT_ATTR;struct[z].attr[x];-",
            "bil:EXPR_IMPORT_ALIAS;-;main.rell/import[bil]",
            "a:DEF_NAMESPACE;-;lib.rell/namespace[a]",
            "s:DEF_STRUCT;-;lib.rell/struct[a.s]"
        )

        chkKdlsErr("import foo;", "import:not_found:foo", "foo:UNKNOWN;-;-")
        chkKdlsErr("import foo.bar;", "import:not_found:foo.bar", "foo:UNKNOWN;-;-", "bar:UNKNOWN;-;-")

        chkKdls("import dir;", "dir:DEF_IMPORT_MODULE;import[dir];dir/module.rell")
        chkKdls("import dir.sub;", "dir:DEF_IMPORT_MODULE;-;dir/module.rell", "sub:DEF_IMPORT_MODULE;import[sub];dir/sub.rell")

        val dirImp = "dir:DEF_IMPORT_MODULE;-;dir/module.rell"
        chkKdlsErr("import dir.foo;", "import:not_found:dir.foo", dirImp, "foo:UNKNOWN;-;-")
        chkKdlsErr("import dir.sub.foo;", "import:not_found:dir.sub.foo", dirImp, "sub:DEF_IMPORT_MODULE;-;dir/sub.rell", "foo:UNKNOWN;-;-")
    }

    @Test fun testImportModuleRef() {
        file("lib.rell", "module; namespace a { struct s {} }")
        file("dir/module.rell", "module;")
        file("dir/sub.rell", "module; namespace b { struct p {} }")
        file("module.rell", """
            import lib;
            import bil: lib;
            import dir.sub;
            import bus: dir.sub;
        """)

        val aRef = "a:DEF_NAMESPACE;-;lib.rell/namespace[a]"
        chkKdlsExpr("lib.a.s()", "lib:DEF_IMPORT_MODULE;-;module.rell/import[lib]", aRef, "s:DEF_STRUCT;-;lib.rell/struct[a.s]")
        chkKdlsExpr("bil.a.s()", "bil:EXPR_IMPORT_ALIAS;-;module.rell/import[bil]", aRef, "s:DEF_STRUCT;-;lib.rell/struct[a.s]")

        val bRef = "b:DEF_NAMESPACE;-;dir/sub.rell/namespace[b]"
        chkKdlsExpr("sub.b.p()", "sub:DEF_IMPORT_MODULE;-;module.rell/import[sub]", bRef, "p:DEF_STRUCT;-;dir/sub.rell/struct[b.p]")
        chkKdlsExpr("bus.b.p()", "bus:EXPR_IMPORT_ALIAS;-;module.rell/import[bus]", bRef, "p:DEF_STRUCT;-;dir/sub.rell/struct[b.p]")
    }

    @Test fun testImportModuleAlias() {
        file("lib.rell", "module; val k = 123;")
        chkKdls("import ref: lib; query q() = ref.k;",
            "ref:DEF_IMPORT_ALIAS;import[ref];-",
            "lib:DEF_IMPORT_MODULE;-;lib.rell",
            "q:*;*;-",
            "ref:EXPR_IMPORT_ALIAS;-;main.rell/import[ref]",
            "k:DEF_CONSTANT;-;lib.rell/constant[k]"
        )
    }

    @Test fun testImportModuleFile() {
        file("lib.rell", "module; val k = 123;")
        chkKdls("import lib; query q() = lib.k;",
            "lib:DEF_IMPORT_MODULE;import[lib];lib.rell",
            "q:*;*;-",
            "lib:DEF_IMPORT_MODULE;-;main.rell/import[lib]",
            "k:DEF_CONSTANT;-;lib.rell/constant[k]"
        )
    }

    @Test fun testImportModuleDirMain() {
        file("mod/top.rell", "struct t {}")
        file("mod/module.rell", "struct u {}")
        file("mod/bot.rell", "val k = 123;")
        chkKdls("import mod; query q() = mod.k;",
            "mod:DEF_IMPORT_MODULE;import[mod];mod/module.rell",
            "q:*;*;-",
            "mod:DEF_IMPORT_MODULE;-;main.rell/import[mod]",
            "k:DEF_CONSTANT;-;mod/bot.rell/constant[k]"
        )
    }

    @Test fun testImportModuleDirNoMain() {
        file("dir/a.rell", "function f() = 123;")
        file("dir/b.rell", "function g() {}")
        chkKdls("import dir; query q() = dir.f();",
            "dir:DEF_IMPORT_MODULE;import[dir];dir/a.rell",
            "q:*;*;-",
            "dir:DEF_IMPORT_MODULE;-;main.rell/import[dir]",
            "f:DEF_FUNCTION;-;dir/a.rell/function[f]"
        )
    }

    @Test fun testImportModuleNested() {
        file("a/b/c.rell", "module; val k = 123;")
        file("x/module.rell", "module;")
        file("x/y/z.rell", "module; function f() = 456;")

        val ab = arrayOf("a:DEF_IMPORT_MODULE;-;-", "b:DEF_IMPORT_MODULE;-;-")
        val c = "c:DEF_IMPORT_MODULE;import[c];a/b/c.rell" to "c:DEF_IMPORT_MODULE;-;main.rell/import[c]"
        val kRef = "k:DEF_CONSTANT;-;a/b/c.rell/constant[k]"
        chkKdls("import a.b.c; query q() = c.k;", *ab, c.first, "q:*;*;-", c.second, kRef)
        chkKdls("import a.b.c.*; query q() = k;", *ab, "c:DEF_IMPORT_MODULE;-;a/b/c.rell", "q:*;*;-", kRef)
        chkKdls("import a.b.c.{k}; query q() = k;", *ab, "c:DEF_IMPORT_MODULE;-;a/b/c.rell", kRef, "q:*;*;-", kRef)

        val xy = arrayOf("x:DEF_IMPORT_MODULE;-;x/module.rell", "y:DEF_IMPORT_MODULE;-;-")
        val z = "z:DEF_IMPORT_MODULE;import[z];x/y/z.rell" to "z:DEF_IMPORT_MODULE;-;main.rell/import[z]"
        val fRef = "f:DEF_FUNCTION;-;x/y/z.rell/function[f]"
        chkKdls("import x.y.z; query q() = z.f();", *xy, z.first, "q:*;*;-", z.second, fRef)
        chkKdls("import x.y.z.*; query q() = f();", *xy, "z:DEF_IMPORT_MODULE;-;x/y/z.rell", "q:*;*;-", fRef)
        chkKdls("import x.y.z.{f}; query q() = f();", *xy, "z:DEF_IMPORT_MODULE;-;x/y/z.rell", fRef, "q:*;*;-", fRef)
    }

    @Test fun testImportModuleNotExists() {
        file("a/module.rell", "module;")
        file("a/b/c/module.rell", "module;")
        chkKdlsErr("import a.b.c.d;", "import:not_found:a.b.c.d",
            "a:DEF_IMPORT_MODULE;-;a/module.rell", "b:DEF_IMPORT_MODULE;-;-", "c:DEF_IMPORT_MODULE;-;a/b/c/module.rell",
            "d:UNKNOWN;-;-",
        )
        chkKdlsErr("import a.b.c.d.e;", "import:not_found:a.b.c.d.e",
            "a:DEF_IMPORT_MODULE;-;a/module.rell", "b:DEF_IMPORT_MODULE;-;-", "c:DEF_IMPORT_MODULE;-;a/b/c/module.rell",
            "d:UNKNOWN;-;-", "e:UNKNOWN;-;-",
        )
    }

    @Test fun testImportExactComplex() {
        initImportExactComplex()

        val ns = "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]"
        val subns = "subns:DEF_NAMESPACE;-;sub.rell/namespace[subns]"

        chkImportExactComplex("sub", "sub:DEF_IMPORT_MODULE;-;lib.rell/import[sub]")
        chkImportExactComplex("bus", "bus:EXPR_IMPORT_ALIAS;-;lib.rell/import[bus]")
        chkImportExactComplex("sub.s1", "sub:DEF_IMPORT_MODULE;-;lib.rell/import[sub]", "s1:DEF_STRUCT;-;sub.rell/struct[s1]")
        chkImportExactComplex("bus.s1", "bus:EXPR_IMPORT_ALIAS;-;lib.rell/import[bus]", "s1:DEF_STRUCT;-;sub.rell/struct[s1]")
        chkImportExactComplex("sub.subns", "sub:DEF_IMPORT_MODULE;-;lib.rell/import[sub]", subns)
        chkImportExactComplex("bus.subns", "bus:EXPR_IMPORT_ALIAS;-;lib.rell/import[bus]", subns)
        chkImportExactComplex("sub.subns.s2", "sub:DEF_IMPORT_MODULE;-;lib.rell/import[sub]", subns, "s2:DEF_STRUCT;-;sub.rell/struct[subns.s2]")
        chkImportExactComplex("bus.subns.s2", "bus:EXPR_IMPORT_ALIAS;-;lib.rell/import[bus]", subns, "s2:DEF_STRUCT;-;sub.rell/struct[subns.s2]")

        chkImportExactComplex("data", "data:DEF_ENTITY;-;lib.rell/entity[data]")
        chkImportExactComplex("f", "f:DEF_FUNCTION;-;lib.rell/function[f]")
        chkImportExactComplex("ns", ns)
        chkImportExactComplex("ns.p", ns, "p:DEF_STRUCT;-;lib.rell/struct[ns.p]")

        val nsSub = "sub:DEF_IMPORT_MODULE;-;lib.rell/import[ns.sub]"
        val nsBus = "bus:EXPR_IMPORT_ALIAS;-;lib.rell/import[ns.bus]"
        chkImportExactComplex("ns.sub", ns, nsSub)
        chkImportExactComplex("ns.bus", ns, nsBus)
        chkImportExactComplex("ns.sub.s1", ns, nsSub, "s1:DEF_STRUCT;-;sub.rell/struct[s1]")
        chkImportExactComplex("ns.bus.s1", ns, nsBus, "s1:DEF_STRUCT;-;sub.rell/struct[s1]")
        chkImportExactComplex("ns.sub.subns", ns, nsSub, subns)
        chkImportExactComplex("ns.bus.subns", ns, nsBus, subns)
        chkImportExactComplex("ns.sub.subns.s2", ns, nsSub, subns, "s2:DEF_STRUCT;-;sub.rell/struct[subns.s2]")
        chkImportExactComplex("ns.bus.subns.s2", ns, nsBus, subns, "s2:DEF_STRUCT;-;sub.rell/struct[subns.s2]")
    }

    @Test fun testImportExactComplexUnknown() {
        initImportExactComplex()

        val ns = "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]"
        val subns = "subns:DEF_NAMESPACE;-;sub.rell/namespace[subns]"

        chkImportExactComplexErr("Z", "Z:UNKNOWN;-;-")
        chkImportExactComplexErr("ns.Z", ns, "Z:UNKNOWN;-;-")
        chkImportExactComplexErr("sub.Z", "sub:DEF_IMPORT_MODULE;-;lib.rell/import[sub]", "Z:UNKNOWN;-;-")
        chkImportExactComplexErr("bus.Z", "bus:EXPR_IMPORT_ALIAS;-;lib.rell/import[bus]", "Z:UNKNOWN;-;-")
        chkImportExactComplexErr("sub.subns.Z", "sub:DEF_IMPORT_MODULE;-;lib.rell/import[sub]", subns, "Z:UNKNOWN;-;-")
        chkImportExactComplexErr("bus.subns.Z", "bus:EXPR_IMPORT_ALIAS;-;lib.rell/import[bus]", subns, "Z:UNKNOWN;-;-")

        chkImportExactComplexErr("ns.sub.Z", ns, "sub:DEF_IMPORT_MODULE;-;lib.rell/import[ns.sub]", "Z:UNKNOWN;-;-")
        chkImportExactComplexErr("ns.bus.Z", ns, "bus:EXPR_IMPORT_ALIAS;-;lib.rell/import[ns.bus]", "Z:UNKNOWN;-;-")
        chkImportExactComplexErr("ns.sub.subns.Z", ns, "sub:DEF_IMPORT_MODULE;-;lib.rell/import[ns.sub]", subns, "Z:UNKNOWN;-;-")
        chkImportExactComplexErr("ns.bus.subns.Z", ns, "bus:EXPR_IMPORT_ALIAS;-;lib.rell/import[ns.bus]", subns, "Z:UNKNOWN;-;-")

        val zyx = arrayOf("Z:UNKNOWN;-;-", "Y:UNKNOWN;-;-", "X:UNKNOWN;-;-")
        chkImportExactComplexErr("Z.Y.X", *zyx)
        chkImportExactComplexErr("sub.Z.Y.X", "sub:DEF_IMPORT_MODULE;-;lib.rell/import[sub]", *zyx)
        chkImportExactComplexErr("sub.subns.Z.Y.X", "sub:DEF_IMPORT_MODULE;-;lib.rell/import[sub]", subns, *zyx)
        chkImportExactComplexErr("ns.sub.Z.Y.X", ns, "sub:DEF_IMPORT_MODULE;-;lib.rell/import[ns.sub]", *zyx)
        chkImportExactComplexErr("ns.sub.subns.Z.Y.X", ns, "sub:DEF_IMPORT_MODULE;-;lib.rell/import[ns.sub]", subns, *zyx)
    }

    private fun initImportExactComplex() {
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

    private fun chkImportExactComplex(name: String, vararg expected: String) {
        val expAlias = replaceKdl(expected.last(), name = "x", defId = "import[x]", link = "-")
        chkKdls("import lib.{$name};", "lib:DEF_IMPORT_MODULE;-;lib.rell", *expected)
        chkKdls("import lib.{x: $name};", "lib:DEF_IMPORT_MODULE;-;lib.rell", expAlias, *expected)
    }

    private fun chkImportExactComplexErr(name: String, vararg expected: String) {
        val expAlias = replaceKdl(expected.last(), name = "x", defId = "import[x]")
        chkKdlsErr("import lib.{$name};", "import:name_unknown:Z", "lib:DEF_IMPORT_MODULE;-;lib.rell", *expected)
        chkKdlsErr("import lib.{x: $name};", "import:name_unknown:Z", "lib:DEF_IMPORT_MODULE;-;lib.rell", expAlias, *expected)
    }

    @Test fun testImportWildcard() {
        file("lib.rell", "module; val k = 123;")
        chkKdls("import lib.*; query q() = k;", "lib:DEF_IMPORT_MODULE;-;lib.rell", "q:*;*;-", "k:DEF_CONSTANT;-;lib.rell/constant[k]")
    }

    @Test fun testImportWildcardAlias() {
        file("lib.rell", "module; val k = 123;")
        chkKdls("import ns: lib.*; query q() = ns.k;",
            "ns:DEF_NAMESPACE;namespace[ns];-",
            "lib:DEF_IMPORT_MODULE;-;lib.rell",
            "q:*;*;-",
            "ns:DEF_NAMESPACE;-;main.rell/namespace[ns]",
            "k:DEF_CONSTANT;-;lib.rell/constant[k]"
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

        val kRef = "k:DEF_NAMESPACE;namespace[k];-"
        chkKdls("import k: lib.*;", kRef, "lib:DEF_IMPORT_MODULE;-;lib.rell")
        chkKdls("import k: lib.{ns.*};", kRef, "lib:DEF_IMPORT_MODULE;-;lib.rell", "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]")
    }

    @Test fun testImportWildcardRef() {
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

        val aRef = "a:DEF_NAMESPACE;-;module.rell/namespace[a]"
        chkKdlsExpr("create a.data('bob')", aRef, "data:DEF_ENTITY;-;lib.rell/entity[data]")
        chkKdlsExpr("a.state.x", aRef, "state:DEF_OBJECT;-;lib.rell/object[state]", "x:MEM_ENTITY_ATTR_NORMAL;-;lib.rell/object[state].attr[x]")
        chkKdlsExpr("a.f()", aRef, "f:DEF_FUNCTION;-;lib.rell/function[f]")

        val bRef = "b:DEF_NAMESPACE;-;module.rell/namespace[b]"
        chkKdlsExpr("create b.data('bob')", bRef, "data:DEF_ENTITY;-;lib.rell/entity[data]")
        chkKdlsExpr("b.state.x", bRef, "state:DEF_OBJECT;-;lib.rell/object[state]", "x:MEM_ENTITY_ATTR_NORMAL;-;lib.rell/object[state].attr[x]")
        chkKdlsExpr("b.f()", bRef, "f:DEF_FUNCTION;-;lib.rell/function[f]")
    }

    @Test fun testImportAllKindsModule() {
        initImportAllKinds()
        file("module.rell", "import lib;")

        val lib = "lib:DEF_IMPORT_MODULE;-;module.rell/import[lib]"
        chkKdlsExpr("create lib.data('Bob')", lib, "data:DEF_ENTITY;-;lib.rell/entity[data]")
        chkKdlsExpr("lib.state.x", lib, "state:DEF_OBJECT;-;lib.rell/object[state]", "x:MEM_ENTITY_ATTR_NORMAL;-;lib.rell/object[state].attr[x]")
        chkKdlsExpr("lib.rec(123)", lib, "rec:DEF_STRUCT;-;lib.rell/struct[rec]")
        chkKdlsExpr("lib.colors.red", lib, "colors:DEF_ENUM;-;lib.rell/enum[colors]", "red:MEM_ENUM_VALUE;-;lib.rell/enum[colors].value[red]")
        chkKdlsExpr("lib.op()", lib, "op:DEF_OPERATION;-;lib.rell/operation[op]")
        chkKdlsExpr("lib.q()", lib, "q:DEF_QUERY;-;lib.rell/query[q]")
        chkKdlsExpr("lib.f()", lib, "f:DEF_FUNCTION;-;lib.rell/function[f]")
        chkKdlsExpr("lib.MAGIC", lib, "MAGIC:DEF_CONSTANT;-;lib.rell/constant[MAGIC]")
        chkKdlsExpr("lib.ns.p()", lib, "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]", "p:DEF_STRUCT;-;lib.rell/struct[ns.p]")
    }

    @Test fun testImportAllKindsExact() {
        initImportAllKinds()

        chkImportAllKindsExact("data", "create data('Bob')", "data:DEF_ENTITY;-;lib.rell/entity[data]")
        chkImportAllKindsExact("rec", "rec(123)", "rec:DEF_STRUCT;-;lib.rell/struct[rec]")
        chkImportAllKindsExact("op", "op()", "op:DEF_OPERATION;-;lib.rell/operation[op]")
        chkImportAllKindsExact("q", "q()", "q:DEF_QUERY;-;lib.rell/query[q]")
        chkImportAllKindsExact("f", "f()", "f:DEF_FUNCTION;-;lib.rell/function[f]")
        chkImportAllKindsExact("MAGIC", "MAGIC", "MAGIC:DEF_CONSTANT;-;lib.rell/constant[MAGIC]")
        chkImportAllKindsExact("ns", "ns.p()", "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]", "p:DEF_STRUCT;-;lib.rell/struct[ns.p]")

        chkImportAllKindsExact("state", "state.x",
            "state:DEF_OBJECT;-;lib.rell/object[state]",
            "x:MEM_ENTITY_ATTR_NORMAL;-;lib.rell/object[state].attr[x]"
        )

        chkImportAllKindsExact("colors", "colors.red",
            "colors:DEF_ENUM;-;lib.rell/enum[colors]",
            "red:MEM_ENUM_VALUE;-;lib.rell/enum[colors].value[red]"
        )
    }

    private fun chkImportAllKindsExact(name: String, expr: String, vararg expExpr: String) {
        val code = "import lib.{$name}; function test() = $expr;"
        chkKdls(code, "lib:DEF_IMPORT_MODULE;-;lib.rell", expExpr[0], "test:DEF_FUNCTION;function[test];-", *expExpr)
    }

    @Test fun testImportAllKindsWildcard() {
        initImportAllKinds()
        file("module.rell", "import lib.*;")

        chkKdlsExpr("create data('Bob')", "data:DEF_ENTITY;-;lib.rell/entity[data]")
        chkKdlsExpr("state.x", "state:DEF_OBJECT;-;lib.rell/object[state]", "x:MEM_ENTITY_ATTR_NORMAL;-;lib.rell/object[state].attr[x]")
        chkKdlsExpr("rec(123)", "rec:DEF_STRUCT;-;lib.rell/struct[rec]")
        chkKdlsExpr("colors.red", "colors:DEF_ENUM;-;lib.rell/enum[colors]", "red:MEM_ENUM_VALUE;-;lib.rell/enum[colors].value[red]")
        chkKdlsExpr("op()", "op:DEF_OPERATION;-;lib.rell/operation[op]")
        chkKdlsExpr("q()", "q:DEF_QUERY;-;lib.rell/query[q]")
        chkKdlsExpr("f()", "f:DEF_FUNCTION;-;lib.rell/function[f]")
        chkKdlsExpr("MAGIC", "MAGIC:DEF_CONSTANT;-;lib.rell/constant[MAGIC]")
        chkKdlsExpr("ns.p()", "ns:DEF_NAMESPACE;-;lib.rell/namespace[ns]", "p:DEF_STRUCT;-;lib.rell/struct[ns.p]")
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
