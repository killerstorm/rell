/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model

import net.postchain.rell.runtime.*
import net.postchain.rell.utils.CommonUtils

sealed class R_UpdateTarget {
    abstract fun entity(): R_DbAtEntity
    abstract fun extraEntities(): List<R_DbAtEntity>
    abstract fun where(): Db_Expr?

    abstract fun execute(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame)
}

class R_UpdateTarget_Simple(
        val entity: R_DbAtEntity,
        val extraEntities: List<R_DbAtEntity>,
        val cardinality: R_AtCardinality,
        val where: Db_Expr?
): R_UpdateTarget() {
    init {
        val intersect = extraEntities.filter { it.id == entity.id }
        check(intersect.isEmpty()) { "Extra entities contain main entity: ${entity.id}" }
    }

    override fun entity() = entity
    override fun extraEntities() = extraEntities
    override fun where() = where

    override fun execute(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame) {
        execute(stmt, frame, listOf(entity) + extraEntities, cardinality)
    }

    companion object {
        fun execute(
                stmt: R_BaseUpdateStatement,
                frame: Rt_CallFrame,
                entities: List<R_DbAtEntity>,
                cardinality: R_AtCardinality
        ) {
            val count = stmt.executeSqlCount(frame, entities)
            R_AtExpr.checkCount(cardinality, count, "records")
        }
    }
}

sealed class R_UpdateTarget_Expr(
        private val entity: R_DbAtEntity,
        private val where: Db_Expr,
        private val expr: R_Expr,
        private val lambda: R_LambdaBlock
): R_UpdateTarget() {
    final override fun entity() = entity
    final override fun extraEntities() = listOf<R_DbAtEntity>()
    final override fun where() = where

    protected abstract fun execute0(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame, value: Rt_Value)

    final override fun execute(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame) {
        val value = expr.evaluate(frame)
        execute0(stmt, frame, value)
    }

    fun executeStmt(frame: Rt_CallFrame, stmt: R_BaseUpdateStatement , value: Rt_Value) {
        lambda.execute(frame, value) {
            stmt.executeSql(frame, listOf(entity))
        }
    }
}

class R_UpdateTarget_Expr_One(entity: R_DbAtEntity, where: Db_Expr, expr: R_Expr, lambda: R_LambdaBlock)
: R_UpdateTarget_Expr(entity, where, expr, lambda) {
    override fun execute0(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame, value: Rt_Value) {
        if (value != Rt_NullValue) {
            executeStmt(frame, stmt, value)
        }
    }
}

class R_UpdateTarget_Expr_Many(
        entity: R_DbAtEntity,
        where: Db_Expr,
        expr: R_Expr,
        lambda: R_LambdaBlock,
        private val set: Boolean,
        private val listType: R_Type
): R_UpdateTarget_Expr(entity, where, expr, lambda) {
    override fun execute0(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame, value: Rt_Value) {
        val lst = if (set) {
            value.asSet().toMutableList()
        } else {
            value.asList().toSet().toMutableList()
        }

        if (lst.isEmpty()) {
            return
        }

        // Experimental maximum is 2^15
        val partSize = frame.defCtx.globalCtx.sqlUpdatePortionSize

        for (part in CommonUtils.split(lst, partSize)) {
            val partValue = Rt_ListValue(listType, part)
            executeStmt(frame, stmt, partValue)
        }
    }
}

class R_UpdateTarget_Object(private val entity: R_DbAtEntity): R_UpdateTarget() {
    override fun entity() = entity
    override fun extraEntities(): List<R_DbAtEntity> = listOf()
    override fun where() = null

    override fun execute(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame) {
        R_UpdateTarget_Simple.execute(stmt, frame, listOf(entity), R_AtCardinality.ONE)
    }
}

class R_UpdateStatementWhat(val attr: R_Attribute, val expr: Db_Expr, val op: Db_BinaryOp?)

sealed class R_BaseUpdateStatement(val target: R_UpdateTarget, val fromBlock: R_FrameBlock): R_Statement() {
    protected abstract fun buildSql(frame: Rt_CallFrame, ctx: SqlGenContext, returning: Boolean): ParameterizedSql

    fun executeSql(frame: Rt_CallFrame, entities: List<R_DbAtEntity>) {
        frame.block(fromBlock) {
            val ctx = SqlGenContext.create(frame, entities)
            val pSql = buildSql(frame, ctx, false)
            pSql.execute(frame.sqlExec)
        }
    }

    fun executeSqlCount(frame: Rt_CallFrame, entities: List<R_DbAtEntity>): Int {
        var count = 0
        frame.block(fromBlock) {
            val ctx = SqlGenContext.create(frame, entities)
            val pSql = buildSql(frame, ctx, true)
            pSql.executeQuery(frame.sqlExec) {
                ++count
            }
        }
        return count
    }

    final override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        frame.checkDbUpdateAllowed()
        target.execute(this, frame)
        return null
    }

    protected fun appendMainTable(b: SqlBuilder, sqlCtx: Rt_SqlContext, fromInfo: SqlFromInfo) {
        val entity = target.entity()
        val table = entity.rEntity.sqlMapping.table(sqlCtx)
        b.appendName(table)
        b.append(" ")
        b.append(fromInfo.entities.getValue(entity.id).alias.str)
    }

    protected fun appendExtraTables(builder: SqlBuilder, sqlCtx: Rt_SqlContext, fromInfo: SqlFromInfo, keyword: String) {
        val tables = mutableListOf<Pair<String, SqlTableAlias>>()

        val entity = target.entity()
        for (join in fromInfo.entities.getValue(entity.id).joins) {
            val table = join.alias.entity.sqlMapping.table(sqlCtx)
            tables.add(Pair(table, join.alias))
        }

        for (extraEntity in target.extraEntities()) {
            tables.add(Pair(extraEntity.rEntity.sqlMapping.table(sqlCtx), fromInfo.entities.getValue(extraEntity.id).alias))
            for (join in fromInfo.entities.getValue(extraEntity.id).joins) {
                tables.add(Pair(join.alias.entity.sqlMapping.table(sqlCtx), join.alias))
            }
        }

        if (tables.isEmpty()) {
            return
        }

        builder.append(" $keyword ")

        builder.append(tables, ", ") { (table, alias) ->
            builder.appendName(table)
            builder.append(" ")
            builder.append(alias.str)
        }
    }

    protected fun translateWhere(ctx: SqlGenContext, redWhere: RedDb_Expr?): ParameterizedSql? {
        return if (redWhere == null) null else {
            val b = SqlBuilder()
            redWhere.toSql(ctx, b)
            b.build()
        }
    }

    protected fun appendWhere(b: SqlBuilder, fromInfo: SqlFromInfo, explicitWhereSql: ParameterizedSql?) {
        val allJoins = fromInfo.entities.values.flatMap { it.joins }

        val whereB = SqlBuilder()
        appendWhereJoins(whereB, allJoins)

        if (explicitWhereSql != null && !explicitWhereSql.isEmpty()) {
            whereB.appendSep(" AND ")
            whereB.append(explicitWhereSql)
        }

        if (!whereB.isEmpty()) {
            b.append(" WHERE ")
            b.append(whereB)
        }
    }

    private fun appendWhereJoins(b: SqlBuilder, allJoins: List<SqlFromJoin>) {
        b.append(allJoins, " AND ") { join ->
            b.append("(")
            b.appendColumn(join.baseAlias, join.attr)
            b.append(" = ")
            b.appendColumn(join.alias, join.alias.entity.sqlMapping.rowidColumn())
            b.append(")")
        }
    }

    protected fun appendReturning(builder: SqlBuilder, fromInfo: SqlFromInfo) {
        val entity = target.entity()
        builder.append(" RETURNING ")
        builder.appendColumn(fromInfo.entities.getValue(entity.id).alias, entity.rEntity.sqlMapping.rowidColumn())
    }
}

class R_UpdateStatement(
        target: R_UpdateTarget,
        fromBlock: R_FrameBlock,
        private val what: List<R_UpdateStatementWhat>
): R_BaseUpdateStatement(target, fromBlock) {
    override fun buildSql(frame: Rt_CallFrame, ctx: SqlGenContext, returning: Boolean): ParameterizedSql {
        val redWhere = target.where()?.toRedExpr(frame)

        val redWhat = what.map {
            val redExpr = it.expr.toRedExpr(frame)
            RedDb_Utils.wrapDecimalExpr(it.expr.type, redExpr)
        }

        val whatSql = translateWhat(ctx, redWhat)
        val whereSql = translateWhere(ctx, redWhere)

        val fromInfo = ctx.getFromInfo(ctx.topFromEntities)
        return buildSql0(ctx.sqlCtx, returning, fromInfo, whatSql, whereSql)
    }

    private fun buildSql0(
            sqlCtx: Rt_SqlContext,
            returning: Boolean,
            fromInfo: SqlFromInfo,
            whatSql: ParameterizedSql,
            whereSql: ParameterizedSql?
    ): ParameterizedSql {
        val b = SqlBuilder()

        b.append("UPDATE ")
        appendMainTable(b, sqlCtx, fromInfo)

        b.append(" SET ")
        b.append(whatSql)

        appendExtraTables(b, sqlCtx, fromInfo, "FROM")
        appendWhere(b, fromInfo, whereSql)

        if (returning) {
            appendReturning(b, fromInfo)
        }

        return b.build()
    }

    private fun translateWhat(ctx: SqlGenContext, redWhat: List<RedDb_Expr>): ParameterizedSql {
        val b = SqlBuilder()
        b.append(redWhat.withIndex(), ", ") { (i, redExpr) ->
            val whatExpr = what[i]
            b.appendName(whatExpr.attr.sqlMapping)
            b.append(" = ")
            if (whatExpr.op != null) {
                b.appendName(whatExpr.attr.sqlMapping)
                b.append(" ")
                b.append(whatExpr.op.sql)
                b.append(" ")
            }
            redExpr.toSql(ctx, b)
        }
        return b.build()
    }
}

class R_DeleteStatement(target: R_UpdateTarget, fromBlock: R_FrameBlock): R_BaseUpdateStatement(target, fromBlock) {
    override fun buildSql(frame: Rt_CallFrame, ctx: SqlGenContext, returning: Boolean): ParameterizedSql {
        val redWhere = target.where()?.toRedExpr(frame)
        val whereSql = translateWhere(ctx, redWhere)
        val fromInfo = ctx.getFromInfo(ctx.topFromEntities)
        return buildSql0(ctx.sqlCtx, returning, fromInfo, whereSql)
    }

    private fun buildSql0(
            sqlCtx: Rt_SqlContext,
            returning: Boolean,
            fromInfo: SqlFromInfo,
            whereSql: ParameterizedSql?
    ): ParameterizedSql {
        val b = SqlBuilder()

        b.append("DELETE FROM ")
        appendMainTable(b, sqlCtx, fromInfo)
        appendExtraTables(b, sqlCtx, fromInfo, "USING")
        appendWhere(b, fromInfo, whereSql)

        if (returning) {
            appendReturning(b, fromInfo)
        }

        return b.build()
    }
}
