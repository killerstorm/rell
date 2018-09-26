package net.postchain.rell.sqlgen

import net.postchain.rell.model.*
import net.postchain.rell.toHex
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

    val jsonAttribSet = mutableSetOf<String>()

    for (attr in classDefinition.attributes) {
        if (attr.type is RInstanceRefType) {
            constraints.add(
                    constraint("${classDefinition.name}_${attr.name}_FK")
                            .foreignKey(attr.name).references(attr.type.name, "rowid")
            )
        } else if (attr.type is RJSONType) {
            jsonAttribSet.add(attr.name)
        }
        q = q.column(attr.name, getSQLType(attr.type).nullable(false))
    }
    for ((kidx, key) in classDefinition.keys.withIndex()) {
        constraints.add(constraint("K_${classDefinition.name}_${kidx}").unique(*key.attribs))
    }
    var ddl = q.constraints(*constraints.toTypedArray()).toString() + ";\n"

    for ((iidx, index) in classDefinition.indexes.withIndex()) {
        val index_sql : String;
        if (index.attribs.size == 1 && jsonAttribSet.contains(index.attribs[0])) {
            val attrName = index.attribs[0]
            index_sql = "CREATE INDEX \"IDX_${classDefinition.name}_${iidx}\" ON \"${classDefinition.name}\" USING gin (\"${attrName}\" jsonb_path_ops)"
        } else {
            index_sql = (ctx.createIndex("IDX_${classDefinition.name}_${iidx}").on(classDefinition.name, *index.attribs)).toString();
        }
        ddl += index_sql + ";\n";
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

fun genRequire(s: RFunCallExpr): String {
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

fun genJSON(s: RFunCallExpr): String {
    if (s.args.size != 1) throw Exception("Wrong number of parameters to json function")
    val arg = genExpr(s.args[0])
    return " (${arg}::jsonb) "
}

val specialFuns = mapOf(
    "json" to ::genJSON
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
            "DELETE FROM ${s.atExpr.rel.name} WHERE ${conditions};"
        }
        is RCallStatement -> {
            if (s.expr.fname in specialOps)
                return specialOps[s.expr.fname]!!(s.expr)
            else {
                throw Exception("Cannot call")
            }
        }
        else -> throw Exception("Statement not supported")
    }
}

fun genExpr(expr: RExpr): String {
    return when (expr) {
        is RVarRef -> "_" + expr.attr.name
        is RAtExpr -> genAtExpr(expr)
        is RBinOpExpr -> genBinOpExpr(expr)
        is RStringLiteral -> "'${expr.literal}'" // TODO: esscape
        is RIntegerLiteral -> expr.literal.toString()
        is RByteALiteral -> "E'\\\\x${expr.literal.toHex()}'"
        is RFunCallExpr -> (
                if (expr.fname == "json")
                    genJSON(expr)
                else throw Exception("unknown funcall"))
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
