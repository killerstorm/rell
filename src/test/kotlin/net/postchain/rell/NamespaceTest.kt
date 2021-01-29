/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class NamespaceTest: BaseRellTest() {
    @Test fun testSimple() {
        def("namespace foo { function bar(): integer = 123; }")
        chk("foo.bar()", "int[123]")
        chk("foo.bar", "ct_err:expr_novalue:function")
        chk("bar()", "ct_err:unknown_name:bar")
        chk("foo", "ct_err:expr_novalue:namespace")
        chk("bar", "ct_err:unknown_name:bar")
    }

    @Test fun testNestedNamespace() {
        def("namespace foo { namespace bar { function f(): integer = 123; } }")
        chk("foo.bar.f()", "int[123]")
        chk("f()", "ct_err:unknown_name:f")
        chk("foo.f()", "ct_err:unknown_name:foo.f")
        chk("foo", "ct_err:expr_novalue:namespace")
        chk("foo.bar", "ct_err:expr_novalue:namespace")
    }

    @Test fun testNameResolution() {
        def("""
            namespace foo {
                function f(): integer = 123;
                function g(): integer = f();
                namespace bar {
                    function h(): integer = f();
                }
            }
        """)

        chk("f()", "ct_err:unknown_name:f")
        chk("g()", "ct_err:unknown_name:g")
        chk("h()", "ct_err:unknown_name:h")
        chk("foo.f()", "int[123]")
        chk("foo.g()", "int[123]")
        chk("foo.bar.h()", "int[123]")
        chk("foo.bar.f()", "ct_err:unknown_name:foo.bar.f")
        chk("foo.bar.g()", "ct_err:unknown_name:foo.bar.g")
    }

    @Test fun testNameResolution2() {
        def("""
            function f(): integer = 123;
            namespace foo {
                function f(): integer = 456;
                function g(): integer = f();
                function h(): integer = foo.f();
            }
        """)
        chk("f()", "int[123]")
        chk("foo.f()", "int[456]")
        chk("foo.g()", "int[456]")
        chk("foo.h()", "int[456]")
    }

    @Test fun testNameConflict() {
        chkCompile("namespace foo {} namespace foo {}", "OK")

        chkCompile("namespace foo {} entity foo {}", """ct_err:
            [name_conflict:user:foo:ENTITY:main.rell(1:25)]
            [name_conflict:user:foo:NAMESPACE:main.rell(1:11)]
        """)

        chkCompile("namespace foo {} object foo {}", """ct_err:
            [name_conflict:user:foo:OBJECT:main.rell(1:25)]
            [name_conflict:user:foo:NAMESPACE:main.rell(1:11)]
        """)

        chkCompile("namespace foo {} function foo(): integer = 123;", """ct_err:
            [name_conflict:user:foo:FUNCTION:main.rell(1:27)]
            [name_conflict:user:foo:NAMESPACE:main.rell(1:11)]
        """)

        chkCompile("namespace foo { namespace bar {} namespace bar {} }", "OK")

        chkCompile("namespace foo { namespace bar {} function bar(): integer = 123; }", """ct_err:
            [name_conflict:user:bar:FUNCTION:main.rell(1:43)]
            [name_conflict:user:bar:NAMESPACE:main.rell(1:27)]
        """)

        chkCompile("namespace foo { entity bar {} object bar {} }", """ct_err:
            [name_conflict:user:bar:OBJECT:main.rell(1:38)]
            [name_conflict:user:bar:ENTITY:main.rell(1:24)]
        """)
    }

    @Test fun testNameConflictMultipart() {
        chkCompile("namespace a { function f(): integer = 123; } namespace a { function f(): integer = 123; }", """ct_err:
            [name_conflict:user:f:FUNCTION:main.rell(1:69)]
            [name_conflict:user:f:FUNCTION:main.rell(1:24)]
        """)

        chkCompile("namespace text { function f(): integer = 123; } namespace text { function f(): integer = 123; }", """ct_err:
            [name_conflict:sys:text:TYPE]
            [name_conflict:user:f:FUNCTION:main.rell(1:75)]
            [name_conflict:user:f:FUNCTION:main.rell(1:27)]
        """)
    }

    @Test fun testForwardReference() {
        def("function g(): integer = foo.f();")
        def("object bar { x: integer = foo.f(); }")
        def("namespace foo { function f(): integer = 123; }")
        chk("g()", "int[123]")
        chk("bar.x", "int[123]")
    }

    @Test fun testEntities() {
        def("namespace foo { entity bar { x: integer; } }")
        insert("c0.foo.bar", "x", "0,123")
        chk("foo.bar @ {} ( foo.bar )", "ct_err:expr_novalue:type")
        chk("foo.bar @ {} ( bar )", "foo.bar[0]")
        chk("foo.bar @ {} ( foo )", "ct_err:expr_novalue:namespace")
        chk("foo.bar @ {} ( bar, _=bar.x, _=.x )", "(foo.bar[0],int[123],int[123])")
    }

    @Test fun testTableNameConflict() {
        def("entity user { x: integer; }")
        def("namespace foo { entity user { y: integer; } }")
        def("namespace bar { object user { z: integer = 789; } }")
        insert("c0.user", "x", "0,123")
        insert("c0.foo.user", "y", "1,456")

        chk("user @ {} ( user, _=.x )", "(user[0],int[123])")
        chk("foo.user @ {} ( user, _=.y )", "(foo.user[1],int[456])")
        chk("bar.user.z", "int[789]")
    }

    @Test fun testAllowedDefs() {
        def("""
            namespace foo {
                function f(): integer = 123;
                entity user { x: integer; }
                object state { v: integer = 456; }
                struct r { x: integer; }
                enum e { A, B, C }
                namespace bar { function g(): integer = 789; }
                operation op() {}
                query q() = 123;
            }
        """)
        insert("c0.foo.user", "x", "0,123")

        chk("foo.f()", "int[123]")
        chk("foo.user @ {} ( .x )", "int[123]")
        chk("foo.state.v", "int[456]")
        chk("foo.r(123)", "foo.r[x=int[123]]")
        chk("foo.e.A", "foo.e[A]")
        chk("foo.bar.g()", "int[789]")

        chk("foo.f", "ct_err:expr_novalue:function")
        chk("foo.user", "ct_err:expr_novalue:type")
        chk("foo.state", "foo.state")
        chk("foo.r", "ct_err:expr_novalue:struct")
        chk("foo.e", "ct_err:expr_novalue:enum")
        chk("foo.bar", "ct_err:expr_novalue:namespace")
    }

    @Test fun testPredefinedNamespaces() {
        chkCompile("namespace integer {}", "ct_err:name_conflict:sys:integer:TYPE")
        chkCompile("namespace text {}", "ct_err:name_conflict:sys:text:TYPE")
        chkCompile("namespace abs {}", "ct_err:name_conflict:sys:abs:FUNCTION")
        chkCompile("entity abs {}", "ct_err:name_conflict:sys:abs:FUNCTION")
        chkCompile("namespace chain_context {}", "ct_err:name_conflict:sys:chain_context:NAMESPACE")
        chkCompile("function chain_context() {}", "ct_err:name_conflict:sys:chain_context:NAMESPACE")
        chkCompile("entity chain_context {}", "ct_err:name_conflict:sys:chain_context:NAMESPACE")
    }

    @Test fun testNamespacedTypes() {
        def("""
            namespace foo {
                namespace bar {
                    entity c { name; }
                    struct r { x: integer; }
                    enum e { A, B, C }
                }
            }
        """)
        insert("c0.foo.bar.c", "name", "0,'Bob'")

        chkEx("{ val x: foo.bar.c = foo.bar.c @ {}; return x; }", "foo.bar.c[0]")
        chkEx("{ val x: foo.bar.r = foo.bar.r(123); return x; }", "foo.bar.r[x=int[123]]")
        chkEx("{ val x: foo.bar.e = foo.bar.e.B; return x; }", "foo.bar.e[B]")
    }

    @Test fun testMultipleNamespacesWithSameName() {
        def("""
            namespace foo { function f(): integer = 123; }
            namespace foo { namespace bar { function g(): integer = 456; } }
            namespace foo { namespace bar { function h(): integer = 789; } }
        """)

        chk("foo.f()", "int[123]")
        chk("foo.bar.g()", "int[456]")
        chk("foo.bar.h()", "int[789]")
    }

    @Test fun testMultipleNamespacesWithSameName2() {
        def("""
            namespace foo { function f(): integer = g() * 2; }
            function p(): integer = foo.f() + foo.g() + foo.h();
            namespace foo { function g(): integer = h() * 3; }
            namespace foo { function h(): integer = 123; }
        """)

        chk("foo.f()", "int[738]")
        chk("foo.g()", "int[369]")
        chk("foo.h()", "int[123]")
        chk("p()", "int[1230]")
    }

    @Test fun testMultipleNamespacesWithSameName3() {
        val code = """
            namespace foo { namespace bar { function f(): integer = 123; }}
            namespace foo { namespace bar {    function f(): integer = 123; }}
        """.trimIndent()

        chkCompile(code, """ct_err:
            [name_conflict:user:f:FUNCTION:main.rell(2:45)]
            [name_conflict:user:f:FUNCTION:main.rell(1:42)]
        """)
    }

    @Test fun testAnonymousNamespace() {
        chkFull("namespace { query q() = 123; }", "int[123]")
        chkFull("namespace { function f(): integer = 123; } query q() = f();", "int[123]")
        chkFull("namespace { function f(): integer = g(); } query q() = f(); namespace { function g(): integer = 123; }", "int[123]")
        chkCompile("namespace { function f(): integer = 123; } namespace ns { function f(): integer = 456; }", "OK")

        chkCompile("namespace { function f(): integer = 123; } namespace { function f(): integer = 456; }", """ct_err:
            [name_conflict:user:f:FUNCTION:main.rell(1:65)]
            [name_conflict:user:f:FUNCTION:main.rell(1:22)]
        """)
    }

    @Test fun testOuterDefsAccess() {
        def("""
            function f(): integer = 123;
            namespace a {
                function g(): integer = f() + 456;
                namespace b {
                    function h(): integer = f() + g() + 789;
                }
            }
        """)

        chk("f()", "int[123]")
        chk("a.g()", "int[579]")
        chk("a.b.h()", "int[1491]")

        chk("a.f()", "ct_err:unknown_name:a.f")
        chk("a.b.f()", "ct_err:unknown_name:a.b.f")
        chk("a.b.g()", "ct_err:unknown_name:a.b.g")
    }

    @Test fun testSysDefInNamespace() {
        def("""
            namespace x {
                function integer(t: text): text = 'Hello';
                function f(): text = integer('');
                namespace y { function g(): text = integer(''); }
            }
        """)
        chk("integer('123')", "int[123]")
        chk("x.integer('123')", "text[Hello]")
        chk("x.f()", "text[Hello]")
        chk("x.y.g()", "text[Hello]")
    }

    @Test fun testComplexNamespace() {
        def("namespace x.y.z { function f() = 123; }")
        chk("x.y.z.f()", "int[123]")
        chk("f()", "ct_err:unknown_name:f")
        chk("x.f()", "ct_err:unknown_name:x.f")
    }

    @Test fun testComplexNamespaceMerging() {
        def("namespace a.b.c { function f() = 123; }")
        def("namespace a.b.d { function f() = 456; }")
        def("namespace a.e.f { function f() = 789; }")
        def("namespace a { namespace g.h { function f() = 987; }}")
        chk("a.b.c.f()", "int[123]")
        chk("a.b.d.f()", "int[456]")
        chk("a.e.f.f()", "int[789]")
        chk("a.g.h.f()", "int[987]")
    }
}
