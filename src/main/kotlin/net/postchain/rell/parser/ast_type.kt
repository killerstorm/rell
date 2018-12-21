package net.postchain.rell.parser

import net.postchain.rell.model.*

sealed class S_Type {
    internal abstract fun compile(ctx: C_ModuleContext): RType
    internal fun compile(ctx: C_ExprContext): RType = compile(ctx.entCtx.modCtx)

    companion object {
        fun match(dstType: RType, srcType: RType, errPos: S_Pos, errCode: String, errMsg: String) {
            if (!dstType.isAssignableFrom(srcType)) {
                throw C_Utils.errTypeMissmatch(errPos, srcType, dstType, errCode, errMsg)
            }
        }

        fun commonType(a: RType, b: RType, errPos: S_Pos, errCode: String, errMsg: String): RType {
            val res = RType.commonTypeOpt(a, b)
            return res ?: throw C_Utils.errTypeMissmatch(errPos, b, a, errCode, errMsg)
        }
    }
}

class S_NameType(val name: S_Name): S_Type() {
    override fun compile(ctx: C_ModuleContext): RType = ctx.getType(name)
}

class S_NullableType(val pos: S_Pos, val valueType: S_Type): S_Type() {
    override fun compile(ctx: C_ModuleContext): RType {
        val rValueType = valueType.compile(ctx)
        if (rValueType is RNullableType) throw C_Error(pos, "type_nullable_nullable", "Nullable nullable (T??) is not allowed")
        return RNullableType(rValueType)
    }
}

class S_TupleType(val fields: List<Pair<S_Name?, S_Type>>): S_Type() {
    override fun compile(ctx: C_ModuleContext): RType {
        val names = mutableSetOf<String>()
        for ((name, _) in fields) {
            val nameStr = name?.str
            if (nameStr != null && !names.add(nameStr)) {
                throw C_Error(name.pos, "type_tuple_dupname:$nameStr", "Duplicate field: '$nameStr'")
            }
        }

        val rFields = fields.map { (name, type) -> RTupleField(name?.str, type.compile(ctx)) }
        return RTupleType(rFields)
    }
}

class S_ListType(val pos: S_Pos, val element: S_Type): S_Type() {
    override fun compile(ctx: C_ModuleContext): RType {
        val rElement = element.compile(ctx)
        C_Utils.checkUnitType(pos, rElement, "type_list_unit", "Invalid list element type")
        return RListType(rElement)
    }
}

class S_SetType(val pos: S_Pos, val element: S_Type): S_Type() {
    override fun compile(ctx: C_ModuleContext): RType {
        val rElement = element.compile(ctx)
        C_Utils.checkUnitType(pos, rElement, "type_set_unit", "Invalid set element type")
        C_Utils.checkSetElementType(pos, rElement)
        return RSetType(rElement)
    }
}

class S_MapType(val pos: S_Pos, val key: S_Type, val value: S_Type): S_Type() {
    override fun compile(ctx: C_ModuleContext): RType {
        val rKey = key.compile(ctx)
        val rValue = value.compile(ctx)
        C_Utils.checkUnitType(pos, rKey, "type_map_key_unit", "Invalid map key type")
        C_Utils.checkUnitType(pos, rValue, "type_map_value_unit", "Invalid map value type")
        C_Utils.checkMapKeyType(pos, rKey)
        return RMapType(rKey, rValue)
    }
}
