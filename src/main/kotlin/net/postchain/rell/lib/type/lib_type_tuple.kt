/*
 * Copyright (C) 2022 ChromaWay AB. See LICENSE for license information.
 */

package net.postchain.rell.lib.type

import net.postchain.rell.compiler.ast.S_VirtualType
import net.postchain.rell.compiler.base.expr.C_AtTypeImplicitAttr
import net.postchain.rell.compiler.base.expr.C_MemberAttr_TupleAttr
import net.postchain.rell.compiler.base.expr.C_TypeValueMember
import net.postchain.rell.compiler.base.expr.C_TypeValueMember_BasicAttr
import net.postchain.rell.compiler.base.utils.C_LibUtils
import net.postchain.rell.model.R_TupleField
import net.postchain.rell.model.R_TupleType
import net.postchain.rell.model.R_Type
import net.postchain.rell.model.R_VirtualTupleType
import net.postchain.rell.model.expr.R_MemberCalculator_TupleAttr
import net.postchain.rell.model.expr.R_MemberCalculator_VirtualTupleAttr

object C_Lib_Type_Tuple {
    fun getValueMembers(type: R_TupleType): List<C_TypeValueMember> {
        val fns = C_LibUtils.typeMemFuncBuilder(type).build()
        val attrMembers = type.fields.mapIndexed { idx, field ->
            val mem = C_MemberAttr_RegularTupleAttr(field.type, idx, field)
            C_TypeValueMember_BasicAttr(mem)
        }
        return C_LibUtils.makeValueMembers(type, fns, attrMembers)
    }

    fun getAtImplicitAttrs(type: R_TupleType): List<C_AtTypeImplicitAttr> {
        val attrMembers = type.fields.mapIndexed { idx, field ->
            val mem = C_MemberAttr_RegularTupleAttr(field.type, idx, field)
            val typeMember = C_TypeValueMember_BasicAttr(mem)
            C_AtTypeImplicitAttr(typeMember, field.type)
        }
        return attrMembers
    }

    private class C_MemberAttr_RegularTupleAttr(type: R_Type, fieldIndex: Int, field: R_TupleField)
        : C_MemberAttr_TupleAttr(type, fieldIndex, field)
    {
        override fun calculator() = R_MemberCalculator_TupleAttr(type, fieldIndex)
    }
}

object C_Lib_Type_VirtualTuple {
    fun getValueMembers(type: R_VirtualTupleType): List<C_TypeValueMember> {
        val fns = C_LibUtils.typeMemFuncBuilder(type)
            .add("to_full", type.innerType, listOf(), C_Lib_Type_Virtual.ToFull)
            .build()

        val attrMembers = type.innerType.fields.mapIndexed { idx, field ->
            val virtualType = S_VirtualType.virtualMemberType(field.type)
            val mem = C_TypeValueMember_VirtualTupleAttr(virtualType, idx, field)
            C_TypeValueMember_BasicAttr(mem)
        }

        return C_LibUtils.makeValueMembers(type, fns, attrMembers)
    }

    private class C_TypeValueMember_VirtualTupleAttr(type: R_Type, fieldIndex: Int, field: R_TupleField)
        : C_MemberAttr_TupleAttr(type, fieldIndex, field)
    {
        override fun calculator() = R_MemberCalculator_VirtualTupleAttr(type, fieldIndex)
    }
}
