/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lang.module

import net.postchain.rell.test.BaseRellTest
import org.junit.Test

class TestModuleTest: BaseRellTest(false) {
    @Test fun testRunTests() {
        file("some_tests.rell", """
            @test module;
            function test_foo() { print("foo"); require(true); }
            function test_bar() { print("bar"); require(false); }
            function not_a_test() { print("not_a_test"); }
        """)
        chkTests("some_tests", "test_foo=OK,test_bar=req_err:null")
        chkOut("foo", "bar")
    }

    @Test fun testImportTestFromRegular() {
        file("test_module.rell", "@test module;")
        file("regular_module.rell", "module; import test_module;")
        chkCompile("import regular_module;", "ct_err:regular_module.rell:import:module_test:test_module")
    }

    @Test fun testImportRegularFromTest() {
        file("regular_module.rell", "module; function f() = 123;")
        file("test_module.rell", "@test module; import regular_module; function test() { print(regular_module.f()); }")
        chkTests("test_module", "test=OK")
        chkOut("123")
    }

    @Test fun testImportTestFromTest() {
        file("helper_module.rell", "@test module; function f() = 123;")
        file("test_module.rell", "@test module; import helper_module; function test() { print(helper_module.f()); }")
        chkTests("test_module", "test=OK")
        chkOut("123")
    }

    @Test fun testAbstractTestModule() {
        file("a.rell", "abstract @test module;")
        file("b.rell", "@test abstract module;")
        chkCompile("import a;", "ct_err:[main.rell:import:module_test:a][a.rell:modifier:bad_combination:kw:abstract,ann:test]")
        chkCompile("import b;", "ct_err:[main.rell:import:module_test:b][b.rell:modifier:bad_combination:ann:test,kw:abstract]")
    }

    @Test fun testDirectoryModuleAsTest() {
        file("some_tests/module.rell", "@test module;")
        file("some_tests/foo.rell", "function test_foo() { print('foo'); require(true); }")
        file("some_tests/bar.rell", "function test_bar() { print('bar'); require(true); }")
        chkTests("some_tests", "test_bar=OK,test_foo=OK")
        chkOut("bar", "foo")
    }

    @Test fun testMountName() {
        file("test.rell", "@mount('mytest') @test module; function test_foo() {}")
        chkCompile("", "OK") //TODO must fail
    }

    @Test fun testUnallowedDefinitions() {
        tst.mainModule("main")
        chkCompile("@test module; operation op() {}", "ct_err:def_test:OPERATION")
        chkCompile("@test module; query q() = 123;", "ct_err:def_test:QUERY")
        chkCompile("@test module; entity user { name; }", "ct_err:def_test:ENTITY")
        chkCompile("@test module; object state { mutable v: integer = 0; }", "ct_err:def_test:OBJECT")
        chkCompile("module; operation op() {}", "OK")
    }

    @Test fun testTransactions() {
        tstCtx.useSql = true

        file("prod.rell", """
            module;
            entity user { name; }
            operation add_user(name) { create user(name); }
            operation remove_user(name) { delete user @* { name }; }
        """)

        file("tests.rell", """
            @test module;
            import prod;

            function test_add_user() {
                assert_equals(prod.user@*{}(.name), list<text>());
                val tx = rell.test.tx(prod.add_user('Bob'));
                assert_equals(prod.user@*{}(.name), list<text>());
                tx.run();
                assert_equals(prod.user@*{}(.name), ['Bob']);
            }

            function test_remove_user() {
                rell.test.tx([prod.add_user('Bob'), prod.add_user('Alice')]).run();
                assert_equals(prod.user@*{}(.name), ['Bob', 'Alice']);
                val tx = rell.test.tx(prod.remove_user('Bob'));
                assert_equals(prod.user@*{}(.name), ['Bob', 'Alice']);
                tx.run();
                assert_equals(prod.user@*{}(.name), ['Alice']);
            }
        """)

        chkTests("tests", "test_add_user=OK,test_remove_user=OK")
    }

    @Test fun testMultipleBlocks() {
        tstCtx.useSql = true

        file("prod.rell", """
            module;
            entity user { name; }
            operation add_user(name) { create user(name); }
        """)

        file("tests.rell", """
            @test module;
            import prod;

            function rowid_to_int(r: rowid) = integer.from_gtv(r.to_gtv());

            function test_add_user() {
                assert_equals(prod.user@*{}(.name), list<text>());
                assert_equals(block@*{}(.block_height), list<integer>());
                assert_equals(transaction@*{}(rowid_to_int(.rowid)), list<integer>());

                prod.add_user('Bob').run();
                assert_equals(prod.user@*{}(.name), ['Bob']);
                assert_equals(block@*{}(.block_height), [0]);
                assert_equals(transaction@*{}(rowid_to_int(.rowid)), [1]);

                prod.add_user('Alice').run();
                assert_equals(prod.user@*{}(.name), ['Bob', 'Alice']);
                assert_equals(block@*{}(.block_height), [0, 1]);
                assert_equals(transaction@*{}(rowid_to_int(.rowid)), [1, 2]);

                prod.add_user('Trudy').run();
                assert_equals(prod.user@*{}(.name), ['Bob', 'Alice', 'Trudy']);
                assert_equals(block@*{}(.block_height), [0, 1, 2]);
                assert_equals(transaction@*{}(rowid_to_int(.rowid)), [1, 2, 3]);
            }
        """)

        chkTests("tests", "test_add_user=OK")
    }

    @Test fun testNamespacedTestFunctions() {
        file("some_tests.rell", """
            @test module;
            function test_foo() { print("foo"); require(true); }
            namespace ns { function test_bar() { print("bar"); require(true); } }
        """)
        chkTests("some_tests", "test_foo=OK")
        chkOut("foo")
    }

    @Test fun testImportFromRepl() {
        file("tests.rell", "@test module; function f() = 123;")
        repl.chk("import tests;")
        repl.chk("tests.f()", "RES:int[123]")
    }

    @Test fun testTestModuleAsParentOfRegularModule() {
        file("foo/module.rell", "@test module;")
        file("foo/a.rell", "module;")
        file("foo/a_test.rell", "@test module; function test() {}")
        file("foo/b/module.rell", "module;")
        file("foo/c/empty.rell", "")

        chkCompile("import foo.a;", "ct_err:foo/a.rell:module:parent_is_test:foo.a:foo")
        chkCompile("import foo.b;", "ct_err:foo/b/module.rell:module:parent_is_test:foo.b:foo")
        chkCompile("import foo.c;", "OK") // Empty file - nowhere to report an error
        chkCompile("import foo.a_test;", "ct_err:import:module_test:foo.a_test")

        chkTests("foo.a_test", "test=OK")
    }

    @Test fun testMountNameOfTestModule() {
        file("tests.rell", "@test @mount('foo') module; function test() {}")
        chkTests("tests", "ct_err:tests.rell:modifier:bad_combination:ann:test,ann:mount")
    }

    @Test fun testModuleArgs() {
        file("app.rell", """module;
            struct module_args { x: integer; }
            function f() = chain_context.args;
            query q() = chain_context.args;
            operation op() { print('o:' + chain_context.args); }
        """)

        file("test.rell", """@test module;
            import app;
            function test() {
                print('f:' + app.f());
                print('q:' + app.q());
                app.op().run();
            }
        """)

        tstCtx.useSql = true
        tst.moduleArgs("app" to "{'x':123}")

        chkTests("test", "test=OK")
        chkOut("f:app:module_args{x=123}", "q:app:module_args{x=123}", "o:app:module_args{x=123}")
    }

    @Test fun testCreateExternalEntity() {
        tstCtx.useSql = true

        file("ext.rell", "@external module; @log entity user { name; }")

        file("prod.rell", """
            module;
            import ext;
            operation add_user(name) { create ext.user(name); }
        """)

        file("tests.rell", """
            @test module;
            import prod;
            import ext;

            function test_add_user() {
                assert_equals(ext.user@*{}(.name), list<text>());
                rell.test.tx(prod.add_user('Bob')).run();
                assert_equals(ext.user@*{}(.name), ['Bob']);
                rell.test.tx(prod.add_user('Alice')).run();
                assert_equals(ext.user@*{}(.name), ['Bob', 'Alice']);
            }
        """)

        chkTests("tests", "test_add_user=OK")
    }
}
