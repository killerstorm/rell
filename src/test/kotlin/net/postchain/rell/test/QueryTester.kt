package net.postchain.rell.test

import net.postchain.rell.runtime.*

class QueryTester(private val tst: RellCodeTester, private val code: String) {
    fun chk(arg: Any?, expected: String): QueryTester {
        chk0(expected, arg)
        return this
    }

    fun chk(arg1: Any?, arg2: Any?, expected: String): QueryTester {
        chk0(expected, arg1, arg2)
        return this
    }

    fun out(vararg expected: String): QueryTester {
        tst.chkOut(*expected)
        return this
    }

    private fun chk0(expected: String, vararg args: Any?) {
        val rtArgs = args.map { argToRt(it) }.toList()
        tst.chkFull(code, rtArgs, expected)
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
