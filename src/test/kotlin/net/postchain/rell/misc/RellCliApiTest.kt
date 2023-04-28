/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.misc

import net.postchain.gtv.GtvFactory
import net.postchain.rell.compiler.base.core.C_CompilationResult
import net.postchain.rell.compiler.base.core.C_CompilerOptions
import net.postchain.rell.compiler.base.utils.C_CommonError
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.test.*
import net.postchain.rell.utils.PostchainGtvUtils
import net.postchain.rell.utils.cli.*
import net.postchain.rell.utils.immListOf
import net.postchain.rell.utils.toImmList
import org.junit.Test
import kotlin.test.assertEquals

class RellCliApiTest {
    private val generalSourceDir = C_SourceDir.mapDirOf(
        "a.rell" to "module;",
        "b1/module.rell" to "module;",
        "b1/b2.rell" to "module;",
        "c.rell" to "module;",
        "d.rell" to "@test module; import c;",
        "e1/module.rell" to "module;",
        "e1/e2/module.rell" to "@test module;",
        "e1/e2/e3.rell" to "@test module;",
    )

    private val defaultConfig = configBuilder().build()

    private fun configBuilder() = RellCliCompileConfig.Builder()

    @Test fun testCompileAppBasic() {
        val sourceDir = C_SourceDir.mapDirOf("main.rell" to "module; function main() {}")
        chkCompileAppModules(defaultConfig, sourceDir, listOf("main"), listOf(), "main")
    }

    @Test fun testCompileAppNoAppModules() {
        val config = configBuilder().includeTestSubModules(true).build()
        chkCompileAppModules(config, generalSourceDir, listOf(), listOf())
    }

    @Test fun testCompileAppAllAppModules() {
        val config = configBuilder().includeTestSubModules(true).build()
        chkCompileAppModules(config, generalSourceDir, null, listOf(), "a", "b1", "b1.b2", "c", "e1")
    }

    @Test fun testCompileAppSpecificAppModule() {
        val config = defaultConfig
        val sourceDir = generalSourceDir
        chkCompileAppModules(config, sourceDir, listOf("a"), listOf(), "a")
        chkCompileAppModules(config, sourceDir, listOf("b1"), listOf(), "b1")
        chkCompileAppModules(config, sourceDir, listOf("b1.b2"), listOf(), "b1", "b1.b2")
        chkCompileAppModules(config, sourceDir, listOf("c"), listOf(), "c")
        chkCompileAppModules(config, sourceDir, listOf("e1"), listOf(), "e1")
        chkCompileAppModules(config, sourceDir, listOf("a", "b1.b2"), listOf(), "a", "b1", "b1.b2")
    }

    @Test fun testCompileAppSpecificTestModule() {
        val config = configBuilder().includeTestSubModules(true).build()
        chkCompileAppModules(config, generalSourceDir, listOf("a"), listOf("d"), "a", "c", "d")
        chkCompileAppModules(config, generalSourceDir, listOf("a"), listOf("e1.e2"), "a", "e1.e2", "e1.e2.e3")
        chkCompileAppModules(config, generalSourceDir, listOf("b1"), listOf("d"), "b1", "c", "d")
        chkCompileAppModules(config, generalSourceDir, listOf(), listOf("d"), "c", "d")
        chkCompileAppModules(config, generalSourceDir, null, listOf("d"), "a", "b1", "b1.b2", "c", "d", "e1")
    }

    @Test fun testCompileAppAllTestModules() {
        val config = configBuilder().includeTestSubModules(true).build()
        chkCompileAppModules(config, generalSourceDir, listOf(), listOf(""), "c", "d", "e1.e2", "e1.e2.e3")
    }

    @Test fun testCompileAppTestSubModules() {
        val config1 = configBuilder().includeTestSubModules(true).build()
        val config2 = configBuilder().includeTestSubModules(false).build()
        chkCompileAppModules(config1, generalSourceDir, listOf(), listOf("d"), "c", "d")
        chkCompileAppModules(config2, generalSourceDir, listOf(), listOf("d"), "c", "d")
        chkCompileAppModules(config1, generalSourceDir, listOf(), listOf("e1.e2"), "e1.e2", "e1.e2.e3")
        chkCompileAppModules(config2, generalSourceDir, listOf(), listOf("e1.e2"), "e1.e2")
        chkCompileAppModules(config1, generalSourceDir, listOf(), listOf("e1"), "e1.e2", "e1.e2.e3")
        chkCompileAppModules(config2, generalSourceDir, listOf(), listOf("e1"), "CME:module:not_test:e1")
        chkCompileAppModules(config1, generalSourceDir, listOf(), listOf("c"), "CME:module:not_test:c")
        chkCompileAppModules(config2, generalSourceDir, listOf(), listOf("c"), "CME:module:not_test:c")
    }

    @Test fun testCompileAppTestSubModulesNotFound() {
        val config1 = configBuilder().includeTestSubModules(true).build()
        val config2 = configBuilder().includeTestSubModules(false).build()
        val sourceDir = C_SourceDir.mapDirOf("a/b/module.rell" to "@test module;")
        chkCompileAppModules(config1, sourceDir, listOf(), listOf("a"), "a.b")
        chkCompileAppModules(config2, sourceDir, listOf(), listOf("a"), "CME:import:not_found:a")
        chkCompileAppModules(config1, sourceDir, listOf(), listOf("x"), "CME:import:not_found:x")
        chkCompileAppModules(config2, sourceDir, listOf(), listOf("x"), "CME:import:not_found:x")
    }

    @Test fun testCompileAppTestModuleAsAppModule() {
        chkCompileAppModules(defaultConfig, generalSourceDir, listOf("d"), listOf(), "CME:module:main_test:d")
    }

    @Test fun testCompileAppModuleNotFound() {
        val config = defaultConfig
        chkCompileAppModules(config, generalSourceDir, listOf("foo"), listOf(), "CME:import:not_found:foo")
        chkCompileAppModules(config, generalSourceDir, listOf(), listOf("foo"), "CME:import:not_found:foo")

        val config2 = config.toBuilder().includeTestSubModules(false).build()
        chkCompileAppModules(config2, generalSourceDir, listOf(), listOf("foo"), "CME:import:not_found:foo")
    }

    @Test fun testCompileAppCompilationError() {
        val sourceDir = C_SourceDir.mapDirOf(
            "good.rell" to "module;",
            "bad.rell" to "module; import lib;",
            "lib.rell" to "module; val x: integer = true;",
        )
        chkCompileAppModules(defaultConfig, sourceDir, listOf("good"), listOf(), "good")
        chkCompileAppModules(defaultConfig, sourceDir, listOf("bad"), listOf(), "CTE:lib.rell:def:const_expr_type:[integer]:[boolean]")
    }

    @Test fun testCompileAppMountConflict() {
        val config1 = configBuilder().mountConflictError(true).build()
        val config2 = configBuilder().mountConflictError(false).build()

        val sourceDir = C_SourceDir.mapDirOf(
            "foo.rell" to "module; entity user {}",
            "bar.rell" to "module; entity user {}",
        )

        val err1 = "bar.rell:mnt_conflict:user:[bar:user]:user:ENTITY:[foo:user]:foo.rell(1:16)"
        val err2 = "foo.rell:mnt_conflict:user:[foo:user]:user:ENTITY:[bar:user]:bar.rell(1:16)"
        chkCompileAppModules(config1, sourceDir, null, listOf(), "CTE:[$err1][$err2]")
        chkCompileAppModules(config2, sourceDir, null, listOf(), "bar", "foo")

        chkCompileAppModules(config1, sourceDir, listOf(), listOf())
        chkCompileAppModules(config2, sourceDir, listOf(), listOf())
        chkCompileAppModules(config1, sourceDir, listOf("foo"), listOf(), "foo")
        chkCompileAppModules(config2, sourceDir, listOf("foo"), listOf(), "foo")
        chkCompileAppModules(config1, sourceDir, listOf("bar"), listOf(), "bar")
        chkCompileAppModules(config2, sourceDir, listOf("bar"), listOf(), "bar")
    }

    @Test fun testCompileAppMountConflictSys() {
        val config1 = configBuilder().mountConflictError(true).build()
        val config2 = configBuilder().mountConflictError(false).build()
        val sourceDir = C_SourceDir.mapDirOf("sys.rell" to "module; @mount('block') entity user {}")
        val err = "sys.rell:mnt_conflict:sys:[sys:user]:block:ENTITY:[block]"
        chkCompileAppModules(config1, sourceDir, null, listOf(), "CTE:$err")
        chkCompileAppModules(config2, sourceDir, null, listOf(), "sys")
        chkCompileAppModules(config1, sourceDir, listOf("sys"), listOf(), "CTE:$err")
        chkCompileAppModules(config2, sourceDir, listOf("sys"), listOf(), "sys")
    }

    @Test fun testCompileAppModuleArgs() {
        val sourceDir = C_SourceDir.mapDirOf(
            "foo.rell" to "module; struct module_args { x: integer; }",
            "bar.rell" to "module; struct module_args { y: text; }",
        )

        var config = defaultConfig
        chkCompileAppModules(config, sourceDir, listOf(), listOf())
        chkCompileAppModules(config, sourceDir, listOf("foo"), listOf(), "CME:module_args_missing:foo")
        chkCompileAppModules(config, sourceDir, listOf("bar"), listOf(), "CME:module_args_missing:bar")
        chkCompileAppModules(config, sourceDir, null, listOf(), "CME:module_args_missing:bar,foo")

        config = configBuilder().moduleArgsMissingError(false).build()
        chkCompileAppModules(config, sourceDir, listOf(), listOf())
        chkCompileAppModules(config, sourceDir, listOf("foo"), listOf(), "foo")
        chkCompileAppModules(config, sourceDir, listOf("bar"), listOf(), "bar")
        chkCompileAppModules(config, sourceDir, null, listOf(), "bar", "foo")

        val fooArgs = mapOf("x" to GtvFactory.gtv(123))
        val barArgs = mapOf("y" to GtvFactory.gtv("Hello"))

        config = configBuilder().moduleArgs("foo" to fooArgs).build()
        chkCompileAppModules(config, sourceDir, listOf("foo"), listOf(), "foo")
        chkCompileAppModules(config, sourceDir, listOf("bar"), listOf(), "CME:module_args_missing:bar")
        chkCompileAppModules(config, sourceDir, null, listOf(), "CME:module_args_missing:bar")

        config = configBuilder().moduleArgs("bar" to barArgs).build()
        chkCompileAppModules(config, sourceDir, listOf("foo"), listOf(), "CME:module_args_missing:foo")
        chkCompileAppModules(config, sourceDir, listOf("bar"), listOf(), "bar")
        chkCompileAppModules(config, sourceDir, null, listOf(), "CME:module_args_missing:foo")

        config = configBuilder().moduleArgs("foo" to barArgs).build()
        chkCompileAppModules(config, sourceDir, listOf(), listOf())
        chkCompileAppModules(config, sourceDir, listOf("foo"), listOf(), "CME:module_args_bad:foo")
        chkCompileAppModules(config, sourceDir, listOf("bar"), listOf(), "CME:module_args_missing:bar")
        chkCompileAppModules(config, sourceDir, null, listOf(), "CME:module_args_missing:bar")
    }

    private fun chkCompileAppModules(
        config: RellCliCompileConfig,
        sourceDir: C_SourceDir,
        appModules: List<String>?,
        testModules: List<String>,
        vararg expected: String,
    ) {
        val env = TestRellCliEnv()
        val config2 = config.toBuilder().cliEnv(env).build()

        val appMods = appModules?.map { R_ModuleName.of(it) }
        val testMods = testModules.map { R_ModuleName.of(it) }
        val actualList = compileApp(config2, sourceDir, appMods, testMods)

        assertEquals(expected.toList(), actualList)
    }

    private fun compileApp(
        config: RellCliCompileConfig,
        sourceDir: C_SourceDir,
        appModules: List<R_ModuleName>?,
        testModules: List<R_ModuleName> = immListOf(),
    ): List<String> {
        val options = RellCliInternalApi.makeCompilerOptions(config)

        val apiRes = try {
            compileApp0(config, options, sourceDir, appModules, testModules)
        } catch (e: C_CommonError) {
            return listOf("CME:${e.code}")
        }

        val res = apiRes.cRes
        val ctErr = handleCompilationError(res)
        return if (ctErr != null) listOf(ctErr) else res.app!!.moduleMap.keys.map { it.str() }.sorted()
    }

    @Test fun testCompileGtv() {
        val rellVer = RellTestUtils.RELL_VER
        chkCompileGtv(defaultConfig, generalSourceDir, "a",
            """{"modules":["a"],"sources":{"a.rell":"module;"},"version":"$rellVer"}"""
        )
        chkCompileGtv(defaultConfig, generalSourceDir, "b1.b2",
            """{"modules":["b1.b2"],"sources":{"b1/b2.rell":"module;","b1/module.rell":"module;"},"version":"$rellVer"}"""
        )
        chkCompileGtv(defaultConfig, generalSourceDir, "d", "CME:module:main_test:d")
    }

    @Test fun testCompileGtvModuleArgs() {
        val sourceDir = C_SourceDir.mapDirOf(
            "foo.rell" to "module; struct module_args { x: integer; }",
            "bar.rell" to "module;",
            "ref.rell" to "module; import foo;",
        )

        val fooEntry = """"foo.rell":"module; struct module_args { x: integer; }""""
        val barEntry = """"bar.rell":"module;""""
        val refEntry = """"ref.rell":"module; import foo;""""
        val verEntry = """"version":"${RellTestUtils.RELL_VER}""""

        var config = defaultConfig
        chkCompileGtv(config, sourceDir, "foo", "CME:module_args_missing:foo")
        chkCompileGtv(config, sourceDir, "ref", "CME:module_args_missing:foo")
        chkCompileGtv(config, sourceDir, "bar", """{"modules":["bar"],"sources":{$barEntry},$verEntry}""")

        config = configBuilder().moduleArgsMissingError(false).build()
        chkCompileGtv(config, sourceDir, "foo", """{"modules":["foo"],"sources":{$fooEntry},$verEntry}""")
        chkCompileGtv(config, sourceDir, "ref", """{"modules":["ref"],"sources":{$fooEntry,$refEntry},$verEntry}""")
        chkCompileGtv(config, sourceDir, "bar", """{"modules":["bar"],"sources":{$barEntry},$verEntry}""")

        val fooArgs = mapOf("x" to GtvFactory.gtv(123))
        val argsEntry = """"moduleArgs":{"foo":{"x":123}}"""
        config = configBuilder().moduleArgs("foo" to fooArgs).build()
        chkCompileGtv(config, sourceDir, "foo", """{$argsEntry,"modules":["foo"],"sources":{$fooEntry},$verEntry}""")
        chkCompileGtv(config, sourceDir, "ref", """{$argsEntry,"modules":["ref"],"sources":{$fooEntry,$refEntry},$verEntry}""")
        chkCompileGtv(config, sourceDir, "bar", """{$argsEntry,"modules":["bar"],"sources":{$barEntry},$verEntry}""")

        val wrongArgs = mapOf("x" to GtvFactory.gtv("Hello"))
        val wrongArgsEntry = """"moduleArgs":{"foo":{"x":"Hello"}}"""
        config = configBuilder().moduleArgs("foo" to wrongArgs).build()
        chkCompileGtv(config, sourceDir, "foo", "CME:module_args_bad:foo")
        chkCompileGtv(config, sourceDir, "ref", "CME:module_args_bad:foo")
        chkCompileGtv(config, sourceDir, "bar", """{$wrongArgsEntry,"modules":["bar"],"sources":{$barEntry},$verEntry}""")
    }

    private fun chkCompileGtv(
        config: RellCliCompileConfig,
        sourceDir: C_SourceDir,
        mainModule: String,
        expected: String,
    ) {
        val actual = compileGtv(config, sourceDir, mainModule)
        assertEquals(expected, actual)
    }

    private fun compileGtv(
        config: RellCliCompileConfig,
        sourceDir: C_SourceDir,
        mainModule: String,
    ): String {
        return try {
            compileGtv0(config, sourceDir, mainModule)
        } catch (e: C_CommonError) {
            "CME:${e.code}"
        }
    }

    private fun compileGtv0(
        config: RellCliCompileConfig,
        sourceDir: C_SourceDir,
        mainModule: String,
    ): String {
        val options = RellCliInternalApi.makeCompilerOptions(config)
        val rModuleName = R_ModuleName.of(mainModule)
        val apiRes = compileApp0(config, options, sourceDir, listOf(rModuleName))

        val cRes = apiRes.cRes
        val ctErr = handleCompilationError(cRes)
        if (ctErr != null) return ctErr

        val gtv = RellCliInternalApi.compileGtv0(config, sourceDir, immListOf(rModuleName), cRes.files)
        return PostchainGtvUtils.gtvToJson(gtv)
    }

    @Test fun testRunTestsBasic() {
        val runConfig = RellCliRunTestsConfig.Builder().build()
        val sourceDir = C_SourceDir.mapDirOf(
            "test.rell" to "@test module; function test_1(){} function test_2(){} function test_3(){}",
        )
        chkRunTests(runConfig, sourceDir, listOf(), listOf("test"),
            "test:test_1:OK", "test:test_2:OK", "test:test_3:OK"
        )
    }

    @Test fun testRunTestsStopOnError() {
        val runConfig = RellCliRunTestsConfig.Builder().build()
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
        val runConfig1 = RellCliRunTestsConfig.Builder().compileConfig(compileConfig1).build()
        val runConfig2 = RellCliRunTestsConfig.Builder().compileConfig(compileConfig2).build()

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
        val runConfig = RellCliRunTestsConfig.Builder().build()
        chkRunTests(runConfig, generalSourceDir, listOf(), listOf("foo"), "CME:import:not_found:foo")
    }

    @Test fun testRunTestsAllAppModules() {
        val runConfig = RellCliRunTestsConfig.Builder().build()
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

        var runConfig = RellCliRunTestsConfig.Builder().build()
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
        compileConfig: RellCliCompileConfig.Builder,
        proto: RellCliRunTestsConfig = RellCliRunTestsConfig.DEFAULT,
    ): RellCliRunTestsConfig {
        return proto.toBuilder().compileConfig(compileConfig.build()).build()
    }

    private fun runTestsDbConfig(): RellCliRunTestsConfig {
        return RellCliRunTestsConfig.Builder()
            .databaseUrl(SqlTestUtils.getDbUrl())
            .build()
    }

    private fun chkRunTestsFnExtend(
        compileConfig: RellCliCompileConfig,
        sourceDir: C_SourceDir,
        appModules: List<String>?,
        testModules: List<String>,
        expectedOut: String,
    ) {
        val printer = Rt_TestPrinter()
        val runConfig = RellCliRunTestsConfig.Builder()
            .compileConfig(compileConfig)
            .outPrinter(printer)
            .build()
        chkRunTests(runConfig, sourceDir, appModules, testModules, "test:test:OK")
        printer.chk(expectedOut)
    }

    private fun chkRunTests(
        config: RellCliRunTestsConfig,
        sourceDir: C_SourceDir,
        appModules: List<String>?,
        testModules: List<String>,
        vararg expected: String,
    ) {
        val actualList = runTests(config, sourceDir, appModules, testModules)
        assertEquals(expected.toList(), actualList)
    }

    private fun runTests(
        config: RellCliRunTestsConfig,
        sourceDir: C_SourceDir,
        appModules: List<String>?,
        testModules: List<String>,
    ): List<String> {
        val appMods = appModules?.map { R_ModuleName.of(it) }
        val testMods = testModules.map { R_ModuleName.of(it) }

        val options = RellCliInternalApi.makeCompilerOptions(config.compileConfig)

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

        val res = RellCliInternalApi.runTests(config2, options, sourceDir, rApp, appMods, apiRes.moduleArgs)
        val resList = res.getResults().map { "${it.case.name}:${it.res}" }

        assertEquals(actualList, resList)
        return actualList.toImmList()
    }

    @Test fun testRunShell() {
        val sourceDir = C_SourceDir.mapDirOf(
            "start.rell" to "module; function f() = 123;",
            "lib.rell" to "module; function g() = 456;",
        )

        val inChannelFactory = RellReplTester.TestReplInputChannelFactory(
            "2+2",
            "f()",
            "g()",
            "import lib;",
            "lib.g()",
            "\\q",
        )

        val outChannelFactory = RellReplTester.TestReplOutputChannelFactory()

        val runConfig = RellCliRunShellConfig.Builder()
            .compileConfig(defaultConfig)
            .inputChannelFactory(inChannelFactory)
            .outputChannelFactory(outChannelFactory)
            .build()

        RellCliInternalApi.runShell(runConfig, sourceDir, R_ModuleName.of("start"))
        outChannelFactory.chk("RES:int[4]", "RES:int[123]", "CTE:<console>:unknown_name:g", "RES:int[456]")
    }

    @Test fun testRunShellBugEnum() {
        val sourceDir = C_SourceDir.mapDirOf("lib.rell" to "module; enum color { red }")
        val inChannelFactory = RellReplTester.TestReplInputChannelFactory("color.red", "\\q")
        val outChannelFactory = RellReplTester.TestReplOutputChannelFactory()

        val runConfig = RellCliRunShellConfig.Builder()
            .compileConfig(defaultConfig)
            .inputChannelFactory(inChannelFactory)
            .outputChannelFactory(outChannelFactory)
            .build()

        RellCliInternalApi.runShell(runConfig, sourceDir, R_ModuleName.of("lib"))
        outChannelFactory.chk("RES:lib:color[red]")
    }

    // Important to call this function instead of calling the API directly - to record test snippets.
    private fun compileApp0(
        config: RellCliCompileConfig,
        options: C_CompilerOptions,
        sourceDir: C_SourceDir,
        appModules: List<R_ModuleName>?,
        testModules: List<R_ModuleName> = immListOf(),
    ): RellCliCompilationResult {
        val apiRes = RellCliInternalApi.compileApp0(config, options, sourceDir, appModules, testModules)
        val modSel = RellCliInternalApi.makeCompilerModuleSelection(config, appModules, testModules)
        TestSnippetsRecorder.record(sourceDir, modSel, options, apiRes.cRes)
        return apiRes
    }

    private fun handleCompilationError(cRes: C_CompilationResult): String? {
        return when {
            cRes.errors.isNotEmpty() -> "CTE:${RellTestUtils.errsToString(cRes.errors, false)}"
            cRes.app == null -> "ERR:no_app"
            else -> null
        }
    }
}
