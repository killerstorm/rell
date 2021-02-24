package net.postchain.rell.compiler

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.model.*

abstract class C_TypeHint {
    abstract fun getListElementType(): R_Type?
    abstract fun getSetElementType(): R_Type?
    abstract fun getMapKeyValueTypes(): R_MapKeyValueTypes?

    companion object {
        val NONE: C_TypeHint = C_TypeHint_None

        fun ofType(type: R_Type?): C_TypeHint = if (type == null) NONE else C_TypeHint_ExactType(type)
        fun collection(elementType: R_Type): C_TypeHint = C_TypeHint_Collection(elementType)
        fun map(keyValueTypes: R_MapKeyValueTypes): C_TypeHint = C_TypeHint_Map(keyValueTypes)
    }
}

private object C_TypeHint_None: C_TypeHint() {
    override fun getListElementType() = null
    override fun getSetElementType() = null
    override fun getMapKeyValueTypes() = null
}

private class C_TypeHint_ExactType(type: R_Type): C_TypeHint() {
    private val baseType: R_Type = (type as? R_NullableType)?.valueType ?: type

    override fun getListElementType() = (baseType as? R_ListType)?.elementType
    override fun getSetElementType() = (baseType as? R_SetType)?.elementType
    override fun getMapKeyValueTypes() = (baseType as? R_MapType)?.keyValueTypes
}

private class C_TypeHint_Collection(private val elementType: R_Type): C_TypeHint() {
    override fun getListElementType() = elementType
    override fun getSetElementType() = elementType
    override fun getMapKeyValueTypes() = null
}

private class C_TypeHint_Map(private val keyValueTypes: R_MapKeyValueTypes): C_TypeHint() {
    override fun getListElementType() = null
    override fun getSetElementType() = null
    override fun getMapKeyValueTypes() = keyValueTypes
}

object C_Types {
    fun match(dstType: R_Type, srcType: R_Type, errPos: S_Pos, errCode: String, errMsg: String) {
        if (dstType.isNotError() && srcType.isNotError() && !dstType.isAssignableFrom(srcType)) {
            throw C_Errors.errTypeMismatch(errPos, srcType, dstType, errCode, errMsg)
        }
    }

    fun matchOpt(msgCtx: C_MessageContext, dstType: R_Type, srcType: R_Type, errPos: S_Pos, errCode: String, errMsg: String): Boolean {
        return msgCtx.consumeError { match(dstType, srcType, errPos, errCode, errMsg); true } ?: false
    }

    fun adapt(dstType: R_Type, srcType: R_Type, errPos: S_Pos, errCode: String, errMsg: String): R_TypeAdapter {
        val adapter = dstType.getTypeAdapter(srcType)
        if (adapter == null) {
            throw C_Errors.errTypeMismatch(errPos, srcType, dstType, errCode, errMsg)
        }
        return adapter
    }

    fun adaptSafe(
            msgCtx: C_MessageContext,
            dstType: R_Type,
            srcType: R_Type,
            errPos: S_Pos,
            errCode: String,
            errMsg: String
    ): R_TypeAdapter {
        val adapter = dstType.getTypeAdapter(srcType)
        return if (adapter != null) adapter else {
            C_Errors.errTypeMismatch(msgCtx, errPos, srcType, dstType, errCode, errMsg)
            R_TypeAdapter_Direct
        }
    }

    fun commonType(a: R_Type, b: R_Type, errPos: S_Pos, errCode: String, errMsg: String): R_Type {
        val res = commonTypeOpt(a, b)
        return res ?: throw C_Errors.errTypeMismatch(errPos, b, a, errCode, errMsg)
    }

    fun commonTypeOpt(a: R_Type, b: R_Type): R_Type? {
        return R_Type.commonTypeOpt(a, b)
    }

    fun commonTypesOpt(a: R_MapKeyValueTypes, b: R_MapKeyValueTypes): R_MapKeyValueTypes? {
        val key = commonTypeOpt(a.key, b.key)
        if (key == null) return null
        val value = commonTypeOpt(a.value, b.value)
        return if (value == null) null else R_MapKeyValueTypes(key, value)
    }

    fun toNullable(type: R_Type): R_Type {
        return if (type is R_NullableType) type else R_NullableType(type)
    }
}
