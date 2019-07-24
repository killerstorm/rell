package net.postchain.rell.sql

import net.postchain.rell.model.R_Attrib
import net.postchain.rell.model.R_Class
import net.postchain.rell.runtime.Rt_ChainSqlMapping
import net.postchain.rell.runtime.Rt_Messages
import net.postchain.rell.runtime.Rt_ModuleContext
import net.postchain.rell.runtime.Rt_SqlContext
import org.jooq.conf.ParamType
import org.jooq.impl.DSL

private val MSG_BROKEN_META = "Broken metadata"

object SqlMeta {
    private val CREATE_TABLE_META_CLASSES = """
    CREATE TABLE "%s"(
        "id" INT NOT NULL PRIMARY KEY,
        "name" TEXT NOT NULL UNIQUE,
        "type" TEXT NOT NULL,
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

    fun checkMetaTablesExisting(mapping: Rt_ChainSqlMapping, tables: Map<String, SqlTable>, msgs: Rt_Messages): Boolean {
        val metaTables = metaTables(mapping)
        val metaExists = metaTables.any { it in tables }
        if (metaExists) {
            val metaMissing = metaTables.filter { it !in tables }
            msgs.errorIfNotEmpty(metaMissing, "meta:notables", "$MSG_BROKEN_META: missing table(s)")
        }

        val clsChk = SqlTableChecker(tables, mapping.metaClassTable)
        clsChk.checkColumn("id", "int4")
        clsChk.checkColumn("name", "text")
        clsChk.checkColumn("type", "text")
        clsChk.checkColumn("log", "bool")
        clsChk.finish(msgs)

        val attrChk = SqlTableChecker(tables, mapping.metaAttributesTable)
        attrChk.checkColumn("class_id", "int4")
        attrChk.checkColumn("name", "text")
        attrChk.checkColumn("type", "text")
        attrChk.finish(msgs)

        return metaExists
    }

    private fun metaTables(mapping: Rt_ChainSqlMapping): List<String> {
        return listOf(mapping.metaClassTable, mapping.metaAttributesTable)
    }

    fun loadMetaData(sqlExec: SqlExecutor, mapping: Rt_ChainSqlMapping, msgs: Rt_Messages): Map<String, MetaClass> {
        val metaClasses = selectMetaClasses(mapping, sqlExec, msgs)
        val metaAttrs = selectMetaAttrs(mapping, sqlExec, msgs)
        msgs.checkErrors()

        val clsMap = metaClasses.map { Pair(it.id, it) }.toMap()
        val attrMap = metaAttrs.groupBy { it.classId }

        for (classId in attrMap.keys.sorted()) {
            if (classId !in clsMap) {
                msgs.error("meta:attrnoclass:$classId", "$MSG_BROKEN_META: attributes without class (class_id = $classId)")
            }
        }
        msgs.checkErrors()

        val res = mutableMapOf<String, MetaClass>()
        for (clsRec in metaClasses) {
            val type = decodeClassType(clsRec, msgs)
            if (type == null) continue
            val attrs = attrMap[clsRec.id] ?: listOf()
            val resAttrMap = attrs.map { Pair(it.name, MetaAttr(it.name, it.type)) }.toMap()
            res[clsRec.name] = MetaClass(clsRec.id, clsRec.name, type, clsRec.log, resAttrMap)
        }

        return res
    }

    private fun decodeClassType(rec: RecMetaClass, msgs: Rt_Messages): MetaClassType? {
        for (type in MetaClassType.values()) {
            if (rec.type == type.code) {
                return type
            }
        }

        msgs.error("meta:cls:bad_type:${rec.id}:${rec.name}:${rec.type}",
                "$MSG_BROKEN_META: Invalid type of meta-class '${rec.name}' (ID = ${rec.id}): '${rec.type}'")
        return null
    }

    private fun selectMetaClasses(mapping: Rt_ChainSqlMapping, sqlExec: SqlExecutor, msgs: Rt_Messages): List<RecMetaClass> {
        val table = mapping.metaClassTable
        val res = mutableListOf<RecMetaClass>()

        sqlExec.executeQuery("""SELECT T."id", T."name", T."type", T."log" FROM "$table" T ORDER BY T."id";""", {}) { rs ->
            val id = rs.getInt(1)
            val name = rs.getString(2)
            val type = rs.getString(3)
            val log = rs.getBoolean(4)
            res.add(RecMetaClass(id, name, type, log))
        }

        checkUniqueKeys(msgs, table, res) { "${it.id}" }
        checkUniqueKeys(msgs, table, res) { it.name }

        return res
    }

    private fun selectMetaAttrs(mapping: Rt_ChainSqlMapping, sqlExec: SqlExecutor, msgs: Rt_Messages): List<RecMetaAttr> {
        val table = mapping.metaAttributesTable
        val res = mutableListOf<RecMetaAttr>()
        sqlExec.executeQuery("""SELECT T."class_id", T."name", T."type" FROM "$table" T ORDER BY T."class_id", T."name";""", {}) { rs ->
            val classId = rs.getInt(1)
            val name = rs.getString(2)
            val type = rs.getString(3)
            res.add(RecMetaAttr(classId, name, type))
        }
        checkUniqueKeys(msgs, table, res) { "${it.classId}:${it.name}" }
        return res
    }

    private fun <T> checkUniqueKeys(msgs: Rt_Messages, table: String, list: List<T>, getter: (T) -> String) {
        val unique = mutableSetOf<String>()
        val dup = mutableSetOf<String>()
        for (rec in list) {
            val key = getter(rec)
            if (!unique.add(key)) {
                dup.add(key)
            }
        }
        msgs.errorIfNotEmpty(dup, "meta:dupkeys:$table", "$MSG_BROKEN_META: duplicate value(s) in table $table")
    }

    fun checkDataTables(
            modCtx: Rt_ModuleContext,
            tables: Map<String, SqlTable>,
            metaData: Map<String, MetaClass>,
            msgs: Rt_Messages
    ) {
        val mapping = modCtx.sqlCtx.mainChainMapping

        val metaTables = metaData.keys.map { mapping.fullName(it) }.toSet()
        val missingTables = metaTables.filter { it !in tables }

        val dataTables = tables.keys - metaTables(mapping)
        val missingClasses = dataTables.filter { it !in metaTables }

        msgs.errorIfNotEmpty(missingTables, "meta:no_data_tables", "Missing tables for existing metadata classes")
        msgs.errorIfNotEmpty(missingClasses, "meta:no_meta_classes", "Missing metadata classes for existing tables")

        for (cls in metaData.values) {
            val table = mapping.fullName(cls.name)
            val sqlTable = tables[table]
            if (sqlTable != null) {
                checkDataTable(table, sqlTable, cls, msgs)
            }
        }
    }

    private fun checkDataTable(table: String, sqlTable: SqlTable, metaCls: MetaClass, msgs: Rt_Messages) {
        val missingCols = (metaCls.attrs.keys + listOf(ROWID_COLUMN)).filter { it !in sqlTable.cols }
        val missingAttrs = (sqlTable.cols.keys - listOf(ROWID_COLUMN)).filter { it !in metaCls.attrs }

        msgs.errorIfNotEmpty(missingCols, "meta:no_data_columns:$table",
                "Missing columns for existing meta attributes in table $table")

        msgs.errorIfNotEmpty(missingAttrs, "meta:no_meta_attrs:$table",
                "Missing meta attributes for existing columns in table $table")
    }

    fun genSqlMetaData(sqlCtx: Rt_SqlContext): String {
        val sqls = mutableListOf<String>()
        sqls += genMetaTablesCreate(sqlCtx)

        val metaClasses = sqlCtx.module.topologicalClasses.filter { it.sqlMapping.autoCreateTable() }
        for ((i, cls) in metaClasses.withIndex()) {
            sqls += genMetaClassInserts(sqlCtx, i, cls, MetaClassType.CLASS)
        }

        return SqlGen.joinSqls(sqls)
    }

    fun genMetaTablesCreate(sqlCtx: Rt_SqlContext): List<String> {
        val sqls = mutableListOf<String>()
        sqls += String.format(CREATE_TABLE_META_CLASSES, sqlCtx.mainChainMapping.metaClassTable)
        sqls += String.format(CREATE_TABLE_META_ATTRIBUTES, sqlCtx.mainChainMapping.metaAttributesTable)
        return sqls
    }

    fun genMetaClassInserts(sqlCtx: Rt_SqlContext, classId: Int, cls: R_Class, clsType: MetaClassType): List<String> {
        val sqls = mutableListOf<String>()

        val clsTable = DSL.table(DSL.name(sqlCtx.mainChainMapping.metaClassTable))

        sqls += SqlGen.DSL_CTX.insertInto(clsTable,
                DSL.field("id"),
                DSL.field("name"),
                DSL.field("type"),
                DSL.field("log")
        ).values(
                classId,
                cls.name,
                clsType.code,
                cls.flags.log
        ).getSQL(ParamType.INLINED) + ";"

        sqls += genMetaAttrsInserts(sqlCtx, classId, cls.attributes.values)

        return sqls
    }

    fun genMetaAttrsInserts(sqlCtx: Rt_SqlContext, classId: Int, attrs: Collection<R_Attrib>): List<String> {
        val sqls = mutableListOf<String>()

        val attrTable = DSL.table(DSL.name(sqlCtx.mainChainMapping.metaAttributesTable))

        for (attr in attrs) {
            sqls += SqlGen.DSL_CTX.insertInto(attrTable,
                    DSL.field("class_id"),
                    DSL.field("name"),
                    DSL.field("type")
            ).values(
                    classId,
                    attr.name,
                    attr.type.sqlAdapter.metaName(sqlCtx)
            ).getSQL(ParamType.INLINED) + ";"
        }

        return sqls
    }
}

private class SqlTableChecker(private val tables: Map<String, SqlTable>, private val table: String) {
    private val missingCols = mutableListOf<String>()
    private val checkedCols = mutableListOf<String>()
    private val diffCols = mutableMapOf<String, Pair<String, String>>()

    fun checkColumn(name: String, type: String) {
        val t = tables[table]
        t ?: return

        checkedCols.add(name)

        val col = t.cols[name]
        if (col == null) {
            missingCols.add(name)
        } else if (col.type != type) {
            diffCols[name] = Pair(type, col.type)
        }
    }

    fun finish(msgs: Rt_Messages) {
        val t = tables[table]
        t ?: return

        msgs.errorIfNotEmpty(missingCols, "meta:nocols:$table", "$MSG_BROKEN_META: missing column(s) in table $table")

        val extraCols = (t.cols.keys - checkedCols).toList()
        msgs.errorIfNotEmpty(extraCols, "meta:extracols:$table", "$MSG_BROKEN_META: wrong column(s) in table $table")

        for ((col, types) in diffCols) {
            val (expType, actType) = types
            msgs.error("meta:coltype:$table:$col:$expType:$actType",
                    "$MSG_BROKEN_META: wrong type of column $table.$col: $actType instead of $expType")
        }
    }
}

private class RecMetaClass(val id: Int, val name: String, val type: String, val log: Boolean)
private class RecMetaAttr(val classId: Int, val name: String, val type: String)

enum class MetaClassType(val code: String, val en: String) {
    CLASS("class", "class"),
    OBJECT("object", "object")
    ;
}

class MetaClass(
        val id: Int,
        val name: String,
        val type: MetaClassType,
        val log: Boolean,
        val attrs: Map<String, MetaAttr>
)

class MetaAttr(val name: String, val type: String)