package net.postchain.rell.model

import net.postchain.rell.parser.C_CompilerPass
import net.postchain.rell.parser.C_LateInit
import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_EnumValue
import net.postchain.rell.runtime.Rt_Value

class R_Key(val attribs: List<String>)

class R_Index(val attribs: List<String>)

class R_EntityFlags(
        val isObject: Boolean,
        val canCreate: Boolean,
        val canUpdate: Boolean,
        val canDelete: Boolean,
        val gtv: Boolean,
        val log: Boolean
)

class R_EntityBody(val keys: List<R_Key>, val indexes: List<R_Index>, val attributes: Map<String, R_Attrib>)

class R_ExternalEntity(val chain: R_ExternalChainRef, val metaCheck: Boolean)

class R_Entity(
        names: R_DefinitionNames,
        val mountName: R_MountName,
        val flags: R_EntityFlags,
        val sqlMapping: R_EntitySqlMapping,
        val external: R_ExternalEntity?
): R_Definition(names) {
    val metaName = mountName.str()

    val type = R_EntityType(this)

    private val body = C_LateInit(C_CompilerPass.MEMBERS, ERROR_BODY)

    val keys: List<R_Key> get() = body.get().keys
    val indexes: List<R_Index> get() = body.get().indexes
    val attributes: Map<String, R_Attrib> get() = body.get().attributes

    fun setBody(body: R_EntityBody) {
        this.body.set(body)
    }

    fun attribute(name: String): R_Attrib {
        val attr = attributes[name]
        return attr ?: throw IllegalStateException("Entity '$appLevelName' has no attribute '$name'")
    }

    companion object {
        private val ERROR_BODY = R_EntityBody(keys = listOf(), indexes = listOf(), attributes = mapOf())
    }
}

class R_Object(names: R_DefinitionNames, val rEntity: R_Entity): R_Definition(names) {
    val type = R_ObjectType(this)

    fun insert(frame: Rt_CallFrame) {
        val createAttrs = rEntity.attributes.values.map { R_CreateExprAttr_Default(it) }
        val sql = R_CreateExpr.buildSql(frame.defCtx.sqlCtx, rEntity, createAttrs, "0")
        sql.execute(frame)
    }
}

class R_StructFlags(val typeFlags: R_TypeFlags, val cyclic: Boolean, val infinite: Boolean)

class R_Struct(names: R_DefinitionNames): R_Definition(names) {
    private val bodyLate = C_LateInit(C_CompilerPass.MEMBERS, DEFAULT_BODY)
    private val flagsLate = C_LateInit(C_CompilerPass.STRUCTS, DEFAULT_STRUCT_FLAGS)

    val attributes: Map<String, R_Attrib> get() = bodyLate.get().attrMap
    val attributesList: List<R_Attrib> get() = bodyLate.get().attrList
    val flags: R_StructFlags get() = flagsLate.get()

    val type = R_StructType(this)
    val virtualType = R_VirtualStructType(type)

    fun setAttributes(attrs: Map<String, R_Attrib>) {
        val attrsList = attrs.values.toList()
        attrsList.withIndex().forEach { (idx, attr) -> check(attr.index == idx) }
        val attrMutable = attrs.values.any { it.mutable }
        bodyLate.set(R_StructBody(attrs, attrsList, attrMutable))
    }

    fun setFlags(flags: R_StructFlags) {
        flagsLate.set(flags)
    }

    fun isDirectlyMutable() = bodyLate.get().attrMutable

    private class R_StructBody(
            val attrMap: Map<String, R_Attrib>,
            val attrList: List<R_Attrib>,
            val attrMutable: Boolean
    )

    companion object {
        private val DEFAULT_BODY = R_StructBody(attrMap = mapOf(), attrList = listOf(), attrMutable = false)
        private val DEFAULT_TYPE_FLAGS = R_TypeFlags(mutable = false, gtv = R_GtvCompatibility(true, true), virtualable = true)
        private val DEFAULT_STRUCT_FLAGS = R_StructFlags(typeFlags = DEFAULT_TYPE_FLAGS, cyclic = false, infinite = false)
    }
}

class R_EnumAttr(val name: String, val value: Int)

class R_Enum(names: R_DefinitionNames, val attrs: List<R_EnumAttr>): R_Definition(names) {
    val type = R_EnumType(this)

    private val attrMap = attrs.map { Pair(it.name, it) }.toMap()
    private val rtValues = attrs.map { Rt_EnumValue(type, it) }

    fun attr(name: String): R_EnumAttr? {
        return attrMap[name]
    }

    fun attr(value: Long): R_EnumAttr? {
        if (value < 0 || value >= attrs.size) {
            return null
        }
        return attrs[value.toInt()]
    }

    fun values(): List<Rt_Value> {
        return rtValues
    }
}
