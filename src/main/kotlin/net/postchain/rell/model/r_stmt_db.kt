/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model

import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_ListValue
import net.postchain.rell.runtime.Rt_NullValue
import net.postchain.rell.runtime.Rt_SqlContext
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
        check(entity.index == 0)
        extraEntities.withIndex().forEach { check(it.index + 1 == it.value.index) }
    }

    override fun entity() = entity
    override fun extraEntities() = extraEntities
    override fun where() = where

    override fun execute(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame) {
        val ctx = SqlGenContext.create(frame, listOf(entity) + extraEntities, listOf())
        execute(stmt, frame, ctx, cardinality)
    }

    companion object {
        fun execute(
                stmt: R_BaseUpdateStatement,
                frame: Rt_CallFrame,
                ctx: SqlGenContext,
                cardinality: R_AtCardinality
        ) {
            val pSql = stmt.buildSql(frame, ctx, true)

            var count = 0
            pSql.executeQuery(frame) {
                ++count
            }

            R_AtExpr.checkCount(cardinality, count, "records")
        }
    }
}

sealed class R_UpdateTarget_Expr(val entity: R_DbAtEntity, val where: Db_Expr, val expr: R_Expr): R_UpdateTarget() {
    init {
        check(entity.index == 0)
    }

    final override fun entity() = entity
    final override fun extraEntities() = listOf<R_DbAtEntity>()
    final override fun where() = where

    protected fun execute0(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame, ctx: SqlGenContext) {
        val pSql = stmt.buildSql(frame, ctx, false)
        pSql.execute(frame)
    }
}

class R_UpdateTarget_Expr_One(entity: R_DbAtEntity, where: Db_Expr, expr: R_Expr): R_UpdateTarget_Expr(entity, where, expr) {
    override fun execute(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame) {
        val value = expr.evaluate(frame)
        if (value == Rt_NullValue) {
            return
        }

        val ctx = SqlGenContext.create(frame, listOf(entity), listOf(value))
        execute0(stmt, frame, ctx)
    }
}

class R_UpdateTarget_Expr_Many(
        entity: R_DbAtEntity,
        where: Db_Expr,
        expr: R_Expr,
        val set: Boolean,
        val listType: R_Type
): R_UpdateTarget_Expr(entity, where, expr) {
    override fun execute(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame) {
        val value = expr.evaluate(frame)

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
            val ctx = SqlGenContext.create(frame, listOf(entity), listOf(partValue))
            execute0(stmt, frame, ctx)
        }
    }
}

class R_UpdateTarget_Object(rObject: R_Object): R_UpdateTarget() {
    private val entity = R_DbAtEntity(rObject.rEntity, 0)

    override fun entity() = entity
    override fun extraEntities(): List<R_DbAtEntity> = listOf()
    override fun where() = null

    override fun execute(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame) {
        val ctx = SqlGenContext.create(frame, listOf(entity), listOf())
        R_UpdateTarget_Simple.execute(stmt, frame, ctx, R_AtCardinality.ONE)
    }
}

class R_UpdateStatementWhat(val attr: R_Attrib, val expr: Db_Expr, val op: Db_BinaryOp?)

sealed class R_BaseUpdateStatement(val target: R_UpdateTarget): R_Statement() {
    abstract fun buildSql(frame: Rt_CallFrame, ctx: SqlGenContext, returning: Boolean): ParameterizedSql

    final override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        frame.checkDbUpdateAllowed()
        target.execute(this, frame)
        return null
    }

    fun appendMainTable(builder: SqlBuilder, sqlCtx: Rt_SqlContext, fromInfo: SqlFromInfo) {
        val entity = target.entity()
        val table = entity.rEntity.sqlMapping.table(sqlCtx)
        builder.appendName(table)
        builder.append(" ")
        builder.append(fromInfo.entities[entity.index].alias.str)
    }

    fun appendExtraTables(builder: SqlBuilder, sqlCtx: Rt_SqlContext, fromInfo: SqlFromInfo, keyword: String) {
        val tables = mutableListOf<Pair<String, SqlTableAlias>>()

        val entity = target.entity()
        for (join in fromInfo.entities[entity.index].joins) {
            val table = join.alias.entity.sqlMapping.table(sqlCtx)
            tables.add(Pair(table, join.alias))
        }

        for (extraEntity in target.extraEntities()) {
            tables.add(Pair(extraEntity.rEntity.sqlMapping.table(sqlCtx), fromInfo.entities[extraEntity.index].alias))
            for (join in fromInfo.entities[extraEntity.index].joins) {
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

    fun appendWhere(b: SqlBuilder, ctx: SqlGenContext, fromInfo: SqlFromInfo, redWhere: RedDb_Expr?) {
        val allJoins = fromInfo.entities.flatMap { it.joins }

        val whereB = SqlBuilder()
        appendWhereJoins(whereB, allJoins)

        if (redWhere != null) {
            whereB.appendSep(" AND ")
            redWhere.toSql(ctx, whereB)
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

    fun appendReturning(builder: SqlBuilder, fromInfo: SqlFromInfo) {
        val entity = target.entity()
        builder.append(" RETURNING ")
        builder.appendColumn(fromInfo.entities[entity.index].alias, entity.rEntity.sqlMapping.rowidColumn())
    }
}

class R_UpdateStatement(target: R_UpdateTarget, val what: List<R_UpdateStatementWhat>): R_BaseUpdateStatement(target) {
    override fun buildSql(frame: Rt_CallFrame, ctx: SqlGenContext, returning: Boolean): ParameterizedSql {
        val redWhere = target.where()?.toRedExpr(frame)

        val redWhat = what.map {
            val redExpr = it.expr.toRedExpr(frame)
            RedDb_Utils.wrapDecimalExpr(it.expr.type, redExpr)
        }

        val fromInfo = buildFromInfo(ctx, redWhere, redWhat)
        val b = SqlBuilder()

        b.append("UPDATE ")
        appendMainTable(b, ctx.sqlCtx, fromInfo)
        appendSet(ctx, b, redWhat)
        appendExtraTables(b, ctx.sqlCtx, fromInfo, "FROM")
        appendWhere(b, ctx, fromInfo, redWhere)

        if (returning) {
            appendReturning(b, fromInfo)
        }

        return b.build()
    }

    private fun buildFromInfo(ctx: SqlGenContext, redWhere: RedDb_Expr?, redWhat: List<RedDb_Expr>): SqlFromInfo {
        val b = SqlBuilder()
        redWhat.forEach { it.toSql(ctx, b) }
        redWhere?.toSql(ctx, b)
        return ctx.getFromInfo()
    }

    private fun appendSet(ctx: SqlGenContext, b: SqlBuilder, redWhat: List<RedDb_Expr>) {
        b.append(" SET ")

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
    }
}

class R_DeleteStatement(target: R_UpdateTarget): R_BaseUpdateStatement(target) {
    override fun buildSql(frame: Rt_CallFrame, ctx: SqlGenContext, returning: Boolean): ParameterizedSql {
        val redWhere = target.where()?.toRedExpr(frame)

        val fromInfo = buildFromInfo(ctx, redWhere)
        val b = SqlBuilder()

        b.append("DELETE FROM ")
        appendMainTable(b, ctx.sqlCtx, fromInfo)
        appendExtraTables(b, ctx.sqlCtx, fromInfo, "USING")
        appendWhere(b, ctx, fromInfo, redWhere)

        if (returning) {
            appendReturning(b, fromInfo)
        }

        return b.build()
    }

    private fun buildFromInfo(ctx: SqlGenContext, redWhere: RedDb_Expr?): SqlFromInfo {
        val b = SqlBuilder()
        redWhere?.toSql(ctx, b)
        return ctx.getFromInfo()
    }
}
