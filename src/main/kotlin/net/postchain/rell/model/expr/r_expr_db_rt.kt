/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model.expr

import net.postchain.rell.model.*
import net.postchain.rell.runtime.*
import net.postchain.rell.sql.SqlExecutor
import net.postchain.rell.utils.chainToIterable
import net.postchain.rell.utils.toImmList
import net.postchain.rell.utils.toImmMap
import java.sql.PreparedStatement
import java.sql.ResultSet

data class SqlTableAlias(val entity: R_EntityDefinition, val exprId: R_AtExprId, val str: String)
class SqlTableJoin(val attr: R_Attribute, val alias: SqlTableAlias)

class SqlFromInfo(entities: Map<R_AtEntityId, SqlFromEntity>) {
    val entities = entities.toImmMap()
}

class SqlFromEntity(val alias: SqlTableAlias, joins: List<SqlFromJoin>) {
    val joins = joins.toImmList()
}

class SqlFromJoin(val baseAlias: SqlTableAlias, val attr: String, val alias: SqlTableAlias)

private class SqlGenAliasAllocator {
    private var aliasCtr = 0

    fun nextAlias(entity: R_EntityDefinition, exprId: R_AtExprId): SqlTableAlias {
        val aliasStr = String.format("A%02d", aliasCtr++)
        return SqlTableAlias(entity, exprId, aliasStr)
    }
}

class SqlGenContext private constructor(
        val sqlCtx: Rt_SqlContext,
        fromEntities: List<R_DbAtEntity>,
        private val parent: SqlGenContext?
) {
    private val fromEntities = fromEntities.toImmList()
    private val aliasAllocator: SqlGenAliasAllocator = parent?.aliasAllocator ?: SqlGenAliasAllocator()

    private val atExprId = R_DbAtEntity.checkList(this.fromEntities)
    private val entityAliasMap = mutableMapOf<R_AtEntityId, EntityAliasTbl>()
    private val aliasTableMap = mutableMapOf<SqlTableAlias, EntityAliasTbl>()

    init {
        for (entity in this.fromEntities) {
            val alias = aliasAllocator.nextAlias(entity.rEntity, atExprId)
            val tbl = EntityAliasTbl(alias)
            check(alias !in aliasTableMap) { "${aliasTableMap.keys} $alias" }
            aliasTableMap[alias] = tbl
            check(entity.id !in entityAliasMap) { "${entityAliasMap.keys} ${entity.id}" }
            entityAliasMap[entity.id] = tbl
        }
    }

    fun createSub(entities: List<R_DbAtEntity>): SqlGenContext {
        return SqlGenContext(sqlCtx, entities, this)
    }

    fun getEntityAlias(entity: R_DbAtEntity): SqlTableAlias {
        val ctx = getSqlGenCtxForExpr(entity.id.exprId)
        val tbl = ctx.entityAliasMap.getValue(entity.id)
        return tbl.alias
    }

    fun getRelAlias(baseAlias: SqlTableAlias, rel: R_Attribute, targetEntity: R_EntityDefinition): SqlTableAlias {
        val ctx = getSqlGenCtxForExpr(baseAlias.exprId)
        val tbl = ctx.aliasTableMap.getValue(baseAlias)
        val map = tbl.subAliases.computeIfAbsent(baseAlias) { mutableMapOf() }
        val join = map.computeIfAbsent(rel.name) {
            val alias = aliasAllocator.nextAlias(targetEntity, baseAlias.exprId)
            ctx.aliasTableMap[alias] = tbl
            SqlTableJoin(rel, alias)
        }
        return join.alias
    }

    fun getFromInfo(): SqlFromInfo {
        val entities = fromEntities.map { rAtEntity ->
            val entityId = rAtEntity.id
            val tbl = entityAliasMap.getValue(entityId)
            val joins = tbl.subAliases.entries.flatMap { (alias, map) ->
                map.values.map { tblJoin -> SqlFromJoin(alias, tblJoin.attr.sqlMapping, tblJoin.alias) }
            }
            entityId to SqlFromEntity(tbl.alias, joins)
        }.toMap()
        return SqlFromInfo(entities)
    }

    private fun getSqlGenCtxForExpr(exprId: R_AtExprId): SqlGenContext {
        val iterable = chainToIterable(this) { it.parent }
        return iterable.firstOrNull { it.atExprId == exprId } ?: throw IllegalArgumentException("$exprId")
    }

    private class EntityAliasTbl(val alias: SqlTableAlias) {
        val subAliases = mutableMapOf<SqlTableAlias, MutableMap<String, SqlTableJoin>>()
    }

    companion object {
        fun createTop(frame: Rt_CallFrame, entities: List<R_DbAtEntity>): SqlGenContext {
            val sqlCtx = frame.defCtx.sqlCtx
            return createTop(sqlCtx, entities)
        }

        fun createTop(sqlCtx: Rt_SqlContext, entities: List<R_DbAtEntity>): SqlGenContext {
            return SqlGenContext(sqlCtx, entities, null)
        }
    }
}

class SqlBuilder {
    private val sqlBuf = StringBuilder()
    private val paramsBuf = mutableListOf<Rt_Value>()

    fun isEmpty(): Boolean {
        return sqlBuf.isEmpty() && paramsBuf.isEmpty()
    }

    fun <T> append(list: Iterable<T>, sep: String, block: (T) -> Unit) {
        var s = ""
        for (t in list) {
            append(s)
            block(t)
            s = sep
        }
    }

    fun appendName(name: String) {
        append("\"")
        append(name)
        append("\"")
    }

    fun appendColumn(alias: SqlTableAlias, column: String) {
        appendColumn(alias.str, column)
    }

    fun appendColumn(alias: String, column: String) {
        append(alias)
        append(".")
        appendName(column)
    }

    fun append(sql: String) {
        sqlBuf.append(sql)
    }

    fun append(param: Long) {
        sqlBuf.append("?")
        paramsBuf.add(Rt_IntValue(param))
    }

    fun append(value: Rt_Value) {
        sqlBuf.append("?")
        paramsBuf.add(value)
    }

    fun append(buf: SqlBuilder) {
        sqlBuf.append(buf.sqlBuf)
        paramsBuf.addAll(buf.paramsBuf)
    }

    fun append(sql: ParameterizedSql) {
        sqlBuf.append(sql.sql)
        paramsBuf.addAll(sql.params)
    }

    fun appendSep(sep: String) {
        if (!isEmpty()) {
            append(sep)
        }
    }

    fun build(): ParameterizedSql = ParameterizedSql(sqlBuf.toString(), paramsBuf.toList())
}

class ParameterizedSql(val sql: String, params: List<Rt_Value>) {
    val params = params.toImmList()

    constructor(): this("", listOf())

    fun isEmpty() = sql.isEmpty() && params.isEmpty()

    fun execute(sqlExec: SqlExecutor) {
        val args = calcArgs()
        sqlExec.execute(sql, args::bind)
    }

    fun executeQuery(sqlExec: SqlExecutor, consumer: (ResultSet) -> Unit) {
        val args = calcArgs()
        sqlExec.executeQuery(sql, args::bind, consumer)
    }

    private fun calcArgs(): SqlArgs {
        // Was experimentally discovered that passing more than 32767 parameters causes PSQL driver to fail and the
        // connection becomes invalid afterwards. Not allowing this to happen.
        val maxParams = 32767
        Rt_Utils.check(params.size <= maxParams) {
            "sql:too_many_params:${params.size}" to "SQL query is too big (${params.size} parameters)" }
        return SqlArgs(params)
    }

    companion object {
        fun generate(generator: (SqlBuilder) -> Unit): ParameterizedSql {
            val b = SqlBuilder()
            generator(b)
            return b.build()
        }
    }
}

class SqlArgs(values: List<Rt_Value>) {
    private val values = values.toImmList()

    fun bind(stmt: PreparedStatement) {
        for ((i, value) in values.withIndex()) {
            val type = value.type()
            type.sqlAdapter.toSql(stmt, i + 1, value)
        }
    }
}

class SqlSelect(val pSql: ParameterizedSql, val resultTypes: List<R_Type>) {
    fun execute(sqlExec: SqlExecutor): List<List<Rt_Value>> {
        return execute(sqlExec) { it }
    }

    fun execute(sqlExec: SqlExecutor, transformer: (List<Rt_Value>) -> List<Rt_Value>): List<List<Rt_Value>> {
        val result = mutableListOf<List<Rt_Value>>()

        pSql.executeQuery(sqlExec) { rs ->
            val list = mutableListOf<Rt_Value>()
            for (i in resultTypes.indices) {
                val type = resultTypes[i]
                val value = type.sqlAdapter.fromSql(rs, i + 1, false)
                list.add(value)
            }

            val row = list.toImmList()
            val transRow = transformer(row)
            result.add(transRow)
        }

        return result
    }
}
