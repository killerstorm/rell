package net.postchain.rell.model

import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_SqlMapper

class R_Key(val attribs: List<String>)

class R_Index(val attribs: List<String>)

class R_ClassFlags(
        val isObject: Boolean,
        val canCreate: Boolean,
        val canUpdate: Boolean,
        val canDelete: Boolean,
        val log: Boolean
)

class R_ClassSqlMapping(
        private val table: String,
        private val sysBlockTx: Boolean,
        val rowidColumn: String,
        val autoCreateTable: Boolean
) {
    fun table(sqlMapper: Rt_SqlMapper): String {
        if (sysBlockTx) {
            return table
        } else {
            val res = sqlMapper.mapName(table)
            return res
        }
    }

    fun extraWhere(b: SqlBuilder, sqlMapper: Rt_SqlMapper, alias: SqlTableAlias) {
        if (sysBlockTx) {
            sqlMapper.blockTxWhere(b, alias)
        }
    }
}

class R_ClassBody(val keys: List<R_Key>, val indexes: List<R_Index>, val attributes: Map<String, R_Attrib>)

class R_Class(val name: String, val flags: R_ClassFlags, val mapping: R_ClassSqlMapping) {
    private lateinit var body: R_ClassBody
    val keys: List<R_Key> get() = body.keys
    val indexes: List<R_Index> get() = body.indexes
    val attributes: Map<String, R_Attrib> get() = body.attributes

    fun setBody(body: R_ClassBody) {
        this.body = body
    }
}

class R_Object(val rClass: R_Class, val entityIndex: Int) {
    val type = R_ObjectType(this)

    fun insert(frame: Rt_CallFrame) {
        val sqlMapper = frame.entCtx.modCtx.globalCtx.sqlMapper
        val createAttrs = rClass.attributes.values.map { R_CreateExprAttr_Default(it) }
        val sql = R_CreateExpr.buildSql(sqlMapper, rClass, createAttrs, "0")
        sql.execute(frame)
    }
}
