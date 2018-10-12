package net.postchain.rell.model

import net.postchain.rell.runtime.RtEnv
import net.postchain.rell.runtime.RtValue
import net.postchain.rell.sql.MAKE_ROWID_FUNCTION
import net.postchain.rell.sql.ROWID_COLUMN

class RCreateStatementAttr(val attr: RAttrib, val expr: RExpr)

class RCreateStatement(val rClass: RClass, val attrs: List<RCreateStatementAttr>): RStatement() {
    override fun execute(env: RtEnv): RtValue? {
        val rtSql = buildSql()
        val rtUpdate = RtUpdate(rtSql)
        rtUpdate.execute(env)
        return null
    }

    private fun buildSql(): RtSql {
        val builder = RtSqlBuilder()

        builder.append("INSERT INTO ")
        builder.appendName(rClass.name)

        builder.append("(")
        builder.appendName(ROWID_COLUMN)
        builder.append(", ")
        builder.append(attrs, ", ") { attr ->
            builder.appendName(attr.attr.name)
        }
        builder.append(")")

        builder.append(" VALUES (")
        builder.append("$MAKE_ROWID_FUNCTION(), ")
        builder.append(attrs, ", ") { attr ->
            builder.append(attr.expr)
        }
        builder.append(")")

        return builder.build()
    }
}

class RUpdateStatementAttr(val attr: RAttrib, val expr: DbExpr)

class RUpdateStatement(
        val cls: RAtClass,
        val extraClasses: List<RAtClass>,
        val where: DbExpr?,
        val attrs: List<RUpdateStatementAttr>
): RStatement()
{
    init {
        check(cls.index == 0)
        extraClasses.withIndex().forEach { check(it.index + 1 == it.value.index) }
    }

    override fun execute(env: RtEnv): RtValue? {
        val rtSql = buildSql()
        val rtUpdate = RtUpdate(rtSql)
        rtUpdate.execute(env)
        return null
    }

    private fun buildSql(): RtSql {
        val builder = RtSqlBuilder()

        val fromInfo = buildFromInfo()

        val ctx = SqlGenContext(fromInfo)

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
        exprs.addAll(attrs.map { it.expr })
        if (where != null) {
            exprs.add(where)
        }

        return SqlFromInfo.create(from, exprs)
    }

    private fun appendSet(ctx: SqlGenContext, builder: RtSqlBuilder) {
        builder.append(" SET ")

        builder.append(attrs, ", ") { attr ->
            builder.appendName(attr.attr.name)
            builder.append(" = ")
            attr.expr.toSql(ctx, builder)
        }
    }
}

class RDeleteStatement(val cls: RAtClass, val extraClasses: List<RAtClass>, val where: DbExpr?): RStatement() {
    init {
        check(cls.index == 0)
        extraClasses.withIndex().forEach { check(it.index + 1 == it.value.index) }
    }

    override fun execute(env: RtEnv): RtValue? {
        val rtSql = buildSql()
        val rtUpdate = RtUpdate(rtSql)
        rtUpdate.execute(env)
        return null
    }

    private fun buildSql(): RtSql {
        val builder = RtSqlBuilder()

        val fromInfo = buildFromInfo()

        val ctx = SqlGenContext(fromInfo)

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
