/*
 * Copyright (C) 2020 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.compiler.ast

import net.postchain.rell.compiler.base.core.C_CompilerPass
import net.postchain.rell.compiler.base.core.C_NamespaceContext
import net.postchain.rell.compiler.base.core.C_Types
import net.postchain.rell.compiler.base.def.C_TypeDef_Generic
import net.postchain.rell.compiler.base.def.C_TypeDef_Normal
import net.postchain.rell.compiler.base.expr.C_ExprContext
import net.postchain.rell.compiler.base.utils.C_Error
import net.postchain.rell.compiler.base.utils.toCodeMsg
import net.postchain.rell.model.*
import net.postchain.rell.tools.api.IdeSymbolInfo

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
        return if (rParamType == null) null else compileMirrorStructType0(ctx, rParamType, mutable)
    }

    protected fun compileMirrorStructType0(ctx: C_NamespaceContext, rParamType: R_Type, mutable: Boolean): R_StructType? {
        return if (rParamType is R_EntityType) {
            val rEntity = rParamType.rEntity
            val rStruct = rEntity.mirrorStructs.getStruct(mutable)
            rStruct.type
        } else {
            if (rParamType.isNotError()) {
                ctx.msgCtx.error(pos, "type:struct:bad_type:${rParamType.strCode()}",
                        "Invalid struct parameter type: ${rParamType.str()}")
            }
            null
        }
    }
}

class S_NameType(private val name: S_QualifiedName): S_Type(name.pos) {
    override fun compile0(ctx: C_NamespaceContext): R_Type {
        val nameHand = name.compile(ctx)
        val typeDef = ctx.getType(nameHand)
        return typeDef.toRType(ctx.msgCtx, name.pos)
    }

    override fun compileMirrorStructType(ctx: C_NamespaceContext, mutable: Boolean): R_StructType? {
        val nameHand = name.compile(ctx)
        val typeRes = ctx.getTypeOpt(nameHand)

        val typeDef = typeRes?.getDef()
        var rParamType = typeDef?.toRType(ctx.msgCtx, name.pos)

        if (rParamType == null) {
            val objRes = ctx.getObjectOpt(nameHand)
            if (objRes != null) {
                val obj = objRes.getDef()
                rParamType = obj.rEntity.type
            }
        }

        if (rParamType != null) {
            return compileMirrorStructType0(ctx, rParamType, mutable)
        }

        val opRes = ctx.getOperationOpt(nameHand)
        val rOp = opRes?.getDef()
        if (rOp != null) {
            val rStruct = rOp.mirrorStructs.getStruct(mutable)
            return rStruct.type
        }

        ctx.getType(nameHand) // Must throw an exception, as type has been already checked.
        return compileMirrorStructType0(ctx, R_CtErrorType, mutable)
    }
}

class S_GenericType(private val name: S_QualifiedName, private val args: List<S_Type>): S_Type(name.pos) {
    override fun compile0(ctx: C_NamespaceContext): R_Type {
        val rPosArgs = args.mapNotNull {
            val rType = it.compileOpt(ctx)
            if (rType == null) null else S_PosValue(it.pos, rType)
        }

        val nameHand = name.compile(ctx)
        return when (val typeDef = ctx.getType(nameHand)) {
            is C_TypeDef_Normal -> {
                val type = typeDef.type
                ctx.msgCtx.error(name.pos, "type:not_generic:${type.strCode()}", "Type '${type.str()}' is not generic")
                R_CtErrorType
            }
            is C_TypeDef_Generic -> {
                if (rPosArgs.size != args.size) {
                    // Some args failed to compile.
                    R_CtErrorType
                } else {
                    typeDef.type.compileType(ctx, name.pos, rPosArgs)
                }
            }
        }
    }
}

class S_NullableType(pos: S_Pos, val valueType: S_Type): S_Type(pos) {
    override fun compile0(ctx: C_NamespaceContext): R_Type {
        val rValueType = valueType.compile(ctx)
        if (rValueType is R_NullableType) throw C_Error.stop(pos, "type_nullable_nullable", "Nullable nullable (T??) is not allowed")
        return R_NullableType(rValueType)
    }
}

class S_TupleType(pos: S_Pos, private val fields: List<S_NameOptValue<S_Type>>): S_Type(pos) {
    override fun compile0(ctx: C_NamespaceContext): R_Type {
        val names = mutableSetOf<String>()

        val rFields = fields.map { (name, type) ->
            val ideInfo = IdeSymbolInfo.MEM_TUPLE_FIELD
            val cName = name?.compile(ctx, ideInfo)
            if (cName != null && !names.add(cName.str)) {
                throw C_Error.stop(cName.pos, "type_tuple_dupname:$cName", "Duplicate field: '$cName'")
            }
            val rType = C_Types.checkNotUnit(ctx.msgCtx, type.pos, type.compile(ctx), cName?.str) {
                "tuple_field" toCodeMsg "tuple field"
            }
            R_TupleField(cName?.rName, rType, ideInfo)
        }

        return R_TupleType(rFields)
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
            C_Types.checkNotUnit(ctx.msgCtx, it.pos, it.compile(ctx), null) { "fntype_param" toCodeMsg "parameter" }
        }

        val rResult = result.compile(ctx)
        return R_FunctionType(rParams, rResult)
    }
}
