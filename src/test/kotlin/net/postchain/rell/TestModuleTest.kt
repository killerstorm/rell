package net.postchain.rell

import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_Function
import net.postchain.rell.runtime.Rt_GlobalContext
import net.postchain.rell.test.BaseRellTest
import net.postchain.rell.test.RellTestUtils
import net.postchain.rell.utils.TestRunner
import org.junit.Test
import kotlin.test.assertEquals

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
        chkCompile("import a;", "ct_err:[main.rell:import:module_test:a][a.rell:modifier:module:abstract:test]")
        chkCompile("import b;", "ct_err:[main.rell:import:module_test:b][b.rell:modifier:module:abstract:test]")
    }

    private fun chkTests(module: String, expected: String) {
        val actual = runTests(module)
        assertEquals(expected, actual)
    }

    private fun runTests(module: String): String {
        mainModule(module)

        val actual = tst.processApp("") { app ->
            val globalCtx = tst.createGlobalCtx()
            val mod = app.modules.find { it.name.str() == module }!!
            val fns = TestRunner.getTestFunctions(mod)
            val res = mutableMapOf<String, String>()
            for (f in fns) res[f.simpleName] = runTest(globalCtx, app, f)
            res.entries.joinToString(",")
        }

        return actual
    }

    private fun runTest(globalCtx: Rt_GlobalContext, app: R_App, f: R_Function): String {
        return RellTestUtils.catchRtErr {
            tstCtx.sqlMgr().execute(false) { sqlExec ->
                val exeCtx = tst.createExeCtx(globalCtx, sqlExec, app)
                f.callTop(exeCtx, listOf())
                "OK"
            }
        }
    }
}
