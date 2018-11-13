package net.postchain.rell.model

import net.postchain.rell.runtime.RtCallFrame
import net.postchain.rell.sql.ROWID_COLUMN

class RUpdateStatementWhat(val attr: RAttrib, val expr: DbExpr, val op: DbBinaryOp?)

class RUpdateStatement(
        val cls: RAtClass,
        val extraClasses: List<RAtClass>,
        val where: DbExpr?,
        val what: List<RUpdateStatementWhat>
): RStatement()
{
    init {
        check(cls.index == 0)
        extraClasses.withIndex().forEach { check(it.index + 1 == it.value.index) }
    }

    override fun execute(frame: RtCallFrame): RStatementResult? {
        frame.entCtx.checkDbUpdateAllowed()
        val rtSql = buildSql()
        val rtUpdate = RtUpdate(rtSql)
        rtUpdate.execute(frame)
        return null
    }

    private fun buildSql(): RtSql {
        val builder = RtSqlBuilder()

        val fromInfo = buildFromInfo()

        val ctx = SqlGenContext(fromInfo, listOf())

        builder.append("UPDATE ")
        appendMainTable(builder, cls, fromInfo)
        appendSet(ctx, builder)
        appendExtraTables(builder, cls, extraClasses, fromInfo, "FROM")
        appendWhere(ctx, builder, fromInfo, where)

        return builder.build()
    }

    private fun buildFromInfo(): SqlFromInfo {
        val from = listOf(cls) + extraClasses

        val exprs = mutableListOf<DbExpr>()
        exprs.addAll(what.map { it.expr })
        if (where != null) {
            exprs.add(where)
        }

        return SqlFromInfo.create(from, exprs)
    }

    private fun appendSet(ctx: SqlGenContext, builder: RtSqlBuilder) {
        builder.append(" SET ")

        builder.append(what, ", ") { whatExpr ->
            builder.appendName(whatExpr.attr.name)
            builder.append(" = ")
            if (whatExpr.op != null) {
                builder.appendName(whatExpr.attr.name)
                builder.append(" ")
                builder.append(whatExpr.op.sql)
                builder.append(" ")
            }
            whatExpr.expr.toSql(ctx, builder)
        }
    }
}

class RDeleteStatement(val cls: RAtClass, val extraClasses: List<RAtClass>, val where: DbExpr?): RStatement() {
    init {
        check(cls.index == 0)
        extraClasses.withIndex().forEach { check(it.index + 1 == it.value.index) }
    }

    override fun execute(frame: RtCallFrame): RStatementResult? {
        frame.entCtx.checkDbUpdateAllowed()
        val rtSql = buildSql()
        val rtUpdate = RtUpdate(rtSql)
        rtUpdate.execute(frame)
        return null
    }

    private fun buildSql(): RtSql {
        val builder = RtSqlBuilder()

        val fromInfo = buildFromInfo()

        val ctx = SqlGenContext(fromInfo, listOf())

        builder.append("DELETE FROM ")
        appendMainTable(builder, cls, fromInfo)
        appendExtraTables(builder, cls, extraClasses, fromInfo, "USING")
        appendWhere(ctx, builder, fromInfo, where)

        return builder.build()
    }

    private fun buildFromInfo(): SqlFromInfo {
        val from = listOf(cls) + extraClasses

        val exprs = mutableListOf<DbExpr>()
        if (where != null) {
            exprs.add(where)
        }

        return SqlFromInfo.create(from, exprs)
    }
}

private fun appendMainTable(builder: RtSqlBuilder, cls: RAtClass, fromInfo: SqlFromInfo) {
    builder.appendName(cls.rClass.name)
    builder.append(" ")
    builder.append(fromInfo.classes[cls.index].alias)
}

private fun appendExtraTables(
        builder: RtSqlBuilder,
        cls: RAtClass,
        extraClasses: List<RAtClass>,
        fromInfo: SqlFromInfo,
        keyword: String)
{
    val tables = mutableListOf<Pair<String, String>>()

    for (join in fromInfo.classes[cls.index].joins) {
        tables.add(Pair(join.cls.name, join.alias))
    }

    for (extraCls in extraClasses) {
        tables.add(Pair(extraCls.rClass.name, fromInfo.classes[extraCls.index].alias))
        for (join in fromInfo.classes[extraCls.index].joins) {
            tables.add(Pair(join.cls.name, join.alias))
        }
    }

    if (tables.isEmpty()) {
        return
    }

    builder.append(" $keyword ")

    builder.append(tables, ", ") { (table, alias) ->
        builder.appendName(table)
        builder.append(" ")
        builder.append(alias)
    }
}

private fun appendWhere(ctx: SqlGenContext, builder: RtSqlBuilder, fromInfo: SqlFromInfo, where: DbExpr?) {
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

private fun appendWhereJoins(builder: RtSqlBuilder, allJoins: List<SqlFromJoin>) {
    builder.append(allJoins, " AND ") { join ->
        builder.appendColumn(join.baseAlias, join.attr)
        builder.append(" = ")
        builder.appendColumn(join.alias, ROWID_COLUMN)
    }
}
