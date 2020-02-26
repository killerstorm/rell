/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.runtime

import mu.KLogger
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory
import net.postchain.rell.compiler.C_Constants
import net.postchain.rell.model.R_StackPos
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlManager
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

fun String.toGtv(): Gtv = GtvFactory.gtv(this)
fun Boolean.toGtv(): Gtv = GtvFactory.gtv(this)
fun List<Gtv>.toGtv(): Gtv = GtvFactory.gtv(this)
fun Map<String, Gtv>.toGtv(): Gtv = GtvFactory.gtv(this)

class RellInterpreterCrashException(message: String): RuntimeException(message)

class Rt_Comparator<T>(private val getter: (Rt_Value) -> T, private val comparator: Comparator<T>): Comparator<Rt_Value> {
    override fun compare(o1: Rt_Value?, o2: Rt_Value?): Int {
        if (o1 == null || o1 == Rt_NullValue) {
            return if (o2 == null || o2 == Rt_NullValue) 0 else -1
        } else if (o2 == null || o2 == Rt_NullValue) {
            return 1
        } else {
            val v1 = getter(o1)
            val v2 = getter(o2)
            val c = comparator.compare(v1, v2)
            return c
        }
    }

    companion object {
        fun <T: Comparable<T>> create(getter: (Rt_Value) -> T): Comparator<Rt_Value> {
            return Rt_Comparator(getter, Comparator { x, y -> x.compareTo(y) })
        }
    }
}

class Rt_ListComparator(private val elemComparator: Comparator<Rt_Value>): Comparator<Rt_Value> {
    override fun compare(o1: Rt_Value?, o2: Rt_Value?): Int {
        val l1 = o1!!.asList()
        val l2 = o2!!.asList()
        val n1 = l1.size
        val n2 = l2.size
        for (i in 0 until Math.min(n1, n2)) {
            val c = elemComparator.compare(l1[i], l2[i])
            if (c != 0) {
                return c
            }
        }
        return n1.compareTo(n2)
    }
}

class Rt_TupleComparator(private val elemComparators: List<Comparator<Rt_Value>>): Comparator<Rt_Value> {
    override fun compare(o1: Rt_Value?, o2: Rt_Value?): Int {
        val t1 = o1!!.asTuple()
        val t2 = o2!!.asTuple()
        for (i in 0 until elemComparators.size) {
            val c = elemComparators[i].compare(t1[i], t2[i])
            if (c != 0) {
                return c
            }
        }
        return 0
    }
}

class Rt_SqlManager(private val sqlMgr: SqlManager, private val logErrors: Boolean): SqlManager() {
    override fun <T> execute0(tx: Boolean, code: (SqlExecutor) -> T): T {
        val res = sqlMgr.execute(tx) { sqlExec ->
            val sqlExec2 = Rt_SqlExecutor(sqlExec, logErrors)
            code(sqlExec2)
        }
        return res
    }
}

class Rt_SqlExecutor(private val sqlExec: SqlExecutor, private val logErrors: Boolean): SqlExecutor() {
    override fun <T> connection(code: (Connection) -> T): T {
        val res = wrapErr("(connection)") {
            sqlExec.connection(code)
        }
        return res
    }

    override fun execute(sql: String) {
        wrapErr(sql) {
            sqlExec.execute(sql)
        }
    }

    override fun execute(sql: String, preparator: (PreparedStatement) -> Unit) {
        wrapErr(sql) {
            sqlExec.execute(sql, preparator)
        }
    }

    override fun executeQuery(sql: String, preparator: (PreparedStatement) -> Unit, consumer: (ResultSet) -> Unit) {
        wrapErr(sql) {
            sqlExec.executeQuery(sql, preparator, consumer)
        }
    }

    private fun <T> wrapErr(sql: String, code: () -> T): T {
        try {
            val res = code()
            return res
        } catch (e: SQLException) {
            if (logErrors) {
                System.err.println("SQL: " + sql)
                e.printStackTrace()
            }
            throw Rt_Error("sqlerr:${e.errorCode}", "SQL Error: ${e.message}")
        }
    }
}

class Rt_Messages(private val logger: KLogger) {
    private val warningCodes = mutableListOf<String>()
    private val errors = mutableListOf<Rt_Error>()

    fun warning(code: String, msg: String) {
        warningCodes.add(code)
        logger.warn(msg)
    }

    fun error(code: String, msg: String) {
        errors.add(Rt_Error(code, msg))
    }

    fun errorIfNotEmpty(list: Collection<String>, code: String, msg: String) {
        if (!list.isEmpty()) {
            val codeList = list.joinToString(",")
            val msgList = list.joinToString(", ")
            error("$code:$codeList", "$msg: $msgList")
        }
    }

    fun checkErrors() {
        if (errors.isEmpty()) {
            return
        }

        if (errors.size == 1) {
            throw errors[0]
        }

        val code = errors.map { it.code }.joinToString(",")
        val msg = errors.map { it.message }.filterNotNull().joinToString("\n")
        throw Rt_Error(code, msg)
    }

    fun warningCodes() = warningCodes.toList()
}

object Rt_Utils {
    fun errNotSupported(msg: String): Rt_Error {
        return Rt_Error("not_supported", msg)
    }

    fun <T> wrapErr(errCode: String, code: () -> T): T {
        try {
            val res = code()
            return res
        } catch (e: Rt_BaseError) {
            throw e
        } catch (e: Throwable) {
            throw Rt_Error(errCode, e.message ?: "")
        }
    }

    fun appendStackTrace(msg: String, stack: List<R_StackPos>): String {
        return if (stack.isEmpty()) msg else (msg + "\n" + stack.joinToString("\n") { "\tat $it" })
    }
}

object Rt_DecimalUtils {
    private val UPPER_LIMIT = BigDecimal.TEN.pow(C_Constants.DECIMAL_INT_DIGITS)
    private val LOWER_LIMIT = -UPPER_LIMIT

    private val POSITIVE_MIN = BigDecimal.ONE.divide(BigDecimal.TEN.pow(C_Constants.DECIMAL_FRAC_DIGITS + 1))
    private val NEGATIVE_MIN = POSITIVE_MIN.negate()

    fun parse(s: String): BigDecimal {
        var t = if (s.startsWith(".")) {
            "0$s"
        } else if (s.startsWith("+.")) {
            "0${s.substring(1)}"
        } else if (s.startsWith("-.")) {
            "-0${s.substring(1)}"
        } else {
            s
        }

        t = removeTrailingZeros(t)
        return BigDecimal(t)
    }

    fun scale(v: BigDecimal): BigDecimal? {
        var t = v

        if (t >= NEGATIVE_MIN && t <= POSITIVE_MIN) {
            return BigDecimal.ZERO
        } else if (t <= LOWER_LIMIT || t >= UPPER_LIMIT) {
            return null
        }

        val scale = t.scale()
        if (scale > C_Constants.DECIMAL_FRAC_DIGITS) {
            t = v.setScale(C_Constants.DECIMAL_FRAC_DIGITS, RoundingMode.HALF_UP)
            if (t <= LOWER_LIMIT || t >= UPPER_LIMIT) {
                return null
            }
        }

        return t
    }

    fun add(a: BigDecimal, b: BigDecimal): BigDecimal {
        return a.add(b)
    }

    fun subtract(a: BigDecimal, b: BigDecimal): BigDecimal {
        return a.subtract(b)
    }

    fun multiply(a: BigDecimal, b: BigDecimal): BigDecimal {
        return a.multiply(b)
    }

    fun divide(a: BigDecimal, b: BigDecimal): BigDecimal {
        val r = a.divide(b, C_Constants.DECIMAL_FRAC_DIGITS, RoundingMode.HALF_UP)
        return r
    }

    fun remainder(a: BigDecimal, b: BigDecimal): BigDecimal {
        return a.remainder(b)
    }

    fun power(a: BigDecimal, b: Int): BigDecimal {
        TODO() // Need to handle rounding and precision carefully.
    }

    private fun mathContext(a: BigDecimal, b: BigDecimal): MathContext {
        val p = a.precision() + b.precision() + 2
        return MathContext(p, RoundingMode.HALF_UP)
    }

    fun toString(v: BigDecimal): String {
        val s = v.toPlainString()
        val r = removeTrailingZeros(s)
        return r
    }

    fun toSciString(v: BigDecimal): String {
        return v.toString()
    }

    fun removeTrailingZeros(s: String): String {
        // Verify that the string is a valid number and find the fractional part.
        val (fracStart, fracEnd) = parseString(s)

        var i = fracEnd
        while (i > fracStart && s[i - 1] == '0') --i
        if (i > fracStart && s[i - 1] == '.') --i

        return if (i == fracEnd) {
            s
        } else if (fracEnd == s.length) {
            s.substring(0, i)
        } else {
            s.substring(0, i) + s.substring(fracEnd)
        }
    }

    private fun parseString(s: String): Pair<Int, Int> {
        val n = s.length

        var fracStart = n
        var fracEnd = n
        var i = 0

        if (i < n && (s[i] == '-' || s[i] == '+')) ++i
        verifyDigit(s, i++)
        while (i < n && isDigit(s, i)) ++i

        if (i < n && s[i] == '.') {
            fracStart = i
            ++i
            verifyDigit(s, i++)
            while (i < n && isDigit(s, i)) ++i
        }

        if (i < n && (s[i] == 'E' || s[i] == 'e')) {
            if (fracStart == n) fracStart = i
            fracEnd = i
            ++i
            if (i < n && (s[i] == '+' || s[i] == '-')) ++i
            verifyDigit(s, i++)
            while (i < n && isDigit(s, i)) ++i
        }

        if (i != n) {
            throw NumberFormatException()
        }

        return Pair(fracStart, fracEnd)
    }

    private fun verifyDigit(s: String, i: Int) {
        if (i >= s.length || !isDigit(s, i)) {
            throw NumberFormatException()
        }
    }

    private fun isDigit(s: String, i: Int): Boolean {
        val c = s[i]
        return c >= '0' && c <= '9'
    }
}
