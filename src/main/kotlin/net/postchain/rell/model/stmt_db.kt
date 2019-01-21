package net.postchain.rell.model

import net.postchain.rell.runtime.Rt_CallFrame

class R_UpdateStatementWhat(val attr: R_Attrib, val expr: Db_Expr, val op: Db_BinaryOp?)

class R_UpdateStatement(
        val cls: R_AtClass,
        val extraClasses: List<R_AtClass>,
        val where: Db_Expr?,
        val what: List<R_UpdateStatementWhat>
): R_Statement()
{
    init {
        check(cls.index == 0)
        extraClasses.withIndex().forEach { check(it.index + 1 == it.value.index) }
    }

    override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        frame.entCtx.checkDbUpdateAllowed()
        val rtSql = buildSql()
        val rtUpdate = SqlUpdate(rtSql)
        rtUpdate.execute(frame)
        return null
    }

    private fun buildSql(): ParameterizedSql {
        val builder = SqlBuilder()

        val ctx = SqlGenContext(listOf(cls) + extraClasses, listOf())
        val fromInfo = buildFromInfo(ctx)

        builder.append("UPDATE ")
        appendMainTable(builder, cls, fromInfo)
        appendSet(ctx, builder)
        appendExtraTables(builder, cls, extraClasses, fromInfo, "FROM")
        appendWhere(ctx, builder, fromInfo, where)

        return builder.build()
    }

    private fun buildFromInfo(ctx: SqlGenContext): SqlFromInfo {
        val b = SqlBuilder()
        what.forEach { it.expr.toSql(ctx, b) }
        if (where != null) {
            where.toSql(ctx, b)
        }
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

class R_DeleteStatement(val cls: R_AtClass, val extraClasses: List<R_AtClass>, val where: Db_Expr?): R_Statement() {
    init {
        check(cls.index == 0)
        extraClasses.withIndex().forEach { check(it.index + 1 == it.value.index) }
    }

    override fun execute(frame: Rt_CallFrame): R_StatementResult? {
        frame.entCtx.checkDbUpdateAllowed()
        val rtSql = buildSql()
        val rtUpdate = SqlUpdate(rtSql)
        rtUpdate.execute(frame)
        return null
    }

    private fun buildSql(): ParameterizedSql {
        val builder = SqlBuilder()

        val ctx = SqlGenContext(listOf(cls) + extraClasses, listOf())
        val fromInfo = buildFromInfo(ctx)

        builder.append("DELETE FROM ")
        appendMainTable(builder, cls, fromInfo)
        appendExtraTables(builder, cls, extraClasses, fromInfo, "USING")
        appendWhere(ctx, builder, fromInfo, where)

        return builder.build()
    }

    private fun buildFromInfo(ctx: SqlGenContext): SqlFromInfo {
        val b = SqlBuilder()
        if (where != null) {
            where.toSql(ctx, b)
        }
        return ctx.getFromInfo()
    }
}

private fun appendMainTable(builder: SqlBuilder, cls: R_AtClass, fromInfo: SqlFromInfo) {
    builder.appendName(cls.rClass.mapping.table)
    builder.append(" ")
    builder.append(fromInfo.classes[cls.index].alias.str)
}

private fun appendExtraTables(
        builder: SqlBuilder,
        cls: R_AtClass,
        extraClasses: List<R_AtClass>,
        fromInfo: SqlFromInfo,
        keyword: String)
{
    val tables = mutableListOf<Pair<String, SqlTableAlias>>()

    for (join in fromInfo.classes[cls.index].joins) {
        tables.add(Pair(join.alias.cls.mapping.table, join.alias))
    }

    for (extraCls in extraClasses) {
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

private fun appendWhere(ctx: SqlGenContext, builder: SqlBuilder, fromInfo: SqlFromInfo, where: Db_Expr?) {
    val allJoins = fromInfo.classes.flatMap { it.joins }

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
