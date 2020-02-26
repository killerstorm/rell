/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.sql

import net.postchain.base.data.PostgreSQLCommands
import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_ChainSqlMapping
import net.postchain.rell.runtime.Rt_SqlContext
import org.jooq.Constraint
import org.jooq.CreateTableColumnStep
import org.jooq.DataType
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DSL.constraint
import org.jooq.impl.SQLDataType

private val disableLogo = run {
    System.setProperty("org.jooq.no-logo", "true")
}

object SqlGen {
    private val disableLogo2 = disableLogo

    val DSL_CTX = DSL.using(SQLDialect.POSTGRES)

    private val CREATE_TABLE_BLOCKS = PostgreSQLCommands.createTableBlocks
    private val CREATE_TABLE_TRANSACTIONS = PostgreSQLCommands.createTableTransactions
    private val CREATE_TABLE_BLOCKCHAINS = PostgreSQLCommands.createTableBlockChains

    fun genSqlCreateSysTables(): String {
        val sqls = mutableListOf<String>()
        sqls += "$CREATE_TABLE_BLOCKS;"
        sqls += "$CREATE_TABLE_TRANSACTIONS;"
        sqls += "$CREATE_TABLE_BLOCKCHAINS;"
        return joinSqls(sqls)
    }

    fun genRowidSql(chainMapping: Rt_ChainSqlMapping): String {
        val table = chainMapping.rowidTable
        val func = chainMapping.rowidFunction
        return """
            CREATE TABLE "$table"( last_value bigint not null);
            INSERT INTO "$table"(last_value) VALUES (0);
            CREATE FUNCTION "$func"() RETURNS BIGINT AS
            'UPDATE "$table" SET last_value = last_value + 1 RETURNING last_value'
            LANGUAGE SQL;
        """.trimIndent()
    }

    fun genEntity(sqlCtx: Rt_SqlContext, rEntity: R_Entity): String {
        val tableName = rEntity.sqlMapping.table(sqlCtx)
        return genEntity(sqlCtx, rEntity, tableName)
    }

    fun genEntity(sqlCtx: Rt_SqlContext, rEntity: R_Entity, tableName: String): String {
        val mapping = rEntity.sqlMapping
        val rowid = mapping.rowidColumn()
        val attrs = rEntity.attributes.values

        val t = SqlGen.DSL_CTX.createTable(tableName)

        val constraints = mutableListOf<Constraint>()
        constraints.add(constraint("PK_" + tableName).primaryKey(rowid))
        constraints += genAttrConstraints(sqlCtx, tableName, attrs)

        var q = t.column(rowid, SQLDataType.BIGINT.nullable(false))
        q = genAttrColumns(attrs, q)

        for ((kidx, key) in rEntity.keys.withIndex()) {
            constraints.add(constraint("K_${tableName}_${kidx}").unique(*key.attribs.toTypedArray()))
        }

        var ddl = q.constraints(*constraints.toTypedArray()).toString() + ";\n"

        val jsonAttribSet = attrs.filter { it.type is R_JsonType }.map { it.name }.toSet()

        for ((iidx, index) in rEntity.indexes.withIndex()) {
            val indexName = "IDX_${tableName}_${iidx}"
            val indexSql : String
            if (index.attribs.size == 1 && jsonAttribSet.contains(index.attribs[0])) {
                val attrName = index.attribs[0]
                indexSql = """CREATE INDEX "$indexName" ON "$tableName" USING gin ("${attrName}" jsonb_path_ops)"""
            } else {
                indexSql = (SqlGen.DSL_CTX.createIndex(indexName).on(tableName, *index.attribs.toTypedArray())).toString();
            }
            ddl += indexSql + ";\n";
        }

        return ddl
    }

    private fun genAttrColumns(attrs: Collection<R_Attrib>, step: CreateTableColumnStep): CreateTableColumnStep {
        var q = step
        for (attr in attrs) {
            q = q.column(attr.sqlMapping, getSqlType(attr.type))
        }
        return q
    }

    private fun genAttrConstraints(sqlCtx: Rt_SqlContext, sqlTable: String, attrs: Collection<R_Attrib>): List<Constraint> {
        val constraints = mutableListOf<Constraint>()

        for (attr in attrs) {
            if (attr.type is R_EntityType) {
                val refEntity = attr.type.rEntity
                val refTable = refEntity.sqlMapping.table(sqlCtx)
                val constraint = constraint("${sqlTable}_${attr.sqlMapping}_FK")
                        .foreignKey(attr.sqlMapping)
                        .references(refTable, refEntity.sqlMapping.rowidColumn())
                constraints.add(constraint)
            }
        }

        return constraints
    }

    fun genAddColumnSql(table: String, attr: R_Attrib, nullable: Boolean): String {
        val type = getSqlType(attr.type).nullable(nullable)
        val b = DSL_CTX.alterTable(table).addColumn(attr.sqlMapping, type)
        return b.toString()
    }

    fun genAddAttrConstraintsSql(sqlCtx: Rt_SqlContext, table: String, attrs: List<R_Attrib>): String {
        val constraints = genAttrConstraints(sqlCtx, table, attrs)
        if (constraints.isEmpty()) {
            return ""
        }

        val b = DSL_CTX.alterTable(table)
        for (c in constraints) {
            b.add(c)
        }

        return b.toString()
    }

    fun joinSqls(sqls: List<String>) = sqls.joinToString("\n") + "\n"
}

private fun getSqlType(t: R_Type): DataType<*> {
    val st = getSqlType0(t)
    return st.nullable(false)
}

private fun getSqlType0(t: R_Type): DataType<*> {
    when (t) {
        is R_PrimitiveType -> return t.sqlType
        is R_EntityType -> return SQLDataType.BIGINT
        is R_EnumType -> return SQLDataType.INTEGER
        else -> throw Exception("SQL type not implemented for $t")
    }
}
