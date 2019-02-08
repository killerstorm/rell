package net.postchain.rell.sql

import net.postchain.rell.model.*
import net.postchain.rell.runtime.Rt_JsonValue
import org.jooq.*
import org.jooq.impl.DSL
import org.jooq.impl.DSL.constraint
import org.jooq.impl.SQLDataType

val ctx = DSL.using(SQLDialect.POSTGRES);

fun getSQLType(t: R_Type): DataType<*> {
    when (t) {
        is R_PrimitiveType -> return t.sqlType
        is R_ClassType -> return SQLDataType.BIGINT
        else -> throw Exception("SQL Type not implemented")
    }
}

private val ROWID_SQL = """
    CREATE TABLE rowid_gen( last_value bigint not null);
    INSERT INTO rowid_gen (last_value) VALUES (0);
    CREATE FUNCTION $MAKE_ROWID_FUNCTION() RETURNS BIGINT AS
    'UPDATE rowid_gen SET last_value = last_value + 1 RETURNING last_value'
    LANGUAGE SQL;
    """

private val CREATE_TABLE_BLOCKS = """
CREATE TABLE blocks(
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
CREATE TABLE transactions(
    tx_iid BIGSERIAL PRIMARY KEY,
    chain_id bigint NOT NULL,
    tx_rid bytea NOT NULL,
    tx_data bytea NOT NULL,
    tx_hash bytea NOT NULL,
    block_iid bigint NOT NULL REFERENCES blocks(block_iid),
    UNIQUE (chain_id, tx_rid)
);
""".trimIndent()

private fun genClass(classDefinition: R_Class): String {
    val mapping = classDefinition.mapping
    val attrs = classDefinition.attributes.values

    val t = ctx.createTable(mapping.table)

    val constraints = mutableListOf<Constraint>()
    constraints.add(constraint("PK_" + mapping.table).primaryKey(mapping.rowidColumn))
    constraints += genAttrConstraints(mapping.table, attrs)

    var q = t.column(mapping.rowidColumn, SQLDataType.BIGINT.nullable(false))
    q = genAttrColumns(attrs, q)

    for ((kidx, key) in classDefinition.keys.withIndex()) {
        constraints.add(constraint("K_${mapping.table}_${kidx}").unique(*key.attribs.toTypedArray()))
    }

    var ddl = q.constraints(*constraints.toTypedArray()).toString() + ";\n"

    val jsonAttribSet = attrs.filter { it.type is R_JSONType }.map { it.name }.toSet()

    for ((iidx, index) in classDefinition.indexes.withIndex()) {
        val index_sql : String;
        val tableName = mapping.table
        if (index.attribs.size == 1 && jsonAttribSet.contains(index.attribs[0])) {
            val attrName = index.attribs[0]
            index_sql = "CREATE INDEX \"IDX_${tableName}_${iidx}\" ON \"$tableName\" USING gin (\"${attrName}\" jsonb_path_ops)"
        } else {
            index_sql = (ctx.createIndex("IDX_${tableName}_${iidx}").on(tableName, *index.attribs.toTypedArray())).toString();
        }
        ddl += index_sql + ";\n";
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

private fun genAttrConstraints(sqlTable: String, attrs: Collection<R_Attrib>): List<Constraint> {
    val constraints = mutableListOf<Constraint>()

    for (attr in attrs) {
        if (attr.type is R_ClassType) {
            val refCls = attr.type.rClass
            val constraint = constraint("${sqlTable}_${attr.sqlMapping}_FK")
                    .foreignKey(attr.sqlMapping)
                    .references(refCls.mapping.table, refCls.mapping.rowidColumn)
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

fun genop(opDefinition: R_Operation): String {
    val args = opDefinition.params.map {
        val typename = getSQLType(it.type).getTypeName(ctx.configuration())
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

fun gensql(model: R_Module, blockTable: Boolean, operations: Boolean): String {
    System.setProperty("org.jooq.no-logo", "true")

    var s = ""
    s += ROWID_SQL
    if (blockTable) {
        s += CREATE_TABLE_BLOCKS
        s += CREATE_TABLE_TRANSACTIONS
    }

    for (cls in model.classes.values) {
        if (cls.mapping.autoCreateTable) {
            s += genClass(cls)
        }
    }

    for (obj in model.objects.values) {
        s += genClass(obj.rClass)
    }

    if (operations) {
        for (op in model.operations.values) {
            s += genop(op)
        }
    }

    return s
}
