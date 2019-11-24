package net.postchain.rell.misc

import net.postchain.rell.model.R_ModuleName
import net.postchain.rell.parser.C_CompilerOptions
import net.postchain.rell.parser.C_DiskSourceDir
import net.postchain.rell.test.RellTestUtils
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

    private fun chkModule(module: String) {
        val dir = File("test-cli/src")
        val sourceDir = C_DiskSourceDir(dir)

        val modules = listOf(R_ModuleName.of(module))
        val res = RellTestUtils.compileApp(sourceDir, modules, C_CompilerOptions.DEFAULT)

        assertEquals(0, res.messages.size)
    }
}
