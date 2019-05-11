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

private val dslCtx = DSL.using(SQLDialect.POSTGRES);

fun getSQLType(t: R_Type): DataType<*> {
    when (t) {
        is R_PrimitiveType -> return t.sqlType
        is R_ClassType -> return SQLDataType.BIGINT
        is R_EnumType -> return SQLDataType.INTEGER
        else -> throw Exception("SQL Type not implemented")
    }
}

private fun genRowidSql(chainMapping: Rt_ChainSqlMapping): String {
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

private val CREATE_TABLE_META_CLASSES = """
CREATE TABLE "%s"(
    "id" INT NOT NULL PRIMARY KEY,
    "name" TEXT NOT NULL UNIQUE,
    "log" BOOLEAN NOT NULL
);
""".trimIndent()

private val CREATE_TABLE_META_ATTRIBUTES = """
CREATE TABLE "%s"(
    "class_id" INT NOT NULL,
    "name" TEXT NOT NULL,
    "type" TEXT NOT NULL
);
""".trimIndent()

private fun genClass(sqlCtx: Rt_SqlContext, rClass: R_Class): String {
    val mapping = rClass.sqlMapping
    val rowid = mapping.rowidColumn()
    val tableName = mapping.table(sqlCtx)
    val attrs = rClass.attributes.values

    val t = dslCtx.createTable(tableName)

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
            indexSql = (dslCtx.createIndex(indexName).on(tableName, *index.attribs.toTypedArray())).toString();
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

fun genRequire(s: R_CallExpr): String {
    if (s.args.size == 0 || s.args.size > 2) throw Exception("Too many (or too little) arguments to require")
    val message = if (s.args.size == 2) genExpr(s.args[1]) else "'Require failed'"

    val condition = s.args[0]
    val condSQL : String
    if (condition.type is R_BooleanType)
        condSQL = "NOT ${genExpr(condition)}"
    else if (condition.type is R_ClassType)
        condSQL = "NOT EXISTS ${genExpr(condition)}"
    else throw Exception("Cannot handle type in require")

    return """
    IF ${condSQL} THEN
        RAISE EXCEPTION ${message};
    END IF;
    """;
}

val specialOps = mapOf(
        "require" to ::genRequire
)

fun genJSON(s: R_CallExpr): String {
    if (s.args.size != 1) throw Exception("Wrong number of parameters to json function")
    val arg = genExpr(s.args[0])
    return " (${arg}::jsonb) "
}

val specialFuns = mapOf(
    "json" to ::genJSON
)

fun genstatement(s: R_Statement): String {
    TODO()
}

fun genExpr(expr: R_Expr): String {
    return when (expr) {
        is R_VarExpr -> TODO() //"_" + expr.name
        is R_AtExpr -> TODO()
        is R_BinaryExpr -> TODO()
//        is RStringLiteralExpr -> "'${expr.value}'" // TODO: esscape
//        is RIntegerLiteralExpr -> expr.value.toString()
//        is RByteArrayLiteralExpr -> TODO()//"E'\\\\x${expr.literal.toHex()}'"
        is R_CallExpr -> TODO() // (
                //if (expr.fname == "json")
                //    genJSON(expr)
                //else throw Exception("unknown funcall"))
        else -> throw Exception("Expression type not supported")
    }
}

fun genOp(opDefinition: R_Operation): String {
    val args = opDefinition.params.map {
        val typename = getSQLType(it.type).getTypeName(dslCtx.configuration())
        "_${it.name} ${typename}"
    }.joinToString()
    val body = genstatement(opDefinition.body)

    return """
        CREATE FUNCTION ${opDefinition.name} (ctx gtx_ctx, ${args}) RETURNS BOOLEAN AS $$
        BEGIN
        ${body}
        RETURN TRUE;
        END;
        $$ LANGUAGE plpgsql;
        SELECT gtx_define_operation('${opDefinition.name}');
"""
}

fun genSql(sqlCtx: Rt_SqlContext, sysTables: Boolean, operations: Boolean): String {
    disableLogo

    val sqls = mutableListOf<String>()

    if (sysTables) {
        sqls += genSqlCreateSysTables()
    }

    sqls += genSqlForChain(sqlCtx)

    if (operations) {
        for (op in sqlCtx.module.operations.values) {
            sqls += genOp(op)
        }
    }

    return joinSqls(sqls)
}

fun genSqlCreateSysTables(): String {
    val sqls = mutableListOf<String>()
    sqls += CREATE_TABLE_BLOCKS
    sqls += CREATE_TABLE_TRANSACTIONS
    sqls += CREATE_TABLE_BLOCKCHAINS
    return joinSqls(sqls)
}

fun genSqlForChain(sqlCtx: Rt_SqlContext): String {
    val sqls = mutableListOf<String>()
    sqls += genRowidSql(sqlCtx.mainChainMapping)
    sqls += genSqlMetaData(sqlCtx)
    sqls += genSqlCreateClassTables(sqlCtx)
    return joinSqls(sqls)
}

private fun genSqlMetaData(sqlCtx: Rt_SqlContext): String {
    val sqls = mutableListOf<String>()

    sqls += String.format(CREATE_TABLE_META_CLASSES, sqlCtx.mainChainMapping.metaClassTable)
    sqls += String.format(CREATE_TABLE_META_ATTRIBUTES, sqlCtx.mainChainMapping.metaAttributesTable)

    val metaClasses = sqlCtx.module.topologicalClasses.filter { it.sqlMapping.autoCreateTable() }
    for ((i, cls) in metaClasses.withIndex()) {
        sqls += genMetaClassInserts(sqlCtx, i, cls)
    }

    return joinSqls(sqls)
}

private fun genMetaClassInserts(sqlCtx: Rt_SqlContext, id: Int, cls: R_Class): List<String> {
    val clsTable = DSL.tableByName(sqlCtx.mainChainMapping.metaClassTable)
    val attrTable = DSL.tableByName(sqlCtx.mainChainMapping.metaAttributesTable)

    val res = mutableListOf<String>()

    res += dslCtx.insertInto(clsTable,
            DSL.fieldByName("id"),
            DSL.fieldByName("name"),
            DSL.fieldByName("log")
    ).values(
            id,
            cls.name,
            cls.flags.log
    ).getSQL(true) + ";"

    for (attr in cls.attributes.values) {
        res += dslCtx.insertInto(attrTable,
                DSL.fieldByName("class_id"),
                DSL.fieldByName("name"),
                DSL.fieldByName("type")
        ).values(
                id,
                attr.name,
                attr.type.sqlAdapter.metaName(sqlCtx)
        ).getSQL(true) + ";"
    }

    return res
}

private fun genSqlCreateClassTables(sqlCtx: Rt_SqlContext): String {
    val sqls = mutableListOf<String>()

    for (cls in sqlCtx.module.topologicalClasses) {
        if (cls.sqlMapping.autoCreateTable()) {
            sqls += genClass(sqlCtx, cls)
        }
    }

    for (obj in sqlCtx.module.objects.values) {
        sqls += genClass(sqlCtx, obj.rClass)
    }

    return joinSqls(sqls)
}

private fun joinSqls(sqls: List<String>) = sqls.joinToString("\n") + "\n"
