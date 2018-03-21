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

val sys = """
    CREATE TABLE rowid_gen( last_value bigint not null);
    INSERT INTO rowid_gen (last_value) VALUES (0);
    CREATE FUNCTION make_rowid() RETURNS BIGINT AS $$
    BEGIN
      UPDATE rowid_gen SET last_value = last_value + 1;
    END;
    $$ language plpgsql;
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

fun genAtExpr(r: RAtExpr): String {
    return "(SELECT rowid FROM ${r.rel.name} WHERE ${r.rel.name}.${r.attr.name} = _${r.varRef._var.name})"
}

fun genstatement(s: RStatement): String {
    return when (s) {
        is RCreateStatement -> {
            val attrNames = "rowid, " + (s.attrs.map { it.attr.name }.joinToString())
            val attrValues = "make_rowid(), " + s.attrs.map {
                val expr = it.expr
                when (expr) {
                    is RVarRef -> "_" + expr._var.name
                    is RAtExpr -> genAtExpr(expr)
                }
            }.joinToString()
            "INSERT INTO ${s.rclass.name} (${attrNames}) VALUES (${attrValues})"
        }
        else -> throw Exception("Statement not supported")
    }
}


fun genop(opDefinition: ROperation): String {
    val args = opDefinition.params.map {
        val typename = getSQLType(it.type).getTypeName(ctx.configuration())
        "_${it.name} ${typename}"
    }.joinToString()
    val statements = opDefinition.statements.map(::genstatement).joinToString(";\n")

    return """
        CREATE FUNCTION ${opDefinition.name} (ctx gtx_ctx, ${args}) RETURNS BOOLEAN AS $$
        ${statements}
        RETURN TRUE;
        $$ LANGUAGE plpgsql;
        SELECT gtx_define_operation('${opDefinition.name}');
"""
}

fun gensql(model: RModule): String {
    var s = ""
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