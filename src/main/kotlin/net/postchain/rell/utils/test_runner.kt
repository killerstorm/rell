/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.utils

import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_FunctionDefinition
import net.postchain.rell.model.R_Module
import net.postchain.rell.runtime.*
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.sql.SqlManager
import net.postchain.rell.sql.SqlUtils

private val PRINT_SEPARATOR = "-".repeat(72)

sealed class TestResult

object TestResult_OK: TestResult() {
    override fun toString() = "OK"
}

class TestResult_Fail(val error: Throwable): TestResult() {
    override fun toString() = "FAILED"
}

class TestRunnerContext(
        val sqlCtx: Rt_SqlContext,
        val sqlMgr: SqlManager,
        private val globalCtx: Rt_GlobalContext,
        private val chainCtx: Rt_ChainContext,
        private val blockRunnerStrategy: Rt_BlockRunnerStrategy,
        val app: R_App
) {
    fun createAppContext(): Rt_AppContext = Rt_AppContext(
            globalCtx,
            chainCtx,
            app,
            repl = false,
            test = true,
            replOut = null,
            blockRunnerStrategy = blockRunnerStrategy
    )
}

class TestRunnerChain(val name: String, val iid: Long) {
    override fun toString() = "$name[$iid]"
}

class TestRunnerCase(chain: TestRunnerChain?, val fn: R_FunctionDefinition) {
    val name = let {
        val f = fn.appLevelName
        if (chain == null) f else "$chain:$f"
    }

    override fun toString(): String {
        return name
    }
}

class TestRunnerResults {
    private val results = mutableListOf<Pair<TestRunnerCase, TestResult>>()

    fun add(case: TestRunnerCase, value: TestResult) {
        results.add(case to value)
    }

    fun getResults() = results.toImmList()

    fun print(): Boolean {
        println()
        println(PRINT_SEPARATOR)
        println("TEST RESULTS:\n")

        if (results.isNotEmpty()) {
            for ((name, r) in results) {
                println("$name $r")
            }
            println()
        }

        val nTests = results.size
        val nOk = results.count { it.second is TestResult_OK }
        val nFailed = nTests - nOk

        println("SUMMARY: $nFailed FAILED / $nOk PASSED / $nTests TOTAL\n")

        val allOk = nFailed == 0
        println("\n***** ${if (allOk) "OK" else "FAILED"} *****")

        return allOk
    }
}

object TestRunner {
    fun getTestFunctions(app: R_App): List<R_FunctionDefinition> {
        val modules = app.modules.filter { it.test }.sortedBy { it.name }
        val fns = modules.flatMap { getTestFunctions(it) }
        return fns
    }

    fun getTestFunctions(module: R_Module): List<R_FunctionDefinition> {
        return module.functions.values
                .filter { it.moduleLevelName == "test" || it.moduleLevelName.startsWith("test_") }
                .filter { it.params().isEmpty() }
    }

    fun runTests(testCtx: TestRunnerContext, cases: List<TestRunnerCase>): Boolean {
        val testRes = TestRunnerResults()
        runTests(testCtx, cases, testRes)
        return testRes.print()
    }

    fun runTests(testCtx: TestRunnerContext, cases: List<TestRunnerCase>, testRes: TestRunnerResults) {
        for (case in cases) {
            val v = runTestCase(testCtx, case)
            testRes.add(case, v)
        }
    }

    private fun runTestCase(testCtx: TestRunnerContext, case: TestRunnerCase): TestResult {
        val caseName = case.name

        println(PRINT_SEPARATOR)
        println("TEST $caseName")

        val appCtx = testCtx.createAppContext()

        if (testCtx.sqlMgr.hasConnection) {
            SqlUtils.initDatabase(appCtx, testCtx.sqlCtx, testCtx.sqlMgr, dropTables = true, sqlInitLog = false)
        }

        return testCtx.sqlMgr.transaction { sqlExec ->
            val exeCtx = Rt_ExecutionContext(appCtx, null, testCtx.sqlCtx, sqlExec)
            try {
                case.fn.callTop(exeCtx, listOf())
                println("OK $caseName")
                TestResult_OK
            } catch (e: Rt_StackTraceError) {
                val msg = Rt_Utils.appendStackTrace("Error: ${e.message}", e.stack)
                System.out.println(msg)
                println("FAILED $caseName")
                TestResult_Fail(e)
            } catch (e: Throwable) {
                e.printStackTrace(System.out)
                println("FAILED $caseName")
                TestResult_Fail(e)
            }
        }
    }
}
