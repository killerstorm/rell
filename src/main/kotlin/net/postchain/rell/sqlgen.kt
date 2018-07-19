package net.postchain.rell

import org.jooq.*
import org.jooq.impl.DSL
import org.jooq.impl.DSL.constraint
import org.jooq.impl.SQLDataType

val ctx = DSL.using(SQLDialect.POSTGRES);

fun getSQLType(t: RType): DataType<*> {
    when (t) {
        is RPrimitiveType -> return t.sqlType
        is RInstanceRefType -> return SQLDataType.BIGINT
        else -> throw Exception("SQL Type not implemented")
    }
}

val rowid_sql = """
    CREATE TABLE rowid_gen( last_value bigint not null);
    INSERT INTO rowid_gen (last_value) VALUES (0);
    CREATE FUNCTION make_rowid() RETURNS BIGINT AS
    'UPDATE rowid_gen SET last_value = last_value + 1 RETURNING last_value'
    LANGUAGE SQL;

    """

fun genclass(classDefinition: RClass): String {
    val t = ctx.createTable(classDefinition.name)
    var q = t.column("rowid", SQLDataType.BIGINT.nullable(false))
    val constraints = mutableListOf<Constraint>(constraint("PK_" + classDefinition.name).primaryKey("rowid"))
    for (attr in classDefinition.attributes) {
        if (attr.type is RInstanceRefType) {
            constraints.add(
                    constraint("${classDefinition.name}_${attr.name}_FK")
                            .foreignKey(attr.name).references(attr.type.name, "rowid")
            )
        }
        q = q.column(attr.name, getSQLType(attr.type).nullable(false))
    }
    for ((kidx, key) in classDefinition.keys.withIndex()) {
        constraints.add(constraint("K_${classDefinition.name}_${kidx}").unique(*key.attribs))
    }
    var ddl = q.constraints(*constraints.toTypedArray()).toString() + ";\n"

    for ((iidx, index) in classDefinition.indexes.withIndex()) {
        val index_sql = ctx.createIndex("IDX_${classDefinition.name}_${iidx}").on(classDefinition.name, *index.attribs);
        ddl += index_sql.toString() + ";\n";
    }

    return ddl
}

fun genAtConditions(r: RAtExpr): String {
    return r.attrConditions.map {
        val expr = genExpr(it.second)
        "(${r.rel.name}.${it.first.name} = ${expr})"
    }.joinToString(" AND ")
}

fun genAtExpr(r: RAtExpr): String {
    val conditions = genAtConditions(r)
    return "(SELECT rowid FROM ${r.rel.name} WHERE ${conditions})"
}

fun genBinOpExpr(r: RBinOpExpr): String {
    val left = genExpr(r.left)
    val right = genExpr(r.right)
    val op = when (r.op) {
        "=", "==" -> "="
        "+" -> "+"
        "-" -> "-"
        "!=" -> "<>"
        else -> throw Exception("Op ${r.op} not supported")
    }
    return "(${left} ${op} ${right})"
}

fun genRequire(s: RCallStatement): String {
    if (s.args.size == 0 || s.args.size > 2) throw Exception("Too many (or too little) arguments to require")
    val message = if (s.args.size == 2) genExpr(s.args[1]) else "'Require failed'"

    val condition = s.args[0]
    val condSQL : String
    if (condition.type is RBooleanType)
        condSQL = "NOT ${genExpr(condition)}"
    else if (condition.type is RInstanceRefType)
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

fun genUpdateStatement(s: RUpdateStatement): String {
    val conditions = genAtConditions(s.atExpr)
    val setAttrs = s.setAttrs.map {
        "${it.attr.name} = ${genExpr(it.expr)}"
    }.joinToString()
    return "UPDATE ${s.atExpr.rel.name} SET ${setAttrs} WHERE ${conditions};"
}

fun genstatement(s: RStatement): String {
    return when (s) {
        is RCreateStatement -> {
            val attrNames = "rowid, " + (s.attrs.map { it.attr.name }.joinToString())
            val attrValues = "make_rowid(), " + s.attrs.map {
                genExpr(it.expr)
            }.joinToString()
            "INSERT INTO ${s.rclass.name} (${attrNames}) VALUES (${attrValues});"
        }
        is RUpdateStatement -> genUpdateStatement(s)
        is RDeleteStatement -> {
            val conditions = genAtConditions(s.atExpr)
            "DELETE FROM ${s.atExpr.rel.name} WHERE ${conditions}"
        }
        is RCallStatement -> {
            if (s.fname in specialOps)
                return specialOps[s.fname]!!(s)
            else {
                throw Exception("Cannot call")
            }
        }
        else -> throw Exception("Statement not supported")
    }
}

fun genExpr(expr: RExpr): String {
    return when (expr) {
        is RVarRef -> "_" + expr._var.name
        is RAtExpr -> genAtExpr(expr)
        is RBinOpExpr -> genBinOpExpr(expr)
        is RStringLiteral -> "'${expr.literal}'" // TODO: esscape
        is RIntegerLiteral -> expr.literal.toString()
        is RByteALiteral -> "E'\\\\x${expr.literal.toHex()}'"
        else -> throw Exception("Expression type not supported")
    }
}

fun genop(opDefinition: ROperation): String {
    val args = opDefinition.params.map {
        val typename = getSQLType(it.type).getTypeName(ctx.configuration())
        "_${it.name} ${typename}"
    }.joinToString()
    val statements = opDefinition.statements.map(::genstatement).joinToString("\n")

    return """
        CREATE FUNCTION ${opDefinition.name} (ctx gtx_ctx, ${args}) RETURNS BOOLEAN AS $$
        BEGIN
        ${statements}
        RETURN TRUE;
        END;
        $$ LANGUAGE plpgsql;
        SELECT gtx_define_operation('${opDefinition.name}');
"""
}

fun gensql(model: RModule): String {
    var s = rowid_sql;
    for (rel in model.relations) {
        when (rel) {
            is RClass -> s += genclass(rel)
        }
    }
    for (op in model.operations) {
        s += genop(op)
    }
    return s
}