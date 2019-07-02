package net.postchain.rell

import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.RellCodeTester
import org.junit.Test

class IncludeTest: BaseRellTest(false) {
    @Test fun testNormal() {
        tst.file("foo.rell", "function f(): text = 'Hello';")
        chkQueryEx("query q() = f();", "ct_err:unknown_name:f")
        chkQueryEx("include 'foo'; query q() = f();", "text[Hello]")
    }

    @Test fun testFileNotFound() {
        tst.file("foo.rell", "")
        chkCompile("include 'bar';", "ct_err:include_not_found:bar.rell")
        chkCompile("include 'foo.rell';", "ct_err:include_not_found:foo.rell.rell")
        chkCompile("include 'foo';", "OK")
    }

    @Test fun testRecursiveInclude() {
        chkInclude("OK",
                "main.rell" to "include 'a';",
                "a.rell" to "include 'b';",
                "b.rell" to "include 'a';"
        )

        chkInclude("OK",
                "main.rell" to "include 'a';",
                "a.rell" to "include 'b';",
                "b.rell" to "include 'main';"
        )

        chkInclude("OK",
                "main.rell" to "include 'a';",
                "a.rell" to "include 'main';"
        )

        chkInclude("ct_err:include_self:a.rell",
                "main.rell" to "include 'a';",
                "a.rell" to "include 'a';"
        )

        chkInclude("ct_err:include_self:main.rell", "main.rell" to "include 'main';")
    }

    @Test fun testRecursiveIncludeNamespace() {
        chkInclude("OK",
                "main.rell" to "include 'a';",
                "a.rell" to "include 'main';"
        )

        chkInclude("ct_err:include_rec:main.rell,a.rell,main.rell",
                "main.rell" to "namespace foo { include 'a'; }",
                "a.rell" to "include 'main';"
        )

        chkInclude("ct_err:include_rec:main.rell,a.rell,main.rell",
                "main.rell" to "include 'a';",
                "a.rell" to "namespace foo { include 'main'; }"
        )

        val files = mapOf(
                "main.rell" to "include 'a';",
                "a.rell" to "include 'b';",
                "b.rell" to "include 'c';",
                "c.rell" to "namespace foo { include 'd'; }",
                "d.rell" to "include 'e';",
                "e.rell" to "include 'f';"
        )

        chkInclude(files, "f.rell", "include 'main';", "ct_err:include_rec:main.rell,a.rell,b.rell,c.rell,d.rell,e.rell,f.rell,main.rell")
        chkInclude(files, "f.rell", "include 'a';", "ct_err:include_rec:a.rell,b.rell,c.rell,d.rell,e.rell,f.rell,a.rell")
        chkInclude(files, "f.rell", "include 'b';", "ct_err:include_rec:b.rell,c.rell,d.rell,e.rell,f.rell,b.rell")
        chkInclude(files, "f.rell", "include 'c';", "ct_err:include_rec:c.rell,d.rell,e.rell,f.rell,c.rell")
        chkInclude(files, "f.rell", "include 'd';", "OK")
        chkInclude(files, "f.rell", "include 'e';", "OK")
        chkInclude(files, "f.rell", "include 'f';", "ct_err:include_self:f.rell")
    }

    @Test fun testRecursiveIncludeNamespace2() {
        val files = mapOf(
                "main.rell" to "include 'a';",
                "a.rell" to "include 'b';",
                "b.rell" to "namespace foo { include 'c'; }",
                "c.rell" to "include 'd';",
                "d.rell" to "namespace bar { include 'e'; }",
                "e.rell" to "include 'f';",
                "f.rell" to "include 'g';"
        )

        chkInclude(files, "g.rell", "include 'main';", "ct_err:include_rec:main.rell,a.rell,b.rell,c.rell,d.rell,e.rell,f.rell,g.rell,main.rell")
        chkInclude(files, "g.rell", "include 'a';", "ct_err:include_rec:a.rell,b.rell,c.rell,d.rell,e.rell,f.rell,g.rell,a.rell")
        chkInclude(files, "g.rell", "include 'b';", "ct_err:include_rec:b.rell,c.rell,d.rell,e.rell,f.rell,g.rell,b.rell")
        chkInclude(files, "g.rell", "include 'c';", "ct_err:include_rec:c.rell,d.rell,e.rell,f.rell,g.rell,c.rell")
        chkInclude(files, "g.rell", "include 'd';", "ct_err:include_rec:d.rell,e.rell,f.rell,g.rell,d.rell")
        chkInclude(files, "g.rell", "include 'e';", "OK")
        chkInclude(files, "g.rell", "include 'f';", "OK")
        chkInclude(files, "g.rell", "include 'g';", "ct_err:include_self:g.rell")
    }

    @Test fun testRecursiveIncludeExternal() {
        chkInclude("OK",
                "main.rell" to "include 'a';",
                "a.rell" to "include 'main';"
        )

        chkInclude("ct_err:include_rec:main.rell,a.rell,main.rell",
                "main.rell" to "external 'foo' { include 'a'; }",
                "a.rell" to "include 'main';"
        )

        chkInclude("ct_err:include_rec:main.rell,a.rell,main.rell",
                "main.rell" to "include 'a';",
                "a.rell" to "external 'foo' { include 'main'; }"
        )

        val files = mapOf(
                "main.rell" to "include 'a';",
                "a.rell" to "include 'b';",
                "b.rell" to "include 'c';",
                "c.rell" to "external 'foo' { include 'd'; }",
                "d.rell" to "include 'e';",
                "e.rell" to "include 'f';"
        )

        chkInclude(files, "f.rell", "include 'main';", "ct_err:include_rec:main.rell,a.rell,b.rell,c.rell,d.rell,e.rell,f.rell,main.rell")
        chkInclude(files, "f.rell", "include 'a';", "ct_err:include_rec:a.rell,b.rell,c.rell,d.rell,e.rell,f.rell,a.rell")
        chkInclude(files, "f.rell", "include 'b';", "ct_err:include_rec:b.rell,c.rell,d.rell,e.rell,f.rell,b.rell")
        chkInclude(files, "f.rell", "include 'c';", "ct_err:include_rec:c.rell,d.rell,e.rell,f.rell,c.rell")
        chkInclude(files, "f.rell", "include 'd';", "OK")
        chkInclude(files, "f.rell", "include 'e';", "OK")
        chkInclude(files, "f.rell", "include 'f';", "ct_err:include_self:f.rell")
    }

    @Test fun testPaths() {
        tst.file("dir/a.rell", "include 'x';")
        tst.file("dir/b.rell", "include 'dir/x';")
        tst.file("dir/c.rell", "include '/x';")
        tst.file("dir/d.rell", "include '/dir/x';")
        tst.file("dir/e.rell", "include 'sub/y';")
        tst.file("dir/x.rell", "")
        tst.file("dir/sub/p.rell", "include '../x';")
        tst.file("dir/sub/q.rell", "include '/dir/x';")
        tst.file("dir/sub/y.rell", "")

        chkCompile("include 'dir/a';", "OK")
        chkCompile("include 'dir/b';", "ct_err:include_not_found:dir/dir/x.rell")
        chkCompile("include 'dir/c';", "ct_err:include_not_found:x.rell")
        chkCompile("include 'dir/d';", "OK")
        chkCompile("include 'dir/e';", "OK")
        chkCompile("include 'dir/sub/p';", "ct_err:include_bad_path:../x")
        chkCompile("include 'dir/sub/q';", "OK")

        chkCompile("include '';", "ct_err:include_bad_path:")
        chkCompile("include '/';", "ct_err:include_bad_path:/")
        chkCompile("include '.';", "ct_err:include_bad_path:.")
        chkCompile("include '..';", "ct_err:include_bad_path:..")
        chkCompile("include './a.rell';", "ct_err:include_bad_path:./a.rell")
        chkCompile("include './dir/a.rell';", "ct_err:include_bad_path:./dir/a.rell")
        chkCompile("include 'dir//a.rell';", "ct_err:include_bad_path:dir//a.rell")
        chkCompile("include 'dir/./a.rell';", "ct_err:include_bad_path:dir/./a.rell")
    }

    @Test fun testIncludeUnderNamespace() {
        tst.file("a.rell", "function f(): integer = 123;")
        chkQueryEx("namespace foo { include 'a'; } query q() = foo.f();", "int[123]")
        chkQueryEx("namespace foo { include 'a'; } query q() = f();", "ct_err:unknown_name:f")
    }

    @Test fun testIncludeUnderExternal() {
        tst.file("a.rell", "class user(log) { name; }")
        chkCompile("external 'foo' { include 'a'; } function f(u: user){}", "OK")
        chkCompile("external 'foo' { include 'a'; } function f(): integer = (user@{}).transaction;",
                "ct_err:entity_rettype:integer:external[foo].transaction")
        chkCompile("include 'a'; function f(): integer = (user@{}).transaction;",
                "ct_err:entity_rettype:integer:transaction")
    }

    @Test fun testInlcudeNotAtTop() {
        tst.file("a.rell", "")
        tst.file("b.rell", "")

        chkCompile("include 'a'; include 'b';", "OK")
        chkCompile("include 'a'; class user {} include 'b';", "OK")
        chkCompile("class user {} include 'a';", "OK")
        chkCompile("namespace foo {} include 'a';", "OK")

        chkCompile("namespace foo { include 'a'; include 'b'; }", "OK")
        chkCompile("namespace foo { include 'a'; class user {} include 'b'; }", "OK")
        chkCompile("namespace foo { class user {} include 'a'; }", "OK")

        chkCompile("external 'foo' { include 'a'; include 'b'; }", "OK")
        chkCompile("external 'foo' { include 'a'; class user(log) {} include 'b'; }", "OK")
        chkCompile("external 'foo' { class user(log) {} include 'a'; }", "OK")
    }

    @Test fun testIncludeInWrongPlace() {
        tst.file("a.rell", "")

        chkCompile("class user { include 'a'; }", "ct_err:syntax")
        chkCompile("object state { include 'a'; }", "ct_err:syntax")
        chkCompile("enum e { include 'a'; }", "ct_err:syntax")
        chkCompile("record rec { include 'a'; }", "ct_err:syntax")
        chkCompile("function f() { include 'a'; }", "ct_err:syntax")
        chkCompile("operation o() { include 'a'; }", "ct_err:syntax")
        chkCompile("query q() { include 'a'; }", "ct_err:syntax")
    }

    @Test fun testDuplicateInclude() {
        tst.file("a.rell", "")
        tst.file("b.rell", "")

        chkCompile("include 'a';", "OK")
        chkCompile("include 'a'; include 'a';", "ct_err:include_dup:a.rell")
        chkCompile("include 'a'; namespace foo { include 'a'; }", "OK")
        chkCompile("include 'a'; external 'foo' { include 'a'; }", "ct_err:include_dup:a.rell")
        chkCompile("include 'a'; namespace foo { external 'foo' { include 'a'; } }", "OK")
        chkCompile("include 'a'; external 'foo' { namespace foo { include 'a'; } }", "OK")
        chkCompile("namespace foo { include 'a'; include 'a'; }", "ct_err:include_dup:a.rell")
        chkCompile("namespace foo { include 'a'; include 'b'; include 'a'; }", "ct_err:include_dup:a.rell")
        chkCompile("namespace foo { include 'a'; include 'b'; namespace bar { include 'a'; } }", "OK")
        chkCompile("namespace foo { include 'a'; } namespace bar { include 'a'; }", "OK")
    }

    @Test fun testIndirectDuplicateInclude() {
        tst.file("a.rell", "include 'b';")
        tst.file("b.rell", "function f(): integer = 123;")

        chkCompile("include 'a';", "OK")
        chkCompile("include 'b';", "OK")
        chkCompile("include 'a'; include 'a';", "ct_err:include_dup:a.rell")

        chkQueryEx("include 'a'; include 'b'; query q() = f();", "int[123]")
        chkQueryEx("include 'b'; include 'a'; query q() = f();", "int[123]")
    }

    @Test fun testOuterDefinitionsAccess() {
        tst.file("a.rell", "function f(): integer = 123;")
        tst.file("b.rell", "function f(): integer = 456;")
        tst.file("c.rell", "function g(): integer = f();")
        def("namespace a { include 'a'; include 'c'; }")
        def("namespace b { include 'b'; include 'c'; }")
        def("namespace c { function f(): integer = 789; include 'c'; }")
        chk("a.g()", "int[123]")
        chk("b.g()", "int[456]")
        chk("c.g()", "int[789]")
    }

    @Test fun testLongIncludeChain() {
        for (i in 1 .. 100) tst.file("a$i.rell", "include 'a${i + 1}';")
        chkCompile("include 'a1';", "ct_err:include_long:100:main.rell:a99.rell")
    }

    @Test fun testIncludeVariousDefs() {
        tst.file("a.rell", """
            class cls {}
            object state { p: integer = 123; }
            record rec {}
            enum e { A }
            function f() {}
        """.trimIndent())

        def("namespace a { include 'a'; }")

        chkCompile("function x(c: a.cls){}", "OK")
        chkCompile("function x(): integer = a.state.p;", "OK")
        chkCompile("function x(r: a.rec){}", "OK")
        chkCompile("function x(e: a.e){}", "OK")
        chkCompile("function x(){ a.f(); }", "OK")
    }

    @Test fun testIncludeOperationQuery() {
        tst.file("a.rell", """
            operation o(){ print('Op done'); }
            query q() = 123;
        """.trimIndent())

        chkOpFull("include 'a';")
        chkStdout("Op done")

        chkQueryEx("include 'a';", "int[123]")
    }

    @Test fun testNameConflicts() {
        tst.file("a.rell", "class user { name; }")
        tst.file("b.rell", "class user { name; }")

        chkCompile("include 'a';", "OK")
        chkCompile("include 'b';", "OK")
        chkCompile("include 'a'; include 'b';", "ct_err:name_conflict:class:user")
        chkCompile("include 'a'; class user {}", "ct_err:name_conflict:class:user")
    }

    @Test fun testIncludeChain() {
        tst.file("a.rell", "include 'b';")
        tst.file("b.rell", "include 'c';")
        tst.file("c.rell", "function f(): integer = 123;")
        def("include 'a';")
        chk("f()", "int[123]")
    }

    @Test fun testErrorLocation() {
        tst.file("a.rell", "class user {}\nclass user {}")
        tst.file("b.rell", "class user {}\n*")
        tst.errMsgPos = true

        chkCompile("include 'a';", "ct_err:a.rell(2:7):name_conflict:class:user")
        chkCompile("include 'b';", "ct_err:b.rell(2:1):syntax")
    }

    private fun chkInclude(expected: String, vararg files: Pair<String, String>) {
        val map = files.toMap()
        chkInclude(map, expected)
    }

    private fun chkInclude(files: Map<String, String>, name: String, text: String, expected: String) {
        val files2 = files + mapOf(name to text)
        chkInclude(files2, expected)
    }

    private fun chkInclude(files: Map<String, String>, expected: String) {
        val t = RellCodeTester(tstCtx)
        t.chainDependency("foo", "abcd", 123)

        for ((file, code) in files) {
            if (file != "main.rell") {
                t.file(file, code)
            }
        }

        val mainCode = files.getValue("main.rell")
        t.chkCompile(mainCode, expected)
    }
}
