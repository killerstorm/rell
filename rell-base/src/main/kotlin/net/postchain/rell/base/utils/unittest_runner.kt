/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.utils

import com.google.common.base.Throwables
import net.postchain.rell.base.model.R_App
import net.postchain.rell.base.model.R_DefinitionName
import net.postchain.rell.base.model.R_FunctionDefinition
import net.postchain.rell.base.model.R_Module
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.runtime.utils.Rt_Utils
import net.postchain.rell.base.sql.SqlInitProjExt
import net.postchain.rell.base.sql.SqlManager
import net.postchain.rell.base.sql.SqlUtils
import org.apache.commons.lang3.StringUtils
import java.time.Duration
import java.util.regex.Pattern

private val PRINT_SEPARATOR = "-".repeat(72)

class UnitTestResult(val duration: Duration, val error: Throwable?) {
    val isOk = error == null

    private fun statusStr() = if (isOk) "OK" else "FAILED"
    override fun toString() = statusStr()

    fun caseResultToString(case: UnitTestCase): String {
        val durationStr = durationToString(duration)
        val res = "${statusStr()} ${case.name} ($durationStr)"
        return res
    }

    companion object {
        fun durationToString(duration: Duration): String {
            val durationMs = duration.toMillis()
            val durationSecStr = if (durationMs == 0L) "0" else String.format("%.3f", durationMs / 1000.0)
            return "${durationSecStr}s"
        }
    }
}

class UnitTestRunnerContext(
    val app: R_App,
    val printer: Rt_Printer,
    val sqlCtx: Rt_SqlContext,
    val sqlMgr: SqlManager,
    val sqlInitProjExt: SqlInitProjExt,
    private val globalCtx: Rt_GlobalContext,
    private val chainCtx: Rt_ChainContext,
    private val blockRunner: Rt_UnitTestBlockRunner,
    val printTestCases: Boolean = true,
    val stopOnError: Boolean = false,
    val onTestCaseStart: (UnitTestCase) -> Unit = {},
    val onTestCaseFinished: (UnitTestCaseResult) -> Unit = {},
) {
    val casePrinter: Rt_Printer = if (printTestCases) printer else Rt_NopPrinter

    fun createAppContext(): Rt_AppContext {
        return Rt_AppContext(
            globalCtx,
            chainCtx,
            app,
            repl = false,
            test = true,
            replOut = null,
            blockRunner = blockRunner,
        )
    }
}

class UnitTestRunnerChain(val name: String, val iid: Long) {
    override fun toString() = "$name[$iid]"
}

class UnitTestCase(chain: UnitTestRunnerChain?, val fn: R_FunctionDefinition) {
    val name = let {
        val f = fn.appLevelName
        if (chain == null) f else "$chain:$f"
    }

    override fun toString(): String {
        return name
    }
}

class UnitTestCaseResult(val case: UnitTestCase, val res: UnitTestResult) {
    override fun toString() = "$case:$res"
}

class UnitTestRunnerResults {
    private val results = mutableListOf<UnitTestCaseResult>()

    fun add(res: UnitTestCaseResult) {
        results.add(res)
    }

    fun getResults() = results.toImmList()

    fun print(printer: Rt_Printer): Boolean {
        val (okTests, failedTests) = results.partition { it.res.error == null }

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

        val sumDuration = results.fold(Duration.ZERO) { a, b -> a.plus(b.res.duration) }
        val sumDurationStr = UnitTestResult.durationToString(sumDuration)

        printer.print("\nSUMMARY: $nFailed FAILED / $nOk PASSED / $nTests TOTAL ($sumDurationStr)\n")

        val allOk = nFailed == 0
        printer.print("\n***** ${if (allOk) "OK" else "FAILED"} *****")

        return allOk
    }

    private fun printResults(printer: Rt_Printer, list: List<UnitTestCaseResult>) {
        if (list.isNotEmpty()) {
            printer.print("")
            for (r in list) {
                val str = r.res.caseResultToString(r.case)
                printer.print(str)
            }
        }
    }
}

object UnitTestRunner {
    fun getTestFunctions(app: R_App, matcher: UnitTestMatcher): List<R_FunctionDefinition> {
        val modules = app.modules
            .filter { it.test && it.selected }
            .sortedBy { it.name }

        val fns = modules.flatMap { getTestFunctions(it, matcher) }
        return fns
    }

    fun getTestFunctions(module: R_Module, matcher: UnitTestMatcher): List<R_FunctionDefinition> {
        return module.functions.values
            .filter { it.moduleLevelName == "test" || it.moduleLevelName.startsWith("test_") }
            .filter { it.params().isEmpty() }
            .filter { matcher.matchFunction(it.defName) }
    }

    fun runTests(testCtx: UnitTestRunnerContext, cases: List<UnitTestCase>): Boolean {
        val testRes = UnitTestRunnerResults()
        runTests(testCtx, cases, testRes)
        return testRes.print(testCtx.printer)
    }

    fun runTests(testCtx: UnitTestRunnerContext, cases: List<UnitTestCase>, testRes: UnitTestRunnerResults) {
        for (case in cases) {
            testCtx.onTestCaseStart(case)

            val v = runTestCase(testCtx, case)
            val caseRes = UnitTestCaseResult(case, v)

            testCtx.onTestCaseFinished(caseRes)
            testRes.add(caseRes)

            if (!v.isOk && testCtx.stopOnError) {
                break
            }
        }
    }

    private fun runTestCase(testCtx: UnitTestRunnerContext, case: UnitTestCase): UnitTestResult {
        val caseName = case.name
        val startTs = System.nanoTime()

        val printer = testCtx.casePrinter
        printer.print(PRINT_SEPARATOR)
        printer.print("TEST $caseName")

        val appCtx = testCtx.createAppContext()

        if (testCtx.sqlMgr.hasConnection) {
            SqlUtils.initDatabase(
                appCtx,
                testCtx.sqlCtx,
                testCtx.sqlMgr,
                adapter = testCtx.sqlInitProjExt,
                dropTables = true,
                sqlInitLog = false,
            )
        }

        return testCtx.sqlMgr.transaction { sqlExec ->
            val exeCtx = Rt_ExecutionContext(appCtx, Rt_NullOpContext, testCtx.sqlCtx, sqlExec)
            try {
                case.fn.callTop(exeCtx, listOf())
                processResult(printer, case, startTs, null)
            } catch (e: Throwable) {
                printException(printer, e)
                processResult(printer, case, startTs, e)
            }
        }
    }

    private fun processResult(printer: Rt_Printer, case: UnitTestCase, startTs: Long, e: Throwable?): UnitTestResult {
        val endTs = System.nanoTime()
        val duration = Duration.ofNanos(endTs - startTs)
        val res = UnitTestResult(duration, e)
        printer.print(res.caseResultToString(case))
        return res
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

class UnitTestMatcher private constructor(private val patterns: List<Pattern>) {
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

        fun make(patterns: List<String>): UnitTestMatcher {
            val patterns2 = patterns.map { globToPattern(it) }.toImmList()
            return UnitTestMatcher(patterns2)
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
