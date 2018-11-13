package net.postchain.rell.parser

import net.postchain.rell.model.*

sealed class S_Type {
    internal abstract fun compile(ctx: CtModuleContext): RType
    internal fun compile(ctx: CtExprContext): RType = compile(ctx.entCtx.modCtx)
}

class S_NameType(val name: String): S_Type() {
    override fun compile(ctx: CtModuleContext): RType = ctx.getType(name)
}

class S_NullableType(val valueType: S_Type): S_Type() {
    override fun compile(ctx: CtModuleContext): RType {
        val rValueType = valueType.compile(ctx)
        if (rValueType is RNullableType) throw CtError("type_nullable_nullable", "Nullable nullable (T??) is not allowed")
        return RNullableType(rValueType)
    }
}

class S_TupleType(val fields: List<Pair<String?, S_Type>>): S_Type() {
    override fun compile(ctx: CtModuleContext): RType {
        val names = mutableSetOf<String>()
        for ((name, _) in fields) {
            if (name != null && !names.add(name)) {
                throw CtError("type_tuple_dupname:$name", "Duplicated field name: '$name'")
            }
        }

        val rFields = fields.map { RTupleField(it.first, it.second.compile(ctx)) }
        return RTupleType(rFields)
    }
}

class S_ListType(val element: S_Type): S_Type() {
    override fun compile(ctx: CtModuleContext): RType {
        val rElement = element.compile(ctx)
        if (rElement == RUnitType) TODO()
        return RListType(rElement)
    }
}

class S_SetType(val element: S_Type): S_Type() {
    override fun compile(ctx: CtModuleContext): RType {
        val rElement = element.compile(ctx)
        if (rElement == RUnitType) TODO()
        return RSetType(rElement)
    }
}

class S_MapType(val key: S_Type, val value: S_Type): S_Type() {
    override fun compile(ctx: CtModuleContext): RType {
        val rKey = key.compile(ctx)
        val rValue = value.compile(ctx)
        if (rKey == RUnitType) TODO()
        if (rValue == RUnitType) TODO()
        return RMapType(rKey, rValue)
    }
}
