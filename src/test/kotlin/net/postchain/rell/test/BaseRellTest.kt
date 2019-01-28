package net.postchain.rell.test

import net.postchain.rell.runtime.Rt_IntValue
import net.postchain.rell.runtime.Rt_Value
import org.junit.After

abstract class BaseRellTest(useSql: Boolean = true, gtx: Boolean = false) {
    open fun classDefs(): List<String> = listOf()
    open fun objInserts(): List<String> = listOf()

    val tst = RellCodeTester(useSql, classDefs(), objInserts(), gtx = gtx)

    @After fun after() = tst.destroy()

    fun chk(code: String, expected: String) = chkEx("= $code ;", expected)
    fun chk(code: String, arg: Long, expected: String) = chkEx("= $code ;", arg, expected)
    fun chk(code: String, arg1: Long, arg2: Long, expected: String) = chkEx("= $code ;", arg1, arg2, expected)

    fun chkEx(code: String, expected: String) = chkFull("query q() $code", listOf(), expected)

    fun chkEx(code: String, arg: Long, expected: String) {
        chkFull("query q(a: integer) $code", listOf(Rt_IntValue(arg)), expected)
    }

    fun chkEx(code: String, arg1: Long, arg2: Long, expected: String) {
        chkFull("query q(a: integer, b: integer) $code", listOf(Rt_IntValue(arg1), Rt_IntValue(arg2)), expected)
    }

    fun chkFull(code: String, args: List<Rt_Value>, expected: String) {
        tst.chkQueryEx(code, args, expected)
    }

    fun chkQueryEx(code: String, expected: String) {
        tst.chkQueryEx(code, listOf(), expected)
    }

    fun chkQueryEx(code: String, args: List<Rt_Value>, expected: String) {
        tst.chkQueryEx(code, args, expected)
    }

    fun chkFn(code: String, expected: String) {
        val modCode = "function f() $code"
        tst.chkFnEx(modCode, expected)
    }

    fun chkCompile(code: String, expected: String) = tst.chkCompile(code, expected)

    fun chkData(vararg expected: String) = tst.chkData(*expected)
    fun chkDataNew(vararg expected: String) = tst.chkDataNew(*expected)

    fun execOp(code: String) = tst.execOp(code)

    fun chkOp(code: String, expected: String) = tst.chkOp(code, expected)
    fun chkOpFull(code: String, expected: String) = tst.chkOpEx(code, expected)
}
