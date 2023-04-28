/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.test

import net.postchain.gtv.Gtv
import net.postchain.rell.runtime.*

abstract class BaseRellTest(useSql: Boolean = true, gtv: Boolean = false): BaseTesterTest(useSql) {
    final override val tst = RellCodeTester(tstCtx, entityDefs(), objInserts(), gtv = gtv)

    val repl by lazy { tst.createRepl() }

    open fun entityDefs(): List<String> = listOf()
    open fun objInserts(): List<String> = listOf()

    fun chk(code: String, arg: Long, expected: String) = chkEx("= $code ;", arg, expected)
    fun chk(code: String, arg1: Long, arg2: Long, expected: String) = chkEx("= $code ;", arg1, arg2, expected)

    fun chkEx(code: String, arg: Long, expected: String) = tst.chkEx(code, listOf(arg), expected)
    fun chkEx(code: String, arg1: Long, arg2: Long, expected: String) = tst.chkEx(code, listOf(arg1, arg2), expected)
    fun chkEx(code: String, arg: Boolean, expected: String) = tst.chkEx(code, listOf(arg), expected)

    fun chkArgs(header: String, code: String, tester: (QueryTester) -> Unit) {
        val fullCode = "query q($header) $code"
        val checker = QueryTester(tst, fullCode)
        tester(checker)
    }

    fun chkFull(code: String, expected: String) =  tst.chkFull(code, expected)
    fun chkFull(code: String, args: List<Rt_Value>, expected: String) = tst.chkFull(code, args, expected)
    fun chkFull(code: String, name: String, args: List<Rt_Value>, expected: String) = tst.chkFull(code, name, args, expected)
    fun chkFullGtv(code: String, args: List<Gtv>, expected: String) = tst.chkFullGtv(code, args, expected)

    fun chkFull(code: String, arg: Long?, expected: String) = chkFull(code, listOf(rtVal(arg)), expected)
    fun chkFull(code: String, arg1: Long?, arg2: Long?, expected: String) = chkFull(code, listOf(rtVal(arg1), rtVal(arg2)), expected)
    fun chkFull(code: String, arg: String?, expected: String) = chkFull(code, listOf(rtVal(arg)), expected)
    fun chkFull(code: String, arg: Boolean?, expected: String) = chkFull(code, listOf(rtVal(arg)), expected)

    fun chkFn(code: String, expected: String) = tst.chkFn(code, expected)
    fun chkFnFull(code: String, expected: String) = tst.chkFnFull(code, expected)

    fun chkOp(code: String, expected: String = "OK") = tst.chkOp(code, expected)
    fun chkOpFull(code: String, expected: String = "OK", name: String = "o") = tst.chkOpEx(code, name, expected)
    fun chkOpFullGtv(code: String, args: List<Gtv>, expected: String = "OK") = tst.chkOpExGtv(code, args, expected)

    fun chkOpOut(code: String, vararg expected: String) {
        chkOp(code, "OK")
        chkOut(*expected)
    }

    fun chkWarn(vararg  expected: String) = tst.chkWarn(*expected)
    fun chkStack(vararg expected: String) = tst.chkStack(*expected)

    fun resetSqlCtr() = tst.resetSqlCtr()
    fun chkSql(expected: Int) = tst.chkSql(expected)

    fun chkTests(testModule: String, expected: String) = tst.chkTests(testModule, expected)

    private fun rtVal(v: Long?) = if (v == null) Rt_NullValue else Rt_IntValue(v)
    private fun rtVal(v: String?) = if (v == null) Rt_NullValue else Rt_TextValue(v)
    private fun rtVal(v: Boolean?) = if (v == null) Rt_NullValue else Rt_BooleanValue(v)
}
