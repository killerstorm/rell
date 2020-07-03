package net.postchain.rell.utils

import net.postchain.rell.model.R_Function
import net.postchain.rell.model.R_Module
import net.postchain.rell.runtime.Rt_ExecutionContext

private sealed class TestResult

private object TestResult_OK: TestResult() {
    override fun toString() = "OK"
}

private class TestResult_Fail(val error: Throwable): TestResult() {
    override fun toString() = "FAILED"
}

object TestRunner {
    private val SEPARATOR = "-".repeat(72)

    fun runTests(exeCtx: Rt_ExecutionContext, fns: List<R_Function>): Boolean {
        val res = runTestCases(exeCtx, fns)
        return processTestResults(res)
    }

    private fun runTestCases(exeCtx: Rt_ExecutionContext, fns: List<R_Function>): Map<String, TestResult> {
        val res = mutableMapOf<String, TestResult>()

        for (f in fns) {
            println(SEPARATOR)
            println("TEST ${f.appLevelName}")

            val v = try {
                f.callTop(exeCtx, listOf())
                println("${f.appLevelName} OK")
                TestResult_OK
            } catch (e: Throwable) {
                e.printStackTrace(System.out)
                println("${f.appLevelName} FAILED")
                TestResult_Fail(e)
            }

            res[f.appLevelName] = v
        }

        return res.toImmMap()
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
