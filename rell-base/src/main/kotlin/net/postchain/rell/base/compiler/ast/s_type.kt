/*
 * Copyright (C) 2023 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.base.compiler.ast

import net.postchain.rell.base.compiler.base.core.C_CompilerPass
import net.postchain.rell.base.compiler.base.core.C_DefinitionContext
import net.postchain.rell.base.compiler.base.core.C_IdeSymbolDef
import net.postchain.rell.base.compiler.base.core.C_Types
import net.postchain.rell.base.compiler.base.expr.C_ExprContext
import net.postchain.rell.base.compiler.base.namespace.C_NamespaceMemberTag
import net.postchain.rell.base.compiler.base.utils.C_Error
import net.postchain.rell.base.compiler.base.utils.toCodeMsg
import net.postchain.rell.base.model.*
import net.postchain.rell.base.utils.ide.IdeSymbolCategory
import net.postchain.rell.base.utils.ide.IdeSymbolKind
import net.postchain.rell.base.utils.immListOf
import net.postchain.rell.base.utils.mapNotNullAllOrNull

sealed class S_Type(val pos: S_Pos) {
    protected abstract fun compile0(ctx: C_DefinitionContext): R_Type

    fun compile(ctx: C_DefinitionContext): R_Type {
        return ctx.msgCtx.consumeError { compile0(ctx) } ?: R_CtErrorType
    }

    fun compile(ctx: C_ExprContext): R_Type {
        ctx.executor.checkPass(C_CompilerPass.EXPRESSIONS)
        return compile(ctx.defCtx)
    }

    fun compileOpt(ctx: C_DefinitionContext): R_Type? {
        return ctx.msgCtx.consumeError { compile0(ctx) }
    }

    open fun compileMirrorStructType(ctx: C_DefinitionContext, mutable: Boolean): R_StructType? {
        val rParamType = compileOpt(ctx)
        return if (rParamType == null) null else compileMirrorStructType0(ctx, rParamType, mutable)
    }

    protected fun compileMirrorStructType0(ctx: C_DefinitionContext, rParamType: R_Type, mutable: Boolean): R_StructType? {
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
    override fun compile0(ctx: C_DefinitionContext): R_Type {
        val nameHand = name.compile(ctx)
        val typeDef = ctx.nsCtx.getType(nameHand)
        return typeDef?.compileType(ctx.appCtx, name.pos, immListOf()) ?: R_CtErrorType
    }

    override fun compileMirrorStructType(ctx: C_DefinitionContext, mutable: Boolean): R_StructType? {
        val nameHand = name.compile(ctx)

        val nameRes = ctx.nsCtx.resolveName(nameHand, C_NamespaceMemberTag.MIRRORABLE)

        val obj = nameRes.getObject(error = false, unknownInfo = false)
        if (obj != null) {
            val rParamType = obj.rEntity.type
            return compileMirrorStructType0(ctx, rParamType, mutable)
        }

        val rOp = nameRes.getOperation(error = false, unknownInfo = false)
        if (rOp != null) {
            val rStruct = rOp.mirrorStructs.getStruct(mutable)
            return rStruct.type
        }

        val typeDef = nameRes.getType()
        if (typeDef != null) {
            val rParamType = typeDef.compileType(ctx.appCtx, name.pos, immListOf())
            return compileMirrorStructType0(ctx, rParamType, mutable)
        }

        return null
    }
}

class S_GenericType(private val name: S_QualifiedName, private val args: List<S_Type>): S_Type(name.pos) {
    override fun compile0(ctx: C_DefinitionContext): R_Type {
        val rPosArgs = args.mapNotNullAllOrNull {
            val rType = it.compileOpt(ctx)
            if (rType == null) null else S_PosValue(it.pos, rType)
        }

        val nameHand = name.compile(ctx)

        val typeDef = ctx.nsCtx.getType(nameHand)
        if (typeDef == null || rPosArgs == null) {
            return R_CtErrorType
        }

        return typeDef.compileType(ctx.appCtx, name.pos, rPosArgs)
    }
}

class S_NullableType(pos: S_Pos, val valueType: S_Type): S_Type(pos) {
    override fun compile0(ctx: C_DefinitionContext): R_Type {
        val rValueType = valueType.compile(ctx)
        if (rValueType is R_NullableType) throw C_Error.stop(pos, "type_nullable_nullable", "Nullable nullable (T??) is not allowed")
        return R_NullableType(rValueType)
    }
}

class S_TupleType(pos: S_Pos, private val fields: List<S_NameOptValue<S_Type>>): S_Type(pos) {
    override fun compile0(ctx: C_DefinitionContext): R_Type {
        val names = mutableSetOf<String>()

        val typeIdeId = ctx.tupleIdeId()

        val rFields = fields.map { (name, type) ->
            val nameHand = name?.compile(ctx)

            val fieldName = if (nameHand == null) null else {
                val cName = nameHand.name
                if (!names.add(cName.str)) {
                    throw C_Error.stop(cName.pos, "type_tuple_dupname:$cName", "Duplicate field: '$cName'")
                }

                val attrIdeId = typeIdeId.appendMember(IdeSymbolCategory.ATTRIBUTE, nameHand.rName)
                val ideDef = C_IdeSymbolDef.make(IdeSymbolKind.MEM_TUPLE_ATTR, nameHand.pos.idePath(), attrIdeId)
                nameHand.setIdeInfo(ideDef.defInfo)
                R_IdeName(nameHand.rName, ideDef.refInfo)
            }

            val rType = C_Types.checkNotUnit(ctx.msgCtx, type.pos, type.compile(ctx), nameHand?.str) {
                "tuple_field" toCodeMsg "tuple field"
            }

            R_TupleField(fieldName, rType)
        }

        return R_TupleType(rFields)
    }
}

class S_VirtualType(pos: S_Pos, val innerType: S_Type): S_Type(pos) {
    override fun compile0(ctx: C_DefinitionContext): R_Type {
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
        fun virtualType(type: R_Type): R_Type? {
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
    override fun compile0(ctx: C_DefinitionContext): R_Type {
        return paramType.compileMirrorStructType(ctx, mutable) ?: R_CtErrorType
    }
}

class S_FunctionType(pos: S_Pos, val params: List<S_Type>, val result: S_Type): S_Type(pos) {
    override fun compile0(ctx: C_DefinitionContext): R_Type {
        val rParams = params.map {
            C_Types.checkNotUnit(ctx.msgCtx, it.pos, it.compile(ctx), null) { "fntype_param" toCodeMsg "parameter" }
        }

        val rResult = result.compile(ctx)
        return R_FunctionType(rParams, rResult)
    }
}
