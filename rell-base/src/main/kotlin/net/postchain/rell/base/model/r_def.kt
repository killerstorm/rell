/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.model

import net.postchain.gtv.Gtv
import net.postchain.rell.base.compiler.base.core.C_CompilerPass
import net.postchain.rell.base.compiler.base.core.C_DefinitionType
import net.postchain.rell.base.compiler.base.core.C_IdeSymbolInfo
import net.postchain.rell.base.compiler.base.expr.C_ExprUtils
import net.postchain.rell.base.compiler.base.utils.C_LateGetter
import net.postchain.rell.base.compiler.base.utils.C_LateInit
import net.postchain.rell.base.lib.type.R_OperationType
import net.postchain.rell.base.model.expr.R_AttributeDefaultValueExpr
import net.postchain.rell.base.model.expr.R_CreateExpr
import net.postchain.rell.base.model.expr.R_CreateExprAttr
import net.postchain.rell.base.model.expr.R_Expr
import net.postchain.rell.base.runtime.*
import net.postchain.rell.base.runtime.utils.Rt_Utils
import net.postchain.rell.base.runtime.utils.toGtv
import net.postchain.rell.base.utils.*
import net.postchain.rell.base.utils.doc.DocDefinition
import net.postchain.rell.base.utils.doc.DocSymbol

sealed class R_KeyIndex(attribs: List<R_Name>) {
    val attribs = attribs.toImmList()

    fun toMetaGtv(): Gtv {
        return mapOf(
                "attributes" to attribs.map { it.str }.toGtv()
        ).toGtv()
    }
}

class R_Key(attribs: List<R_Name>): R_KeyIndex(attribs)
class R_Index(attribs: List<R_Name>): R_KeyIndex(attribs)

class R_EntityFlags(
        val isObject: Boolean,
        val canCreate: Boolean,
        val canUpdate: Boolean,
        val canDelete: Boolean,
        val gtv: Boolean,
        val log: Boolean
)

class R_EntityBody(
        keys: List<R_Key>,
        indexes: List<R_Index>,
        attributes: Map<R_Name, R_Attribute>
) {
    val keys = keys.toImmList()
    val indexes = indexes.toImmList()
    val attributes = attributes.toImmMap()
}

class R_ExternalEntity(val chain: R_ExternalChainRef, val metaCheck: Boolean)

class R_EntityDefinition(
    base: R_DefinitionBase,
    defType: C_DefinitionType,
    val rName: R_Name,
    val flags: R_EntityFlags,
    val sqlMapping: R_EntitySqlMapping,
    val external: R_ExternalEntity?,
): R_Definition(base) {
    val mountName = sqlMapping.mountName
    val metaName = sqlMapping.metaName

    val type = R_EntityType(this)

    val mirrorStructs = R_MirrorStructs(base, defType, type)

    private val bodyLate = C_LateInit(C_CompilerPass.MEMBERS, ERROR_BODY)

    val keys: List<R_Key> get() = bodyLate.get().keys
    val indexes: List<R_Index> get() = bodyLate.get().indexes
    val attributes: Map<R_Name, R_Attribute> get() = bodyLate.get().attributes

    val strAttributes: Map<String, R_Attribute> by lazy {
        attributes.mapKeys { it.key.str }.toImmMap()
    }

    fun setBody(body: R_EntityBody) {
        bodyLate.set(body)
    }

    fun attribute(name: String): R_Attribute {
        val attr = strAttributes[name]
        return attr ?: throw IllegalStateException("Entity '$appLevelName' has no attribute '$name'")
    }

    override fun toMetaGtv() = toMetaGtv(true)

    fun toMetaGtv(full: Boolean): Gtv {
        val map = mutableMapOf(
            "mount" to mountName.str().toGtv(),
            "attributes" to attributes.values.map { it.toMetaGtv() }.toGtv(),
        )

        if (full) {
            map["log"] = flags.log.toGtv()
            map["keys"] = keys.map { it.toMetaGtv() }.toGtv()
            map["indexes"] = indexes.map { it.toMetaGtv() }.toGtv()
        }

        return map.toGtv()
    }

    override fun getDocMember(name: String): DocDefinition? {
        val attr = attributes[R_Name.of(name)]
        return attr
    }

    companion object {
        private val ERROR_BODY = R_EntityBody(keys = listOf(), indexes = listOf(), attributes = mapOf())
    }
}

class R_ObjectDefinition(base: R_DefinitionBase, val rEntity: R_EntityDefinition): R_Definition(base) {
    val type = R_ObjectType(this)

    fun insert(frame: Rt_CallFrame) {
        val createExprAttrs = rEntity.attributes.values.map {
            val expr = R_AttributeDefaultValueExpr(it, null, initFrameGetter)
            R_CreateExprAttr(it, expr)
        }

        val createAttrs = createExprAttrs.map { it.attr }
        val createRecord = createExprAttrs.map { it.evaluate(frame) }
        val createValues = R_CreateExpr.CreateValues(createAttrs, immListOf(createRecord))

        val sql = R_CreateExpr.buildSql(frame.defCtx.sqlCtx, rEntity, createValues, "0")
        sql.execute(frame.sqlExec)
    }

    override fun toMetaGtv() = rEntity.toMetaGtv(false)

    override fun getDocMember(name: String): DocDefinition? {
        return rEntity.getDocMember(name)
    }
}

class R_StructFlags(
        val typeFlags: R_TypeFlags,
        val cyclic: Boolean,
        val infinite: Boolean
)

class R_Struct(
    val name: String,
    val typeMetaGtv: Gtv,
    val initFrameGetter: C_LateGetter<R_CallFrame>,
    val mirrorStructs: R_MirrorStructs?,
) {
    private val bodyLate = LateInit(ERROR_BODY)
    private val flagsLate = LateInit(ERROR_STRUCT_FLAGS)

    val attributes: Map<R_Name, R_Attribute> get() = bodyLate.get().attrMap
    val attributesList: List<R_Attribute> get() = bodyLate.get().attrList
    val flags: R_StructFlags get() = flagsLate.get()

    val strAttributes: Map<String, R_Attribute> by lazy {
        attributes.mapKeys { it.key.str }.toImmMap()
    }

    val type = R_StructType(this)
    val virtualType = R_VirtualStructType(type)

    fun setAttributes(attrs: Map<R_Name, R_Attribute>) {
        val attrsList = attrs.values.toList()
        attrsList.withIndex().forEach { (idx, attr) -> checkEquals(attr.index, idx) }
        val attrMutable = attrs.values.any { it.mutable }
        bodyLate.set(R_StructBody(attrs, attrsList, attrMutable))
    }

    fun setFlags(flags: R_StructFlags) {
        flagsLate.set(flags)
    }

    fun isDirectlyMutable() = bodyLate.get().attrMutable

    fun toMetaGtv(): Gtv {
        return mapOf(
            "attributes" to attributesList.map { it.toMetaGtv() }.toGtv()
        ).toGtv()
    }

    override fun toString() = name

    private class R_StructBody(
            val attrMap: Map<R_Name, R_Attribute>,
            val attrList: List<R_Attribute>,
            val attrMutable: Boolean
    )

    companion object {
        private val ERROR_BODY = R_StructBody(attrMap = mapOf(), attrList = listOf(), attrMutable = false)

        private val ERROR_TYPE_FLAGS = R_TypeFlags(
                mutable = false,
                gtv = R_GtvCompatibility(true, true),
                virtualable = true,
                pure = true
        )

        private val ERROR_STRUCT_FLAGS = R_StructFlags(typeFlags = ERROR_TYPE_FLAGS, cyclic = false, infinite = false)
    }
}

class R_MirrorStructs(
    defBase: R_DefinitionBase,
    defType: C_DefinitionType,
    val innerType: R_Type,
) {
    val immutable = createStruct(defBase, defType, false)
    val mutable = createStruct(defBase, defType, true)

    val operation: R_OperationDefinition? = (innerType as? R_OperationType)?.rOperation

    fun getStruct(mutable: Boolean) = if (mutable) this.mutable else this.immutable

    private fun createStruct(
        defBase: R_DefinitionBase,
        defType: C_DefinitionType,
        mutable: Boolean,
    ): R_Struct {
        val mutableStr = if (mutable) "mutable " else ""
        val structName = "struct<$mutableStr${defBase.defName.appLevelName}>"

        val structMetaGtv = mapOf(
            "type" to "struct".toGtv(),
            "definition_type" to defType.name.toGtv(),
            "definition" to defBase.defName.appLevelName.toGtv(),
            "mutable" to mutable.toGtv(),
        ).toGtv()

        return R_Struct(structName, structMetaGtv, defBase.initFrameGetter, mirrorStructs = this)
    }
}

class R_StructDefinition(base: R_DefinitionBase, val struct: R_Struct): R_Definition(base) {
    val type = struct.type

    val hasDefaultConstructor: Boolean by lazy {
        struct.attributesList.all { it.expr != null }
    }

    override fun toMetaGtv() = struct.toMetaGtv()

    override fun getDocMember(name: String): DocDefinition? {
        val attr = struct.strAttributes[name]
        return attr
    }
}

class R_EnumAttr(
    val rName: R_Name,
    val value: Int,
    val ideInfo: C_IdeSymbolInfo,
): DocDefinition {
    val name = rName.str

    override val docSymbol: DocSymbol = ideInfo.getIdeInfo().doc ?: DocSymbol.NONE

    fun toMetaGtv() = mapOf(
        "name" to name.toGtv(),
    ).toGtv()
}

class R_EnumDefinition(
    base: R_DefinitionBase,
    val attrs: List<R_EnumAttr>,
): R_Definition(base) {
    val type = R_EnumType(this)

    private val attrMap = attrs.associateBy { it.name }.toImmMap()

    fun attr(name: String): R_EnumAttr? {
        return attrMap[name]
    }

    fun attr(value: Long): R_EnumAttr? {
        return if (value < 0 || value >= attrs.size) null else {
            attrs[value.toInt()]
        }
    }

    fun values(): List<Rt_Value> = type.values

    override fun toMetaGtv(): Gtv {
        return mapOf(
            "values" to attrs.map { it.toMetaGtv() }.toGtv(),
        ).toGtv()
    }

    override fun getDocMember(name: String): DocDefinition? {
        val attr = attrMap[name]
        return attr
    }
}

class R_GlobalConstantId(
        val index: Int,
        val app: R_AppUid,
        val module: R_ModuleKey,
        val appLevelName: String,
        private val moduleLevelName: String
) {
    fun strCode() = "$index:$module:$moduleLevelName"

    override fun toString() = "$index:$app:$module:$moduleLevelName"

    // not overriding equals() and hashCode() on purpose
}

class R_GlobalConstantBody(val type: R_Type, val expr: R_Expr, val value: Rt_Value?) {
    companion object {
        val ERROR = R_GlobalConstantBody(R_CtErrorType, C_ExprUtils.ERROR_R_EXPR, null)
    }
}

class R_GlobalConstantDefinition(
        base: R_DefinitionBase,
        val constId: R_GlobalConstantId,
        private val filePos: R_FilePos,
        private val bodyGetter: C_LateGetter<R_GlobalConstantBody>
): R_Definition(base) {
    fun evaluate(exeCtx: Rt_ExecutionContext): Rt_Value {
        val body = bodyGetter.get()
        return if (body.value != null) {
            body.value
        } else {
            val defCtx = Rt_DefinitionContext(exeCtx, false, defId)
            Rt_Utils.evaluateInNewFrame(defCtx, null, body.expr, filePos, initFrameGetter)
        }
    }

    override fun toMetaGtv(): Gtv {
        val body = bodyGetter.get()
        val map = mutableMapOf(
                "type" to body.type.toMetaGtv()
        )

        val gtv = valueToGtv(body)
        if (gtv != null) {
            map["value"] = gtv
        }

        return map.toGtv()
    }

    private fun valueToGtv(body: R_GlobalConstantBody): Gtv? {
        body.value ?: return null

        val type = body.value.type()
        if (!type.completeFlags().gtv.toGtv) return null

        return try {
            type.rtToGtv(body.value, true)
        } catch (e: Throwable) {
            null
        }
    }
}
