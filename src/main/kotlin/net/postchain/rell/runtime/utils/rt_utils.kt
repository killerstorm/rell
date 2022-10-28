/*
 * Copyright (C) 2021 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.runtime.utils

import mu.KLogger
import net.postchain.common.BlockchainRid
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory
import net.postchain.rell.compiler.base.utils.C_LateGetter
import net.postchain.rell.model.R_CallFrame
import net.postchain.rell.model.R_FilePos
import net.postchain.rell.model.R_FunctionBase
import net.postchain.rell.model.R_StackPos
import net.postchain.rell.model.expr.R_Expr
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.sql.SqlManager
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

fun Boolean.toGtv(): Gtv = GtvFactory.gtv(this)
fun Int.toGtv(): Gtv = GtvFactory.gtv(this.toLong())
fun Long.toGtv(): Gtv = GtvFactory.gtv(this)
fun String.toGtv(): Gtv = GtvFactory.gtv(this)
fun BlockchainRid.toGtv(): Gtv = GtvFactory.gtv(this.data)
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
    override val hasConnection = sqlMgr.hasConnection

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
        val msg = errors.mapNotNull { it.message }.joinToString("\n")
        throw Rt_Error(code, msg)
    }

    fun warningCodes() = warningCodes.toList()
}

object Rt_Utils {
    fun errNotSupported(msg: String): Rt_Error {
        return Rt_Error("not_supported", msg)
    }

    fun <T> wrapErr(errCode: String, code: () -> T): T {
        return wrapErr({ errCode }, code)
    }

    fun <T> wrapErr(errCodeFn: () -> String, code: () -> T): T {
        try {
            val res = code()
            return res
        } catch (e: Rt_BaseError) {
            throw e
        } catch (e: Throwable) {
            val errCode = errCodeFn()
            throw Rt_Error(errCode, e.message ?: "")
        }
    }

    fun appendStackTrace(msg: String, stack: List<R_StackPos>): String {
        return if (stack.isEmpty()) msg else (msg + "\n" + stack.joinToString("\n") { "\tat $it" })
    }

    fun check(b: Boolean, msgProvider: () -> Pair<String, String>) {
        if (!b) {
            val (code, msg) = msgProvider()
            throw Rt_Error(code, msg)
        }
    }

    fun <T> checkNotNull(value: T?, msgProvider: () -> Pair<String, String>): T {
        if (value == null) {
            val (code, msg) = msgProvider()
            throw Rt_Error(code, msg)
        }
        return value
    }

    fun <T> checkEquals(actual: T, expected: T) {
        check(expected == actual) {
            val code = "check_equals:$expected:$actual"
            val msg = "expected <$expected> actual <$actual>"
            code to msg
        }
    }

    fun evaluateInNewFrame(
            defCtx: Rt_DefinitionContext,
            frame: Rt_CallFrame?,
            expr: R_Expr,
            filePos: R_FilePos?,
            rFrameGetter: C_LateGetter<R_CallFrame>
    ): Rt_Value {
        val caller = if (filePos == null || frame == null) null else R_FunctionBase.createFrameCaller(frame, filePos)
        val rSubFrame = rFrameGetter.get()
        val subFrame = R_FunctionBase.createCallFrame(defCtx, caller, rSubFrame)
        return expr.evaluate(subFrame)
    }
}
