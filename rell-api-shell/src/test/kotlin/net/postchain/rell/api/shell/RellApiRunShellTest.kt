/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.api.shell

import net.postchain.rell.api.base.BaseRellApiTest
import net.postchain.rell.base.compiler.base.utils.C_SourceDir
import net.postchain.rell.base.model.R_ModuleName
import net.postchain.rell.base.testutils.RellReplTester
import org.junit.Test

class RellApiRunShellTest: BaseRellApiTest() {
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

        val runConfig = RellApiRunShell.Config.Builder()
            .compileConfig(defaultConfig)
            .inputChannelFactory(inChannelFactory)
            .outputChannelFactory(outChannelFactory)
            .build()

        RellApiShellInternal.runShell(runConfig, sourceDir, R_ModuleName.of("start"))
        outChannelFactory.chk("RES:int[4]", "RES:int[123]", "CTE:<console>:unknown_name:g", "RES:int[456]")
    }

    @Test fun testRunShellBugEnum() {
        val sourceDir = C_SourceDir.mapDirOf("lib.rell" to "module; enum color { red }")
        val inChannelFactory = RellReplTester.TestReplInputChannelFactory("color.red", "\\q")
        val outChannelFactory = RellReplTester.TestReplOutputChannelFactory()

        val runConfig = RellApiRunShell.Config.Builder()
            .compileConfig(defaultConfig)
            .inputChannelFactory(inChannelFactory)
            .outputChannelFactory(outChannelFactory)
            .build()

        RellApiShellInternal.runShell(runConfig, sourceDir, R_ModuleName.of("lib"))
        outChannelFactory.chk("RES:lib:color[red]")
    }
}
