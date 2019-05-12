package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class NamespaceTest: BaseRellTest() {
    @Test fun testSimple() {
        tst.defs = listOf("namespace foo { function bar(): integer = 123; }")
        chk("foo.bar()", "int[123]")
        chk("foo.bar", "ct_err:expr_novalue:function")
        chk("bar()", "ct_err:unknown_name:bar")
        chk("foo", "ct_err:expr_novalue:namespace")
        chk("bar", "ct_err:unknown_name:bar")
    }

    @Test fun testNestedNamespace() {
        tst.defs = listOf("namespace foo { namespace bar { function f(): integer = 123; } }")
        chk("foo.bar.f()", "int[123]")
        chk("f()", "ct_err:unknown_name:f")
        chk("foo.f()", "ct_err:unknown_name:foo.f")
        chk("foo", "ct_err:expr_novalue:namespace")
        chk("foo.bar", "ct_err:expr_novalue:namespace")
    }

    @Test fun testNameResolution() {
        tst.defs = listOf("""
            namespace foo {
                function f(): integer = 123;
                function g(): integer = f();
                namespace bar {
                    function h(): integer = f();
                }
            }
        """.trimIndent())

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
        tst.defs = listOf("""
            function f(): integer = 123;
            namespace foo {
                function f(): integer = 456;
                function g(): integer = f();
                function h(): integer = foo.f();
            }
        """.trimIndent())
        chk("f()", "int[123]")
        chk("foo.f()", "int[456]")
        chk("foo.g()", "int[456]")
        chk("foo.h()", "int[456]")
    }

    @Test fun testNameConflict() {
        chkCompile("namespace foo {} namespace foo {}", "ct_err:name_conflict:namespace:foo")
        chkCompile("namespace foo {} class foo {}", "ct_err:name_conflict:namespace:foo")
        chkCompile("namespace foo {} object foo {}", "ct_err:name_conflict:namespace:foo")
        chkCompile("namespace foo {} function foo(): integer = 123;", "ct_err:name_conflict:namespace:foo")

        chkCompile("namespace foo { namespace bar {} namespace bar {} }", "ct_err:name_conflict:namespace:bar")
        chkCompile("namespace foo { namespace bar {} function bar(): integer = 123; }", "ct_err:name_conflict:namespace:bar")
        chkCompile("namespace foo { class bar {} object bar {} }", "ct_err:name_conflict:class:bar")
    }

    @Test fun testForwardReference() {
        tst.defs = listOf(
                "function g(): integer = foo.f();",
                "object bar { x: integer = foo.f(); }",
                "namespace foo { function f(): integer = 123; }"
        )
        chk("g()", "int[123]")
        chk("bar.x", "int[123]")
    }

    @Test fun testClasses() {
        tst.defs = listOf("namespace foo { class bar { x: integer; } }")
        tst.insert("c0.foo.bar", "x", "0,123")
        chk("foo.bar @ {} ( foo.bar )", "ct_err:expr_novalue:type")
        chk("foo.bar @ {} ( bar )", "foo.bar[0]")
        chk("foo.bar @ {} ( foo )", "ct_err:expr_novalue:namespace")
        chk("foo.bar @ {} ( bar, bar.x, .x )", "(foo.bar[0],int[123],int[123])")
    }

    @Test fun testTableNameConflict() {
        tst.defs = listOf(
                "class user { x: integer; }",
                "namespace foo { class user { y: integer; } }",
                "namespace bar { object user { z: integer = 789; } }"
        )
        tst.insert("c0.user", "x", "0,123")
        tst.insert("c0.foo.user", "y", "1,456")

        chk("user @ {} ( user, =.x )", "(user[0],int[123])")
        chk("foo.user @ {} ( user, =.y )", "(foo.user[1],int[456])")
        chk("bar.user.z", "int[789]")
    }

    @Test fun testAllowedDefs() {
        tst.defs = listOf("""
            namespace foo {
                function f(): integer = 123;
                class user { x: integer; }
                object state { v: integer = 456; }
                record r { x: integer; }
                enum e { A, B, C }
                namespace bar { function g(): integer = 789; }
                operation op() {}
                query q() = 123;
            }
        """.trimIndent())
        tst.insert("c0.foo.user", "x", "0,123")

        chk("foo.f()", "int[123]")
        chk("foo.user @ {} ( .x )", "int[123]")
        chk("foo.state.v", "int[456]")
        chk("foo.r(123)", "foo.r[x=int[123]]")
        chk("foo.e.A", "foo.e[A]")
        chk("foo.bar.g()", "int[789]")

        chk("foo.f", "ct_err:expr_novalue:function")
        chk("foo.user", "ct_err:expr_novalue:type")
        chk("foo.state", "foo.state")
        chk("foo.r", "ct_err:expr_novalue:record")
        chk("foo.e", "ct_err:expr_novalue:enum")
        chk("foo.bar", "ct_err:expr_novalue:namespace")
    }

    @Test fun testPredefinedNamespaces() {
        chkCompile("namespace integer {}", "ct_err:name_conflict:type:integer")
        chkCompile("namespace text {}", "ct_err:name_conflict:type:text")
        chkCompile("namespace abs {}", "ct_err:name_conflict:function:abs")
        chkCompile("class abs {}", "ct_err:name_conflict:function:abs")
        chkCompile("namespace chain_context {}", "ct_err:name_conflict:namespace:chain_context")
        chkCompile("function chain_context() {}", "ct_err:name_conflict:namespace:chain_context")
        chkCompile("class chain_context {}", "ct_err:name_conflict:namespace:chain_context")
    }

    @Test fun testNamespacedTypes() {
        tst.defs = listOf("""
            namespace foo {
                namespace bar {
                    class c { name; }
                    record r { x: integer; }
                    enum e { A, B, C }
                }
            }
        """.trimIndent())
        tst.insert("c0.foo.bar.c", "name", "0,'Bob'")

        chkEx("{ val x: foo.bar.c = foo.bar.c @ {}; return x; }", "foo.bar.c[0]")
        chkEx("{ val x: foo.bar.r = foo.bar.r(123); return x; }", "foo.bar.r[x=int[123]]")
        chkEx("{ val x: foo.bar.e = foo.bar.e.B; return x; }", "foo.bar.e[B]")
    }
}
