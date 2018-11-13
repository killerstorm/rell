package net.postchain.rell

import net.postchain.rell.runtime.RtIntValue
import net.postchain.rell.runtime.RtValue
import org.junit.After

abstract class BaseRellTest(useSql: Boolean = true) {
    val tst = RellSqlTester(useSql = useSql)

    @After fun after() = tst.destroy()

    fun chk(code: String, expected: String) = chkEx("= $code ;", expected)
    fun chk(code: String, arg: Long, expected: String) = chkEx("= $code ;", arg, expected)
    fun chk(code: String, arg1: Long, arg2: Long, expected: String) = chkEx("= $code ;", arg1, arg2, expected)

    fun chkEx(code: String, expected: String) = chkFull("query q() $code", listOf(), expected)

    fun chkEx(code: String, arg: Long, expected: String) {
        chkFull("query q(a: integer) $code", listOf(RtIntValue(arg)), expected)
    }

    fun chkEx(code: String, arg1: Long, arg2: Long, expected: String) {
        chkFull("query q(a: integer, b: integer) $code", listOf(RtIntValue(arg1), RtIntValue(arg2)), expected)
    }

    fun chkFull(code: String, args: List<RtValue>, expected: String) {
        tst.chkQueryEx(code, args, expected)
    }

    fun chkQueryEx(code: String, expected: String) {
        tst.chkQueryEx(code, listOf(), expected)
    }

    fun chkQueryEx(code: String, args: List<RtValue>, expected: String) {
        tst.chkQueryEx(code, args, expected)
    }

    fun chkFn(code: String, expected: String) {
        val modCode = "function f() $code"
        tst.chkFnEx(modCode, expected)
    }

    fun chkData(vararg expected: String) = tst.chkData(*expected)

    fun execOp(code: String) = tst.execOp(code)
}
