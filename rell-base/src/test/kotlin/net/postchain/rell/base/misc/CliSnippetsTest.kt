/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.misc

import net.postchain.rell.base.compiler.base.core.C_CompilerModuleSelection
import net.postchain.rell.base.compiler.base.core.C_CompilerOptions
import net.postchain.rell.base.compiler.base.utils.C_SourceDir
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.testutils.RellTestUtils
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class CliSnippetsTest {
    @Test fun testMod() = chkModule("mod")
    @Test fun testModComplexBar() = chkModule("mod.complex.bar")
    @Test fun testModComplexFoo() = chkModule("mod.complex.foo")

    @Test fun testCalc() = chkModule("calc")
    @Test fun testLegacyTest() = chkModule("legacy_test")
    @Test fun testMisc() = chkModule("misc")
    @Test fun testRunSimple() = chkModule("run_simple")
    @Test fun testStair() = chkModule("stair")

    @Test fun testAbstr() = chkModule("abstr.main")
    @Test fun testStackTrace() = chkModule("stack_trace")

    @Test fun testRunTests() {
        chkTestModules("run_tests.bar", "run_tests.bar_extra_test", "run_tests.common_test")
        chkTestModules("run_tests.foo", "run_tests.foo_extra_test", "run_tests.common_test")
        chkTestModules("run_tests.foo_10")
        chkTestModules("run_tests.generic")

        chkModule("run_tests.bar.bar_test")
        chkModule("run_tests.foo.foo_test")
        chkModule("run_tests.foo_10.foo_10_test")
        chkModule("run_tests.generic.tests")
        chkModule("run_tests.bar_extra_test")
        chkModule("run_tests.foo_extra_test")
        chkModule("run_tests.common_test")
    }

    @Test fun testTests() {
        chkTestModules("tests")
        chkModule("tests.calc_test")
        chkModule("tests.data_test")
        chkModule("tests.lib_test")
        chkModule("tests.foobar")
    }

    private fun chkModule(module: String) {
        val modules = listOf(R_ModuleName.of(module))
        val modSel = C_CompilerModuleSelection(modules, listOf())
        chkModules(modSel)
    }

    private fun chkTestModules(vararg modules: String) {
        val modNames = modules.map { R_ModuleName.of(it) }
        val modSel = C_CompilerModuleSelection(listOf(), modNames)
        chkModules(modSel)
    }

    private fun chkModules(modSel: C_CompilerModuleSelection) {
        val dir = File("../work/testproj/src")
        val sourceDir = C_SourceDir.diskDir(dir)
        val res = RellTestUtils.compileApp(sourceDir, modSel, C_CompilerOptions.DEFAULT)
        assertEquals(0, res.messages.size, res.messages.toString())
    }
}
