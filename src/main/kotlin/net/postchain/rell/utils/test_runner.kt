/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.utils

import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_DefinitionName
import net.postchain.rell.model.R_FunctionDefinition
import net.postchain.rell.model.R_Module
import net.postchain.rell.runtime.*
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.sql.SqlManager
import net.postchain.rell.sql.SqlUtils
import org.apache.commons.lang3.StringUtils
import java.util.regex.Pattern

private val PRINT_SEPARATOR = "-".repeat(72)

class TestResult(val error: Throwable?) {
    override fun toString() = if (error == null) "OK" else "FAILED"
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

class TestCaseResult(val case: TestRunnerCase, val res: TestResult)

class TestRunnerResults {
    private val results = mutableListOf<TestCaseResult>()

    fun add(case: TestRunnerCase, value: TestResult) {
        results.add(TestCaseResult(case, value))
    }

    fun getResults() = results.toImmList()

    fun print(): Boolean {
        val (okTests, failedTests) = results.partition { it.res.error == null }

        if (failedTests.isNotEmpty()) {
            println()
            println(PRINT_SEPARATOR)
            println("FAILED TESTS:")
            for (r in failedTests) {
                println()
                println(r.case.name)
                printException(r.res.error!!)
            }
        }

        println()
        println(PRINT_SEPARATOR)
        println("TEST RESULTS:")

        printResults(okTests)
        printResults(failedTests)

        val nTests = results.size
        val nOk = okTests.size
        val nFailed = failedTests.size

        println("\nSUMMARY: $nFailed FAILED / $nOk PASSED / $nTests TOTAL\n")

        val allOk = nFailed == 0
        println("\n***** ${if (allOk) "OK" else "FAILED"} *****")

        return allOk
    }

    private fun printResults(list: List<TestCaseResult>) {
        if (list.isNotEmpty()) {
            println()
            for (r in list) {
                println("${r.res} ${r.case}")
            }
        }
    }
}

object TestRunner {
    fun getTestFunctions(app: R_App, matcher: TestMatcher): List<R_FunctionDefinition> {
        val modules = app.modules
            .filter { it.test && it.selected }
            .sortedBy { it.name }

        val fns = modules.flatMap { getTestFunctions(it, matcher) }
        return fns
    }

    fun getTestFunctions(module: R_Module, matcher: TestMatcher): List<R_FunctionDefinition> {
        return module.functions.values
            .filter { it.moduleLevelName == "test" || it.moduleLevelName.startsWith("test_") }
            .filter { it.params().isEmpty() }
            .filter { matcher.matchFunction(it.defName) }
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
                TestResult(null)
            } catch (e: Throwable) {
                printException(e)
                println("FAILED ${case.name}")
                TestResult(e)
            }
        }
    }
}

private fun printException(e: Throwable) {
    when (e) {
        is Rt_Exception -> {
            val msg = Rt_Utils.appendStackTrace("Error: ${e.message}", e.info.stack)
            println(msg)
        }
        else -> {
            e.printStackTrace(System.out)
        }
    }
}

class TestMatcher private constructor(private val patterns: List<Pattern>) {
    fun matchFunction(defName: R_DefinitionName): Boolean {
        if (match(defName.simpleName) || match(defName.qualifiedName) || match(defName.module)) {
            return true
        }

        var appLevelName = defName.appLevelName
        if (defName.module.isEmpty() && defName.appLevelName == defName.qualifiedName) {
            appLevelName = ":$appLevelName"
        }

        return match(appLevelName)
    }

    private fun match(s: String): Boolean {
        return patterns.any { it.matcher(s).matches() }
    }

    companion object {
        val ANY = make("*")

        fun make(patterns: String): TestMatcher {
            val list = patterns.split(",")
            val patterns2 = list.map { globToPattern(it) }.toImmList()
            return TestMatcher(patterns2)
        }

        fun globToPattern(s: String): Pattern {
            var pat = s
            val b = StringBuilder()

            while (true) {
                val i = StringUtils.indexOfAny(pat, "*?")
                if (i >= 0) {
                    if (i > 0) b.append(Pattern.quote(pat.substring(0, i)))
                    b.append(".")
                    if (pat[i] == '*') b.append("*")
                    pat = pat.substring(i + 1)
                } else {
                    b.append(Pattern.quote(pat))
                    break
                }
            }

            return Pattern.compile(b.toString())
        }
    }
}
