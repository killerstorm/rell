package net.postchain.rell.model

import net.postchain.rell.compiler.C_Utils
import net.postchain.rell.runtime.*
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.toImmList

abstract class Rt_AtWhatCombiner(val dbValueCount: Int) {
    abstract fun combine(dbValues: List<Rt_Value>): Rt_Value

    companion object {
        fun combineValues(combiners: List<Rt_AtWhatCombiner>, row: List<Rt_Value>): List<Rt_Value> {
            val res: MutableList<Rt_Value> = ArrayList(combiners.size)

            var pos = 0
            for (combiner in combiners) {
                val count = combiner.dbValueCount
                val subRow = row.subList(pos, pos + count)
                val value = combiner.combine(subRow)
                res.add(value)
                pos += count
            }

            return res.toImmList()
        }
    }
}

sealed class Db_AtWhatValue {
    abstract fun rawTypes(): List<R_Type>
    abstract fun toRedExprs(frame: Rt_CallFrame): List<RedDb_Expr>
    abstract fun combiner(frame: Rt_CallFrame): Rt_AtWhatCombiner
}

class Db_AtWhatValue_RExpr(private val expr: R_Expr): Db_AtWhatValue() {
    override fun rawTypes() = listOf<R_Type>()
    override fun toRedExprs(frame: Rt_CallFrame) = listOf<RedDb_Expr>()

    override fun combiner(frame: Rt_CallFrame): Rt_AtWhatCombiner = Rt_AtWhatCombiner_RExpr(frame)

    private inner class Rt_AtWhatCombiner_RExpr(private val frame: Rt_CallFrame): Rt_AtWhatCombiner(0) {
        private var value: Rt_Value? = null

        override fun combine(dbValues: List<Rt_Value>): Rt_Value {
            checkEquals(dbValues.size, 0)
            var v = value
            if (v == null) {
                v = expr.evaluate(frame)
                value = v
            }
            return v
        }
    }
}

class Db_AtWhatValue_DbExpr(private val expr: Db_Expr, private val resultType: R_Type): Db_AtWhatValue() {
    override fun rawTypes() = listOf(resultType)

    override fun toRedExprs(frame: Rt_CallFrame): List<RedDb_Expr> {
        val redExpr = expr.toRedExpr(frame)
        return listOf(redExpr)
    }

    override fun combiner(frame: Rt_CallFrame): Rt_AtWhatCombiner = Rt_AtWhatCombiner_Simple

    private object Rt_AtWhatCombiner_Simple: Rt_AtWhatCombiner(1) {
        override fun combine(dbValues: List<Rt_Value>): Rt_Value {
            checkEquals(dbValues.size, 1)
            return dbValues[0]
        }
    }
}

abstract class Db_ComplexAtWhatEvaluator {
    abstract fun evaluate(frame: Rt_CallFrame, values: List<Rt_Value>): Rt_Value
}

class Db_AtWhatValue_Complex(
        subWhatValues: List<Db_AtWhatValue>,
        rExprs: List<R_Expr>,
        items: List<Pair<Boolean, Int>>,
        private val evaluator: Db_ComplexAtWhatEvaluator
): Db_AtWhatValue() {
    private val subWhatValues = subWhatValues.toImmList()
    private val rawTypes = this.subWhatValues.flatMap { it.rawTypes() }.toImmList()
    private val rExprs = rExprs.toImmList()
    private val items = items.toImmList()

    init {
        items.forEach { (db, i) ->
            require(i >= 0 && i < (if (db) subWhatValues else rExprs).size) { "$db $i" }
        }
    }

    override fun rawTypes() = rawTypes

    override fun toRedExprs(frame: Rt_CallFrame): List<RedDb_Expr> {
        return subWhatValues.flatMap { it.toRedExprs(frame) }
    }

    override fun combiner(frame: Rt_CallFrame): Rt_AtWhatCombiner {
        val subCombiners = subWhatValues.map { it.combiner(frame) }.toImmList()
        val dbValueCount = subCombiners.sumBy { it.dbValueCount }
        return Rt_AtWhatCombiner_Complex(frame, subCombiners, dbValueCount)
    }

    private inner class Rt_AtWhatCombiner_Complex(
            private val frame: Rt_CallFrame,
            private val subCombiners: List<Rt_AtWhatCombiner>,
            dbValueCount: Int
    ): Rt_AtWhatCombiner(dbValueCount) {
        private var rValues: List<Rt_Value>? = null

        override fun combine(dbValues: List<Rt_Value>): Rt_Value {
            val dbValues2 = combineValues(subCombiners, dbValues)

            var rVals = rValues
            if (rVals == null) {
                rVals = rExprs.map { it.evaluate(frame) }
                rValues = rVals
            }

            val allValues = items.map { (db, i) ->
                val selValues = if (db) dbValues2 else rVals
                selValues[i]
            }

            return evaluator.evaluate(frame, allValues)
        }
    }
}

class Db_AtWhatValue_ToStruct(private val rStruct: R_Struct, exprs: List<Db_Expr>): Db_AtWhatValue() {
    private val exprs = exprs.toImmList()

    override fun rawTypes() = exprs.map { it.type }

    override fun toRedExprs(frame: Rt_CallFrame): List<RedDb_Expr> {
        return exprs.map { it.toRedExpr(frame) }
    }

    override fun combiner(frame: Rt_CallFrame): Rt_AtWhatCombiner = Rt_AtWhatCombiner_ToStruct()

    private inner class Rt_AtWhatCombiner_ToStruct: Rt_AtWhatCombiner(exprs.size) {
        override fun combine(dbValues: List<Rt_Value>): Rt_Value {
            val attrs = rStruct.attributesList

            if (dbValues.size != attrs.size) {
                throw Rt_Error("to_struct:values_size:${attrs.size}:${dbValues.size}",
                        "Received wrong number of values: ${dbValues.size} instead of ${attrs.size}")
            }

            val attrValues = dbValues.toMutableList()
            return Rt_StructValue(rStruct.type, attrValues)
        }
    }
}

class Db_AtWhatField(val flags: R_AtWhatFieldFlags, val value: Db_AtWhatValue)

class RedDb_AtWhatField(val expr: RedDb_Expr, val flags: R_AtWhatFieldFlags)

class RedDb_AtExprBase(from: List<R_DbAtEntity>, private val where: RedDb_Expr?, what: List<RedDb_AtWhatField>) {
    private val from = from.toImmList()
    private val what = what.toImmList()

    fun buildSql(frame: Rt_CallFrame, extras: Rt_AtExprExtras): ParameterizedSql {
        val ctx = SqlGenContext.createTop(frame, from)
        val b = SqlBuilder()
        buildSql0(ctx, b, extras)
        return b.build()
    }

    fun buildNestedSql(ctx: SqlGenContext, b: SqlBuilder, extras: Rt_AtExprExtras) {
        val subCtx = ctx.createSub(from)
        buildSql0(subCtx, b, extras)
    }

    private fun buildSql0(ctx: SqlGenContext, b: SqlBuilder, extras: Rt_AtExprExtras) {
        val sqlParts = AtExprSqlParts(ctx)
        appendClause(b, "SELECT", sqlParts.whatSqls)
        appendClause(b, " FROM", sqlParts.fromSqls)
        appendClause(b, " WHERE", sqlParts.whereSql)
        appendClause(b, " GROUP BY", sqlParts.groupBySqls)
        appendClause(b, " ORDER BY", sqlParts.orderBySqls)
        appendClause(b, " LIMIT", extras.limit)
        appendClause(b, " OFFSET", extras.offset)
    }

    private fun appendClause(b: SqlBuilder, clause: String, sqls: List<ParameterizedSql>) {
        if (sqls.isNotEmpty()) {
            b.append("$clause ")
            b.append(sqls, ", ") {
                b.append(it)
            }
        }
    }

    private fun appendClause(b: SqlBuilder, clause: String, sql: ParameterizedSql?) {
        appendClause(b, clause, listOfNotNull(sql))
    }

    private fun appendClause(b: SqlBuilder, clause: String, value: Long?) {
        val sql = if (value == null) null else ParameterizedSql.generate { it.append(value) }
        appendClause(b, clause, sql)
    }

    private inner class AtExprSqlParts(ctx: SqlGenContext) {
        val whereSql: ParameterizedSql?
        val whatSqls: List<ParameterizedSql>
        val groupBySqls: List<ParameterizedSql>
        val orderBySqls: List<ParameterizedSql>
        val fromSqls: List<ParameterizedSql>

        init {
            whereSql = translateWhere(ctx, where)
            whatSqls = translateWhat(ctx, what)
            groupBySqls = translateGroupBy(ctx, what)
            orderBySqls = translateOrderBy(ctx, what)

            val fromInfo = ctx.getFromInfo()
            fromSqls = translateFrom(ctx, fromInfo)
        }

        private fun translateFrom(ctx: SqlGenContext, fromInfo: SqlFromInfo): List<ParameterizedSql> {
            return fromInfo.entities.values.map { translateFromItem(ctx.sqlCtx, it) }
        }

        private fun translateFromItem(sqlCtx: Rt_SqlContext, entity: SqlFromEntity): ParameterizedSql {
            val b = SqlBuilder()

            val table = entity.alias.entity.sqlMapping.table(sqlCtx)
            b.appendName(table)
            b.append(" ")
            b.append(entity.alias.str)

            for (join in entity.joins) {
                b.append(" INNER JOIN ")
                val joinMapping = join.alias.entity.sqlMapping
                b.appendName(joinMapping.table(sqlCtx))
                b.append(" ")
                b.append(join.alias.str)
                b.append(" ON ")
                b.appendColumn(join.baseAlias, join.attr)
                b.append(" = ")
                b.appendColumn(join.alias, joinMapping.rowidColumn())
            }

            return b.build()
        }

        private fun translateWhere(ctx: SqlGenContext, redWhere: RedDb_Expr?): ParameterizedSql? {
            return if (redWhere == null) null else translateExpr(ctx, redWhere)
        }

        private fun translateWhat(ctx: SqlGenContext, redWhat: List<RedDb_AtWhatField>): List<ParameterizedSql> {
            val res = redWhat.filter { !it.flags.omit }.map { translateExpr(ctx, it.expr) }
            return if (res.isNotEmpty()) res else listOf(ParameterizedSql("0", listOf()))
        }

        private fun translateGroupBy(ctx: SqlGenContext, redWhat: List<RedDb_AtWhatField>): List<ParameterizedSql> {
            return redWhat.filter { it.flags.group }.map { translateExpr(ctx, it.expr) }
        }

        private fun translateOrderBy(ctx: SqlGenContext, redWhat: List<RedDb_AtWhatField>): List<ParameterizedSql> {
            val elements = getOrderByElements(redWhat)
            return elements.map { element ->
                ParameterizedSql.generate { element.toSql(ctx, it) }
            }
        }

        private fun translateExpr(ctx: SqlGenContext, redExpr: RedDb_Expr): ParameterizedSql {
            return ParameterizedSql.generate { redExpr.toSql(ctx, it, false) }
        }

        private fun getOrderByElements(redWhat: List<RedDb_AtWhatField>): List<OrderByElement> {
            val elements = mutableListOf<OrderByElement>()

            for (field in redWhat) {
                if (field.flags.sort != null) {
                    elements.add(OrderByElement_Expr(field.expr, field.flags.sort))
                }
            }

            val redGroup = redWhat.filter { it.flags.group }
            if (redGroup.isNotEmpty() || redWhat.any { it.flags.aggregate }) {
                for (field in redGroup) {
                    if (field.flags.sort == null) {
                        elements.add(OrderByElement_Expr(field.expr, R_AtWhatSort.ASC))
                    }
                }
            } else {
                for (entity in from) {
                    elements.add(OrderByElement_Entity(entity))
                }
            }

            return elements.toImmList()
        }
    }

    private abstract class OrderByElement {
        abstract fun toSql(ctx: SqlGenContext, b: SqlBuilder)
    }

    private class OrderByElement_Expr(val redExpr: RedDb_Expr, val sort: R_AtWhatSort): OrderByElement() {
        override fun toSql(ctx: SqlGenContext, b: SqlBuilder) {
            redExpr.toSql(ctx, b, false)
            if (!sort.asc) {
                b.append(" DESC")
            }
        }
    }

    private class OrderByElement_Entity(val entity: R_DbAtEntity): OrderByElement() {
        override fun toSql(ctx: SqlGenContext, b: SqlBuilder) {
            val alias = ctx.getEntityAlias(entity)
            b.appendColumn(alias, entity.rEntity.sqlMapping.rowidColumn())
        }
    }
}

class Db_AtExprBase(
        from: List<R_DbAtEntity>,
        what: List<Db_AtWhatField>,
        private val where: Db_Expr?
) {
    private val from = from.toImmList()
    private val what = what.toImmList()

    private val selWhat = what.filter { !it.flags.omit }.toImmList()
    private val resultTypes = selWhat.flatMap { it.value.rawTypes() }.toImmList()

    init {
        R_DbAtEntity.checkList(from)
    }

    fun toRedBase(frame: Rt_CallFrame): RedDb_AtExprBase {
        val redWhere = makeFullWhere(frame)

        val redWhat = what.flatMap { whatField ->
            val redExprs = whatField.value.toRedExprs(frame)
            redExprs.map { RedDb_AtWhatField(it, whatField.flags) }
        }

        return RedDb_AtExprBase(from, redWhere, redWhat)
    }

    private fun makeFullWhere(frame: Rt_CallFrame): RedDb_Expr? {
        val exprs = mutableListOf<Db_Expr?>()
        exprs.add(where)

        for (atEntity in from) {
            val expr = atEntity.rEntity.sqlMapping.extraWhereExpr(atEntity)
            exprs.add(expr)
        }

        val validExprs = exprs.filterNotNull()
        val expr = if (validExprs.isEmpty()) {
            null
        } else {
            C_Utils.makeDbBinaryExprChain(R_BooleanType, R_BinaryOp_And, Db_BinaryOp_And, validExprs)
        }

        return expr?.toRedExpr(frame)
    }

    fun execute(frame: Rt_CallFrame, extras: Rt_AtExprExtras): List<List<Rt_Value>> {
        val redBase = toRedBase(frame)
        val rtSql = redBase.buildSql(frame, extras)
        val select = SqlSelect(rtSql, resultTypes)
        val combiners = selWhat.map { it.value.combiner(frame) }
        val records = select.execute(frame.sqlExec) { Rt_AtWhatCombiner.combineValues(combiners, it) }
        return records
    }
}

class Db_NestedAtExpr(
        type: R_Type,
        private val base: Db_AtExprBase,
        private val extras: R_AtExprExtras,
        private val block: R_FrameBlock
): Db_Expr(type) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        val redBase = frame.block(block) {
            base.toRedBase(frame)
        }

        val rtExtras = extras.evaluate(frame)
        return RedDb_NestedAtExpr(redBase, rtExtras)
    }

    private class RedDb_NestedAtExpr(
            private val redBase: RedDb_AtExprBase,
            private val rtExtras: Rt_AtExprExtras
    ): RedDb_Expr() {
        override fun toSql0(ctx: SqlGenContext, bld: SqlBuilder) {
            redBase.buildNestedSql(ctx, bld, rtExtras)
        }
    }
}
