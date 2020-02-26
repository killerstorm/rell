/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class ImportTest: BaseRellTest(false) {
    @Test fun testExact() {
        file("a.rell", "module; function f(): integer = 123; function g(): integer = 456;")
        chkImport("import a.{f};", "f()", "int[123]")
        chkImport("import a.{g};", "g()", "int[456]")
        chkImport("import a.{f,g};", "f()+g()", "int[579]")
        chkImport("import a.{f};", "g()", "ct_err:unknown_name:g")
        chkImport("import a.{g};", "f()", "ct_err:unknown_name:f")
    }

    @Test fun testExactAlias() {
        file("a.rell", "module; function f(): integer = 123; function g(): integer = 456;")

        chkImport("import x: a.{f};", "x.f()", "int[123]")
        chkImport("import x: a.{g};", "x.g()", "int[456]")
        chkImport("import x: a.{f,g};", "x.f()+x.g()", "int[579]")
        chkImport("import x: a.{f};", "x.g()", "ct_err:unknown_name:x.g")
        chkImport("import x: a.{g};", "x.f()", "ct_err:unknown_name:x.f")
        chkImport("import x: a.{f,g};", "f()", "ct_err:unknown_name:f")
        chkImport("import x: a.{f,g};", "g()", "ct_err:unknown_name:g")

        chkImport("import a.{r: f};", "r()", "int[123]")
        chkImport("import a.{s: g};", "s()", "int[456]")
        chkImport("import a.{r: f};", "f()", "ct_err:unknown_name:f")
        chkImport("import a.{s: g};", "g()", "ct_err:unknown_name:g")
        chkImport("import a.{r: f, s: g};", "r()+s()", "int[579]")
        chkImport("import a.{f: g};", "f()", "int[456]")
        chkImport("import a.{f: g};", "g()", "ct_err:unknown_name:g")
        chkImport("import a.{g: f};", "g()", "int[123]")
        chkImport("import a.{g: f};", "f()", "ct_err:unknown_name:f")

        chkImport("import x: a.{r: f};", "x.r()", "int[123]")
        chkImport("import x: a.{s: g};", "x.s()", "int[456]")
        chkImport("import x: a.{r: f, s: g};", "x.r()+x.s()", "int[579]")
    }

    @Test fun testExactQualified() {
        file("a.rell", "module; namespace x { function f(): integer = 123; namespace y { function g(): integer = 456; }}")
        chkImport("import a.{x};", "x.f()", "int[123]")
        chkImport("import a.{x};", "x.y.g()", "int[456]")
        chkImport("import a.{x.f};", "f()", "int[123]")
        chkImport("import a.{x.y};", "y.g()", "int[456]")
        chkImport("import a.{x.y.g};", "g()", "int[456]")
        chkImport("import a.{x.f,x.y.g};", "f()+g()", "int[579]")
    }

    @Test fun testExactNoBrackets() {
        file("a.rell", "module; function f(): integer = 123;")
        chkImport("import a.f;", "f()", "ct_err:[import:not_found:a.f][unknown_name:f]")
        chkImport("import a.{f};", "f()", "int[123]")
    }

    @Test fun testExactConflict() {
        file("a.rell", "module; function f(): integer = 123;")
        file("b.rell", "module; function f(): integer = 123;")
        file("c.rell", "module; import a.{f};")
        chkImport("function f(): integer = 123; import a.{f};", "0",
                "ct_err:[name_conflict:user:f:IMPORT:main.rell(1:40)][name_conflict:user:f:FUNCTION:main.rell(1:10)]")
        chkImport("import a.{f}; import b.{f};", "0",
                "ct_err:[name_conflict:user:f:IMPORT:main.rell(1:25)][name_conflict:user:f:IMPORT:main.rell(1:11)]")
        chkImport("import a.{f}; import a.{f};", "f()", "int[123]")
        chkImport("import a.{f}; import c.{f};", "0",
                "ct_err:[name_conflict:user:f:IMPORT:main.rell(1:25)][name_conflict:user:f:IMPORT:main.rell(1:11)]")
    }

    @Test fun testExactConflict2() {
        file("a.rell", "module; namespace x { function f(): integer = 123; } namespace y { function f(): integer = 456; }")
        chkImport("import a.{x.f}; import a.{y.f};", "0",
                "ct_err:[name_conflict:user:f:IMPORT:main.rell(1:29)][name_conflict:user:f:IMPORT:main.rell(1:13)]")
        chkImport("import a.{x.f, y.f};", "0",
                "ct_err:[name_conflict:user:f:IMPORT:main.rell(1:18)][name_conflict:user:f:IMPORT:main.rell(1:13)]")
        chkImport("import a.{x, x};", "x.f()", "int[123]")
        chkImport("namespace x {} import a.{x};", "0",
                "ct_err:[name_conflict:user:x:IMPORT:main.rell(1:26)][name_conflict:user:x:NAMESPACE:main.rell(1:11)]")
    }

    @Test fun testWildcard() {
        file("a.rell", "module; function f(): integer = 123; function g(): integer = 456;")
        chkImport("import a.*;", "f()", "int[123]")
        chkImport("import a.*;", "g()", "int[456]")
    }

    @Test fun testWildcardAlias() {
        file("a.rell", "module; function f(): integer = 123; function g(): integer = 456;")
        chkImport("import x: a.*;", "x.f()", "int[123]")
        chkImport("import x: a.*;", "x.g()", "int[456]")
        chkImport("import x: a.*;", "f()", "ct_err:unknown_name:f")
        chkImport("import x: a.*;", "g()", "ct_err:unknown_name:g")
    }

    @Test fun testWildcardAlias2() {
        file("a.rell", "module; namespace ns { function f(): integer = 123; function g(): integer = 456; }")

        chkImport("import x: a.{ns.*};", "x.f()", "int[123]")
        chkImport("import x: a.{ns.*};", "x.g()", "int[456]")
        chkImport("import x: a.{ns.*};", "f()", "ct_err:unknown_name:f")
        chkImport("import x: a.{ns.*};", "g()", "ct_err:unknown_name:g")

        chkImport("import a.{y: ns.*};", "y.f()", "int[123]")
        chkImport("import a.{y: ns.*};", "y.g()", "int[456]")
        chkImport("import a.{y: ns.*};", "f()", "ct_err:unknown_name:f")
        chkImport("import a.{y: ns.*};", "g()", "ct_err:unknown_name:g")

        chkImport("import x: a.{y: ns.*};", "x.y.f()", "int[123]")
        chkImport("import x: a.{y: ns.*};", "x.y.g()", "int[456]")
        chkImport("import x: a.{y: ns.*};", "f()", "ct_err:unknown_name:f")
        chkImport("import x: a.{y: ns.*};", "g()", "ct_err:unknown_name:g")
        chkImport("import x: a.{y: ns.*};", "x.f()", "ct_err:unknown_name:x.f")
        chkImport("import x: a.{y: ns.*};", "x.g()", "ct_err:unknown_name:x.g")
        chkImport("import x: a.{y: ns.*};", "y.f()", "ct_err:unknown_name:y")
        chkImport("import x: a.{y: ns.*};", "y.g()", "ct_err:unknown_name:y")
    }

    @Test fun testWildcardQualified() {
        file("a.rell", """module;
            namespace x {
                function f(): integer = 123;
                function g(): integer = 456;
                namespace y {
                    function p(): integer = 321;
                    function q(): integer = 654;
                }
            }
        """)

        chkImport("import a.{x.*};", "f()", "int[123]")
        chkImport("import a.{x.*};", "g()", "int[456]")
        chkImport("import a.{x.*};", "y.p()", "int[321]")
        chkImport("import a.{x.*};", "y.q()", "int[654]")

        chkImport("import a.{x.y.*};", "p()", "int[321]")
        chkImport("import a.{x.y.*};", "q()", "int[654]")
        chkImport("import a.{x.y.*};", "y.p()", "ct_err:unknown_name:y")
        chkImport("import a.{x.y.*};", "y.q()", "ct_err:unknown_name:y")

        chkImport("import a.{x.*,x.y.*};", "f()", "int[123]")
        chkImport("import a.{x.*,x.y.*};", "g()", "int[456]")
        chkImport("import a.{x.*,x.y.*};", "p()", "int[321]")
        chkImport("import a.{x.*,x.y.*};", "q()", "int[654]")
        chkImport("import a.{x.*,x.y.*};", "y.p()", "int[321]")
        chkImport("import a.{x.*,x.y.*};", "y.q()", "int[654]")

        chkImport("import ns: a.{x.*,x.y.*};", "ns.f()", "int[123]")
        chkImport("import ns: a.{x.*,x.y.*};", "ns.g()", "int[456]")
        chkImport("import ns: a.{x.*,x.y.*};", "ns.p()", "int[321]")
        chkImport("import ns: a.{x.*,x.y.*};", "ns.q()", "int[654]")
        chkImport("import ns: a.{x.*,x.y.*};", "ns.y.p()", "int[321]")
        chkImport("import ns: a.{x.*,x.y.*};", "ns.y.q()", "int[654]")
    }

    @Test fun testWildcardConflict() {
        file("a.rell", "module; function f(): integer = 123; function g(): integer = 456;")
        chkImport("function f(): integer = 789; import a.*;", "f()", "int[789]")
        chkImport("function f(): integer = 789; import a.*;", "g()", "int[456]")
    }

    @Test fun testWildcardConflict2() {
        file("a.rell", "module; function f(): integer = 123; function g(): integer = 456;")
        chkImport("import a.{f}; import a.*;", "f()", "int[123]")
        chkImport("import a.{f}; import a.*;", "g()", "int[456]")
    }

    @Test fun testWildcardConflict3() {
        file("a.rell", "module; function f(): integer = 123; function p(): integer = 456;")
        file("b.rell", "module; function f(): integer = 321; function q(): integer = 789;")
        chkImport("import a.*; import b.*;", "f()", "ct_err:name:ambig:f")
        chkImport("import a.*; import b.*;", "p()", "int[456]")
        chkImport("import a.*; import b.*;", "q()", "int[789]")
        chkImport("import a.*; import a.*;", "f()", "int[123]")
        chkImport("import b.*; import b.*;", "f()", "int[321]")
    }

    @Test fun testWildcardConflict4() {
        file("a.rell", "module; namespace x { function f(): integer = 123; }")
        chkImport("namespace x { function g(): integer = 456; } import a.*;", "x.f()", "ct_err:unknown_name:x.f")
        chkImport("namespace x { function g(): integer = 456; } import a.*;", "x.g()", "int[456]")
    }


    @Test fun testWildcardConflictInterFile() {
        file("a.rell", "module; function f(): integer = 123;")
        file("b.rell", "module; function f(): integer = 456;")
        file("c/c1.rell", "import a.*;")
        file("c/c2.rell", "function f(): integer = 789;")
        file("c/c3.rell", "import b.*;")
        file("c/c4.rell", "function g(): integer = f();")
        chkImport("import c;", "c.f()", "ct_err:name:ambig:c.f")
        chkImport("import c;", "c.g()", "int[789]")
    }

    @Test fun testWildcardConflicts() {
        file("a.rell", """module;
            namespace ns { namespace x { function f(): integer = 456; struct rec { p: text; } } }
            namespace st { struct x { v: integer; } }
            namespace en { enum x { EN, DE } }
            namespace fn { function x(): integer = 123; }
        """)

        chkImport("import a.{ns.*, st.*};", "x(123)", "a!st.x[v=int[123]]")
        chkImport("import a.{ns.*, st.*};", "list<x.rec>()", "list<a!ns.x.rec>[]")
        chkImport("import a.{ns.*, st.*};", "x.f()", "ct_err:name:ambig:x")

        chkImport("import a.{ns.*, en.*};", "list<x>()", "list<a!en.x>[]")
        chkImport("import a.{ns.*, en.*};", "list<x.rec>()", "list<a!ns.x.rec>[]")
        chkImport("import a.{ns.*, en.*};", "x.EN", "ct_err:[name:ambig:x][unknown_name:x.EN]")
        chkImport("import a.{ns.*, en.*};", "x.f()", "ct_err:name:ambig:x")

        chkImport("import a.{ns.*, fn.*};", "list<x.rec>()", "list<a!ns.x.rec>[]")
        chkImport("import a.{ns.*, fn.*};", "x.f()", "int[456]")
        chkImport("import a.{ns.*, fn.*};", "x()", "int[123]")

        chkImport("import a.{st.*, en.*};", "list<x>()", "ct_err:name:ambig:x")
        chkImport("import a.{st.*, en.*};", "x.EN", "ct_err:[name:ambig:x][unknown_name:x.EN]")
        chkImport("import a.{st.*, en.*};", "x.from_bytes(x'')", "ct_err:name:ambig:x")
        chkImport("import a.{st.*, en.*};", "x.value(0)", "ct_err:[name:ambig:x][unknown_name:x.value]")

        chkImport("import a.{st.*, fn.*};", "list<x>()", "list<a!st.x>[]")
        chkImport("import a.{st.*, fn.*};", "_type_of(x.from_bytes(x''))", "text[a!st.x]")
        chkImport("import a.{st.*, fn.*};", "x(123)", "ct_err:name:ambig:x")

        chkImport("import a.{en.*, fn.*};", "list<x>()", "list<a!en.x>[]")
        chkImport("import a.{en.*, fn.*};", "x.EN", "a!en.x[EN]")
        chkImport("import a.{en.*, fn.*};", "x()", "int[123]")
    }

    @Test fun testWildcardNonNamespace() {
        file("a.rell", """module;
            entity user { name; }
            object state { value: integer = 123; }
            enum lang { EN, DE }
            function f(): integer = 123;
            struct rec { x: integer; }
        """)
        chkImport("import a.{user.*};", "0", "ct_err:import:not_ns:user")
        chkImport("import a.{state.*};", "0", "ct_err:import:not_ns:state")
        chkImport("import a.{lang.*};", "0", "ct_err:import:not_ns:lang")
        chkImport("import a.{f.*};", "0", "ct_err:import:not_ns:f")
        chkImport("import a.{rec.*};", "0", "ct_err:import:not_ns:rec")
    }

    @Test fun testWildcardImportNamespace() {
        file("sub.rell", "module; function f(): integer = 123;")
        file("a.rell", """module;
            import sub;
            import wild: sub.*;
            import exact: sub.{f};
        """)
        chkImport("import a.{sub.*};", "f()", "int[123]")
        chkImport("import a.{wild.*};", "f()", "int[123]")
        chkImport("import a.{exact.*};", "f()", "int[123]")
    }

    @Test fun testWildcardDuplicate() {
        file("lib.rell", "module; function f(): integer = 123;")
        chkImport("import lib.*; import lib.*;", "f()", "int[123]")
    }

    @Test fun testDefKindsExact() {
        initDefKinds()
        chkImport("import lib.{user};", "_type_of(user@{})", "text[lib!user]")
        chkImport("import lib.{state};", "_type_of(state.value)", "text[integer]")
        chkImport("import lib.{lang};", "lang.EN", "lib!lang[EN]")
        chkImport("import lib.{rec};", "rec(123)", "lib!rec[x=int[123]]")
        chkImport("import lib.{f};", "f()", "int[123]")
        chkImport("import lib.{ns};", "ns.p()", "int[789]")
        chkImport("import lib.{sub};", "sub.g()", "int[456]")
        chkImport("import lib.{g};", "g()", "int[456]")
        chkImport("import lib.{foo};", "foo.g()", "int[456]")
    }

    @Test fun testDefKindsWildcard() {
        initDefKinds()
        chkImport("import lib.*;", "_type_of(user@{})", "text[lib!user]")
        chkImport("import lib.*;", "_type_of(state.value)", "text[integer]")
        chkImport("import lib.*;", "lang.EN", "lib!lang[EN]")
        chkImport("import lib.*;", "rec(123)", "lib!rec[x=int[123]]")
        chkImport("import lib.*;", "f()", "int[123]")
        chkImport("import lib.*;", "ns.p()", "int[789]")
        chkImport("import lib.*;", "sub.g()", "int[456]")
        chkImport("import lib.*;", "g()", "int[456]")
        chkImport("import lib.*;", "foo.g()", "int[456]")
    }

    private fun initDefKinds() {
        file("sub.rell", "module; function g(): integer = 456;")

        file("lib.rell", """module;
            entity user { name; }
            object state { mutable value: integer = 0; }
            enum lang { EN, DE }
            struct rec { x: integer; }
            function f(): integer = 123;
            namespace ns { function p(): integer = 789; }
            import sub;
            import sub.{g};
            import foo: sub.*;
        """)
    }

    @Test fun testTransitiveExact() {
        file("a.rell", "module; function f(): integer = 123;")
        file("b.rell", "module; function g(): integer = 456;")
        file("lib.rell", "module; import a.{f}; import b.{g};")
        chkImport("import lib;", "lib.f()", "int[123]")
        chkImport("import lib;", "lib.g()", "int[456]")
    }

    @Test fun testTransitiveWildcard() {
        file("a.rell", "module; function f(): integer = 123;")
        file("b.rell", "module; function g(): integer = 456;")
        file("lib.rell", "module; import a.*; import b.*;")
        chkImport("import lib;", "lib.f()", "int[123]")
        chkImport("import lib;", "lib.g()", "int[456]")
    }

    @Test fun testTransitiveWildcard2() {
        file("a.rell", "module; function f(): integer = 123;")
        file("b.rell", "module; function g(): integer = 456;")
        file("lib.rell", "module; import x: a.*; import y: b.*;")
        chkImport("import lib;", "lib.x.f()", "int[123]")
        chkImport("import lib;", "lib.y.g()", "int[456]")
    }

    @Test fun testTransitiveMixed() {
        file("a.rell", "module; function f(): integer = 123;")
        file("b.rell", "module; import a.*;")
        file("c.rell", "module; import b.{f};")
        chkImport("import c;", "c.f()", "int[123]")
    }

    @Test fun testRecursionExact() {
        file("a.rell", "module; namespace x { function f(): integer = 123; import a.{x}; }")
        chkImport("import a;", "a.x.f()", "int[123]")
        chkImport("import a;", "a.x.x.f()", "int[123]")
        chkImport("import a;", "a.x.x.x.f()", "int[123]")
    }

    @Test fun testRecursionExact2() {
        file("a.rell", "module; import a.{x};")
        chkImport("import a;", "a.x", "ct_err:[main.rell:unknown_name:a.x][a.rell:import:exact:recursion:a!x]")
        chkImport("import a;", "0", "ct_err:a.rell:import:exact:recursion:a!x")
    }

    @Test fun testRecursionExact3() {
        file("a.rell", "module; import b.{x};")
        file("b.rell", "module; import c.{x};")
        file("c.rell", "module; import a.{x};")
        file("d.rell", "module; import a.{x};")

        chkImport("import a.{x};", "0", """ct_err:
            [main.rell:import:exact:unresolved:a!x]
            [a.rell:import:exact:recursion:b!x]
            [b.rell:import:exact:recursion:c!x]
            [c.rell:import:exact:recursion:a!x]
        """)

        chkImport("import b.{x};", "0", """ct_err:
            [main.rell:import:exact:unresolved:b!x]
            [a.rell:import:exact:recursion:b!x]
            [b.rell:import:exact:recursion:c!x]
            [c.rell:import:exact:recursion:a!x]
        """)

        chkImport("import c.{x};", "0", """ct_err:
            [main.rell:import:exact:unresolved:c!x]
            [a.rell:import:exact:recursion:b!x]
            [b.rell:import:exact:recursion:c!x]
            [c.rell:import:exact:recursion:a!x]
        """)

        chkImport("import d.{x};", "0", """ct_err:
            [main.rell:import:exact:unresolved:d!x]
            [a.rell:import:exact:recursion:b!x]
            [b.rell:import:exact:recursion:c!x]
            [c.rell:import:exact:recursion:a!x]
            [d.rell:import:exact:unresolved:a!x]
        """)

        chkImport("import a;", "0", """ct_err:
            [a.rell:import:exact:recursion:b!x]
            [b.rell:import:exact:recursion:c!x]
            [c.rell:import:exact:recursion:a!x]
        """)

        chkImport("import d;", "0", """ct_err:
            [a.rell:import:exact:recursion:b!x]
            [b.rell:import:exact:recursion:c!x]
            [c.rell:import:exact:recursion:a!x]
            [d.rell:import:exact:unresolved:a!x]
        """)
    }

    @Test fun testRecursionExact4() {
        file("x.rell", "module; namespace a { import y.{b: d.e.f}; }")
        file("y.rell", "module; namespace d { import x.{e: a.b.c}; }")
        chkImport("import x;", "0", "ct_err:[x.rell:import:name_unresolved:e][y.rell:import:name_unresolved:b]")
        chkImport("import y;", "0", "ct_err:[x.rell:import:name_unresolved:e][y.rell:import:name_unresolved:b]")
    }

    @Test fun testRecursionExact5() {
        file("x.rell", "module; namespace a { import y.{b: d.e}; }")
        file("y.rell", "module; namespace d { import x.{e: a.b.c}; }")
        chkImport("import x;", "0", "ct_err:[x.rell:import:exact:unresolved:y!d.e][y.rell:import:name_unresolved:b]")
        chkImport("import y;", "0", "ct_err:[x.rell:import:exact:unresolved:y!d.e][y.rell:import:name_unresolved:b]")
    }

    @Test fun testRecursionWildcard() {
        file("a.rell", "module; import b.*; function p(): integer = 123;")
        file("b.rell", "module; import c.*; function q(): integer = 456;")
        file("c.rell", "module; import a.*; function r(): integer = 789;")
        chkImport("import a;", "a.p()", "int[123]")
        chkImport("import a;", "a.q()", "int[456]")
        chkImport("import a;", "a.r()", "int[789]")
        chkImport("import b;", "b.p()", "int[123]")
        chkImport("import b;", "b.q()", "int[456]")
        chkImport("import b;", "b.r()", "int[789]")
        chkImport("import c;", "c.p()", "int[123]")
        chkImport("import c;", "c.q()", "int[456]")
        chkImport("import c;", "c.r()", "int[789]")
    }

    @Test fun testRecursionWildcard2() {
        file("a.rell", "module; function f(): integer = 123; namespace x { import a.*; }")
        chkImport("import a;", "a.f()", "int[123]")
        chkImport("import a;", "a.x.f()", "int[123]")
        chkImport("import a;", "a.x.x.f()", "int[123]")
        chkImport("import a;", "a.x.x.x.f()", "int[123]")
    }

    @Test fun testRecursionWildcard3() {
        file("a/a1.rell", "function f(): integer = 123;")
        file("a/a2.rell", "import a.*; function g(): integer = 456; function h(): integer = f();")
        chkImport("import a;", "a.f()", "int[123]")
        chkImport("import a;", "a.g()", "int[456]")
        chkImport("import a;", "a.h()", "int[123]")
    }

    @Test fun testRecursionMixed() {
        file("a.rell", "module; function f(): integer = 123; import b.*;")
        file("b.rell", "module; import c.{x};")
        file("c.rell", "module; namespace x { import a.*; }")
        chkImport("import a;", "a.f()", "int[123]")
        chkImport("import a;", "a.x.f()", "int[123]")
        chkImport("import a;", "a.x.x.f()", "int[123]")
    }

    @Test fun testRecursionMixed2() {
        file("m1.rell", "module; namespace a { import m2.{x.y.*}; namespace y { function f(): integer = 123; } }")
        file("m2.rell", "module; namespace x { import m1.{a.*}; }")
        chkImport("import m1;", "m1.a.f()", "int[123]")
    }

    @Test fun testRecursionMixed3() {
        file("m1.rell", "module; namespace a { import m2.{x.y.f}; namespace y { function f(): integer = 123; } }")
        file("m2.rell", "module; namespace x { import m1.{a.*}; }")
        chkImport("import m1;", "m1.a.f()", "int[123]")
    }

    @Test fun testRecursionMixed4() {
        file("m1.rell", "module; namespace a { import m2.{x.y.*}; namespace b { function f(): integer = 123; } }")
        file("m2.rell", "module; namespace x { import m1.{y: a.b}; }")
        chkImport("import m1;", "m1.a.f()", "int[123]")
    }

    @Test fun testRecursionMixed5() {
        file("m1.rell", "module; namespace a { import m2.{x.y.f}; namespace y { function f(): integer = 123; } }")
        file("m2.rell", "module; namespace x { import m1.{a.y}; }")
        chkImport("import m1;", "m1.a.f()", "int[123]")
    }

    @Test fun testRecursionMixed6() {
        file("m1.rell", "module; namespace a { import m2.{x.y.f}; namespace b { import m2.{x.*}; } }")
        file("m2.rell", "module; namespace x { import m1.{y: a.b}; }")
        chkImport("import m1;", "m1.a.f()", "ct_err:[main.rell:unknown_name:m1.a.f][m1.rell:import:name_unknown:f]")
    }

    @Test fun testRelativePath() {
        file("lib/foo/a.rell", "module; function f(): integer = 123;")
        file("lib/bar/exact.rell", "module; import ^^.foo.a.{f}; function g(): integer = -f();")
        file("lib/bar/wildcard.rell", "module; import ^^.foo.a.*; function g(): integer = -f();")
        chkImport("import lib.bar.exact;", "exact.g()", "int[-123]")
        chkImport("import lib.bar.wildcard;", "wildcard.g()", "int[-123]")
    }

    @Test fun testSysDefs() {
        file("a.rell", "module; function f(): integer = 123;")
        chkImport("import a;", "_type_of(a.integer)", "ct_err:unknown_name:a.integer")
        chkImport("import a;", "a.abs(123)", "ct_err:unknown_name:a.abs")
        chkImport("import ns: a.*;", "_type_of(ns.integer)", "ct_err:unknown_name:ns.integer")
        chkImport("import ns: a.*;", "ns.abs(123)", "ct_err:unknown_name:ns.abs")
    }

    @Test fun testAliasConflictNamespace() {
        file("a.rell", "module; function f(): integer = 123;")

        chkCompile("import a; namespace a {}",
                "ct_err:[name_conflict:user:a:NAMESPACE:main.rell(1:21)][name_conflict:user:a:IMPORT:main.rell(1:8)]")

        chkCompile("import x: a.*; namespace x {}",
                "ct_err:[name_conflict:user:x:NAMESPACE:main.rell(1:26)][name_conflict:user:x:NAMESPACE:main.rell(1:8)]")

        chkCompile("import x: a.{f}; namespace x {}",
                "ct_err:[name_conflict:user:x:NAMESPACE:main.rell(1:28)][name_conflict:user:x:NAMESPACE:main.rell(1:8)]")
    }

    @Test fun testAmbiguousName() {
        file("a.rell", "module; namespace x { function f(): integer = 123; }")
        file("b.rell", "module; namespace x { function g(): integer = 456; }")
        file("c.rell", "module; import a.*; import b.*;")
        chkImport("import c.{x.f};", "0", "ct_err:import:name_ambig:x")
        chkImport("import c.{x.g};", "0", "ct_err:import:name_ambig:x")
        chkImport("import c.{x.*};", "0", "ct_err:import:name_ambig:x")
    }

    @Test fun testAmbiguousNameImport() {
        file("a.rell", "module; function f(): integer = 123;")
        file("b.rell", "module; function f(): integer = 456;")
        file("c.rell", "module; import a.*; import b.*;")
        file("d.rell", "module; import a.*; import b.*; function f(): integer = 789;")
        chkImport("import c.{f};", "f()", "ct_err:[import:name_ambig:f][unknown_name:f]")
        chkImport("import d.{f};", "f()", "int[789]")
        chkImport("import c.*;", "f()", "ct_err:name:ambig:f")
        chkImport("import d.*;", "f()", "ct_err:name:ambig:f")
    }

    @Test fun testAmbiguousNameImport2() {
        file("a.rell", "module; namespace x { function f(): integer = 123; }")
        file("b.rell", "module; namespace x { function g(): integer = 456; }")
        file("c.rell", "module; import a.*; import b.*;")
        file("d.rell", "module; import a.*; import b.*; namespace x { function h(): integer = 789; }")
        chkImport("import c.{x.*};", "0", "ct_err:import:name_ambig:x")
        chkImport("import d.{x.*};", "f()", "ct_err:unknown_name:f")
        chkImport("import d.{x.*};", "g()", "ct_err:unknown_name:g")
        chkImport("import d.{x.*};", "h()", "int[789]")
        chkImport("import c.*;", "x.f()", "ct_err:name:ambig:x")
        chkImport("import d.*;", "x.h()", "ct_err:name:ambig:x")
    }

    @Test fun testUnresolvedName() {
        file("a.rell", "module; namespace x {}")
        file("b.rell", "module; namespace y {}")
        file("c.rell", "module; import a.*; import b.*;")
        chkImport("import c.{z.f};", "0", "ct_err:import:name_unknown:z")
        chkImport("import c.{z.g};", "0", "ct_err:import:name_unknown:z")
        chkImport("import c.{z.*};", "0", "ct_err:import:name_unknown:z")
    }

    @Test fun testUnresolvedName2() {
        file("a.rell", "module; namespace x {}")
        file("b.rell", "module; import a.{x.y};")
        chkImport("import b.{y.z};", "0", "ct_err:[main.rell:import:name_unresolved:y][b.rell:import:name_unknown:y]")
    }

    private fun chkImport(imp: String, code: String, exp: String) = chkQueryEx("$imp query q() = $code;", exp)
}