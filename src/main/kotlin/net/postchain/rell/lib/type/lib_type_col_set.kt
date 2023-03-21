/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.ast.S_Pos
import net.postchain.rell.compiler.ast.S_PosValue
import net.postchain.rell.compiler.base.core.C_DefinitionContext
import net.postchain.rell.compiler.base.core.C_TypeHint
import net.postchain.rell.compiler.base.def.C_GenericType
import net.postchain.rell.compiler.base.def.C_GlobalFunction
import net.postchain.rell.compiler.base.expr.C_TypeValueMember
import net.postchain.rell.compiler.base.namespace.C_SysNsProtoBuilder
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.compiler.base.utils.C_MemberFuncTable
import net.postchain.rell.compiler.base.utils.C_Utils
import net.postchain.rell.model.R_ListType
import net.postchain.rell.model.R_SetType
import net.postchain.rell.model.R_Type
import net.postchain.rell.model.R_VirtualSetType
import net.postchain.rell.model.expr.R_CollectionKind_Set
import net.postchain.rell.utils.checkEquals

object C_Lib_Type_Set {
    const val TYPE_NAME = "set"
    val DEF_NAME = C_LibUtils.defName(TYPE_NAME)

    fun getConstructorFn(setType: R_SetType): C_GlobalFunction {
        return C_CollectionConstructorFunction(C_CollectionKindAdapter_Set, setType.elementType)
    }

    fun getValueMembers(setType: R_SetType): List<C_TypeValueMember> {
        val fns = getMemberFns(setType)
        return C_LibUtils.makeValueMembers(setType, fns)
    }

    private fun getMemberFns(setType: R_SetType): C_MemberFuncTable {
        val listType = R_ListType(setType.elementType)
        val b = C_LibUtils.typeMemFuncBuilder(setType)
        C_Lib_Type_Collection.bindMemberFns(b, listType)
        return b.build()
    }

    fun bind(b: C_SysNsProtoBuilder) {
        b.addType(TYPE_NAME, C_GenericType_Set)
    }
}

private object C_GenericType_Set: C_GenericType(C_Lib_Type_Set.TYPE_NAME, C_Lib_Type_Set.DEF_NAME, 1) {
    override val rawConstructorFn: C_GlobalFunction = C_CollectionConstructorFunction(C_CollectionKindAdapter_Set, null)

    override fun compileType0(ctx: C_DefinitionContext, pos: S_Pos, args: List<S_PosValue<R_Type>>): R_Type {
        checkEquals(args.size, 1)
        val elemEntry = args[0]
        C_CollectionKindAdapter_Set.checkElementType(ctx, pos, elemEntry.pos, elemEntry.value)
        return R_SetType(elemEntry.value)
    }
}

private object C_CollectionKindAdapter_Set: C_CollectionKindAdapter(C_Lib_Type_Set.TYPE_NAME) {
    override fun elementTypeFromTypeHint(typeHint: C_TypeHint) = typeHint.getSetElementType()
    override fun makeKind(rElementType: R_Type) = R_CollectionKind_Set(R_SetType(rElementType))

    override fun checkElementType0(ctx: C_DefinitionContext, pos: S_Pos, elemTypePos: S_Pos, rElemType: R_Type) {
        C_Utils.checkSetElementType(ctx, elemTypePos, rElemType)
    }
}

object C_Lib_Type_VirtualSet {
    fun getValueMembers(type: R_VirtualSetType): List<C_TypeValueMember> {
        val b = C_LibUtils.typeMemFuncBuilder(type)
        C_Lib_Type_VirtualCollection.bindMemberFns(b, type.innerType)
        val fns = b.build()
        return C_LibUtils.makeValueMembers(type, fns)
    }
}
