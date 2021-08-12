/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.*
import net.postchain.rell.model.*

sealed class S_Type(val pos: S_Pos) {
    protected abstract fun compile0(ctx: C_NamespaceContext): R_Type

    fun compile(ctx: C_NamespaceContext): R_Type {
        return ctx.msgCtx.consumeError { compile0(ctx) } ?: R_CtErrorType
    }

    fun compile(ctx: C_ExprContext): R_Type {
        ctx.executor.checkPass(C_CompilerPass.EXPRESSIONS)
        return compile(ctx.nsCtx)
    }

    fun compileOpt(ctx: C_NamespaceContext): R_Type? {
        return ctx.msgCtx.consumeError { compile0(ctx) }
    }

    open fun compileMirrorStructType(ctx: C_NamespaceContext, mutable: Boolean): R_StructType? {
        val rParamType = compileOpt(ctx)
        return if (rParamType == null) null else compileMirrorStructType0(rParamType, mutable)
    }

    protected fun compileMirrorStructType0(rParamType: R_Type, mutable: Boolean): R_StructType {
        return if (rParamType is R_EntityType) {
            val rEntity = rParamType.rEntity
            val rStruct = rEntity.mirrorStructs.getStruct(mutable)
            rStruct.type
        } else {
            throw C_Error.more(pos, "type:struct:bad_type:$rParamType", "Invalid struct parameter type: $rParamType")
        }
    }
}

class S_NameType(val names: List<S_Name>): S_Type(names[0].pos) {
    override fun compile0(ctx: C_NamespaceContext): R_Type = ctx.getType(names)

    override fun compileMirrorStructType(ctx: C_NamespaceContext, mutable: Boolean): R_StructType? {
        var rParamType = ctx.getTypeOpt(names)

        if (rParamType == null) {
            val obj = ctx.getObjectOpt(names)
            if (obj != null) {
                rParamType = obj.rEntity.type
            }
        }

        if (rParamType != null) {
            return compileMirrorStructType0(rParamType, mutable)
        }

        val rOp = ctx.getOperationOpt(names)
        if (rOp != null) {
            val rStruct = rOp.mirrorStructs.getStruct(mutable)
            return rStruct.type
        }

        return super.compileMirrorStructType(ctx, mutable) // Reports compilation error.
    }
}

class S_NullableType(pos: S_Pos, val valueType: S_Type): S_Type(pos) {
    override fun compile0(ctx: C_NamespaceContext): R_Type {
        val rValueType = valueType.compile(ctx)
        if (rValueType is R_NullableType) throw C_Error.stop(pos, "type_nullable_nullable", "Nullable nullable (T??) is not allowed")
        return R_NullableType(rValueType)
    }
}

class S_TupleType(pos: S_Pos, val fields: List<S_NameOptValue<S_Type>>): S_Type(pos) {
    override fun compile0(ctx: C_NamespaceContext): R_Type {
        val names = mutableSetOf<String>()
        for ((name, _) in fields) {
            val nameStr = name?.str
            if (nameStr != null && !names.add(nameStr)) {
                throw C_Error.stop(name.pos, "type_tuple_dupname:$nameStr", "Duplicate field: '$nameStr'")
            }
        }

        val rFields = fields.map { (name, type) ->
            val rType = C_Types.checkNotUnit(ctx.msgCtx, type.pos, type.compile(ctx), name?.str) {
                "tuple_field" to "tuple field"
            }
            R_TupleField(name?.str, rType)
        }

        return R_TupleType(rFields)
    }
}

sealed class S_CollectionType(pos: S_Pos, private val element: S_Type): S_Type(pos) {
    protected fun compileElementType(ctx: C_NamespaceContext, collectionKind: String): R_Type {
        val rElementType0 = element.compile(ctx)
        return C_Types.checkNotUnit(ctx.msgCtx, element.pos, rElementType0, null) {
            "$collectionKind:elem" to "$collectionKind element"
        }
    }
}

class S_ListType(pos: S_Pos, element: S_Type): S_CollectionType(pos, element) {
    override fun compile0(ctx: C_NamespaceContext): R_Type {
        val rElementType = compileElementType(ctx, "list")
        return R_ListType(rElementType)
    }
}

class S_SetType(pos: S_Pos, element: S_Type): S_CollectionType(pos, element) {
    override fun compile0(ctx: C_NamespaceContext): R_Type {
        val rElementType = compileElementType(ctx, "set")
        C_Utils.checkSetElementType(ctx, pos, rElementType)
        return R_SetType(rElementType)
    }
}

class S_MapType(pos: S_Pos, val key: S_Type, val value: S_Type): S_Type(pos) {
    override fun compile0(ctx: C_NamespaceContext): R_Type {
        val rKey = compileElementType(ctx, key)
        val rValue = compileElementType(ctx, value)
        C_Utils.checkMapKeyType(ctx, pos, rKey)
        return R_MapType(R_MapKeyValueTypes(rKey, rValue))
    }

    private fun compileElementType(ctx: C_NamespaceContext, type: S_Type): R_Type {
        return C_Types.checkNotUnit(ctx.msgCtx, type.pos, type.compile(ctx), null) { "map_elem" to "map element" }
    }
}

class S_VirtualType(pos: S_Pos, val innerType: S_Type): S_Type(pos) {
    override fun compile0(ctx: C_NamespaceContext): R_Type {
        val rInnerType = innerType.compile(ctx)

        val rType = virtualType(rInnerType)
        if (rType == null) {
            throw errBadInnerType(rInnerType)
        }

        ctx.executor.onPass(C_CompilerPass.VALIDATION) {
            validate(rInnerType)
        }

        return rType
    }

    private fun validate(rInnerType: R_Type) {
        val flags = rInnerType.completeFlags()
        if (!flags.virtualable) {
            throw errBadInnerType(rInnerType)
        }
    }

    private fun errBadInnerType(rInnerType: R_Type): C_Error {
        return C_Error.stop(pos, "type:virtual:bad_inner_type:${rInnerType.name}",
                "Type '${rInnerType.name}' cannot be virtual (allowed types are: list, set, map, struct, tuple)")
    }

    companion object {
        private fun virtualType(type: R_Type): R_Type? {
            return when (type) {
                is R_ListType -> type.virtualType
                is R_SetType -> type.virtualType
                is R_MapType -> type.virtualType
                is R_TupleType -> type.virtualType
                is R_StructType -> type.struct.virtualType
                else -> null
            }
        }

        fun virtualMemberType(type: R_Type): R_Type {
            return when(type) {
                is R_NullableType -> {
                    val subType = virtualMemberType(type.valueType)
                    R_NullableType(subType)
                }
                else -> virtualType(type) ?: type
            }
        }
    }
}

class S_MirrorStructType(pos: S_Pos, val mutable: Boolean, val paramType: S_Type): S_Type(pos) {
    override fun compile0(ctx: C_NamespaceContext): R_Type {
        return paramType.compileMirrorStructType(ctx, mutable) ?: R_CtErrorType
    }
}

class S_FunctionType(pos: S_Pos, val params: List<S_Type>, val result: S_Type): S_Type(pos) {
    override fun compile0(ctx: C_NamespaceContext): R_Type {
        val rParams = params.map {
            C_Types.checkNotUnit(ctx.msgCtx, it.pos, it.compile(ctx), null) { "fntype_param" to "parameter" }
        }

        val rResult = result.compile(ctx)
        return R_FunctionType(rParams, rResult)
    }
}
