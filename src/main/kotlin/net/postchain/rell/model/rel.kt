package net.postchain.rell.model

import net.postchain.rell.runtime.*
import java.lang.IllegalStateException

class R_Key(val attribs: List<String>)

class R_Index(val attribs: List<String>)

class R_ClassFlags(
        val isObject: Boolean,
        val canCreate: Boolean,
        val canUpdate: Boolean,
        val canDelete: Boolean,
        val gtx: Boolean,
        val log: Boolean
)

class R_ClassBody(val keys: List<R_Key>, val indexes: List<R_Index>, val attributes: Map<String, R_Attrib>)

class R_ExternalClass(val chain: R_ExternalChain, val externalName: String, val metaCheck: Boolean)

class R_Class(val name: String, val flags: R_ClassFlags, val sqlMapping: R_ClassSqlMapping, val external: R_ExternalClass?) {
    private lateinit var body: R_ClassBody
    val keys: List<R_Key> get() = body.keys
    val indexes: List<R_Index> get() = body.indexes
    val attributes: Map<String, R_Attrib> get() = body.attributes

    fun setBody(body: R_ClassBody) {
        this.body = body
    }

    fun attribute(name: String): R_Attrib {
        val attr = attributes[name]
        return attr ?: throw IllegalStateException("Class '${this.name}' has no attribute '$name'")
    }
}

class R_Object(val rClass: R_Class, val entityIndex: Int) {
    val type = R_ObjectType(this)

    fun insert(frame: Rt_CallFrame) {
        val createAttrs = rClass.attributes.values.map { R_CreateExprAttr_Default(it) }
        val sql = R_CreateExpr.buildSql(frame.entCtx.modCtx.sqlCtx, rClass, createAttrs, "0")
        sql.execute(frame)
    }
}
