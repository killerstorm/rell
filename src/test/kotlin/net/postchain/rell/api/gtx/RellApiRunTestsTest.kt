/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.api.gtx

import net.postchain.gtv.GtvFactory
import net.postchain.rell.api.base.BaseRellApiTest
import net.postchain.rell.api.base.RellApiBaseInternal
import net.postchain.rell.api.base.RellApiCompile
import net.postchain.rell.base.compiler.base.utils.C_CommonError
import net.postchain.rell.base.compiler.base.utils.C_SourceDir
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.testutils.Rt_TestPrinter
import net.postchain.rell.base.testutils.SqlTestUtils
import net.postchain.rell.base.utils.toImmList
import kotlin.test.Test
import kotlin.test.assertEquals

class RellApiRunTestsTest: BaseRellApiTest() {
    @Test fun testRunTestsBasic() {
        val runConfig = RellApiRunTests.Config.Builder().build()
        val sourceDir = C_SourceDir.mapDirOf(
            "test.rell" to "@test module; function test_1(){} function test_2(){} function test_3(){}",
        )
        chkRunTests(runConfig, sourceDir, listOf(), listOf("test"),
            "test:test_1:OK", "test:test_2:OK", "test:test_3:OK"
        )
    }

    @Test fun testRunTestsStopOnError() {
        val runConfig = RellApiRunTests.Config.Builder().build()
        val sourceDir = C_SourceDir.mapDirOf(
            "test.rell" to "@test module; function test_1(){} function test_2(){ assert_true(false); } function test_3(){}",
        )

        chkRunTests(runConfig, sourceDir, listOf(), listOf("test"),
            "test:test_1:OK", "test:test_2:FAILED", "test:test_3:OK"
        )

        val runConfig1 = runConfig.toBuilder().stopOnError(false).build()
        chkRunTests(runConfig1, sourceDir, listOf(), listOf("test"),
            "test:test_1:OK", "test:test_2:FAILED", "test:test_3:OK"
        )

        val runConfig2 = runConfig.toBuilder().stopOnError(true).build()
        chkRunTests(runConfig2, sourceDir, listOf(), listOf("test"), "test:test_1:OK", "test:test_2:FAILED")
    }

    @Test fun testRunTestsSubModules() {
        val compileConfig1 = configBuilder().includeTestSubModules(true).build()
        val compileConfig2 = configBuilder().includeTestSubModules(false).build()
        val runConfig1 = RellApiRunTests.Config.Builder().compileConfig(compileConfig1).build()
        val runConfig2 = RellApiRunTests.Config.Builder().compileConfig(compileConfig2).build()

        val sourceDir = C_SourceDir.mapDirOf(
            "a/module.rell" to "module;",
            "a/b/module.rell" to "@test module; function test_1(){}",
            "a/b/c/module.rell" to "@test module; function test_2(){}",
            "a/b/c/d.rell" to "@test module; function test_3(){}",
        )

        chkRunTests(runConfig1, sourceDir, listOf(), listOf("a.b"),
            "a.b:test_1:OK", "a.b.c:test_2:OK", "a.b.c.d:test_3:OK")
        chkRunTests(runConfig2, sourceDir, listOf(), listOf("a.b"), "a.b:test_1:OK")
        chkRunTests(runConfig1, sourceDir, listOf(), listOf("a.b.c"), "a.b.c:test_2:OK", "a.b.c.d:test_3:OK")
        chkRunTests(runConfig2, sourceDir, listOf(), listOf("a.b.c"), "a.b.c:test_2:OK")
        chkRunTests(runConfig1, sourceDir, listOf(), listOf("a.b.c.d"), "a.b.c.d:test_3:OK")
        chkRunTests(runConfig2, sourceDir, listOf(), listOf("a.b.c.d"), "a.b.c.d:test_3:OK")
        chkRunTests(runConfig1, sourceDir, listOf(), listOf("a"),
            "a.b:test_1:OK", "a.b.c:test_2:OK", "a.b.c.d:test_3:OK")
        chkRunTests(runConfig2, sourceDir, listOf(), listOf("a"), "CME:module:not_test:a")
        chkRunTests(runConfig1, sourceDir, listOf(), listOf(""),
            "a.b:test_1:OK", "a.b.c:test_2:OK", "a.b.c.d:test_3:OK")
        chkRunTests(runConfig2, sourceDir, listOf(), listOf(""), "CME:import:not_found:")
    }

    @Test fun testRunTestsModuleNotFound() {
        val runConfig = RellApiRunTests.Config.Builder().build()
        chkRunTests(runConfig, generalSourceDir, listOf(), listOf("foo"), "CME:import:not_found:foo")
    }

    @Test fun testRunTestsAllAppModules() {
        val runConfig = RellApiRunTests.Config.Builder().build()
        val sourceDir = C_SourceDir.mapDirOf(
            "lib.rell" to "module; @extendable function f(): integer?;",
            "def.rell" to "module; import lib; @extend(lib.f) function() = 123;",
            "test.rell" to "@test module; import lib; function test_1(){ assert_equals(lib.f(), 123); }",
        )

        chkRunTests(runConfig, sourceDir, listOf(), listOf("test"), "test:test_1:FAILED")
        chkRunTests(runConfig, sourceDir, listOf("lib"), listOf("test"), "test:test_1:FAILED")
        chkRunTests(runConfig, sourceDir, listOf("def"), listOf("test"), "test:test_1:OK")
        chkRunTests(runConfig, sourceDir, null, listOf("test"), "test:test_1:OK")
    }

    @Test fun testRunTestsModuleArgs() {
        val sourceDir = C_SourceDir.mapDirOf(
            "foo.rell" to """
                @test module;
                struct module_args { x: integer; }
                function test_1() { assert_equals(chain_context.args.x, 123); }
            """,
            "bar.rell" to """
                @test module;
                struct module_args { y: text; }
                function test_2() { assert_equals(chain_context.args.y, 'Hello'); }
            """,
        )

        val fooArgs = mapOf("x" to GtvFactory.gtv(123))
        val barArgs = mapOf("y" to GtvFactory.gtv("Hello"))

        var runConfig = RellApiRunTests.Config.Builder().build()
        chkRunTests(runConfig, sourceDir, listOf(), listOf(""), "CME:module_args_missing:bar,foo")
        runConfig = runTestsConfig(configBuilder().moduleArgs())
        chkRunTests(runConfig, sourceDir, listOf(), listOf(""), "CME:module_args_missing:bar,foo")
        runConfig = runTestsConfig(configBuilder().moduleArgs("foo" to fooArgs))
        chkRunTests(runConfig, sourceDir, listOf(), listOf(""), "CME:module_args_missing:bar")
        runConfig = runTestsConfig(configBuilder().moduleArgs("bar" to barArgs))
        chkRunTests(runConfig, sourceDir, listOf(), listOf(""), "CME:module_args_missing:foo")
        runConfig = runTestsConfig(configBuilder().moduleArgs("foo" to barArgs, "bar" to barArgs))
        chkRunTests(runConfig, sourceDir, listOf(), listOf(""), "CME:module_args_bad:foo")
        runConfig = runTestsConfig(configBuilder().moduleArgs("foo" to fooArgs, "bar" to fooArgs))
        chkRunTests(runConfig, sourceDir, listOf(), listOf(""), "CME:module_args_bad:bar")
        runConfig = runTestsConfig(configBuilder().moduleArgs("foo" to fooArgs, "bar" to barArgs))
        chkRunTests(runConfig, sourceDir, listOf(), listOf(""), "bar:test_2:OK", "foo:test_1:OK")

        val fooArgs2 = mapOf("x" to GtvFactory.gtv(456))
        val barArgs2 = mapOf("y" to GtvFactory.gtv("Bye"))
        runConfig = runTestsConfig(configBuilder().moduleArgs("foo" to fooArgs2, "bar" to barArgs))
        chkRunTests(runConfig, sourceDir, listOf(), listOf(""), "bar:test_2:OK", "foo:test_1:FAILED")
        runConfig = runTestsConfig(configBuilder().moduleArgs("foo" to fooArgs, "bar" to barArgs2))
        chkRunTests(runConfig, sourceDir, listOf(), listOf(""), "bar:test_2:FAILED", "foo:test_1:OK")
    }

    @Test fun testRunTestsMainModules() {
        val sourceDir = C_SourceDir.mapDirOf(
            "lib.rell" to "module; operation op() {}",
            "test.rell" to "@test module; import lib; function test() { lib.op().run(); }",
        )

        var runConfig = runTestsDbConfig()
        chkRunTests(runConfig, sourceDir, listOf(), listOf("test"), "test:test:OK")
        runConfig = runConfig.toBuilder().addTestDependenciesToBlockRunModules(false).build()
        chkRunTests(runConfig, sourceDir, listOf(), listOf("test"), "test:test:FAILED")
        chkRunTests(runConfig, sourceDir, listOf("lib"), listOf("test"), "test:test:OK")
    }

    @Test fun testRunTestsModuleArgsOperation() {
        val sourceDir = C_SourceDir.mapDirOf(
            "lib.rell" to """
                module;
                struct module_args { x: integer; }
                operation op() { require(chain_context.args.x > 0); }
            """,
            "test.rell" to """
                @test module;
                import lib;
                function test() {
                    print('begin');
                    lib.op().run();
                    print('end');
                }
            """,
        )

        var runConfig = runTestsDbConfig()
        chkRunTests(runConfig, sourceDir, listOf("lib"), listOf("test"), "CME:module_args_missing:lib")
        runConfig = runTestsConfig(configBuilder().moduleArgs("lib" to mapOf("x" to GtvFactory.gtv(123))), runConfig)
        chkRunTests(runConfig, sourceDir, listOf("lib"), listOf("test"), "test:test:OK")
        runConfig = runTestsConfig(configBuilder().moduleArgs("lib" to mapOf("x" to GtvFactory.gtv(1))), runConfig)
        chkRunTests(runConfig, sourceDir, listOf("lib"), listOf("test"), "test:test:OK")
        runConfig = runTestsConfig(configBuilder().moduleArgs("lib" to mapOf("x" to GtvFactory.gtv(0))), runConfig)
        chkRunTests(runConfig, sourceDir, listOf("lib"), listOf("test"), "test:test:FAILED")
    }

    @Test fun testRunTestsFunctionExtend() {
        val sourceDir = C_SourceDir.mapDirOf(
            "f.rell" to "module; @extendable function f(): list<text> = ['f'];",
            "g.rell" to "module; import f.*; @extend(f) function g() = ['g'];",
            "h.rell" to "module; import f.*; @extend(f) function h() = ['h'];",
            "test.rell" to "@test module; import f; import g; import h; function test() { print(f.f()); }",
        )

        val config = configBuilder().appModuleInTestsError(false).build()

        chkRunTestsFnExtend(config, sourceDir, listOf(), listOf("test"), "[f]")
        chkRunTestsFnExtend(config, sourceDir, listOf("g"), listOf("test"), "[g, f]")
        chkRunTestsFnExtend(config, sourceDir, listOf("h"), listOf("test"), "[h, f]")
        chkRunTestsFnExtend(config, sourceDir, listOf("g", "h"), listOf("test"), "[g, h, f]")
        chkRunTestsFnExtend(config, sourceDir, listOf(), listOf("g", "h", "test"), "[f]")
        chkRunTestsFnExtend(config, sourceDir, listOf("g"), listOf("g", "h", "test"), "[g, f]")
        chkRunTestsFnExtend(config, sourceDir, listOf("h"), listOf("g", "h", "test"), "[h, f]")
        chkRunTestsFnExtend(config, sourceDir, listOf("g", "h"), listOf("g", "h", "test"), "[g, h, f]")
    }

    private fun runTestsConfig(
        compileConfig: RellApiCompile.Config.Builder,
        proto: RellApiRunTests.Config = RellApiRunTests.Config.DEFAULT,
    ): RellApiRunTests.Config {
        return proto.toBuilder().compileConfig(compileConfig.build()).build()
    }

    private fun runTestsDbConfig(): RellApiRunTests.Config {
        return RellApiRunTests.Config.Builder()
            .databaseUrl(SqlTestUtils.getDbUrl())
            .build()
    }

    private fun chkRunTestsFnExtend(
        compileConfig: RellApiCompile.Config,
        sourceDir: C_SourceDir,
        appModules: List<String>?,
        testModules: List<String>,
        expectedOut: String,
    ) {
        val printer = Rt_TestPrinter()
        val runConfig = RellApiRunTests.Config.Builder()
            .compileConfig(compileConfig)
            .outPrinter(printer)
            .build()
        chkRunTests(runConfig, sourceDir, appModules, testModules, "test:test:OK")
        printer.chk(expectedOut)
    }

    private fun chkRunTests(
        config: RellApiRunTests.Config,
        sourceDir: C_SourceDir,
        appModules: List<String>?,
        testModules: List<String>,
        vararg expected: String,
    ) {
        val actualList = runTests(config, sourceDir, appModules, testModules)
        assertEquals(expected.toList(), actualList)
    }

    private fun runTests(
        config: RellApiRunTests.Config,
        sourceDir: C_SourceDir,
        appModules: List<String>?,
        testModules: List<String>,
    ): List<String> {
        val appMods = appModules?.map { R_ModuleName.of(it) }
        val testMods = testModules.map { R_ModuleName.of(it) }

        val options = RellApiBaseInternal.makeCompilerOptions(config.compileConfig)

        val apiRes = try {
            compileApp0(config.compileConfig, options, sourceDir, appMods, testMods)
        } catch (e: C_CommonError) {
            return listOf("CME:${e.code}")
        }

        val cRes = apiRes.cRes
        val ctErr = handleCompilationError(cRes)
        if (ctErr != null) return listOf(ctErr)
        val rApp = cRes.app!!

        val actualList = mutableListOf<String>()
        val config2 = config.toBuilder()
            .onTestCaseFinished { actualList.add("${it.case.name}:${it.res}") }
            .build()

        val res = RellApiGtxInternal.runTests(config2, options, sourceDir, rApp, appMods, apiRes.moduleArgs)
        val resList = res.getResults().map { "${it.case.name}:${it.res}" }

        assertEquals(actualList, resList)
        return actualList.toImmList()
    }
}
