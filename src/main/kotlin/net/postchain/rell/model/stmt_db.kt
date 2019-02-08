package net.postchain.rell.model

import net.postchain.rell.CommonUtils
import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_ListValue
import net.postchain.rell.runtime.Rt_NullValue

sealed class R_UpdateTarget {
    abstract fun cls(): R_AtClass
    abstract fun extraClasses(): List<R_AtClass>
    abstract fun where(): Db_Expr?

    abstract fun execute(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame)
}

class R_UpdateTarget_Simple(
        val cls: R_AtClass,
        val extraClasses: List<R_AtClass>,
        val cardinality: R_AtCardinality,
        val where: Db_Expr?
): R_UpdateTarget() {
    init {
        check(cls.index == 0)
        extraClasses.withIndex().forEach { check(it.index + 1 == it.value.index) }
    }

    override fun cls() = cls
    override fun extraClasses() = extraClasses
    override fun where() = where

    override fun execute(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame) {
        val ctx = SqlGenContext(listOf(cls) + extraClasses, listOf())
        execute(stmt, frame, ctx, cardinality)
    }

    companion object {
        fun execute(
                stmt: R_BaseUpdateStatement,
                frame: Rt_CallFrame,
                ctx: SqlGenContext,
                cardinality: R_AtCardinality
        ) {
            val pSql = stmt.buildSql(ctx, true)

            var count = 0
            pSql.executeQuery(frame) { rs ->
                ++count
            }

            R_AtExpr.checkCount(cardinality, count)
        }
    }
}

sealed class R_UpdateTarget_Expr(val cls: R_AtClass, val where: Db_Expr, val expr: R_Expr): R_UpdateTarget() {
    init {
        check(cls.index == 0)
    }

    final override fun cls() = cls
    final override fun extraClasses() = listOf<R_AtClass>()
    final override fun where() = where

    protected fun execute0(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame, ctx: SqlGenContext) {
        val pSql = stmt.buildSql(ctx, false)
        pSql.execute(frame)
    }
}

class R_UpdateTarget_Expr_One(cls: R_AtClass, where: Db_Expr, expr: R_Expr): R_UpdateTarget_Expr(cls, where, expr) {
    override fun execute(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame) {
        val value = expr.evaluate(frame)
        if (value == Rt_NullValue) {
            return
        }

        val ctx = SqlGenContext(listOf(cls), listOf(value))
        execute0(stmt, frame, ctx)
    }
}

class R_UpdateTarget_Expr_Many(
        cls: R_AtClass,
        where: Db_Expr,
        expr: R_Expr,
        val set: Boolean,
        val listType: R_Type
): R_UpdateTarget_Expr(cls, where, expr) {
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
        val partSize = frame.entCtx.modCtx.globalCtx.sqlUpdatePortionSize

        for (part in CommonUtils.split(lst, partSize)) {
            val partValue = Rt_ListValue(listType, part)
            val ctx = SqlGenContext(listOf(cls), listOf(partValue))
            execute0(stmt, frame, ctx)
        }
    }
}

class R_UpdateTarget_Object(private val cls: R_AtClass, private val rObject: R_Object): R_UpdateTarget() {
    init {
        check(cls.index == 0)
    }

    override fun cls() = cls
    override fun extraClasses(): List<R_AtClass> = listOf()
    override fun where() = null

    override fun execute(stmt: R_BaseUpdateStatement, frame: Rt_CallFrame) {
        val ctx = SqlGenContext(listOf(cls), listOf())
        R_UpdateTarget_Simple.execute(stmt, frame, ctx, R_AtCardinality.ONE)
    }
}

class R_UpdateStatementWhat(val attr: R_Attrib, val expr: Db_Expr, val op: Db_BinaryOp?)

sealed class R_BaseUpdateStatement(val target: R_UpdateTarget): R_Statement() {
    abstract fun buildSql(ctx: SqlGenContext, returning: Boolean): ParameterizedSql

    final override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        frame.entCtx.checkDbUpdateAllowed()
        target.execute(this, frame)
        return null
    }

    fun appendMainTable(builder: SqlBuilder, fromInfo: SqlFromInfo) {
        val cls = target.cls()
        builder.appendName(cls.rClass.mapping.table)
        builder.append(" ")
        builder.append(fromInfo.classes[cls.index].alias.str)
    }

    fun appendExtraTables(builder: SqlBuilder, fromInfo: SqlFromInfo, keyword: String) {
        val tables = mutableListOf<Pair<String, SqlTableAlias>>()

        val cls = target.cls()
        for (join in fromInfo.classes[cls.index].joins) {
            tables.add(Pair(join.alias.cls.mapping.table, join.alias))
        }

        for (extraCls in target.extraClasses()) {
            tables.add(Pair(extraCls.rClass.mapping.table, fromInfo.classes[extraCls.index].alias))
            for (join in fromInfo.classes[extraCls.index].joins) {
                tables.add(Pair(join.alias.cls.mapping.table, join.alias))
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

    fun appendWhere(ctx: SqlGenContext, builder: SqlBuilder, fromInfo: SqlFromInfo) {
        val allJoins = fromInfo.classes.flatMap { it.joins }
        val where = target.where()

        if (allJoins.isEmpty() && where == null) {
            return
        }

        builder.append(" WHERE ")

        if (!allJoins.isEmpty() && where != null) {
            builder.append("(")
            appendWhereJoins(builder, allJoins)
            builder.append(") AND (")
            where.toSql(ctx, builder)
            builder.append(")")
        } else if (!allJoins.isEmpty()) {
            appendWhereJoins(builder, allJoins)
        } else if (where != null) {
            where.toSql(ctx, builder)
        }
    }

    private fun appendWhereJoins(builder: SqlBuilder, allJoins: List<SqlFromJoin>) {
        builder.append(allJoins, " AND ") { join ->
            builder.appendColumn(join.baseAlias, join.attr)
            builder.append(" = ")
            builder.appendColumn(join.alias, join.alias.cls.mapping.rowidColumn)
        }
    }

    fun appendReturning(builder: SqlBuilder, fromInfo: SqlFromInfo) {
        val cls = target.cls()
        builder.append(" RETURNING ")
        builder.appendColumn(fromInfo.classes[cls.index].alias, cls.rClass.mapping.rowidColumn)
    }
}

class R_UpdateStatement(target: R_UpdateTarget, val what: List<R_UpdateStatementWhat>): R_BaseUpdateStatement(target) {
    override fun buildSql(ctx: SqlGenContext, returning: Boolean): ParameterizedSql {
        val fromInfo = buildFromInfo(ctx)
        val builder = SqlBuilder()

        builder.append("UPDATE ")
        appendMainTable(builder, fromInfo)
        appendSet(ctx, builder)
        appendExtraTables(builder, fromInfo, "FROM")
        appendWhere(ctx, builder, fromInfo)

        if (returning) {
            appendReturning(builder, fromInfo)
        }

        return builder.build()
    }

    private fun buildFromInfo(ctx: SqlGenContext): SqlFromInfo {
        val b = SqlBuilder()
        what.forEach { it.expr.toSql(ctx, b) }
        target.where()?.toSql(ctx, b)
        return ctx.getFromInfo()
    }

    private fun appendSet(ctx: SqlGenContext, builder: SqlBuilder) {
        builder.append(" SET ")

        builder.append(what, ", ") { whatExpr ->
            builder.appendName(whatExpr.attr.sqlMapping)
            builder.append(" = ")
            if (whatExpr.op != null) {
                builder.appendName(whatExpr.attr.sqlMapping)
                builder.append(" ")
                builder.append(whatExpr.op.sql)
                builder.append(" ")
            }
            whatExpr.expr.toSql(ctx, builder)
        }
    }
}

class R_DeleteStatement(target: R_UpdateTarget): R_BaseUpdateStatement(target) {
    override fun buildSql(ctx: SqlGenContext, returning: Boolean): ParameterizedSql {
        val fromInfo = buildFromInfo(ctx)
        val builder = SqlBuilder()

        builder.append("DELETE FROM ")
        appendMainTable(builder, fromInfo)
        appendExtraTables(builder, fromInfo, "USING")
        appendWhere(ctx, builder, fromInfo)

        if (returning) {
            appendReturning(builder, fromInfo)
        }

        return builder.build()
    }

    private fun buildFromInfo(ctx: SqlGenContext): SqlFromInfo {
        val b = SqlBuilder()
        target.where()?.toSql(ctx, b)
        return ctx.getFromInfo()
    }
}
