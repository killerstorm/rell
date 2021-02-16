package net.postchain.rell.model

import net.postchain.rell.compiler.C_Utils
import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_SqlContext
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.utils.toImmList

abstract class R_DbAtWhatCombiner {
    abstract fun combine(values: List<Rt_Value>): Rt_Value
}

sealed class Db_AtWhatValue {
    abstract fun exprs(): List<Db_Expr>
    abstract fun rawCount(): Int
    abstract fun rawTypes(): List<R_Type>
    abstract fun toRedExprs(frame: Rt_CallFrame): List<RedDb_Expr>
    abstract fun rawToResult(rawValues: List<Rt_Value>): Rt_Value
}

class Db_AtWhatValue_Simple(private val expr: Db_Expr, private val resultType: R_Type): Db_AtWhatValue() {
    override fun exprs() = listOf(expr)
    override fun rawCount() = 1
    override fun rawTypes() = listOf(resultType)

    override fun toRedExprs(frame: Rt_CallFrame): List<RedDb_Expr> {
        val redExpr = expr.toRedExpr(frame)
        return listOf(redExpr)
    }

    override fun rawToResult(rawValues: List<Rt_Value>): Rt_Value {
        check(rawValues.size == 1) { "rawValues.size = ${rawValues.size}" }
        return rawValues[0]
    }
}

class Db_AtWhatValue_Complex(exprs: List<Db_Expr>, private val combiner: R_DbAtWhatCombiner): Db_AtWhatValue() {
    private val exprs = exprs.toImmList()

    override fun exprs() = exprs
    override fun rawCount() = exprs.size
    override fun rawTypes() = exprs.map { it.type }

    override fun toRedExprs(frame: Rt_CallFrame): List<RedDb_Expr> {
        return exprs.map { it.toRedExpr(frame) }
    }

    override fun rawToResult(rawValues: List<Rt_Value>): Rt_Value {
        check(rawValues.size == exprs.size) { "rawValues.size = ${rawValues.size} (expected {${exprs.size}})" }
        return combiner.combine(rawValues)
    }
}

class Db_AtWhatField(val flags: R_AtWhatFieldFlags, val value: Db_AtWhatValue)

class Db_AtExprBase(
        private val from: List<R_DbAtEntity>,
        private val what: List<Db_AtWhatField>,
        private val where: Db_Expr?
) {
    private val selWhat = what.filter { !it.flags.omit }.toImmList()
    private val resultTypes = selWhat.flatMap { it.value.rawTypes() }.toImmList()

    init {
        R_DbAtEntity.checkList(from)
    }

    fun directSubExprs(): List<Db_Expr> = listOfNotNull(where) + what.flatMap { it.value.exprs() }

    fun execute(frame: Rt_CallFrame, limit: Long?, offset: Long?): List<List<Rt_Value>> {
        val rtSql = buildSql(frame, limit, offset)
        val select = SqlSelect(rtSql, resultTypes)
        val records = select.execute(frame.sqlExec) { combineRow(it) }
        return records
    }

    private fun combineRow(row: List<Rt_Value>): List<Rt_Value> {
        val res: MutableList<Rt_Value> = ArrayList(selWhat.size)

        var pos = 0
        for (field in selWhat) {
            val v = field.value
            val count = v.rawCount()
            val subRow = row.subList(pos, pos + count)
            val value = v.rawToResult(subRow)
            res.add(value)
            pos += count
        }

        return res.toImmList()
    }

    private fun buildSql(frame: Rt_CallFrame, limit: Long?, offset: Long?): ParameterizedSql {
        val ctx = SqlGenContext.create(frame, from)
        val b = SqlBuilder()
        buildSql0(ctx, b, frame)

        appendClause(b, "LIMIT", limit)
        appendClause(b, "OFFSET", offset)
        return b.build()
    }

    fun buildNestedSql(ctx: SqlGenContext, b: SqlBuilder, frame: Rt_CallFrame) {
        ctx.addFromEntities(from)
        buildSql0(ctx, b, frame)
    }

    private fun buildSql0(ctx: SqlGenContext, b: SqlBuilder, frame: Rt_CallFrame) {
        val sqlParts = AtExprSqlParts(frame, ctx)
        appendClause(b, "SELECT", sqlParts.whatSqls)
        appendClause(b, "FROM", sqlParts.fromSqls)
        appendClause(b, "WHERE", sqlParts.whereSql)
        appendClause(b, "GROUP BY", sqlParts.groupBySqls)
        appendClause(b, "ORDER BY", sqlParts.orderBySqls)
    }

    private fun appendClause(b: SqlBuilder, clause: String, sqls: List<ParameterizedSql>) {
        if (sqls.isNotEmpty()) {
            b.appendSep(" ")
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

    private inner class AtExprSqlParts(frame: Rt_CallFrame, ctx: SqlGenContext) {
        val whereSql: ParameterizedSql?
        val whatSqls: List<ParameterizedSql>
        val groupBySqls: List<ParameterizedSql>
        val orderBySqls: List<ParameterizedSql>
        val fromSqls: List<ParameterizedSql>

        init {
            val redWhere = makeFullWhere(frame)

            val redWhat = what.flatMap { whatField ->
                val redExprs = whatField.value.toRedExprs(frame)
                redExprs.map { RedWhatField(it, whatField.flags) }
            }

            whereSql = translateWhere(ctx, redWhere)
            whatSqls = translateWhat(ctx, redWhat)
            groupBySqls = translateGroupBy(ctx, redWhat)
            orderBySqls = translateOrderBy(ctx, redWhat)

            val fromInfo = ctx.getFromInfo(from)
            fromSqls = translateFrom(ctx, fromInfo)
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

        private fun translateWhat(ctx: SqlGenContext, redWhat: List<RedWhatField>): List<ParameterizedSql> {
            return redWhat.filter { !it.flags.omit }.map { translateExpr(ctx, it.expr) }
        }

        private fun translateGroupBy(ctx: SqlGenContext, redWhat: List<RedWhatField>): List<ParameterizedSql> {
            return redWhat.filter { it.flags.group }.map { translateExpr(ctx, it.expr) }
        }

        private fun translateOrderBy(ctx: SqlGenContext, redWhat: List<RedWhatField>): List<ParameterizedSql> {
            val elements = getOrderByElements(redWhat)
            return elements.map { element ->
                ParameterizedSql.generate { element.toSql(ctx, it) }
            }
        }

        private fun translateExpr(ctx: SqlGenContext, redExpr: RedDb_Expr): ParameterizedSql {
            return ParameterizedSql.generate { redExpr.toSql(ctx, it) }
        }

        private fun getOrderByElements(redWhat: List<RedWhatField>): List<OrderByElement> {
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

    private class RedWhatField(val expr: RedDb_Expr, val flags: R_AtWhatFieldFlags)

    private abstract class OrderByElement {
        abstract fun toSql(ctx: SqlGenContext, b: SqlBuilder)
    }

    private class OrderByElement_Expr(val redExpr: RedDb_Expr, val sort: R_AtWhatSort): OrderByElement() {
        override fun toSql(ctx: SqlGenContext, b: SqlBuilder) {
            redExpr.toSql(ctx, b)
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

class Db_NestedAtExpr(
        type: R_Type,
        private val base: Db_AtExprBase,
        private val block: R_FrameBlock
): Db_Expr(type, base.directSubExprs()) {
    override fun toRedExpr(frame: Rt_CallFrame): RedDb_Expr {
        return RedDb_NestedAtExpr(frame)
    }

    private inner class RedDb_NestedAtExpr(private val frame: Rt_CallFrame): RedDb_Expr() {
        override fun toSql(ctx: SqlGenContext, bld: SqlBuilder) {
            frame.block(block) {
                base.buildNestedSql(ctx, bld, frame)
            }
        }
    }
}
