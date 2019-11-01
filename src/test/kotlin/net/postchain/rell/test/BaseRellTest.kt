package net.postchain.rell.test

import net.postchain.rell.runtime.*

abstract class BaseRellTest(useSql: Boolean = true, gtv: Boolean = false): BaseTesterTest(useSql) {
    open fun entityDefs(): List<String> = listOf()
    open fun objInserts(): List<String> = listOf()

    final override val tst = RellCodeTester(tstCtx, entityDefs(), objInserts(), gtv = gtv)

    fun chk(code: String, arg: Long, expected: String) = chkEx("= $code ;", arg, expected)
    fun chk(code: String, arg1: Long, arg2: Long, expected: String) = chkEx("= $code ;", arg1, arg2, expected)

    final override fun chkEx(code: String, expected: String) = chkFull("query q() $code", listOf(), expected)

    fun chkEx(code: String, arg: Long, expected: String) {
        chkFull("query q(a: integer) $code", listOf(Rt_IntValue(arg)), expected)
    }

    fun chkEx(code: String, arg1: Long, arg2: Long, expected: String) {
        chkFull("query q(a: integer, b: integer) $code", listOf(Rt_IntValue(arg1), Rt_IntValue(arg2)), expected)
    }

    fun chkEx(code: String, arg: Boolean, expected: String) {
        chkFull("query q(a: boolean) $code", listOf(Rt_BooleanValue(arg)), expected)
    }

    fun chkArgs(header: String, code: String, tester: (QueryChecker) -> Unit) {
        val fullCode = "query q($header) $code"
        val checker = QueryChecker(fullCode)
        tester(checker)
    }

    fun chkFull(code: String, args: List<Rt_Value>, expected: String) {
        tst.chkQueryEx(code, "q", args, expected)
    }

    fun chkQueryEx(code: String, expected: String) {
        tst.chkQueryEx(code, "q", listOf(), expected)
    }

    fun chkQueryEx(code: String, args: List<Rt_Value>, expected: String, name: String = "q") {
        tst.chkQueryEx(code, name, args, expected)
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

    fun chkData(vararg expected: String) = tst.chkData(*expected)
    fun chkDataNew(vararg expected: String) = tst.chkDataNew(*expected)
    fun chkDataRaw(vararg expected: String) = tst.chkDataRaw(*expected)

    fun chkOp(code: String, expected: String = "OK") = tst.chkOp(code, expected)
    fun chkOpFull(code: String, expected: String = "OK", name: String = "o") = tst.chkOpEx(code, name, expected)

    fun chkWarn(vararg  expected: String) = tst.chkWarn(*expected)

    private fun rtVal(v: Long?) = if (v == null) Rt_NullValue else Rt_IntValue(v)
    private fun rtVal(v: String?) = if (v == null) Rt_NullValue else Rt_TextValue(v)
    private fun rtVal(v: Boolean?) = if (v == null) Rt_NullValue else Rt_BooleanValue(v)

    inner class QueryChecker(private val code: String) {
        fun chk(arg: Any?, expected: String): QueryChecker {
            chk0(expected, arg)
            return this
        }

        fun chk(arg1: Any?, arg2: Any?, expected: String): QueryChecker {
            chk0(expected, arg1, arg2)
            return this
        }

        fun stdout(vararg expected: String): QueryChecker {
            chkStdout(*expected)
            return this
        }

        private fun chk0(expected: String, vararg args: Any?) {
            val rtArgs = args.map { argToRt(it) }.toList()
            chkFull(code, rtArgs, expected)
        }

        private fun argToRt(arg: Any?): Rt_Value {
            return when (arg) {
                is Boolean -> Rt_BooleanValue(arg)
                is Int -> Rt_IntValue(arg.toLong())
                is Long -> Rt_IntValue(arg)
                is String -> Rt_TextValue(arg)
                null -> Rt_NullValue
                else -> throw IllegalArgumentException(arg.javaClass.canonicalName)
            }
        }
    }
}
