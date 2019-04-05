package net.postchain.rell.runtime

import net.postchain.rell.sql.SqlExecutor
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

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

class Rt_SqlExecutor(private val sqlExec: SqlExecutor, private val logErrors: Boolean): SqlExecutor() {
    override fun transaction(code: () -> Unit) {
        wrapErr("(transaction)") {
            sqlExec.transaction(code)
        }
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
}
