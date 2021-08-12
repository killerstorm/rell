package net.postchain.rell.model

import com.google.common.collect.Iterables
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.toImmList
import kotlin.math.min

sealed class R_ColAtFieldSummarization {
    abstract fun newSummarizer(): R_ColAtValueSummarizer
}

object R_ColAtFieldSummarization_None: R_ColAtFieldSummarization() {
    override fun newSummarizer() = throw Rt_Error("at_summarization_none", "Aggregation failed")
}

class R_ColAtFieldSummarization_Group: R_ColAtFieldSummarization() {
    override fun newSummarizer(): R_ColAtValueSummarizer = R_ColAtValueSummarizer_Group()
}

sealed class R_ColAtFieldSummarization_Aggregate: R_ColAtFieldSummarization() {
    override fun newSummarizer(): R_ColAtValueSummarizer = R_ColAtValueSummarizer_Aggregate(this)

    abstract fun summarize(value1: Rt_Value, value2: Rt_Value): Rt_Value
    abstract fun result(value: Rt_Value?): Rt_Value
}

class R_ColAtFieldSummarization_Aggregate_Sum(
        private val op: R_BinaryOp,
        private val zeroValue: Rt_Value
): R_ColAtFieldSummarization_Aggregate() {
    override fun summarize(value1: Rt_Value, value2: Rt_Value) = op.evaluate(value1, value2)
    override fun result(value: Rt_Value?) = value ?: zeroValue
}

class R_ColAtFieldSummarization_Aggregate_MinMax(
        private val rCmpOp: R_CmpOp,
        private val comparator: Comparator<Rt_Value>
): R_ColAtFieldSummarization_Aggregate() {
    override fun summarize(value1: Rt_Value, value2: Rt_Value): Rt_Value {
        val cmp = comparator.compare(value1, value2)
        return if (rCmpOp.check(cmp)) value1 else value2
    }

    override fun result(value: Rt_Value?) = value ?: Rt_NullValue
}

class R_ColAtWhatField(val expr: R_Expr, val flags: R_AtWhatFieldFlags, val summarization: R_ColAtFieldSummarization)

class R_ColAtWhat(
        fields: List<R_ColAtWhatField>,
        selectedFields: List<Int>,
        groupFields: List<Int>,
        sorting: List<IndexedValue<Comparator<Rt_Value>>>,
        val rowDecoder: R_AtExprRowDecoder
) {
    val fields = fields.toImmList()
    val selectedFields = selectedFields.toImmList()
    val groupFields = groupFields.toImmList()
    val sorting = sorting.toImmList()

    init {
        selectedFields.forEach { check(it in fields.indices) }
        groupFields.forEach { check(it in fields.indices) }
        sorting.forEach { check(it.index in fields.indices) }
    }
}

sealed class R_ColAtSummarization {
    abstract fun newSummarizer(): R_ColAtSummarizer
}

class R_ColAtSummarization_None(private val fieldCount: Int): R_ColAtSummarization() {
    override fun newSummarizer(): R_ColAtSummarizer = R_ColAtSummarizer_None(fieldCount)
}

class R_ColAtSummarization_Group(private val what: R_ColAtWhat): R_ColAtSummarization() {
    override fun newSummarizer(): R_ColAtSummarizer = R_ColAtSummarizer_Group(what)
}

class R_ColAtSummarization_All(private val what: R_ColAtWhat): R_ColAtSummarization() {
    override fun newSummarizer(): R_ColAtSummarizer = R_ColAtSummarizer_All(what)
}

sealed class R_ColAtSummarizer {
    abstract fun newLimiter(limits: Rt_AtExprExtras, sorting: Boolean): R_ColAtLimiter
    abstract fun addRecord(values: List<Rt_Value>)
    abstract fun getResult(): List<List<Rt_Value>>

    protected fun newLimiter0(limits: Rt_AtExprExtras, early: Boolean): R_ColAtLimiter {
        if (limits.limit == null && limits.offset == null) return R_ColAtLimiter_None
        val limit = limits.limit ?: Long.MAX_VALUE
        val offset = limits.offset ?: 0L
        return if (early) R_ColAtLimiter_Early(limit, offset) else R_ColAtLimiter_Late(limit, offset)
    }
}

private class R_ColAtSummarizer_None(private val fieldCount: Int): R_ColAtSummarizer() {
    val res = mutableListOf<List<Rt_Value>>()

    override fun newLimiter(limits: Rt_AtExprExtras, sorting: Boolean) = newLimiter0(limits, !sorting)

    override fun addRecord(values: List<Rt_Value>) {
        checkEquals(values.size, fieldCount)
        res.add(values)
    }

    override fun getResult(): List<List<Rt_Value>> {
        return res
    }
}

private class R_ColAtSummarizer_Group(what: R_ColAtWhat): R_ColAtSummarizer() {
    private val fields = what.fields
    private val groupFields = what.groupFields
    private val map = mutableMapOf<List<Rt_Value>, R_ColAtRowAggregator>()

    override fun newLimiter(limits: Rt_AtExprExtras, sorting: Boolean) = newLimiter0(limits, false)

    override fun addRecord(values: List<Rt_Value>) {
        checkEquals(values.size, fields.size)
        val key = groupFields.map { values[it] }
        val aggregator = map.computeIfAbsent(key) { R_ColAtRowAggregator(fields) }
        aggregator.update(values)
    }

    override fun getResult(): List<List<Rt_Value>> {
        return map.values.map { it.getResult() }
    }
}

private class R_ColAtSummarizer_All(what: R_ColAtWhat): R_ColAtSummarizer() {
    private val fields = what.fields
    private val aggregator = R_ColAtRowAggregator(what.fields)

    override fun newLimiter(limits: Rt_AtExprExtras, sorting: Boolean) = newLimiter0(limits, false)

    override fun addRecord(values: List<Rt_Value>) {
        checkEquals(values.size, fields.size)
        aggregator.update(values)
    }

    override fun getResult(): List<List<Rt_Value>> {
        val res = aggregator.getResult()
        return listOf(res)
    }
}

private class R_ColAtRowAggregator(fields: List<R_ColAtWhatField>) {
    private val valueAggregators = fields.map { it.summarization.newSummarizer() }

    fun update(values: List<Rt_Value>) {
        for (i in valueAggregators.indices) {
            val value = values[i]
            valueAggregators[i].update(value)
        }
    }

    fun getResult(): List<Rt_Value> {
        return valueAggregators.map { it.getResult() }
    }
}

sealed class R_ColAtValueSummarizer {
    abstract fun update(value: Rt_Value)
    abstract fun getResult(): Rt_Value
}

private class R_ColAtValueSummarizer_Group: R_ColAtValueSummarizer() {
    private var lastValue: Rt_Value? = null

    override fun update(value: Rt_Value) {
        val v = lastValue
        if (v == null) {
            lastValue = value
        } else {
            checkEquals(value, v)
        }
    }

    override fun getResult(): Rt_Value {
        return lastValue!!
    }
}

private class R_ColAtValueSummarizer_Aggregate(
        private val summarization: R_ColAtFieldSummarization_Aggregate
): R_ColAtValueSummarizer() {
    private var lastValue: Rt_Value? = null

    override fun update(value: Rt_Value) {
        val v0 = lastValue
        val v = if (v0 == null) value else summarization.summarize(v0, value)
        lastValue = v
    }

    override fun getResult(): Rt_Value {
        return summarization.result(lastValue)
    }
}

sealed class R_ColAtLimiter {
    abstract fun processLimit(): Boolean
    abstract fun processOffset(): Boolean
    abstract fun getResult(list: List<List<Rt_Value>>): List<List<Rt_Value>>
}

object R_ColAtLimiter_None: R_ColAtLimiter() {
    override fun processLimit() = true
    override fun processOffset() = true
    override fun getResult(list: List<List<Rt_Value>>) = list
}

class R_ColAtLimiter_Early(private val limit: Long, private val offset: Long): R_ColAtLimiter() {
    private var pos = 0L
    private var size = 0L

    override fun processLimit() = size < limit

    override fun processOffset(): Boolean {
        val res = pos >= offset
        if (res) size += 1
        pos += 1
        return res
    }

    override fun getResult(list: List<List<Rt_Value>>): List<List<Rt_Value>> {
        val actSize = list.size.toLong()
        checkEquals(actSize, size)
        return list
    }
}

class R_ColAtLimiter_Late(private val limit: Long, private val offset: Long): R_ColAtLimiter() {
    override fun processLimit() = true
    override fun processOffset() = true

    override fun getResult(list: List<List<Rt_Value>>): List<List<Rt_Value>> {
        val size = list.size.toLong()
        val start = min(offset, size)
        val end = min(start + min(limit, Integer.MAX_VALUE.toLong()), size)
        return if (start == 0L && end == size) list else list.subList(start.toInt(), end.toInt())
    }
}

sealed class R_ColAtFrom(private val expr: R_Expr) {
    protected abstract fun evaluate0(value: Rt_Value): Iterable<Rt_Value>

    fun evaluate(frame: Rt_CallFrame): Iterable<Rt_Value> {
        val value = expr.evaluate(frame)
        return evaluate0(value)
    }
}

class R_ColAtFrom_Collection(expr: R_Expr): R_ColAtFrom(expr) {
    override fun evaluate0(value: Rt_Value): Iterable<Rt_Value> {
        return value.asCollection()
    }
}

class R_ColAtFrom_Map(expr: R_Expr, private val tupleType: R_TupleType): R_ColAtFrom(expr) {
    override fun evaluate0(value: Rt_Value): Iterable<Rt_Value> {
        val map = value.asMap()
        return Iterables.transform(map.entries) {
            val entry = it!!
            Rt_TupleValue(tupleType, listOf(entry.key, entry.value))
        }
    }
}

class R_ColAtExpr(
        type: R_Type,
        val block: R_FrameBlock,
        val param: R_VarParam,
        val from: R_ColAtFrom,
        val what: R_ColAtWhat,
        val where: R_Expr,
        val summarization: R_ColAtSummarization,
        cardinality: R_AtCardinality,
        extras: R_AtExprExtras
): R_AtExpr(type, cardinality, extras) {
    private val rowComparator = RowComparator.create(what.sorting)
    private val hasSorting = rowComparator != null

    override fun evaluate0(frame: Rt_CallFrame): Rt_Value {
        val resList = evalList(frame)
        checkCount(cardinality, resList.size, "values")

        val res = evalResult(resList)
        return res
    }

    private fun evalList(frame: Rt_CallFrame): MutableList<Rt_Value> {
        val rtExtras = extras.evaluate(frame)
        if (rtExtras.limit != null && rtExtras.limit <= 0L) return mutableListOf()

        val iterable = from.evaluate(frame)
        val summarizer = summarization.newSummarizer()

        val limiter = summarizer.newLimiter(rtExtras, hasSorting)

        frame.block(block) {
            for (item in iterable) {
                if (!limiter.processLimit()) {
                    break
                }

                frame.set(param.ptr, param.type, item, true)

                val whereValue = where.evaluate(frame)
                if (!whereValue.asBoolean()) {
                    continue
                }

                if (!limiter.processOffset()) {
                    continue
                }

                val values = what.fields.map { it.expr.evaluate(frame) }
                summarizer.addRecord(values)
            }
        }

        var rows = summarizer.getResult()
        if (rowComparator != null) rows = rows.sortedWith(rowComparator)
        rows = limiter.getResult(rows)

        val resList: MutableList<Rt_Value> = ArrayList(rows.size)
        for (rowValues in rows) {
            val selValues = what.selectedFields.map { rowValues[it] }
            val value = what.rowDecoder.decode(selValues)
            resList.add(value)
        }

        return resList
    }

    private class RowComparator(private val sorting: List<IndexedValue<Comparator<Rt_Value>>>): Comparator<List<Rt_Value>> {
        override fun compare(p0: List<Rt_Value>?, p1: List<Rt_Value>?): Int {
            p0!!
            p1!!
            for ((i, c) in sorting) {
                val v1 = p0[i]
                val v2 = p1[i]
                val d = c.compare(v1, v2)
                if (d != 0) return d
            }
            return 0
        }

        companion object {
            fun create(sorting: List<IndexedValue<Comparator<Rt_Value>>>): Comparator<List<Rt_Value>>? {
                return if (sorting.isEmpty()) null else RowComparator(sorting)
            }
        }
    }
}
