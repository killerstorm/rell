/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.model

import net.postchain.gtv.Gtv
import net.postchain.rell.compiler.C_CompilerPass
import net.postchain.rell.compiler.C_LateGetter
import net.postchain.rell.compiler.C_LateInit
import net.postchain.rell.runtime.Rt_CallFrame
import net.postchain.rell.runtime.Rt_EnumValue
import net.postchain.rell.runtime.Rt_Value
import net.postchain.rell.runtime.toGtv
import net.postchain.rell.utils.checkEquals
import net.postchain.rell.utils.toGtv

sealed class R_KeyIndex(val attribs: List<String>) {
    fun toMetaGtv(): Gtv {
        return mapOf(
                "attributes" to attribs.toGtv()
        ).toGtv()
    }
}

class R_Key(attribs: List<String>): R_KeyIndex(attribs)
class R_Index(attribs: List<String>): R_KeyIndex(attribs)

class R_EntityFlags(
        val isObject: Boolean,
        val canCreate: Boolean,
        val canUpdate: Boolean,
        val canDelete: Boolean,
        val gtv: Boolean,
        val log: Boolean
)

class R_EntityBody(val keys: List<R_Key>, val indexes: List<R_Index>, val attributes: Map<String, R_Attribute>)

class R_ExternalEntity(val chain: R_ExternalChainRef, val metaCheck: Boolean)

class R_EntityDefinition(
        names: R_DefinitionNames,
        initFrameGetter: C_LateGetter<R_CallFrame>,
        val mountName: R_MountName,
        val flags: R_EntityFlags,
        val sqlMapping: R_EntitySqlMapping,
        val external: R_ExternalEntity?,
        val mirrorStructs: R_MirrorStructs
): R_Definition(names, initFrameGetter) {
    val metaName = mountName.str()

    val type = R_EntityType(this)

    private val bodyLate = C_LateInit(C_CompilerPass.MEMBERS, ERROR_BODY)

    val keys: List<R_Key> get() = bodyLate.get().keys
    val indexes: List<R_Index> get() = bodyLate.get().indexes
    val attributes: Map<String, R_Attribute> get() = bodyLate.get().attributes

    fun setBody(body: R_EntityBody) {
        bodyLate.set(body)
    }

    fun attribute(name: String): R_Attribute {
        val attr = attributes[name]
        return attr ?: throw IllegalStateException("Entity '$appLevelName' has no attribute '$name'")
    }

    override fun toMetaGtv() = toMetaGtv(true)

    fun toMetaGtv(full: Boolean): Gtv {
        val map = mutableMapOf(
                "mount" to mountName.str().toGtv(),
                "attributes" to attributes.mapValues { it.value.toMetaGtv() }.toGtv()
        )

        if (full) {
            map["log"] = flags.log.toGtv()
            map["keys"] = keys.map { it.toMetaGtv() }.toGtv()
            map["indexes"] = indexes.map { it.toMetaGtv() }.toGtv()
        }

        return map.toGtv()
    }

    companion object {
        private val ERROR_BODY = R_EntityBody(keys = listOf(), indexes = listOf(), attributes = mapOf())
    }
}

class R_ObjectDefinition(names: R_DefinitionNames, val rEntity: R_EntityDefinition): R_Definition(names, rEntity.initFrameGetter) {
    val type = R_ObjectType(this)

    fun insert(frame: Rt_CallFrame) {
        val createAttrs = rEntity.attributes.values.map {
            val expr = R_AttributeDefaultValueExpr(it, initFrameGetter, null)
            R_CreateExprAttr(it, expr)
        }
        val createValues = createAttrs.map { it.attr to it.evaluate(frame) }
        val sql = R_CreateExpr.buildSql(frame.defCtx.sqlCtx, rEntity, createValues, "0")
        sql.execute(frame.sqlExec)
    }

    override fun toMetaGtv() = rEntity.toMetaGtv(false)
}

class R_StructFlags(val typeFlags: R_TypeFlags, val cyclic: Boolean, val infinite: Boolean)

class R_Struct(
        val name: String,
        val typeMetaGtv: Gtv,
        val initFrameGetter: C_LateGetter<R_CallFrame>,
        val mirrorStructs: R_MirrorStructs?
) {
    private val bodyLate = C_LateInit(C_CompilerPass.MEMBERS, DEFAULT_BODY)
    private val flagsLate = C_LateInit(C_CompilerPass.APPDEFS, DEFAULT_STRUCT_FLAGS)

    val attributes: Map<String, R_Attribute> get() = bodyLate.get().attrMap
    val attributesList: List<R_Attribute> get() = bodyLate.get().attrList
    val flags: R_StructFlags get() = flagsLate.get()

    val type = R_StructType(this)
    val virtualType = R_VirtualStructType(type)

    fun setAttributes(attrs: Map<String, R_Attribute>) {
        val attrsList = attrs.values.toList()
        attrsList.withIndex().forEach { (idx, attr) -> checkEquals(attr.index, idx) }
        val attrMutable = attrs.values.any { it.mutable }
        bodyLate.set(R_StructBody(attrs, attrsList, attrMutable), true)
    }

    fun setFlags(flags: R_StructFlags) {
        flagsLate.set(flags)
    }

    fun isDirectlyMutable() = bodyLate.get().attrMutable

    fun toMetaGtv(): Gtv {
        return mapOf(
                "attributes" to attributes.mapValues { it.value.toMetaGtv() }.toGtv()
        ).toGtv()
    }

    override fun toString() = name

    private class R_StructBody(
            val attrMap: Map<String, R_Attribute>,
            val attrList: List<R_Attribute>,
            val attrMutable: Boolean
    )

    companion object {
        private val DEFAULT_BODY = R_StructBody(attrMap = mapOf(), attrList = listOf(), attrMutable = false)
        private val DEFAULT_TYPE_FLAGS = R_TypeFlags(mutable = false, gtv = R_GtvCompatibility(true, true), virtualable = true)
        private val DEFAULT_STRUCT_FLAGS = R_StructFlags(typeFlags = DEFAULT_TYPE_FLAGS, cyclic = false, infinite = false)
    }
}

class R_MirrorStructs(
        names: R_DefinitionNames,
        initFrameGetter: C_LateGetter<R_CallFrame>,
        defType: String,
        val operation: R_MountName?
) {
    val immutable = createStruct(names, initFrameGetter, defType, false)
    val mutable = createStruct(names, initFrameGetter, defType, true)

    fun getStruct(mutable: Boolean) = if (mutable) this.mutable else this.immutable

    private fun createStruct(
            names: R_DefinitionNames,
            initFrameGetter: C_LateGetter<R_CallFrame>,
            defType: String,
            mutable: Boolean
    ): R_Struct {
        val mutableStr = if (mutable) "mutable " else ""
        val structName = "struct<$mutableStr${names.appLevelName}>"
        val structMetaGtv = mapOf(
                "type" to "struct".toGtv(),
                "definition_type" to defType.toGtv(),
                "definition" to names.appLevelName.toGtv(),
                "mutable" to mutable.toGtv()
        ).toGtv()
        return R_Struct(structName, structMetaGtv, initFrameGetter, mirrorStructs = this)
    }
}

class R_StructDefinition(names: R_DefinitionNames, val struct: R_Struct): R_Definition(names, struct.initFrameGetter) {
    val type = struct.type

    override fun toMetaGtv() = struct.toMetaGtv()
}

class R_EnumAttr(val name: String, val value: Int) {
    // Currently returning an empty map, in the future there may be some values.
    fun toMetaGtv() = mapOf(
            "value" to value.toGtv()
    ).toGtv()
}

class R_EnumDefinition(
        names: R_DefinitionNames,
        initFrameGetter: C_LateGetter<R_CallFrame>,
        val attrs: List<R_EnumAttr>
): R_Definition(names, initFrameGetter) {
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

    override fun toMetaGtv(): Gtv {
        return mapOf(
                "values" to attrMap.mapValues { it.value.toMetaGtv() }.toGtv()
        ).toGtv()
    }
}
