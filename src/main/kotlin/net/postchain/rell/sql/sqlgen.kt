package net.postchain.rell.sql

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

    private val CREATE_TABLE_BLOCKS = """
    CREATE TABLE "blocks"(
        block_iid BIGSERIAL PRIMARY KEY,
        block_height BIGINT NOT NULL,
        block_rid BYTEA,
        chain_id BIGINT NOT NULL,
        block_header_data BYTEA,
        block_witness BYTEA,
        timestamp BIGINT,
        UNIQUE (chain_id, block_rid),
        UNIQUE (chain_id, block_height)
    );
    """.trimIndent()

    private val CREATE_TABLE_TRANSACTIONS = """
    CREATE TABLE "transactions"(
        tx_iid BIGSERIAL PRIMARY KEY,
        chain_id BIGINT NOT NULL,
        tx_rid BYTEA NOT NULL,
        tx_data BYTEA NOT NULL,
        tx_hash BYTEA NOT NULL,
        block_iid BIGINT NOT NULL REFERENCES blocks(block_iid),
        UNIQUE (chain_id, tx_rid)
    );
    """.trimIndent()

    private val CREATE_TABLE_BLOCKCHAINS = """
    CREATE TABLE "blockchains"(
        chain_id BIGINT NOT NULL PRIMARY KEY,
        blockchain_rid BYTEA NOT NULL UNIQUE
    );
    """.trimIndent()

    fun genSqlCreateSysTables(): String {
        val sqls = mutableListOf<String>()
        sqls += CREATE_TABLE_BLOCKS
        sqls += CREATE_TABLE_TRANSACTIONS
        sqls += CREATE_TABLE_BLOCKCHAINS
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

    fun genClass(sqlCtx: Rt_SqlContext, rClass: R_Class): String {
        val tableName = rClass.sqlMapping.table(sqlCtx)
        return genClass(sqlCtx, rClass, tableName)
    }

    fun genClass(sqlCtx: Rt_SqlContext, rClass: R_Class, tableName: String): String {
        val mapping = rClass.sqlMapping
        val rowid = mapping.rowidColumn()
        val attrs = rClass.attributes.values

        val t = SqlGen.DSL_CTX.createTable(tableName)

        val constraints = mutableListOf<Constraint>()
        constraints.add(constraint("PK_" + tableName).primaryKey(rowid))
        constraints += genAttrConstraints(sqlCtx, tableName, attrs)

        var q = t.column(rowid, SQLDataType.BIGINT.nullable(false))
        q = genAttrColumns(attrs, q)

        for ((kidx, key) in rClass.keys.withIndex()) {
            constraints.add(constraint("K_${tableName}_${kidx}").unique(*key.attribs.toTypedArray()))
        }

        var ddl = q.constraints(*constraints.toTypedArray()).toString() + ";\n"

        val jsonAttribSet = attrs.filter { it.type is R_JsonType }.map { it.name }.toSet()

        for ((iidx, index) in rClass.indexes.withIndex()) {
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
            q = q.column(attr.sqlMapping, getSQLType(attr.type).nullable(false))
        }
        return q
    }

    private fun genAttrConstraints(sqlCtx: Rt_SqlContext, sqlTable: String, attrs: Collection<R_Attrib>): List<Constraint> {
        val constraints = mutableListOf<Constraint>()

        for (attr in attrs) {
            if (attr.type is R_ClassType) {
                val refCls = attr.type.rClass
                val refTable = refCls.sqlMapping.table(sqlCtx)
                val constraint = constraint("${sqlTable}_${attr.sqlMapping}_FK")
                        .foreignKey(attr.sqlMapping)
                        .references(refTable, refCls.sqlMapping.rowidColumn())
                constraints.add(constraint)
            }
        }

        return constraints
    }

    fun joinSqls(sqls: List<String>) = sqls.joinToString("\n") + "\n"
}

private fun getSQLType(t: R_Type): DataType<*> {
    when (t) {
        is R_PrimitiveType -> return t.sqlType
        is R_ClassType -> return SQLDataType.BIGINT
        is R_EnumType -> return SQLDataType.INTEGER
        else -> throw Exception("SQL Type not implemented")
    }
}
