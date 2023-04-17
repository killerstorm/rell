/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.utils

import com.google.common.base.Throwables
import net.postchain.rell.lib.test.Rt_BlockRunnerConfig
import net.postchain.rell.lib.test.Rt_BlockRunnerStrategy
import net.postchain.rell.model.R_App
import net.postchain.rell.model.R_DefinitionName
import net.postchain.rell.model.R_FunctionDefinition
import net.postchain.rell.model.R_Module
import net.postchain.rell.runtime.*
import net.postchain.rell.runtime.utils.Rt_Utils
import net.postchain.rell.sql.SqlManager
import net.postchain.rell.sql.SqlUtils
import net.postchain.rell.utils.cli.RellCliEnv
import net.postchain.rell.utils.cli.Rt_CliEnvPrinter
import org.apache.commons.lang3.StringUtils
import java.util.regex.Pattern

private val PRINT_SEPARATOR = "-".repeat(72)

class TestResult(val error: Throwable?) {
    val isOk = error == null
    override fun toString() = if (isOk) "OK" else "FAILED"
}

class TestRunnerContext(
    val app: R_App,
    val cliEnv: RellCliEnv,
    val sqlCtx: Rt_SqlContext,
    val sqlMgr: SqlManager,
    private val globalCtx: Rt_GlobalContext,
    private val chainCtx: Rt_ChainContext,
    private val blockRunnerConfig: Rt_BlockRunnerConfig,
    private val blockRunnerStrategy: Rt_BlockRunnerStrategy,
    val printTestCases: Boolean = true,
    val stopOnError: Boolean = false,
    val onTestCaseStart: (TestCase) -> Unit = {},
    val onTestCaseFinished: (TestCaseResult) -> Unit = {},
) {
    val casePrinter: Rt_Printer = if (printTestCases) Rt_CliEnvPrinter(cliEnv) else Rt_NopPrinter

    fun createAppContext(): Rt_AppContext {
        return Rt_AppContext(
            globalCtx,
            chainCtx,
            app,
            repl = false,
            test = true,
            replOut = null,
            blockRunnerConfig = blockRunnerConfig,
            blockRunnerStrategy = blockRunnerStrategy,
        )
    }
}

class TestRunnerChain(val name: String, val iid: Long) {
    override fun toString() = "$name[$iid]"
}

class TestCase(chain: TestRunnerChain?, val fn: R_FunctionDefinition) {
    val name = let {
        val f = fn.appLevelName
        if (chain == null) f else "$chain:$f"
    }

    override fun toString(): String {
        return name
    }
}

class TestCaseResult(val case: TestCase, val res: TestResult) {
    override fun toString() = "$case:$res"
}

class TestRunnerResults {
    private val results = mutableListOf<TestCaseResult>()

    fun add(res: TestCaseResult) {
        results.add(res)
    }

    fun getResults() = results.toImmList()

    fun print(env: RellCliEnv): Boolean {
        val (okTests, failedTests) = results.partition { it.res.error == null }

        val printer = Rt_CliEnvPrinter(env)

        if (failedTests.isNotEmpty()) {
            printer.print("")
            printer.print(PRINT_SEPARATOR)
            printer.print("FAILED TESTS:")
            for (r in failedTests) {
                printer.print("")
                printer.print(r.case.name)
                printException(printer, r.res.error!!)
            }
        }

        printer.print("")
        printer.print(PRINT_SEPARATOR)
        printer.print("TEST RESULTS:")

        printResults(printer, okTests)
        printResults(printer, failedTests)

        val nTests = results.size
        val nOk = okTests.size
        val nFailed = failedTests.size

        printer.print("\nSUMMARY: $nFailed FAILED / $nOk PASSED / $nTests TOTAL\n")

        val allOk = nFailed == 0
        printer.print("\n***** ${if (allOk) "OK" else "FAILED"} *****")

        return allOk
    }

    private fun printResults(printer: Rt_Printer, list: List<TestCaseResult>) {
        if (list.isNotEmpty()) {
            printer.print("")
            for (r in list) {
                printer.print("${r.res} ${r.case}")
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

    fun runTests(testCtx: TestRunnerContext, cases: List<TestCase>): Boolean {
        val testRes = TestRunnerResults()
        runTests(testCtx, cases, testRes)
        return testRes.print(testCtx.cliEnv)
    }

    fun runTests(testCtx: TestRunnerContext, cases: List<TestCase>, testRes: TestRunnerResults) {
        for (case in cases) {
            testCtx.onTestCaseStart(case)

            val v = runTestCase(testCtx, case)
            val caseRes = TestCaseResult(case, v)

            testCtx.onTestCaseFinished(caseRes)
            testRes.add(caseRes)

            if (!v.isOk && testCtx.stopOnError) {
                break
            }
        }
    }

    private fun runTestCase(testCtx: TestRunnerContext, case: TestCase): TestResult {
        val caseName = case.name

        val printer = testCtx.casePrinter
        printer.print(PRINT_SEPARATOR)
        printer.print("TEST $caseName")

        val appCtx = testCtx.createAppContext()

        if (testCtx.sqlMgr.hasConnection) {
            SqlUtils.initDatabase(appCtx, testCtx.sqlCtx, testCtx.sqlMgr, dropTables = true, sqlInitLog = false)
        }

        return testCtx.sqlMgr.transaction { sqlExec ->
            val exeCtx = Rt_ExecutionContext(appCtx, null, testCtx.sqlCtx, sqlExec)
            try {
                case.fn.callTop(exeCtx, listOf())
                printer.print("OK $caseName")
                TestResult(null)
            } catch (e: Throwable) {
                printException(printer, e)
                printer.print("FAILED ${case.name}")
                TestResult(e)
            }
        }
    }
}

private fun printException(printer: Rt_Printer, e: Throwable) {
    when (e) {
        is Rt_Exception -> {
            val msg = Rt_Utils.appendStackTrace("Error: ${e.message}", e.info.stack)
            printer.print(msg)
        }
        else -> {
            val s = Throwables.getStackTraceAsString(e)
            printer.print(s)
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
        val ANY = make(listOf("*"))

        fun make(patterns: List<String>): TestMatcher {
            val patterns2 = patterns.map { globToPattern(it) }.toImmList()
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
