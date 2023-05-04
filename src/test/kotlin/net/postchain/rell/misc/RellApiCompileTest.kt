/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.misc

import net.postchain.gtv.GtvFactory
import net.postchain.rell.compiler.base.utils.C_CommonError
import net.postchain.rell.compiler.base.utils.C_SourceDir
import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.test.RellTestUtils
import net.postchain.rell.test.TestRellCliEnv
import net.postchain.rell.utils.PostchainGtvUtils
import net.postchain.rell.utils.cli.RellCliCompileConfig
import net.postchain.rell.utils.cli.RellCliInternalBaseApi
import net.postchain.rell.utils.immListOf
import org.junit.Test
import kotlin.test.assertEquals

class RellApiCompileTest: BaseRellApiTest() {
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
        val options = RellCliInternalBaseApi.makeCompilerOptions(config)

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
        val options = RellCliInternalBaseApi.makeCompilerOptions(config)
        val rModuleName = R_ModuleName.of(mainModule)
        val apiRes = compileApp0(config, options, sourceDir, listOf(rModuleName))

        val cRes = apiRes.cRes
        val ctErr = handleCompilationError(cRes)
        if (ctErr != null) return ctErr

        val gtv = RellCliInternalBaseApi.compileGtv0(config, sourceDir, immListOf(rModuleName), cRes.files)
        return PostchainGtvUtils.gtvToJson(gtv)
    }
}
