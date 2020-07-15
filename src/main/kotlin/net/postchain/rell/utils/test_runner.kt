package net.postchain.rell.utils

import net.postchain.rell.compiler.C_SourceDir
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_Function
import net.postchain.rell.model.R_Module
import net.postchain.rell.runtime.Rt_AppContext
import net.postchain.rell.runtime.Rt_ExecutionContext
import net.postchain.rell.runtime.Rt_GlobalContext
import net.postchain.rell.runtime.Rt_SqlContext
import net.postchain.rell.sql.SqlManager
import net.postchain.rell.sql.SqlUtils

private sealed class TestResult

private object TestResult_OK: TestResult() {
    override fun toString() = "OK"
}

private class TestResult_Fail(val error: Throwable): TestResult() {
    override fun toString() = "FAILED"
}

object TestRunner {
    private val SEPARATOR = "-".repeat(72)

    fun runTests(
            sqlMgr: SqlManager,
            app: R_App,
            globalCtx: Rt_GlobalContext,
            sqlCtx: Rt_SqlContext,
            sourceDir: C_SourceDir,
            fns: List<R_Function>
    ): Boolean {
        val resMap = runTestCases(sqlMgr, app, globalCtx, sqlCtx, sourceDir, fns)
        return processTestResults(resMap)
    }

    private fun runTestCases(
            sqlMgr: SqlManager,
            app: R_App,
            globalCtx: Rt_GlobalContext,
            sqlCtx: Rt_SqlContext,
            sourceDir: C_SourceDir,
            fns: List<R_Function>
    ): Map<String, TestResult> {
        val res = mutableMapOf<String, TestResult>()

        for (f in fns) {
            val v = runTestCase(sqlMgr, app, globalCtx, sqlCtx, sourceDir, f)
            res[f.appLevelName] = v
        }

        return res.toImmMap()
    }

    private fun runTestCase(
            sqlMgr: SqlManager,
            app: R_App,
            globalCtx: Rt_GlobalContext,
            sqlCtx: Rt_SqlContext,
            sourceDir: C_SourceDir,
            f: R_Function
    ): TestResult {
        println(SEPARATOR)
        println("TEST ${f.appLevelName}")

        val appCtx = Rt_AppContext(
                globalCtx,
                sqlCtx,
                app,
                repl = false,
                test = true,
                replOut = null,
                sourceDir = sourceDir,
                modules = app.moduleMap.keys.toImmSet()
        )

        if (sqlMgr.hasConnection) {
            SqlUtils.initDatabase(appCtx, sqlMgr, true, false)
        }

        return sqlMgr.transaction { sqlExec ->
            val exeCtx = Rt_ExecutionContext(appCtx, sqlExec)
            try {
                f.callTop(exeCtx, listOf())
                println("OK ${f.appLevelName}")
                TestResult_OK
            } catch (e: Throwable) {
                e.printStackTrace(System.out)
                println("FAILED ${f.appLevelName}")
                TestResult_Fail(e)
            }
        }
    }

    private fun processTestResults(resMap: Map<String, TestResult>): Boolean {
        println()
        println(SEPARATOR)
        println("TEST RESULTS:\n")

        if (resMap.isNotEmpty()) {
            for ((name, r) in resMap) {
                println("$name $r")
            }
            println()
        }

        val nTests = resMap.size
        val nOk = resMap.values.count { it is TestResult_OK }
        val nFailed = nTests - nOk

        println("TESTS:  $nTests")
        println("PASSED: $nOk")
        println("FAILED: $nFailed")

        val allOk = nFailed == 0
        println("\n***** ${if (allOk) "OK" else "FAILED"} *****")

        return allOk
    }

    fun getTestFunctions(module: R_Module): List<R_Function> {
        return module.functions.values
                .filter { it.simpleName == "test" || it.simpleName.startsWith("test_") }
                .filter { it.params().isEmpty() }
    }
}
