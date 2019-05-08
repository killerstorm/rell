package net.postchain.rell.test

import net.postchain.rell.runtime.*

abstract class BaseRellTest(useSql: Boolean = true, gtx: Boolean = false): BaseResourcefulTest() {
    open fun classDefs(): List<String> = listOf()
    open fun objInserts(): List<String> = listOf()

    val tstCtx = resource(RellTestContext(useSql))
    val tst = RellCodeTester(tstCtx, classDefs(), objInserts(), gtx = gtx)

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

    fun chkEx(code: String, arg: Boolean, expected: String) {
        chkFull("query q(a: boolean) $code", listOf(Rt_BooleanValue(arg)), expected)
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

    fun chkQueryEx(code: String, arg: Long?, expected: String) = chkQueryEx(code, listOf(rtVal(arg)), expected)
    fun chkQueryEx(code: String, arg1: Long?, arg2: Long?, expected: String) = chkQueryEx(code, listOf(rtVal(arg1), rtVal(arg2)), expected)
    fun chkQueryEx(code: String, arg: String?, expected: String) = chkQueryEx(code, listOf(rtVal(arg)), expected)
    fun chkQueryEx(code: String, arg: Boolean?, expected: String) = chkQueryEx(code, listOf(rtVal(arg)), expected)

    fun chkFn(code: String, expected: String) {
        val modCode = "function f() $code"
        chkFnEx(modCode, expected)
    }

    fun chkFnEx(code: String, expected: String) = tst.chkFnEx(code, expected)

    fun chkCompile(code: String, expected: String) = tst.chkCompile(code, expected)

    fun chkData(vararg expected: String) = tst.chkData(*expected)
    fun chkDataNew(vararg expected: String) = tst.chkDataNew(*expected)

    fun chkOp(code: String, expected: String = "OK") = tst.chkOp(code, expected)
    fun chkOpFull(code: String, expected: String = "OK") = tst.chkOpEx(code, expected)

    fun chkStdout(vararg expected: String) = tst.chkStdout(*expected)
    fun chkLog(vararg expected: String) = tst.chkLog(*expected)
    fun chkWarn(vararg  expected: String) = tst.chkWarn(*expected)

    private fun rtVal(v: Long?) = if (v == null) Rt_NullValue else Rt_IntValue(v)
    private fun rtVal(v: String?) = if (v == null) Rt_NullValue else Rt_TextValue(v)
    private fun rtVal(v: Boolean?) = if (v == null) Rt_NullValue else Rt_BooleanValue(v)
}
